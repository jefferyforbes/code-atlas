package atlas

import atlas.bridge.VaultPlanBridge
import atlas.cli.CodeGraphCli
import atlas.config.CacheProfileResolver
import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectureAuditor
import atlas.engine.AutonomousRemediationVerifier
import atlas.engine.ScopeSynthesizer
import atlas.engine.ToolMigrationEngine
import atlas.engine.WorkspaceIndexer
import atlas.extract.FigmaSnapshotExtractor
import atlas.extract.ModuleBoundaryDetector
import atlas.mcp.CodeGraphMcpServer
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid
import platform.posix.symlink
import platform.posix.system
import platform.posix.time
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class EngineAndMcpTest {

    private val json = Json { ignoreUnknownKeys = true }

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
            PlatformFiles.deleteRecursively(tmpWs)
            val hash = CacheProfileResolver.computeRepoHash(tmpWs)
            PlatformFiles.deleteRecursively("${PlatformFiles.userHome()}/Library/Caches/atlas/$hash")
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
                "atlas_query",
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
                "atlas_query",
                buildJsonObject {
                    put("action", "impact")
                    put("project", "audit_proj")
                    put("target", "FirstTopLevel")
                    put("depth", 2)
                }
            )
            val spill2 = server.callTool(
                "atlas_query",
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

            // 1. atlas_query with action=impact (and legacy alias codegraph_query)
            val queryCall = server.processJsonRpcMessage(
                """{"jsonrpc":"2.0","id":101,"method":"tools/call","params":{"name":"atlas_query","arguments":{"project":"test_wm","action":"impact","target":"test_wm:src/Service.kt#PaymentService"}}}"""
            )
            assertNotNull(queryCall)
            assertTrue(queryCall.contains("exclusive lock on payment ledger table"), "Impact response must include active WorkMemory annotations")

            val legacyQueryCall = server.processJsonRpcMessage(
                """{"jsonrpc":"2.0","id":1011,"method":"tools/call","params":{"name":"codegraph_query","arguments":{"project":"test_wm","action":"impact","target":"test_wm:src/Service.kt#PaymentService"}}}"""
            )
            assertNotNull(legacyQueryCall)
            assertTrue(legacyQueryCall.contains("exclusive lock on payment ledger table"), "Legacy codegraph_query alias must work")
            val parsedLegacy = json.parseToJsonElement(legacyQueryCall).jsonObject
            val legacyText = parsedLegacy["result"]?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
            val legacyData = json.parseToJsonElement(legacyText).jsonObject
            val deprecation = legacyData["_deprecation"]?.jsonObject
            assertNotNull(deprecation, "Legacy codegraph_query call must include _deprecation metadata")
            assertEquals("codegraph_query", deprecation["legacy_tool"]?.jsonPrimitive?.content)
            assertEquals("atlas_query", deprecation["preferred_tool"]?.jsonPrimitive?.content)

            // 2. atlas_audit with action=synthesize_3d_scope (and legacy alias codegraph_audit)
            val scopeCall = server.processJsonRpcMessage(
                """{"jsonrpc":"2.0","id":102,"method":"tools/call","params":{"name":"atlas_audit","arguments":{"project":"test_wm","action":"synthesize_3d_scope","targets":["test_wm:src/Service.kt#PaymentService"]}}}"""
            )
            assertNotNull(scopeCall)
            assertTrue(scopeCall.contains("exclusive lock on payment ledger table"), "3D scope synthesis response must include active WorkMemory annotations")
            val parsedScope = json.parseToJsonElement(scopeCall).jsonObject
            val scopeText = parsedScope["result"]?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
            val scopeData = json.parseToJsonElement(scopeText).jsonObject
            assertFalse(scopeData.containsKey("_deprecation"), "Canonical atlas_audit must not contain _deprecation metadata")

            val legacyScopeCall = server.processJsonRpcMessage(
                """{"jsonrpc":"2.0","id":1021,"method":"tools/call","params":{"name":"codegraph_audit","arguments":{"project":"test_wm","action":"synthesize_3d_scope","targets":["test_wm:src/Service.kt#PaymentService"]}}}"""
            )
            assertNotNull(legacyScopeCall)
            assertTrue(legacyScopeCall.contains("exclusive lock on payment ledger table"), "Legacy codegraph_audit alias must work")
            val parsedLegacyScope = json.parseToJsonElement(legacyScopeCall).jsonObject
            val legacyScopeText = parsedLegacyScope["result"]?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
            val legacyScopeData = json.parseToJsonElement(legacyScopeText).jsonObject
            val scopeDeprecation = legacyScopeData["_deprecation"]?.jsonObject
            assertNotNull(scopeDeprecation, "Legacy codegraph_audit call must include _deprecation metadata")
            assertEquals("codegraph_audit", scopeDeprecation["legacy_tool"]?.jsonPrimitive?.content)
            assertEquals("atlas_audit", scopeDeprecation["preferred_tool"]?.jsonPrimitive?.content)
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
    fun testDeadCodeExcludesContainersWithActiveChildrenLifecycleMethodsAndEnums() {
        val dbPath = "/tmp/test_dead_code_containers_${getpid()}_${time(null)}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val containerNode = GraphNode(
                nodeId = "p:src/Extractor.kt#ComplexityExtractor",
                projectId = "p",
                fileId = "p:src/Extractor.kt",
                name = "ComplexityExtractor",
                qualifiedName = "atlas.extract.ComplexityExtractor",
                kind = "object",
                startLine = 10,
                endLine = 50,
                inDegree = 0
            )
            val activeChildFn = GraphNode(
                nodeId = "p:src/Extractor.kt#calculate",
                projectId = "p",
                fileId = "p:src/Extractor.kt",
                name = "calculate",
                qualifiedName = "atlas.extract.ComplexityExtractor.calculate",
                kind = "function",
                startLine = 15,
                endLine = 30,
                inDegree = 3
            )
            val closeLifecycleFn = GraphNode(
                nodeId = "p:src/Store.kt#close",
                projectId = "p",
                fileId = "p:src/Store.kt",
                name = "close",
                qualifiedName = "atlas.db.Store.close",
                kind = "function",
                startLine = 20,
                endLine = 25,
                inDegree = 0
            )
            val enumTypeNode = GraphNode(
                nodeId = "p:src/Extractor.kt#ScopeType",
                projectId = "p",
                fileId = "p:src/Extractor.kt",
                name = "ScopeType",
                qualifiedName = "atlas.extract.ComplexityExtractor.ScopeType",
                kind = "enum",
                startLine = 35,
                endLine = 45,
                inDegree = 0
            )
            val trulyDeadFn = GraphNode(
                nodeId = "p:src/Dead.kt#deadHelper",
                projectId = "p",
                fileId = "p:src/Dead.kt",
                name = "deadHelper",
                qualifiedName = "atlas.dead.deadHelper",
                kind = "function",
                startLine = 5,
                endLine = 10,
                inDegree = 0
            )

            val callerNode = GraphNode(
                nodeId = "p:src/Store.kt#main",
                projectId = "p",
                fileId = "p:src/Store.kt",
                name = "main",
                qualifiedName = "atlas.db.Store.main",
                kind = "function",
                startLine = 1,
                endLine = 5
            )
            val callEdge = GraphEdge(
                edgeId = "edge1",
                projectId = "p",
                sourceNodeId = callerNode.nodeId,
                targetNodeId = activeChildFn.nodeId,
                edgeType = "CALLS"
            )

            store.upsertNodesAndEdges(
                projectId = "p",
                nodes = listOf(containerNode, activeChildFn, closeLifecycleFn, enumTypeNode, trulyDeadFn, callerNode),
                edges = listOf(callEdge)
            )
            val auditor = ArchitectureAuditor(store)
            val deadNodes = auditor.detectDeadCode("p")
            assertEquals(1, deadNodes.size, "Only truly dead helper should be flagged")
            assertEquals("deadHelper", deadNodes[0].name)
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

    @Test
    fun testSqliteGraphStoreDescriptionPersistenceAndMigration() {
        val dbPath = "/tmp/test_atlas_desc_store_${getpid()}_${time(null)}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val documentedNode = GraphNode(
                nodeId = "p:src/domain/User.kt#User",
                projectId = "p",
                fileId = "p:src/domain/User.kt",
                name = "User",
                qualifiedName = "com.domain.User",
                kind = "class",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = "Core user domain entity.",
                isTopLevel = true
            )
            val undocumentedNode = GraphNode(
                nodeId = "p:src/domain/Order.kt#Order",
                projectId = "p",
                fileId = "p:src/domain/Order.kt",
                name = "Order",
                qualifiedName = "com.domain.Order",
                kind = "class",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = null,
                isTopLevel = true
            )
            val nestedNode = GraphNode(
                nodeId = "p:src/domain/User.kt#User.Address",
                projectId = "p",
                fileId = "p:src/domain/User.kt",
                name = "Address",
                qualifiedName = "com.domain.User.Address",
                kind = "class",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = null,
                isTopLevel = false
            )

            store.upsertNodesAndEdges("p", listOf(documentedNode, undocumentedNode, nestedNode), emptyList())

            // Test getNode
            val retrievedUser = store.getNode("p", "User")
            assertNotNull(retrievedUser)
            assertEquals("Core user domain entity.", retrievedUser.description)
            assertTrue(retrievedUser.isTopLevel)

            val retrievedOrder = store.getNode("p", "Order")
            assertNotNull(retrievedOrder)
            assertEquals(null, retrievedOrder.description)
            assertTrue(retrievedOrder.isTopLevel)

            val retrievedNested = store.getNode("p", "Address")
            assertNotNull(retrievedNested)
            assertEquals(null, retrievedNested.description)
            assertFalse(retrievedNested.isTopLevel)

            // Test getNodesForFile
            val fileNodes = store.getNodesForFile("p", "src/domain/User.kt")
            assertEquals(2, fileNodes.size)
            val userInFile = fileNodes.firstOrNull { it.name == "User" }
            assertNotNull(userInFile)
            assertEquals("Core user domain entity.", userInFile.description)
            assertTrue(userInFile.isTopLevel)

            // Test listNodes
            val allNodes = store.listNodes("p")
            assertEquals(3, allNodes.size)

            // Test searchNodes
            val searchResults = store.searchNodes("p", "User")
            val searchedUser = searchResults.firstOrNull { it.name == "User" }
            assertNotNull(searchedUser)
            assertEquals("Core user domain entity.", searchedUser.description)

            // Test schema migration idempotency
            store.migrateNodesSchema()
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testArchitectureAuditorDescriptionSuggestionsAndModes() {
        val dbPath = "/tmp/test_atlas_desc_auditor_${getpid()}_${time(null)}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val domainNoDesc = GraphNode(
                nodeId = "p:src/domain/OrderService.kt#OrderService",
                projectId = "p",
                fileId = "p:src/domain/OrderService.kt",
                name = "OrderService",
                qualifiedName = "com.domain.OrderService",
                kind = "class",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = null,
                isTopLevel = true
            )
            val domainWithDesc = GraphNode(
                nodeId = "p:src/domain/UserService.kt#UserService",
                projectId = "p",
                fileId = "p:src/domain/UserService.kt",
                name = "UserService",
                qualifiedName = "com.domain.UserService",
                kind = "class",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = "Manages user account operations.",
                isTopLevel = true
            )
            val presNoDesc = GraphNode(
                nodeId = "p:src/ui/OrderView.kt#OrderView",
                projectId = "p",
                fileId = "p:src/ui/OrderView.kt",
                name = "OrderView",
                qualifiedName = "com.ui.OrderView",
                kind = "function",
                semioticLayer = "pragmatic",
                archLayer = "presentation",
                description = null,
                isTopLevel = true
            )
            val dataNoDesc = GraphNode(
                nodeId = "p:src/data/OrderRepo.kt#OrderRepo",
                projectId = "p",
                fileId = "p:src/data/OrderRepo.kt",
                name = "OrderRepo",
                qualifiedName = "com.data.OrderRepo",
                kind = "class",
                semioticLayer = "syntactic",
                archLayer = "data",
                description = null,
                isTopLevel = true
            )
            val testNode = GraphNode(
                nodeId = "p:src/test/OrderTest.kt#OrderTest",
                projectId = "p",
                fileId = "p:src/test/OrderTest.kt",
                name = "OrderTest",
                qualifiedName = "com.test.OrderTest",
                kind = "class",
                semioticLayer = "test",
                archLayer = "test",
                description = null,
                isTopLevel = true
            )
            val testDoubleNode = GraphNode(
                nodeId = "p:src/domain/FakeOrder.kt#FakeOrder",
                projectId = "p",
                fileId = "p:src/domain/FakeOrder.kt",
                name = "FakeOrder",
                qualifiedName = "com.domain.FakeOrder",
                kind = "class",
                semioticLayer = "semantic",
                archLayer = "domain",
                isTestDouble = true,
                description = null,
                isTopLevel = true
            )
            val nestedNode = GraphNode(
                nodeId = "p:src/domain/OrderService.kt#OrderService.Builder",
                projectId = "p",
                fileId = "p:src/domain/OrderService.kt",
                name = "Builder",
                qualifiedName = "com.domain.OrderService.Builder",
                kind = "class",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = null,
                isTopLevel = false
            )

            store.upsertNodesAndEdges(
                "p",
                listOf(domainNoDesc, domainWithDesc, presNoDesc, dataNoDesc, testNode, testDoubleNode, nestedNode),
                emptyList()
            )

            val auditor = ArchitectureAuditor(store)

            // 1. Default mode ("domain-only"): flags only domainNoDesc
            val defaultSuggestions = auditor.checkTopLevelDescriptions("p")
            assertEquals(1, defaultSuggestions.size)
            val sug0 = defaultSuggestions[0]
            assertEquals("OrderService", sug0.name)
            assertEquals("domain", sug0.archLayer)
            assertEquals("DOC-TOP-LEVEL", sug0.rule)

            // 2. "all" mode: flags domainNoDesc, presNoDesc, dataNoDesc (ignores test & test double & nested)
            val allSuggestions = auditor.checkTopLevelDescriptions("p", mode = "all")
            assertEquals(3, allSuggestions.size)
            val namesInAll = allSuggestions.map { it.name }.toSet()
            assertEquals(setOf("OrderService", "OrderView", "OrderRepo"), namesInAll)

            // 3. "off" mode: returns empty list
            val offSuggestions = auditor.checkTopLevelDescriptions("p", mode = "off")
            assertEquals(0, offSuggestions.size)

            val disabledSuggestions = auditor.checkTopLevelDescriptions("p", mode = "disabled")
            assertEquals(0, disabledSuggestions.size)

            // 4. runFullAudit with default domain-only: passed is true because suggestions are warnings
            val auditReport = auditor.runFullAudit("p")
            assertTrue(auditReport.passed, "Audit must pass because description suggestions are advisory warnings")
            assertEquals(1, auditReport.descriptionSuggestions.size)

            // 5. runFullAudit with mode = "off"
            val auditReportOff = auditor.runFullAudit("p", descriptionMode = "off")
            assertTrue(auditReportOff.passed)
            assertEquals(0, auditReportOff.descriptionSuggestions.size)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testMcpServerVerifyArchitectureAndCheckDescriptions() {
        val dbPath = "/tmp/test_atlas_desc_mcp_${getpid()}_${time(null)}.db"
        PlatformFiles.delete(dbPath)
        val server = CodeGraphMcpServer(workspaceRoot = ".", customDbPath = dbPath)
        try {
            val domainNode = GraphNode(
                nodeId = "p:src/domain/Payment.kt#Payment",
                projectId = "p",
                fileId = "p:src/domain/Payment.kt",
                name = "Payment",
                qualifiedName = "com.domain.Payment",
                kind = "class",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = null,
                isTopLevel = true
            )
            server.store.upsertNodesAndEdges("p", listOf(domainNode), emptyList())

            // Test verify_architecture with default mode
            val auditArgs = buildJsonObject {
                put("action", "verify_architecture")
                put("project", "p")
            }
            val auditRes = server.callTool("atlas_audit", auditArgs)
            val auditData = auditRes["data"]?.jsonObject ?: auditRes
            assertTrue(auditData["passed"]?.jsonPrimitive?.boolean == true)
            val suggestionsArray = auditData["description_suggestions"]?.jsonArray
                ?: auditData["descriptionSuggestions"]?.jsonArray
            assertNotNull(suggestionsArray)
            assertEquals(1, suggestionsArray.size)

            // Test verify_architecture with description_mode = "off"
            val auditOffArgs = buildJsonObject {
                put("action", "verify_architecture")
                put("project", "p")
                put("description_mode", "off")
            }
            val auditOffRes = server.callTool("atlas_audit", auditOffArgs)
            val auditOffData = auditOffRes["data"]?.jsonObject ?: auditOffRes
            val suggestionsOffArray = auditOffData["description_suggestions"]?.jsonArray
                ?: auditOffData["descriptionSuggestions"]?.jsonArray
            assertNotNull(suggestionsOffArray)
            assertEquals(0, suggestionsOffArray.size)

            // Test check_descriptions action
            val checkArgs = buildJsonObject {
                put("action", "check_descriptions")
                put("project", "p")
                put("description_mode", "domain-only")
            }
            val checkRes = server.callTool("atlas_audit", checkArgs)
            val checkData = checkRes["data"]?.jsonObject ?: checkRes
            assertEquals("domain-only", checkData["mode"]?.jsonPrimitive?.content)
            assertEquals(1, checkData["total_suggestions"]?.jsonPrimitive?.int)
            val checkSuggestions = checkData["suggestions"]?.jsonArray
            assertNotNull(checkSuggestions)
            assertEquals(1, checkSuggestions.size)
        } finally {
            server.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testCliAuditDescriptionModeFlag() {
        val dbPath = "/tmp/test_atlas_desc_cli_${getpid()}_${time(null)}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val domainNode = GraphNode(
                nodeId = "p:src/domain/Wallet.kt#Wallet",
                projectId = "p",
                fileId = "p:src/domain/Wallet.kt",
                name = "Wallet",
                qualifiedName = "com.domain.Wallet",
                kind = "class",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = null,
                isTopLevel = true
            )
            store.upsertNodesAndEdges("p", listOf(domainNode), emptyList())
        } finally {
            store.close()
        }

        try {
            // CLI audit with default options (exit code 0 since suggestions don't fail audit)
            val exitCodeDefault = CodeGraphCli.run(arrayOf("audit", "--project", "p", "--db", dbPath))
            assertEquals(0, exitCodeDefault)

            // CLI audit with --description-mode all
            val exitCodeAll = CodeGraphCli.run(arrayOf("audit", "--project", "p", "--db", dbPath, "--description-mode", "all"))
            assertEquals(0, exitCodeAll)

            // CLI audit with --no-description-check
            val exitCodeNoCheck = CodeGraphCli.run(arrayOf("audit", "--project", "p", "--db", dbPath, "--no-description-check"))
            assertEquals(0, exitCodeNoCheck)
        } finally {
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testSwiftProtocolAndActorAuditSuggestions() {
        val dbPath = "/tmp/test_atlas_swift_proto_${getpid()}_${time(null)}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val protocolNode = GraphNode(
                nodeId = "p:src/domain/AccountRepo.swift#AccountRepo",
                projectId = "p",
                fileId = "p:src/domain/AccountRepo.swift",
                name = "AccountRepo",
                qualifiedName = "AccountRepo",
                kind = "protocol",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = null,
                isTopLevel = true
            )
            val actorNode = GraphNode(
                nodeId = "p:src/domain/SessionActor.swift#SessionActor",
                projectId = "p",
                fileId = "p:src/domain/SessionActor.swift",
                name = "SessionActor",
                qualifiedName = "SessionActor",
                kind = "actor",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = null,
                isTopLevel = true
            )
            store.upsertNodesAndEdges("p", listOf(protocolNode, actorNode), emptyList())

            val auditor = ArchitectureAuditor(store)
            val suggestions = auditor.checkTopLevelDescriptions("p", mode = "domain-only")
            assertEquals(2, suggestions.size)
            val names = suggestions.map { it.name }.toSet()
            assertEquals(setOf("AccountRepo", "SessionActor"), names)
            val protoSug = suggestions.first { it.name == "AccountRepo" }
            assertTrue(protoSug.message.contains("Top-level interface 'AccountRepo'"))
            val actorSug = suggestions.first { it.name == "SessionActor" }
            assertTrue(actorSug.message.contains("Top-level actor 'SessionActor'"))
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testComposableAuditedInAllMode() {
        val dbPath = "/tmp/test_atlas_composable_${getpid()}_${time(null)}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val compNode = GraphNode(
                nodeId = "p:src/ui/UserCard.kt#UserCard",
                projectId = "p",
                fileId = "p:src/ui/UserCard.kt",
                name = "UserCard",
                qualifiedName = "ui.UserCard",
                kind = "composable",
                semioticLayer = "pragmatic",
                archLayer = "presentation",
                description = null,
                isTopLevel = true
            )
            store.upsertNodesAndEdges("p", listOf(compNode), emptyList())

            val auditor = ArchitectureAuditor(store)
            // Domain-only ignores presentation layer
            val domSug = auditor.checkTopLevelDescriptions("p", mode = "domain-only")
            assertEquals(0, domSug.size)

            // All mode flags presentation composable
            val allSug = auditor.checkTopLevelDescriptions("p", mode = "all")
            assertEquals(1, allSug.size)
            assertEquals("UserCard", allSug[0].name)
            assertTrue(allSug[0].message.contains("Top-level function 'UserCard'"))
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testAuditorPrefixMatchingExactBoundary() {
        val dbPath = "/tmp/test_atlas_prefix_bound_${getpid()}_${time(null)}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val appNode = GraphNode(
                nodeId = "p:src/app/Foo.kt#Foo",
                projectId = "p",
                fileId = "p:src/app/Foo.kt",
                name = "Foo",
                qualifiedName = "app.Foo",
                kind = "class",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = null,
                isTopLevel = true
            )
            val appleNode = GraphNode(
                nodeId = "p:src/apple/Bar.kt#Bar",
                projectId = "p",
                fileId = "p:src/apple/Bar.kt",
                name = "Bar",
                qualifiedName = "apple.Bar",
                kind = "class",
                semioticLayer = "semantic",
                archLayer = "domain",
                description = null,
                isTopLevel = true
            )
            store.upsertNodesAndEdges("p", listOf(appNode, appleNode), emptyList())

            val auditor = ArchitectureAuditor(store)
            val scopedSug = auditor.checkTopLevelDescriptions("p", pathPrefixes = listOf("src/app"))
            assertEquals(1, scopedSug.size)
            assertEquals("Foo", scopedSug[0].name)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testScopeSynthesizerDownstreamScreensOnlyIncludesScreens() {
        val dbPath = "/tmp/test_atlas_scope_screens_${getpid()}_${time(null)}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val rootScreen = "p:src/HomeScreen.kt#HomeScreen"
            val childButton = "p:src/PrimaryButton.kt#PrimaryButton"
            val childScreen = "p:src/DetailScreen.kt#DetailScreen"
            store.upsertComposeNodesAndParams(
                listOf(
                    atlas.model.ComposeNodeRecord(
                        nodeId = rootScreen, projectSlug = "p", filePath = "src/HomeScreen.kt",
                        fqName = "com.example.HomeScreen", startLine = 1, endLine = 10,
                        isSkippable = true, isRestartable = true
                    ),
                    atlas.model.ComposeNodeRecord(
                        nodeId = childButton, projectSlug = "p", filePath = "src/PrimaryButton.kt",
                        fqName = "com.example.PrimaryButton", startLine = 1, endLine = 10,
                        isSkippable = true, isRestartable = true
                    ),
                    atlas.model.ComposeNodeRecord(
                        nodeId = childScreen, projectSlug = "p", filePath = "src/DetailScreen.kt",
                        fqName = "com.example.DetailScreen", startLine = 1, endLine = 10,
                        isSkippable = true, isRestartable = true
                    )
                ),
                emptyList()
            )
            val nodeHome = GraphNode(nodeId = rootScreen, projectId = "p", fileId = "p:src/HomeScreen.kt", name = "HomeScreen", qualifiedName = "com.example.HomeScreen", kind = "composable")
            val nodeButton = GraphNode(nodeId = childButton, projectId = "p", fileId = "p:src/PrimaryButton.kt", name = "PrimaryButton", qualifiedName = "com.example.PrimaryButton", kind = "composable")
            val nodeDetail = GraphNode(nodeId = childScreen, projectId = "p", fileId = "p:src/DetailScreen.kt", name = "DetailScreen", qualifiedName = "com.example.DetailScreen", kind = "composable")
            store.upsertNodesAndEdges(
                "p",
                listOf(nodeHome, nodeButton, nodeDetail),
                listOf(
                    GraphEdge(
                        edgeId = "e1", projectId = "p",
                        sourceNodeId = rootScreen, targetNodeId = childButton,
                        edgeType = "CALLS"
                    ),
                    GraphEdge(
                        edgeId = "e2", projectId = "p",
                        sourceNodeId = rootScreen, targetNodeId = childScreen,
                        edgeType = "CALLS"
                    )
                )
            )
            val synthesizer = ScopeSynthesizer(store)
            val scope = synthesizer.synthesizeComposeScope("p", listOf("src/HomeScreen.kt"))
            assertTrue(scope.downstreamScreens.contains("com.example.DetailScreen"))
            assertFalse(scope.downstreamScreens.contains("com.example.PrimaryButton"))
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testComposeDoctorRealFileSlicing() {
        val tmpDir = "/tmp/test_atlas_compose_slice_${getpid()}_${time(null)}"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val srcDir = "$tmpDir/src"
            PlatformFiles.mkdirs(srcDir)
            val filePath = "src/UserCard.kt"
            val fileContent = """
                package com.example

                data class UserProfile(
                    var nickname: String,
                    val id: String
                )
            """.trimIndent()
            PlatformFiles.writeText("$tmpDir/$filePath", fileContent)

            val classNodeId = "p:$filePath#UserProfile"
            val compNodeId = "p:$filePath#UserCard"
            store.upsertNodesAndEdges(
                "p",
                listOf(
                    GraphNode(
                        nodeId = classNodeId, projectId = "p", fileId = "p:$filePath",
                        name = "UserProfile", qualifiedName = "com.example.UserProfile",
                        kind = "data class", startLine = 3, endLine = 6
                    )
                ),
                emptyList()
            )
            store.upsertComposeNodesAndParams(
                listOf(
                    atlas.model.ComposeNodeRecord(
                        nodeId = compNodeId, projectSlug = "p", filePath = filePath,
                        fqName = "com.example.UserCard", startLine = 8, endLine = 20,
                        isSkippable = false, isRestartable = true,
                        violationMask = atlas.extract.ComposeStabilityExtractor.BIT_STAB_001
                    )
                ),
                listOf(
                    atlas.model.ComposeParamRecord(
                        nodeId = compNodeId, paramIndex = 0, paramName = "profile",
                        typeSignature = "com.example.UserProfile", stability = "UNSTABLE",
                        stabilityReason = "MUTABLE_VAR_PROPERTY:nickname"
                    )
                )
            )

            val engine = atlas.engine.ComposeStabilityEngine(store)
            val report = engine.runDoctor("p", workspaceRoot = tmpDir)
            assertEquals(1, report.totalPrescriptions)
            val presc = report.prescriptions.first()
            assertTrue(presc.writeSiteSafe)
            assertEquals("    var nickname: String,", presc.beforeSnippet)
            assertEquals("    val nickname: String,", presc.afterSnippet)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }

    @Test
    fun testMcpToolSchemasIncludeAllRequiredProperties() {
        val testDb = "/tmp/test_schemas_${getpid()}_${time(null)}.db"
        val server = CodeGraphMcpServer(workspaceRoot = "/tmp", customDbPath = testDb)
        try {
            val tools = server.toolDefinitions()
            val queryTool = tools.first { it["name"]?.jsonPrimitive?.content == "atlas_query" }
            val queryProps = queryTool["inputSchema"]?.jsonObject?.get("properties")?.jsonObject
            assertNotNull(queryProps)
            assertTrue(queryProps.containsKey("limit"), "querySchema must contain limit")

            val auditTool = tools.first { it["name"]?.jsonPrimitive?.content == "atlas_audit" }
            val auditProps = auditTool["inputSchema"]?.jsonObject?.get("properties")?.jsonObject
            assertNotNull(auditProps)
            assertTrue(auditProps.containsKey("limit"), "auditSchema must contain limit")
            assertTrue(auditProps.containsKey("max_cyclomatic"), "auditSchema must contain max_cyclomatic")
            assertTrue(auditProps.containsKey("split"), "auditSchema must contain split")
            assertTrue(auditProps.containsKey("make_private"), "auditSchema must contain make_private")
            assertTrue(auditProps.containsKey("rule"), "auditSchema must contain rule")
            assertTrue(auditProps.containsKey("target"), "auditSchema must contain target")
        } finally {
            server.store.close()
            PlatformFiles.delete(testDb)
            PlatformFiles.delete("$testDb-wal")
            PlatformFiles.delete("$testDb-shm")
        }
    }

    @Test
    fun testSqliteGraphStoreFtsUpsertAndReindexCollisionSafety() {
        val testDb = "/tmp/test_fts_collision_${getpid()}_${time(null)}.db"
        val store = SqliteGraphStore(testDb)
        try {
            val fileA = "proj:src/FileA.kt"
            val nodeA1 = GraphNode(
                nodeId = "proj:src/FileA.kt#AlphaService",
                projectId = "proj",
                fileId = fileA,
                name = "AlphaService",
                qualifiedName = "com.example.AlphaService",
                kind = "class"
            )
            store.upsertFileRecord(
                projectId = "proj",
                relPath = "src/FileA.kt",
                language = "kotlin",
                moduleId = ":app",
                sourceSet = "main",
                semioticLayer = "syntactic",
                archLayer = "domain",
                sha256 = "hash1",
                mtime = 1000.0,
                loc = 50,
                topLevelDeclCount = 1
            )
            store.upsertNodesAndEdges("proj", listOf(nodeA1), emptyList())

            // Re-upsert file A (which deletes old nodes from nodes and nodes_fts)
            store.upsertFileRecord(
                projectId = "proj",
                relPath = "src/FileA.kt",
                language = "kotlin",
                moduleId = ":app",
                sourceSet = "main",
                semioticLayer = "syntactic",
                archLayer = "domain",
                sha256 = "hash2",
                mtime = 2000.0,
                loc = 60,
                topLevelDeclCount = 1
            )

            // Now insert a new node with different ID into file A
            val nodeA2 = GraphNode(
                nodeId = "proj:src/FileA.kt#BetaWorker",
                projectId = "proj",
                fileId = fileA,
                name = "BetaWorker",
                qualifiedName = "com.example.BetaWorker",
                kind = "class"
            )
            // This must not crash even if rowid is reused by SQLite
            store.upsertNodesAndEdges("proj", listOf(nodeA2), emptyList())

            // Re-insert nodeA1 as well
            store.upsertNodesAndEdges("proj", listOf(nodeA1, nodeA2), emptyList())

            // FTS query should find both
            val matches = store.searchNodes("proj", "AlphaService")
            assertEquals(1, matches.size)
            assertEquals("proj:src/FileA.kt#AlphaService", matches[0].nodeId)

            val matches2 = store.searchNodes("proj", "BetaWorker")
            assertEquals(1, matches2.size)
            assertEquals("proj:src/FileA.kt#BetaWorker", matches2[0].nodeId)
        } finally {
            store.close()
            PlatformFiles.delete(testDb)
            PlatformFiles.delete("$testDb-wal")
            PlatformFiles.delete("$testDb-shm")
        }
    }

    @Test
    fun testVaultPlanBridgeDotQualifiedBacktickResolution() {
        val targetNode = GraphNode(
            nodeId = "p:src/SqliteGraphStore.kt#SqliteGraphStore.searchNodes",
            projectId = "p",
            fileId = "p:src/SqliteGraphStore.kt",
            name = "searchNodes",
            qualifiedName = "atlas.db.SqliteGraphStore.searchNodes",
            kind = "function"
        )
        val markdown = """
            # Architecture Notes
            We invoke `SqliteGraphStore.searchNodes` to search FTS index.
        """.trimIndent()
        val (nodes, edges) = VaultPlanBridge.extractMarkdownAndPlans(
            projectId = "p",
            relPath = "docs/architecture.md",
            markdownText = markdown,
            indexedNodes = listOf(targetNode)
        )
        val codeSpanEdges = edges.filter { it.edgeType == "CODE_SPAN_REFS" }
        assertEquals(1, codeSpanEdges.size)
        assertEquals(targetNode.nodeId, codeSpanEdges[0].targetNodeId)
    }

    @Test
    fun testIncrementalReindexingRelinksUnchangedMarkdownDocs() {
        val tmpDir = "/tmp/test_atlas_md_relink_${getpid()}_${time(null)}"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val docsDir = "$tmpDir/docs"
            val srcDir = "$tmpDir/src"
            PlatformFiles.mkdirs(docsDir)
            PlatformFiles.mkdirs(srcDir)

            val docFile = "$docsDir/notes.md"
            PlatformFiles.writeText(docFile, "Reference to `NewWorker` here.")

            val srcFile = "$srcDir/Initial.kt"
            PlatformFiles.writeText(srcFile, "package com.example\nclass Initial")

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("test_proj")

            // Initial index: NewWorker does not exist yet
            val edges1 = store.listEdges("test_proj").filter { it.sourceNodeId == "test_proj:docs/notes.md#notes" }
            assertTrue(edges1.none { it.edgeType == "CODE_SPAN_REFS" })

            // Add NewWorker.kt without touching notes.md
            val workerFile = "$srcDir/NewWorker.kt"
            PlatformFiles.writeText(workerFile, "package com.example\nclass NewWorker")

            // Incremental index
            indexer.indexWorkspace("test_proj")

            // notes.md must be re-evaluated and link to NewWorker!
            val edges2 = store.listEdges("test_proj").filter { it.sourceNodeId == "test_proj:docs/notes.md#notes" }
            assertTrue(edges2.any { it.edgeType == "CODE_SPAN_REFS" && it.targetNodeId.contains("NewWorker") })
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            PlatformFiles.delete("$dbPath-wal")
            PlatformFiles.delete("$dbPath-shm")
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }

    @Test
    fun testComplexitySingleArgHelpExitZero() {
        val exitCode = CodeGraphCli.run(arrayOf("complexity"))
        assertEquals(0, exitCode, "atlas complexity with no arguments should print help and return exit code 0")
    }

    @Test
    fun testRemediationVerifierExitCodeNormalization() {
        val tmpDir = "/tmp/test_verifier_exit_${getpid()}"
        PlatformFiles.mkdirs(tmpDir)
        try {
            val scriptPath = "$tmpDir/test_fail.sh"
            PlatformFiles.writeText(scriptPath, "#!/bin/sh\nexit 1\n")
            system("chmod +x \"$scriptPath\"")

            val fileA = "$tmpDir/A.kt"
            PlatformFiles.writeText(fileA, "class A\n")

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)
            val result = verifier.verifyAndApply(
                projectSlug = "test",
                relPath = "A.kt",
                beforeSnippet = "class A\n",
                afterSnippet = "class A\n// comment\n",
                ruleId = "GENERIC",
                dryRun = true,
                testCommand = "./test_fail.sh"
            )
            assertFalse(result.testsPreserved, "Tests should fail when exit code is 1")
            assertTrue(
                result.details.any { it.contains("exit code 1") },
                "Failed test log must report exit code 1 (decoded), not 256. Details: ${result.details}"
            )
        } finally {
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }

    @Test
    fun testToolMigrationEngineScanAndMigrate() {
        val pid = getpid()
        val tmpDir = "/tmp/test_migration_engine_$pid"
        PlatformFiles.mkdirs(tmpDir)
        try {
            val mdFile = "$tmpDir/plan.md"
            val ktFile = "$tmpDir/Task.kt"
            PlatformFiles.writeText(
                mdFile,
                """
                # Plan
                Run `codegraph_query` to find symbols.
                Run `codegraph_audit` to verify scope.
                """.trimIndent()
            )
            PlatformFiles.writeText(
                ktFile,
                """
                // Call codegraph_query for callers
                val cmd = "codegraph_query"
                """.trimIndent()
            )

            // 1. Dry run
            val dryReport = ToolMigrationEngine.scanAndMigrate(tmpDir, apply = false)
            assertEquals(4, dryReport.totalOccurrences)
            assertEquals(2, dryReport.filesAffected)
            assertFalse(dryReport.applied)

            // Content remains unmodified
            assertTrue(PlatformFiles.readText(mdFile).contains("codegraph_query"))
            assertTrue(PlatformFiles.readText(mdFile).contains("codegraph_audit"))
            assertTrue(PlatformFiles.readText(ktFile).contains("codegraph_query"))

            // 2. Apply migration
            val applyReport = ToolMigrationEngine.scanAndMigrate(tmpDir, apply = true)
            assertEquals(4, applyReport.totalOccurrences)
            assertEquals(2, applyReport.filesAffected)
            assertTrue(applyReport.applied)

            // Content migrated
            val mdContent = PlatformFiles.readText(mdFile)
            val ktContent = PlatformFiles.readText(ktFile)
            assertFalse(mdContent.contains("codegraph_query"))
            assertFalse(mdContent.contains("codegraph_audit"))
            assertTrue(mdContent.contains("atlas_query"))
            assertTrue(mdContent.contains("atlas_audit"))
            assertFalse(ktContent.contains("codegraph_query"))
            assertTrue(ktContent.contains("atlas_query"))

            // 3. Rescan is clean
            val cleanReport = ToolMigrationEngine.scanAndMigrate(tmpDir, apply = false)
            assertEquals(0, cleanReport.totalOccurrences)
            assertEquals(0, cleanReport.filesAffected)
        } finally {
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }

    @Test
    fun testCliMigrateToolsAndAuditFlag() {
        val pid = getpid()
        val tmpDir = "/tmp/test_migration_cli_$pid"
        PlatformFiles.mkdirs(tmpDir)
        try {
            val planFile = "$tmpDir/workflow.md"
            PlatformFiles.writeText(planFile, "Step 1: invoke codegraph_query\nStep 2: invoke codegraph_audit\n")

            // Dry-run via CLI returns exit code 1 because unmigrated occurrences found
            val dryCode = CodeGraphCli.run(arrayOf("migrate-tools", "--path", tmpDir))
            assertEquals(1, dryCode, "migrate-tools dry-run must return 1 when unmigrated occurrences exist")

            // audit --legacy-tools returns 1
            val auditCode = CodeGraphCli.run(arrayOf("audit", "--legacy-tools", "--path", tmpDir))
            assertEquals(1, auditCode, "audit --legacy-tools must return 1 when deprecated tool calls exist")

            // Apply via CLI returns exit code 0
            val applyCode = CodeGraphCli.run(arrayOf("migrate-tools", "--path", tmpDir, "--apply"))
            assertEquals(0, applyCode, "migrate-tools with --apply must return 0")

            // audit --legacy-tools now returns 0
            val cleanAuditCode = CodeGraphCli.run(arrayOf("audit", "--legacy-tools", "--path", tmpDir))
            assertEquals(0, cleanAuditCode, "audit --legacy-tools must pass (0) when all legacy calls are migrated")

            val migratedContent = PlatformFiles.readText(planFile)
            assertTrue(migratedContent.contains("atlas_query"))
            assertTrue(migratedContent.contains("atlas_audit"))
            assertFalse(migratedContent.contains("codegraph_query"))
            assertFalse(migratedContent.contains("codegraph_audit"))
        } finally {
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }

    @Test
    fun testMcpMigrateToolsAction() {
        val pid = getpid()
        val tmpDir = "/tmp/test_migration_mcp_$pid"
        PlatformFiles.mkdirs(tmpDir)
        try {
            val planFile = "$tmpDir/test_mcp.md"
            PlatformFiles.writeText(planFile, "Use codegraph_query for blast radius.\n")

            val server = CodeGraphMcpServer(workspaceRoot = tmpDir)
            try {
                // Call atlas_audit with action=migrate_tools (dry-run)
                val dryCall = server.processJsonRpcMessage(
                    """{"jsonrpc":"2.0","id":2001,"method":"tools/call","params":{"name":"atlas_audit","arguments":{"project":"test_p","action":"migrate_tools","path":"$tmpDir","apply":false}}}"""
                )
                assertNotNull(dryCall)
                val parsedDry = json.parseToJsonElement(dryCall).jsonObject
                val dryText = parsedDry["result"]?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                val dryData = json.parseToJsonElement(dryText).jsonObject
                assertEquals(1, dryData["totalOccurrences"]?.jsonPrimitive?.int)
                assertFalse(dryData["applied"]?.jsonPrimitive?.boolean ?: true)

                // Call atlas_audit with action=migrate_tools (apply=true)
                val applyCall = server.processJsonRpcMessage(
                    """{"jsonrpc":"2.0","id":2002,"method":"tools/call","params":{"name":"atlas_audit","arguments":{"project":"test_p","action":"migrate_tools","path":"$tmpDir","apply":true}}}"""
                )
                assertNotNull(applyCall)
                val parsedApply = json.parseToJsonElement(applyCall).jsonObject
                val applyText = parsedApply["result"]?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                val applyData = json.parseToJsonElement(applyText).jsonObject
                assertEquals(1, applyData["totalOccurrences"]?.jsonPrimitive?.int)
                assertTrue(applyData["applied"]?.jsonPrimitive?.boolean ?: false)

                val updated = PlatformFiles.readText(planFile)
                assertTrue(updated.contains("atlas_query"))
                assertFalse(updated.contains("codegraph_query"))
            } finally {
                server.close()
            }
        } finally {
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }
}

