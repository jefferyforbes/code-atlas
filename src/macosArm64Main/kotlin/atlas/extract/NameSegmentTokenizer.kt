package atlas.extract

/**
 * Tokenizes camelCase, snake_case, and CJK symbol identifiers into normalized vocabulary segments.
 */
class NameSegmentTokenizer {
    companion object {
        private val CAMEL_REGEX = Regex("(?<=[\\p{Ll}\\p{N}])(?=[\\p{Lu}])|(?<=[\\p{Lu}])(?=[\\p{Lu}][\\p{Ll}])")
        private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{N}]+")

        private fun isCjk(c: Char): Boolean =
            c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf' || c in '\uf900'..'\ufaff' ||
            c in '\u3040'..'\u309f' || c in '\u30a0'..'\u30ff' || c in '\uac00'..'\ud7af'

        fun tokenize(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            val expanded = CAMEL_REGEX.replace(text, " ")
            val parts = NON_ALNUM_REGEX.split(expanded).filter { it.isNotBlank() }
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()
            for (p in parts) {
                val clean = p.lowercase().trim()
                val isAcceptableLength = clean.length >= 2 || (clean.length == 1 && isCjk(clean[0]))
                if (isAcceptableLength && seen.add(clean)) {
                    result.add(clean)
                }
            }
            return result
        }
    }
}
