package atlas.model

import kotlinx.serialization.Serializable

/**
 * Design token variable record supporting primitive definitions and alias DAG dependencies.
 */
@Serializable
data class FigmaVariable(
    val variableId: String,
    val fileKey: String,
    val collectionName: String,
    val name: String,
    val resolvedType: String,
    val isAlias: Boolean = false,
    val aliasedVariableId: String? = null,
    val modesJson: String = "{}",
    val codeSyntaxCompose: String? = null,
    val codeSyntaxWeb: String? = null
)
