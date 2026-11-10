package atlas.testdoubles

import atlas.model.ComplexityScoreRecord
import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.FileRecordUpsert
import atlas.model.GraphEdge
import atlas.model.GraphNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeGraphStoreTest {

    @Test
    fun `fake graph store preserves metadata and annotations`() {
        val store = FakeGraphStore()
        store.setProjectMetadata("test-proj", "version", "1.4.2")
        assertEquals("1.4.2", store.getProjectMetadata("test-proj", "version"))

        store.insertNodeAnnotation(
            annotationId = "ann-1",
            projectId = "test-proj",
            nodeId = "node-1",
            outcome = "FIXED",
            note = "Remediated complexity",
            createdAtEpoch = 1000.0
        )
        val anns = store.getNodeAnnotations("test-proj", "node-1")
        assertEquals(1, anns.size)
        assertEquals("FIXED", anns[0].outcome)
    }

    @Test
    fun `fake graph store supports node and edge operations`() {
        val store = FakeGraphStore()
        val node1 = GraphNode(
            nodeId = "sym:OrderService",
            projectId = "p1",
            fileId = "p1:src/OrderService.kt",
            name = "OrderService",
            qualifiedName = "com.example.OrderService",
            kind = "class",
            startLine = 10,
            endLine = 50,
            archLayer = "domain",
            semioticLayer = "semantic"
        )
        val node2 = GraphNode(
            nodeId = "sym:PaymentGateway",
            projectId = "p1",
            fileId = "p1:src/PaymentGateway.kt",
            name = "PaymentGateway",
            qualifiedName = "com.example.PaymentGateway",
            kind = "interface",
            startLine = 5,
            endLine = 20,
            archLayer = "domain",
            semioticLayer = "semantic"
        )
        val edge = GraphEdge(
            edgeId = "edge-1",
            projectId = "p1",
            sourceNodeId = "sym:OrderService",
            targetNodeId = "sym:PaymentGateway",
            edgeType = "CALLS"
        )

        store.upsertNodesAndEdges("p1", listOf(node1, node2), listOf(edge))
        store.recomputeDegrees("p1")

        val fetched = store.getNode("p1", "OrderService")
        assertNotNull(fetched)
        assertEquals("sym:OrderService", fetched.nodeId)
        assertEquals(1, fetched.outDegree)

        val callees = store.getCallees("p1", "sym:OrderService")
        assertEquals(1, callees.size)
        assertEquals("PaymentGateway", callees[0].name)

        val callers = store.getCallers("p1", "sym:PaymentGateway")
        assertEquals(1, callers.size)
        assertEquals("OrderService", callers[0].name)

        val impact = store.getImpactRadius("p1", "sym:OrderService")
        assertEquals(1, impact.callees.size)

        val stats = store.getStats("p1")
        assertEquals(2, stats.nodes)
        assertEquals(1, stats.edges)
    }

    @Test
    fun `fake graph store handles files and compose nodes`() {
        val store = FakeGraphStore()
        store.upsertFileRecord(
            FileRecordUpsert(
                projectId = "p1",
                relPath = "src/Ui.kt",
                language = "kotlin",
                sha256 = "hash1",
                loc = 100
            )
        )
        assertEquals(1, store.listFiles("p1").size)
        assertEquals(1, store.getFileRecordsMap("p1").size)

        val composeNode = ComposeNodeRecord(
            nodeId = "comp-1",
            projectSlug = "p1",
            fqName = "com.example.OrderScreen",
            filePath = "src/Ui.kt",
            startLine = 15,
            endLine = 45
        )
        val composeParam = ComposeParamRecord(
            nodeId = "comp-1",
            paramIndex = 0,
            paramName = "orderId",
            typeSignature = "String",
            stability = "STABLE",
            stabilityReason = "Primitive string"
        )
        store.upsertComposeNodesAndParams(listOf(composeNode), listOf(composeParam))

        val retrievedNode = store.getComposeNode("p1", "com.example.OrderScreen")
        assertNotNull(retrievedNode)
        assertEquals("comp-1", retrievedNode.nodeId)

        val params = store.listComposeParams("comp-1")
        assertEquals(1, params.size)
        assertEquals("String", params[0].typeSignature)

        store.deleteComposeNodesForFile("p1", "src/Ui.kt")
        assertNull(store.getComposeNode("p1", "com.example.OrderScreen"))
    }
}
