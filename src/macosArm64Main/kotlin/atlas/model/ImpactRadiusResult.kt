package atlas.model

import kotlinx.serialization.Serializable

/**
 * Transitive blast radius report across callers, callees, files, modules, and layers.
 */
@Serializable
data class ImpactRadiusResult(
    val seedNodeId: String,
    val depth: Int,
    val impactedNodes: List<GraphNode>,
    val impactedFiles: List<String>,
    val impactedModules: List<String>,
    val impactedLayers: List<String>,
    val callers: List<GraphHop>,
    val callees: List<GraphHop>
)
