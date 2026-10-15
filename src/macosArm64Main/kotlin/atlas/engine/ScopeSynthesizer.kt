package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.model.GraphNode
import atlas.model.ScopeRecommendation

/**
 * 3D Concurrency Scope Synthesizer for /architect and /plan task decomposition.
 */
class ScopeSynthesizer(private val store: SqliteGraphStore) {

    fun synthesizeScope(
        projectId: String,
        seedTargets: List<String>,
        depth: Int = 2
    ): ScopeRecommendation {
        val resolvedNodes = mutableListOf<GraphNode>()
        for (target in seedTargets) {
            val node = store.getNode(projectId, target)
            if (node != null) {
                resolvedNodes.add(node)
            } else {
                val matches = store.searchNodes(projectId, target, limit = 3)
                resolvedNodes.addAll(matches)
            }
        }

        val filesSet = mutableSetOf<String>()
        val modulesSet = mutableSetOf<String>()
        val archSet = mutableSetOf<String>()
        val impactedNodeIds = mutableSetOf<String>()

        for (seed in resolvedNodes) {
            val impact = store.getImpactRadius(projectId, seed.nodeId, depth = depth)
            for (fPath in impact.impactedFiles) {
                if (!fPath.startsWith("plans/")) {
                    filesSet.add(fPath)
                }
            }
            for (mod in impact.impactedModules) {
                if (mod.isNotEmpty() && mod != "plans") {
                    modulesSet.add(mod)
                }
            }
            for (layer in impact.impactedLayers) {
                if (!layer.endsWith(":infrastructure")) {
                    archSet.add(layer)
                }
            }
            for (n in impact.impactedNodes) {
                impactedNodeIds.add(n.nodeId)
            }
        }

        val godNodes = store.getGodNodes(projectId, topN = 10)
        val godTouched = godNodes.filter { it.nodeId in impactedNodeIds }.map { it.name }

        val testFixtures = filesSet.filter { fPath ->
            fPath.contains("fixtures/") || fPath.lowercase().contains("fake_") || fPath.lowercase().contains("commontest")
        }.sorted()

        val collidingPlans = mutableListOf<String>()
        val planEdges = store.listEdges(projectId, edgeTypes = listOf("PLAN_SCOPES_FILE"))
        for (edge in planEdges) {
            if (edge.targetNodeId in impactedNodeIds) {
                val planNode = store.getNode(projectId, edge.sourceNodeId)
                val planName = planNode?.name ?: edge.sourceNodeId
                if (planName !in collidingPlans) {
                    collidingPlans.add(planName)
                }
            }
        }

        return ScopeRecommendation(
            projectId = projectId,
            seedTargets = seedTargets,
            files = filesSet.sorted(),
            modules = modulesSet.sorted(),
            architecture = archSet.sorted(),
            godNodesTouched = godTouched,
            requiredTestFixtures = testFixtures,
            collidingPlans = collidingPlans.sorted()
        )
    }
}
