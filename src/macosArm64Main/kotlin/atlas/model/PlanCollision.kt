package atlas.model

import kotlinx.serialization.Serializable

/**
 * 3D plan contract concurrency boundary collision.
 */
@Serializable
data class PlanCollision(
    val planA: String,
    val planB: String,
    val sharedFiles: List<String>,
    val sharedModules: List<String>
)
