package atlas.model

import kotlinx.serialization.Serializable

/**
 * Compact summary statistics for an indexed project.
 */
@Serializable
data class GraphStats(
    val projectId: String,
    val files: Int,
    val nodes: Int,
    val edges: Int,
    val provenanceBreakdown: Map<String, Int>
)
