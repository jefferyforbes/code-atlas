package atlas.model

import kotlinx.serialization.Serializable

/**
 * Live disk source slice with SHA-256 freshness verification.
 */
@Serializable
data class LiveSourceSlice(
    val fresh: Boolean,
    val relPath: String,
    val startLine: Int,
    val endLine: Int,
    val indexedSha256: String,
    val liveSha256: String,
    val source: String,
    val reason: String = ""
)
