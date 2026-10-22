package atlas.model

import kotlinx.serialization.Serializable

/**
 * Agent skill audit violation record (SKILL-001, SKILL-002, SKILL-003).
 */
@Serializable
data class SkillAuditViolation(
    val rule: String,
    val skillName: String,
    val relPath: String,
    val lineNumber: Int = 1,
    val message: String,
    val target: String? = null
)
