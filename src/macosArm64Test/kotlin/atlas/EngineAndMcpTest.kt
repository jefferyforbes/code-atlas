package atlas

import atlas.bridge.VaultPlanBridge
import atlas.cli.CodeGraphCli
import atlas.config.CacheProfileResolver
import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectureAuditor
import atlas.engine.ScopeSynthesizer
import atlas.extract.FigmaSnapshotExtractor
import atlas.extract.ModuleBoundaryDetector
import atlas.mcp.CodeGraphMcpServer
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid
import platform.posix.symlink
import platform.posix.time
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class EngineAndMcpTest {

    @Test
    fun testVaultPlanBridgeWikilinksCodeSpansAndPlanScope() {
        val codeNodeA = GraphNode(
            nodeId = "proj:src/OrderService.kt#OrderService",
            projectId = "proj",
            fileId = "proj:src/OrderService.kt",
            name = "OrderService",
            qualifiedName = "com.example.OrderService",
            kind = "class",
            moduleId = ":core:order"
        )
        val planMd = """
            ---
            status: active
            scope:
              files:
                - src/OrderService.kt
            ---
            # Order Service Refactor Plan
            See [[Architecture Overview#Order Flow|Order Flow]] and update `OrderService` to validate items.
        """.trimIndent()

        val (nodes, edges) = VaultPlanBridge.extractMarkdownAndPlans(
            projectId = "proj",
            relPath = "plans/order_refactor.md",
            markdownText = planMd,
            indexedNodes = listOf(codeNodeA)
        )

        assertEquals(1, nodes.size)
        val planNode = nodes[0]
        assertEquals("plan_contract", planNode.kind)
        assertTrue(planNode.signature.contains("(active)"))

        assertTrue(edges.any { it.edgeType == "PLAN_SCOPES_FILE" && it.targetNodeId == codeNodeA.nodeId })
        assertTrue(edges.any { it.edgeType == "WIKILINK_REFS" && it.targetNodeId == "proj:vault_ref#Architecture Overview" })
        assertTrue(edges.any { it.edgeType == "CODE_SPAN_REFS" && it.targetNodeId == codeNodeA.nodeId })
    }

    @Test
    fun testModuleBoundaryDetectorSettingsBuildAndKmpExpectActual() {
        val settingsContent = """
            rootProject.name = "sample"
            include(":core:model", ":feature:checkout")
            include("shared:network")
        """.trimIndent()

        val includes = ModuleBoundaryDetector.parseSettingsIncludes(settingsContent)
        assertEquals(listOf(":core:model", ":feature:checkout", ":shared:network"), includes)

        val buildContent = """
            dependencies {
                implementation(project(":core:model"))
                api(projects.shared.network)
            }
        """.trimIndent()
        val (modNodes, modEdges) = ModuleBoundaryDetector.extractFromBuildContent(
            projectId = "proj",
            relPath = "feature/checkout/build.gradle.kts",
            sourceText = buildContent,
            parentModule = ":feature:checkout"
        )
        assertTrue(modNodes.any { it.name == ":feature:checkout" })
        assertTrue(modEdges.any { it.edgeType == "MODULE_DEPENDS_ON" && it.targetNodeId == "proj:module::core:model" })
        assertTrue(modEdges.any { it.edgeType == "MODULE_DEPENDS_ON" && it.targetNodeId == "proj:module::shared:network" })

        val expectNode = GraphNode(
            nodeId = "proj:commonMain/Platform.kt#PlatformLogger",
            projectId = "proj",
            fileId = "proj:commonMain/Platform.kt",
            name = "PlatformLogger",
            qualifiedName = "pkg.PlatformLogger",
            kind = "expect_decl",
            moduleId = ":shared"
        )
        val actualNode = GraphNode(
            nodeId = "proj:macosArm64Main/Platform.kt#PlatformLogger",
            projectId = "proj",
            fileId = "proj:macosArm64Main/Platform.kt",
            name = "PlatformLogger",
            qualifiedName = "pkg.PlatformLogger",
            kind = "actual_decl",
            moduleId = ":shared",
            startLine = 12
        )
        val kmpEdges = ModuleBoundaryDetector.synthesizeKmpExpectActualEdges("proj", listOf(expectNode, actualNode))
        assertEquals(1, kmpEdges.size)
        assertEquals("EXPECT_ACTUAL", kmpEdges[0].edgeType)
        assertEquals(actualNode.nodeId, kmpEdges[0].sourceNodeId)
        assertEquals(expectNode.nodeId, kmpEdges[0].targetNodeId)
    }

    @Test
    fun testCacheProfileResolverModes() {
        val tmpWs = "/tmp/test_atlas_cache_ws_${getpid()}"
        PlatformFiles.mkdirs("$tmpWs/sub")
        PlatformFiles.writeText("$tmpWs/sub/Sample.kt", "package sample")
        symlink(tmpWs, "$tmpWs/sub/loop")
        try {
            val files = PlatformFiles.listFilesRecursively(tmpWs)
            assertEquals(1, files.count { it.endsWith("Sample.kt") }, "Symlink cycle must not duplicate or infinitely recurse")

            val hash = CacheProfileResolver.computeRepoHash(tmpWs)
            assertEquals(16, hash.length)

            val (inRepoDb, inRepoArt) = CacheProfileResolver.resolvePaths(tmpWs, profile = "in-repo")
            assertTrue(inRepoDb.endsWith("/.atlas/atlas.db"))
            assertTrue(inRepoArt.endsWith("/.atlas/artifacts"))

            val (entDb, entArt) = CacheProfileResolver.resolvePaths(tmpWs, profile = "enterprise")
            assertTrue(entDb.contains("/Library/Caches/atlas/$hash/atlas.db"))
            assertTrue(entArt.contains("/Library/Caches/atlas/$hash/artifacts"))
        } finally {
            PlatformFiles.delete("$tmpWs/sub/loop")
            PlatformFiles.delete("$tmpWs/sub/Sample.kt")
            PlatformFiles.delete("$tmpWs/.atlas/atlas.db")
        }
    }

    @Test
    fun testArchitectureAuditorBoost003AndPlanCollisions() {
        val dbPath = "/tmp/test_atlas_auditor_${getpid()}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            store.upsertFileRecord(
                projectId = "p",
                relPath = "src/MultiClass.kt",
                language = "kotlin",
                moduleId = ":core",
                sourceSet = "main",
                semioticLayer = "semantic",
                archLayer = "domain",
                sha256 = "sha1",
                mtime = 1.0,
                loc = 30,
                topLevelDeclCount = 2
            )
            store.upsertFileRecord(
                projectId = "p",
                relPath = "src/TestMulti.kt",
                language = "kotlin",
                moduleId = ":core",
                sourceSet = "macosArm64Test",
                semioticLayer = "test",
                archLayer = "test",
                sha256 = "sha2",
                mtime = 1.0,
                loc = 20,
                topLevelDeclCount = 3
            )

            val sharedTarget = GraphNode(
                nodeId = "p:src/Shared.kt#Shared",
                projectId = "p",
                fileId = "p:src/Shared.kt",
                name = "Shared",
                qualifiedName = "pkg.Shared",
                kind = "class",
                moduleId = ":core"
            )
            val plan1 = GraphNode(
                nodeId = "p:plans/p1.md#p1",
                projectId = "p",
                fileId = "p:plans/p1.md",
                name = "plan_alpha",
                qualifiedName = "plans/p1.md",
                kind = "plan_contract",
                signature = "plan_contract (active): plans/p1.md"
            )
            val plan2 = GraphNode(
                nodeId = "p:plans/p2.md#p2",
                projectId = "p",
                fileId = "p:plans/p2.md",
                name = "plan_beta",
                qualifiedName = "plans/p2.md",
                kind = "plan_contract",
                signature = "plan_contract (active): plans/p2.md"
            )
            store.upsertNodesAndEdges(
                projectId = "p",
                nodes = listOf(sharedTarget, plan1, plan2),
                edges = listOf(
                    GraphEdge("e1", "p", plan1.nodeId, sharedTarget.nodeId, "PLAN_SCOPES_FILE"),
                    GraphEdge("e2", "p", plan2.nodeId, sharedTarget.nodeId, "PLAN_SCOPES_FILE")
                )
            )

            val auditor = ArchitectureAuditor(store)
            val report = auditor.runFullAudit("p")
            assertFalse(report.passed)
            assertEquals(1, report.singleDeclarationViolations.size)
            assertEquals("src/MultiClass.kt", report.singleDeclarationViolations[0].relPath)
            assertEquals(1, report.plan3dCollisions.size)
            assertEquals("plan_alpha", report.plan3dCollisions[0].planA)
            assertEquals("plan_beta", report.plan3dCollisions[0].planB)
            assertEquals(listOf("src/Shared.kt"), report.plan3dCollisions[0].sharedFiles)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testIncrementalWorkspaceIndexOrphanSweepSubpathAndSpillover() {
        val pid = getpid()
        val wsRoot = "/tmp/test_atlas_inc_ws_$pid"
        val dbPath = "/tmp/test_atlas_inc_$pid.db"
        PlatformFiles.delete(dbPath)
        PlatformFiles.mkdirs("$wsRoot/src")
        PlatformFiles.mkdirs("$wsRoot/plans")

        PlatformFiles.writeText(
            "$wsRoot/src/Caller.kt",
            """
            package demo
            class Caller {
                fun run() {
                    oldTarget()
                }
            }
            """.trimIndent()
        )
        PlatformFiles.writeText(
            "$wsRoot/src/Target.kt",
            """
            package demo
            fun oldTarget() {}
            """.trimIndent()
        )
        PlatformFiles.writeText(
            "$wsRoot/plans/feature.md",
            """
            ---
            status: active
            scope:
              files:
                - src/Target.kt
            ---
            # Feature Plan
            Touches `oldTarget`.
            """.trimIndent()
        )

        val server = CodeGraphMcpServer(workspaceRoot = wsRoot, customDbPath = dbPath)
        try {
            val firstIdx = server.indexWorkspace("demo")
            assertEquals(3, firstIdx["files_indexed"]?.jsonPrimitive?.int)
            val oldTargetNode = server.store.getNode("demo", "oldTarget")
            assertNotNull(oldTargetNode)
            assertTrue(oldTargetNode.inDegree >= 1)

            // Re-index without changes -> all 3 skipped
            val secondIdx = server.indexWorkspace("demo")
            assertEquals(0, secondIdx["files_indexed"]?.jsonPrimitive?.int)
            assertEquals(3, secondIdx["files_skipped"]?.jsonPrimitive?.int)

            // Rename oldTarget -> newTarget in Target.kt and re-index using `./src` subpath
            PlatformFiles.writeText(
                "$wsRoot/src/Target.kt",
                """
                package demo
                fun newTarget() {}
                """.trimIndent()
            )
            val subIdx = server.indexWorkspace("demo", subpaths = listOf("./src"))
            assertEquals(1, subIdx["files_indexed"]?.jsonPrimitive?.int)

            // Verify oldTarget is gone, newTarget is present, and plan_contract re-linked to newTarget
            val deletedOld = server.store.getNode("demo", "oldTarget")
            assertEquals(null, deletedOld)
            val newTargetNode = server.store.getNode("demo", "newTarget")
            assertNotNull(newTargetNode)
            val planEdges = server.store.listEdges("demo", listOf("PLAN_SCOPES_FILE"))
            assertTrue(planEdges.any { it.targetNodeId == newTargetNode.nodeId }, "Plan should re-link to newTarget on incremental code re-index")

            // Verify no orphan edges pointing to oldTarget remain
            val allEdges = server.store.listEdges("demo")
            assertTrue(allEdges.none { it.targetNodeId.endsWith("#oldTarget") }, "Orphan edge to oldTarget must be swept")

            // Verify WIKILINK_REFS edge to unresolved vault_ref# note is preserved by sweepOrphanEdges
            PlatformFiles.writeText(
                "$wsRoot/plans/feature.md",
                """
                ---
                status: active
                scope:
                  files:
                    - src/Target.kt
                ---
                # Feature Plan
                See [[Design Specification]] and touches `newTarget`.
                """.trimIndent()
            )
            server.indexWorkspace("demo")
            val wikiEdges = server.store.listEdges("demo", listOf("WIKILINK_REFS"))
            assertTrue(
                wikiEdges.any { it.targetNodeId == "demo:vault_ref#Design Specification" },
                "WIKILINK_REFS edge to unresolved vault_ref# note must survive orphan edge sweep"
            )

            // Test deleting a file on disk between incremental runs
            PlatformFiles.delete("$wsRoot/src/Caller.kt")
            val delIdx = server.indexWorkspace("demo")
            assertEquals(null, server.store.getNode("demo", "Caller"), "Deleted file node must be pruned on incremental index")

            // Test live source slicing
            val slice = server.store.sliceLiveSource(wsRoot, "demo", newTargetNode)
            assertTrue(slice.fresh)
            assertTrue(slice.source.contains("fun newTarget()"))

            // Test >32KB spillover envelope
            val bulkNodes = (1..450).map { i ->
                GraphNode(
                    nodeId = "demo:src/Bulk$i.kt#BulkSymbolNameWithLongIdentifier_$i",
                    projectId = "demo",
                    fileId = "demo:src/Bulk$i.kt",
                    name = "BulkSymbolNameWithLongIdentifier_$i",
                    qualifiedName = "com.example.very.deep.package.structure.module$i.BulkSymbolNameWithLongIdentifier_$i",
                    kind = "class",
                    signature = "class BulkSymbolNameWithLongIdentifier_$i(val parameterOne: String, val parameterTwo: Int)"
                )
            }
            val bulkEdges = (1..450).map { i ->
                GraphEdge(
                    edgeId = "demo:bulk_edge_$i",
                    projectId = "demo",
                    sourceNodeId = newTargetNode.nodeId,
                    targetNodeId = bulkNodes[i - 1].nodeId,
                    edgeType = "CALLS"
                )
            }
            server.store.upsertNodesAndEdges("demo", bulkNodes, bulkEdges)

            val impactResp = server.callTool(
                "codegraph_query",
                buildJsonObject {
                    put("action", "impact")
                    put("project", "demo")
                    put("target", "newTarget")
                    put("depth", 2)
                }
            )
            assertEquals("true", impactResp["spillover"]?.jsonPrimitive?.content)
            val artifactUri = impactResp["artifact_uri"]?.jsonPrimitive?.content
            assertNotNull(artifactUri)
            assertTrue(PlatformFiles.exists(artifactUri))
            PlatformFiles.delete(artifactUri)
        } finally {
            server.store.close()
            PlatformFiles.delete("$wsRoot/src/Caller.kt")
            PlatformFiles.delete("$wsRoot/src/Target.kt")
            PlatformFiles.delete("$wsRoot/plans/feature.md")
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testFigmaSnapshotReIngestPurgesDeletedNodesAndCliUtf8Truncation() {
        val dbPath = "/tmp/test_atlas_figma_reingest_${getpid()}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val extractor = FigmaSnapshotExtractor()
            val snap1 = """
                {
                  "name": "DesignDoc",
                  "document": {
                    "id": "0:0",
                    "name": "Document",
                    "type": "DOCUMENT",
                    "children": [
                      {
                        "id": "1:1",
                        "name": "Page 1",
                        "type": "CANVAS",
                        "children": [
                          {
                            "id": "2:1",
                            "name": "OldFrame",
                            "type": "FRAME",
                            "absoluteBoundingBox": {"x": 0, "y": 0, "width": 200, "height": 100}
                          }
                        ]
                      }
                    ]
                  }
                }
            """.trimIndent()
            extractor.ingest(store, "fk_reingest", snap1)
            assertNotNull(store.queryFigmaNodeById("fk_reingest", "2:1"))

            val snap2 = """
                {
                  "name": "DesignDoc",
                  "document": {
                    "id": "0:0",
                    "name": "Document",
                    "type": "DOCUMENT",
                    "children": [
                      {
                        "id": "1:1",
                        "name": "Page 1",
                        "type": "CANVAS",
                        "children": [
                          {
                            "id": "2:2",
                            "name": "NewFrame",
                            "type": "FRAME",
                            "layoutMode": "VERTICAL",
                            "primaryAxisSizingMode": "AUTO",
                            "counterAxisSizingMode": "AUTO",
                            "absoluteBoundingBox": {"x": 0, "y": 0, "width": 200, "height": 120}
                          }
                        ]
                      }
                    ]
                  }
                }
            """.trimIndent()
            extractor.ingest(store, "fk_reingest", snap2)
            assertEquals(null, store.queryFigmaNodeById("fk_reingest", "2:1"), "Deleted Figma node must be purged on re-ingest")
            assertNotNull(store.queryFigmaNodeById("fk_reingest", "2:2"))

            // Test UTF-8 multi-byte safe truncateSummary in CodeGraphCli
            // Put 3-byte '→' (\u2192) across byte offset 396..398
            val prefix = "a".repeat(395)
            val multiByteText = prefix + "→→→→→"
            val truncated = CodeGraphCli.truncateSummary(multiByteText, isJson = false)
            assertTrue(truncated.encodeToByteArray().size <= CodeGraphCli.SUMMARY_CEILING_BYTES)
            assertFalse(truncated.contains('\uFFFD'), "Truncated UTF-8 summary must not contain replacement character U+FFFD")
            assertTrue(truncated.endsWith("..."))

            // Test CLI flag parser guard so --target without a value does not swallow --json
            val exitCode = CodeGraphCli.run(arrayOf("query", "--db", dbPath, "--target", "--json"))
            assertEquals(0, exitCode)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testUnchangedCallerReResolutionAndStalePlanEdgeCleanup() {
        val pid = getpid()
        val wsRoot = "/tmp/test_atlas_bug01_$pid"
        val dbPath = "/tmp/test_atlas_bug01_$pid.db"
        PlatformFiles.delete(dbPath)
        PlatformFiles.mkdirs("$wsRoot/src")
        PlatformFiles.mkdirs("$wsRoot/plans")

        // Step 1: Index Caller.kt when Callee.kt does NOT exist yet
        PlatformFiles.writeText(
            "$wsRoot/src/Caller.kt",
            """
            package sample
            class Caller {
                fun invokeLater() {
                    newlyAddedCallee()
                }
            }
            """.trimIndent()
        )
        PlatformFiles.writeText(
            "$wsRoot/src/OldScoped.kt",
            """
            package sample
            class OldScoped {
                fun unusedHelper() {}
            }
            """.trimIndent()
        )
        PlatformFiles.writeText(
            "$wsRoot/plans/active_plan.md",
            """
            ---
            status: active
            scope:
              files:
                - src/OldScoped.kt
                - src/Callee.kt
            ---
            # Active Plan
            Scopes `OldScoped` and `newlyAddedCallee`.
            """.trimIndent()
        )

        val server = CodeGraphMcpServer(workspaceRoot = wsRoot, customDbPath = dbPath)
        try {
            server.indexWorkspace("bug01")
            assertEquals(null, server.store.getNode("bug01", "newlyAddedCallee"))

            // Step 2: Add Callee.kt WITHOUT modifying Caller.kt or active_plan.md, and rename OldScoped -> RenamedScoped
            PlatformFiles.writeText(
                "$wsRoot/src/Callee.kt",
                """
                package sample
                fun newlyAddedCallee() {}
                """.trimIndent()
            )
            PlatformFiles.writeText(
                "$wsRoot/src/OldScoped.kt",
                """
                package sample
                class RenamedScoped
                """.trimIndent()
            )

            val incRes = server.indexWorkspace("bug01")
            // Caller.kt and active_plan.md were unchanged (skipped), Callee.kt and OldScoped.kt were indexed
            assertEquals(2, incRes["files_indexed"]?.jsonPrimitive?.int)
            assertEquals(2, incRes["files_skipped"]?.jsonPrimitive?.int)

            // BUG-01: Unchanged Caller#invokeLater must now have a resolved CALLS edge to Callee#newlyAddedCallee!
            val calleeNode = server.store.getNode("bug01", "newlyAddedCallee")
            assertNotNull(calleeNode, "newlyAddedCallee should be indexed")
            val callersOfNew = server.store.getCallers("bug01", calleeNode.nodeId, depth = 1)
            assertTrue(
                callersOfNew.any { it.name == "invokeLater" },
                "Unchanged Caller#invokeLater must resolve CALLS edge to newlyAddedCallee via persisted raw_symbol_edges"
            )

            // BUG-14: Unchanged active_plan.md must link to RenamedScoped and newlyAddedCallee and NOT retain stale edges to OldScoped
            val planEdges = server.store.listEdges("bug01", listOf("PLAN_SCOPES_FILE"))
            assertTrue(planEdges.any { it.targetNodeId.endsWith("#newlyAddedCallee") })
            assertTrue(planEdges.any { it.targetNodeId.endsWith("#RenamedScoped") })
            assertTrue(planEdges.none { it.targetNodeId.endsWith("#OldScoped") })

            // Dead code detection: InvokeLater is uncalled, RenamedScoped is uncalled, newlyAddedCallee IS called (inDegree >= 1)
            val deadNodes = server.auditor.detectDeadCode("bug01")
            val deadNames = deadNodes.map { it.name }.toSet()
            assertFalse("newlyAddedCallee" in deadNames, "Called function must not be reported as dead code")
            assertTrue("invokeLater" in deadNames, "Uncalled function should be reported in dead_code")

            // CLI dead-code command returns 0
            val deadExit = CodeGraphCli.run(arrayOf("dead-code", "--project", "bug01", "--workspace", wsRoot, "--db", dbPath))
            assertEquals(0, deadExit)
        } finally {
            server.store.close()
            PlatformFiles.delete("$wsRoot/src/Caller.kt")
            PlatformFiles.delete("$wsRoot/src/Callee.kt")
            PlatformFiles.delete("$wsRoot/src/OldScoped.kt")
            PlatformFiles.delete("$wsRoot/plans/active_plan.md")
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testCliAuditExitCodeAndSymlinkEscapeAndSpilloverUniqueness() {
        val pid = getpid()
        val wsRoot = "/tmp/test_atlas_cli_audit_$pid"
        val outsideDir = "/tmp/test_atlas_outside_$pid"
        val dbPath = "/tmp/test_atlas_cli_audit_$pid.db"
        PlatformFiles.delete(dbPath)
        PlatformFiles.mkdirs("$wsRoot/src")
        PlatformFiles.mkdirs(outsideDir)

        PlatformFiles.writeText("$outsideDir/SecretOutside.kt", "package outside\nclass SecretOutside")
        symlink(outsideDir, "$wsRoot/src/escaped_link")

        // Write a file with 2 top-level classes to trigger BOOST-003 violation
        PlatformFiles.writeText(
            "$wsRoot/src/ViolatingFile.kt",
            """
            package sample
            class FirstTopLevel
            class SecondTopLevel
            """.trimIndent()
        )

        val server = CodeGraphMcpServer(workspaceRoot = wsRoot, customDbPath = dbPath)
        try {
            server.indexWorkspace("audit_proj")
            // Symlink pointing outside workspaceRoot must NOT be indexed
            assertEquals(null, server.store.getNode("audit_proj", "SecretOutside"))

            // BUG-03: CLI `audit` must return exit code 1 when violations exist (both summary and --json modes)
            val exitSummary = CodeGraphCli.run(arrayOf("audit", "--project", "audit_proj", "--workspace", wsRoot, "--db", dbPath))
            assertEquals(1, exitSummary, "atlas audit must return exit code 1 when passed=false in summary mode")

            val exitJson = CodeGraphCli.run(arrayOf("audit", "--project", "audit_proj", "--workspace", wsRoot, "--db", dbPath, "--json"))
            assertEquals(1, exitJson, "atlas audit must return exit code 1 when passed=false in --json mode")

            // Scoped audit on a clean subpath must pass (exit code 0) while scoped audit on src/ViolatingFile.kt fails (exit code 1)
            PlatformFiles.writeText(
                "$wsRoot/src/CleanFile.kt",
                """
                package sample
                class CleanSingleDeclaration
                """.trimIndent()
            )
            server.indexWorkspace("audit_proj")
            val exitScopedPass = CodeGraphCli.run(
                arrayOf("audit", "--project", "audit_proj", "--workspace", wsRoot, "--db", dbPath, "--paths", "src/CleanFile.kt")
            )
            assertEquals(0, exitScopedPass, "Scoped atlas audit on clean file must return exit code 0")

            val exitScopedFail = CodeGraphCli.run(
                arrayOf("audit", "--project", "audit_proj", "--workspace", wsRoot, "--db", dbPath, "--paths", "src/ViolatingFile.kt")
            )
            assertEquals(1, exitScopedFail, "Scoped atlas audit on violating file must return exit code 1")

            // Verify resolveProjectWorkspace resolves ~/Development/<Project> case-insensitively
            val resolvedDevWs = CacheProfileResolver.resolveProjectWorkspace("/", "code-atlas")
            if (PlatformFiles.isDirectory("${PlatformFiles.userHome()}/Development/code-atlas")) {
                assertTrue(resolvedDevWs.endsWith("/Development/code-atlas"))
            }

            // Verify figma-shortest-path and figma shortest-path CLI subcommands execute cleanly
            val figmaShort1 = CodeGraphCli.run(arrayOf("figma-shortest-path", "--db", dbPath, "--from", "n1", "--to", "n2"))
            assertEquals(0, figmaShort1)
            val figmaShort2 = CodeGraphCli.run(arrayOf("figma", "shortest-path", "--db", dbPath, "--from", "n1", "--to", "n2"))
            assertEquals(0, figmaShort2)

            // BUG-16: Verify multiple >32KB tool calls within the same second generate distinct spillover filenames
            val bulkNodes = (1..450).map { i ->
                GraphNode(
                    nodeId = "audit_proj:src/Bulk$i.kt#BulkSymbolNameWithLongIdentifier_$i",
                    projectId = "audit_proj",
                    fileId = "audit_proj:src/Bulk$i.kt",
                    name = "BulkSymbolNameWithLongIdentifier_$i",
                    qualifiedName = "com.example.very.deep.package.structure.module$i.BulkSymbolNameWithLongIdentifier_$i",
                    kind = "class",
                    signature = "class BulkSymbolNameWithLongIdentifier_$i(val parameterOne: String, val parameterTwo: Int)"
                )
            }
            val firstNode = server.store.getNode("audit_proj", "FirstTopLevel")
            assertNotNull(firstNode)
            val bulkEdges = (1..450).map { i ->
                GraphEdge(
                    edgeId = "audit_proj:bulk_edge_$i",
                    projectId = "audit_proj",
                    sourceNodeId = firstNode.nodeId,
                    targetNodeId = bulkNodes[i - 1].nodeId,
                    edgeType = "CALLS"
                )
            }
            server.store.upsertNodesAndEdges("audit_proj", bulkNodes, bulkEdges)

            val spill1 = server.callTool(
                "codegraph_query",
                buildJsonObject {
                    put("action", "impact")
                    put("project", "audit_proj")
                    put("target", "FirstTopLevel")
                    put("depth", 2)
                }
            )
            val spill2 = server.callTool(
                "codegraph_query",
                buildJsonObject {
                    put("action", "impact")
                    put("project", "audit_proj")
                    put("target", "FirstTopLevel")
                    put("depth", 2)
                }
            )
            val uri1 = spill1["artifact_uri"]?.jsonPrimitive?.content
            val uri2 = spill2["artifact_uri"]?.jsonPrimitive?.content
            assertNotNull(uri1)
            assertNotNull(uri2)
            assertTrue(uri1 != uri2, "Multiple >32KB calls in the same second must produce distinct spillover artifact URIs")
            PlatformFiles.delete(uri1)
            PlatformFiles.delete(uri2)
        } finally {
            server.close()
            PlatformFiles.delete("$wsRoot/src/escaped_link")
            PlatformFiles.delete("$wsRoot/src/ViolatingFile.kt")
            PlatformFiles.delete("$wsRoot/src/CleanFile.kt")
            PlatformFiles.delete("$outsideDir/SecretOutside.kt")
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testMcpMethodNotFoundReturns32601() {
        val server = CodeGraphMcpServer(workspaceRoot = ".")
        try {
            val response = server.processJsonRpcMessage("""{"jsonrpc":"2.0","id":123,"method":"unknown_method","params":{}}""")
            assertNotNull(response)
            assertTrue(response.contains("-32601"), "Must return error code -32601 for unknown method")
            assertTrue(response.contains("Method not found: unknown_method"))

            // Notification without id should return null
            val notif = server.processJsonRpcMessage("""{"jsonrpc":"2.0","method":"unknown_notif","params":{}}""")
            assertEquals(null, notif)
        } finally {
            server.close()
        }
    }

    @Test
    fun testMcpPingReturnsEmptyResult() {
        val server = CodeGraphMcpServer(workspaceRoot = ".")
        try {
            val response = server.processJsonRpcMessage("""{"jsonrpc":"2.0","id":42,"method":"ping"}""")
            assertNotNull(response)
            assertTrue(response.contains(""""id":42"""))
            assertTrue(response.contains(""""result":{}"""))

            // Standard MCP initialized notification returns null
            val notif = server.processJsonRpcMessage("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            assertEquals(null, notif)
        } finally {
            server.close()
        }
    }

    @Test
    fun testMcpToolErrorReturnsResultWithIsErrorTrue() {
        val server = CodeGraphMcpServer(workspaceRoot = ".")
        try {
            val response = server.processJsonRpcMessage("""{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"unknown_tool","arguments":{}}}""")
            assertNotNull(response)
            assertTrue(response.contains(""""id":99"""))
            assertTrue(response.contains(""""isError":true"""), "Tool error must return isError: true under MCP 2024-11-05")
            assertFalse(response.contains(""""code":-32000"""), "Tool failure must not return protocol-level JSON-RPC error")
            assertTrue(response.contains("Unknown MCP tool: unknown_tool"))
        } finally {
            server.close()
        }
    }

    @Test
    fun testMcpWorkMemoryAnnotationsIn3dScopeAndImpact() {
        val pid = getpid()
        val dbPath = "/tmp/test_mcp_wm_$pid.db"
        PlatformFiles.delete(dbPath)
        val server = CodeGraphMcpServer(workspaceRoot = ".", customDbPath = dbPath)
        try {
            val node = GraphNode(
                nodeId = "test_wm:src/Service.kt#PaymentService",
                projectId = "test_wm",
                fileId = "test_wm:src/Service.kt",
                name = "PaymentService",
                qualifiedName = "com.PaymentService",
                kind = "class",
                signature = "class PaymentService",
                startLine = 1,
                endLine = 20,
                moduleId = ":service",
                sourceSet = "main",
                semioticLayer = "semantic",
                archLayer = "domain",
                isTestDouble = false
            )
            server.store.upsertNodesAndEdges("test_wm", listOf(node), emptyList())
            server.store.insertNodeAnnotation(
                annotationId = "ann_1",
                projectId = "test_wm",
                nodeId = node.nodeId,
                outcome = "WARNING",
                note = "Requires exclusive lock on payment ledger table",
                createdAtEpoch = time(null).toDouble()
            )

            // 1. codegraph_query with action=impact
            val queryCall = server.processJsonRpcMessage(
                """{"jsonrpc":"2.0","id":101,"method":"tools/call","params":{"name":"codegraph_query","arguments":{"project":"test_wm","action":"impact","target":"test_wm:src/Service.kt#PaymentService"}}}"""
            )
            assertNotNull(queryCall)
            assertTrue(queryCall.contains("exclusive lock on payment ledger table"), "Impact response must include active WorkMemory annotations")

            // 2. codegraph_audit with action=synthesize_3d_scope
            val scopeCall = server.processJsonRpcMessage(
                """{"jsonrpc":"2.0","id":102,"method":"tools/call","params":{"name":"codegraph_audit","arguments":{"project":"test_wm","action":"synthesize_3d_scope","targets":["test_wm:src/Service.kt#PaymentService"]}}}"""
            )
            assertNotNull(scopeCall)
            assertTrue(scopeCall.contains("exclusive lock on payment ledger table"), "3D scope synthesis response must include active WorkMemory annotations")
        } finally {
            server.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testTerminalPlanStatusesDoNotTriggerCollision() {
        val pid = getpid()
        val dbPath = "/tmp/test_plan_term_$pid.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val sharedTarget = GraphNode(
                nodeId = "p:src/SharedFile.kt#SharedFile",
                projectId = "p",
                fileId = "p:src/SharedFile.kt",
                name = "SharedFile",
                qualifiedName = "SharedFile",
                kind = "class"
            )
            val donePlan = GraphNode(
                nodeId = "p:plans/done.md#DonePlan",
                projectId = "p",
                fileId = "p:plans/done.md",
                name = "DonePlan",
                qualifiedName = "plans/done.md",
                kind = "plan_contract",
                signature = "plan (done)"
            )
            val landedPlan = GraphNode(
                nodeId = "p:plans/landed.md#LandedPlan",
                projectId = "p",
                fileId = "p:plans/landed.md",
                name = "LandedPlan",
                qualifiedName = "plans/landed.md",
                kind = "plan_contract",
                signature = "plan (landed)"
            )
            val closedPlan = GraphNode(
                nodeId = "p:plans/closed.md#ClosedPlan",
                projectId = "p",
                fileId = "p:plans/closed.md",
                name = "ClosedPlan",
                qualifiedName = "plans/closed.md",
                kind = "plan_contract",
                signature = "plan (closed)"
            )
            val activePlan1 = GraphNode(
                nodeId = "p:plans/active1.md#ActivePlan1",
                projectId = "p",
                fileId = "p:plans/active1.md",
                name = "ActivePlan1",
                qualifiedName = "plans/active1.md",
                kind = "plan_contract",
                signature = "plan (active)"
            )
            val activePlan2 = GraphNode(
                nodeId = "p:plans/active2.md#ActivePlan2",
                projectId = "p",
                fileId = "p:plans/active2.md",
                name = "ActivePlan2",
                qualifiedName = "plans/active2.md",
                kind = "plan_contract",
                signature = "plan (active)"
            )

            store.upsertNodesAndEdges(
                projectId = "p",
                nodes = listOf(sharedTarget, donePlan, landedPlan, closedPlan, activePlan1, activePlan2),
                edges = listOf(
                    GraphEdge("e1", "p", donePlan.nodeId, sharedTarget.nodeId, "PLAN_SCOPES_FILE"),
                    GraphEdge("e2", "p", landedPlan.nodeId, sharedTarget.nodeId, "PLAN_SCOPES_FILE"),
                    GraphEdge("e3", "p", closedPlan.nodeId, sharedTarget.nodeId, "PLAN_SCOPES_FILE"),
                    GraphEdge("e4", "p", activePlan1.nodeId, sharedTarget.nodeId, "PLAN_SCOPES_FILE"),
                    GraphEdge("e5", "p", activePlan2.nodeId, sharedTarget.nodeId, "PLAN_SCOPES_FILE")
                )
            )

            val auditor = ArchitectureAuditor(store)
            val collisions = auditor.checkPlan3dCollisions("p")
            // done, landed, closed are ignored, so only activePlan1 and activePlan2 collide
            assertEquals(1, collisions.size)
            assertEquals("ActivePlan1", collisions[0].planA)
            assertEquals("ActivePlan2", collisions[0].planB)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testDeadCodeExcludesFileScope() {
        val pid = getpid()
        val dbPath = "/tmp/test_dead_code_$pid.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val fileScopeNode = GraphNode(
                nodeId = "p:src/Main.kt#file_scope",
                projectId = "p",
                fileId = "p:src/Main.kt",
                name = "file_scope",
                qualifiedName = "file_scope",
                kind = "file_scope",
                inDegree = 0
            )
            val deadFn = GraphNode(
                nodeId = "p:src/Main.kt#unusedFunction",
                projectId = "p",
                fileId = "p:src/Main.kt",
                name = "unusedFunction",
                qualifiedName = "com.example.unusedFunction",
                kind = "function",
                inDegree = 0
            )
            store.upsertNodesAndEdges(
                projectId = "p",
                nodes = listOf(fileScopeNode, deadFn),
                edges = emptyList()
            )
            val auditor = ArchitectureAuditor(store)
            val deadNodes = auditor.detectDeadCode("p")
            assertEquals(1, deadNodes.size)
            assertEquals("unusedFunction", deadNodes[0].name)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testVaultPlanBridgeGlobAndDirectoryScoping() {
        val nodeService = GraphNode(
            nodeId = "p:src/services/deep/PaymentService.kt#PaymentService",
            projectId = "p",
            fileId = "p:src/services/deep/PaymentService.kt",
            name = "PaymentService",
            qualifiedName = "com.services.deep.PaymentService",
            kind = "class"
        )
        val nodeModel = GraphNode(
            nodeId = "p:src/models/Payment.kt#Payment",
            projectId = "p",
            fileId = "p:src/models/Payment.kt",
            name = "Payment",
            qualifiedName = "com.models.Payment",
            kind = "class"
        )
        val nodeApi = GraphNode(
            nodeId = "p:src/api/v1/Routes.kt#Routes",
            projectId = "p",
            fileId = "p:src/api/v1/Routes.kt",
            name = "Routes",
            qualifiedName = "com.api.v1.Routes",
            kind = "class"
        )
        val nodeExact = GraphNode(
            nodeId = "p:src/exact/Target.kt#Target",
            projectId = "p",
            fileId = "p:src/exact/Target.kt",
            name = "Target",
            qualifiedName = "com.exact.Target",
            kind = "class"
        )

        val planMd = """
            ---
            status: active
            scope:
              files:
                - src/services/**/*.kt
                - src/models/*.kt
                - src/api/
                - ./src/exact/Target.kt
            ---
            # Glob Scoped Plan
        """.trimIndent()

        val (nodes, edges) = VaultPlanBridge.extractMarkdownAndPlans(
            projectId = "p",
            relPath = "plans/glob_plan.md",
            markdownText = planMd,
            indexedNodes = listOf(nodeService, nodeModel, nodeApi, nodeExact)
        )

        assertEquals(1, nodes.size)
        val scopedTargetIds = edges.filter { it.edgeType == "PLAN_SCOPES_FILE" }.map { it.targetNodeId }.toSet()
        assertTrue(nodeService.nodeId in scopedTargetIds, "src/services/**/*.kt must match deep nested service")
        assertTrue(nodeModel.nodeId in scopedTargetIds, "src/models/*.kt must match model")
        assertTrue(nodeApi.nodeId in scopedTargetIds, "src/api/ must match files under api directory")
        assertTrue(nodeExact.nodeId in scopedTargetIds, "./src/exact/Target.kt must match exact path")
    }

    @Test
    fun testScopeSynthesizerGlobDirectoryAndFileResolution() {
        val pid = getpid()
        val dbPath = "/tmp/test_synthesizer_globs_$pid.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            store.upsertFileRecord(
                projectId = "p",
                relPath = "src/service/OrderService.kt",
                language = "kotlin",
                moduleId = ":service",
                sourceSet = "commonMain",
                semioticLayer = "semantic",
                archLayer = "domain",
                sha256 = "sha1",
                mtime = 100.0,
                loc = 50,
                topLevelDeclCount = 2
            )
            store.upsertFileRecord(
                projectId = "p",
                relPath = "src/models/Order.kt",
                language = "kotlin",
                moduleId = ":models",
                sourceSet = "commonMain",
                semioticLayer = "syntactic",
                archLayer = "domain",
                sha256 = "sha2",
                mtime = 100.0,
                loc = 20,
                topLevelDeclCount = 1
            )

            val classNode = GraphNode(
                nodeId = "p:src/service/OrderService.kt#OrderService",
                projectId = "p",
                fileId = "p:src/service/OrderService.kt",
                name = "OrderService",
                qualifiedName = "com.OrderService",
                kind = "class"
            )
            val fnNode = GraphNode(
                nodeId = "p:src/service/OrderService.kt#processOrder",
                projectId = "p",
                fileId = "p:src/service/OrderService.kt",
                name = "processOrder",
                qualifiedName = "com.processOrder",
                kind = "function"
            )
            val modelNode = GraphNode(
                nodeId = "p:src/models/Order.kt#Order",
                projectId = "p",
                fileId = "p:src/models/Order.kt",
                name = "Order",
                qualifiedName = "com.Order",
                kind = "class"
            )
            store.upsertNodesAndEdges("p", listOf(classNode, fnNode, modelNode), emptyList())

            val synthesizer = ScopeSynthesizer(store)

            // 1. Seed by glob: "src/**/*.kt" resolves all files
            val globScope = synthesizer.synthesizeScope("p", listOf("src/**/*.kt"))
            assertTrue(globScope.files.contains("src/service/OrderService.kt"))
            assertTrue(globScope.files.contains("src/models/Order.kt"))

            // 2. Seed by directory prefix: "src/service/"
            val dirScope = synthesizer.synthesizeScope("p", listOf("src/service/"))
            assertTrue(dirScope.files.contains("src/service/OrderService.kt"))
            assertFalse(dirScope.files.contains("src/models/Order.kt"))

            // 3. Seed by specific file path: "src/service/OrderService.kt" resolves both nodes in that file
            val fileScope = synthesizer.synthesizeScope("p", listOf("src/service/OrderService.kt"))
            assertTrue(fileScope.files.contains("src/service/OrderService.kt"))
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }
}
