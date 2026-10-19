package atlas.model

import kotlinx.serialization.Serializable

/**
 * Directed relationship edge connecting two nodes in the polyglot knowledge graph with provenance and confidence metadata.
 */
@Serializable
data class GraphEdge(
    val edgeId: String,
    val projectId: String,
    val sourceNodeId: String,
    val targetNodeId: String,
    val edgeType: String,
    val provenance: String = "EXTRACTED_AST",
    val confidence: Double = 1.0,
    val lineNumber: Int = 1
)
