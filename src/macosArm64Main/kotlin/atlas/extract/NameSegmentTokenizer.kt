package atlas.extract

class NameSegmentTokenizer {
    companion object {
        private val CAMEL_REGEX = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
        private val NON_ALNUM_REGEX = Regex("[^a-zA-Z0-9]+")

        fun tokenize(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            val expanded = CAMEL_REGEX.replace(text, " ")
            val parts = NON_ALNUM_REGEX.split(expanded)
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()
            for (p in parts) {
                val clean = p.lowercase().trim()
                if (clean.length >= 2 && seen.add(clean)) {
                    result.add(clean)
                }
            }
            return result
        }
    }
}
