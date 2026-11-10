package atlas.db

import atlas.extract.SemioticLayerClassifier
import atlas.model.ComplexityDoctorReport
import atlas.model.ComplexityScoreRecord
import atlas.model.ComplexityVector
import atlas.model.ComplexityViolation
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Pattern 2 Invariant: state rollback verified and side-effect callCount == 0
class ComplexitySqliteStoreTest {

    @Test
    fun testComplexityModelsObeySemioticDomainLayer() {
        val modelFiles = listOf(
            "src/macosArm64Main/kotlin/atlas/model/ComplexityVector.kt" to "ComplexityVector",
            "src/macosArm64Main/kotlin/atlas/model/ComplexityScoreRecord.kt" to "ComplexityScoreRecord",
            "src/macosArm64Main/kotlin/atlas/model/ComplexityViolation.kt" to "ComplexityViolation",
            "src/macosArm64Main/kotlin/atlas/model/ComplexityDoctorReport.kt" to "ComplexityDoctorReport"
        )
        for ((relPath, className) in modelFiles) {
            val (sem, arch, isDouble) = SemioticLayerClassifier.classify(relPath, className)
            assertEquals("syntactic", sem, "$className must be classified as syntactic")
            assertEquals("domain", arch, "$className must be classified as domain")
            assertEquals(false, isDouble, "$className must not be classified as test double")
        }
    }

    @Test
    fun testDatabaseSchemaAndComplexityStorage() {
        val dbPath = "/tmp/atlas_complexity_test_${Random.nextInt(100000, 999999)}.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val node1 = GraphNode(
                nodeId = "test:src/Main.kt#heavyCalc",
                projectId = "test",
                fileId = "test:src/Main.kt",
                name = "heavyCalc",
                qualifiedName = "pkg.Main.heavyCalc",
                kind = "function",
                signature = "fun heavyCalc()",
                startLine = 10,
                endLine = 50,
                inDegree = 5,
                outDegree = 2,
                cyclomaticComplexity = 7,
                cognitiveComplexity = 14,
                maxNestingDepth = 3,
                complexityScore = 75.0
            )

            val node2 = GraphNode(
                nodeId = "test:src/Main.kt#simpleCalc",
                projectId = "test",
                fileId = "test:src/Main.kt",
                name = "simpleCalc",
                qualifiedName = "pkg.Main.simpleCalc",
                kind = "function",
                signature = "fun simpleCalc()",
                startLine = 52,
                endLine = 60,
                inDegree = 1,
                outDegree = 0,
                cyclomaticComplexity = 1,
                cognitiveComplexity = 2,
                maxNestingDepth = 1,
                complexityScore = 98.0
            )

            val caller1 = GraphNode(
                nodeId = "test:src/Caller.kt#c1",
                projectId = "test",
                fileId = "test:src/Caller.kt",
                name = "c1",
                qualifiedName = "pkg.Caller.c1",
                kind = "function"
            )
            val caller2 = GraphNode(
                nodeId = "test:src/Caller.kt#c2",
                projectId = "test",
                fileId = "test:src/Caller.kt",
                name = "c2",
                qualifiedName = "pkg.Caller.c2",
                kind = "function"
            )
            val edges = listOf(
                GraphEdge(
                    edgeId = "e1",
                    projectId = "test",
                    sourceNodeId = "test:src/Caller.kt#c1",
                    targetNodeId = "test:src/Main.kt#heavyCalc",
                    edgeType = "CALLS"
                ),
                GraphEdge(
                    edgeId = "e2",
                    projectId = "test",
                    sourceNodeId = "test:src/Caller.kt#c2",
                    targetNodeId = "test:src/Main.kt#heavyCalc",
                    edgeType = "CALLS"
                )
            )

            store.upsertNodesAndEdges(
                nodes = listOf(node1, node2, caller1, caller2),
                edges = edges,
                projectId = "test"
            )

            val fetched1 = store.getNode("test", "test:src/Main.kt#heavyCalc")
            assertNotNull(fetched1)
            assertEquals(7, fetched1.cyclomaticComplexity)
            assertEquals(14, fetched1.cognitiveComplexity)
            assertEquals(3, fetched1.maxNestingDepth)
            assertEquals(75.0, fetched1.complexityScore)

            val complexNodes = store.queryComplexNodes("test", minCognitive = 10)
            assertEquals(1, complexNodes.size)
            val topRecord = complexNodes.first()
            assertEquals("heavyCalc", topRecord.name)
            assertEquals(14, topRecord.cognitive)
            assertEquals(7, topRecord.cyclomatic)
            assertEquals(2, topRecord.inDegree)
            assertTrue(topRecord.riskIndex > 20.0, "Risk index should account for afferent in-degree")

            // Test partial index update via upsertNodeComplexity
            store.upsertNodeComplexity(
                nodeId = "test:src/Main.kt#simpleCalc",
                cyclomatic = 8,
                cognitive = 18,
                maxNesting = 4,
                score = 60.0
            )

            val updatedComplexNodes = store.queryComplexNodes("test", minCognitive = 10)
            assertEquals(2, updatedComplexNodes.size)
            assertEquals("simpleCalc", updatedComplexNodes.first().name) // 18 > 14

            val riskNodes = store.queryArchitecturalRiskNodes("test", minRisk = 10.0)
            assertTrue(riskNodes.size >= 2)
            assertTrue(riskNodes[0].riskIndex >= riskNodes[1].riskIndex)
        } finally {
            PlatformFiles.delete(dbPath)
            PlatformFiles.delete("$dbPath-wal")
            PlatformFiles.delete("$dbPath-shm")
        }
    }

    @Test
    fun testSchemaMigrationOnLegacyDatabase() {
        val dbPath = "/tmp/atlas_complexity_legacy_${Random.nextInt(100000, 999999)}.db"
        val rawDb = NativeSqliteDatabase(dbPath)
        // Create table without complexity columns
        rawDb.exec(
            """
            CREATE TABLE nodes (
                node_id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                file_id TEXT NOT NULL,
                name TEXT NOT NULL,
                qualified_name TEXT NOT NULL,
                kind TEXT NOT NULL,
                signature TEXT NOT NULL DEFAULT '',
                start_line INTEGER NOT NULL DEFAULT 1,
                end_line INTEGER NOT NULL DEFAULT 1,
                module_id TEXT NOT NULL DEFAULT '',
                source_set TEXT NOT NULL DEFAULT 'main',
                semiotic_layer TEXT NOT NULL DEFAULT 'semantic',
                arch_layer TEXT NOT NULL DEFAULT 'domain',
                is_test_double INTEGER NOT NULL DEFAULT 0,
                in_degree INTEGER NOT NULL DEFAULT 0,
                out_degree INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        rawDb.close()

        // Opening via SqliteGraphStore triggers migration
        val store = SqliteGraphStore(dbPath)
        try {
            val node = GraphNode(
                nodeId = "legacy:test#fn",
                projectId = "legacy",
                fileId = "legacy:test.kt",
                name = "fn",
                qualifiedName = "pkg.fn",
                kind = "function",
                cyclomaticComplexity = 4,
                cognitiveComplexity = 11,
                maxNestingDepth = 2,
                complexityScore = 82.0
            )
            store.upsertNodesAndEdges("legacy", listOf(node), emptyList())

            val fetched = store.getNode("legacy", "legacy:test#fn")
            assertNotNull(fetched)
            assertEquals(4, fetched.cyclomaticComplexity)
            assertEquals(11, fetched.cognitiveComplexity)
            assertEquals(2, fetched.maxNestingDepth)
            assertEquals(82.0, fetched.complexityScore)

            val complex = store.queryComplexNodes("legacy", minCognitive = 10)
            assertEquals(1, complex.size)
            assertEquals("fn", complex[0].name)
        } finally {
            PlatformFiles.delete(dbPath)
            PlatformFiles.delete("$dbPath-wal")
            PlatformFiles.delete("$dbPath-shm")
        }
    }

    @Test
    fun testComposableComplexityNodesAreQueried() {
        val dbPath = "/tmp/atlas_complexity_composable_${Random.nextInt(100000, 999999)}.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val composableNode = GraphNode(
                nodeId = "test:src/UI.kt#FeedScreen",
                projectId = "test",
                fileId = "test:src/UI.kt",
                name = "FeedScreen",
                qualifiedName = "pkg.UI.FeedScreen",
                kind = "composable",
                signature = "@Composable fun FeedScreen()",
                startLine = 15,
                endLine = 80,
                inDegree = 4,
                outDegree = 3,
                cyclomaticComplexity = 6,
                cognitiveComplexity = 15,
                maxNestingDepth = 3,
                complexityScore = 65.0
            )
            store.upsertNodesAndEdges("test", listOf(composableNode), emptyList())

            val complex = store.queryComplexNodes("test", minCognitive = 10)
            assertEquals(1, complex.size)
            assertEquals("FeedScreen", complex[0].name)
            assertEquals(15, complex[0].cognitive)

            val riskNodes = store.queryArchitecturalRiskNodes("test", minRisk = 10.0)
            assertEquals(1, riskNodes.size)
            assertEquals("FeedScreen", riskNodes[0].name)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            PlatformFiles.delete("$dbPath-wal")
            PlatformFiles.delete("$dbPath-shm")
        }
    }

    @Test
    fun testSqliteDegreeRecomputationOnMutation() {
        val dbPath = "/tmp/atlas_degrees_test_${Random.nextInt(100000, 999999)}.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val nodeA = GraphNode(
                nodeId = "proj:src/A.kt#fnA",
                projectId = "proj",
                fileId = "proj:src/A.kt",
                name = "fnA",
                qualifiedName = "pkg.A.fnA",
                kind = "function"
            )
            val nodeB = GraphNode(
                nodeId = "proj:src/B.kt#fnB",
                projectId = "proj",
                fileId = "proj:src/B.kt",
                name = "fnB",
                qualifiedName = "pkg.B.fnB",
                kind = "function"
            )
            val nodeC = GraphNode(
                nodeId = "proj:src/C.kt#fnC",
                projectId = "proj",
                fileId = "proj:src/C.kt",
                name = "fnC",
                qualifiedName = "pkg.C.fnC",
                kind = "function"
            )

            // Initially upsert with no edges
            store.upsertNodesAndEdges("proj", listOf(nodeA, nodeB, nodeC), emptyList())

            var fetchedA = store.getNode("proj", nodeA.nodeId)
            var fetchedB = store.getNode("proj", nodeB.nodeId)
            var fetchedC = store.getNode("proj", nodeC.nodeId)
            assertEquals(0, fetchedA?.inDegree)
            assertEquals(0, fetchedA?.outDegree)
            assertEquals(0, fetchedB?.inDegree)
            assertEquals(0, fetchedB?.outDegree)

            // Add edges: A -> B, C -> B, B -> C
            val edge1 = GraphEdge(
                edgeId = "e1",
                projectId = "proj",
                sourceNodeId = nodeA.nodeId,
                targetNodeId = nodeB.nodeId,
                edgeType = "CALLS"
            )
            val edge2 = GraphEdge(
                edgeId = "e2",
                projectId = "proj",
                sourceNodeId = nodeC.nodeId,
                targetNodeId = nodeB.nodeId,
                edgeType = "CALLS"
            )
            val edge3 = GraphEdge(
                edgeId = "e3",
                projectId = "proj",
                sourceNodeId = nodeB.nodeId,
                targetNodeId = nodeC.nodeId,
                edgeType = "CALLS"
            )

            store.upsertNodesAndEdges("proj", listOf(nodeA, nodeB, nodeC), listOf(edge1, edge2, edge3))

            fetchedA = store.getNode("proj", nodeA.nodeId)
            fetchedB = store.getNode("proj", nodeB.nodeId)
            fetchedC = store.getNode("proj", nodeC.nodeId)

            // A calls B: in=0, out=1
            assertEquals(0, fetchedA?.inDegree)
            assertEquals(1, fetchedA?.outDegree)

            // B is called by A and C, and calls C: in=2, out=1
            assertEquals(2, fetchedB?.inDegree)
            assertEquals(1, fetchedB?.outDegree)

            // C is called by B, and calls B: in=1, out=1
            assertEquals(1, fetchedC?.inDegree)
            assertEquals(1, fetchedC?.outDegree)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            PlatformFiles.delete("$dbPath-wal")
            PlatformFiles.delete("$dbPath-shm")
        }
    }
}
