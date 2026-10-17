package atlas.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Compose3DScope(
    val project: String,
    @SerialName("target_files") val targetFiles: List<String>,
    val modules: List<String>,
    @SerialName("architecture_layers") val architectureLayers: List<String>,
    @SerialName("shared_stable_models") val sharedStableModels: List<String>,
    @SerialName("composition_locals") val compositionLocals: List<String>,
    @SerialName("theme_token_providers") val themeTokenProviders: List<String>,
    @SerialName("downstream_screens") val downstreamScreens: List<String>,
    @SerialName("collision_risks") val collisionRisks: List<CollisionRisk>,
    @SerialName("safe_for_parallel_dispatch") val safeForParallelDispatch: Boolean
) {
    @Serializable
    data class CollisionRisk(
        @SerialName("shared_symbol_id") val sharedSymbolId: String,
        @SerialName("risk_kind") val riskKind: String,
        @SerialName("colliding_files") val collidingFiles: List<String>,
        val remediation: String
    )
}
