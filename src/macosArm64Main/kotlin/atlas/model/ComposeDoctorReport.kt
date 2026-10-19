package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Prescriptive remediation report synthesizing prioritized refactoring actions for unstable Compose functions.
 */
@Serializable
data class ComposeDoctorReport(
    val project: String,
    @SerialName("scoring_mode") val scoringMode: String,
    @SerialName("total_prescriptions") val totalPrescriptions: Int,
    val prescriptions: List<Prescription>
) {
    @Serializable
    data class Prescription(
        val rank: Int,
        @SerialName("priority_score") val priorityScore: Double,
        @SerialName("scoring_mode") val scoringMode: String,
        @SerialName("rule_id") val ruleId: String,
        @SerialName("fix_strategy") val fixStrategy: String,
        @SerialName("write_site_safe") val writeSiteSafe: Boolean,
        @SerialName("target_node_id") val targetNodeId: String,
        @SerialName("file_path") val filePath: String,
        val line: Int,
        @SerialName("downstream_cascade_count") val downstreamCascadeCount: Int,
        val rationale: String,
        @SerialName("before_snippet") val beforeSnippet: String,
        @SerialName("after_snippet") val afterSnippet: String
    )
}
