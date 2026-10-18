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

    private fun calculateBraceLanguage(
        maskedLines: List<String>,
        rawLines: List<String>,
        language: String,
        isComposable: Boolean,
        functionName: String? = null
    ): ComplexityVector {
        var cyclomatic = 1
        var cognitive = 0
        var maxNesting = 0
        var returnCount = 0

        val scopeStack = mutableListOf<ScopeType>()
        var pendingControl = false
        var lastIdentifier = ""
        var lastCallIdentifier = ""
        var prevWord = ""

        for (line in maskedLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Count returns
            val words = tokenizeWords(trimmed)
            for (w in words) {
                if (w == "return") returnCount++
            }

            // Count binary logical operators
            cyclomatic += countRegexMatches(trimmed, Regex("""&&|\|\|"""))
            cognitive += countLogicalOperatorSequences(trimmed)

            // Elvis in Kotlin/Swift or ternary in TS/C++
            if (language in listOf("kotlin", "swift")) {
                val elvisCount = countRegexMatches(trimmed, Regex("""\?:"""))
                cyclomatic += elvisCount
                cognitive += elvisCount
            } else if (language in listOf("typescript", "javascript", "ts", "js", "cpp", "c")) {
                val ternaryCount = countRegexMatches(trimmed, Regex("""\?[^:]+:"""))
                cyclomatic += ternaryCount
                cognitive += ternaryCount
            }

            // Character-by-character scan for tokens, braces, and keywords
            var i = 0
            while (i < trimmed.length) {
                val c = trimmed[i]

                if (c == '(') {
                    lastCallIdentifier = lastIdentifier
                    i++
                    continue
                }

                if (c == '{') {
                    val callTarget = if (lastCallIdentifier.isNotEmpty()) lastCallIdentifier else lastIdentifier
                    val isRootScope = scopeStack.isEmpty() && !pendingControl
                    val isSelfDecl = functionName != null && callTarget == functionName
                    val scopeType = when {
                        pendingControl -> ScopeType.CONTROL
                        isRootScope || isSelfDecl -> ScopeType.OTHER
                        isComposable && callTarget in COMPOSE_CONTAINERS -> ScopeType.COMPOSE_CONTAINER
                        !isComposable && callTarget in COMPOSE_CONTAINERS -> ScopeType.LAMBDA
                        callTarget in COMMON_LAMBDAS -> ScopeType.LAMBDA
                        else -> ScopeType.OTHER
                    }
                    scopeStack.add(scopeType)
                    val activeCognitiveNesting = scopeStack.count { it == ScopeType.CONTROL || it == ScopeType.LAMBDA }
                    if (activeCognitiveNesting > maxNesting) {
                        maxNesting = activeCognitiveNesting
                    }
                    pendingControl = false
                    lastCallIdentifier = ""
                    i++
                    continue
                } else if (c == '}') {
                    if (scopeStack.isNotEmpty()) {
                        scopeStack.removeAt(scopeStack.size - 1)
                    }
                    i++
                    continue
                }

                // Check for words
                if (c.isLetter() || c == '_') {
                    val start = i
                    while (i < trimmed.length && (trimmed[i].isLetterOrDigit() || trimmed[i] == '_')) {
                        i++
                    }
                    val word = trimmed.substring(start, i)
                    val currentNesting = scopeStack.count { it == ScopeType.CONTROL || it == ScopeType.LAMBDA }

                    when (word) {
                        "if" -> {
                            val isElseIf = prevWord == "else"
                            cyclomatic++
                            if (isElseIf) {
                                cognitive += 1 // else if increment without nesting penalty
                            } else {
                                cognitive += (1 + currentNesting)
                            }
                            pendingControl = true
                        }
                        "else" -> {
                            val remainder = trimmed.substring(i).trimStart()
                            if (!remainder.startsWith("if")) {
                                cognitive += 1 // else structural increment
                                pendingControl = true
                            }
                        }
                        "when", "switch" -> {
                            cyclomatic++
                            cognitive += (1 + currentNesting)
                            pendingControl = true
                        }
                        "for", "while", "do" -> {
                            cyclomatic++
                            cognitive += (1 + currentNesting)
                            pendingControl = true
                        }
                        "catch" -> {
                            cyclomatic++
                            cognitive += (1 + currentNesting)
                            pendingControl = true
                        }
                        "case" -> {
                            cyclomatic++
                        }
                        "break", "continue" -> {
                            val remainder = trimmed.substring(i).trimStart()
                            if (remainder.startsWith("@") || remainder.takeWhile { it.isLetter() }.isNotEmpty()) {
                                cognitive += 1
                            }
                        }
                    }
                    prevWord = word
                    lastIdentifier = word
                    continue
                }

                // Arrow branch in Kotlin when
                if (c == '-' && i + 1 < trimmed.length && trimmed[i + 1] == '>') {
                    val beforeArrow = trimmed.substring(0, i).trim()
                    if (!beforeArrow.endsWith("else")) {
                        cyclomatic++
                    }
                    i += 2
                    continue
                }

                i++
            }

            // Reset pending control if line finishes a single-line statement without opening a brace
            if (pendingControl && !trimmed.endsWith("{") && !trimmed.endsWith("(") && !trimmed.endsWith("&&") && !trimmed.endsWith("||")) {
                // If it was a single line statement (e.g. if (x) return 1), control does not persist to next line
                if (trimmed.contains("return") || trimmed.contains("=") || trimmed.endsWith(";")) {
                    pendingControl = false
                }
            }
        }

        val sloc = computeSloc(if (rawLines.isNotEmpty()) rawLines else maskedLines, language)
        val paramCount = extractParamCount(if (rawLines.isNotEmpty()) rawLines.firstOrNull() ?: "" else maskedLines.firstOrNull() ?: "")

        return ComplexityVector(
            cyclomatic = cyclomatic,
            cognitive = cognitive,
            maxNesting = maxNesting,
            sloc = sloc,
            paramCount = paramCount,
            returnCount = returnCount
        )
    }

    private fun calculatePython(
        maskedLines: List<String>,
        rawLines: List<String>
    ): ComplexityVector {
        var cyclomatic = 1
        var cognitive = 0
        var maxNesting = 0
        var returnCount = 0

        val indentStack = mutableListOf<Pair<Int, Boolean>>() // (indentation, isControl)

        for (line in maskedLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)

            // Pop scopes where indentation is no longer active
            while (indentStack.isNotEmpty() && indentStack.last().first >= indent) {
                indentStack.removeAt(indentStack.size - 1)
            }

            val currentNesting = indentStack.count { it.second }
            if (currentNesting > maxNesting) {
                maxNesting = currentNesting
            }

            val words = tokenizeWords(trimmed)
            for (w in words) {
                if (w == "return") returnCount++
            }

            // Logical operators and, or
            for (w in words) {
                if (w == "and" || w == "or") {
                    cyclomatic++
                    cognitive++
                }
            }

            val firstWord = words.firstOrNull() ?: ""
            var openedControl = false

            when (firstWord) {
                "if" -> {
                    cyclomatic++
                    cognitive += (1 + currentNesting)
                    openedControl = true
                }
                "elif" -> {
                    cyclomatic++
                    cognitive += 1
                    openedControl = true
                }
                "else" -> {
                    cognitive += 1
                    openedControl = true
                }
                "for", "while" -> {
                    cyclomatic++
                    cognitive += (1 + currentNesting)
                    openedControl = true
                }
                "except" -> {
                    cyclomatic++
                    cognitive += (1 + currentNesting)
                    openedControl = true
                }
                "match" -> {
                    cyclomatic++
                    cognitive += (1 + currentNesting)
                    openedControl = true
                }
                "case" -> {
                    if (words.getOrNull(1) != "_") {
                        cyclomatic++
                    }
                    openedControl = true
                }
            }

            if (trimmed.endsWith(":")) {
                indentStack.add(Pair(indent, openedControl))
                val newNesting = indentStack.count { it.second }
                if (newNesting > maxNesting) {
                    maxNesting = newNesting
                }
            }
        }

        val sloc = computeSloc(if (rawLines.isNotEmpty()) rawLines else maskedLines, "python")
        val paramCount = extractParamCount(if (rawLines.isNotEmpty()) rawLines.firstOrNull() ?: "" else maskedLines.firstOrNull() ?: "")

        return ComplexityVector(
            cyclomatic = cyclomatic,
            cognitive = cognitive,
            maxNesting = maxNesting,
            sloc = sloc,
            paramCount = paramCount,
            returnCount = returnCount
        )
    }

    private fun countLogicalOperatorSequences(line: String): Int {
        var count = 0
        var inAnd = false
        var inOr = false
        var i = 0
        while (i < line.length - 1) {
            if (line[i] == '&' && line[i + 1] == '&') {
                if (!inAnd) {
                    count++
                    inAnd = true
                    inOr = false
                }
                i += 2
                continue
            }
            if (line[i] == '|' && line[i + 1] == '|') {
                if (!inOr) {
                    count++
                    inOr = true
                    inAnd = false
                }
                i += 2
                continue
            }
            i++
        }
        return count
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

    private fun extractParamCount(headerLine: String): Int {
        val openParen = headerLine.indexOf('(')
        val closeParen = headerLine.lastIndexOf(')')
        if (openParen == -1 || closeParen <= openParen) return 0
        val paramContent = headerLine.substring(openParen + 1, closeParen).trim()
        if (paramContent.isEmpty()) return 0

        var depth = 0
        var commas = 0
        for (c in paramContent) {
            when (c) {
                '(', '<', '[', '{' -> depth++
                ')', '>', ']', '}' -> depth--
                ',' -> if (depth == 0) commas++
            }
        }
        return commas + 1
    }

    private enum class ScopeType {
        COMPOSE_CONTAINER,
        CONTROL,
        LAMBDA,
        OTHER
    }
}
