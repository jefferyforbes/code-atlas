package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Architectural complexity violation record flagging functions exceeding cognitive or cyclomatic thresholds.
 */
@Serializable
data class ComplexityViolation(
    @SerialName("rule_id") val ruleId: String = "COMPLEXITY-001",
    val severity: String = "WARNING",
    val message: String,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("line_span") val lineSpan: String = "",
    @SerialName("risk_index") val riskIndex: Double = 0.0,
    @SerialName("cognitive_complexity") val cognitiveComplexity: Int = 0,
    @SerialName("cyclomatic_complexity") val cyclomaticComplexity: Int = 1
)
