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


    companion object {
        private fun sanitizeCoord(v: Double): Double = if (v.isFinite()) v else 0.0

        fun fromRect(x: Double, y: Double, width: Double, height: Double): FigmaSpatialBounds {
            val safeX = sanitizeCoord(x)
            val safeY = sanitizeCoord(y)
            val safeW = sanitizeCoord(width)
            val safeH = sanitizeCoord(height)
            val minX = minOf(safeX, safeX + safeW)
            val maxX = maxOf(safeX, safeX + safeW)
            val minY = minOf(safeY, safeY + safeH)
            val maxY = maxOf(safeY, safeY + safeH)
            return FigmaSpatialBounds(minX, maxX, minY, maxY)
        }
    }
}
