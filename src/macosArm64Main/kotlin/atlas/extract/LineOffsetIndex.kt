package atlas.extract

/**
 * Shared binary-searched line offset index providing O(log N) 1-based line number resolution.
 */
class LineOffsetIndex(val offsets: IntArray) {

    val lineCount: Int get() = offsets.size

    fun lineNumberAt(offset: Int): Int {
        if (offsets.isEmpty()) return 1
        val target = offset.coerceAtLeast(0)
        var low = 0
        var high = offsets.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (offsets[mid] <= target) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return high + 1
    }

    companion object {
        fun from(text: String): LineOffsetIndex {
            var newlineCount = 0
            for (i in text.indices) {
                if (text[i] == '\n') newlineCount++
            }
            val offsets = IntArray(newlineCount + 1)
            offsets[0] = 0
            var idx = 1
            for (i in text.indices) {
                if (text[i] == '\n') {
                    offsets[idx++] = i + 1
                }
            }
            return LineOffsetIndex(offsets)
        }
    }
}
