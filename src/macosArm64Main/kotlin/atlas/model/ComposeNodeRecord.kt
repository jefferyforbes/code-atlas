package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ComposeNodeRecord(
    @SerialName("node_id") val nodeId: String,
    @SerialName("project_slug") val projectSlug: String,
    @SerialName("fqname") val fqName: String,
    @SerialName("file_path") val filePath: String,
    @SerialName("start_line") val startLine: Int,
    @SerialName("end_line") val endLine: Int,
    @SerialName("source_set") val sourceSet: String = "commonMain",
    @SerialName("is_restartable") val isRestartable: Boolean = true,
    @SerialName("is_skippable") val isSkippable: Boolean = true,
    @SerialName("is_readonly") val isReadonly: Boolean = false,
    @SerialName("is_inline") val isInline: Boolean = false,
    @SerialName("returns_unit") val returnsUnit: Boolean = true,
    @SerialName("has_modifier_param") val hasModifierParam: Boolean = false,
    @SerialName("modifier_is_first_optional") val modifierIsFirstOptional: Boolean = true,
    @SerialName("modifier_has_default") val modifierHasDefault: Boolean = true,
    @SerialName("unstable_param_count") val unstableParamCount: Int = 0,
    @SerialName("runtime_param_count") val runtimeParamCount: Int = 0,
    @SerialName("receiver_stability") val receiverStability: String = "STABLE",
    @SerialName("provenance") val provenance: String = "STATIC_INFERRED",
    @SerialName("violation_mask") val violationMask: Int = 0
)
