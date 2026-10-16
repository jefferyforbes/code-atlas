package atlas.model

import kotlinx.serialization.Serializable

/**
 * Transitive blast radius report for a Figma design token variable across the alias DAG and bound nodes.
 */
@Serializable
data class FigmaVariableImpact(
    val seedVariableIdOrName: String,
    val depth: Int,
    val impactedVariables: List<FigmaVariable>,
    val boundNodes: List<FigmaNode>
)
