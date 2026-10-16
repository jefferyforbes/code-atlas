package atlas.model

import kotlinx.serialization.Serializable

/**
 * Design invariant violation record emitted by FigmaDesignAuditor.
 */
@Serializable
data class FigmaViolation(
    val rule: String,
    val nodeId: String,
    val nodeName: String,
    val message: String,
    val severity: String = "ERROR"
)
