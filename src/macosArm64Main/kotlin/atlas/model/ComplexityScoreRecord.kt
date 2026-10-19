package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Detailed complexity metrics, afferent coupling risk index, and maintainability grade for a function or node.
 */
@Serializable
data class ComplexityScoreRecord(
    @SerialName("node_id") val nodeId: String,
    @SerialName("project_id") val projectId: String,
    val name: String = "",
    @SerialName("qualified_name") val qualifiedName: String = "",
    @SerialName("file_path") val filePath: String = "",
    val line: Int = 1,
    val cyclomatic: Int = 1,
    val cognitive: Int = 0,
    @SerialName("max_nesting") val maxNesting: Int = 0,
    @SerialName("in_degree") val inDegree: Int = 0,
    @SerialName("overall_score") val overallScore: Double = 100.0,
    val grade: String = "A",
    @SerialName("risk_index") val riskIndex: Double = 0.0
)
