package atlas

import atlas.model.AuditReport
import atlas.model.GraphHop
import atlas.model.GraphNode
import atlas.model.GraphStats
import atlas.model.GraphStore
import atlas.model.ImpactRadiusResult
import atlas.model.SpikeAnalysisResult
import atlas.model.SpikeRequest
import kotlinx.serialization.json.JsonObject

/**
 * Unified public client interface for programmatic interaction with Code Atlas.
 * Provides workspace indexing, AST queries, architectural audits, and topological spikes.
 */
interface AtlasClient : AutoCloseable {
    val projectSlug: String
    val workspacePath: String
    val store: GraphStore

    /**
     * Incrementally or fully index the workspace source files into the knowledge graph.
     */
    fun index(incremental: Boolean = true): JsonObject

    /**
     * Retrieve a specific node by name or identifier.
     */
    fun getNode(target: String): GraphNode?

    /**
     * Find upstream callers of a symbol up to [depth] hops.
     */
    fun callers(target: String, depth: Int = 2): List<GraphHop>

    /**
     * Find downstream callees of a symbol up to [depth] hops.
     */
    fun callees(target: String, depth: Int = 2): List<GraphHop>

    /**
     * Compute downstream blast-radius and impact tree for a symbol.
     */
    fun impactRadius(target: String, depth: Int = 2): ImpactRadiusResult

    /**
     * Find shortest dependency path between two symbols.
     */
    fun shortestPath(sourceNodeId: String, targetNodeId: String, maxHops: Int = 6): List<String>

    /**
     * Search symbols across the project by query and optional kind filter.
     */
    fun searchNodes(query: String, limit: Int = 15, kind: String? = null): List<GraphNode>

    /**
     * Query overall graph metrics and storage counts.
     */
    fun stats(): GraphStats

    /**
     * Run the Clean Architecture, layer boundaries, and Single Declaration audit.
     */
    fun auditArchitecture(): AuditReport

    /**
     * Execute a topological spike hypothesis test.
     */
    fun runSpike(request: SpikeRequest): SpikeAnalysisResult

    /**
     * Convenience method to execute a topological spike hypothesis test.
     */
    fun runSpike(
        hypothesis: String,
        from: String? = null,
        to: String? = null,
        target: String? = null,
        maxHops: Int = 10
    ): SpikeAnalysisResult = runSpike(
        SpikeRequest(
            projectId = projectSlug,
            hypothesis = hypothesis,
            from = from,
            to = to,
            target = target,
            maxHops = maxHops
        )
    )
}
