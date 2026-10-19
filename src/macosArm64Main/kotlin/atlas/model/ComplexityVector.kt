package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Multi-dimensional complexity metrics vector tracking cyclomatic, cognitive, nesting, SLOC, and parameter counts.
 */
@Serializable
data class ComplexityVector(
    val cyclomatic: Int = 1,
    val cognitive: Int = 0,
    @SerialName("max_nesting") val maxNesting: Int = 0,
    val sloc: Int = 0,
    @SerialName("param_count") val paramCount: Int = 0,
    @SerialName("return_count") val returnCount: Int = 0
)
