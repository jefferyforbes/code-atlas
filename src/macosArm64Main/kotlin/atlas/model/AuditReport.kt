package atlas.model

import kotlinx.serialization.Serializable

/**
 * Full architecture audit report.
 */
@Serializable
data class AuditReport(
    val projectId: String,
    val passed: Boolean,
    val singleDeclarationViolations: List<SingleDeclarationViolation>,
    val layerInversions: List<LayerInversionViolation>,
    val plan3dCollisions: List<PlanCollision>,
    val descriptionSuggestions: List<DescriptionSuggestion> = emptyList()
)
