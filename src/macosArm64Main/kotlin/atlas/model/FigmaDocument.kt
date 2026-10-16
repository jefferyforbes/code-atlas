package atlas.model

import kotlinx.serialization.Serializable

/**
 * Metadata record for an indexed Figma document file.
 */
@Serializable
data class FigmaDocument(
    val fileKey: String,
    val name: String,
    val version: String = "",
    val lastModified: String = "",
    val thumbnailUrl: String = ""
)
