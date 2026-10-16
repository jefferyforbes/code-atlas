package atlas.model

import kotlinx.serialization.Serializable

/**
 * 3D Concurrency Scope for concurrent multi-agent design tasks.
 * Dimension 1: Spatial Canvas R*Tree Bounding Box
 * Dimension 2: Component Hierarchy & Shared Master Component Boundaries
 * Dimension 3: Design Tokens & Variable Alias Boundaries
 */
@Serializable
data class Figma3DScope(
    val project: String,
    val fileKey: String,
    val seedNodes: List<String>,
    val spatialBounds: FigmaSpatialBounds,
    val scopedNodesCount: Int,
    val sharedMasterComponents: List<String> = emptyList(),
    val tokenLayers: List<String> = emptyList(),
    val potentialCollisions: List<String> = emptyList()
)
