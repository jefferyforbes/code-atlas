package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Deterministic Jetpack Compose Gate 1 compliance and stability audit evaluation result.
 */
@Serializable
data class ComposeAuditResult(
    val project: String,
    val status: String,
    @SerialName("overall_score") val overallScore: Double,
    @SerialName("raw_unclamped_score") val rawUnclampedScore: Double,
    @SerialName("gate1_target") val gate1Target: Double = 95.0,
    @SerialName("pass_threshold") val passThreshold: Double = 80.0,
    @SerialName("total_composables") val totalComposables: Int,
    @SerialName("skippable_composables") val skippableComposables: Int,
    @SerialName("strong_skipping_mode") val strongSkippingMode: Boolean = true,
    @SerialName("violation_counts") val violationCounts: Map<String, Int>,
    val violations: List<Violation>,
    @SerialName("reality_check") val realityCheck: List<RealityItem> = emptyList(),
    @SerialName("remaining_5_percent_polish_delta") val remainingPolishDelta: List<PolishItem>
) {
    @Serializable
    data class Violation(
        @SerialName("rule_id") val ruleId: String,
        val severity: String,
        @SerialName("point_deduction") val pointDeduction: Double,
        @SerialName("node_id") val nodeId: String,
        @SerialName("file_path") val filePath: String,
        val line: Int,
        val message: String,
        val remediation: String,
        val provenance: String
    )

    @Serializable
    data class RealityItem(
        @SerialName("node_id") val nodeId: String,
        val fqname: String,
        val classification: String,
        @SerialName("static_skippable") val staticSkippable: Boolean,
        @SerialName("unstable_param_count") val unstableParamCount: Int,
        @SerialName("recomposition_count") val recompositionCount: Int,
        @SerialName("skip_count") val skipCount: Int,
        @SerialName("dirty_params") val dirtyParams: List<String> = emptyList()
    )

    @Serializable
    data class PolishItem(
        val category: String,
        val target: String,
        val recommendation: String
    )
}
