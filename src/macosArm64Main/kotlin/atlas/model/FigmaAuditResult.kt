package atlas.model

import kotlinx.serialization.Serializable

/**
 * Result of a deterministic 12-invariant design audit for a Figma document.
 */
@Serializable
data class FigmaAuditResult(
    val fileKey: String,
    val passed: Boolean,
    val maturityScore: Double,
    val polishDelta: List<String> = emptyList(),
    val violations: List<FigmaViolation> = emptyList(),
    val totalNodes: Int = 0
)
