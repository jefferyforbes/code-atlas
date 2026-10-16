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
            val minX = minOf(x, x + width)
            val maxX = maxOf(x, x + width)
            val minY = minOf(y, y + height)
            val maxY = maxOf(y, y + height)
            return FigmaSpatialBounds(minX, maxX, minY, maxY)
        }
    }
}
