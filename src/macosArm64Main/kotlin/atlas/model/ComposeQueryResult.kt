package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ComposeQueryResult(
    val project: String,
    val action: String,
    @SerialName("strong_skipping") val strongSkipping: Boolean = true,
    @SerialName("total_composables") val totalComposables: Int = 0,
    @SerialName("skippable_count") val skippableCount: Int = 0,
    @SerialName("skippable_pct") val skippablePct: Double = 100.0,
    @SerialName("unstable_param_total") val unstableParamTotal: Int = 0,
    val nodes: List<NodeSummary> = emptyList(),
    val cascade: List<CascadeHop> = emptyList(),
    val blame: List<BlameHop> = emptyList()
) {
    @Serializable
    data class NodeSummary(
        @SerialName("node_id") val nodeId: String,
        val fqname: String,
        @SerialName("file_path") val filePath: String,
        val line: Int,
        @SerialName("source_set") val sourceSet: String,
        @SerialName("is_skippable") val isSkippable: Boolean,
        @SerialName("unstable_params") val unstableParams: List<ComposeParamRecord>,
        val provenance: String
    )

    @Serializable
    data class CascadeHop(
        @SerialName("caller_id") val callerId: String,
        @SerialName("callee_id") val calleeId: String,
        @SerialName("callee_fqname") val calleeFqName: String,
        @SerialName("file_path") val filePath: String,
        val line: Int,
        val depth: Int,
        @SerialName("forwarded_param") val forwardedParam: String,
        @SerialName("callee_skippable") val calleeSkippable: Boolean,
        @SerialName("via_edge_kind") val viaEdgeKind: String = "CALLS_COMPOSABLE"
    )

    @Serializable
    data class BlameHop(
        @SerialName("composable_id") val composableId: String,
        @SerialName("param_name") val paramName: String,
        @SerialName("type_signature") val typeSignature: String,
        val stability: String,
        @SerialName("root_cause_node_id") val rootCauseNodeId: String? = null,
        @SerialName("root_cause_file") val rootCauseFile: String? = null,
        @SerialName("root_cause_line") val rootCauseLine: Int? = null,
        @SerialName("blame_reason") val blameReason: String
    )
}
