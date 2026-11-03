package atlas.extract

/**
 * Tokenizes camelCase, snake_case, and CJK symbol identifiers into normalized vocabulary segments.
 */
class NameSegmentTokenizer {
    companion object {
        private fun isCjk(c: Char): Boolean =
            c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf' || c in '\uf900'..'\ufaff' ||
                c in '\u3040'..'\u309f' || c in '\u30a0'..'\u30ff' || c in '\uac00'..'\ud7af'

        private fun isCamelBoundary(text: String, i: Int): Boolean {
            if (i <= 0 || i >= text.length) return false
            val prev = text[i - 1]
            val curr = text[i]
            if (!curr.isUpperCase()) return false
            if (prev.isLowerCase() || prev.isDigit()) return true
            return prev.isUpperCase() && i + 1 < text.length && text[i + 1].isLowerCase()
        }

        private fun flushSegment(
            buf: StringBuilder,
            seen: MutableSet<String>,
            result: MutableList<String>
        ) {
            if (buf.isEmpty()) return
            val clean = buf.toString()
            buf.clear()
            val isAcceptable = clean.length >= 2 || (clean.length == 1 && isCjk(clean[0]))
            if (isAcceptable && seen.add(clean)) {
                result.add(clean)
            }
        }

        fun tokenize(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()
            val buf = StringBuilder(text.length)

            for (i in text.indices) {
                val c = text[i]
                if (!c.isLetterOrDigit()) {
                    flushSegment(buf, seen, result)
                    continue
                }
                if (isCamelBoundary(text, i)) {
                    flushSegment(buf, seen, result)
                }
                buf.append(c.lowercaseChar())
            }
            flushSegment(buf, seen, result)
            return result
        }
    }
}
