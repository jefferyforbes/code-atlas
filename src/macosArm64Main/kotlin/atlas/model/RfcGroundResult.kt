package atlas.model

import kotlinx.serialization.Serializable

/**
 * Dense subsystem discovery and grounding result for RFC-0 research (<= 800B summary table).
 */
@Serializable
data class RfcGroundResult(
    val query: String,
    val projectId: String,
    val matchedNodesCount: Int,
    val domainContracts: List<String>,
    val afferentEntryPoints: List<String>,
    val efferentDependencies: List<String>,
    val syntacticTier: List<String>,
    val semanticTier: List<String>,
    val pragmaticTier: List<String>,
    val markdownTable: String,
    val byteSize: Int
)
