package atlas.extract

import atlas.model.ComplexityVector
import kotlin.math.ln

/**
 * Polyglot Function Complexity Engine calculating SonarSource Cognitive Complexity,
 * McCabe Cyclomatic Complexity, nesting depth, and SLOC across Kotlin, Python, Swift,
 * TypeScript, Rust, and C++.
 *
 * Enforces the Compose Filter Invariant: declarative Compose layout lambdas do not incur
 * algorithmic cognitive nesting penalties.
 */
object ComplexityExtractor {

    private val COMPOSE_CONTAINERS = setOf(
        "Column", "Row", "Box", "Surface", "Scaffold", "Card",
        "LazyColumn", "LazyRow", "LazyVerticalGrid", "LazyHorizontalGrid",
        "Spacer", "Item", "items", "ConstraintLayout", "FlowRow", "FlowColumn"
    )

    private val DECLARATION_PREFIX_KEYWORDS = setOf(
        "fun", "fn", "func", "function", "val", "var", "let", "const",
        "class", "interface", "object", "struct", "enum", "trait", "type", "typealias"
    )

    private val COMMON_LAMBDAS = setOf(
        "let", "apply", "run", "also", "with", "filter", "map", "forEach", "flatMap", "use"
    )

    private val LOGICAL_OP_RE = Regex("""&&|\|\|""")
    private val ELVIS_OP_RE = Regex("""\?:""")
    private val TERNARY_OP_RE = Regex("""(?<=\S)\s+\?\s+[^:?]+\s+:""")
    private val FN_KEYWORD_RE = Regex("""\b(?:fun|def|func|fn|constructor|function)\b""")

    fun calculate(
        maskedLines: List<String>,
        rawLines: List<String> = emptyList(),
        language: String = "kotlin",
        isComposable: Boolean = false,
        functionName: String? = null
    ): ComplexityVector {
        if (maskedLines.isEmpty()) {
            return ComplexityVector(cyclomatic = 1, cognitive = 0, maxNesting = 0, sloc = 0)
        }

        val lang = language.lowercase()
        return if (lang == "python" || lang == "py") {
            calculatePython(maskedLines, rawLines, functionName)
        } else {
            calculateBraceLanguage(maskedLines, rawLines, lang, isComposable, functionName)
        }
    }

    fun computeScore(vector: ComplexityVector): Double {
        val raw = 100.0 - (vector.cognitive * 3.0) - (maxOf(0, vector.cyclomatic - 1) * 2.0)
        return raw.coerceIn(0.0, 100.0)
    }

    fun computeGrade(score: Double): String {
        return when {
            score >= 90.0 -> "A"
            score >= 80.0 -> "B"
            score >= 70.0 -> "C"
            score >= 60.0 -> "D"
            else -> "F"
        }
    }

    fun computeRiskIndex(cognitive: Int, inDegree: Int): Double {
        return cognitive.toDouble() * (1.0 + ln(1.0 + inDegree.toDouble()))
    }

    private class BraceRunState {
        var cyclomatic = 1
        var cognitive = 0
        var maxNesting = 0
        var returnCount = 0

        val scopeStack = mutableListOf<ScopeType>()
        var pendingControl = false
        var pendingSwitchLike = false
        var lastIdentifier = ""
        var lastCallIdentifier = ""
        var prevWord = ""
        var parenDepth = 0

        fun currentNesting(): Int = scopeStack.count {
            it == ScopeType.CONTROL || it == ScopeType.SWITCH_LIKE || it == ScopeType.LAMBDA
        }

        fun updateNesting() {
            val nesting = currentNesting()
            if (nesting > maxNesting) maxNesting = nesting
        }

        fun openScope(scopeType: ScopeType) {
            scopeStack.add(scopeType)
            updateNesting()
            pendingControl = false
            pendingSwitchLike = false
            lastCallIdentifier = ""
        }

        fun closeScope() {
            if (scopeStack.isNotEmpty()) {
                scopeStack.removeAt(scopeStack.size - 1)
            }
        }
    }

    private fun calculateBraceLanguage(
        maskedLines: List<String>,
        rawLines: List<String>,
        language: String,
        isComposable: Boolean,
        functionName: String? = null
    ): ComplexityVector {
        val state = BraceRunState()

        for (line in maskedLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            tallyLineReturnsAndOperators(trimmed, language, state)
            scanBraceLineTokens(trimmed, language, isComposable, functionName, state)
            resetPendingControlIfSingleLine(trimmed, state)
        }

        val effectiveLines = if (rawLines.isNotEmpty()) rawLines else maskedLines
        return ComplexityVector(
            cyclomatic = state.cyclomatic,
            cognitive = state.cognitive,
            maxNesting = state.maxNesting,
            sloc = computeSloc(effectiveLines, language),
            paramCount = extractParamCount(effectiveLines, functionName, language),
            returnCount = state.returnCount
        )
    }

    private fun tallyLineReturnsAndOperators(
        trimmed: String,
        language: String,
        state: BraceRunState
    ) {
        val words = tokenizeWords(trimmed)
        for (w in words) {
            if (w == "return") state.returnCount++
        }

        state.cyclomatic += countRegexMatches(trimmed, LOGICAL_OP_RE)
        state.cognitive += countLogicalOperatorSequences(trimmed)

        if (language in listOf("kotlin", "swift")) {
            val elvisCount = countRegexMatches(trimmed, ELVIS_OP_RE)
            state.cyclomatic += elvisCount
            state.cognitive += elvisCount
        } else if (language in listOf("typescript", "javascript", "ts", "js", "cpp", "c")) {
            val ternaryCount = countRegexMatches(trimmed, TERNARY_OP_RE)
            state.cyclomatic += ternaryCount
            state.cognitive += ternaryCount
        }
    }

    private fun resolveOpeningScopeType(
        state: BraceRunState,
        isComposable: Boolean,
        functionName: String?
    ): ScopeType {
        val callTarget = if (state.lastCallIdentifier.isNotEmpty()) state.lastCallIdentifier else state.lastIdentifier
        val isRootScope = state.scopeStack.isEmpty() && !state.pendingControl && !state.pendingSwitchLike
        val isSelfDecl = functionName != null && callTarget == functionName
        return when {
            state.pendingSwitchLike -> ScopeType.SWITCH_LIKE
            state.pendingControl -> ScopeType.CONTROL
            isRootScope || isSelfDecl -> ScopeType.OTHER
            isComposable && callTarget in COMPOSE_CONTAINERS -> ScopeType.COMPOSE_CONTAINER
            !isComposable && callTarget in COMPOSE_CONTAINERS -> ScopeType.LAMBDA
            callTarget in COMMON_LAMBDAS -> ScopeType.LAMBDA
            else -> ScopeType.OTHER
        }
    }

    private fun handleIfKeyword(state: BraceRunState, nesting: Int) {
        val isElseIf = state.prevWord == "else"
        state.cyclomatic++
        if (isElseIf) {
            state.cognitive += 1
        } else {
            state.cognitive += (1 + nesting)
        }
        state.pendingControl = true
    }

    private fun handleElseKeyword(trimmed: String, wordEndIndex: Int, state: BraceRunState) {
        val remainder = trimmed.substring(wordEndIndex).trimStart()
        if (!remainder.startsWith("if")) {
            state.cognitive += 1
            state.pendingControl = true
        }
    }

    private fun handleBreakContinueKeyword(trimmed: String, wordEndIndex: Int, state: BraceRunState) {
        val remainder = trimmed.substring(wordEndIndex).trimStart()
        if (remainder.startsWith("@") || remainder.takeWhile { it.isLetter() }.isNotEmpty()) {
            state.cognitive += 1
        }
    }

    private fun isSwitchLikeKeyword(word: String, language: String): Boolean = when (word) {
        "when" -> language == "kotlin" || language == "kt"
        "match" -> language == "rust" || language == "rs"
        "switch" -> language !in listOf("kotlin", "kt", "rust", "rs")
        else -> false
    }

    private fun isCaseKeywordForLanguage(language: String): Boolean =
        language !in listOf("kotlin", "kt", "rust", "rs")

    private fun isNonControlIdentifierPosition(trimmed: String, startIndex: Int, prevWord: String): Boolean {
        val prevChar = if (startIndex > 0) trimmed[startIndex - 1] else ' '
        return prevChar == '.' || prevChar == ':' || prevWord in DECLARATION_PREFIX_KEYWORDS
    }

    private fun handleSwitchLikeKeyword(
        word: String,
        language: String,
        nesting: Int,
        state: BraceRunState
    ) {
        if (!isSwitchLikeKeyword(word, language)) return
        state.cyclomatic++
        state.cognitive += (1 + nesting)
        state.pendingControl = true
        state.pendingSwitchLike = true
    }

    private fun handleKeywordToken(
        word: String,
        trimmed: String,
        wordEndIndex: Int,
        language: String,
        state: BraceRunState
    ) {
        val nesting = state.currentNesting()
        when (word) {
            "if" -> handleIfKeyword(state, nesting)
            "else" -> handleElseKeyword(trimmed, wordEndIndex, state)
            "when", "switch", "match" -> handleSwitchLikeKeyword(word, language, nesting, state)
            "for", "while", "do", "catch" -> {
                state.cyclomatic++
                state.cognitive += (1 + nesting)
                state.pendingControl = true
            }
            "case" -> if (isCaseKeywordForLanguage(language)) state.cyclomatic++
            "break", "continue" -> handleBreakContinueKeyword(trimmed, wordEndIndex, state)
        }
    }

    private fun scanWordToken(
        trimmed: String,
        startIndex: Int,
        language: String,
        state: BraceRunState
    ): Int {
        var end = startIndex
        while (end < trimmed.length && (trimmed[end].isLetterOrDigit() || trimmed[end] == '_')) {
            end++
        }
        val word = trimmed.substring(startIndex, end)
        if (!isNonControlIdentifierPosition(trimmed, startIndex, state.prevWord)) {
            handleKeywordToken(word, trimmed, end, language, state)
        }
        state.prevWord = word
        state.lastIdentifier = word
        return end
    }

    private fun checkKotlinArrow(trimmed: String, index: Int, language: String, state: BraceRunState): Boolean {
        if (index + 1 >= trimmed.length || trimmed[index] != '-' || trimmed[index + 1] != '>') return false
        val inKotlinWhen = (language in listOf("kotlin", "kt")) &&
            state.scopeStack.lastOrNull() == ScopeType.SWITCH_LIKE &&
            state.parenDepth == 0
        if (inKotlinWhen) {
            val beforeArrow = trimmed.substring(0, index).trim()
            if (!beforeArrow.endsWith("else")) {
                state.cyclomatic++
            }
        }
        return true
    }

    private fun checkRustFatArrow(trimmed: String, index: Int, language: String, state: BraceRunState): Boolean {
        if (index + 1 >= trimmed.length || trimmed[index] != '=' || trimmed[index + 1] != '>') return false
        val inRustMatch = (language in listOf("rust", "rs")) &&
            state.scopeStack.lastOrNull() == ScopeType.SWITCH_LIKE &&
            state.parenDepth == 0
        if (inRustMatch) {
            val beforeArrow = trimmed.substring(0, index).trim()
            if (!beforeArrow.endsWith("_")) {
                state.cyclomatic++
            }
        }
        return true
    }

    private fun scanBraceLineTokens(
        trimmed: String,
        language: String,
        isComposable: Boolean,
        functionName: String?,
        state: BraceRunState
    ) {
        var i = 0
        while (i < trimmed.length) {
            val c = trimmed[i]
            when {
                c == '(' -> {
                    state.parenDepth++
                    state.lastCallIdentifier = state.lastIdentifier
                    i++
                }
                c == ')' -> {
                    if (state.parenDepth > 0) state.parenDepth--
                    i++
                }
                c == '{' -> {
                    val scopeType = resolveOpeningScopeType(state, isComposable, functionName)
                    state.openScope(scopeType)
                    i++
                }
                c == '}' -> {
                    state.closeScope()
                    i++
                }
                c.isLetter() || c == '_' -> {
                    i = scanWordToken(trimmed, i, language, state)
                }
                checkKotlinArrow(trimmed, i, language, state) -> {
                    i += 2
                }
                checkRustFatArrow(trimmed, i, language, state) -> {
                    i += 2
                }
                else -> i++
            }
        }
    }

    private fun resetPendingControlIfSingleLine(trimmed: String, state: BraceRunState) {
        if (!state.pendingControl && !state.pendingSwitchLike) return
        if (trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("&&") || trimmed.endsWith("||")) return
        if (trimmed.contains("return") || trimmed.contains("=") || trimmed.endsWith(";")) {
            state.pendingControl = false
            state.pendingSwitchLike = false
        }
    }

    private class PythonRunState {
        var cyclomatic = 1
        var cognitive = 0
        var maxNesting = 0
        var returnCount = 0

        fun updateNesting(nesting: Int) {
            if (nesting > maxNesting) maxNesting = nesting
        }
    }

    private fun calculatePython(
        maskedLines: List<String>,
        rawLines: List<String>,
        functionName: String? = null
    ): ComplexityVector {
        val state = PythonRunState()
        val indentStack = mutableListOf<Pair<Int, Boolean>>() // (indentation, isControl)

        for (line in maskedLines) {
            processPythonLine(line, indentStack, state)
        }

        val effectiveLines = if (rawLines.isNotEmpty()) rawLines else maskedLines
        return ComplexityVector(
            cyclomatic = state.cyclomatic,
            cognitive = state.cognitive,
            maxNesting = state.maxNesting,
            sloc = computeSloc(effectiveLines, "python"),
            paramCount = extractParamCount(effectiveLines, functionName, "python"),
            returnCount = state.returnCount
        )
    }

    private fun processPythonLine(
        line: String,
        indentStack: MutableList<Pair<Int, Boolean>>,
        state: PythonRunState
    ) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return

        val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        popInactivePythonIndent(indentStack, indent)

        val currentNesting = indentStack.count { it.second }
        state.updateNesting(currentNesting)

        val words = tokenizeWords(trimmed)
        val (returns, ops) = tallyPythonTokens(words)
        state.returnCount += returns
        state.cyclomatic += ops
        state.cognitive += ops

        val firstWord = words.firstOrNull() ?: ""
        val (cycDelta, cogDelta, openedControl) = evalPythonControlKeyword(firstWord, words, currentNesting)
        state.cyclomatic += cycDelta
        state.cognitive += cogDelta

        if (trimmed.endsWith(":")) {
            indentStack.add(Pair(indent, openedControl))
            state.updateNesting(indentStack.count { it.second })
        }
    }

    private fun popInactivePythonIndent(indentStack: MutableList<Pair<Int, Boolean>>, indent: Int) {
        while (indentStack.isNotEmpty() && indentStack.last().first >= indent) {
            indentStack.removeAt(indentStack.size - 1)
        }
    }

    private fun tallyPythonTokens(words: List<String>): Pair<Int, Int> {
        var returns = 0
        var ops = 0
        for (w in words) {
            if (w == "return") returns++
            if (w == "and" || w == "or") ops++
        }
        return Pair(returns, ops)
    }

    private data class PythonKeywordComplexity(
        val cyclomaticDelta: Int,
        val cognitiveDelta: Int,
        val openedControl: Boolean
    )

    private fun evalPythonControlKeyword(
        firstWord: String,
        words: List<String>,
        currentNesting: Int
    ): PythonKeywordComplexity {
        return when (firstWord) {
            "if" -> PythonKeywordComplexity(1, 1 + currentNesting, true)
            "elif" -> PythonKeywordComplexity(1, 1, true)
            "else" -> PythonKeywordComplexity(0, 1, true)
            "for", "while" -> PythonKeywordComplexity(1, 1 + currentNesting, true)
            "except" -> PythonKeywordComplexity(1, 1 + currentNesting, true)
            "match" -> PythonKeywordComplexity(1, 1 + currentNesting, true)
            "case" -> {
                val cyc = if (words.getOrNull(1) != "_") 1 else 0
                PythonKeywordComplexity(cyc, 0, true)
            }
            else -> PythonKeywordComplexity(0, 0, false)
        }
    }

    private fun countLogicalOperatorSequences(line: String): Int {
        var count = 0
        var currentOp = ""
        var i = 0
        while (i < line.length - 1) {
            val op = readLogicalOp(line, i)
            if (op != null) {
                if (currentOp != op) {
                    count++
                    currentOp = op
                }
                i += 2
            } else {
                i++
            }
        }
        return count
    }

    private fun readLogicalOp(line: String, i: Int): String? {
        if (line[i] == '&' && line[i + 1] == '&') return "&&"
        if (line[i] == '|' && line[i + 1] == '|') return "||"
        return null
    }

    private fun countRegexMatches(text: String, regex: Regex): Int {
        return regex.findAll(text).count()
    }

    private fun tokenizeWords(line: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            if (line[i].isLetter() || line[i] == '_') {
                val start = i
                while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) {
                    i++
                }
                result.add(line.substring(start, i))
            } else {
                i++
            }
        }
        return result
    }

    private fun computeSloc(lines: List<String>, language: String): Int {
        val lang = language.lowercase()
        return lines.count { line ->
            val t = line.trim()
            if (t.isEmpty()) return@count false
            if (lang == "python" || lang == "py") {
                !t.startsWith("#")
            } else {
                !t.startsWith("//") && !t.startsWith("/*") && !t.startsWith("*")
            }
        }
    }

    fun extractParamCount(
        lines: List<String>,
        functionName: String? = null,
        language: String = "kotlin"
    ): Int {
        val paramContent = extractParamContent(lines, functionName, language) ?: return 0
        if (paramContent.trim().isEmpty()) return 0
        return countParameters(paramContent, language)
    }

    fun extractParamContent(
        lines: List<String>,
        functionName: String? = null,
        language: String = "kotlin"
    ): String? {
        if (lines.isEmpty()) return null
        val header = collectHeaderSnippet(lines, functionName, language)
        if (header.isEmpty()) return null
        val openParen = findParameterListOpenParen(header, functionName, language)
        if (openParen < 0) return null
        val closeParen = findMatchingClosingParen(header, openParen)
        if (closeParen <= openParen) return null
        return header.substring(openParen + 1, closeParen).trim()
    }

    private fun skipTripleQuote(s: String, startIndex: Int): Int {
        var i = startIndex + 3
        while (i + 2 < s.length) {
            if (s[i] == '\\') {
                i += 2
            } else if (s[i] == '"' && s[i + 1] == '"' && s[i + 2] == '"') {
                return i + 3
            } else {
                i++
            }
        }
        return s.length
    }

    private fun skipQuotedLiteral(s: String, startIndex: Int): Int {
        if (s.startsWith("\"\"\"", startIndex)) {
            return skipTripleQuote(s, startIndex)
        }
        val quote = s[startIndex]
        var i = startIndex + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i += 2
            } else if (c == quote) {
                return i + 1
            } else {
                i++
            }
        }
        return s.length
    }

    private fun findCommentPrefixIndex(line: String, prefix: String): Int {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"' || c == '\'') {
                i = skipQuotedLiteral(line, i)
                continue
            }
            if (line.startsWith(prefix, i)) {
                return i
            }
            i++
        }
        return -1
    }

    private fun stripBlockComments(line: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"' || c == '\'') {
                val end = skipQuotedLiteral(line, i)
                sb.append(line, i, end)
                i = end
                continue
            }
            if (line.startsWith("/*", i)) {
                val end = line.indexOf("*/", i + 2)
                if (end >= 0) {
                    sb.append(' ')
                    i = end + 2
                    continue
                } else {
                    break
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun stripComments(line: String, language: String): String {
        val lang = language.lowercase()
        val isPy = lang == "python" || lang == "py"
        val lineCommentPrefix = if (isPy) "#" else "//"
        val idx = findCommentPrefixIndex(line, lineCommentPrefix)
        val withoutLineComment = if (idx >= 0) line.substring(0, idx) else line
        val blockClean = if (!isPy && withoutLineComment.contains("/*")) {
            stripBlockComments(withoutLineComment)
        } else {
            withoutLineComment
        }
        return blockClean.trim()
    }

    private fun stripStringLiterals(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '"' || c == '\'') {
                val end = skipQuotedLiteral(s, i)
                i = end
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun findDeclStartLine(lines: List<String>, functionName: String?, language: String): Int {
        if (functionName.isNullOrBlank()) return 0
        val fnRegex = Regex("""(?:\b|\.)${Regex.escape(functionName)}\b""")
        for (i in lines.indices) {
            val clean = stripComments(lines[i], language)
            val codeOnly = stripStringLiterals(clean)
            if (fnRegex.containsMatchIn(codeOnly)) return i
        }
        return 0
    }

    private fun updateLineParens(line: String, currentDepth: Int): Pair<Int, Boolean> {
        var depth = currentDepth
        var sawOpen = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"' || c == '\'') {
                i = skipQuotedLiteral(line, i)
                continue
            }
            if (c == '(') {
                depth++
                sawOpen = true
            } else if (c == ')') {
                depth = maxOf(0, depth - 1)
            }
            i++
        }
        return Pair(depth, sawOpen)
    }

    private fun isHeaderTerminator(clean: String, isPy: Boolean): Boolean {
        if (clean.contains('{') || clean.contains(';') || clean.contains('=')) return true
        return isPy && clean.endsWith(":")
    }

    private fun shouldStopHeader(clean: String, foundOpen: Boolean, depth: Int, isPy: Boolean): Boolean {
        if (!foundOpen || depth != 0) return false
        return clean.contains(')') || isHeaderTerminator(clean, isPy)
    }

    private fun collectHeaderSnippet(
        lines: List<String>,
        functionName: String?,
        language: String
    ): String {
        val startIdx = findDeclStartLine(lines, functionName, language)
        val headerSb = StringBuilder()
        var parenDepth = 0
        var foundOpen = false
        val isPy = language.equals("python", ignoreCase = true) || language.equals("py", ignoreCase = true)
        val maxLines = minOf(lines.size, startIdx + 30)

        for (i in startIdx until maxLines) {
            val clean = stripComments(lines[i], language)
            if (clean.isEmpty()) continue
            headerSb.append(clean).append(' ')

            val (newDepth, sawOpen) = updateLineParens(clean, parenDepth)
            parenDepth = newDepth
            if (sawOpen) foundOpen = true
            if (shouldStopHeader(clean, foundOpen, parenDepth, isPy)) break
        }
        return headerSb.toString()
    }

    private fun skipWhitespace(s: String, startIndex: Int): Int {
        var pos = startIndex
        while (pos < s.length && s[pos].isWhitespace()) pos++
        return pos
    }

    private fun skipAngleBrackets(s: String, startIndex: Int): Int {
        if (startIndex >= s.length || s[startIndex] != '<') return startIndex
        var depth = 0
        var pos = startIndex
        while (pos < s.length) {
            val c = s[pos]
            if (c == '<') {
                depth++
            } else if (c == '>') {
                depth--
                if (depth == 0) return pos + 1
            }
            pos++
        }
        return pos
    }

    private fun findOpenParenAfterName(header: String, functionName: String): Int {
        val fnRegex = Regex("""(?:\b|\.)""" + Regex.escape(functionName) + """\b""")
        for (match in fnRegex.findAll(header)) {
            val afterName = match.range.last + 1
            val afterWs = skipWhitespace(header, afterName)
            val afterGenerics = skipAngleBrackets(header, afterWs)
            val finalPos = skipWhitespace(header, afterGenerics)
            if (finalPos < header.length && header[finalPos] == '(') return finalPos
        }
        return -1
    }

    private fun checkReceiverParen(header: String, openParen: Int): Int {
        val closeParen = findMatchingClosingParen(header, openParen)
        if (closeParen <= openParen) return -1
        val nextNonWs = skipWhitespace(header, closeParen + 1)
        return if (nextNonWs < header.length && header[nextNonWs] == '.') closeParen + 1 else -1
    }

    private fun scanHeaderForOpenParen(header: String, searchStart: Int): Int {
        var angleDepth = 0
        var i = searchStart
        while (i < header.length) {
            val c = header[i]
            if (c == '<') {
                angleDepth++
            } else if (c == '>') {
                angleDepth = maxOf(0, angleDepth - 1)
            } else if (c == '(' && angleDepth == 0) {
                val skipTo = checkReceiverParen(header, i)
                if (skipTo > i) {
                    i = skipTo
                    continue
                }
                return i
            }
            i++
        }
        return -1
    }

    private fun findParameterListOpenParen(
        header: String,
        functionName: String?,
        language: String
    ): Int {
        if (!functionName.isNullOrBlank()) {
            val namedParen = findOpenParenAfterName(header, functionName)
            if (namedParen >= 0) return namedParen
        }

        val kwMatch = FN_KEYWORD_RE.find(header)
        val searchStart = if (kwMatch != null) kwMatch.range.last + 1 else 0

        val scannedParen = scanHeaderForOpenParen(header, searchStart)
        return if (scannedParen >= 0) scannedParen else header.indexOf('(')
    }

    private fun findMatchingClosingParen(header: String, openParen: Int): Int {
        var parenDepth = 0
        var i = openParen
        while (i < header.length) {
            val c = header[i]
            if (c == '"' || c == '\'') {
                i = skipQuotedLiteral(header, i)
                continue
            }
            if (c == '(') {
                parenDepth++
            } else if (c == ')') {
                parenDepth--
                if (parenDepth == 0) return i
            }
            i++
        }
        return -1
    }

    private class ParamBracketTracker {
        var paren = 0
        var angle = 0
        var square = 0
        var curly = 0

        val isTopLevel: Boolean
            get() = paren == 0 && angle == 0 && square == 0 && curly == 0

        fun consume(c: Char) {
            when (c) {
                '(' -> paren++
                ')' -> paren = maxOf(0, paren - 1)
                '<' -> angle++
                '>' -> angle = maxOf(0, angle - 1)
                '[' -> square++
                ']' -> square = maxOf(0, square - 1)
                '{' -> curly++
                '}' -> curly = maxOf(0, curly - 1)
            }
        }
    }

    private fun addChunkIfNonEmpty(chunks: MutableList<String>, sb: StringBuilder) {
        val s = sb.toString().trim()
        if (s.isNotEmpty()) chunks.add(s)
        sb.clear()
    }

    private fun resolveTwoCharSymbol(c: Char, next: Char): String? = when {
        c == '-' && next == '>' -> "->"
        c == '=' && next == '>' -> "=>"
        c == '>' && next == '=' -> ">="
        c == '<' && (next == '=' || next == ' ') -> "<"
        else -> null
    }

    private fun tryConsumeMultiCharToken(s: String, i: Int, out: StringBuilder): Int {
        val c = s[i]
        if (c == '"' || c == '\'') {
            val end = skipQuotedLiteral(s, i)
            out.append(s, i, end)
            return end
        }
        if (i + 1 < s.length) {
            val sym = resolveTwoCharSymbol(c, s[i + 1])
            if (sym != null) {
                out.append(sym)
                return if (sym == "<") i + 1 else i + 2
            }
        }
        return -1
    }

    private fun splitParamChunks(trimmed: String): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        val tracker = ParamBracketTracker()
        var i = 0

        while (i < trimmed.length) {
            val nextIdx = tryConsumeMultiCharToken(trimmed, i, current)
            if (nextIdx > i) {
                i = nextIdx
                continue
            }
            val c = trimmed[i]
            if (c == ',' && tracker.isTopLevel) {
                addChunkIfNonEmpty(chunks, current)
                i++
                continue
            }
            tracker.consume(c)
            current.append(c)
            i++
        }
        addChunkIfNonEmpty(chunks, current)
        return chunks
    }

    private fun adjustPythonChunks(chunks: List<String>): Int {
        if (chunks.isEmpty()) return 0
        val firstWord = chunks.first().takeWhile { it.isLetterOrDigit() || it == '_' }
        return if (firstWord == "self" || firstWord == "cls") chunks.size - 1 else chunks.size
    }

    fun countParameters(paramContent: String, language: String = "kotlin"): Int {
        val trimmed = paramContent.trim()
        if (trimmed.isEmpty()) return 0
        val chunks = splitParamChunks(trimmed)
        val isPy = language.equals("python", ignoreCase = true) || language.equals("py", ignoreCase = true)
        return if (isPy) adjustPythonChunks(chunks) else chunks.size
    }

    private enum class ScopeType {
        COMPOSE_CONTAINER,
        CONTROL,
        SWITCH_LIKE,
        LAMBDA,
        OTHER
    }
}
