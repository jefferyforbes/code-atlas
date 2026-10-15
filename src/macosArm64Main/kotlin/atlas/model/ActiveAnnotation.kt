package atlas.model

import kotlinx.serialization.Serializable

/**
 * Active work memory annotation weighted by exponential decay.
 */
@Serializable
data class ActiveAnnotation(
    val annotationId: String,
    val outcome: String,
    val note: String,
    val ageDays: Double,
    val decayWeight: Double
)
