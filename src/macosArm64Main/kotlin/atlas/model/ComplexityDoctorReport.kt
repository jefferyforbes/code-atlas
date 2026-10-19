package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Prioritized complexity reduction prescriptions and automated remediation outcome report.
 */
@Serializable
data class ComplexityDoctorReport(
    val project: String,
    @SerialName("total_prescriptions") val totalPrescriptions: Int,
    @SerialName("verified_count") val verifiedCount: Int = 0,
    @SerialName("rolled_back_count") val rolledBackCount: Int = 0,
    val prescriptions: List<Prescription> = emptyList()
) {
    @Serializable
    data class Prescription(
        val rank: Int,
        @SerialName("priority_score") val priorityScore: Double,
        @SerialName("rule_id") val ruleId: String,
        @SerialName("fix_strategy") val fixStrategy: String,
        @SerialName("write_site_safe") val writeSiteSafe: Boolean,
        @SerialName("target_node_id") val targetNodeId: String,
        @SerialName("file_path") val filePath: String,
        val line: Int,
        @SerialName("cognitive_before") val cognitiveBefore: Int = 0,
        @SerialName("cognitive_after") val cognitiveAfter: Int = 0,
        @SerialName("reduction_percent") val reductionPercent: Double = 0.0,
        val rationale: String,
        @SerialName("before_snippet") val beforeSnippet: String,
        @SerialName("after_snippet") val afterSnippet: String
    )
}
