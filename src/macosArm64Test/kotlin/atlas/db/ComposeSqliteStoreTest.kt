package atlas.db

import atlas.extract.SemioticLayerClassifier
import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComposeSqliteStoreTest {

    @Test
    fun testComposeModelsObeySemioticDomainLayerAndSavepointRollback() {
        val modelFiles = listOf(
            "src/macosArm64Main/kotlin/atlas/model/ComposeNodeRecord.kt" to "ComposeNodeRecord",
            "src/macosArm64Main/kotlin/atlas/model/ComposeParamRecord.kt" to "ComposeParamRecord",
            "src/macosArm64Main/kotlin/atlas/model/ComposeQueryResult.kt" to "ComposeQueryResult",
            "src/macosArm64Main/kotlin/atlas/model/ComposeAuditResult.kt" to "ComposeAuditResult",
            "src/macosArm64Main/kotlin/atlas/model/ComposeDoctorReport.kt" to "ComposeDoctorReport",
            "src/macosArm64Main/kotlin/atlas/model/Compose3DScope.kt" to "Compose3DScope"
        )
        for ((relPath, className) in modelFiles) {
            val (sem, arch, _) = SemioticLayerClassifier.classify(relPath, className)
            assertEquals("syntactic", sem, "$className must be classified as syntactic")
            assertEquals("domain", arch, "$className must be classified as domain")
        }

        val dbPath = "/tmp/atlas_compose_store_test_${kotlin.random.Random.nextInt(100000, 999999)}.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val rootNode = ComposeNodeRecord(
                nodeId = "proj:ui/HomeScreen.kt#HomeScreen",
                projectSlug = "proj",
                fqName = "com.example.ui.HomeScreen",
                filePath = "ui/HomeScreen.kt",
                startLine = 10,
                endLine = 42,
                sourceSet = "commonMain",
                isRestartable = true,
                isSkippable = false,
                unstableParamCount = 1,
                provenance = "STATIC_INFERRED",
                violationMask = 1
            )
            store.upsertComposeNodesAndParams(listOf(rootNode), emptyList())

            assertFailsWith<RuntimeException> {
                store.withSavepoint("sp_rollback_test") {
                    val tempNode = rootNode.copy(nodeId = "proj:ui/Temp.kt#Temp", fqName = "com.example.ui.Temp")
                    store.upsertComposeNodesAndParams(listOf(tempNode), emptyList())
                    throw RuntimeException("Simulated failure inside savepoint")
                }
            }

            val allNodes = store.listComposeNodes("proj")
            assertEquals(1, allNodes.size, "Rolled-back savepoint must not persist Temp composable")
            assertEquals("proj:ui/HomeScreen.kt#HomeScreen", allNodes.first().nodeId)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testProvenanceDominanceAndCycleSafeCascadeAndBlame() {
        val dbPath = "/tmp/atlas_compose_cascade_test_${kotlin.random.Random.nextInt(100000, 999999)}.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val nodeAId = "proj:ui/Feed.kt#FeedList"
            val nodeBId = "proj:ui/Feed.kt#FeedRow"
            val modelNodeId = "proj:ui/FeedUiState.kt#FeedUiState"

            // Seed AST graph nodes + cyclic CALLS_COMPOSABLE edges (FeedList <-> FeedRow)
            store.upsertNodesAndEdges(
                projectId = "proj",
                nodes = listOf(
                    GraphNode(
                        nodeId = nodeAId,
                        projectId = "proj",
                        fileId = "proj:ui/Feed.kt",
                        name = "FeedList",
                        qualifiedName = "com.example.ui.FeedList",
                        kind = "composable",
                        signature = "@Composable fun FeedList(state: FeedUiState)",
                        startLine = 12,
                        endLine = 30
                    ),
                    GraphNode(
                        nodeId = nodeBId,
                        projectId = "proj",
                        fileId = "proj:ui/Feed.kt",
                        name = "FeedRow",
                        qualifiedName = "com.example.ui.FeedRow",
                        kind = "composable",
                        signature = "@Composable fun FeedRow(state: FeedUiState)",
                        startLine = 32,
                        endLine = 50
                    ),
                    GraphNode(
                        nodeId = modelNodeId,
                        projectId = "proj",
                        fileId = "proj:ui/FeedUiState.kt",
                        name = "FeedUiState",
                        qualifiedName = "com.example.ui.FeedUiState",
                        kind = "class",
                        signature = "data class FeedUiState(var title: String)",
                        startLine = 5,
                        endLine = 8
                    ),
                    GraphNode(
                        nodeId = "$modelNodeId#title",
                        projectId = "proj",
                        fileId = "proj:ui/FeedUiState.kt",
                        name = "title",
                        qualifiedName = "com.example.ui.FeedUiState.title",
                        kind = "property",
                        signature = "var title: String",
                        startLine = 6,
                        endLine = 6
                    )
                ),
                edges = listOf(
                    GraphEdge(
                        edgeId = "e1",
                        projectId = "proj",
                        sourceNodeId = nodeAId,
                        targetNodeId = nodeBId,
                        edgeType = "CALLS_COMPOSABLE"
                    ),
                    GraphEdge(
                        edgeId = "e2",
                        projectId = "proj",
                        sourceNodeId = nodeBId,
                        targetNodeId = nodeAId,
                        edgeType = "CALLS_COMPOSABLE"
                    ),
                    GraphEdge(
                        edgeId = "e3",
                        projectId = "proj",
                        sourceNodeId = nodeAId,
                        targetNodeId = "$modelNodeId#title",
                        edgeType = "MUTATES_PROPERTY"
                    )
                )
            )

            // 1. Insert COMPILER_VERIFIED node
            val verifiedA = ComposeNodeRecord(
                nodeId = nodeAId,
                projectSlug = "proj",
                fqName = "com.example.ui.FeedList",
                filePath = "ui/Feed.kt",
                startLine = 12,
                endLine = 30,
                isSkippable = false,
                unstableParamCount = 1,
                provenance = "COMPILER_VERIFIED",
                violationMask = 1
            )
            val verifiedB = ComposeNodeRecord(
                nodeId = nodeBId,
                projectSlug = "proj",
                fqName = "com.example.ui.FeedRow",
                filePath = "ui/Feed.kt",
                startLine = 32,
                endLine = 50,
                isSkippable = false,
                unstableParamCount = 1,
                provenance = "STATIC_INFERRED",
                violationMask = 5
            )
            val paramA = ComposeParamRecord(
                nodeId = nodeAId,
                paramIndex = 0,
                paramName = "state",
                typeSignature = "com.example.ui.FeedUiState",
                stability = "UNSTABLE",
                stabilityReason = "MUTABLE_VAR_PROPERTY:title",
                provenance = "COMPILER_VERIFIED"
            )
            val paramB = ComposeParamRecord(
                nodeId = nodeBId,
                paramIndex = 0,
                paramName = "state",
                typeSignature = "com.example.ui.FeedUiState",
                stability = "UNSTABLE",
                stabilityReason = "MUTABLE_VAR_PROPERTY:title",
                provenance = "STATIC_INFERRED"
            )
            store.upsertComposeNodesAndParams(listOf(verifiedA, verifiedB), listOf(paramA, paramB))

            // 2. Attempt to overwrite nodeA with lower-provenance STATIC_INFERRED ( claiming isSkippable = true )
            val staticOverwriteA = verifiedA.copy(
                startLine = 14,
                isSkippable = true,
                provenance = "STATIC_INFERRED",
                violationMask = 3
            )
            store.upsertComposeNodesAndParams(listOf(staticOverwriteA), listOf(paramA.copy(stability = "STABLE", provenance = "STATIC_INFERRED")))

            val fetchedA = store.getComposeNode("proj", "FeedList")
            assertNotNull(fetchedA)
            assertEquals("COMPILER_VERIFIED", fetchedA.provenance, "Provenance dominance must preserve COMPILER_VERIFIED over STATIC_INFERRED")
            assertEquals(false, fetchedA.isSkippable, "Skippability from COMPILER_VERIFIED must be preserved")
            assertEquals(14, fetchedA.startLine, "Start line and violation_mask should still update")

            // 3. Verify cycle-safe cascade query terminates on mutual recursion
            val cascade = store.queryComposeCascade("proj", nodeAId, maxDepth = 5)
            assertTrue(cascade.isNotEmpty(), "Cascade should find downstream FeedRow")
            assertEquals(nodeBId, cascade.first().calleeId)
            assertEquals("state", cascade.first().forwardedParam)

            // 4. Verify blame lineage resolves FeedUiState root cause
            val blame = store.queryComposeBlame("proj", nodeAId)
            assertEquals(1, blame.size)
            assertEquals(modelNodeId, blame.first().rootCauseNodeId)
            assertEquals("ui/FeedUiState.kt", blame.first().rootCauseFile)
            assertEquals(5, blame.first().rootCauseLine)

            // 5. Verify property mutation count for write-site safety proof
            val mutCount = store.countPropertyMutations("proj", "title")
            assertEquals(1, mutCount)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }
}
