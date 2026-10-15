package atlas.model

import kotlinx.serialization.Serializable

/**
 * Clean Architecture layer inversion violation record.
 */
@Serializable
data class LayerInversionViolation(
    val rule: String = "ARCH-LAYER-INVERSION",
    val source: String,
    val target: String,
    val edgeType: String,
    val lineNumber: Int,
    val message: String
)
