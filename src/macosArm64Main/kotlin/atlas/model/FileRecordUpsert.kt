package atlas.model

import kotlinx.serialization.Serializable

/**
 * Input descriptor for inserting or updating an indexed file record.
 */
@Serializable
data class FileRecordUpsert(
    val projectId: String,
    val relPath: String,
    val language: String,
    val moduleId: String = "",
    val sourceSet: String = "main",
    val semioticLayer: String = "semantic",
    val archLayer: String = "domain",
    val sha256: String,
    val mtime: Double = 0.0,
    val loc: Int = 0,
    val topLevelDeclCount: Int = 0
)
