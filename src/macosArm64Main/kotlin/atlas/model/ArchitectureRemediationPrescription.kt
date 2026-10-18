package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Concrete remediation prescription for an architecture audit violation (BOOST-003 or ARCH-LAYER-INVERSION).
 */
@Serializable
data class ArchitectureRemediationPrescription(
    val id: String,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("file_path") val filePath: String,
    val line: Int = 1,
    @SerialName("fix_strategy") val fixStrategy: String,
    val rationale: String,
    @SerialName("primary_declaration") val primaryDeclaration: String? = null,
    @SerialName("secondary_declarations") val secondaryDeclarations: List<String> = emptyList(),
    @SerialName("spawned_files") val spawnedFiles: List<SpawnedFileRecord> = emptyList(),
    @SerialName("before_snippet") val beforeSnippet: String? = null,
    @SerialName("after_snippet") val afterSnippet: String? = null,
    @SerialName("diff_preview") val diffPreview: String? = null,
    val verified: Boolean = false,
    val applied: Boolean = false,
    val details: List<String> = emptyList()
)
