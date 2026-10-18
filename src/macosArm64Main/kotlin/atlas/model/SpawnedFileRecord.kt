package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Record of a source file spawned during AST file-splitting to satisfy BOOST-003.
 */
@Serializable
data class SpawnedFileRecord(
    @SerialName("rel_path") val relPath: String,
    @SerialName("declaration_name") val declarationName: String,
    val content: String,
    val loc: Int
)
