package atlas.model

import kotlinx.serialization.Serializable

/**
 * Primary symbol or structural declaration node in the polyglot knowledge graph with architectural classifications.
 */
@Serializable
data class GraphNode(
    val nodeId: String,
    val projectId: String,
    val fileId: String,
    val name: String,
    val qualifiedName: String,
    val kind: String,
    val signature: String = "",
    val startLine: Int = 1,
    val endLine: Int = 1,
    val moduleId: String = "",
    val sourceSet: String = "main",
    val semioticLayer: String = "semantic",
    val archLayer: String = "domain",
    val isTestDouble: Boolean = false,
    val inDegree: Int = 0,
    val outDegree: Int = 0,
    val description: String? = null,
    val isTopLevel: Boolean = true,
    val cyclomaticComplexity: Int = 1,
    val cognitiveComplexity: Int = 0,
    val maxNestingDepth: Int = 0,
    val complexityScore: Double = 100.0
)
