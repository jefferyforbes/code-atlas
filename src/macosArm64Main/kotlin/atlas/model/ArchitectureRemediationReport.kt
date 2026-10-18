package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Aggregated report of architecture audit remediations (BOOST-003 and ARCH-LAYER-INVERSION).
 */
@Serializable
data class ArchitectureRemediationReport(
    val project: String,
    @SerialName("total_violations") val totalViolations: Int,
    @SerialName("boost003_count") val boost003Count: Int,
    @SerialName("layer_inversion_count") val layerInversionCount: Int,
    @SerialName("verified_count") val verifiedCount: Int,
    @SerialName("applied_count") val appliedCount: Int,
    @SerialName("dry_run") val dryRun: Boolean,
    val prescriptions: List<ArchitectureRemediationPrescription> = emptyList(),
    val summary: String = ""
)
