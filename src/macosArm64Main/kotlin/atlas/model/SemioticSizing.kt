package atlas.model

import kotlinx.serialization.Serializable

/**
 * Unified architectural and visual complexity sizing metric for 3-band velocity gating.
 */
@Serializable
data class SemioticSizing(
    val codeAci: Double,
    val vscScore: Double,
    val unifiedAci: Double,
    val velocityBand: String,
    val recommendedPipeline: String,
    val rationale: String
)
