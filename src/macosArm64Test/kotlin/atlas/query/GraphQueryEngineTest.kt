package atlas.query

import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.testdoubles.FakeGraphStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphQueryEngineTest {

    @Test
    fun testCycleDetectionDetectsCircularDependencies() {
        val store = FakeGraphStore()
        val project = "cycle-test"

        val nA = GraphNode(nodeId = "A", projectId = project, fileId = "A.kt", name = "A", qualifiedName = "A", kind = "class")
        val nB = GraphNode(nodeId = "B", projectId = project, fileId = "B.kt", name = "B", qualifiedName = "B", kind = "class")
        val nC = GraphNode(nodeId = "C", projectId = project, fileId = "C.kt", name = "C", qualifiedName = "C", kind = "class")

        val e1 = GraphEdge(edgeId = "A->B", projectId = project, sourceNodeId = "A", targetNodeId = "B", edgeType = "calls")
        val e2 = GraphEdge(edgeId = "B->C", projectId = project, sourceNodeId = "B", targetNodeId = "C", edgeType = "calls")
        val e3 = GraphEdge(edgeId = "C->A", projectId = project, sourceNodeId = "C", targetNodeId = "A", edgeType = "calls")

        store.upsertNodesAndEdges(project, listOf(nA, nB, nC), listOf(e1, e2, e3))

        val engine = GraphQueryEngine(store, project)
        val cycles = engine.findCycles()

        assertEquals(1, cycles.size)
        val cycle = cycles.first()
        assertEquals(4, cycle.size)
        assertEquals(cycle.first(), cycle.last())
        assertTrue(cycle.contains("A"))
        assertTrue(cycle.contains("B"))
        assertTrue(cycle.contains("C"))

        // Topological sort must fail on a cyclic graph
        val order = engine.topologicalSort()
        assertNull(order)
    }

    @Test
    fun testTopologicalSortOnDAG() {
        val store = FakeGraphStore()
        val project = "dag-test"

        val nCtrl = GraphNode(nodeId = "Controller", projectId = project, fileId = "C.kt", name = "Controller", qualifiedName = "Controller", kind = "class")
        val nSvc = GraphNode(nodeId = "Service", projectId = project, fileId = "S.kt", name = "Service", qualifiedName = "Service", kind = "class")
        val nRepo = GraphNode(nodeId = "Repository", projectId = project, fileId = "R.kt", name = "Repository", qualifiedName = "Repository", kind = "class")

        val e1 = GraphEdge(edgeId = "C->S", projectId = project, sourceNodeId = "Controller", targetNodeId = "Service", edgeType = "calls")
        val e2 = GraphEdge(edgeId = "S->R", projectId = project, sourceNodeId = "Service", targetNodeId = "Repository", edgeType = "calls")

        store.upsertNodesAndEdges(project, listOf(nCtrl, nSvc, nRepo), listOf(e1, e2))

        val engine = GraphQueryEngine(store, project)
        val cycles = engine.findCycles()
        assertEquals(0, cycles.size)

        val order = engine.topologicalSort()
        assertNotNull(order)
        assertEquals(3, order.size)

        // Controller must appear before Service, Service before Repository
        val ctrlIdx = order.indexOf("Controller")
        val svcIdx = order.indexOf("Service")
        val repoIdx = order.indexOf("Repository")
        assertTrue(ctrlIdx < svcIdx)
        assertTrue(svcIdx < repoIdx)
    }

    @Test
    fun testIslandRootAndLeafDiscovery() {
        val store = FakeGraphStore()
        val project = "topo-shape-test"

        val nIsland = GraphNode(nodeId = "Island", projectId = project, fileId = "I.kt", name = "Island", qualifiedName = "Island", kind = "class", inDegree = 0, outDegree = 0)
        val nRoot = GraphNode(nodeId = "Root", projectId = project, fileId = "R.kt", name = "Root", qualifiedName = "Root", kind = "class", inDegree = 0, outDegree = 1)
        val nLeaf = GraphNode(nodeId = "Leaf", projectId = project, fileId = "L.kt", name = "Leaf", qualifiedName = "Leaf", kind = "class", inDegree = 1, outDegree = 0)

        val edge = GraphEdge(edgeId = "R->L", projectId = project, sourceNodeId = "Root", targetNodeId = "Leaf", edgeType = "calls")

        store.upsertNodesAndEdges(project, listOf(nIsland, nRoot, nLeaf), listOf(edge))

        val engine = GraphQueryEngine(store, project)

        val islands = engine.findIslands()
        assertEquals(1, islands.size)
        assertEquals("Island", islands.first().nodeId)

        val roots = engine.findRoots()
        assertEquals(1, roots.size)
        assertEquals("Root", roots.first().nodeId)

        val leaves = engine.findLeaves()
        assertEquals(1, leaves.size)
        assertEquals("Leaf", leaves.first().nodeId)
    }

    @Test
    fun testFindPathsBetweenMultipleRoutes() {
        val store = FakeGraphStore()
        val project = "paths-test"

        val nA = GraphNode(nodeId = "A", projectId = project, fileId = "A.kt", name = "A", qualifiedName = "A", kind = "class")
        val nB = GraphNode(nodeId = "B", projectId = project, fileId = "B.kt", name = "B", qualifiedName = "B", kind = "class")
        val nC = GraphNode(nodeId = "C", projectId = project, fileId = "C.kt", name = "C", qualifiedName = "C", kind = "class")
        val nD = GraphNode(nodeId = "D", projectId = project, fileId = "D.kt", name = "D", qualifiedName = "D", kind = "class")

        val e1 = GraphEdge(edgeId = "A->B", projectId = project, sourceNodeId = "A", targetNodeId = "B", edgeType = "calls")
        val e2 = GraphEdge(edgeId = "B->D", projectId = project, sourceNodeId = "B", targetNodeId = "D", edgeType = "calls")
        val e3 = GraphEdge(edgeId = "A->C", projectId = project, sourceNodeId = "A", targetNodeId = "C", edgeType = "calls")
        val e4 = GraphEdge(edgeId = "C->D", projectId = project, sourceNodeId = "C", targetNodeId = "D", edgeType = "calls")

        store.upsertNodesAndEdges(project, listOf(nA, nB, nC, nD), listOf(e1, e2, e3, e4))

        val engine = GraphQueryEngine(store, project)
        val paths = engine.findPathsBetween("A", "D")

        assertEquals(2, paths.size)
        assertTrue(paths.contains(listOf("A", "B", "D")))
        assertTrue(paths.contains(listOf("A", "C", "D")))
    }

    @Test
    fun testSubgraphExtraction() {
        val store = FakeGraphStore()
        val project = "slice-test"

        val nA = GraphNode(nodeId = "A", projectId = project, fileId = "A.kt", name = "A", qualifiedName = "A", kind = "class")
        val nB = GraphNode(nodeId = "B", projectId = project, fileId = "B.kt", name = "B", qualifiedName = "B", kind = "class")
        val nC = GraphNode(nodeId = "C", projectId = project, fileId = "C.kt", name = "C", qualifiedName = "C", kind = "class")

        val e1 = GraphEdge(edgeId = "A->B", projectId = project, sourceNodeId = "A", targetNodeId = "B", edgeType = "calls")
        val e2 = GraphEdge(edgeId = "B->C", projectId = project, sourceNodeId = "B", targetNodeId = "C", edgeType = "calls")

        store.upsertNodesAndEdges(project, listOf(nA, nB, nC), listOf(e1, e2))

        val engine = GraphQueryEngine(store, project)

        // Downstream from B: should only contain B and C
        val downstream = engine.subgraph("B", depth = 1, direction = GraphQueryEngine.TraversalDirection.Downstream)
        assertEquals("B", downstream.seedNodeId)
        val downstreamNodeIds = downstream.nodes.map { it.nodeId }.toSet()
        assertEquals(setOf("B", "C"), downstreamNodeIds)
        assertEquals(1, downstream.edges.size)
        assertEquals("B->C", downstream.edges.first().edgeId)

        // Upstream from B: should only contain B and A
        val upstream = engine.subgraph("B", depth = 1, direction = GraphQueryEngine.TraversalDirection.Upstream)
        val upstreamNodeIds = upstream.nodes.map { it.nodeId }.toSet()
        assertEquals(setOf("B", "A"), upstreamNodeIds)
        assertEquals(1, upstream.edges.size)
        assertEquals("A->B", upstream.edges.first().edgeId)
    }
}
