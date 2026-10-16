package atlas.model

import kotlinx.serialization.Serializable

/**
 * 2D bounding box coordinates on the Figma canvas for R*Tree spatial indexing.
 */
@Serializable
data class FigmaSpatialBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double
) {
    val width: Double
        get() = maxX - minX

    val height: Double
        get() = maxY - minY

    fun intersects(other: FigmaSpatialBounds): Boolean {
        return maxX >= other.minX && minX <= other.maxX &&
            maxY >= other.minY && minY <= other.maxY
    }

    companion object {
        fun fromRect(x: Double, y: Double, width: Double, height: Double): FigmaSpatialBounds {
            val safeX = if (x.isNaN() || x.isInfinite()) 0.0 else x
            val safeY = if (y.isNaN() || y.isInfinite()) 0.0 else y
            val safeW = if (width.isNaN() || width.isInfinite()) 0.0 else width
            val safeH = if (height.isNaN() || height.isInfinite()) 0.0 else height
            val minX = minOf(safeX, safeX + safeW)
            val maxX = maxOf(safeX, safeX + safeW)
            val minY = minOf(safeY, safeY + safeH)
            val maxY = maxOf(safeY, safeY + safeH)
            return FigmaSpatialBounds(minX, maxX, minY, maxY)
        }
    }
}
