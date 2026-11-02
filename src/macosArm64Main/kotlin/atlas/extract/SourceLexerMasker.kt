package atlas.extract

/**
 * Polyglot comment and string literal masker preserving exact character length and newline (`\n`) offsets.
 */
internal object SourceLexerMasker {

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
        if (maskComment(state)) return
        if (ch == '"' || ch == '\'') {
            maskJsInterpolatedQuote(state, ch)
        } else if (ch == '`') {
            maskJsTemplateLiteral(state)
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

    private fun maskInterpolatedLiteralOrComment(state: MaskState): Boolean {
        if (maskComment(state)) return true
        if (maskTripleQuotedString(state)) return true
        if (maskDoubleQuotedString(state)) return true
        return maskSingleQuotedString(state)
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
        if (maskInterpolatedLiteralOrComment(state)) {
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
        if (maskInterpolatedLiteralOrComment(state)) {
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
}
