package atlas.extract

import atlas.model.ComplexityVector
import atlas.model.GraphEdge
import atlas.model.GraphNode

/**
 * Polyglot AST declaration, import, call, and complexity extractor across Kotlin, TypeScript, Python, Swift, Rust, and C++.
 */
class PolyglotAstExtractor {
    companion object {
        private val EXT_MAP = mapOf(
            ".kt" to "kotlin", ".kts" to "kotlin",
            ".py" to "python",
            ".swift" to "swift",
            ".ts" to "typescript", ".tsx" to "typescript",
            ".js" to "javascript", ".jsx" to "javascript",
            ".rs" to "rust",
            ".cpp" to "cpp", ".cc" to "cpp", ".h" to "cpp", ".hpp" to "cpp"
        )

        private val KT_PKG_RE = Regex("""^\s*package\s+([a-zA-Z0-9_.]+)""", RegexOption.MULTILINE)
        private val KT_IMP_RE = Regex("""^\s*import\s+([a-zA-Z0-9_.*]+)""", RegexOption.MULTILINE)
        private val KT_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?<annotations>(?:@[A-Za-z0-9_]+(?:\((?:[^()]|\([^()]*\))*\))?\s*)*)""" +
            """(?<modifiers>(?:(?:public|internal|private|protected|open|abstract|sealed|data|value|inline|suspend|expect|actual|override|companion|fun|final|inner|enum|annotation|operator|infix|tailrec|external)\s+)*)""" +
            """(?<kw>class|interface|object|fun|typealias)(?:\s+(?:[A-Za-z0-9_<>?,.\t ]+\.)?(?<name>[A-Za-z0-9_]+))?""",
            RegexOption.MULTILINE
        )

        private val SWIFT_IMP_RE = Regex("""^\s*(?:@\w+\s+)?(?:public\s+|internal\s+)?import\s+([a-zA-Z0-9_.]+)""", RegexOption.MULTILINE)
        private val SWIFT_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|fileprivate|internal|open|final|actor|indirect|nonisolated|isolated|override|static|mutating|class|convenience|required|lazy|weak|unowned)\s+)*""" +
            """(?<kw>struct|class|protocol|actor|enum|func|extension)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )
        private val SWIFT_SCOPE_KEYWORDS = setOf("class", "struct", "protocol", "actor", "enum", "func", "extension")

        private val TS_IMP_RE = Regex("""import\s+(?:type\s+)?(?:(?:\{([^}]+)\})|([A-Za-z0-9_]+))\s+from\s+['"]([^'"]+)['"]""")
        private val TS_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:export\s+)?(?:default\s+)?(?:async\s+)?(?<kw>class|interface|type|function|const\s+enum|enum|const|let|var)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )
        private val TS_METHOD_DECL_RE = Regex(
            """^(?<indent>[ \t]+)(?:(?:public|private|protected|static|async|override|readonly|abstract)\s+)*(?:(?<getter>get|set)\s+)?(?<name>[A-Za-z0-9_$]+)\s*(?:<[^>]+>)?\s*\(""",
            RegexOption.MULTILINE
        )

        private val RS_USE_RE = Regex("""^\s*(?:pub(?:\([^)]*\))?\s+)?use\s+([a-zA-Z0-9_:*]+)""", RegexOption.MULTILINE)
        private val RS_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?<kw>struct|enum|trait|fn|type|mod)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )
        private val RUST_DOC_KINDS = setOf("class", "interface", "enum", "function")
        private val RUST_SCOPE_KEYWORDS = setOf("struct", "enum", "trait", "fn", "mod")
        private val RUST_NON_COUNTABLE_TOP_LEVEL = setOf("typealias", "module")

        data class ScopeEntry(val name: String, val startLine: Int, val endLine: Int, val kind: String = "")

        private val CPP_INC_RE = Regex("""^\s*#include\s+[<"]([^>"]+)[>"]""", RegexOption.MULTILINE)
        private val CPP_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:template\s*<[^>]*>\s*)?(?<kw>class|struct|namespace|enum(?:\s+class)?)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )
        private val CPP_FUNC_RE = Regex(
            """^(?<indent>[ \t]*)(?:template\s*<[^>]*>\s*)?(?:(?:inline|static|virtual|explicit|constexpr|extern|friend|const|unsigned|signed|typename)\s+)*(?:[A-Za-z_][A-Za-z0-9_:<>*,&\s]*?)\b(?<name>(?:[A-Za-z_][A-Za-z0-9_]*::)?[A-Za-z_][A-Za-z0-9_]*)\s*\([^;{}]*\)\s*(?:const\s*)?(?:noexcept\s*)?(?:override\s*)?\{""",
            RegexOption.MULTILINE
        )
        private val CPP_FUNC_STOPWORDS = setOf("main", "sizeof", "alignof", "decltype", "static_assert")
        private val CPP_DOC_KINDS = setOf("class", "struct", "enum")
        private val CPP_SCOPE_KINDS = setOf("class", "struct", "namespace")

        private val PY_IMP_RE = Regex("""^\s*(?:from\s+([a-zA-Z0-9_.]+)\s+import|import\s+([a-zA-Z0-9_.]+))""", RegexOption.MULTILINE)
        private val PY_DECL_RE = Regex("""^(?<indent>[ \t]*)(?<kw>class|def|async\s+def)\s+(?<name>[A-Za-z0-9_]+)""", RegexOption.MULTILINE)

        private val CALL_RE = Regex("""\b(?:(?<receiver>[A-Za-z_][A-Za-z0-9_]*)(?:::|\.))?(?<callee>[A-Za-z_][A-Za-z0-9_]{2,})\s*\(""")
        private val CALL_STOPWORDS = setOf(
            "if", "for", "while", "switch", "when", "return", "catch", "super", "this", "self",
            "print", "println", "len", "str", "int", "float", "bool", "listOf", "mapOf", "setOf",
            "require", "check", "match", "throw", "yield", "guard", "case", "typeof"
        )

        const val EXTRACTOR_VERSION: String = "1.4.1"

        private val INFIX_CALL_RE = Regex(
            """(?<=\s|[)\]a-zA-Z0-9_])\s+(?<callee>[a-zA-Z_][a-zA-Z0-9_]{1,})\s+(?=[A-Za-z0-9_"'(\[])"""
        )
        private val INFIX_STOPWORDS = setOf(
            "val", "var", "fun", "class", "interface", "object", "package", "import",
            "return", "throw", "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
            "in", "is", "as", "by", "where", "get", "set", "constructor", "init", "out", "super",
            "this", "typealias", "companion", "enum", "sealed", "open", "abstract", "data", "value",
            "inline", "noinline", "crossinline", "suspend", "expect", "actual", "const", "lateinit",
            "override", "private", "protected", "public", "internal", "operator", "infix", "tailrec", "external"
        )

        private val LOCAL_VAL_COLON_RE = Regex("""(?:val|var)\s+([A-Za-z0-9_$]+)\s*:\s*([A-Z][A-Za-z0-9_$.]*)""")
        private val LOCAL_VAL_ASSIGN_RE = Regex("""(?:val|var)\s+([A-Za-z0-9_$]+)\s*=\s*([A-Z][A-Za-z0-9_$.]*)\s*(?:<[^>]+>)?\s*\(""")
        private val KOTLIN_PRIMITIVE_TYPES = setOf(
            "String", "Int", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Char", "Any", "Unit", "Nothing"
        )
        private val COMMON_CONSTRUCTORS = setOf(
            "listOf", "setOf", "mapOf", "mutableListOf", "mutableSetOf", "mutableMapOf",
            "buildList", "buildSet", "buildMap", "emptyList", "emptySet", "emptyMap",
            "arrayOf", "byteArrayOf", "intArrayOf"
        )

        private fun scoreSpecialReceiver(clean: String, candFileId: String, srcFileId: String): Double? {
            if (clean == "this") return if (candFileId == srcFileId) 100.0 else 0.0
            if (clean == "super") return if (candFileId == srcFileId) 90.0 else 0.0
            return null
        }

        private fun isExactReceiverMatch(clean: String, candSimpleName: String, candName: String, qn: String): Boolean {
            return candSimpleName.equals(clean, ignoreCase = true) ||
                candName.equals(clean, ignoreCase = true) ||
                qn.contains(".$clean.", ignoreCase = true) ||
                qn.endsWith(".$clean", ignoreCase = true)
        }

        private fun scoreTokenOverlap(clean: String, candSimpleName: String, candName: String, qn: String): Double {
            val recTokens = NameSegmentTokenizer.tokenize(clean).toSet()
            if (recTokens.isEmpty()) return 0.0

            val candTokens = (
                NameSegmentTokenizer.tokenize(candSimpleName) +
                NameSegmentTokenizer.tokenize(candName) +
                NameSegmentTokenizer.tokenize(qn)
            ).toSet()

            val tokenMatches = recTokens.count { it in candTokens }
            if (tokenMatches == 0) return 0.0

            val fraction = tokenMatches.toDouble() / recTokens.size
            return fraction * 40.0
        }

        private fun scoreReceiverCandidate(
            cand: GraphNode,
            receiver: String?,
            srcFileId: String,
            srcNode: GraphNode?
        ): Double {
            if (receiver.isNullOrBlank()) return 0.0
            val clean = receiver.trim()

            val specialScore = scoreSpecialReceiver(clean, cand.fileId, srcFileId)
            if (specialScore != null) return specialScore

            val candSimpleName = cand.fileId.substringAfterLast('/').substringBeforeLast('.')
            val qn = cand.qualifiedName

            if (isExactReceiverMatch(clean, candSimpleName, cand.name, qn)) {
                return 100.0
            }
            if (qn.contains(clean, ignoreCase = true) || cand.signature.contains(clean, ignoreCase = true)) {
                return 60.0
            }

            return scoreTokenOverlap(clean, candSimpleName, cand.name, qn)
        }

        fun supportedLanguage(ext: String): String = EXT_MAP[ext.lowercase()] ?: ""

        /**
         * Replaces comment bodies and string literal contents with spaces while preserving
         * exact string length and all newline (`\n`) offsets so regex match offsets map 1:1
         * to original source lines.
         */
        fun maskCommentsAndStrings(source: String, language: String = "kotlin"): String {
            val state = MaskState(
                source = source,
                out = CharArray(source.length),
                n = source.length,
                isPy = language == "python",
                isRust = language == "rust",
                isTsJs = language == "typescript" || language == "javascript",
                isKotlin = language == "kotlin",
                isSwift = language == "swift"
            )

            while (state.i < state.n) {
                processNextToken(state)
            }
            return state.out.concatToString()
        }

        private class MaskState(
            val source: String,
            val out: CharArray,
            val n: Int,
            val isPy: Boolean,
            val isRust: Boolean,
            val isTsJs: Boolean,
            val isKotlin: Boolean,
            val isSwift: Boolean,
            var i: Int = 0
        ) {
            fun writeSpaceOrNewline(idx: Int) {
                out[idx] = if (source[idx] == '\n') '\n' else ' '
            }
        }

        private fun processNextToken(state: MaskState) {
            if (maskComment(state)) return
            if (maskTripleQuotedString(state)) return
            if (maskDoubleQuotedString(state)) return
            if (maskSingleQuotedString(state)) return
            if (maskJsTemplateLiteral(state)) return

            state.out[state.i] = state.source[state.i]
            state.i++
        }

        private fun maskComment(state: MaskState): Boolean {
            val c = state.source[state.i]
            if (state.isPy && c == '#') {
                maskLineCommentToNewline(state)
                return true
            }
            if (!state.isPy && c == '/' && state.i + 1 < state.n) {
                val next = state.source[state.i + 1]
                if (next == '/') {
                    maskLineCommentToNewline(state)
                    return true
                }
                if (next == '*') {
                    maskCStyleBlockComment(state)
                    return true
                }
            }
            return false
        }

        private fun maskLineCommentToNewline(state: MaskState) {
            while (state.i < state.n && state.source[state.i] != '\n') {
                state.out[state.i] = ' '
                state.i++
            }
        }

        private fun maskCStyleBlockComment(state: MaskState) {
            state.out[state.i] = ' '
            state.out[state.i + 1] = ' '
            state.i += 2
            while (state.i < state.n) {
                if (state.source[state.i] == '*' && state.i + 1 < state.n && state.source[state.i + 1] == '/') {
                    state.out[state.i] = ' '
                    state.out[state.i + 1] = ' '
                    state.i += 2
                    return
                }
                state.writeSpaceOrNewline(state.i)
                state.i++
            }
        }

        private fun isTripleQuoteStart(state: MaskState, c: Char): Boolean {
            if (state.i + 2 >= state.n) return false
            val c1 = state.source[state.i + 1]
            val c2 = state.source[state.i + 2]
            if (c == '"' && c1 == '"' && c2 == '"') return true
            return state.isPy && c == '\'' && c1 == '\'' && c2 == '\''
        }

        private fun isTripleQuoteEnd(state: MaskState, delim: Char): Boolean {
            val idx = state.i
            if (idx + 2 >= state.n) return false
            val src = state.source
            return src[idx] == delim && src[idx + 1] == delim && src[idx + 2] == delim
        }

        private fun isKotlinInterpolationStart(state: MaskState, delim: Char): Boolean {
            if (!state.isKotlin || delim != '"') return false
            val idx = state.i
            return idx + 1 < state.n && state.source[idx] == '$' && state.source[idx + 1] == '{'
        }

        private fun isSwiftInterpolationStart(state: MaskState, delim: Char): Boolean {
            if (!state.isSwift || delim != '"') return false
            val idx = state.i
            return idx + 1 < state.n && state.source[idx] == '\\' && state.source[idx + 1] == '('
        }

        private fun maskTripleQuotedString(state: MaskState): Boolean {
            val c = state.source[state.i]
            if (!isTripleQuoteStart(state, c)) return false

            val delim = c
            state.out[state.i] = delim
            state.out[state.i + 1] = ' '
            state.out[state.i + 2] = ' '
            state.i += 3

            while (state.i < state.n) {
                if (isTripleQuoteEnd(state, delim)) {
                    state.out[state.i] = ' '
                    state.out[state.i + 1] = ' '
                    state.out[state.i + 2] = delim
                    state.i += 3
                    return true
                }
                if (isKotlinInterpolationStart(state, delim)) {
                    maskKotlinInterpolation(state)
                } else if (isSwiftInterpolationStart(state, delim)) {
                    maskSwiftInterpolation(state)
                } else {
                    state.writeSpaceOrNewline(state.i)
                    state.i++
                }
            }
            return true
        }

        private fun maskDoubleQuotedString(state: MaskState): Boolean {
            if (state.source[state.i] != '"') return false
            state.out[state.i] = '"'
            state.i++
            while (state.i < state.n) {
                if (stepDoubleQuotedChar(state, state.source[state.i])) {
                    return true
                }
            }
            return true
        }

        private fun stepDoubleQuotedChar(state: MaskState, ch: Char): Boolean {
            if (isSwiftInterpolationStart(state, '"')) {
                maskSwiftInterpolation(state)
                return false
            }
            if (ch == '\\' && state.i + 1 < state.n) {
                state.writeSpaceOrNewline(state.i)
                state.writeSpaceOrNewline(state.i + 1)
                state.i += 2
                return false
            }
            if (isKotlinInterpolationStart(state, '"')) {
                maskKotlinInterpolation(state)
                return false
            }
            if (ch == '"') {
                state.out[state.i] = '"'
                state.i++
                return true
            }
            if (!state.isRust && ch == '\n') {
                state.out[state.i] = '\n'
                state.i++
                return true
            }
            state.writeSpaceOrNewline(state.i)
            state.i++
            return false
        }

        private fun maskSingleQuotedString(state: MaskState): Boolean {
            if (state.source[state.i] != '\'') return false
            if (state.isRust) {
                return maskRustCharOrLifetime(state)
            }
            state.out[state.i] = '\''
            state.i++
            while (state.i < state.n) {
                val ch = state.source[state.i]
                if (ch == '\\' && state.i + 1 < state.n) {
                    state.writeSpaceOrNewline(state.i)
                    state.writeSpaceOrNewline(state.i + 1)
                    state.i += 2
                } else if (ch == '\'') {
                    state.out[state.i] = '\''
                    state.i++
                    return true
                } else if (ch == '\n') {
                    state.out[state.i] = '\n'
                    state.i++
                    return true
                } else {
                    state.writeSpaceOrNewline(state.i)
                    state.i++
                }
            }
            return true
        }

        private fun maskRustCharOrLifetime(state: MaskState): Boolean {
            val idx = state.i
            val len = state.n
            val src = state.source
            if (idx + 2 < len && src[idx + 1] != '\\' && src[idx + 2] == '\'') {
                state.out[idx] = '\''
                state.out[idx + 1] = ' '
                state.out[idx + 2] = '\''
                state.i += 3
                return true
            }
            if (idx + 3 < len && src[idx + 1] == '\\' && src[idx + 3] == '\'') {
                state.out[idx] = '\''
                state.out[idx + 1] = ' '
                state.out[idx + 2] = ' '
                state.out[idx + 3] = '\''
                state.i += 4
                return true
            }
            state.out[idx] = src[idx]
            state.i++
            return true
        }

        private fun maskJsTemplateLiteral(state: MaskState): Boolean {
            if (!state.isTsJs || state.source[state.i] != '`') return false
            state.out[state.i] = '`'
            state.i++
            while (state.i < state.n) {
                val ch = state.source[state.i]
                if (ch == '\\' && state.i + 1 < state.n) {
                    state.writeSpaceOrNewline(state.i)
                    state.writeSpaceOrNewline(state.i + 1)
                    state.i += 2
                } else if (ch == '$' && state.i + 1 < state.n && state.source[state.i + 1] == '{') {
                    maskJsTemplateInterpolation(state)
                } else if (ch == '`') {
                    state.out[state.i] = '`'
                    state.i++
                    return true
                } else {
                    state.writeSpaceOrNewline(state.i)
                    state.i++
                }
            }
            return true
        }

        private fun maskJsTemplateInterpolation(state: MaskState) {
            state.out[state.i] = ' '
            state.out[state.i + 1] = ' '
            state.i += 2
            var depth = 1
            while (state.i < state.n && depth > 0) {
                depth = handleJsInterpolatedChar(state, state.source[state.i], depth)
            }
        }

        private fun handleJsInterpolatedChar(state: MaskState, ch: Char, depth: Int): Int {
            if (ch == '{') {
                state.out[state.i] = '{'
                state.i++
                return depth + 1
            }
            if (ch == '}') {
                val nextDepth = depth - 1
                state.out[state.i] = if (nextDepth == 0) ' ' else '}'
                state.i++
                return nextDepth
            }
            dispatchJsInterpolatedToken(state, ch)
            return depth
        }

        private fun dispatchJsInterpolatedToken(state: MaskState, ch: Char) {
            if (ch == '"' || ch == '\'') {
                maskJsInterpolatedQuote(state, ch)
            } else if (ch == '`') {
                maskJsNestedTemplate(state)
            } else {
                state.out[state.i] = ch
                state.i++
            }
        }

        private fun maskJsInterpolatedQuote(state: MaskState, strQuote: Char) {
            state.out[state.i] = strQuote
            state.i++
            while (state.i < state.n) {
                val ch = state.source[state.i]
                if (ch == '\\' && state.i + 1 < state.n) {
                    state.writeSpaceOrNewline(state.i)
                    state.writeSpaceOrNewline(state.i + 1)
                    state.i += 2
                } else if (ch == strQuote) {
                    state.out[state.i] = strQuote
                    state.i++
                    return
                } else if (ch == '\n') {
                    state.out[state.i] = '\n'
                    state.i++
                    return
                } else {
                    state.writeSpaceOrNewline(state.i)
                    state.i++
                }
            }
        }

        private fun maskJsNestedTemplate(state: MaskState) {
            state.out[state.i] = '`'
            state.i++
            while (state.i < state.n) {
                val ch = state.source[state.i]
                if (ch == '\\' && state.i + 1 < state.n) {
                    state.writeSpaceOrNewline(state.i)
                    state.writeSpaceOrNewline(state.i + 1)
                    state.i += 2
                } else if (ch == '$' && state.i + 1 < state.n && state.source[state.i + 1] == '{') {
                    state.out[state.i] = ' '
                    state.out[state.i + 1] = ' '
                    state.i += 2
                    maskJsInterpolationBraces(state)
                } else if (ch == '`') {
                    state.out[state.i] = '`'
                    state.i++
                    return
                } else {
                    state.writeSpaceOrNewline(state.i)
                    state.i++
                }
            }
        }

        private fun maskJsInterpolationBraces(state: MaskState) {
            var innerDepth = 1
            while (state.i < state.n && innerDepth > 0) {
                val ch = state.source[state.i]
                if (ch == '{') {
                    innerDepth++
                    state.out[state.i] = '{'
                    state.i++
                } else if (ch == '}') {
                    innerDepth--
                    state.out[state.i] = if (innerDepth == 0) ' ' else '}'
                    state.i++
                } else {
                    state.out[state.i] = ch
                    state.i++
                }
            }
        }

        private fun stepKotlinInterpolation(state: MaskState, ch: Char, depth: Int): Int {
            if (ch == '{') {
                state.out[state.i] = '{'
                state.i++
                return depth + 1
            }
            if (ch == '}') {
                val nextDepth = depth - 1
                state.out[state.i] = if (nextDepth == 0) ' ' else '}'
                state.i++
                return nextDepth
            }
            if (ch == '"') {
                maskInterpolatedDoubleQuote(state)
                return depth
            }
            state.out[state.i] = ch
            state.i++
            return depth
        }

        private fun maskKotlinInterpolation(state: MaskState) {
            state.out[state.i] = ' '
            state.out[state.i + 1] = ' '
            state.i += 2
            var depth = 1
            while (state.i < state.n && depth > 0) {
                depth = stepKotlinInterpolation(state, state.source[state.i], depth)
            }
        }

        private fun stepSwiftInterpolation(state: MaskState, ch: Char, depth: Int): Int {
            if (ch == '(') {
                state.out[state.i] = '('
                state.i++
                return depth + 1
            }
            if (ch == ')') {
                val nextDepth = depth - 1
                state.out[state.i] = if (nextDepth == 0) ' ' else ')'
                state.i++
                return nextDepth
            }
            if (ch == '"') {
                maskInterpolatedDoubleQuote(state)
                return depth
            }
            state.out[state.i] = ch
            state.i++
            return depth
        }

        private fun maskSwiftInterpolation(state: MaskState) {
            state.out[state.i] = ' '
            state.out[state.i + 1] = ' '
            state.i += 2
            var depth = 1
            while (state.i < state.n && depth > 0) {
                depth = stepSwiftInterpolation(state, state.source[state.i], depth)
            }
        }

        private fun maskInterpolatedDoubleQuote(state: MaskState) {
            state.out[state.i] = '"'
            state.i++
            while (state.i < state.n) {
                val ch = state.source[state.i]
                if (ch == '\\' && state.i + 1 < state.n) {
                    state.writeSpaceOrNewline(state.i)
                    state.writeSpaceOrNewline(state.i + 1)
                    state.i += 2
                } else if (ch == '"') {
                    state.out[state.i] = '"'
                    state.i++
                    return
                } else if (ch == '\n') {
                    state.out[state.i] = '\n'
                    state.i++
                    return
                } else {
                    state.writeSpaceOrNewline(state.i)
                    state.i++
                }
            }
        }

        private fun dispatchLanguageExtraction(
            lang: String,
            projectId: String,
            relPath: String,
            sourceText: String,
            moduleId: String,
            sourceSet: String,
            maskedText: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int>? {
            return when (lang) {
                "kotlin" -> extractKotlin(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                "swift" -> extractSwift(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                "python" -> extractPython(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                "typescript", "javascript" -> extractTypeScript(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                "rust" -> extractRust(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                "cpp" -> extractCpp(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                else -> null
            }
        }

        private fun isEnclosingClassLike(node: GraphNode?): Boolean {
            return node == null || node.kind == "class" || node.kind == "interface" || node.kind == "object"
        }

        private fun findAnnotatedChild(baseNodes: List<GraphNode>, directEnclosing: GraphNode?, lineNumber: Int): GraphNode? {
            return baseNodes
                .filter {
                    (it.kind == "function" || it.kind == "method" || it.kind == "composable") &&
                        it.startLine in (lineNumber + 1)..(lineNumber + 5) &&
                        (directEnclosing == null || (it.startLine >= directEnclosing.startLine && it.endLine <= directEnclosing.endLine))
                }
                .minByOrNull { it.startLine - lineNumber }
        }

        private fun findUrlEnclosingNode(
            baseNodes: List<GraphNode>,
            lineNumber: Int
        ): GraphNode? {
            val directEnclosing = baseNodes
                .filter { it.kind != "file_scope" && lineNumber in it.startLine..it.endLine }
                .minWithOrNull(compareBy<GraphNode> { it.endLine - it.startLine }.thenByDescending { it.startLine })
            val annotatedChild = if (isEnclosingClassLike(directEnclosing)) {
                findAnnotatedChild(baseNodes, directEnclosing, lineNumber)
            } else null
            return annotatedChild ?: directEnclosing
        }

        private fun createFileScopeNode(
            fileId: String,
            projectId: String,
            relPath: String,
            moduleId: String,
            sourceSet: String,
            sourceText: String
        ): GraphNode {
            val (semLayer, archLayer, _) = SemioticLayerClassifier.classify(relPath)
            val totalLines = sourceText.lines().size.coerceAtLeast(1)
            return GraphNode(
                nodeId = "$fileId#file", projectId = projectId, fileId = fileId,
                name = relPath.substringAfterLast('/'), qualifiedName = relPath,
                kind = "file_scope", signature = relPath, startLine = 1, endLine = totalLines,
                moduleId = moduleId, sourceSet = sourceSet, semioticLayer = semLayer,
                archLayer = archLayer, isTestDouble = false, isTopLevel = false
            )
        }

        private fun enrichNodesAndEdgesWithUrls(
            projectId: String,
            relPath: String,
            moduleId: String,
            sourceSet: String,
            sourceText: String,
            baseNodes: List<GraphNode>,
            baseEdges: List<GraphEdge>,
            extractedUrls: List<UrlTargetResolver.ExtractedEndpointOrUrl>
        ): Pair<List<GraphNode>, List<GraphEdge>> {
            val fileId = "$projectId:$relPath"
            val finalNodes = baseNodes.toMutableList()
            val finalEdges = baseEdges.toMutableList()
            var fileScopeNode: GraphNode? = finalNodes.firstOrNull { it.nodeId == "$fileId#file" }

            for (item in extractedUrls) {
                var sourceNode = findUrlEnclosingNode(baseNodes, item.lineNumber)
                if (sourceNode == null) {
                    if (fileScopeNode == null) {
                        val created = createFileScopeNode(fileId, projectId, relPath, moduleId, sourceSet, sourceText)
                        fileScopeNode = created
                        finalNodes.add(created)
                    }
                    sourceNode = fileScopeNode
                }

                val targetId = if (item.isHttpEndpointRoute) {
                    "$projectId:endpoint#${item.canonicalRouteOrUrl}"
                } else {
                    "$projectId:url_ref#${item.canonicalRouteOrUrl}"
                }
                finalEdges.add(
                    GraphEdge(
                        edgeId = "${sourceNode.nodeId}:url:${item.edgeType.lowercase()}:${item.canonicalRouteOrUrl}:${item.lineNumber}",
                        projectId = projectId,
                        sourceNodeId = sourceNode.nodeId,
                        targetNodeId = targetId,
                        edgeType = item.edgeType,
                        provenance = "EXTRACTED_URL",
                        confidence = 1.0,
                        lineNumber = item.lineNumber
                    )
                )
            }
            return Pair(finalNodes, finalEdges)
        }

        fun extractFile(
            projectId: String,
            relPath: String,
            sourceText: String,
            knownModules: List<String> = emptyList()
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val ext = if (relPath.contains('.')) "." + relPath.substringAfterLast('.') else ""
            val lang = supportedLanguage(ext)
            val moduleId = ModuleBoundaryDetector.resolveModuleId(relPath, knownModules)
            val sourceSet = SemioticLayerClassifier.detectSourceSet(relPath)

            val maskedText = maskCommentsAndStrings(sourceText, lang)
            val extracted = dispatchLanguageExtraction(lang, projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                ?: return Triple(emptyList(), emptyList(), 0)
            val (baseNodes, baseEdges, topLevelCount) = extracted

            val extractedUrls = UrlTargetResolver.extractCodeRoutesAndUrls(sourceText, maskedText, lang)
            if (extractedUrls.isEmpty()) {
                return Triple(baseNodes, baseEdges, topLevelCount)
            }

            val (finalNodes, finalEdges) = enrichNodesAndEdgesWithUrls(
                projectId = projectId,
                relPath = relPath,
                moduleId = moduleId,
                sourceSet = sourceSet,
                sourceText = sourceText,
                baseNodes = baseNodes,
                baseEdges = baseEdges,
                extractedUrls = extractedUrls
            )

            return Triple(finalNodes, finalEdges, topLevelCount)
        }

        private class LexerState {
            var inBlockComment = false
            var inTripleQuote = false
            var inBacktick = false
        }

        private class LineCleanerSession(
            private val line: String,
            private val state: LexerState
        ) {
            val sb = StringBuilder()
            var i = 0

            fun clean(): String {
                while (i < line.length) {
                    if (handleActiveState()) continue
                    if (handleDelimiterStart()) continue
                    if (isLineCommentStart(line, i)) break
                    sb.append(line[i])
                    i++
                }
                return sb.toString()
            }

            private fun handleActiveState(): Boolean {
                if (state.inBlockComment) {
                    i = resumeBlockComment(line, i, state)
                    return true
                }
                if (state.inTripleQuote) {
                    i = resumeTripleQuote(line, i, state)
                    return true
                }
                if (state.inBacktick) {
                    i = resumeBacktick(line, i, state)
                    return true
                }
                return false
            }

            private fun handleDelimiterStart(): Boolean {
                if (isBlockCommentStart(line, i)) {
                    state.inBlockComment = true
                    i += 2
                    return true
                }
                if (isTripleQuoteStart(line, i)) {
                    state.inTripleQuote = true
                    i += 3
                    return true
                }
                if (line[i] == '"') {
                    i = skipQuotedString(line, i, '"')
                    return true
                }
                if (line[i] == '\'') {
                    i = skipQuotedString(line, i, '\'')
                    return true
                }
                if (line[i] == '`') {
                    i = skipBacktickLiteral(line, i, state)
                    return true
                }
                return false
            }
        }

        private fun resumeBlockComment(line: String, pos: Int, state: LexerState): Int {
            if (line[pos] == '*') {
                if (pos + 1 < line.length && line[pos + 1] == '/') {
                    state.inBlockComment = false
                    return pos + 2
                }
            }
            return pos + 1
        }

        private fun isTripleQuoteTokenAt(line: String, pos: Int): Boolean {
            if (pos + 2 >= line.length) return false
            return line[pos] == '"' && line[pos + 1] == '"' && line[pos + 2] == '"'
        }

        private fun resumeTripleQuote(line: String, pos: Int, state: LexerState): Int {
            if (isTripleQuoteTokenAt(line, pos)) {
                state.inTripleQuote = false
                return pos + 3
            }
            return pos + 1
        }

        private fun resumeBacktick(line: String, pos: Int, state: LexerState): Int {
            if (line[pos] == '\\' && pos + 1 < line.length) {
                return pos + 2
            }
            if (line[pos] == '`') {
                state.inBacktick = false
                return pos + 1
            }
            return pos + 1
        }

        private fun isBlockCommentStart(line: String, pos: Int): Boolean {
            if (pos + 1 >= line.length) return false
            return line[pos] == '/' && line[pos + 1] == '*'
        }

        private fun isLineCommentStart(line: String, pos: Int): Boolean {
            if (pos + 1 >= line.length) return false
            return line[pos] == '/' && line[pos + 1] == '/'
        }

        private fun isTripleQuoteStart(line: String, pos: Int): Boolean {
            return isTripleQuoteTokenAt(line, pos)
        }

        private fun skipQuotedString(line: String, startPos: Int, delimiter: Char): Int {
            var pos = startPos + 1
            while (pos < line.length && line[pos] != delimiter) {
                if (line[pos] == '\\' && pos + 1 < line.length) pos += 2 else pos++
            }
            if (pos < line.length && line[pos] == delimiter) pos++
            return pos
        }

        private fun skipBacktickLiteral(line: String, startPos: Int, state: LexerState): Int {
            var pos = startPos + 1
            var closed = false
            while (pos < line.length) {
                if (line[pos] == '\\' && pos + 1 < line.length) {
                    pos += 2
                    continue
                }
                if (line[pos] == '`') {
                    closed = true
                    pos++
                    break
                }
                pos++
            }
            if (!closed) {
                state.inBacktick = true
            }
            return pos
        }

        private fun cleanLine(line: String, state: LexerState = LexerState()): String {
            return LineCleanerSession(line, state).clean()
        }

        private fun cleanLinesFrom(lines: List<String>, startIdx: Int): List<String> {
            val state = LexerState()
            val result = ArrayList<String>(lines.size - startIdx)
            for (idx in startIdx until lines.size) {
                result.add(cleanLine(lines[idx], state))
            }
            return result
        }

        private val DECL_KEYWORDS = setOf(
            "fun", "class", "interface", "object", "typealias", "val", "var",
            "def",
            "func", "struct", "protocol", "actor", "enum", "extension", "let",
            "function", "const", "type",
            "fn", "trait", "mod", "impl",
            "namespace"
        )

        private val MODIFIER_KEYWORDS = setOf(
            "public", "private", "protected", "internal", "open", "abstract", "sealed",
            "override", "expect", "actual", "inline", "suspend", "data", "value",
            "export", "default", "declare", "readonly", "static", "final", "mutating",
            "fileprivate", "nonisolated", "isolated", "indirect",
            "pub", "async", "unsafe", "extern", "virtual", "constexpr"
        )

        private fun isAnnotationOrAttribute(trimmed: String): Boolean {
            return trimmed.startsWith("@") || trimmed.startsWith("#[")
        }

        private fun extractDeclarationWords(trimmed: String): List<String> {
            return trimmed.split(' ', '\t', '(', ':', '<')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        private fun hasDeclAfterModifiers(words: List<String>): Boolean {
            if (words.size == 1) return true
            for (w in words.drop(1)) {
                if (w in DECL_KEYWORDS) return true
                if (w !in MODIFIER_KEYWORDS) return false
            }
            return false
        }

        private fun isNewDeclarationStart(trimmed: String): Boolean {
            if (isAnnotationOrAttribute(trimmed)) return true
            val words = extractDeclarationWords(trimmed)
            if (words.isEmpty()) return false

            val firstWord = words.first()
            if (firstWord in DECL_KEYWORDS) return true
            if (firstWord !in MODIFIER_KEYWORDS) return false
            return hasDeclAfterModifiers(words)
        }

        private fun hasDeclarationKeyword(str: String): Boolean {
            val tokens = str.split(' ', '\t', '(', ':', '<', '=')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val kws = setOf(
                "class", "interface", "object", "fun", "typealias",
                "func", "struct", "protocol", "actor", "enum", "extension",
                "function", "const", "let", "var", "type",
                "fn", "trait", "mod", "impl",
                "def", "namespace"
            )
            return tokens.any { it in kws }
        }

        private val LINE_CONTINUATION_SUFFIXES = setOf(
            "=", "+", "-", "*", "/", "%", "&&", "||", "?:",
            ".", "?.", "::", ",", "->", ":", "&", "|"
        )

        private val NEXT_LINE_CONTINUATION_PREFIXES = setOf(
            ".", "?.", "?:", "->", "+", "-", "*", "/", "&&", "||", "&", "|"
        )

        private val COMPARISON_CHARS = setOf('=', '!', '<', '>')

        private class BlockScanState {
            var depth = 0
            var started = false
            var hasEquals = false
            var seenKeyword = false
            var parenDepth = 0
            var bracketDepth = 0

            fun isExpressionBoundary(): Boolean {
                if (!seenKeyword) return false
                if (parenDepth != 0) return false
                return bracketDepth == 0
            }

            fun scanLine(clean: String) {
                if (!seenKeyword && hasDeclarationKeyword(clean)) {
                    seenKeyword = true
                }
                for (i in clean.indices) {
                    processChar(clean, i)
                }
            }

            private fun processChar(clean: String, i: Int) {
                when (clean[i]) {
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    '=' -> recordEquals(clean, i)
                    '{' -> recordOpenBrace()
                    '}' -> recordCloseBrace()
                }
            }

            private fun recordEquals(clean: String, i: Int) {
                if (started) return
                if (parenDepth != 0) return
                if (bracketDepth != 0) return
                val prev = if (i > 0) clean[i - 1] else ' '
                val next = if (i + 1 < clean.length) clean[i + 1] else ' '
                if (prev in COMPARISON_CHARS) return
                if (next == '=') return
                hasEquals = true
            }

            private fun recordOpenBrace() {
                if (parenDepth == 0 && bracketDepth == 0) {
                    depth++
                    started = true
                } else if (started) {
                    depth++
                }
            }

            private fun recordCloseBrace() {
                if (started) {
                    depth--
                }
            }
        }

        private fun isLineContinuation(trimmed: String): Boolean =
            LINE_CONTINUATION_SUFFIXES.any { trimmed.endsWith(it) }

        private fun isNextLineContinuation(nextTrimmed: String): Boolean =
            NEXT_LINE_CONTINUATION_PREFIXES.any { nextTrimmed.startsWith(it) }

        private fun findNextNonEmptyLineIndex(cleanLines: List<String>, fromIndex: Int): Int {
            var lookAhead = fromIndex
            while (lookAhead < cleanLines.size && cleanLines[lookAhead].trim().isEmpty()) {
                lookAhead++
            }
            return lookAhead
        }

        private fun isBlockContinuation(trimmed: String, cleanLines: List<String>, fromIndex: Int): Boolean {
            if (isLineContinuation(trimmed)) return true
            val lookAhead = findNextNonEmptyLineIndex(cleanLines, fromIndex)
            if (lookAhead >= cleanLines.size) return false
            return isNextLineContinuation(cleanLines[lookAhead].trim())
        }

        private fun isNonEqualsTerminated(nextTrimmed: String): Boolean =
            !nextTrimmed.startsWith("=") && !nextTrimmed.startsWith("=>")

        private fun isIndentedPastBase(lines: List<String>, startLine: Int, lookAhead: Int): Boolean {
            val baseIndent = lines[startLine - 1].takeWhile { it == ' ' || it == '\t' }.length
            val nextIndent = lines[lookAhead].takeWhile { it == ' ' || it == '\t' }.length
            return nextIndent <= baseIndent
        }

        private fun isSingleExpressionTerminated(
            lines: List<String>,
            cleanLines: List<String>,
            startLine: Int,
            lookAhead: Int,
            hasEquals: Boolean
        ): Boolean {
            val nextTrimmed = cleanLines[lookAhead].trim()
            if (nextTrimmed.startsWith("{")) return false
            if (!hasEquals) return isNonEqualsTerminated(nextTrimmed)
            if (isNewDeclarationStart(nextTrimmed)) return true
            if (nextTrimmed.startsWith("}")) return true
            return isIndentedPastBase(lines, startLine, lookAhead)
        }

        private fun checkExpressionEndLine(
            lines: List<String>,
            cleanLines: List<String>,
            clean: String,
            idx: Int,
            startLine: Int,
            hasEquals: Boolean
        ): Int? {
            val trimmed = clean.trimEnd()
            if (trimmed.endsWith(";")) return idx + 1
            if (isLineContinuation(trimmed)) return null

            val lookAhead = findNextNonEmptyLineIndex(cleanLines, idx + 1)
            if (lookAhead >= cleanLines.size) return idx + 1
            if (isNextLineContinuation(cleanLines[lookAhead].trim())) return null

            if (isSingleExpressionTerminated(lines, cleanLines, startLine, lookAhead, hasEquals)) {
                return idx + 1
            }
            return null
        }

        private fun checkStartedBlockEnd(
            clean: String,
            cleanLines: List<String>,
            idx: Int,
            state: BlockScanState
        ): Int? {
            if (state.depth > 0) return null
            if (!state.hasEquals) return idx + 1
            if (isBlockContinuation(clean.trimEnd(), cleanLines, idx + 1)) return null
            return idx + 1
        }

        private fun checkLineTermination(
            lines: List<String>,
            cleanLines: List<String>,
            clean: String,
            idx: Int,
            startLine: Int,
            state: BlockScanState
        ): Int? {
            if (state.started) {
                return checkStartedBlockEnd(clean, cleanLines, idx, state)
            }
            if (state.isExpressionBoundary()) {
                return checkExpressionEndLine(lines, cleanLines, clean, idx, startLine, state.hasEquals)
            }
            return null
        }

        private fun findBlockEndLine(
            lines: List<String>,
            startLine: Int,
            precomputedCleanLines: List<String>? = null
        ): Int {
            if (startLine > lines.size) return lines.size

            val startIdx = startLine - 1
            val cleanLines = precomputedCleanLines ?: cleanLinesFrom(lines, 0)
            val state = BlockScanState()

            for (idx in startIdx until cleanLines.size) {
                val clean = cleanLines[idx]
                state.scanLine(clean)

                val terminatedLine = checkLineTermination(lines, cleanLines, clean, idx, startLine, state)
                if (terminatedLine != null) return terminatedLine
            }
            return minOf(lines.size, startLine)
        }

        private val TRIPLE_QUOTE_TOKENS = setOf("\"\"\"", "'''")

        private class PythonBlockScanState(val baseIndent: Int) {
            var parenDepth = 0
            var bracketDepth = 0
            var braceDepth = 0
            var headerEnded = false
            var inTripleQuote: String? = null

            fun isBalanced(): Boolean {
                if (inTripleQuote != null) return false
                if (parenDepth != 0) return false
                if (bracketDepth != 0) return false
                if (braceDepth != 0) return false
                return true
            }

            fun handleInTripleQuote(raw: String): Boolean {
                val quote = inTripleQuote ?: return false
                val closePos = raw.indexOf(quote)
                if (closePos >= 0) {
                    inTripleQuote = null
                }
                return true
            }

            fun isBlockTerminated(isPastStart: Boolean, raw: String, trimmed: String): Boolean {
                if (!isPastStart) return false
                if (!headerEnded) return false
                if (!isBalanced()) return false
                val indent = raw.takeWhile { it == ' ' || it == '\t' }.length
                if (indent > baseIndent) return false
                if (trimmed.startsWith(")")) return false
                if (trimmed.startsWith("]")) return false
                if (trimmed.startsWith("}")) return false
                return true
            }

            fun scanLine(raw: String, masked: String) {
                scanTripleQuotes(raw.substringBefore("#"))
                updateDepths(masked)
                if (isBalanced() && masked.trim().endsWith(":")) {
                    headerEnded = true
                }
            }

            private fun scanTripleQuotes(stripped: String) {
                var j = 0
                while (j < stripped.length) {
                    j = stepTripleQuote(stripped, j)
                    if (inTripleQuote != null) break
                }
            }

            private fun stepTripleQuote(stripped: String, j: Int): Int {
                if (j + 2 >= stripped.length) return j + 1
                val sub3 = stripped.substring(j, j + 3)
                if (sub3 !in TRIPLE_QUOTE_TOKENS) return j + 1
                val closeOnSameLine = stripped.indexOf(sub3, j + 3)
                if (closeOnSameLine >= 0) return closeOnSameLine + 3
                inTripleQuote = sub3
                return j + 3
            }

            private fun updateDepths(masked: String) {
                for (i in masked.indices) {
                    stepDepth(masked[i])
                }
            }

            private fun stepDepth(ch: Char) {
                when (ch) {
                    '(' -> parenDepth++
                    ')' -> decParen()
                    '[' -> bracketDepth++
                    ']' -> decBracket()
                    '{' -> braceDepth++
                    '}' -> decBrace()
                }
            }

            private fun decParen() { if (parenDepth > 0) parenDepth-- }
            private fun decBracket() { if (bracketDepth > 0) bracketDepth-- }
            private fun decBrace() { if (braceDepth > 0) braceDepth-- }
        }

        private class PythonBlockScanner(
            private val lines: List<String>,
            private val maskedLines: List<String>,
            private val startLine: Int,
            baseIndent: Int
        ) {
            private val state = PythonBlockScanState(baseIndent)
            private var lastContentLine = startLine

            fun scan(startIdx: Int): Int {
                for (idx in startIdx until lines.size) {
                    if (processLine(idx, lines[idx])) break
                }
                return lastContentLine
            }

            private fun processLine(idx: Int, raw: String): Boolean {
                if (state.handleInTripleQuote(raw)) {
                    lastContentLine = idx + 1
                    return false
                }
                val masked = resolveMaskedLine(idx, raw)
                val trimmed = masked.trim()
                if (trimmed.isEmpty()) return false

                if (state.isBlockTerminated(idx >= startLine, raw, trimmed)) {
                    return true
                }
                state.scanLine(raw, masked)
                lastContentLine = idx + 1
                return false
            }

            private fun resolveMaskedLine(idx: Int, raw: String): String {
                if (idx < maskedLines.size) return maskedLines[idx]
                return raw.substringBefore("#")
            }
        }

        private fun findPythonBlockEndLine(lines: List<String>, maskedLines: List<String>, startLine: Int): Int {
            if (startLine > lines.size) return lines.size
            val startIdx = startLine - 1
            val baseIndent = lines[startIdx].takeWhile { it == ' ' || it == '\t' }.length
            return PythonBlockScanner(lines, maskedLines, startLine, baseIndent).scan(startIdx)
        }

        private fun scanMaskedHeader(state: PythonColonScanState, masked: String): Boolean {
            for (i in masked.indices) {
                stepColonScan(state, masked[i])
            }
            return state.isAtHeaderColon() && masked.endsWith(":")
        }

        private fun buildPythonHeaderSignature(lines: List<String>, maskedLines: List<String>, startLine: Int, endLine: Int): String {
            if (startLine > lines.size) return ""
            val safeEnd = minOf(lines.size, endLine)
            val parts = mutableListOf<String>()
            val state = PythonColonScanState()
            for (lineNo in startLine..safeEnd) {
                val raw = lines[lineNo - 1].substringBefore("#").trim()
                val masked = if (lineNo - 1 < maskedLines.size) maskedLines[lineNo - 1].trim() else raw
                if (raw.isNotEmpty()) parts.add(raw)
                if (scanMaskedHeader(state, masked)) break
            }
            return parts.joinToString(" ").replace(Regex("""\s+"""), " ")
        }

        private fun maskChildLineRanges(
            lines: List<String>,
            startLine: Int,
            endLine: Int,
            childRanges: List<IntRange>
        ): String {
            val safeEnd = minOf(lines.size, endLine)
            val sb = StringBuilder()
            for (lineNo in startLine..safeEnd) {
                val inChild = childRanges.any { lineNo in it }
                if (!inChild) {
                    sb.append(lines[lineNo - 1])
                }
                if (lineNo < safeEnd) sb.append('\n')
            }
            return sb.toString()
        }

        private fun stripLeadingInlineAnnotations(line: String): String {
            var rest = line.trim()
            val inlineAnnRe = Regex("""^@[A-Za-z0-9_]+(?:\((?:[^()]|\([^()]*\))*\))?\s*""")
            while (true) {
                val m = inlineAnnRe.find(rest) ?: break
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            return rest
        }

        private fun computeComplexityMetrics(
            kind: String,
            startLine: Int,
            endLine: Int,
            maskedLines: List<String>,
            lines: List<String>,
            language: String,
            isComposable: Boolean = false,
            functionName: String? = null
        ): Pair<ComplexityVector, Double> {
            return if (kind in setOf("function", "composable", "method")) {
                val sIdx = (startLine - 1).coerceAtLeast(0)
                val eIdx = endLine.coerceAtMost(maskedLines.size)
                val fMasked = if (sIdx < eIdx) maskedLines.subList(sIdx, eIdx) else emptyList()
                val fRaw = if (sIdx < lines.size) lines.subList(sIdx, minOf(eIdx, lines.size)) else emptyList()
                val vec = ComplexityExtractor.calculate(fMasked, fRaw, language, isComposable, functionName)
                val score = ComplexityExtractor.computeScore(vec)
                Pair(vec, score)
            } else {
                Pair(ComplexityVector(), 100.0)
            }
        }

        private class HeaderDepthTracker(var paren: Int = 0, var angle: Int = 0) {
            val isTopLevel: Boolean get() = paren == 0 && angle == 0

            fun adjust(c: Char): Boolean {
                when (c) {
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    else -> return false
                }
                return true
            }
        }

        private fun buildDeclarationHeader(
            lines: List<String>,
            startLine: Int,
            endLine: Int
        ): String {
            val safeEnd = minOf(lines.size, endLine)
            val headerBuilder = StringBuilder()
            for (lineNo in startLine..safeEnd) {
                val raw = lines[lineNo - 1]
                val line = if (lineNo == startLine) stripLeadingInlineAnnotations(raw) else raw
                val clean = line.substringBefore("//")
                val braceIdx = clean.indexOf('{')
                if (braceIdx >= 0) {
                    headerBuilder.append(clean.substring(0, braceIdx))
                    break
                }
                headerBuilder.append(clean).append(' ')
            }
            return headerBuilder.toString()
        }

        private fun findSupertypesColonIndex(header: String): Int {
            val tracker = HeaderDepthTracker()
            for (i in header.indices) {
                val c = header[i]
                if (tracker.adjust(c)) continue
                if (c == ':' && tracker.isTopLevel && isStandaloneColon(header, i)) {
                    return i
                }
            }
            return -1
        }

        private fun isStandaloneColon(header: String, i: Int): Boolean {
            val nextChar = if (i + 1 < header.length) header[i + 1] else ' '
            val prevChar = if (i > 0) header[i - 1] else ' '
            return nextChar != ':' && prevChar != ':'
        }

        private fun splitTopLevelSupertypes(superPart: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            val tracker = HeaderDepthTracker()
            for (c in superPart) {
                if (c == ',' && tracker.isTopLevel) {
                    parts.add(current.toString())
                    current.clear()
                    continue
                }
                tracker.adjust(c)
                current.append(c)
            }
            if (current.isNotEmpty()) parts.add(current.toString())
            return parts
        }

        private fun parseSupertypeEntry(st: String): Pair<String, String>? {
            val trimmed = st.trim()
            val clean = trimmed.substringBefore("(").substringBefore("<").substringBefore(" by ").trim()
                .substringAfterLast('.')
            if (clean.isEmpty() || !clean[0].isUpperCase()) return null
            val edgeType = if ("(" in trimmed.substringBefore(" by ")) "INHERITS" else "IMPLEMENTS"
            return Pair(clean, edgeType)
        }

        private fun parseSupertypeEntries(parts: List<String>): List<Pair<String, String>> {
            val results = mutableListOf<Pair<String, String>>()
            for (st in parts) {
                val entry = parseSupertypeEntry(st)
                if (entry != null) results.add(entry)
            }
            return results
        }

        private fun extractKotlinSupertypes(
            lines: List<String>,
            startLine: Int,
            endLine: Int
        ): List<Pair<String, String>> {
            val header = buildDeclarationHeader(lines, startLine, endLine)
            val colonIdx = findSupertypesColonIndex(header)
            if (colonIdx < 0) return emptyList()
            val superPart = header.substring(colonIdx + 1)
            val parts = splitTopLevelSupertypes(superPart)
            return parseSupertypeEntries(parts)
        }

        private val NON_DOC_LINE_PREFIXES = listOf(
            "// copyright", "// (c)", "// license", "// spdx-", "// all rights reserved",
            "// mark:", "// todo", "// fixme", "// note:", "// hack:", "// xxx",
            "// nolint", "// clang-format", "// eslint-", "// @ts-", "// ts-ignore",
            "// --", "// ==", "// **", "// ##"
        )

        private val PYTHON_NON_DOC_PREFIXES = listOf(
            "# copyright", "# (c)", "# license", "# spdx-", "# all rights reserved",
            "# todo", "# fixme", "# note:", "# type:", "# noqa", "# pylint:", "# flake8:",
            "# mypy:", "# pyright:", "# --", "# ==", "# **", "# ##"
        )

        /**
         * Extracts doc comment description (KDoc, Javadoc, JSDoc, Swift/Rust ///, C++ Doxygen)
         * preceding a declaration at [matchStartLine] (1-based line of the declaration or leading annotation).
         */
        private class BracketDepths(var p: Int = 0, var b: Int = 0, var a: Int = 0) {
            fun isClosed(): Boolean = p <= 0 && b <= 0 && a <= 0
            fun update(line: String, language: String) {
                for (c in line) {
                    when (c) {
                        '(' -> p--
                        ')' -> p++
                        '[' -> b--
                        ']' -> b++
                        '<' -> if (language == "cpp") a--
                        '>' -> if (language == "cpp") a++
                    }
                }
            }
        }

        private fun isMultilineAnnotationEnd(trimmed: String, language: String): Boolean {
            return trimmed.endsWith(")") || trimmed.endsWith("]") || (language == "cpp" && trimmed.endsWith(">"))
        }

        private fun skipMultilineAnnotation(
            lines: List<String>,
            cur: Int,
            trimmed: String,
            language: String
        ): Int? {
            if (!isMultilineAnnotationEnd(trimmed, language)) return null
            val depths = BracketDepths()
            var scanIdx = cur
            while (scanIdx >= 0) {
                val sLine = lines[scanIdx].trim()
                depths.update(sLine, language)
                if (depths.isClosed() && isAnnotationOrAttributeLine(sLine, language)) {
                    return scanIdx - 1
                }
                scanIdx--
            }
            return null
        }

        private fun parseLeadingDocComment(
            lines: List<String>,
            cur: Int,
            trimmed: String,
            language: String
        ): String? {
            if (trimmed.endsWith("*/")) {
                return parseBlockDocComment(lines, cur)
            }
            if (language == "rust" && (trimmed.startsWith("//!") || trimmed.startsWith("/*!"))) {
                return null
            }
            if (trimmed.startsWith("///")) {
                return parseTripleSlashDocComment(lines, cur)
            }
            if (trimmed.startsWith("//")) {
                val low = trimmed.lowercase()
                if (NON_DOC_LINE_PREFIXES.any { low.startsWith(it) }) {
                    return null
                }
                return parseDoubleSlashDocComment(lines, cur)
            }
            return null
        }

        private fun findNextPrecedingIndex(
            lines: List<String>,
            cur: Int,
            trimmed: String,
            language: String
        ): Int? {
            if (isAnnotationOrAttributeLine(trimmed, language)) {
                return cur - 1
            }
            return skipMultilineAnnotation(lines, cur, trimmed, language)
        }

        fun extractDocDescription(
            lines: List<String>,
            matchStartLine: Int,
            language: String
        ): String? {
            val startIdx = matchStartLine - 1
            if (startIdx !in 1..lines.size) return null

            var cur = startIdx - 1
            var blankCount = 0
            while (cur >= 0) {
                val raw = lines[cur]
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) {
                    blankCount++
                    if (blankCount > 1) break
                    cur--
                    continue
                }
                val nextIdx = findNextPrecedingIndex(lines, cur, trimmed, language)
                if (nextIdx != null) {
                    cur = nextIdx
                    blankCount = 0
                    continue
                }
                return parseLeadingDocComment(lines, cur, trimmed, language)
            }
            return null
        }

        private fun isAnnotationOrAttributeLine(trimmed: String, language: String): Boolean {
            return when (language) {
                "rust" -> trimmed.startsWith("#[") || trimmed.startsWith("#![")
                "cpp" -> trimmed.startsWith("[[") || trimmed.startsWith("template") ||
                         trimmed.startsWith("__attribute__") ||
                         trimmed.matches(Regex("""^[A-Z][A-Z0-9_]+(?:\(.*\))?$"""))
                else -> trimmed.startsWith("@")
            }
        }

        private fun findBlockDocStart(lines: List<String>, endIdx: Int): Int? {
            var idx = endIdx
            while (idx >= 0) {
                val trimmed = lines[idx].trimStart()
                if (trimmed.startsWith("/*")) {
                    return if (trimmed.startsWith("/**") || trimmed.startsWith("/*!")) idx else null
                }
                idx--
            }
            return null
        }

        private fun isDocTagLine(line: String): Boolean {
            return line.startsWith("@") || line.startsWith(":param") ||
                line.startsWith("- Parameter") || line.startsWith("- Returns")
        }

        private fun cleanBlockDocLine(raw: String, isFirst: Boolean, isLast: Boolean): String {
            var line = raw.trim()
            if (isFirst) {
                line = if (line.startsWith("/**")) line.removePrefix("/**").trim()
                       else line.removePrefix("/*!").trim()
            }
            if (isLast) {
                line = line.removeSuffix("*/").trim()
            }
            if (line.startsWith("*")) {
                line = line.removePrefix("*").trim()
            }
            return line
        }

        private fun parseBlockDocComment(lines: List<String>, endIdx: Int): String? {
            val startIdx = findBlockDocStart(lines, endIdx) ?: return null
            val contentLines = mutableListOf<String>()
            for (i in startIdx..endIdx) {
                val line = cleanBlockDocLine(lines[i], i == startIdx, i == endIdx)
                if (line.isNotEmpty()) {
                    if (isDocTagLine(line)) {
                        break
                    }
                    contentLines.add(line)
                }
            }
            val result = contentLines.joinToString(" ").trim()
            return if (result.isNotEmpty()) result else null
        }

        private fun parseTripleSlashDocComment(lines: List<String>, endIdx: Int): String? {
            var startIdx = endIdx
            while (startIdx > 0 && lines[startIdx - 1].trim().startsWith("///")) {
                startIdx--
            }
            val contentLines = mutableListOf<String>()
            for (i in startIdx..endIdx) {
                val line = lines[i].trim().removePrefix("///").trim()
                if (isDocTagLine(line)) {
                    break
                }
                if (line.isNotEmpty()) {
                    contentLines.add(line)
                }
            }
            val result = contentLines.joinToString(" ").trim()
            return if (result.isNotEmpty()) result else null
        }

        private fun findDoubleSlashDocStart(lines: List<String>, endIdx: Int): Int {
            var startIdx = endIdx
            while (startIdx > 0 && lines[startIdx - 1].trim().startsWith("//")) {
                val prevLow = lines[startIdx - 1].trim().lowercase()
                if (NON_DOC_LINE_PREFIXES.any { prevLow.startsWith(it) }) break
                startIdx--
            }
            return startIdx
        }

        private fun parseDoubleSlashDocComment(lines: List<String>, endIdx: Int): String? {
            val startIdx = findDoubleSlashDocStart(lines, endIdx)
            val contentLines = mutableListOf<String>()
            for (i in startIdx..endIdx) {
                val rawTrim = lines[i].trim()
                val lLow = rawTrim.lowercase()
                if (NON_DOC_LINE_PREFIXES.any { lLow.startsWith(it) }) {
                    break
                }
                val line = rawTrim.removePrefix("//").trim()
                if (line.isNotEmpty()) {
                    contentLines.add(line)
                }
            }
            val result = contentLines.joinToString(" ").trim()
            return if (result.isNotEmpty()) result else null
        }

        /**
         * Extracts Python docstring (PEP 257) or preceding comment description for a function/class.
         */
        fun extractPythonDocDescription(
            lines: List<String>,
            declStartLine: Int,
            hdrEndLine: Int,
            bodyEndLine: Int
        ): String? {
            val inlineDoc = extractPythonInlineDoc(lines, declStartLine)
            if (inlineDoc != null) return inlineDoc

            val blockDoc = extractPythonBlockDoc(lines, hdrEndLine, bodyEndLine)
            if (blockDoc != null) return blockDoc

            return extractPythonPrecedingComments(lines, declStartLine)
        }

        private val PYTHON_TRIPLE_QUOTES = listOf(
            "\"\"\"" to "\"\"\"",
            "r\"\"\"" to "\"\"\"",
            "f\"\"\"" to "\"\"\"",
            "'''" to "'''",
            "r'''" to "'''",
            "f'''" to "'''"
        )

        private fun detectPythonTripleQuote(text: String): String? {
            for (entry in PYTHON_TRIPLE_QUOTES) {
                if (text.startsWith(entry.first)) return entry.second
            }
            return null
        }

        private class PythonColonScanState {
            var parenDepth = 0
            var bracketDepth = 0

            fun onParenOpen() { parenDepth++ }
            fun onParenClose() { if (parenDepth > 0) parenDepth-- }
            fun onBracketOpen() { bracketDepth++ }
            fun onBracketClose() { if (bracketDepth > 0) bracketDepth-- }
            fun isAtHeaderColon(): Boolean = parenDepth == 0 && bracketDepth == 0
        }

        private fun stepColonScan(state: PythonColonScanState, ch: Char): Boolean {
            when (ch) {
                '(' -> state.onParenOpen()
                ')' -> state.onParenClose()
                '[' -> state.onBracketOpen()
                ']' -> state.onBracketClose()
                ':' -> return state.isAtHeaderColon()
            }
            return false
        }

        private fun findPythonHeaderColonIndex(line: String): Int {
            val state = PythonColonScanState()
            for (i in line.indices) {
                if (stepColonScan(state, line[i])) return i
            }
            return -1
        }

        private fun extractPythonInlineDoc(lines: List<String>, declStartLine: Int): String? {
            val declLineIdx = declStartLine - 1
            if (declLineIdx !in lines.indices) return null
            val declLine = lines[declLineIdx]
            val colonIdx = findPythonHeaderColonIndex(declLine)
            if (colonIdx < 0) return null
            if (colonIdx + 1 >= declLine.length) return null

            val afterColon = declLine.substring(colonIdx + 1).trim()
            val quote = detectPythonTripleQuote(afterColon) ?: return null
            val quotePos = afterColon.indexOf(quote)
            val afterFirst = afterColon.substring(quotePos + quote.length)
            if (!afterFirst.contains(quote)) return null
            val content = afterFirst.substringBefore(quote).trim()
            return if (content.isNotEmpty()) content else null
        }

        private fun findFirstPythonDocLineIndex(lines: List<String>, start: Int, end: Int): Int {
            for (idx in start until end) {
                val trimmed = lines[idx].trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("#")) continue
                return idx
            }
            return -1
        }

        private fun isEligibleDocLine(line: String): Boolean {
            if (line.isEmpty()) return false
            if (line.startsWith(":")) return false
            if (line.startsWith("@")) return false
            return true
        }

        private fun isDocTerminalLine(line: String): Boolean {
            if (line.startsWith(":")) return true
            if (line.startsWith("@")) return true
            return false
        }

        private class MultilineDocCollector(private val quote: String) {
            val docLines = mutableListOf<String>()

            fun addInitial(initial: String) {
                val trimmed = initial.trim()
                if (trimmed.isNotEmpty()) docLines.add(trimmed)
            }

            fun processLine(curLine: String): Boolean {
                if (curLine.contains(quote)) {
                    val beforeEnd = curLine.substringBefore(quote).trim()
                    if (isEligibleDocLine(beforeEnd)) docLines.add(beforeEnd)
                    return false
                }
                if (isDocTerminalLine(curLine)) return false
                if (curLine.isNotEmpty()) docLines.add(curLine)
                return true
            }

            fun buildResult(): String? {
                val result = docLines.joinToString(" ").trim()
                return if (result.isNotEmpty()) result else null
            }
        }

        private fun collectMultilineDocContent(
            lines: List<String>,
            startIdx: Int,
            searchEnd: Int,
            quote: String,
            afterFirst: String
        ): String? {
            val collector = MultilineDocCollector(quote)
            collector.addInitial(afterFirst)
            for (idx in startIdx until searchEnd) {
                val shouldContinue = collector.processLine(lines[idx].trim())
                if (!shouldContinue) break
            }
            return collector.buildResult()
        }

        private fun extractPythonBlockDoc(lines: List<String>, hdrEndLine: Int, bodyEndLine: Int): String? {
            val searchStart = hdrEndLine
            val searchEnd = minOf(lines.size, bodyEndLine)
            val docLineIdx = findFirstPythonDocLineIndex(lines, searchStart, searchEnd)
            if (docLineIdx == -1) return null

            val line = lines[docLineIdx].trim()
            val quote = detectPythonTripleQuote(line) ?: return null
            val afterFirst = line.substring(line.indexOf(quote) + quote.length)
            if (afterFirst.contains(quote)) {
                val content = afterFirst.substringBefore(quote).trim()
                return if (content.isNotEmpty()) content else null
            }
            return collectMultilineDocContent(lines, docLineIdx + 1, searchEnd, quote, afterFirst)
        }

        private fun findPrecedingCommentEndIndex(lines: List<String>, startIdx: Int): Int {
            var cur = startIdx - 1
            while (cur >= 0 && lines[cur].trim().startsWith("@")) cur--
            return cur
        }

        private fun isPythonNonDocComment(lowercaseLine: String): Boolean {
            for (prefix in PYTHON_NON_DOC_PREFIXES) {
                if (lowercaseLine.startsWith(prefix)) return true
            }
            return false
        }

        private fun findPrecedingCommentStartIndex(lines: List<String>, commentEnd: Int): Int {
            var cStart = commentEnd
            while (cStart > 0 && lines[cStart - 1].trim().startsWith("#")) {
                val prevLow = lines[cStart - 1].trim().lowercase()
                if (isPythonNonDocComment(prevLow)) break
                cStart--
            }
            return cStart
        }

        private fun collectPrecedingCommentLines(lines: List<String>, cStart: Int, cEnd: Int): String? {
            val commentLines = mutableListOf<String>()
            for (i in cStart..cEnd) {
                val cTrim = lines[i].trim()
                val cLow = cTrim.lowercase()
                if (isPythonNonDocComment(cLow)) break
                val c = cTrim.removePrefix("#").trim()
                if (c.isNotEmpty()) {
                    commentLines.add(c)
                }
            }
            val result = commentLines.joinToString(" ").trim()
            return if (result.isNotEmpty()) result else null
        }

        private fun extractPythonPrecedingComments(lines: List<String>, declStartLine: Int): String? {
            val startIdx = declStartLine - 1
            if (startIdx <= 0) return null
            val cEnd = findPrecedingCommentEndIndex(lines, startIdx)
            if (cEnd < 0) return null
            if (!lines[cEnd].trim().startsWith("#")) return null
            val cStart = findPrecedingCommentStartIndex(lines, cEnd)
            return collectPrecedingCommentLines(lines, cStart, cEnd)
        }

        private fun extractKotlinImports(maskedText: String): List<Pair<String, Int>> =
            KT_IMP_RE.findAll(maskedText).map { match ->
                Pair(match.groupValues[1], maskedText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

        private fun extractKotlinReceiverTypes(
            text: String,
            baseTypes: Map<String, String>? = null
        ): Map<String, String> {
            val result = baseTypes?.toMutableMap() ?: mutableMapOf()
            for (m in LOCAL_VAL_COLON_RE.findAll(text)) {
                val varName = m.groupValues[1]
                val typeName = m.groupValues[2].substringAfterLast('.').substringBefore('<').trim()
                if (varName.isNotEmpty() && typeName.isNotEmpty() && typeName !in KOTLIN_PRIMITIVE_TYPES) {
                    result[varName] = typeName
                }
            }
            for (m in LOCAL_VAL_ASSIGN_RE.findAll(text)) {
                val varName = m.groupValues[1]
                val typeName = m.groupValues[2].substringAfterLast('.').substringBefore('<').trim()
                if (varName.isNotEmpty() && typeName.isNotEmpty() && typeName !in KOTLIN_PRIMITIVE_TYPES && typeName !in COMMON_CONSTRUCTORS) {
                    result[varName] = typeName
                }
            }
            return result
        }

        private fun extractKotlinKind(kw: String, modTokens: Set<String>, annTokens: List<String>): String = when {
            "expect" in modTokens -> "expect_decl"
            "actual" in modTokens -> "actual_decl"
            "Composable" in annTokens -> "composable"
            "enum" in modTokens -> "enum"
            "annotation" in modTokens -> "annotation"
            kw == "fun" -> "function"
            else -> kw
        }

        private fun parseModifierTokens(modifiers: String?): Set<String> {
            if (modifiers.isNullOrBlank()) return emptySet()
            return modifiers.split(Regex("""\s+""")).filter { it.isNotEmpty() }.toSet()
        }

        private fun resolveKotlinDeclName(rawName: String?, kw: String, modTokens: Set<String>): String? {
            if (!rawName.isNullOrEmpty()) return rawName
            if (kw == "object" && "companion" in modTokens) return "Companion"
            return null
        }

        private fun extractKotlinDeclHeader(match: MatchResult): Triple<String, String, Set<String>>? {
            val kw = match.groups["kw"]?.value ?: return null
            val modTokens = parseModifierTokens(match.groups["modifiers"]?.value)
            val name = resolveKotlinDeclName(match.groups["name"]?.value, kw, modTokens) ?: return null
            return Triple(kw, name, modTokens)
        }

        private fun lineNumberOfOffset(text: String, offset: Int): Int =
            text.substring(0, offset).count { it == '\n' } + 1

        private fun resolveDeclStartOffset(match: MatchResult): Int {
            val modGroup = match.groups["modifiers"]
            if (modGroup != null && modGroup.value.isNotEmpty()) {
                return modGroup.range.first
            }
            return match.groups["kw"]!!.range.first
        }

        private fun resolveKotlinDeclLines(
            match: MatchResult,
            maskedText: String,
            lines: List<String>,
            cleanLines: List<String>
        ): Triple<Int, Int, Int> {
            val matchStartLine = lineNumberOfOffset(maskedText, match.range.first)
            val declStartOffset = resolveDeclStartOffset(match)
            val startLine = lineNumberOfOffset(maskedText, declStartOffset)
            val endLine = findBlockEndLine(lines, startLine, cleanLines)
            return Triple(matchStartLine, startLine, endLine)
        }

        private fun extractKotlinDoc(
            isTopLevel: Boolean,
            kw: String,
            annTokens: List<String>,
            lines: List<String>,
            matchStartLine: Int
        ): String? {
            val isEligible = isTopLevel && (kw in listOf("class", "interface", "object", "fun") || "Composable" in annTokens)
            return if (isEligible) extractDocDescription(lines, matchStartLine, "kotlin") else null
        }

        private fun addKotlinEdges(
            nodeId: String,
            isTopLevel: Boolean,
            kw: String,
            startLine: Int,
            endLine: Int,
            lines: List<String>,
            imports: List<Pair<String, Int>>,
            projectId: String,
            edges: MutableList<GraphEdge>
        ) {
            if (isTopLevel) {
                for ((impTarget, impLine) in imports) {
                    edges.add(GraphEdge(
                        edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                        sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                        lineNumber = impLine
                    ))
                }
            }
            if (kw in listOf("class", "interface", "object")) {
                for ((superName, edgeType) in extractKotlinSupertypes(lines, startLine, endLine)) {
                    edges.add(GraphEdge(
                        edgeId = "$nodeId:sup:$superName:$startLine", projectId = projectId,
                        sourceNodeId = nodeId, targetNodeId = "sym:$superName", edgeType = edgeType,
                        lineNumber = startLine
                    ))
                }
            }
        }

        private fun resolveCallTargetAndProvenance(
            receiver: String?,
            callee: String,
            localReceiverTypes: Map<String, String>
        ): Pair<String, String> {
            val resolvedType = if (!receiver.isNullOrEmpty()) localReceiverTypes[receiver] else null
            val targetSym = when {
                resolvedType != null -> "$resolvedType.$callee"
                !receiver.isNullOrEmpty() -> "$receiver.$callee"
                else -> callee
            }
            val provenance = when {
                resolvedType != null -> "CALL_RECEIVER:$receiver;type=$resolvedType"
                !receiver.isNullOrEmpty() -> "CALL_RECEIVER:$receiver"
                else -> "INFERRED_CALL"
            }
            return Pair(targetSym, provenance)
        }

        private fun extractKotlinStandardCallEdges(
            node: GraphNode,
            body: String,
            localReceiverTypes: Map<String, String>,
            projectId: String,
            seenCallees: MutableSet<String>,
            edges: MutableList<GraphEdge>
        ) {
            for (callMatch in CALL_RE.findAll(body)) {
                val receiver = callMatch.groups["receiver"]?.value?.trim()
                val callee = callMatch.groups["callee"]?.value ?: continue
                val isSelf = callee == node.name && (receiver.isNullOrEmpty() || receiver == "this")
                if (isSelf || callee in CALL_STOPWORDS) continue

                val (targetSym, provenance) = resolveCallTargetAndProvenance(receiver, callee, localReceiverTypes)
                if (seenCallees.add(targetSym)) {
                    val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                    edges.add(GraphEdge(
                        edgeId = "${node.nodeId}:call:$targetSym:$callLine", projectId = projectId,
                        sourceNodeId = node.nodeId, targetNodeId = "sym:$targetSym", edgeType = "CALLS",
                        provenance = provenance, confidence = 0.85, lineNumber = callLine
                    ))
                }
            }
        }

        private fun extractKotlinInfixCallEdges(
            node: GraphNode,
            body: String,
            projectId: String,
            seenCallees: MutableSet<String>,
            edges: MutableList<GraphEdge>
        ) {
            for (infixMatch in INFIX_CALL_RE.findAll(body)) {
                val callee = infixMatch.groups["callee"]?.value ?: continue
                val isIgnored = callee == node.name || callee in INFIX_STOPWORDS || callee in CALL_STOPWORDS
                if (!isIgnored && seenCallees.add(callee)) {
                    val callLine = node.startLine + body.substring(0, infixMatch.range.first).count { it == '\n' }
                    edges.add(GraphEdge(
                        edgeId = "${node.nodeId}:infix:$callee:$callLine", projectId = projectId,
                        sourceNodeId = node.nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                        provenance = "INFERRED_INFIX_CALL", confidence = 0.85, lineNumber = callLine
                    ))
                }
            }
        }

        private fun extractKotlinCallEdges(
            node: GraphNode,
            body: String,
            localReceiverTypes: Map<String, String>,
            projectId: String,
            edges: MutableList<GraphEdge>
        ) {
            val seenCallees = mutableSetOf<String>()
            extractKotlinStandardCallEdges(node, body, localReceiverTypes, projectId, seenCallees, edges)
            extractKotlinInfixCallEdges(node, body, projectId, seenCallees, edges)
        }

        private fun shrinkScopeStack(scopeStack: MutableList<ScopeEntry>, startLine: Int) {
            while (scopeStack.isNotEmpty() && scopeStack.last().endLine < startLine) {
                scopeStack.removeAt(scopeStack.size - 1)
            }
        }

        private fun isPublicTopLevel(isTopLevel: Boolean, modTokens: Set<String>, kw: String): Boolean =
            isTopLevel && "private" !in modTokens && "companion" !in modTokens && kw != "typealias"

        private data class FileExtractionContext(
            val projectId: String,
            val relPath: String,
            val fileId: String,
            val moduleId: String,
            val sourceSet: String,
            val maskedText: String,
            val lines: List<String>,
            val maskedLines: List<String>,
            val cleanLines: List<String>,
            val imports: List<Pair<String, Int>>
        )

        private data class KotlinDeclSpec(
            val name: String,
            val kw: String,
            val modTokens: Set<String>,
            val annotations: String,
            val enclosingPrefix: String,
            val startLine: Int,
            val endLine: Int,
            val matchStartLine: Int,
            val isTopLevel: Boolean
        )

        private fun buildKotlinNode(
            ctx: FileExtractionContext,
            pkgName: String,
            decl: KotlinDeclSpec
        ): GraphNode {
            val (nodeId, qualifiedName) = if (decl.enclosingPrefix.isNotEmpty()) {
                Pair("${ctx.fileId}#${decl.enclosingPrefix}.${decl.name}", "$pkgName.${decl.enclosingPrefix}.${decl.name}")
            } else {
                Pair("${ctx.fileId}#${decl.name}", "$pkgName.${decl.name}")
            }

            val annTokens = Regex("""@([A-Za-z0-9_]+)""").findAll(decl.annotations).map { it.groupValues[1] }.toList()
            val sigLine = if (decl.startLine <= ctx.lines.size) stripLeadingInlineAnnotations(ctx.lines[decl.startLine - 1]) else decl.name
            val kind = extractKotlinKind(decl.kw, decl.modTokens, annTokens)
            val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, decl.name, annTokens)
            val description = extractKotlinDoc(decl.isTopLevel, decl.kw, annTokens, ctx.lines, decl.matchStartLine)

            val (cVec, cScore) = computeComplexityMetrics(
                kind = kind,
                startLine = decl.startLine,
                endLine = decl.endLine,
                maskedLines = ctx.maskedLines,
                lines = ctx.lines,
                language = "kotlin",
                isComposable = "Composable" in annTokens,
                functionName = decl.name
            )

            return GraphNode(
                nodeId = nodeId, projectId = ctx.projectId, fileId = ctx.fileId, name = decl.name,
                qualifiedName = qualifiedName, kind = kind, signature = sigLine,
                startLine = decl.startLine, endLine = decl.endLine, moduleId = ctx.moduleId,
                sourceSet = ctx.sourceSet, semioticLayer = sem, archLayer = arch, isTestDouble = isTd,
                description = description, isTopLevel = decl.isTopLevel,
                cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                maxNestingDepth = cVec.maxNesting, complexityScore = cScore
            )
        }

        private fun extractChildLineRanges(nodes: List<GraphNode>, target: GraphNode): List<IntRange> {
            val result = mutableListOf<IntRange>()
            for (node in nodes) {
                if (node.nodeId == target.nodeId) continue
                if (node.startLine >= target.startLine && node.endLine <= target.endLine && (node.startLine > target.startLine || node.endLine < target.endLine)) {
                    result.add(node.startLine..node.endLine)
                }
            }
            return result
        }

        private fun pushScopeEntryIfContainer(
            kw: String,
            name: String,
            startLine: Int,
            endLine: Int,
            scopeStack: MutableList<ScopeEntry>
        ) {
            if (kw in setOf("class", "interface", "object", "enum", "fun")) {
                scopeStack.add(ScopeEntry(name, startLine, endLine))
            }
        }

        private fun processSingleKotlinDeclaration(
            match: MatchResult,
            ctx: FileExtractionContext,
            pkgName: String,
            scopeStack: MutableList<ScopeEntry>,
            edges: MutableList<GraphEdge>
        ): Pair<GraphNode, Boolean>? {
            val (kw, name, modTokens) = extractKotlinDeclHeader(match) ?: return null
            val annotations = match.groups["annotations"]?.value ?: ""
            val (matchStartLine, startLine, endLine) = resolveKotlinDeclLines(match, ctx.maskedText, ctx.lines, ctx.cleanLines)
            shrinkScopeStack(scopeStack, startLine)

            val isTopLevel = scopeStack.isEmpty()
            val isPublicTop = isPublicTopLevel(isTopLevel, modTokens, kw)
            val enclosingPrefix = scopeStack.joinToString(".") { it.name }
            val node = buildKotlinNode(
                ctx = ctx,
                pkgName = pkgName,
                decl = KotlinDeclSpec(
                    name = name,
                    kw = kw,
                    modTokens = modTokens,
                    annotations = annotations,
                    enclosingPrefix = enclosingPrefix,
                    startLine = startLine,
                    endLine = endLine,
                    matchStartLine = matchStartLine,
                    isTopLevel = isTopLevel
                )
            )
            addKotlinEdges(node.nodeId, isTopLevel, kw, startLine, endLine, ctx.lines, ctx.imports, ctx.projectId, edges)
            pushScopeEntryIfContainer(kw, name, startLine, endLine, scopeStack)
            return Pair(node, isPublicTop)
        }

        private fun parseKotlinDeclarations(
            ctx: FileExtractionContext,
            pkgName: String,
            edges: MutableList<GraphEdge>
        ): Pair<List<GraphNode>, Int> {
            val nodes = mutableListOf<GraphNode>()
            val scopeStack = mutableListOf<ScopeEntry>()
            var topLevelCount = 0

            for (match in KT_DECL_RE.findAll(ctx.maskedText)) {
                val (node, isPublicTop) = processSingleKotlinDeclaration(
                    match, ctx, pkgName, scopeStack, edges
                ) ?: continue
                nodes.add(node)
                if (isPublicTop) topLevelCount++
            }
            return Pair(nodes, topLevelCount)
        }

        private fun parseKotlinCalls(
            nodes: List<GraphNode>,
            maskedLines: List<String>,
            fileVarTypes: Map<String, String>,
            projectId: String,
            edges: MutableList<GraphEdge>
        ) {
            for (node in nodes) {
                val childRanges = extractChildLineRanges(nodes, node)
                val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
                val localReceiverTypes = extractKotlinReceiverTypes(body, fileVarTypes)
                extractKotlinCallEdges(node, body, localReceiverTypes, projectId, edges)
            }
        }

        private fun extractKotlin(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "kotlin")
            val maskedLines = maskedText.lines()
            val cleanLines = cleanLinesFrom(lines, 0)

            val pkgMatch = KT_PKG_RE.find(maskedText)
            val pkgName = pkgMatch?.groupValues?.get(1) ?: relPath.replace('/', '.')
            val imports = extractKotlinImports(maskedText)
            val ctx = FileExtractionContext(
                projectId, relPath, fileId, moduleId, sourceSet,
                maskedText, lines, maskedLines, cleanLines, imports
            )

            val (nodes, topLevelCount) = parseKotlinDeclarations(ctx, pkgName, edges)

            val fileVarTypes = extractKotlinReceiverTypes(maskedText)
            parseKotlinCalls(nodes, maskedLines, fileVarTypes, projectId, edges)

            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractSwiftImports(maskedText: String): List<Pair<String, Int>> =
            SWIFT_IMP_RE.findAll(maskedText).map { match ->
                Pair(match.groupValues[1], maskedText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

        private fun parseSwiftCallEdge(
            callMatch: MatchResult,
            node: GraphNode,
            body: String,
            seenCallees: MutableSet<String>,
            projectId: String
        ): GraphEdge? {
            val receiver = callMatch.groups["receiver"]?.value?.trim()
            val callee = callMatch.groups["callee"]?.value ?: return null
            val isSelfCall = callee == node.name && (receiver.isNullOrEmpty() || receiver == "self")
            if (isSelfCall || callee in CALL_STOPWORDS) return null
            val targetSym = if (!receiver.isNullOrEmpty()) "$receiver.$callee" else callee
            if (!seenCallees.add(targetSym)) return null

            val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
            return GraphEdge(
                edgeId = "${node.nodeId}:call:$targetSym:$callLine",
                projectId = projectId,
                sourceNodeId = node.nodeId,
                targetNodeId = "sym:$targetSym",
                edgeType = "CALLS",
                provenance = "INFERRED_CALL",
                confidence = 0.85,
                lineNumber = callLine
            )
        }

        private fun extractSwiftCallEdges(
            node: GraphNode,
            allNodes: List<GraphNode>,
            maskedLines: List<String>,
            projectId: String
        ): List<GraphEdge> {
            val childRanges = buildChildRanges(allNodes, node)
            val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
            val seenCallees = mutableSetOf<String>()
            val edges = mutableListOf<GraphEdge>()
            for (callMatch in CALL_RE.findAll(body)) {
                val edge = parseSwiftCallEdge(callMatch, node, body, seenCallees, projectId)
                if (edge != null) edges.add(edge)
            }
            return edges
        }

        private fun extractSwiftDeclHeader(match: MatchResult): Triple<String, String, String>? {
            val name = match.groups["name"]?.value ?: return null
            val groups = match.groups
            val kw = groups["kw"]?.value ?: "class"
            val indent = groups["indent"]?.value ?: ""
            return Triple(indent, kw, name)
        }

        private fun buildSwiftNodeIdentifiers(fileId: String, relPath: String, name: String, scopeStack: List<ScopeEntry>): Pair<String, String> {
            val prefix = scopeStack.joinToString(".") { it.name }
            return if (prefix.isNotEmpty()) {
                Pair("$fileId#$prefix.$name", "$relPath:$prefix.$name")
            } else {
                Pair("$fileId#$name", "$relPath:$name")
            }
        }

        private fun buildSwiftNode(
            ctx: FileExtractionContext,
            nodeId: String,
            qualifiedName: String,
            name: String,
            kw: String,
            startLine: Int,
            endLine: Int,
            isTopLevel: Boolean
        ): GraphNode {
            val sigLine = if (startLine <= ctx.lines.size) ctx.lines[startLine - 1].trim() else name
            val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, name)
            val description = if (isTopLevel) extractDocDescription(ctx.lines, startLine, "swift") else null
            val kind = if (kw == "func") "function" else kw
            val (cVec, cScore) = computeComplexityMetrics(
                kind = kind,
                startLine = startLine,
                endLine = endLine,
                maskedLines = ctx.maskedLines,
                lines = ctx.lines,
                language = "swift",
                functionName = name
            )
            return GraphNode(
                nodeId = nodeId, projectId = ctx.projectId, fileId = ctx.fileId, name = name,
                qualifiedName = qualifiedName, kind = kind,
                signature = sigLine, startLine = startLine, endLine = endLine,
                moduleId = ctx.moduleId, sourceSet = ctx.sourceSet, semioticLayer = sem,
                archLayer = arch, isTestDouble = isTd,
                description = description, isTopLevel = isTopLevel,
                cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                maxNestingDepth = cVec.maxNesting, complexityScore = cScore
            )
        }

        private fun buildSwiftImportEdges(nodeId: String, projectId: String, imports: List<Pair<String, Int>>): List<GraphEdge> {
            return imports.map { (impTarget, impLine) ->
                GraphEdge(
                    edgeId = "$nodeId:imp:$impTarget:$impLine",
                    projectId = projectId,
                    sourceNodeId = nodeId,
                    targetNodeId = "sym:$impTarget",
                    edgeType = "IMPORTS",
                    lineNumber = impLine
                )
            }
        }

        private fun isSwiftTopLevel(indent: String, scopeStack: List<ScopeEntry>): Boolean =
            indent.isEmpty() && scopeStack.isEmpty()

        private fun recordSwiftScopeEntry(kw: String, name: String, startLine: Int, endLine: Int, scopeStack: MutableList<ScopeEntry>) {
            if (kw in SWIFT_SCOPE_KEYWORDS) {
                scopeStack.add(ScopeEntry(name, startLine, endLine))
            }
        }

        private fun recordSwiftNodeAndEdges(
            node: GraphNode,
            isTopLevel: Boolean,
            nodeId: String,
            projectId: String,
            imports: List<Pair<String, Int>>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ) {
            nodes.add(node)
            if (isTopLevel) {
                edges.addAll(buildSwiftImportEdges(nodeId, projectId, imports))
            }
        }

        private fun processSwiftDeclaration(
            match: MatchResult,
            ctx: FileExtractionContext,
            scopeStack: MutableList<ScopeEntry>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ): Boolean {
            val (indent, kw, name) = extractSwiftDeclHeader(match) ?: return false
            val startLine = ctx.maskedText.substring(0, match.range.first).count { it == '\n' } + 1
            val endLine = findBlockEndLine(ctx.lines, startLine, ctx.cleanLines)

            pruneScopeStack(scopeStack, startLine)
            val (nodeId, qualifiedName) = buildSwiftNodeIdentifiers(ctx.fileId, ctx.relPath, name, scopeStack)
            val isTopLevel = isSwiftTopLevel(indent, scopeStack)

            val node = buildSwiftNode(
                ctx, nodeId, qualifiedName, name, kw, startLine, endLine, isTopLevel
            )
            recordSwiftNodeAndEdges(node, isTopLevel, nodeId, ctx.projectId, ctx.imports, nodes, edges)
            recordSwiftScopeEntry(kw, name, startLine, endLine, scopeStack)

            return isTopLevel && kw != "extension"
        }

        private fun extractSwift(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "swift")
            val maskedLines = maskedText.lines()
            val cleanLines = cleanLinesFrom(lines, 0)
            val scopeStack = mutableListOf<ScopeEntry>()
            val imports = extractSwiftImports(maskedText)
            val ctx = FileExtractionContext(
                projectId, relPath, fileId, moduleId, sourceSet,
                maskedText, lines, maskedLines, cleanLines, imports
            )

            var topLevelCount = 0
            for (match in SWIFT_DECL_RE.findAll(maskedText)) {
                if (processSwiftDeclaration(match, ctx, scopeStack, nodes, edges)) {
                    topLevelCount++
                }
            }

            for (node in nodes) {
                edges.addAll(extractSwiftCallEdges(node, nodes, maskedLines, projectId))
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private val TS_ARROW_OR_FN_RE = Regex("""=\s*(?:async\s*)?(?:function\b|(?:\([^)]*\)|[A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]+)?=>)""")
        private val TS_METHOD_STOPWORDS = setOf("if", "while", "for", "switch", "catch", "function", "return", "throw", "new", "typeof", "instanceof")
        private val TS_DOC_KINDS = setOf("class", "interface", "function", "enum")
        private val TS_SCOPE_KINDS = setOf("class", "interface", "function", "enum")
        private val TS_NON_COUNTABLE_TOP_LEVEL = setOf("typealias", "variable")

        private data class TsDeclCandidate(
            val rangeStart: Int,
            val indent: String,
            val kw: String,
            val name: String,
            val isMethod: Boolean
        )

        private fun extractTypeScriptImports(maskedText: String, sourceText: String): List<Pair<String, Int>> {
            val imports = mutableListOf<Pair<String, Int>>()
            for (m in TS_IMP_RE.findAll(maskedText)) {
                val origMatch = sourceText.substring(m.range)
                val parsed = TS_IMP_RE.find(origMatch) ?: m
                val named = parsed.groupValues[1].ifEmpty { parsed.groupValues[2] }
                val lineNo = maskedText.substring(0, m.range.first).count { it == '\n' } + 1
                for (sym in named.split(",").filter { it.isNotBlank() }) {
                    val clean = sym.trim().substringBefore(" as ").trim()
                    if (clean.isNotEmpty()) imports.add(Pair(clean, lineNo))
                }
            }
            return imports
        }

        private fun extractTsDeclCandidate(match: MatchResult): TsDeclCandidate? {
            val groups = match.groups
            val name = groups["name"]?.value ?: return null
            val indent = groups["indent"]?.value.orEmpty()
            val kw = groups["kw"]?.value ?: "class"
            return TsDeclCandidate(match.range.first, indent, kw, name, isMethod = false)
        }

        private fun getTsMethodName(match: MatchResult): String? {
            val name = match.groups["name"]?.value
            return if (name != null && name !in TS_METHOD_STOPWORDS) name else null
        }

        private fun extractTsMethodCandidate(match: MatchResult): TsDeclCandidate? {
            val name = getTsMethodName(match) ?: return null
            val indent = match.groups["indent"]?.value.orEmpty()
            return TsDeclCandidate(match.range.first, indent, "method", name, isMethod = true)
        }

        private fun collectTsDeclarations(maskedText: String): List<TsDeclCandidate> {
            val list = mutableListOf<TsDeclCandidate>()
            for (match in TS_DECL_RE.findAll(maskedText)) {
                val candidate = extractTsDeclCandidate(match)
                if (candidate != null) list.add(candidate)
            }
            return list
        }

        private fun collectTsMethods(maskedText: String): List<TsDeclCandidate> {
            val list = mutableListOf<TsDeclCandidate>()
            for (match in TS_METHOD_DECL_RE.findAll(maskedText)) {
                val candidate = extractTsMethodCandidate(match)
                if (candidate != null) list.add(candidate)
            }
            return list
        }

        private fun collectTsDeclCandidates(maskedText: String): List<TsDeclCandidate> {
            val combined = collectTsDeclarations(maskedText) + collectTsMethods(maskedText)
            return combined.sortedBy { it.rangeStart }
        }

        private fun findTsBlockEndLine(lines: List<String>, cleanLines: List<String>, startLine: Int): Int {
            val hasBrace = (startLine - 1 until minOf(cleanLines.size, startLine + 3)).any { cleanLines[it].contains('{') }
            return if (hasBrace) findBlockEndLine(lines, startLine, cleanLines) else startLine
        }

        private fun resolveTsKind(candidate: TsDeclCandidate, kw: String, declHeaderSlice: String): String = when {
            candidate.isMethod -> "method"
            kw == "function" -> "function"
            kw in setOf("const", "let", "var") -> if (TS_ARROW_OR_FN_RE.containsMatchIn(declHeaderSlice)) "function" else "variable"
            kw == "interface" -> "interface"
            kw == "type" -> "typealias"
            kw == "enum" || kw.endsWith("enum") -> "enum"
            else -> "class"
        }

        private fun buildTsNodeIdentifiers(fileId: String, relPath: String, name: String, scopeStack: List<ScopeEntry>): Pair<String, String> {
            val prefix = scopeStack.joinToString(".") { it.name }
            return if (prefix.isNotEmpty()) {
                Pair("$fileId#$prefix.$name", "$relPath:$prefix.$name")
            } else {
                Pair("$fileId#$name", "$relPath:$name")
            }
        }

        private fun buildTsNode(
            ctx: FileExtractionContext,
            nodeId: String,
            qualifiedName: String,
            name: String,
            kind: String,
            startLine: Int,
            endLine: Int,
            isTopLevel: Boolean
        ): GraphNode {
            val sigLine = if (startLine <= ctx.lines.size) ctx.lines[startLine - 1].trim() else name
            val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, name)
            val description = if (isTopLevel && kind in TS_DOC_KINDS) extractDocDescription(ctx.lines, startLine, "typescript") else null
            val (cVec, cScore) = computeComplexityMetrics(
                kind = kind,
                startLine = startLine,
                endLine = endLine,
                maskedLines = ctx.maskedLines,
                lines = ctx.lines,
                language = "typescript",
                functionName = name
            )
            return GraphNode(
                nodeId = nodeId, projectId = ctx.projectId, fileId = ctx.fileId, name = name,
                qualifiedName = qualifiedName, kind = kind,
                signature = sigLine, startLine = startLine, endLine = endLine,
                moduleId = ctx.moduleId, sourceSet = ctx.sourceSet, semioticLayer = sem,
                archLayer = arch, isTestDouble = isTd,
                description = description, isTopLevel = isTopLevel,
                cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                maxNestingDepth = cVec.maxNesting, complexityScore = cScore
            )
        }

        private fun buildTsImportEdges(nodeId: String, projectId: String, imports: List<Pair<String, Int>>): List<GraphEdge> {
            return imports.map { (impTarget, impLine) ->
                GraphEdge(
                    edgeId = "$nodeId:imp:$impTarget:$impLine",
                    projectId = projectId,
                    sourceNodeId = nodeId,
                    targetNodeId = "sym:$impTarget",
                    edgeType = "IMPORTS",
                    lineNumber = impLine
                )
            }
        }

        private fun recordTsScopeEntry(kind: String, name: String, startLine: Int, endLine: Int, scopeStack: MutableList<ScopeEntry>) {
            if (kind in TS_SCOPE_KINDS) {
                scopeStack.add(ScopeEntry(name, startLine, endLine))
            }
        }

        private fun recordTsNodeAndEdges(
            node: GraphNode,
            isTopLevel: Boolean,
            nodeId: String,
            projectId: String,
            imports: List<Pair<String, Int>>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ) {
            nodes.add(node)
            if (isTopLevel) {
                edges.addAll(buildTsImportEdges(nodeId, projectId, imports))
            }
        }

        private fun processTsCandidate(
            candidate: TsDeclCandidate,
            ctx: FileExtractionContext,
            scopeStack: MutableList<ScopeEntry>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ): Boolean {
            val startLine = ctx.maskedText.substring(0, candidate.rangeStart).count { it == '\n' } + 1
            pruneScopeStack(scopeStack, startLine)

            if (candidate.isMethod && scopeStack.isEmpty()) {
                return false
            }

            val endLine = findTsBlockEndLine(ctx.lines, ctx.cleanLines, startLine)
            val (nodeId, qualifiedName) = buildTsNodeIdentifiers(ctx.fileId, ctx.relPath, candidate.name, scopeStack)

            val declHeaderSlice = ctx.maskedLines.subList(startLine - 1, minOf(ctx.maskedLines.size, minOf(endLine, startLine + 2))).joinToString(" ")
            val kind = resolveTsKind(candidate, candidate.kw, declHeaderSlice)
            val isTopLevel = !candidate.isMethod && candidate.indent.isEmpty() && scopeStack.isEmpty()

            if (!isTopLevel && kind == "variable") return false

            val node = buildTsNode(
                ctx, nodeId, qualifiedName, candidate.name, kind, startLine, endLine, isTopLevel
            )
            recordTsNodeAndEdges(node, isTopLevel, nodeId, ctx.projectId, ctx.imports, nodes, edges)
            recordTsScopeEntry(kind, candidate.name, startLine, endLine, scopeStack)

            return isTopLevel && kind !in TS_NON_COUNTABLE_TOP_LEVEL
        }

        private fun parseTsCallEdge(
            callMatch: MatchResult,
            node: GraphNode,
            body: String,
            seenCallees: MutableSet<String>,
            projectId: String
        ): GraphEdge? {
            val receiver = callMatch.groups["receiver"]?.value?.trim()
            val callee = callMatch.groups["callee"]?.value ?: return null
            val targetSym = if (!receiver.isNullOrEmpty()) "$receiver.$callee" else callee
            val isSelfCall = callee == node.name && (receiver.isNullOrEmpty() || receiver == "this")
            if (isSelfCall || callee in CALL_STOPWORDS || !seenCallees.add(targetSym)) return null

            val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
            return GraphEdge(
                edgeId = "${node.nodeId}:call:$targetSym:$callLine",
                projectId = projectId,
                sourceNodeId = node.nodeId,
                targetNodeId = "sym:$targetSym",
                edgeType = "CALLS",
                provenance = "INFERRED_CALL",
                confidence = 0.85,
                lineNumber = callLine
            )
        }

        private fun extractTsCallEdges(
            node: GraphNode,
            allNodes: List<GraphNode>,
            maskedLines: List<String>,
            projectId: String
        ): List<GraphEdge> {
            val childRanges = buildChildRanges(allNodes, node)
            val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
            val seenCallees = mutableSetOf<String>()
            val edges = mutableListOf<GraphEdge>()
            for (callMatch in CALL_RE.findAll(body)) {
                val edge = parseTsCallEdge(callMatch, node, body, seenCallees, projectId)
                if (edge != null) edges.add(edge)
            }
            return edges
        }

        private fun extractTypeScript(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "typescript")
            val maskedLines = maskedText.lines()
            val cleanLines = cleanLinesFrom(lines, 0)
            val scopeStack = mutableListOf<ScopeEntry>()
            val imports = extractTypeScriptImports(maskedText, sourceText)
            val declCandidates = collectTsDeclCandidates(maskedText)
            val ctx = FileExtractionContext(
                projectId, relPath, fileId, moduleId, sourceSet,
                maskedText, lines, maskedLines, cleanLines, imports
            )

            var topLevelCount = 0
            val seenStartOffsets = mutableSetOf<Int>()
            for (candidate in declCandidates) {
                if (!seenStartOffsets.add(candidate.rangeStart)) continue
                if (processTsCandidate(candidate, ctx, scopeStack, nodes, edges)) {
                    topLevelCount++
                }
            }

            for (node in nodes) {
                edges.addAll(extractTsCallEdges(node, nodes, maskedLines, projectId))
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractRustImports(maskedText: String): List<Pair<String, Int>> =
            RS_USE_RE.findAll(maskedText).map { match ->
                val sym = match.groupValues[1].substringAfterLast("::")
                Pair(sym, maskedText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

        private fun resolveRustKind(kw: String): String = when (kw) {
            "fn" -> "function"
            "trait" -> "interface"
            "enum" -> "enum"
            "type" -> "typealias"
            "mod" -> "module"
            else -> "class"
        }

        private fun buildRustNodeIdentifiers(fileId: String, relPath: String, name: String, scopeStack: List<ScopeEntry>): Pair<String, String> {
            val prefix = scopeStack.joinToString("::") { it.name }
            return if (prefix.isNotEmpty()) {
                Pair("$fileId#$prefix::$name", "$relPath:$prefix::$name")
            } else {
                Pair("$fileId#$name", "$relPath:$name")
            }
        }

        private fun buildRustNode(
            ctx: FileExtractionContext,
            nodeId: String,
            qualifiedName: String,
            name: String,
            kind: String,
            startLine: Int,
            endLine: Int,
            isTopLevel: Boolean
        ): GraphNode {
            val sigLine = if (startLine <= ctx.lines.size) ctx.lines[startLine - 1].trim() else name
            val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, name)
            val description = if (isTopLevel && kind in RUST_DOC_KINDS) extractDocDescription(ctx.lines, startLine, "rust") else null
            val (cVec, cScore) = computeComplexityMetrics(
                kind = kind,
                startLine = startLine,
                endLine = endLine,
                maskedLines = ctx.maskedLines,
                lines = ctx.lines,
                language = "rust",
                functionName = name
            )
            return GraphNode(
                nodeId = nodeId, projectId = ctx.projectId, fileId = ctx.fileId, name = name,
                qualifiedName = qualifiedName, kind = kind,
                signature = sigLine, startLine = startLine, endLine = endLine,
                moduleId = ctx.moduleId, sourceSet = ctx.sourceSet, semioticLayer = sem,
                archLayer = arch, isTestDouble = isTd,
                description = description, isTopLevel = isTopLevel,
                cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                maxNestingDepth = cVec.maxNesting, complexityScore = cScore
            )
        }

        private fun buildRustImportEdges(nodeId: String, projectId: String, imports: List<Pair<String, Int>>): List<GraphEdge> {
            return imports.map { (impTarget, impLine) ->
                GraphEdge(
                    edgeId = "$nodeId:imp:$impTarget:$impLine",
                    projectId = projectId,
                    sourceNodeId = nodeId,
                    targetNodeId = "sym:$impTarget",
                    edgeType = "IMPORTS",
                    lineNumber = impLine
                )
            }
        }

        private fun isRustTopLevel(indent: String, scopeStack: List<ScopeEntry>): Boolean =
            indent.isEmpty() && scopeStack.isEmpty()

        private fun recordRustScopeEntry(kw: String, name: String, startLine: Int, endLine: Int, scopeStack: MutableList<ScopeEntry>) {
            if (kw in RUST_SCOPE_KEYWORDS) {
                scopeStack.add(ScopeEntry(name, startLine, endLine))
            }
        }

        private fun recordRustNodeAndEdges(
            node: GraphNode,
            isTopLevel: Boolean,
            nodeId: String,
            projectId: String,
            imports: List<Pair<String, Int>>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ) {
            nodes.add(node)
            if (isTopLevel) {
                edges.addAll(buildRustImportEdges(nodeId, projectId, imports))
            }
        }

        private fun processRustDeclaration(
            match: MatchResult,
            ctx: FileExtractionContext,
            scopeStack: MutableList<ScopeEntry>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ): Boolean {
            val (indent, kw, name) = extractSwiftDeclHeader(match) ?: return false
            val kind = resolveRustKind(kw)
            val startLine = ctx.maskedText.substring(0, match.range.first).count { it == '\n' } + 1
            val endLine = findBlockEndLine(ctx.lines, startLine, ctx.cleanLines)

            pruneScopeStack(scopeStack, startLine)
            val (nodeId, qualifiedName) = buildRustNodeIdentifiers(ctx.fileId, ctx.relPath, name, scopeStack)
            val isTopLevel = isRustTopLevel(indent, scopeStack)

            val node = buildRustNode(
                ctx, nodeId, qualifiedName, name, kind, startLine, endLine, isTopLevel
            )
            recordRustNodeAndEdges(node, isTopLevel, nodeId, ctx.projectId, ctx.imports, nodes, edges)
            recordRustScopeEntry(kw, name, startLine, endLine, scopeStack)

            return isTopLevel && kind !in RUST_NON_COUNTABLE_TOP_LEVEL
        }

        private fun parseRustCallEdge(
            callMatch: MatchResult,
            node: GraphNode,
            body: String,
            seenCallees: MutableSet<String>,
            projectId: String
        ): GraphEdge? {
            val receiver = callMatch.groups["receiver"]?.value?.trim()
            val callee = callMatch.groups["callee"]?.value ?: return null
            val targetSym = when {
                callee == "new" && !receiver.isNullOrEmpty() -> receiver
                !receiver.isNullOrEmpty() -> "$receiver::$callee"
                else -> callee
            }
            val isSelfCall = targetSym == node.name || (callee == node.name && (receiver.isNullOrEmpty() || receiver == "self"))
            if (isSelfCall || targetSym in CALL_STOPWORDS || !seenCallees.add(targetSym)) return null

            val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
            return GraphEdge(
                edgeId = "${node.nodeId}:call:$targetSym:$callLine",
                projectId = projectId,
                sourceNodeId = node.nodeId,
                targetNodeId = "sym:$targetSym",
                edgeType = "CALLS",
                provenance = "INFERRED_CALL",
                confidence = 0.85,
                lineNumber = callLine
            )
        }

        private fun extractRustCallEdges(
            node: GraphNode,
            allNodes: List<GraphNode>,
            maskedLines: List<String>,
            projectId: String
        ): List<GraphEdge> {
            val childRanges = buildChildRanges(allNodes, node)
            val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
            val seenCallees = mutableSetOf<String>()
            val edges = mutableListOf<GraphEdge>()
            for (callMatch in CALL_RE.findAll(body)) {
                val edge = parseRustCallEdge(callMatch, node, body, seenCallees, projectId)
                if (edge != null) edges.add(edge)
            }
            return edges
        }

        private fun extractRust(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "rust")
            val maskedLines = maskedText.lines()
            val cleanLines = cleanLinesFrom(lines, 0)
            val scopeStack = mutableListOf<ScopeEntry>()
            val imports = extractRustImports(maskedText)
            val ctx = FileExtractionContext(
                projectId, relPath, fileId, moduleId, sourceSet,
                maskedText, lines, maskedLines, cleanLines, imports
            )

            var topLevelCount = 0
            for (match in RS_DECL_RE.findAll(maskedText)) {
                if (processRustDeclaration(match, ctx, scopeStack, nodes, edges)) {
                    topLevelCount++
                }
            }

            for (node in nodes) {
                edges.addAll(extractRustCallEdges(node, nodes, maskedLines, projectId))
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractCppImports(sourceText: String, maskedLines: List<String>): List<Pair<String, Int>> =
            CPP_INC_RE.findAll(sourceText).mapNotNull { match ->
                val lineIdx = sourceText.substring(0, match.range.first).count { it == '\n' }
                val maskedLine = if (lineIdx < maskedLines.size) maskedLines[lineIdx] else ""
                if (maskedLine.trim().isEmpty()) {
                    null
                } else {
                    val header = match.groupValues[1].substringAfterLast('/')
                    Pair(header, lineIdx + 1)
                }
            }.toList()

        private fun resolveCppDeclKind(kw: String): String = when {
            kw == "namespace" -> "namespace"
            kw == "struct" -> "struct"
            kw.startsWith("enum") -> "enum"
            else -> "class"
        }

        private fun buildCppTypeNodeIdentifiers(fileId: String, relPath: String, name: String, scopeStack: List<ScopeEntry>): Pair<String, String> {
            val prefix = scopeStack.joinToString("::") { it.name }
            return if (prefix.isNotEmpty()) {
                Pair("$fileId#$prefix::$name", "$relPath:$prefix::$name")
            } else {
                Pair("$fileId#$name", "$relPath:$name")
            }
        }

        private fun buildCppImportEdges(nodeId: String, projectId: String, imports: List<Pair<String, Int>>): List<GraphEdge> {
            return imports.map { (impTarget, impLine) ->
                GraphEdge(
                    edgeId = "$nodeId:imp:$impTarget:$impLine",
                    projectId = projectId,
                    sourceNodeId = nodeId,
                    targetNodeId = "sym:$impTarget",
                    edgeType = "IMPORTS",
                    lineNumber = impLine
                )
            }
        }

        private fun isInsideCppClassOrStruct(scopeStack: List<ScopeEntry>): Boolean {
            for (entry in scopeStack) {
                if (entry.kind == "class" || entry.kind == "struct") return true
            }
            return false
        }

        private fun isTopLevelCppType(indent: String, scopeStack: List<ScopeEntry>): Boolean {
            if (indent.isNotEmpty()) return false
            return !isInsideCppClassOrStruct(scopeStack)
        }

        private fun updateCppScopeStack(
            kind: String, name: String, startLine: Int, endLine: Int, scopeStack: MutableList<ScopeEntry>
        ) {
            if (kind in CPP_SCOPE_KINDS) {
                scopeStack.add(ScopeEntry(name, startLine, endLine, kind))
            }
        }

        private fun isReportableTopLevelCppType(isTopLevel: Boolean, kind: String): Boolean {
            if (!isTopLevel) return false
            return kind != "namespace"
        }

        private fun resolveDeclarationSignature(lines: List<String>, line: Int, fallback: String): String {
            if (line in 1..lines.size) {
                return lines[line - 1].trim()
            }
            return fallback
        }

        private fun resolveCppTypeDoc(isTopLevel: Boolean, kind: String, lines: List<String>, startLine: Int): String? {
            if (!isTopLevel) return null
            if (kind !in CPP_DOC_KINDS) return null
            return extractDocDescription(lines, startLine, "cpp")
        }

        private fun createCppTypeNode(
            ctx: FileExtractionContext,
            ids: Pair<String, String>,
            decl: Triple<String, String, String>,
            linesAndDoc: Triple<IntRange, String?, Boolean>
        ): GraphNode {
            val (nodeId, qualifiedName) = ids
            val (name, kind, sigLine) = decl
            val (lineRange, description, isTopLevel) = linesAndDoc
            val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, name)
            return GraphNode(
                nodeId = nodeId, projectId = ctx.projectId, fileId = ctx.fileId, name = name,
                qualifiedName = qualifiedName, kind = kind,
                signature = sigLine, startLine = lineRange.first, endLine = lineRange.last,
                moduleId = ctx.moduleId, sourceSet = ctx.sourceSet, semioticLayer = sem,
                archLayer = arch, isTestDouble = isTd,
                description = description, isTopLevel = isTopLevel
            )
        }

        private fun processCppTypeDeclaration(
            declMatch: MatchResult,
            ctx: FileExtractionContext,
            scopeStack: MutableList<ScopeEntry>,
            seenNodeIds: MutableSet<String>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ): Boolean {
            val header = extractSwiftDeclHeader(declMatch)
            if (header == null) return false
            val (indent, kw, name) = header
            val kind = resolveCppDeclKind(kw)
            val startLine = ctx.maskedText.substring(0, declMatch.range.first).count { it == '\n' } + 1
            val endLine = findBlockEndLine(ctx.lines, startLine, ctx.cleanLines)

            pruneScopeStack(scopeStack, startLine)
            val (nodeId, qualifiedName) = buildCppTypeNodeIdentifiers(ctx.fileId, ctx.relPath, name, scopeStack)

            val isTopLevel = isTopLevelCppType(indent, scopeStack)
            val sigLine = resolveDeclarationSignature(ctx.lines, startLine, name)
            if (!seenNodeIds.add(nodeId)) return false

            val description = resolveCppTypeDoc(isTopLevel, kind, ctx.lines, startLine)
            nodes.add(
                createCppTypeNode(
                    ctx = ctx,
                    ids = nodeId to qualifiedName,
                    decl = Triple(name, kind, sigLine),
                    linesAndDoc = Triple(startLine..endLine, description, isTopLevel)
                )
            )

            if (isTopLevel) {
                edges.addAll(buildCppImportEdges(nodeId, ctx.projectId, ctx.imports))
            }
            updateCppScopeStack(kind, name, startLine, endLine, scopeStack)
            return isReportableTopLevelCppType(isTopLevel, kind)
        }

        private fun isCppConstructor(nodes: List<GraphNode>, shortName: String, startLine: Int): Boolean {
            for (node in nodes) {
                if (node.name == shortName && (node.kind == "class" || node.kind == "struct")) {
                    if (startLine >= node.startLine && startLine <= node.endLine) return true
                }
            }
            return false
        }

        private fun resolveCppFunctionQualifiedName(rawName: String, enclosingClass: GraphNode?): String = when {
            rawName.contains("::") -> rawName
            enclosingClass != null -> "${enclosingClass.name}::$rawName"
            else -> rawName
        }

        private fun isCppFunctionStopword(name: String): Boolean =
            name in CALL_STOPWORDS || name in CPP_FUNC_STOPWORDS

        private fun isSkippedCppFunction(rawName: String, nodes: List<GraphNode>, startLine: Int): Boolean {
            val shortName = rawName.substringAfterLast("::")
            if (isCppFunctionStopword(shortName)) return true
            return isCppConstructor(nodes, shortName, startLine)
        }

        private fun findEnclosingCppClass(nodes: List<GraphNode>, startLine: Int): GraphNode? {
            for (node in nodes) {
                if ((node.kind == "class" || node.kind == "struct") && startLine > node.startLine && startLine < node.endLine) {
                    return node
                }
            }
            return null
        }

        private fun isTopLevelCppFunction(indent: String, enclosingClass: GraphNode?, rawName: String): Boolean {
            if (indent.isNotEmpty()) return false
            if (enclosingClass != null) return false
            return !rawName.contains("::")
        }

        private fun resolveCppFunctionDoc(isTopLevel: Boolean, lines: List<String>, line: Int): String? {
            if (!isTopLevel) return null
            return extractDocDescription(lines, line, "cpp")
        }

        private fun createCppFunctionNode(
            ctx: FileExtractionContext,
            nodeId: String,
            qualifiedCppName: String,
            shortName: String,
            sigLine: String,
            startLine: Int,
            endLine: Int,
            description: String?,
            isTopLevel: Boolean
        ): GraphNode {
            val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, shortName)
            val (cVec, cScore) = computeComplexityMetrics(
                kind = "function",
                startLine = startLine,
                endLine = endLine,
                maskedLines = ctx.maskedLines,
                lines = ctx.lines,
                language = "cpp",
                functionName = shortName
            )
            return GraphNode(
                nodeId = nodeId, projectId = ctx.projectId, fileId = ctx.fileId, name = shortName,
                qualifiedName = "${ctx.relPath}:$qualifiedCppName", kind = "function",
                signature = sigLine, startLine = startLine, endLine = endLine,
                moduleId = ctx.moduleId, sourceSet = ctx.sourceSet, semioticLayer = sem,
                archLayer = arch, isTestDouble = isTd,
                description = description, isTopLevel = isTopLevel,
                cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                maxNestingDepth = cVec.maxNesting, complexityScore = cScore
            )
        }

        private fun processCppFunctionDeclaration(
            declMatch: MatchResult,
            ctx: FileExtractionContext,
            seenNodeIds: MutableSet<String>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ): Boolean {
            val rawName = declMatch.groups["name"]?.value
            if (rawName == null) return false
            val startLine = ctx.maskedText.substring(0, declMatch.range.first).count { it == '\n' } + 1
            if (isSkippedCppFunction(rawName, nodes, startLine)) return false

            val shortName = rawName.substringAfterLast("::")
            val endLine = findBlockEndLine(ctx.lines, startLine, ctx.cleanLines)
            val enclosingClass = findEnclosingCppClass(nodes, startLine)
            val qualifiedCppName = resolveCppFunctionQualifiedName(rawName, enclosingClass)
            val indent = declMatch.groups["indent"]?.value.orEmpty()
            val isTopLevel = isTopLevelCppFunction(indent, enclosingClass, rawName)
            val nodeId = "${ctx.fileId}#$qualifiedCppName"
            if (!seenNodeIds.add(nodeId)) return false

            val sigLine = resolveDeclarationSignature(ctx.lines, startLine, shortName)
            val description = resolveCppFunctionDoc(isTopLevel, ctx.lines, startLine)
            nodes.add(
                createCppFunctionNode(
                    ctx = ctx, nodeId = nodeId, qualifiedCppName = qualifiedCppName, shortName = shortName,
                    sigLine = sigLine, startLine = startLine, endLine = endLine,
                    description = description, isTopLevel = isTopLevel
                )
            )

            if (isTopLevel) {
                edges.addAll(buildCppImportEdges(nodeId, ctx.projectId, ctx.imports))
            }
            return isTopLevel
        }

        private fun parseCppCallEdge(
            callMatch: MatchResult,
            node: GraphNode,
            body: String,
            seenCallees: MutableSet<String>,
            projectId: String
        ): GraphEdge? {
            val receiver = callMatch.groups["receiver"]?.value?.trim()
            val callee = callMatch.groups["callee"]?.value ?: return null
            val targetSym = if (!receiver.isNullOrEmpty()) "$receiver::$callee" else callee
            val isSelfCall = callee == node.name && (receiver.isNullOrEmpty() || receiver == "this")
            if (isSelfCall || callee in CALL_STOPWORDS || !seenCallees.add(targetSym)) return null

            val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
            return GraphEdge(
                edgeId = "${node.nodeId}:call:$targetSym:$callLine",
                projectId = projectId,
                sourceNodeId = node.nodeId,
                targetNodeId = "sym:$targetSym",
                edgeType = "CALLS",
                provenance = "INFERRED_CALL",
                confidence = 0.85,
                lineNumber = callLine
            )
        }

        private fun extractCppCallEdges(
            node: GraphNode,
            allNodes: List<GraphNode>,
            maskedLines: List<String>,
            projectId: String
        ): List<GraphEdge> {
            val childRanges = buildChildRanges(allNodes, node)
            val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
            val seenCallees = mutableSetOf<String>()
            val edges = mutableListOf<GraphEdge>()
            for (callMatch in CALL_RE.findAll(body)) {
                val edge = parseCppCallEdge(callMatch, node, body, seenCallees, projectId)
                if (edge != null) edges.add(edge)
            }
            return edges
        }

        private fun collectCppTypeDeclarations(
            ctx: FileExtractionContext,
            seenNodeIds: MutableSet<String>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ): Int {
            val scopeStack = mutableListOf<ScopeEntry>()
            var count = 0
            for (declMatch in CPP_DECL_RE.findAll(ctx.maskedText)) {
                if (processCppTypeDeclaration(declMatch, ctx, scopeStack, seenNodeIds, nodes, edges)) {
                    count++
                }
            }
            return count
        }

        private fun collectCppFunctionDeclarations(
            ctx: FileExtractionContext,
            seenNodeIds: MutableSet<String>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ): Int {
            var count = 0
            for (declMatch in CPP_FUNC_RE.findAll(ctx.maskedText)) {
                if (processCppFunctionDeclaration(declMatch, ctx, seenNodeIds, nodes, edges)) {
                    count++
                }
            }
            return count
        }

        private fun collectAllCppCallEdges(
            nodes: List<GraphNode>, maskedLines: List<String>, projectId: String
        ): List<GraphEdge> {
            val edges = mutableListOf<GraphEdge>()
            for (node in nodes) {
                edges.addAll(extractCppCallEdges(node, nodes, maskedLines, projectId))
            }
            return edges
        }

        private fun extractCpp(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "cpp")
            val maskedLines = maskedText.lines()
            val cleanLines = cleanLinesFrom(lines, 0)
            val imports = extractCppImports(sourceText, maskedLines)
            val seenNodeIds = mutableSetOf<String>()
            val ctx = FileExtractionContext(
                projectId, relPath, fileId, moduleId, sourceSet,
                maskedText, lines, maskedLines, cleanLines, imports
            )

            val typeTopLevelCount = collectCppTypeDeclarations(ctx, seenNodeIds, nodes, edges)
            val funcTopLevelCount = collectCppFunctionDeclarations(ctx, seenNodeIds, nodes, edges)
            edges.addAll(collectAllCppCallEdges(nodes, maskedLines, projectId))

            return Triple(nodes, edges, typeTopLevelCount + funcTopLevelCount)
        }

        private fun findPythonHeaderEndLine(maskedLines: List<String>, startLine: Int, endLine: Int): Int {
            if (startLine > maskedLines.size) return startLine
            val safeEnd = minOf(maskedLines.size, endLine)
            val state = PythonColonScanState()
            for (lineNo in startLine..safeEnd) {
                val masked = maskedLines[lineNo - 1].trim()
                if (scanMaskedHeader(state, masked)) return lineNo
            }
            return startLine
        }

        private val ALLOWED_PYTHON_DUNDERS = setOf(
            "__init__", "__enter__", "__exit__", "__call__", "__str__", "__repr__",
            "__getitem__", "__setitem__", "__len__", "__iter__", "__next__", "__contains__",
            "__aenter__", "__aexit__", "__eq__", "__hash__"
        )

        private fun isAllowedPythonDeclaration(name: String): Boolean {
            if (!name.startsWith("_")) return true
            val isDunder = name.startsWith("__") && name.endsWith("__")
            return isDunder && name in ALLOWED_PYTHON_DUNDERS
        }

        private fun extractPythonImports(maskedText: String): List<Pair<String, Int>> {
            return PY_IMP_RE.findAll(maskedText).map { match ->
                val mod = match.groupValues[1].ifEmpty { match.groupValues[2] }
                Pair(mod, maskedText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()
        }

        private fun buildPythonNodeIdentifiers(
            fileId: String,
            relPath: String,
            name: String,
            scopeStack: List<ScopeEntry>
        ): Pair<String, String> {
            val prefix = scopeStack.joinToString(".") { it.name }
            return if (prefix.isNotEmpty()) {
                Pair("$fileId#$prefix.$name", "$relPath:$prefix.$name")
            } else {
                Pair("$fileId#$name", "$relPath:$name")
            }
        }

        private fun buildPythonNode(
            ctx: FileExtractionContext,
            nodeId: String,
            qualifiedName: String,
            name: String,
            kw: String,
            startLine: Int,
            endLine: Int,
            isTopLevel: Boolean
        ): GraphNode {
            val sigLine = buildPythonHeaderSignature(ctx.lines, ctx.maskedLines, startLine, endLine)
            val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, name)
            val headerEndLine = findPythonHeaderEndLine(ctx.maskedLines, startLine, endLine)
            val description = if (isTopLevel) {
                extractPythonDocDescription(ctx.lines, startLine, headerEndLine, endLine)
            } else null
            val kind = if ("class" in kw) "class" else "function"
            val (cVec, cScore) = computeComplexityMetrics(
                kind = kind,
                startLine = startLine,
                endLine = endLine,
                maskedLines = ctx.maskedLines,
                lines = ctx.lines,
                language = "python",
                functionName = name
            )

            return GraphNode(
                nodeId = nodeId, projectId = ctx.projectId, fileId = ctx.fileId, name = name,
                qualifiedName = qualifiedName, kind = kind,
                signature = sigLine, startLine = startLine, endLine = endLine,
                moduleId = ctx.moduleId, sourceSet = ctx.sourceSet, semioticLayer = sem,
                archLayer = arch, isTestDouble = isTd,
                description = description, isTopLevel = isTopLevel,
                cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                maxNestingDepth = cVec.maxNesting, complexityScore = cScore
            )
        }

        private fun buildPythonImportEdges(
            nodeId: String,
            projectId: String,
            imports: List<Pair<String, Int>>
        ): List<GraphEdge> {
            return imports.map { (impTarget, impLine) ->
                GraphEdge(
                    edgeId = "$nodeId:imp:$impTarget:$impLine",
                    projectId = projectId,
                    sourceNodeId = nodeId,
                    targetNodeId = "sym:$impTarget",
                    edgeType = "IMPORTS",
                    lineNumber = impLine
                )
            }
        }

        private fun isAllowedPythonCallee(callee: String, nodeName: String, receiver: String?): Boolean {
            val isSelfCall = callee == nodeName && (receiver.isNullOrEmpty() || receiver == "self")
            return !isSelfCall && callee !in CALL_STOPWORDS && !callee.startsWith("_")
        }

        private fun pruneScopeStack(scopeStack: MutableList<ScopeEntry>, startLine: Int) {
            while (scopeStack.isNotEmpty() && scopeStack.last().endLine < startLine) {
                scopeStack.removeAt(scopeStack.size - 1)
            }
        }

        private fun getGroupName(match: MatchResult): String? {
            val name = match.groups["name"]?.value
            return if (name != null && isAllowedPythonDeclaration(name)) name else null
        }

        private fun extractDeclHeader(match: MatchResult): Triple<String, String, String>? {
            val name = getGroupName(match) ?: return null
            val groups = match.groups
            val indent = groups["indent"]?.value ?: ""
            val kw = groups["kw"]?.value ?: "def"
            return Triple(indent, kw, name)
        }

        private fun appendScopeIfBlock(kw: String, name: String, startLine: Int, endLine: Int, scopeStack: MutableList<ScopeEntry>) {
            if ("class" in kw || "def" in kw) {
                scopeStack.add(ScopeEntry(name, startLine, endLine))
            }
        }

        private fun recordNodeAndEdges(
            node: GraphNode,
            isTopLevel: Boolean,
            nodeId: String,
            projectId: String,
            imports: List<Pair<String, Int>>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ) {
            nodes.add(node)
            if (isTopLevel) {
                edges.addAll(buildPythonImportEdges(nodeId, projectId, imports))
            }
        }

        private fun processPythonDeclaration(
            match: MatchResult,
            ctx: FileExtractionContext,
            scopeStack: MutableList<ScopeEntry>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ): Boolean {
            val (indent, kw, name) = extractDeclHeader(match) ?: return false
            val startLine = ctx.maskedText.substring(0, match.range.first).count { it == '\n' } + 1
            val endLine = findPythonBlockEndLine(ctx.lines, ctx.maskedLines, startLine)

            pruneScopeStack(scopeStack, startLine)
            val (nodeId, qualifiedName) = buildPythonNodeIdentifiers(ctx.fileId, ctx.relPath, name, scopeStack)
            val isTopLevel = indent.isEmpty() && scopeStack.isEmpty()

            val node = buildPythonNode(
                ctx = ctx, nodeId = nodeId, qualifiedName = qualifiedName, name = name, kw = kw,
                startLine = startLine, endLine = endLine, isTopLevel = isTopLevel
            )
            recordNodeAndEdges(node, isTopLevel, nodeId, ctx.projectId, ctx.imports, nodes, edges)
            appendScopeIfBlock(kw, name, startLine, endLine, scopeStack)
            return isTopLevel
        }

        private fun buildChildRanges(nodes: List<GraphNode>, parent: GraphNode): List<IntRange> {
            return nodes.filter { child ->
                child.nodeId != parent.nodeId &&
                    child.startLine >= parent.startLine &&
                    child.endLine <= parent.endLine &&
                    (child.startLine > parent.startLine || child.endLine < parent.endLine)
            }.map { it.startLine..it.endLine }
        }

        private fun parseCallEdge(
            callMatch: MatchResult,
            node: GraphNode,
            scanStartLine: Int,
            body: String,
            seenCallees: MutableSet<String>,
            projectId: String
        ): GraphEdge? {
            val receiver = callMatch.groups["receiver"]?.value?.trim()
            val callee = callMatch.groups["callee"]?.value ?: return null
            val targetSym = if (!receiver.isNullOrEmpty()) "$receiver.$callee" else callee
            if (!isAllowedPythonCallee(callee, node.name, receiver) || !seenCallees.add(targetSym)) {
                return null
            }
            val callLine = scanStartLine + body.substring(0, callMatch.range.first).count { it == '\n' }
            return GraphEdge(
                edgeId = "${node.nodeId}:call:$targetSym:$callLine", projectId = projectId,
                sourceNodeId = node.nodeId, targetNodeId = "sym:$targetSym", edgeType = "CALLS",
                provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
            )
        }

        private fun extractNodeCallEdges(
            node: GraphNode,
            nodes: List<GraphNode>,
            maskedLines: List<String>,
            projectId: String
        ): List<GraphEdge> {
            val childRanges = buildChildRanges(nodes, node)
            val headerEndLine = findPythonHeaderEndLine(maskedLines, node.startLine, node.endLine)
            val scanStartLine = if (headerEndLine < node.endLine) headerEndLine + 1 else node.startLine
            val body = maskChildLineRanges(maskedLines, scanStartLine, node.endLine, childRanges)
            val seenCallees = mutableSetOf<String>()
            val edges = mutableListOf<GraphEdge>()

            for (callMatch in CALL_RE.findAll(body)) {
                val edge = parseCallEdge(callMatch, node, scanStartLine, body, seenCallees, projectId)
                if (edge != null) edges.add(edge)
            }
            return edges
        }

        private fun extractPython(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "python")
            val maskedLines = maskedText.lines()
            val scopeStack = mutableListOf<ScopeEntry>()
            val imports = extractPythonImports(maskedText)
            val ctx = FileExtractionContext(
                projectId, relPath, fileId, moduleId, sourceSet,
                maskedText, lines, maskedLines, emptyList(), imports
            )

            var topLevelCount = 0
            for (match in PY_DECL_RE.findAll(maskedText)) {
                val isTop = processPythonDeclaration(match, ctx, scopeStack, nodes, edges)
                if (isTop) topLevelCount++
            }

            for (node in nodes) {
                edges.addAll(extractNodeCallEdges(node, nodes, maskedLines, projectId))
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private class NodeLookupIndex(
            val byName: Map<String, List<GraphNode>>,
            val byQName: Map<String, GraphNode>,
            val byNodeId: Map<String, GraphNode>
        )

        private fun buildNodeLookupIndex(nodes: List<GraphNode>, projectId: String): NodeLookupIndex {
            val byName = mutableMapOf<String, MutableList<GraphNode>>()
            val byQName = mutableMapOf<String, GraphNode>()
            val byNodeId = mutableMapOf<String, GraphNode>()
            for (node in nodes) {
                if (node.projectId != projectId) continue
                byName.getOrPut(node.name) { mutableListOf() }.add(node)
                byQName[node.qualifiedName] = node
                byNodeId[node.nodeId] = node
            }
            return NodeLookupIndex(byName, byQName, byNodeId)
        }

        private fun buildImportsByFileMap(
            rawEdges: List<GraphEdge>,
            byNodeId: Map<String, GraphNode>
        ): Map<String, Set<String>> {
            val importsByFile = mutableMapOf<String, MutableSet<String>>()
            for (edge in rawEdges) {
                if (edge.edgeType == "IMPORTS") {
                    val srcFileId = byNodeId[edge.sourceNodeId]?.fileId ?: edge.sourceNodeId.substringBefore("#")
                    val impSym = edge.targetNodeId.removePrefix("sym:")
                    importsByFile.getOrPut(srcFileId) { mutableSetOf() }.add(impSym)
                }
            }
            return importsByFile
        }

        private fun extractSymbolReceiver(sym: String): String? = when {
            sym.contains('.') -> sym.substringBeforeLast('.')
            sym.contains("::") -> sym.substringBeforeLast("::")
            else -> null
        }

        private fun resolveReceiverMatches(
            candidates: List<GraphNode>,
            receiver: String?,
            srcFileId: String,
            srcNode: GraphNode?
        ): List<GraphNode> {
            if (receiver == null) return emptyList()
            val scored = candidates.map { cand ->
                cand to scoreReceiverCandidate(cand, receiver, srcFileId, srcNode)
            }
            val maxScore = scored.maxOfOrNull { it.second } ?: 0.0
            if (maxScore <= 0.0) return emptyList()
            return scored.filter { it.second == maxScore }.map { it.first }
        }

        private fun isSymbolImported(qn: String, fileImports: Set<String>): Boolean {
            val pkg = qn.substringBeforeLast('.', "")
            if (qn in fileImports) return true
            if ("$pkg.*" in fileImports) return true
            return fileImports.any { imp -> qn.endsWith(".$imp") }
        }

        private fun filterByLexicalScope(pool: List<GraphNode>, srcNode: GraphNode?): List<GraphNode> {
            if (pool.size <= 1 || srcNode == null) return pool
            val srcScope = srcNode.qualifiedName.substringBeforeLast('.', "")
            if (srcScope.isEmpty()) return pool

            val exactScope = pool.filter { it.qualifiedName.substringBeforeLast('.', "") == srcScope }
            if (exactScope.isNotEmpty()) return exactScope

            val companionScope = pool.filter { cand ->
                cand.qualifiedName.substringBeforeLast('.', "") == "$srcScope.Companion"
            }
            if (companionScope.isNotEmpty()) return companionScope

            val outerScope = pool.filter { cand ->
                val candScope = cand.qualifiedName.substringBeforeLast('.', "")
                candScope.isNotEmpty() && srcScope.startsWith("$candScope.")
            }
            if (outerScope.isNotEmpty()) {
                val maxLen = outerScope.maxOf { it.qualifiedName.substringBeforeLast('.', "").length }
                return outerScope.filter { it.qualifiedName.substringBeforeLast('.', "").length == maxLen }
            }

            val topLevel = pool.filter { it.isTopLevel }
            if (topLevel.isNotEmpty()) return topLevel

            return pool
        }

        private data class DisambiguationContext(
            val srcNode: GraphNode?,
            val srcFileId: String,
            val srcModId: String,
            val fileImports: Set<String>
        )

        private fun selectCandidatePool(
            candidatePool: List<GraphNode>,
            receiverMatches: List<GraphNode>,
            fallback: List<GraphNode>,
            ctx: DisambiguationContext
        ): List<GraphNode> {
            val sameFile = candidatePool.filter { it.fileId == ctx.srcFileId }
            if (sameFile.isNotEmpty()) return filterByLexicalScope(sameFile, ctx.srcNode)

            val imported = candidatePool.filter { isSymbolImported(it.qualifiedName, ctx.fileImports) }
            if (imported.isNotEmpty()) return filterByLexicalScope(imported, ctx.srcNode)

            if (receiverMatches.isNotEmpty()) return receiverMatches

            if (ctx.srcModId.isNotEmpty()) {
                val sameModule = candidatePool.filter { it.moduleId == ctx.srcModId }
                if (sameModule.isNotEmpty()) return filterByLexicalScope(sameModule, ctx.srcNode)
            }
            val srcArch = ctx.srcNode?.archLayer.orEmpty()
            if (srcArch.isNotEmpty()) {
                val sameArchLayer = candidatePool.filter { it.archLayer == srcArch }
                if (sameArchLayer.isNotEmpty()) return filterByLexicalScope(sameArchLayer, ctx.srcNode)
            }
            return filterByLexicalScope(fallback, ctx.srcNode)
        }

        private fun pickBestCandidate(pool: List<GraphNode>): GraphNode =
            pool.sortedWith(
                compareByDescending<GraphNode> { it.inDegree }.thenBy { it.nodeId }
            ).first()

        private data class DisambiguatedTarget(
            val targetId: String,
            val confidence: Double
        )

        private fun disambiguateSymbolTarget(
            sym: String,
            initialConf: Double,
            edge: GraphEdge,
            index: NodeLookupIndex,
            importsByFile: Map<String, Set<String>>
        ): DisambiguatedTarget? {
            val exact = index.byQName[sym]
            if (exact != null) {
                return DisambiguatedTarget(exact.nodeId, initialConf)
            }

            val shortSym = sym.substringAfterLast('.').substringAfterLast("::")
            val candidates = index.byName[shortSym]
            if (candidates.isNullOrEmpty()) return null
            if (candidates.size == 1) {
                return DisambiguatedTarget(candidates[0].nodeId, initialConf)
            }

            val srcNode = index.byNodeId[edge.sourceNodeId]
            val srcFileId = srcNode?.fileId ?: edge.sourceNodeId.substringBefore("#")
            val srcModId = srcNode?.moduleId.orEmpty()
            val fileImports = importsByFile[srcFileId].orEmpty()
            val ctx = DisambiguationContext(srcNode, srcFileId, srcModId, fileImports)

            val receiver = extractSymbolReceiver(sym)
            val receiverMatches = resolveReceiverMatches(candidates, receiver, srcFileId, srcNode)
            val candidatePool = if (receiverMatches.isNotEmpty()) receiverMatches else candidates
            val chosenPool = selectCandidatePool(candidatePool, receiverMatches, candidates, ctx)
            val chosen = pickBestCandidate(chosenPool)

            var conf = initialConf
            if (chosenPool.size > 1 && edge.provenance.startsWith("INFERRED_CALL")) {
                conf = minOf(conf, 0.75)
            }
            return DisambiguatedTarget(chosen.nodeId, conf)
        }

        fun resolveCrossFileEdges(
            projectId: String,
            nodes: List<GraphNode>,
            rawEdges: List<GraphEdge>
        ): List<GraphEdge> {
            val index = buildNodeLookupIndex(nodes, projectId)
            val importsByFile = buildImportsByFileMap(rawEdges, index.byNodeId)
            val resolved = mutableListOf<GraphEdge>()
            val seen = mutableSetOf<String>()

            for (edge in rawEdges) {
                val targetId: String
                val conf: Double
                if (edge.targetNodeId.startsWith("sym:")) {
                    val disambiguated = disambiguateSymbolTarget(
                        edge.targetNodeId.removePrefix("sym:"),
                        edge.confidence,
                        edge,
                        index,
                        importsByFile
                    ) ?: continue
                    targetId = disambiguated.targetId
                    conf = disambiguated.confidence
                } else {
                    targetId = edge.targetNodeId
                    conf = edge.confidence
                }

                if (targetId == edge.sourceNodeId) continue
                val dedupeKey = "${edge.edgeId}->$targetId"
                if (!seen.add(dedupeKey)) continue

                resolved.add(
                    GraphEdge(
                        edgeId = dedupeKey,
                        projectId = edge.projectId,
                        sourceNodeId = edge.sourceNodeId,
                        targetNodeId = targetId,
                        edgeType = edge.edgeType,
                        provenance = edge.provenance,
                        confidence = conf,
                        lineNumber = edge.lineNumber
                    )
                )
            }
            return resolved
        }
    }
}
