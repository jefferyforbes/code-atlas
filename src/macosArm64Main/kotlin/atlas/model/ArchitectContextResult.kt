package atlas.model

import kotlinx.serialization.Serializable

/**
 * Encapsulates a token-budgeted 3D Concurrency Scope and structural AST skeleton packet for /architect and /plan.
 */
@Serializable
data class ArchitectContextResult(
    val projectId: String,
    val queryOrSeeds: List<String>,
    val aciScore: Double,
    val isFastPath: Boolean,
    val files: List<String>,
    val modules: List<String>,
    val architectureLayers: List<String>,
    val collidingPlans: List<String>,
    val godNodesTouched: List<String>,
    val seedSymbols: List<String>,
    val spilled: Boolean,
    val spilloverPath: String? = null,
    val inlineMarkdown: String,
    val semioticSizing: SemioticSizing? = null
)
