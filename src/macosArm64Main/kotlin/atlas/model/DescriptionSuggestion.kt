package atlas.model

import kotlinx.serialization.Serializable

/**
 * Audit suggestion for top-level classes or functions missing documentation descriptions.
 */
@Serializable
data class DescriptionSuggestion(
    val rule: String = "DOC-TOP-LEVEL",
    val nodeId: String,
    val name: String,
    val kind: String,
    val relPath: String,
    val lineNumber: Int,
    val semioticLayer: String,
    val archLayer: String,
    val message: String
)
