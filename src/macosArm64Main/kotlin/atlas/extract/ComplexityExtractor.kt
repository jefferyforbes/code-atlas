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

    private val CONTROL_KEYWORDS = setOf(
        "if", "when", "switch", "for", "while", "do", "catch", "except", "match"
    )

    private val COMMON_LAMBDAS = setOf(
        "let", "apply", "run", "also", "with", "filter", "map", "forEach", "flatMap", "use"
    )

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
            calculatePython(maskedLines, rawLines)
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
            paramCount = extractParamCount(effectiveLines),
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

        state.cyclomatic += countRegexMatches(trimmed, Regex("""&&|\|\|"""))
        state.cognitive += countLogicalOperatorSequences(trimmed)

        if (language in listOf("kotlin", "swift")) {
            val elvisCount = countRegexMatches(trimmed, Regex("""\?:"""))
            state.cyclomatic += elvisCount
            state.cognitive += elvisCount
        } else if (language in listOf("typescript", "javascript", "ts", "js", "cpp", "c")) {
            val ternaryCount = countRegexMatches(trimmed, Regex("""(?<=\S)\s+\?\s+[^:?]+\s+:"""))
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

    private fun handleKeywordToken(
        word: String,
        trimmed: String,
        wordEndIndex: Int,
        state: BraceRunState
    ) {
        val nesting = state.currentNesting()
        when (word) {
            "if" -> handleIfKeyword(state, nesting)
            "else" -> handleElseKeyword(trimmed, wordEndIndex, state)
            "when", "switch", "match" -> {
                state.cyclomatic++
                state.cognitive += (1 + nesting)
                state.pendingControl = true
                state.pendingSwitchLike = true
            }
            "for", "while", "do", "catch" -> {
                state.cyclomatic++
                state.cognitive += (1 + nesting)
                state.pendingControl = true
            }
            "case" -> state.cyclomatic++
            "break", "continue" -> handleBreakContinueKeyword(trimmed, wordEndIndex, state)
        }
        state.prevWord = word
        state.lastIdentifier = word
    }

    private fun scanWordToken(
        trimmed: String,
        startIndex: Int,
        state: BraceRunState
    ): Int {
        var end = startIndex
        while (end < trimmed.length && (trimmed[end].isLetterOrDigit() || trimmed[end] == '_')) {
            end++
        }
        val word = trimmed.substring(startIndex, end)
        handleKeywordToken(word, trimmed, end, state)
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
                    i = scanWordToken(trimmed, i, state)
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
        rawLines: List<String>
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
            paramCount = extractParamCount(effectiveLines),
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

    private fun extractParamCount(lines: List<String>): Int {
        val paramContent = extractParamContent(lines) ?: return 0
        if (paramContent.isEmpty()) return 0
        return countTopLevelCommas(paramContent) + 1
    }

    private fun extractParamContent(lines: List<String>): String? {
        if (lines.isEmpty()) return null
        val header = collectHeaderSnippet(lines)
        val openParen = header.indexOf('(')
        val closeParen = header.lastIndexOf(')')
        if (openParen == -1 || closeParen <= openParen) return null
        return header.substring(openParen + 1, closeParen).trim()
    }

    private fun collectHeaderSnippet(lines: List<String>): String {
        val headerSb = StringBuilder()
        var parenDepth = 0
        var foundOpen = false
        for (line in lines) {
            headerSb.append(line).append(' ')
            val (depth, opened) = scanLineParens(line, parenDepth, foundOpen)
            parenDepth = depth
            foundOpen = opened
            if (foundOpen && parenDepth <= 0) break
            if (headerSb.length > 500) break
        }
        return headerSb.toString()
    }

    private fun scanLineParens(line: String, startDepth: Int, startOpened: Boolean): Pair<Int, Boolean> {
        var depth = startDepth
        var opened = startOpened
        for (c in line) {
            if (c == '(') {
                depth++
                opened = true
            } else if (c == ')') {
                depth--
            }
        }
        return Pair(depth, opened)
    }

    private fun countTopLevelCommas(paramContent: String): Int {
        var depth = 0
        var commas = 0
        for (c in paramContent) {
            when (c) {
                '(', '<', '[', '{' -> depth++
                ')', '>', ']', '}' -> depth--
                ',' -> if (depth == 0) commas++
            }
        }
        return commas
    }

    private enum class ScopeType {
        COMPOSE_CONTAINER,
        CONTROL,
        SWITCH_LIKE,
        LAMBDA,
        OTHER
    }
}
