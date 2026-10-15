package atlas.model

import kotlinx.serialization.Serializable

/**
 * Experiential annotation attached to a graph node.
 */
@Serializable
data class NodeAnnotation(
    val annotationId: String,
    val projectId: String,
    val nodeId: String,
    val outcome: String,
    val note: String,
    val createdAtEpoch: Double
)
