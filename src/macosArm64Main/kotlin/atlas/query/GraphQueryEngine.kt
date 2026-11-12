package atlas.query

import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.model.GraphReader

/**
 * High-performance graph traversal and topological analysis engine for Code Atlas.
 * Provides cycle detection, topological sorting, topological shape discovery, and subgraph slicing.
 */
class GraphQueryEngine(
    val store: GraphReader,
    val projectSlug: String
) {

    /**
     * Traversal direction for neighborhood graph extraction.
     */
    enum class TraversalDirection {
        Upstream,
        Downstream,
        Both
    }

    /**
     * Encapsulates an extracted subgraph slice of nodes and connecting edges.
     */
    data class GraphSlice(
        val seedNodeId: String,
        val depth: Int,
        val nodes: List<GraphNode>,
        val edges: List<GraphEdge>
    )

    /**
     * Detect all directed cycles (circular dependencies) up to [maxCycles].
     * Returns each cycle as a list of node IDs where the first and last element match.
     */
    fun findCycles(
        edgeTypes: List<String>? = null,
        maxCycles: Int = 50
    ): List<List<String>> {
        val edges = store.listEdges(projectSlug, edgeTypes)
        val allNodes = store.listNodes(projectSlug)
        val nodeIds = allNodes.map { it.nodeId }.toSet()
        val adj = buildAdjacencyList(edges, nodeIds)

        val visited = mutableSetOf<String>()
        val path = mutableListOf<String>()
        val inPath = mutableSetOf<String>()
        val detectedCycles = mutableListOf<List<String>>()
        val normalizedCycles = mutableSetOf<String>()

        fun dfs(current: String) {
            if (detectedCycles.size >= maxCycles) return
            visited.add(current)
            path.add(current)
            inPath.add(current)

            val neighbors = adj[current] ?: emptyList()
            for (next in neighbors) {
                if (detectedCycles.size >= maxCycles) break
                if (next !in visited) {
                    dfs(next)
                } else if (next in inPath) {
                    recordCycle(path, next, normalizedCycles, detectedCycles)
                }
            }

            inPath.remove(current)
            path.removeAt(path.size - 1)
        }

        for (node in allNodes) {
            if (node.nodeId !in visited) {
                dfs(node.nodeId)
            }
        }

        return detectedCycles
    }

    private fun buildAdjacencyList(edges: List<GraphEdge>, nodeIds: Set<String>): Map<String, List<String>> {
        val adj = mutableMapOf<String, MutableList<String>>()
        for (edge in edges) {
            if (edge.sourceNodeId in nodeIds && edge.targetNodeId in nodeIds) {
                adj.getOrPut(edge.sourceNodeId) { mutableListOf() }.add(edge.targetNodeId)
            }
        }
        return adj
    }

    private fun recordCycle(
        path: List<String>,
        next: String,
        normalizedCycles: MutableSet<String>,
        detectedCycles: MutableList<List<String>>
    ) {
        val cycleStartIndex = path.indexOf(next)
        if (cycleStartIndex >= 0) {
            val cycle = path.subList(cycleStartIndex, path.size).toList() + next
            val normalizedKey = normalizeCycleKey(cycle)
            if (normalizedKey !in normalizedCycles) {
                normalizedCycles.add(normalizedKey)
                detectedCycles.add(cycle)
            }
        }
    }

    /**
     * Compute a topological ordering of all nodes using Kahn's algorithm.
     * Returns null if the graph contains cycles (not a DAG).
     */
    fun topologicalSort(edgeTypes: List<String>? = null): List<String>? {
        val allNodes = store.listNodes(projectSlug)
        val edges = store.listEdges(projectSlug, edgeTypes)
        val nodeIds = allNodes.map { it.nodeId }.toSet()

        val inDegree = nodeIds.associateWith { 0 }.toMutableMap()
        val adj = mutableMapOf<String, MutableList<String>>()
        buildTopologicalAdjacency(edges, nodeIds, adj, inDegree)

        val queue = ArrayDeque<String>()
        for ((nodeId, deg) in inDegree) {
            if (deg == 0) {
                queue.add(nodeId)
            }
        }

        val result = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            result.add(u)
            processTopologicalNode(u, adj, inDegree, queue)
        }

        return if (result.size == nodeIds.size) result else null
    }

    private fun buildTopologicalAdjacency(
        edges: List<GraphEdge>,
        nodeIds: Set<String>,
        adj: MutableMap<String, MutableList<String>>,
        inDegree: MutableMap<String, Int>
    ) {
        for (edge in edges) {
            if (edge.sourceNodeId in nodeIds && edge.targetNodeId in nodeIds) {
                adj.getOrPut(edge.sourceNodeId) { mutableListOf() }.add(edge.targetNodeId)
                inDegree[edge.targetNodeId] = (inDegree[edge.targetNodeId] ?: 0) + 1
            }
        }
    }

    private fun processTopologicalNode(
        u: String,
        adj: Map<String, List<String>>,
        inDegree: MutableMap<String, Int>,
        queue: ArrayDeque<String>
    ) {
        val neighbors = adj[u] ?: emptyList()
        for (v in neighbors) {
            val currentDeg = (inDegree[v] ?: 1) - 1
            inDegree[v] = currentDeg
            if (currentDeg == 0) {
                queue.add(v)
            }
        }
    }

    private fun resolveEffectiveDegrees(): Pair<Map<String, Int>, Map<String, Int>> {
        val edges = store.listEdges(projectSlug)
        val inDeg = mutableMapOf<String, Int>()
        val outDeg = mutableMapOf<String, Int>()
        for (edge in edges) {
            inDeg[edge.targetNodeId] = (inDeg[edge.targetNodeId] ?: 0) + 1
            outDeg[edge.sourceNodeId] = (outDeg[edge.sourceNodeId] ?: 0) + 1
        }
        return Pair(inDeg, outDeg)
    }

    /**
     * Find isolated nodes with zero in-degree and zero out-degree.
     */
    fun findIslands(): List<GraphNode> {
        val (inDeg, outDeg) = resolveEffectiveDegrees()
        return store.listNodes(projectSlug).filter { node ->
            val inD = if (node.inDegree > 0) node.inDegree else (inDeg[node.nodeId] ?: 0)
            val outD = if (node.outDegree > 0) node.outDegree else (outDeg[node.nodeId] ?: 0)
            inD == 0 && outD == 0
        }
    }

    /**
     * Find root entrypoint nodes with zero in-degree and positive out-degree.
     */
    fun findRoots(): List<GraphNode> {
        val (inDeg, outDeg) = resolveEffectiveDegrees()
        return store.listNodes(projectSlug).filter { node ->
            val inD = if (node.inDegree > 0) node.inDegree else (inDeg[node.nodeId] ?: 0)
            val outD = if (node.outDegree > 0) node.outDegree else (outDeg[node.nodeId] ?: 0)
            inD == 0 && outD > 0
        }
    }

    /**
     * Find terminal leaf nodes with positive in-degree and zero out-degree.
     */
    fun findLeaves(): List<GraphNode> {
        val (inDeg, outDeg) = resolveEffectiveDegrees()
        return store.listNodes(projectSlug).filter { node ->
            val inD = if (node.inDegree > 0) node.inDegree else (inDeg[node.nodeId] ?: 0)
            val outD = if (node.outDegree > 0) node.outDegree else (outDeg[node.nodeId] ?: 0)
            inD > 0 && outD == 0
        }
    }

    /**
     * Find all simple directed paths between [sourceId] and [targetId] up to [maxHops].
     */
    fun findPathsBetween(
        sourceId: String,
        targetId: String,
        maxHops: Int = 6,
        limit: Int = 10
    ): List<List<String>> {
        val srcNode = store.getNode(projectSlug, sourceId)?.nodeId ?: sourceId
        val tgtNode = store.getNode(projectSlug, targetId)?.nodeId ?: targetId

        if (srcNode == tgtNode) return listOf(listOf(srcNode))

        val allEdges = store.listEdges(projectSlug)
        val adj = mutableMapOf<String, MutableList<String>>()
        for (edge in allEdges) {
            adj.getOrPut(edge.sourceNodeId) { mutableListOf() }.add(edge.targetNodeId)
        }

        val results = mutableListOf<List<String>>()
        val currentPath = mutableListOf(srcNode)
        val onPath = mutableSetOf(srcNode)

        fun search(current: String) {
            if (results.size >= limit) return
            if (currentPath.size - 1 > maxHops) return

            if (current == tgtNode) {
                results.add(currentPath.toList())
                return
            }

            val neighbors = adj[current] ?: emptyList()
            for (next in neighbors) {
                if (next !in onPath) {
                    onPath.add(next)
                    currentPath.add(next)
                    search(next)
                    currentPath.removeAt(currentPath.size - 1)
                    onPath.remove(next)
                }
            }
        }

        search(srcNode)
        return results
    }

    /**
     * Extract a connected neighborhood subgraph slice around [seedTarget].
     */
    fun subgraph(
        seedTarget: String,
        depth: Int = 2,
        direction: TraversalDirection = TraversalDirection.Both
    ): GraphSlice {
        val resolvedSeed = store.getNode(projectSlug, seedTarget)?.nodeId ?: seedTarget
        val allEdges = store.listEdges(projectSlug)
        val (forwardAdj, backwardAdj) = buildSubgraphAdjacency(allEdges)

        val visitedNodes = mutableSetOf(resolvedSeed)
        val collectedEdges = mutableSetOf<GraphEdge>()
        var currentFrontier = setOf(resolvedSeed)

        for (step in 1..depth) {
            if (currentFrontier.isEmpty()) break
            currentFrontier = stepFrontier(currentFrontier, direction, forwardAdj, backwardAdj, visitedNodes, collectedEdges)
        }

        val allNodesMap = store.listNodes(projectSlug).associateBy { it.nodeId }
        val finalNodes = visitedNodes.mapNotNull { allNodesMap[it] }

        return GraphSlice(
            seedNodeId = resolvedSeed,
            depth = depth,
            nodes = finalNodes,
            edges = collectedEdges.toList()
        )
    }

    private fun buildSubgraphAdjacency(allEdges: List<GraphEdge>): Pair<Map<String, List<GraphEdge>>, Map<String, List<GraphEdge>>> {
        val forwardAdj = mutableMapOf<String, MutableList<GraphEdge>>()
        val backwardAdj = mutableMapOf<String, MutableList<GraphEdge>>()
        for (edge in allEdges) {
            forwardAdj.getOrPut(edge.sourceNodeId) { mutableListOf() }.add(edge)
            backwardAdj.getOrPut(edge.targetNodeId) { mutableListOf() }.add(edge)
        }
        return Pair(forwardAdj, backwardAdj)
    }

    private fun stepFrontier(
        currentFrontier: Set<String>,
        direction: TraversalDirection,
        forwardAdj: Map<String, List<GraphEdge>>,
        backwardAdj: Map<String, List<GraphEdge>>,
        visitedNodes: MutableSet<String>,
        collectedEdges: MutableSet<GraphEdge>
    ): Set<String> {
        val nextFrontier = mutableSetOf<String>()
        val checkDownstream = direction == TraversalDirection.Downstream || direction == TraversalDirection.Both
        val checkUpstream = direction == TraversalDirection.Upstream || direction == TraversalDirection.Both

        for (curr in currentFrontier) {
            if (checkDownstream) {
                stepDownstream(curr, forwardAdj, visitedNodes, collectedEdges, nextFrontier)
            }
            if (checkUpstream) {
                stepUpstream(curr, backwardAdj, visitedNodes, collectedEdges, nextFrontier)
            }
        }
        return nextFrontier
    }

    private fun stepDownstream(
        curr: String,
        forwardAdj: Map<String, List<GraphEdge>>,
        visitedNodes: MutableSet<String>,
        collectedEdges: MutableSet<GraphEdge>,
        nextFrontier: MutableSet<String>
    ) {
        val outEdges = forwardAdj[curr] ?: emptyList()
        for (e in outEdges) {
            collectedEdges.add(e)
            if (visitedNodes.add(e.targetNodeId)) {
                nextFrontier.add(e.targetNodeId)
            }
        }
    }

    private fun stepUpstream(
        curr: String,
        backwardAdj: Map<String, List<GraphEdge>>,
        visitedNodes: MutableSet<String>,
        collectedEdges: MutableSet<GraphEdge>,
        nextFrontier: MutableSet<String>
    ) {
        val inEdges = backwardAdj[curr] ?: emptyList()
        for (e in inEdges) {
            collectedEdges.add(e)
            if (visitedNodes.add(e.sourceNodeId)) {
                nextFrontier.add(e.sourceNodeId)
            }
        }
    }

    private fun normalizeCycleKey(cycle: List<String>): String {
        val core = if (cycle.first() == cycle.last() && cycle.size > 1) cycle.dropLast(1) else cycle
        if (core.isEmpty()) return ""
        var minIdx = 0
        var minVal = core[0]
        for (i in 1 until core.size) {
            if (core[i] < minVal) {
                minVal = core[i]
                minIdx = i
            }
        }
        val rotated = core.subList(minIdx, core.size) + core.subList(0, minIdx)
        return rotated.joinToString("->")
    }
}
