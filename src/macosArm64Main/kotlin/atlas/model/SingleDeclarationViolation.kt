package atlas.model

import kotlinx.serialization.Serializable

/**
 * Single Declaration Principle (BOOST-003) violation record.
 */
@Serializable
data class SingleDeclarationViolation(
    val rule: String = "BOOST-003",
    val relPath: String,
    val topLevelDeclCount: Int,
    val message: String
)
