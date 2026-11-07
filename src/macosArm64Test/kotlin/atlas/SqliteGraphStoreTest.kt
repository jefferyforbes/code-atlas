package atlas

import atlas.db.SqliteGraphStore
import atlas.engine.WorkspaceIndexer
import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.FigmaDocument
import atlas.model.FigmaNode
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteGraphStoreTest {

    @Test
    fun `test SqliteGraphStore delegates Figma Compose and Traversal operations to domain DAOs`() {
        val store = SqliteGraphStore(":memory:")
        try {
            // 1. Figma DAO delegation
            val doc = FigmaDocument(
                fileKey = "fk-dao-1",
                name = "Design System",
                version = "v1",
                lastModified = "2026-10-27T00:00:00Z",
                thumbnailUrl = ""
            )
            store.insertFigmaDocument(doc)
            val loadedDoc = store.getFigmaDocument("fk-dao-1")
            assertNotNull(loadedDoc)
            assertEquals("Design System", loadedDoc.name)

            store.insertFigmaNodes(
                "fk-dao-1",
                listOf(
                    FigmaNode(
                        nodeId = "1:1",
                        fileKey = "fk-dao-1",
                        pageName = "Page 1",
                        sectionId = null,
                        parentId = null,
                        name = "PrimaryButton",
                        nodeType = "COMPONENT",
                        layoutMode = "HORIZONTAL",
                        sizingHorizontal = "HUG",
                        sizingVertical = "HUG",
                        textAutoResize = "NONE",
                        paddingLeft = 16.0,
                        paddingRight = 16.0,
                        paddingTop = 12.0,
                        paddingBottom = 12.0,
                        itemSpacing = 8.0,
                        localX = 0.0,
                        localY = 0.0,
                        canvasX = 100.0,
                        canvasY = 100.0,
                        width = 120.0,
                        height = 48.0,
                        isInteractive = true,
                        hasEmoji = false,
                        unboundFill = false,
                        unboundStroke = false,
                        wcagContrast = 7.5,
                        semioticLayer = "PRAGMATIC"
                    )
                )
            )
            assertEquals(1, store.countFigmaNodes("fk-dao-1"))
            val fNode = store.queryFigmaNodeById("fk-dao-1", "1:1")
            assertNotNull(fNode)
            assertEquals("PrimaryButton", fNode.name)

            // 2. Compose DAO delegation
            store.upsertComposeNodesAndParams(
                nodes = listOf(
                    ComposeNodeRecord(
                        nodeId = "cmp:1",
                        projectSlug = "test-proj",
                        fqName = "com.example.PrimaryButton",
                        filePath = "src/main/PrimaryButton.kt",
                        startLine = 10,
                        endLine = 25,
                        sourceSet = "main",
                        isRestartable = true,
                        isSkippable = true,
                        isReadonly = false,
                        isInline = false,
                        returnsUnit = true,
                        hasModifierParam = true,
                        modifierIsFirstOptional = true,
                        modifierHasDefault = true,
                        unstableParamCount = 0,
                        runtimeParamCount = 0,
                        receiverStability = "STABLE",
                        provenance = "STATIC_INFERRED",
                        violationMask = 0
                    )
                ),
                params = listOf(
                    ComposeParamRecord(
                        nodeId = "cmp:1",
                        paramIndex = 0,
                        paramName = "label",
                        typeSignature = "String",
                        stability = "STABLE",
                        stabilityReason = "Primitive/String",
                        hasDefault = false,
                        isUsedInBody = true,
                        provenance = "STATIC_INFERRED"
                    )
                )
            )
            val composeNodes = store.listComposeNodes("test-proj")
            assertEquals(1, composeNodes.size)
            assertEquals("com.example.PrimaryButton", composeNodes.first().fqName)
            assertEquals(1, store.listComposeParams("cmp:1").size)

            // 3. Traversal & Complexity DAO delegation
            val callerNode = GraphNode(
                nodeId = "n:caller",
                projectId = "test-proj",
                fileId = "test-proj:src/A.kt",
                kind = "function",
                name = "callerFn",
                qualifiedName = "com.example.callerFn",
                startLine = 1,
                endLine = 10,
                signature = "fun callerFn()",
                semioticLayer = "SEMANTIC"
            )
            val calleeNode = GraphNode(
                nodeId = "n:callee",
                projectId = "test-proj",
                fileId = "test-proj:src/B.kt",
                kind = "function",
                name = "calleeFn",
                qualifiedName = "com.example.calleeFn",
                startLine = 1,
                endLine = 10,
                signature = "fun calleeFn()",
                semioticLayer = "SEMANTIC"
            )
            val callEdge = GraphEdge(
                edgeId = "e:1",
                projectId = "test-proj",
                sourceNodeId = "n:caller",
                targetNodeId = "n:callee",
                edgeType = "CALLS",
                provenance = "AST"
            )
            store.upsertNodesAndEdges("test-proj", listOf(callerNode, calleeNode), listOf(callEdge))

            val callers = store.getCallers("test-proj", "n:callee", depth = 2)
            assertEquals(1, callers.size)
            assertEquals("n:caller", callers.first().sourceId)

            store.upsertNodeComplexity("n:caller", cyclomatic = 4, cognitive = 3, maxNesting = 1, score = 95.0)
            val complexNodes = store.queryComplexNodes("test-proj", minCognitive = 2)
            assertTrue(complexNodes.any { it.nodeId == "n:caller" && it.cognitive == 3 })
        } finally {
            store.close()
        }
    }

    @Test
    fun `test WorkspaceIndexer preserves file local class stability priority and SQLite schema fast path on reopen`() {
        val tempRoot = "/tmp/atlas-indexer-test-${kotlin.random.Random.nextInt(100_000, 999_999)}"
        val dbPath = "$tempRoot/graph.db"
        try {
            PlatformFiles.mkdirs("$tempRoot/src/featureA")
            PlatformFiles.mkdirs("$tempRoot/src/featureB")

            PlatformFiles.writeText(
                "$tempRoot/src/featureA/ScreenA.kt",
                """
                package com.example.featureA

                import androidx.compose.runtime.Composable

                data class UiState(val title: String)

                @Composable
                fun ScreenA(state: UiState) {
                    Text(state.title)
                }
                """.trimIndent()
            )
            PlatformFiles.writeText(
                "$tempRoot/src/featureB/ScreenB.kt",
                """
                package com.example.featureB

                import androidx.compose.runtime.Composable

                class UiState(var mutableCounter: Int)

                @Composable
                fun ScreenB(state: UiState) {
                    Text(state.mutableCounter.toString())
                }
                """.trimIndent()
            )

            val store1 = SqliteGraphStore(dbPath)
            try {
                val indexer = WorkspaceIndexer(tempRoot, store1)
                indexer.indexWorkspace(projectId = "collision-proj", force = true)
            } finally {
                store1.close()
            }

            // Reopen existing DB to exercise GraphSchemaMigrator PRAGMA user_version = 8 fast-path
            val store2 = SqliteGraphStore(dbPath)
            try {
                val composeNodes = store2.listComposeNodes("collision-proj").associateBy { it.fqName }
                val screenA = composeNodes["com.example.featureA.ScreenA"]
                val screenB = composeNodes["com.example.featureB.ScreenB"]
                assertNotNull(screenA)
                assertNotNull(screenB)

                val paramsA = store2.listComposeParams(screenA.nodeId).associateBy { it.paramName }
                val paramsB = store2.listComposeParams(screenB.nodeId).associateBy { it.paramName }

                assertEquals(
                    "STABLE",
                    paramsA["state"]?.stability,
                    "File-local stable UiState in ScreenA.kt must not be overwritten by unstable UiState in ScreenB.kt"
                )
                assertEquals(
                    "UNSTABLE",
                    paramsB["state"]?.stability,
                    "File-local unstable UiState in ScreenB.kt must not be overwritten by stable UiState in ScreenA.kt"
                )
            } finally {
                store2.close()
            }
        } finally {
            PlatformFiles.deleteRecursively(tempRoot)
        }
    }
}
