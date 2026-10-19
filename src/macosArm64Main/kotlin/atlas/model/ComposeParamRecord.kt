package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parameter-level stability classification and inference record for a Composable function parameter.
 */
@Serializable
data class ComposeParamRecord(
    @SerialName("node_id") val nodeId: String,
    @SerialName("param_index") val paramIndex: Int,
    @SerialName("param_name") val paramName: String,
    @SerialName("type_signature") val typeSignature: String,
    @SerialName("stability") val stability: String,
    @SerialName("stability_reason") val stabilityReason: String,
    @SerialName("has_default") val hasDefault: Boolean = false,
    @SerialName("is_used_in_body") val isUsedInBody: Boolean = true,
    @SerialName("provenance") val provenance: String = "STATIC_INFERRED"
)
