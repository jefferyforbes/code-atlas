package atlas.model

import kotlinx.serialization.Serializable

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
