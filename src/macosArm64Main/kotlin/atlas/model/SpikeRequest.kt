package atlas.model

import kotlinx.serialization.Serializable

/**
 * Parameters for topological hypothesis testing in spike analysis.
 */
@Serializable
data class SpikeRequest(
    val projectId: String,
    val hypothesis: String,
    val from: String? = null,
    val to: String? = null,
    val target: String? = null,
    val forbiddenLayer: String? = null,
    val maxFiles: Int? = null,
    val maxHops: Int = 10
)
