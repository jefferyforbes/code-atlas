package atlas.integration

import atlas.db.SqliteGraphStore
import atlas.extract.FigmaSnapshotExtractor
import atlas.model.FigmaDocument
import atlas.model.FigmaEdge
import atlas.model.FigmaNode
import atlas.model.FigmaVariable
import atlas.util.PlatformFiles
import platform.posix.getpid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conformance Contract Test Suite validating StoreSink implementations:
 * 1. FakeStoreSink (In-memory test seam)
 * 2. SqliteGraphStore (Persistent SQLite implementation)
 */
class AtlasConformanceContractTest {

    private data class SinkBoundaryCase(
        val label: String,
        val nodeCount: Int,
        val varCount: Int,
        val edgeCount: Int,
        val fileKey: String
    )

    private fun withTempSqliteStore(block: (SqliteGraphStore, String) -> Unit) {
        val dbPath = "/tmp/atlas_conformance_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            block(store, dbPath)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    private fun createSampleDoc(fileKey: String, name: String = "Design Spec"): FigmaDocument {
        return FigmaDocument(
            fileKey = fileKey,
            name = name,
            version = "1.0.0",
            lastModified = "2026-11-10T00:00:00Z",
            thumbnailUrl = "https://example.com/thumb.png"
        )
    }

    private fun createSampleNodes(fileKey: String, count: Int): List<FigmaNode> {
        return (1..count).map { idx ->
            FigmaNode(
                nodeId = "node:$idx",
                fileKey = fileKey,
                pageName = "Page 1",
                name = "Frame $idx",
                nodeType = "FRAME"
            )
        }
    }

    // Pattern 1: Happy Path AAA Contract Test for FakeStoreSink
    @Test
    fun testFakeStoreSinkConformance() {
        // Arrange
        val sink = FakeStoreSink()
        val doc = createSampleDoc("fake_key_1")
        val nodes = createSampleNodes("fake_key_1", 3)

        // Act
        sink.insertFigmaDocument(doc)
        sink.insertFigmaNodes("fake_key_1", nodes)

        // Assert
        assertEquals(2, sink.callCount)
        assertEquals(doc, sink.documents["fake_key_1"])
        assertEquals(3, sink.nodes["fake_key_1"]?.size)

        // Act 2: Deletion contract
        sink.deleteFigmaDocument("fake_key_1")

        // Assert 2
        assertEquals(3, sink.callCount)
        assertNull(sink.documents["fake_key_1"])
        assertNull(sink.nodes["fake_key_1"])
    }

    // Pattern 1: Happy Path AAA Contract Test for SqliteGraphStore
    @Test
    fun testSqliteGraphStoreConformance() = withTempSqliteStore { store, _ ->
        // Arrange
        val fileKey = "sqlite_key_1"
        val doc = createSampleDoc(fileKey)
        val nodes = createSampleNodes(fileKey, 5)

        // Act
        store.insertFigmaDocument(doc)
        store.insertFigmaNodes(fileKey, nodes)

        // Assert
        val retrievedDoc = store.getFigmaDocument(fileKey)
        assertNotNull(retrievedDoc)
        assertEquals(doc.name, retrievedDoc.name)
        val retrievedCount = store.countFigmaNodes(fileKey)
        assertEquals(5, retrievedCount)

        // Act 2: Deletion contract
        store.deleteFigmaDocument(fileKey)

        // Assert 2: Cascade removal
        assertNull(store.getFigmaDocument(fileKey))
        assertEquals(0, store.countFigmaNodes(fileKey))
    }

    // Pattern 2: Sad Path, Chaos & Rollback Verification
    @Test
    fun testStoreSinkSadPathRollbackWhenFailureInjected() {
        // Arrange: Configure fake to simulate database lock/corruption
        val sink = FakeStoreSink()
        sink.shouldFail = true
        val doc = createSampleDoc("rollback_key")

        // Act & Assert: Exception check verifying state rollback invariant
        assertFailsWith<IllegalStateException> {
            sink.insertFigmaDocument(doc)
        }

        // Assert Rollback: downstream state remains untouched, side-effect callCount == 0
        assertEquals(0, sink.callCount)
        assertEquals(0, sink.insertDocumentCallCount)
        assertTrue(sink.documents.isEmpty())
        assertTrue(sink.nodes.isEmpty())
    }

    // Pattern 3: Parameterized Edge Cases & Boundary Value Analysis (BVA)
    @Test
    fun testBoundaryValueAnalysisAcrossSinkImplementations() {
        val boundaryCases = listOf(
            SinkBoundaryCase("BVA Minimum / Empty", nodeCount = 0, varCount = 0, edgeCount = 0, fileKey = "bva_empty"),
            SinkBoundaryCase("BVA Single Item Boundary", nodeCount = 1, varCount = 1, edgeCount = 1, fileKey = "bva_single"),
            SinkBoundaryCase("BVA Nominal Load", nodeCount = 20, varCount = 5, edgeCount = 10, fileKey = "bva_nominal"),
            SinkBoundaryCase("BVA Batch Ceiling", nodeCount = 100, varCount = 20, edgeCount = 50, fileKey = "bva_large")
        )

        for (case in boundaryCases) {
            // Test FakeStoreSink conformance across boundaries
            val fakeSink = FakeStoreSink()
            val doc = createSampleDoc(case.fileKey)
            fakeSink.insertFigmaDocument(doc)
            fakeSink.insertFigmaNodes(case.fileKey, createSampleNodes(case.fileKey, case.nodeCount))

            val fakeNodeCount = fakeSink.nodes[case.fileKey]?.size ?: 0
            assertEquals(case.nodeCount, fakeNodeCount, "Fake failed on case ${case.label}")
            assertEquals(2, fakeSink.callCount, "Fake call count mismatch on case ${case.label}")

            // Test SqliteGraphStore conformance across boundaries
            withTempSqliteStore { store, _ ->
                store.insertFigmaDocument(doc)
                if (case.nodeCount > 0) {
                    store.insertFigmaNodes(case.fileKey, createSampleNodes(case.fileKey, case.nodeCount))
                }
                val retrievedCount = store.countFigmaNodes(case.fileKey)
                assertEquals(case.nodeCount, retrievedCount, "SQLite failed on case ${case.label}")
            }
        }
    }
}
