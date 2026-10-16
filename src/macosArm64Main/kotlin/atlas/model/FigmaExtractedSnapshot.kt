package atlas.model

import kotlinx.serialization.Serializable

/**
 * Encapsulates all domain records extracted from a Figma document snapshot and token bundles.
 */
@Serializable
data class FigmaExtractedSnapshot(
    val document: FigmaDocument,
    val nodes: List<FigmaNode> = emptyList(),
    val variables: List<FigmaVariable> = emptyList(),
    val edges: List<FigmaEdge> = emptyList()
)
