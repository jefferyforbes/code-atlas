package atlas.extract

import atlas.model.GraphEdge
import atlas.model.GraphNode

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
        private val KT_IMP_RE = Regex("""^\s*import\s+([a-zA-Z0-9_.]+)""", RegexOption.MULTILINE)
        private val KT_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?<annotations>(?:@[A-Za-z0-9_]+(?:\((?:[^()]|\([^()]*\))*\))?\s*)*)""" +
            """(?<modifiers>(?:(?:public|internal|private|protected|open|abstract|sealed|data|value|inline|suspend|expect|actual|override|companion|fun)\s+)*)""" +
            """(?<kw>class|interface|object|fun|typealias)(?:\s+(?:[A-Za-z0-9_<>?,.\t ]+\.)?(?<name>[A-Za-z0-9_]+))?""",
            RegexOption.MULTILINE
        )

        private val SWIFT_IMP_RE = Regex("""^\s*(?:@\w+\s+)?(?:public\s+|internal\s+)?import\s+([a-zA-Z0-9_.]+)""", RegexOption.MULTILINE)
        private val SWIFT_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:(?:public|private|fileprivate|internal|open|final|actor|indirect|nonisolated|isolated)\s+)*""" +
            """(?<kw>struct|class|protocol|actor|enum|func|extension)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )

        private val TS_IMP_RE = Regex("""import\s+(?:type\s+)?(?:(?:\{([^}]+)\})|([A-Za-z0-9_]+))\s+from\s+['"]([^'"]+)['"]""")
        private val TS_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:export\s+)?(?:default\s+)?(?:async\s+)?(?<kw>class|interface|type|function|enum|const)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )

        private val RS_USE_RE = Regex("""^\s*(?:pub(?:\([^)]*\))?\s+)?use\s+([a-zA-Z0-9_:]+)""", RegexOption.MULTILINE)
        private val RS_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?<kw>struct|enum|trait|fn|type|mod)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )

        private val CPP_INC_RE = Regex("""^\s*#include\s+[<"]([^>"]+)[>"]""", RegexOption.MULTILINE)
        private val CPP_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:template\s*<[^>]*>\s*)?(?<kw>class|struct|namespace|enum(?:\s+class)?)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )

        private val PY_IMP_RE = Regex("""^\s*(?:from\s+([a-zA-Z0-9_.]+)\s+import|import\s+([a-zA-Z0-9_.]+))""", RegexOption.MULTILINE)
        private val PY_DECL_RE = Regex("""^(?<indent>[ \t]*)(?<kw>class|def|async\s+def)\s+(?<name>[A-Za-z0-9_]+)""", RegexOption.MULTILINE)

        private val CALL_RE = Regex("""\b(?:(?<receiver>[A-Za-z_][A-Za-z0-9_]*)(?:::|\.))?(?<callee>[A-Za-z_][A-Za-z0-9_]{2,})\s*\(""")
        private val CALL_STOPWORDS = setOf(
            "if", "for", "while", "switch", "when", "return", "catch", "super", "this", "self",
            "print", "println", "len", "str", "int", "float", "bool", "listOf", "mapOf", "setOf",
            "require", "check", "match", "throw", "yield", "guard", "case", "typeof"
        )

        fun supportedLanguage(ext: String): String = EXT_MAP[ext.lowercase()] ?: ""

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

            return when (lang) {
                "kotlin" -> extractKotlin(projectId, relPath, sourceText, moduleId, sourceSet)
                "swift" -> extractSwift(projectId, relPath, sourceText, moduleId, sourceSet)
                "python" -> extractPython(projectId, relPath, sourceText, moduleId, sourceSet)
                "typescript", "javascript" -> extractTypeScript(projectId, relPath, sourceText, moduleId, sourceSet)
                "rust" -> extractRust(projectId, relPath, sourceText, moduleId, sourceSet)
                "cpp" -> extractCpp(projectId, relPath, sourceText, moduleId, sourceSet)
                else -> Triple(emptyList(), emptyList(), 0)
            }
        }

        private class LexerState {
            var inBlockComment = false
            var inTripleQuote = false
            var inBacktick = false
        }

        private fun cleanLine(line: String, state: LexerState = LexerState()): String {
            val sb = StringBuilder()
            var i = 0
            while (i < line.length) {
                if (state.inBlockComment) {
                    if (line[i] == '*' && i + 1 < line.length && line[i + 1] == '/') {
                        state.inBlockComment = false
                        i += 2
                    } else {
                        i++
                    }
                    continue
                }
                if (state.inTripleQuote) {
                    if (line[i] == '"' && i + 2 < line.length && line[i + 1] == '"' && line[i + 2] == '"') {
                        state.inTripleQuote = false
                        i += 3
                    } else {
                        i++
                    }
                    continue
                }
                if (state.inBacktick) {
                    if (line[i] == '\\' && i + 1 < line.length) {
                        i += 2
                    } else if (line[i] == '`') {
                        state.inBacktick = false
                        i++
                    } else {
                        i++
                    }
                    continue
                }

                // Check for block comment start
                if (line[i] == '/' && i + 1 < line.length && line[i + 1] == '*') {
                    state.inBlockComment = true
                    i += 2
                    continue
                }
                // Check for line comment start
                if (line[i] == '/' && i + 1 < line.length && line[i + 1] == '/') {
                    break
                }
                // Check for triple quote start
                if (line[i] == '"' && i + 2 < line.length && line[i + 1] == '"' && line[i + 2] == '"') {
                    state.inTripleQuote = true
                    i += 3
                    continue
                }
                // Check for single-line double-quoted string
                if (line[i] == '"') {
                    i++
                    while (i < line.length && line[i] != '"') {
                        if (line[i] == '\\' && i + 1 < line.length) i += 2 else i++
                    }
                    if (i < line.length && line[i] == '"') i++
                    continue
                }
                // Check for single-line single-quoted string / char
                if (line[i] == '\'') {
                    i++
                    while (i < line.length && line[i] != '\'') {
                        if (line[i] == '\\' && i + 1 < line.length) i += 2 else i++
                    }
                    if (i < line.length && line[i] == '\'') i++
                    continue
                }
                // Check for backtick (JS/TS template literal or Kotlin identifier)
                if (line[i] == '`') {
                    i++
                    var closed = false
                    while (i < line.length) {
                        if (line[i] == '\\' && i + 1 < line.length) {
                            i += 2
                            continue
                        }
                        if (line[i] == '`') {
                            closed = true
                            i++
                            break
                        }
                        i++
                    }
                    if (!closed) {
                        state.inBacktick = true
                    }
                    continue
                }

                sb.append(line[i])
                i++
            }
            return sb.toString()
        }

        private fun cleanLinesFrom(lines: List<String>, startIdx: Int): List<String> {
            val state = LexerState()
            val result = ArrayList<String>(lines.size - startIdx)
            for (idx in startIdx until lines.size) {
                result.add(cleanLine(lines[idx], state))
            }
            return result
        }

        private fun isNewDeclarationStart(trimmed: String): Boolean {
            if (trimmed.startsWith("@") || trimmed.startsWith("#[")) return true
            val words = trimmed.split(' ', '\t', '(', ':', '<')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (words.isEmpty()) return false

            val declKeywords = setOf(
                "fun", "class", "interface", "object", "typealias", "val", "var",
                "def",
                "func", "struct", "protocol", "actor", "enum", "extension", "let",
                "function", "const", "type",
                "fn", "trait", "mod", "impl",
                "namespace"
            )
            val modifierKeywords = setOf(
                "public", "private", "protected", "internal", "open", "abstract", "sealed",
                "override", "expect", "actual", "inline", "suspend", "data", "value",
                "export", "default", "declare", "readonly", "static", "final", "mutating",
                "fileprivate", "nonisolated", "isolated", "indirect",
                "pub", "async", "unsafe", "extern", "virtual", "constexpr"
            )

            val firstWord = words.first()
            if (firstWord in declKeywords) return true
            if (firstWord in modifierKeywords) {
                if (words.size == 1) return true
                for (w in words.drop(1)) {
                    if (w in declKeywords) return true
                    if (w !in modifierKeywords) break
                }
            }
            return false
        }

        private fun hasDeclarationKeyword(str: String): Boolean {
            val tokens = str.split(' ', '\t', '(', ':', '<', '=')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val kws = setOf(
                "class", "interface", "object", "fun", "typealias",
                "func", "struct", "protocol", "actor", "enum", "extension",
                "function", "const", "type",
                "fn", "trait", "mod", "impl",
                "def", "namespace"
            )
            return tokens.any { it in kws }
        }

        private fun findBlockEndLine(lines: List<String>, startLine: Int): Int {
            if (startLine > lines.size) return lines.size

            val startIdx = startLine - 1
            val cleanLines = cleanLinesFrom(lines, startIdx)

            var depth = 0
            var started = false
            var hasEquals = false
            var seenKeyword = false
            var parenDepth = 0
            var bracketDepth = 0

            for (relativeIdx in cleanLines.indices) {
                val idx = startIdx + relativeIdx
                val clean = cleanLines[relativeIdx]

                if (!seenKeyword) {
                    if (hasDeclarationKeyword(clean)) {
                        seenKeyword = true
                    }
                }

                for (i in clean.indices) {
                    val c = clean[i]
                    when (c) {
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '[' -> bracketDepth++
                        ']' -> if (bracketDepth > 0) bracketDepth--
                        '=' -> {
                            if (!started && parenDepth == 0 && bracketDepth == 0) {
                                val prev = if (i > 0) clean[i - 1] else ' '
                                val next = if (i + 1 < clean.length) clean[i + 1] else ' '
                                val isComparison = prev in listOf('=', '!', '<', '>') || next == '='
                                if (!isComparison) {
                                    hasEquals = true
                                }
                            }
                        }
                        '{' -> {
                            if (parenDepth == 0 && bracketDepth == 0) {
                                depth++
                                started = true
                            } else if (started) {
                                depth++
                            }
                        }
                        '}' -> {
                            if (started) {
                                depth--
                            }
                        }
                    }
                }

                if (started && depth <= 0) {
                    if (!hasEquals) {
                        return idx + 1
                    }
                    val trimmed = clean.trimEnd()
                    val isContinuation = trimmed.endsWith("=") ||
                        trimmed.endsWith("+") ||
                        trimmed.endsWith("-") ||
                        trimmed.endsWith("*") ||
                        trimmed.endsWith("/") ||
                        trimmed.endsWith("%") ||
                        trimmed.endsWith("&&") ||
                        trimmed.endsWith("||") ||
                        trimmed.endsWith("?:") ||
                        trimmed.endsWith(".") ||
                        trimmed.endsWith("?.") ||
                        trimmed.endsWith("::") ||
                        trimmed.endsWith(",") ||
                        trimmed.endsWith("->") ||
                        trimmed.endsWith(":") ||
                        trimmed.endsWith("&") ||
                        trimmed.endsWith("|")

                    var lookAheadRelative = relativeIdx + 1
                    while (lookAheadRelative < cleanLines.size && cleanLines[lookAheadRelative].trim().isEmpty()) {
                        lookAheadRelative++
                    }

                    if (lookAheadRelative >= cleanLines.size) {
                        return idx + 1
                    }

                    val nextClean = cleanLines[lookAheadRelative]
                    val nextTrimmed = nextClean.trim()
                    val nextIsContinuation = nextTrimmed.startsWith(".") ||
                        nextTrimmed.startsWith("?.") ||
                        nextTrimmed.startsWith("?:") ||
                        nextTrimmed.startsWith("->") ||
                        nextTrimmed.startsWith("+") ||
                        nextTrimmed.startsWith("-") ||
                        nextTrimmed.startsWith("*") ||
                        nextTrimmed.startsWith("/") ||
                        nextTrimmed.startsWith("&&") ||
                        nextTrimmed.startsWith("||") ||
                        nextTrimmed.startsWith("&") ||
                        nextTrimmed.startsWith("|")

                    if (!isContinuation && !nextIsContinuation) {
                        return idx + 1
                    }
                    continue
                }

                if (started) {
                    continue
                }

                if (seenKeyword && parenDepth == 0 && bracketDepth == 0) {
                    val trimmed = clean.trimEnd()
                    if (trimmed.endsWith(";")) {
                        return idx + 1
                    }

                    val isContinuation = trimmed.endsWith("=") ||
                        trimmed.endsWith("+") ||
                        trimmed.endsWith("-") ||
                        trimmed.endsWith("*") ||
                        trimmed.endsWith("/") ||
                        trimmed.endsWith("%") ||
                        trimmed.endsWith("&&") ||
                        trimmed.endsWith("||") ||
                        trimmed.endsWith("?:") ||
                        trimmed.endsWith(".") ||
                        trimmed.endsWith("?.") ||
                        trimmed.endsWith("::") ||
                        trimmed.endsWith(",") ||
                        trimmed.endsWith("->") ||
                        trimmed.endsWith(":") ||
                        trimmed.endsWith("&") ||
                        trimmed.endsWith("|")

                    var lookAheadRelative = relativeIdx + 1
                    while (lookAheadRelative < cleanLines.size && cleanLines[lookAheadRelative].trim().isEmpty()) {
                        lookAheadRelative++
                    }

                    if (lookAheadRelative >= cleanLines.size) {
                        return idx + 1
                    }

                    val nextClean = cleanLines[lookAheadRelative]
                    val nextTrimmed = nextClean.trim()

                    val nextIsContinuation = nextTrimmed.startsWith(".") ||
                        nextTrimmed.startsWith("?.") ||
                        nextTrimmed.startsWith("?:") ||
                        nextTrimmed.startsWith("->") ||
                        nextTrimmed.startsWith("+") ||
                        nextTrimmed.startsWith("-") ||
                        nextTrimmed.startsWith("*") ||
                        nextTrimmed.startsWith("/") ||
                        nextTrimmed.startsWith("&&") ||
                        nextTrimmed.startsWith("||") ||
                        nextTrimmed.startsWith("&") ||
                        nextTrimmed.startsWith("|")

                    if (!isContinuation && !nextIsContinuation) {
                        if (nextTrimmed.startsWith("{")) {
                            continue
                        }

                        if (hasEquals) {
                            if (isNewDeclarationStart(nextTrimmed) || nextTrimmed.startsWith("}")) {
                                return idx + 1
                            }
                            val baseIndent = lines[startLine - 1].takeWhile { it == ' ' || it == '\t' }.length
                            val nextRaw = lines[startIdx + lookAheadRelative]
                            val nextIndent = nextRaw.takeWhile { it == ' ' || it == '\t' }.length
                            if (nextIndent <= baseIndent) {
                                return idx + 1
                            }
                        } else {
                            if (!nextTrimmed.startsWith("=") && !nextTrimmed.startsWith("=>")) {
                                return idx + 1
                            }
                        }
                    }
                }
            }
            return minOf(lines.size, startLine)
        }

        private fun findPythonBlockEndLine(lines: List<String>, startLine: Int): Int {
            if (startLine > lines.size) return lines.size
            val startIdx = startLine - 1
            val declLine = lines[startIdx]
            val baseIndent = declLine.takeWhile { it == ' ' || it == '\t' }.length

            var parenDepth = 0
            var bracketDepth = 0
            var braceDepth = 0
            var headerEnded = false
            var inTripleQuote: String? = null
            var lastContentLine = startLine

            for (idx in startIdx until lines.size) {
                val raw = lines[idx]

                if (inTripleQuote != null) {
                    val closePos = raw.indexOf(inTripleQuote)
                    if (closePos >= 0) {
                        inTripleQuote = null
                    }
                    lastContentLine = idx + 1
                    continue
                }

                val stripped = raw.substringBefore("#")
                val trimmed = stripped.trim()
                if (trimmed.isEmpty()) continue

                if (idx > startIdx && headerEnded && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    val indent = raw.takeWhile { it == ' ' || it == '\t' }.length
                    if (indent <= baseIndent && !trimmed.startsWith(")") && !trimmed.startsWith("]") && !trimmed.startsWith("}")) {
                        break
                    }
                }

                var i = 0
                while (i < stripped.length) {
                    if (i + 2 < stripped.length) {
                        val sub3 = stripped.substring(i, i + 3)
                        if (sub3 == "\"\"\"" || sub3 == "'''") {
                            val closeOnSameLine = stripped.indexOf(sub3, i + 3)
                            if (closeOnSameLine >= 0) {
                                i = closeOnSameLine + 3
                                continue
                            } else {
                                inTripleQuote = sub3
                                break
                            }
                        }
                    }
                    when (stripped[i]) {
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '[' -> bracketDepth++
                        ']' -> if (bracketDepth > 0) bracketDepth--
                        '{' -> braceDepth++
                        '}' -> if (braceDepth > 0) braceDepth--
                    }
                    i++
                }
                if (inTripleQuote == null && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && trimmed.endsWith(":")) {
                    headerEnded = true
                }
                lastContentLine = idx + 1
            }
            return lastContentLine
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

        private fun extractKotlinSupertypes(
            lines: List<String>,
            startLine: Int,
            endLine: Int
        ): List<Pair<String, String>> {
            val safeEnd = minOf(lines.size, endLine)
            val headerBuilder = StringBuilder()
            for (lineNo in startLine..safeEnd) {
                val line = if (lineNo == startLine) stripLeadingInlineAnnotations(lines[lineNo - 1]) else lines[lineNo - 1]
                val clean = line.substringBefore("//")
                val braceIdx = clean.indexOf('{')
                if (braceIdx >= 0) {
                    headerBuilder.append(clean.substring(0, braceIdx))
                    break
                }
                headerBuilder.append(clean).append(' ')
            }
            val header = headerBuilder.toString()
            var parenDepth = 0
            var angleDepth = 0
            var colonIdx = -1
            for (i in header.indices) {
                when (header[i]) {
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '<' -> angleDepth++
                    '>' -> if (angleDepth > 0) angleDepth--
                    ':' -> if (parenDepth == 0 && angleDepth == 0) {
                        val nextChar = if (i + 1 < header.length) header[i + 1] else ' '
                        val prevChar = if (i > 0) header[i - 1] else ' '
                        if (nextChar != ':' && prevChar != ':') {
                            colonIdx = i
                            break
                        }
                    }
                }
            }
            if (colonIdx < 0) return emptyList()
            val superPart = header.substring(colonIdx + 1)

            // Split top-level comma-separated supertypes (ignoring commas inside (...) or <...>)
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var pDepth = 0
            var aDepth = 0
            for (c in superPart) {
                when (c) {
                    '(' -> { pDepth++; current.append(c) }
                    ')' -> { if (pDepth > 0) pDepth--; current.append(c) }
                    '<' -> { aDepth++; current.append(c) }
                    '>' -> { if (aDepth > 0) aDepth--; current.append(c) }
                    ',' -> if (pDepth == 0 && aDepth == 0) {
                        parts.add(current.toString())
                        current.clear()
                    } else {
                        current.append(c)
                    }
                    else -> current.append(c)
                }
            }
            if (current.isNotEmpty()) parts.add(current.toString())

            val results = mutableListOf<Pair<String, String>>()
            for (st in parts) {
                val trimmed = st.trim()
                val clean = trimmed.substringBefore("(").substringBefore("<").substringBefore(" by ").trim()
                    .substringAfterLast('.')
                if (clean.isNotEmpty() && clean[0].isUpperCase()) {
                    val edgeType = if ("(" in trimmed.substringBefore(" by ")) "INHERITS" else "IMPLEMENTS"
                    results.add(Pair(clean, edgeType))
                }
            }
            return results
        }

        private fun extractKotlin(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val pkgMatch = KT_PKG_RE.find(sourceText)
            val pkgName = pkgMatch?.groupValues?.get(1) ?: relPath.replace('/', '.')
            val imports = KT_IMP_RE.findAll(sourceText).map { match ->
                Pair(match.groupValues[1], sourceText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in KT_DECL_RE.findAll(sourceText)) {
                val annotations = match.groups["annotations"]?.value ?: ""
                val modifiersGroup = match.groups["modifiers"]
                val modifiers = modifiersGroup?.value ?: ""
                val kwGroup = match.groups["kw"] ?: continue
                val kw = kwGroup.value
                val rawName = match.groups["name"]?.value
                val modTokens = modifiers.split(Regex("""\s+""")).filter { it.isNotEmpty() }.toSet()

                val name = when {
                    !rawName.isNullOrEmpty() -> rawName
                    kw == "object" && "companion" in modTokens -> "Companion"
                    else -> continue
                }
                if (name == "Companion") continue

                val declStartOffset = if (modifiers.isNotEmpty() && modifiersGroup != null) {
                    modifiersGroup.range.first
                } else {
                    kwGroup.range.first
                }
                val startLine = sourceText.substring(0, declStartOffset).count { it == '\n' } + 1
                val declLineRaw = if (startLine <= lines.size) lines[startLine - 1] else ""
                val isTopLevel = declLineRaw.takeWhile { it == ' ' || it == '\t' }.isEmpty()
                if (isTopLevel && "private" !in modTokens && "companion" !in modTokens) topLevelCount++

                val endLine = findBlockEndLine(lines, startLine)
                val sigLine = if (startLine <= lines.size) stripLeadingInlineAnnotations(lines[startLine - 1]) else name

                val annTokens = Regex("""@([A-Za-z0-9_]+)""").findAll(annotations).map { it.groupValues[1] }.toList()
                val kind = when {
                    "expect" in modTokens -> "expect_decl"
                    "actual" in modTokens -> "actual_decl"
                    "Composable" in annTokens -> "composable"
                    kw == "fun" -> "function"
                    else -> kw
                }

                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name, annTokens)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$pkgName.$name", kind = kind, signature = sigLine,
                        startLine = startLine, endLine = endLine, moduleId = moduleId,
                        sourceSet = sourceSet, semioticLayer = sem, archLayer = arch, isTestDouble = isTd
                    )
                )

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

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val body = maskChildLineRanges(lines, node.startLine, node.endLine, childRanges)
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    if (callee != node.name && callee !in CALL_STOPWORDS && seenCallees.add(callee)) {
                        val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$callee:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractSwift(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val imports = SWIFT_IMP_RE.findAll(sourceText).map { match ->
                Pair(match.groupValues[1], sourceText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in SWIFT_DECL_RE.findAll(sourceText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "class"
                val name = match.groups["name"]?.value ?: continue

                val isTopLevel = indent.isEmpty()
                if (isTopLevel) topLevelCount++
                val startLine = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine)
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$relPath:$name", kind = if (kw == "func") "function" else kw,
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }
            }

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val body = maskChildLineRanges(lines, node.startLine, node.endLine, childRanges)
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    if (callee != node.name && callee !in CALL_STOPWORDS && seenCallees.add(callee)) {
                        val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$callee:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractTypeScript(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val imports = mutableListOf<Pair<String, Int>>()
            for (m in TS_IMP_RE.findAll(sourceText)) {
                val named = m.groupValues[1].ifEmpty { m.groupValues[2] }
                val lineNo = sourceText.substring(0, m.range.first).count { it == '\n' } + 1
                for (sym in named.split(",")) {
                    val clean = sym.trim().substringBefore(" as ").trim()
                    if (clean.isNotEmpty()) imports.add(Pair(clean, lineNo))
                }
            }

            var topLevelCount = 0
            for (match in TS_DECL_RE.findAll(sourceText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "class"
                val name = match.groups["name"]?.value ?: continue

                val isTopLevel = indent.isEmpty()
                if (isTopLevel) topLevelCount++
                val startLine = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine)
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$relPath:$name", kind = if (kw in listOf("function", "const")) "function" else "class",
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }
            }

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val body = maskChildLineRanges(lines, node.startLine, node.endLine, childRanges)
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    if (callee != node.name && callee !in CALL_STOPWORDS && seenCallees.add(callee)) {
                        val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$callee:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractRust(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val imports = RS_USE_RE.findAll(sourceText).map { match ->
                val sym = match.groupValues[1].substringAfterLast("::")
                Pair(sym, sourceText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in RS_DECL_RE.findAll(sourceText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "struct"
                val name = match.groups["name"]?.value ?: continue

                val isTopLevel = indent.isEmpty()
                if (isTopLevel) topLevelCount++
                val startLine = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine)
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$relPath:$name", kind = if (kw == "fn") "function" else "class",
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }
            }

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val body = maskChildLineRanges(lines, node.startLine, node.endLine, childRanges)
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val receiver = callMatch.groups["receiver"]?.value
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    val targetSym = if (callee == "new" && !receiver.isNullOrEmpty()) receiver else callee
                    if (targetSym != node.name && targetSym !in CALL_STOPWORDS && seenCallees.add(targetSym)) {
                        val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$targetSym:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$targetSym", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractCpp(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val imports = CPP_INC_RE.findAll(sourceText).map { match ->
                val header = match.groupValues[1].substringAfterLast('/')
                Pair(header, sourceText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in CPP_DECL_RE.findAll(sourceText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "class"
                val name = match.groups["name"]?.value ?: continue

                val isTopLevel = indent.isEmpty()
                if (isTopLevel) topLevelCount++
                val startLine = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine)
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$relPath:$name", kind = "class",
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }
            }

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val body = maskChildLineRanges(lines, node.startLine, node.endLine, childRanges)
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    if (callee != node.name && callee !in CALL_STOPWORDS && seenCallees.add(callee)) {
                        val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$callee:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractPython(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val imports = PY_IMP_RE.findAll(sourceText).map { match ->
                val mod = match.groupValues[1].ifEmpty { match.groupValues[2] }
                Pair(mod, sourceText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in PY_DECL_RE.findAll(sourceText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "def"
                val name = match.groups["name"]?.value ?: continue

                if (name.startsWith("_")) continue
                val isTopLevel = indent.isEmpty()
                if (isTopLevel) topLevelCount++

                val startLine = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findPythonBlockEndLine(lines, startLine)
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$relPath:$name", kind = if ("class" in kw) "class" else "function",
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }
            }

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val body = maskChildLineRanges(lines, node.startLine, node.endLine, childRanges)
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    if (callee != node.name && callee !in CALL_STOPWORDS && !callee.startsWith("_") && seenCallees.add(callee)) {
                        val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$callee:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        fun resolveCrossFileEdges(
            projectId: String,
            nodes: List<GraphNode>,
            rawEdges: List<GraphEdge>
        ): List<GraphEdge> {
            val byName = mutableMapOf<String, MutableList<GraphNode>>()
            val byQName = mutableMapOf<String, GraphNode>()
            for (node in nodes) {
                if (node.projectId != projectId) continue
                byName.getOrPut(node.name) { mutableListOf() }.add(node)
                byQName[node.qualifiedName] = node
            }

            val resolved = mutableListOf<GraphEdge>()
            val seen = mutableSetOf<String>()

            for (edge in rawEdges) {
                var targetId = edge.targetNodeId
                var conf = edge.confidence

                if (targetId.startsWith("sym:")) {
                    val sym = targetId.removePrefix("sym:")
                    val exact = byQName[sym]
                    if (exact != null) {
                        targetId = exact.nodeId
                    } else {
                        val shortSym = sym.substringAfterLast('.').substringAfterLast("::")
                        val candidates = byName[shortSym]
                        if (candidates.isNullOrEmpty()) continue
                        targetId = candidates[0].nodeId
                        if (candidates.size > 1 && edge.provenance == "INFERRED_CALL") {
                            conf = minOf(conf, 0.75)
                        }
                    }
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
