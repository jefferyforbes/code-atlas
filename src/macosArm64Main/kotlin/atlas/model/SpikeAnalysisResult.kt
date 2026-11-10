package atlas.model

import kotlinx.serialization.Serializable

/**
 * Result of topological hypothesis testing for spike analysis.
 */
@Serializable
data class SpikeAnalysisResult(
    val hypothesis: String,
    val projectId: String,
    val status: String,
    val passed: Boolean,
    val message: String,
    val path: List<String> = emptyList(),
    val violations: List<LayerInversionViolation> = emptyList(),
    val details: String = "",
    val markdownBlock: String = ""
)
