package atlas.model

import kotlinx.serialization.Serializable

/**
 * AST domain model for a Figma document node with AutoLayout, ergonomics, and spatial bounds.
 */
@Serializable
data class FigmaNode(
    val rowidPk: Long = 0L,
    val nodeId: String,
    val fileKey: String,
    val pageName: String,
    val sectionId: String? = null,
    val parentId: String? = null,
    val name: String,
    val nodeType: String,
    val layoutMode: String = "NONE",
    val sizingHorizontal: String = "FIXED",
    val sizingVertical: String = "FIXED",
    val textAutoResize: String = "NONE",
    val paddingLeft: Double = 0.0,
    val paddingRight: Double = 0.0,
    val paddingTop: Double = 0.0,
    val paddingBottom: Double = 0.0,
    val itemSpacing: Double = 0.0,
    val localX: Double = 0.0,
    val localY: Double = 0.0,
    val canvasX: Double = 0.0,
    val canvasY: Double = 0.0,
    val width: Double = 0.0,
    val height: Double = 0.0,
    val isInteractive: Boolean = false,
    val hasEmoji: Boolean = false,
    val unboundFill: Boolean = false,
    val unboundStroke: Boolean = false,
    val wcagContrast: Double? = null,
    val semioticLayer: String = "syntactic"
) {
    val spatialBounds: FigmaSpatialBounds
        get() = FigmaSpatialBounds.fromRect(canvasX, canvasY, width, height)
}
