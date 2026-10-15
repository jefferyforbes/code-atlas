package atlas.model

import kotlinx.serialization.Serializable

/**
 * Caller or callee graph hop record from recursive CTE traversal.
 */
@Serializable
data class GraphHop(
    val sourceId: String,
    val targetId: String,
    val edgeType: String,
    val provenance: String,
    val hop: Int,
    val name: String,
    val fileId: String,
    val startLine: Int,
    val semioticLayer: String,
    val archLayer: String
)
