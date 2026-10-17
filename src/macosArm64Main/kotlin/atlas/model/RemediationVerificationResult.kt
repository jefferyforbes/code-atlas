package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of pre-flight validation, staged application, and post-remediation verification.
 */
@Serializable
data class RemediationVerificationResult(
    val valid: Boolean,
    val phase: String,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("file_path") val filePath: String,
    @SerialName("pre_flight_passed") val preFlightPassed: Boolean,
    @SerialName("ast_valid") val astValid: Boolean,
    @SerialName("checks_passed") val checksPassed: Boolean,
    @SerialName("tests_preserved") val testsPreserved: Boolean,
    val message: String,
    val details: List<String> = emptyList(),
    @SerialName("verified_diff") val verifiedDiff: String? = null
)
