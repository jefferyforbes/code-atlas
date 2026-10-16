package atlas.model

import kotlinx.serialization.Serializable

/**
 * Directed relationship edge between Figma nodes, components, and variables.
 */
@Serializable
data class FigmaEdge(
    val edgeId: String,
    val fileKey: String,
    val sourceNodeId: String,
    val targetNodeId: String,
    val edgeType: String,
    val propertySlot: String = ""
)
