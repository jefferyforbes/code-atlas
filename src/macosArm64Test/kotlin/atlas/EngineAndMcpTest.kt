package atlas

import atlas.bridge.VaultPlanBridge
import atlas.cli.CodeGraphCli
import atlas.config.CacheProfileResolver
import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectureAuditor
import atlas.extract.FigmaSnapshotExtractor
import atlas.extract.ModuleBoundaryDetector
import atlas.mcp.CodeGraphMcpServer
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
}
