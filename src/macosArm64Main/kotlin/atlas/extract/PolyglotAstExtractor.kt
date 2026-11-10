package atlas.extract

import atlas.model.ComplexityVector
import atlas.model.GraphEdge
import atlas.model.GraphNode

/**
 * Polyglot AST declaration, import, call, and complexity extractor across Kotlin, TypeScript, Python, Swift, Rust, and C++.
 */
class PolyglotAstExtractor {
    data class ScopeEntry(val name: String, val startLine: Int, val endLine: Int, val kind: String = "")

    internal data class FileExtractionContext(
        val projectId: String,
        val relPath: String,
        val fileId: String,
        val moduleId: String,
        val sourceSet: String,
        val maskedText: String,
        val lines: List<String>,
        val maskedLines: List<String>,
        val cleanLines: List<String>,
        val imports: List<Pair<String, Int>>,
        val lineIndex: LineOffsetIndex = LineOffsetIndex.from(maskedText)
    )

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

        internal val CALL_RE = Regex(
            """\b(?:(?<receiver>[A-Za-z_][A-Za-z0-9_]*)(?:::|\.))?(?<callee>[A-Za-z_][A-Za-z0-9_]+)\s*(?<callType>\(|\{)"""
        )
        private val INLINE_ANNOTATION_RE = Regex("""^@[A-Za-z0-9_]+(?:\((?:[^()]|\([^()]*\))*\))?\s*""")
        private val CPP_MACRO_ATTRIBUTE_RE = Regex("""^[A-Z][A-Z0-9_]+(?:\(.*\))?$""")
        internal val CALL_STOPWORDS = setOf(
            "if", "for", "while", "switch", "when", "return", "catch", "super", "this", "self",
            "print", "println", "len", "str", "int", "float", "bool", "listOf", "mapOf", "setOf",
            "require", "check", "match", "throw", "yield", "guard", "case", "typeof",
            "init", "get", "set", "else", "try", "finally", "do"
        )

        fun isValidCallMatch(callee: String, receiver: String?, callType: String): Boolean {
            if (callee in CALL_STOPWORDS) return false
            if (callType == "{") {
                if (receiver.isNullOrEmpty()) {
                    return callee.length >= 3 && callee[0].isLowerCase()
                }
                return callee.length >= 2
            }
            return callee.length >= 2
        }

        const val EXTRACTOR_VERSION: String = "1.4.2"

        fun supportedLanguage(ext: String): String = EXT_MAP[ext.lowercase()] ?: ""

        /**
         * Replaces comment bodies and string literal contents with spaces while preserving
         * exact string length and all newline (`\n`) offsets so regex match offsets map 1:1
         * to original source lines.
         */
        fun maskCommentsAndStrings(source: String, language: String = "kotlin"): String =
            SourceLexerMasker.maskCommentsAndStrings(source, language)

        private data class SourceDispatchContext(
            val projectId: String,
            val relPath: String,
            val sourceText: String,
            val moduleId: String,
            val sourceSet: String,
            val maskedText: String
        )

        private fun dispatchLanguageExtraction(
            lang: String,
            ctx: SourceDispatchContext
        ): Triple<List<GraphNode>, List<GraphEdge>, Int>? {
            return when (lang) {
                "kotlin" -> KotlinAstExtractor.extractKotlin(ctx.projectId, ctx.relPath, ctx.sourceText, ctx.moduleId, ctx.sourceSet, ctx.maskedText)
                "swift" -> NativeSystemsAstExtractor.extractSwift(ctx.projectId, ctx.relPath, ctx.sourceText, ctx.moduleId, ctx.sourceSet, ctx.maskedText)
                "python" -> PythonAstExtractor.extractPython(ctx.projectId, ctx.relPath, ctx.sourceText, ctx.moduleId, ctx.sourceSet, ctx.maskedText)
                "typescript", "javascript" -> TypeScriptAstExtractor.extractTypeScript(ctx.projectId, ctx.relPath, ctx.sourceText, ctx.moduleId, ctx.sourceSet, ctx.maskedText)
                "rust" -> NativeSystemsAstExtractor.extractRust(ctx.projectId, ctx.relPath, ctx.sourceText, ctx.moduleId, ctx.sourceSet, ctx.maskedText)
                "cpp" -> NativeSystemsAstExtractor.extractCpp(ctx.projectId, ctx.relPath, ctx.sourceText, ctx.moduleId, ctx.sourceSet, ctx.maskedText)
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
            ctx: SourceDispatchContext,
            baseNodes: List<GraphNode>,
            baseEdges: List<GraphEdge>,
            extractedUrls: List<UrlTargetResolver.ExtractedEndpointOrUrl>
        ): Pair<List<GraphNode>, List<GraphEdge>> {
            val fileId = "${ctx.projectId}:${ctx.relPath}"
            val finalNodes = baseNodes.toMutableList()
            val finalEdges = baseEdges.toMutableList()
            var fileScopeNode: GraphNode? = finalNodes.firstOrNull { it.nodeId == "$fileId#file" }

            for (item in extractedUrls) {
                var sourceNode = findUrlEnclosingNode(baseNodes, item.lineNumber)
                if (sourceNode == null) {
                    if (fileScopeNode == null) {
                        val created = createFileScopeNode(fileId, ctx.projectId, ctx.relPath, ctx.moduleId, ctx.sourceSet, ctx.sourceText)
                        fileScopeNode = created
                        finalNodes.add(created)
                    }
                    sourceNode = fileScopeNode
                }

                val targetId = if (item.isHttpEndpointRoute) {
                    "${ctx.projectId}:endpoint#${item.canonicalRouteOrUrl}"
                } else {
                    "${ctx.projectId}:url_ref#${item.canonicalRouteOrUrl}"
                }
                finalEdges.add(
                    GraphEdge(
                        edgeId = "${sourceNode.nodeId}:url:${item.edgeType.lowercase()}:${item.canonicalRouteOrUrl}:${item.lineNumber}",
                        projectId = ctx.projectId,
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
            knownModules: List<String> = emptyList(),
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val ext = if (relPath.contains('.')) "." + relPath.substringAfterLast('.') else ""
            val lang = supportedLanguage(ext)
            val moduleId = ModuleBoundaryDetector.resolveModuleId(relPath, knownModules)
            val sourceSet = SemioticLayerClassifier.detectSourceSet(relPath)

            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, lang)
            val ctx = SourceDispatchContext(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
            val extracted = dispatchLanguageExtraction(lang, ctx)
                ?: return Triple(emptyList(), emptyList(), 0)
            val (baseNodes, baseEdges, topLevelCount) = extracted

            val extractedUrls = UrlTargetResolver.extractCodeRoutesAndUrls(sourceText, maskedText, lang)
            if (extractedUrls.isEmpty()) {
                return Triple(baseNodes, baseEdges, topLevelCount)
            }

            val (finalNodes, finalEdges) = enrichNodesAndEdgesWithUrls(
                ctx = ctx,
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

        internal fun countNewlinesBefore(text: String, offset: Int): Int {
            val limit = offset.coerceIn(0, text.length)
            var count = 0
            for (i in 0 until limit) {
                if (text[i] == '\n') count++
            }
            return count
        }

        internal fun lineNumberAtOffset(text: String, offset: Int): Int =
            LineOffsetIndex.from(text).lineNumberAt(offset)

        private fun skipQuotedInterpolation(line: String, startPos: Int, closeChar: Char): Int {
            val openChar = if (closeChar == ')') '(' else '{'
            var pos = startPos + 2
            var depth = 1
            while (pos < line.length && depth > 0) {
                val c = line[pos]
                when {
                    c == '"' || c == '\'' -> pos = skipQuotedString(line, pos, c)
                    c == openChar -> {
                        depth++
                        pos++
                    }
                    c == closeChar -> {
                        depth--
                        pos++
                    }
                    else -> pos++
                }
            }
            return pos
        }

        private fun interpolationCloseChar(line: String, pos: Int, delimiter: Char): Char? {
            if (delimiter != '"' || pos + 1 >= line.length) return null
            val c = line[pos]
            val next = line[pos + 1]
            return when {
                c == '$' && next == '{' -> '}'
                c == '\\' && next == '(' -> ')'
                else -> null
            }
        }

        private fun skipQuotedString(line: String, startPos: Int, delimiter: Char): Int {
            var pos = startPos + 1
            while (pos < line.length && line[pos] != delimiter) {
                val interpClose = interpolationCloseChar(line, pos, delimiter)
                if (interpClose != null) {
                    pos = skipQuotedInterpolation(line, pos, interpClose)
                } else if (line[pos] == '\\' && pos + 1 < line.length) {
                    pos += 2
                } else {
                    pos++
                }
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

        internal fun cleanLinesFrom(lines: List<String>, startIdx: Int): List<String> {
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

        internal fun findBlockEndLine(
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

        internal fun maskChildLineRanges(
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

        internal fun stripLeadingInlineAnnotations(line: String): String {
            var rest = line.trim()
            while (true) {
                val m = INLINE_ANNOTATION_RE.find(rest) ?: break
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            return rest
        }

        internal data class DeclMetricSpan(
            val kind: String,
            val startLine: Int,
            val endLine: Int,
            val name: String? = null,
            val isComposable: Boolean = false
        )

        internal fun computeComplexityMetrics(
            ctx: FileExtractionContext,
            span: DeclMetricSpan,
            language: String
        ): Pair<ComplexityVector, Double> {
            return if (span.kind in setOf("function", "composable", "method")) {
                val sIdx = (span.startLine - 1).coerceAtLeast(0)
                val eIdx = span.endLine.coerceAtMost(ctx.maskedLines.size)
                val fMasked = if (sIdx < eIdx) ctx.maskedLines.subList(sIdx, eIdx) else emptyList()
                val fRaw = if (sIdx < ctx.lines.size) ctx.lines.subList(sIdx, minOf(eIdx, ctx.lines.size)) else emptyList()
                val vec = ComplexityExtractor.calculate(fMasked, fRaw, language, span.isComposable, span.name)
                val score = ComplexityExtractor.computeScore(vec)
                Pair(vec, score)
            } else {
                Pair(ComplexityVector(), 100.0)
            }
        }

        private val NON_DOC_LINE_PREFIXES = listOf(
            "// copyright", "// (c)", "// license", "// spdx-", "// all rights reserved",
            "// mark:", "// todo", "// fixme", "// note:", "// hack:", "// xxx",
            "// nolint", "// clang-format", "// eslint-", "// @ts-", "// ts-ignore",
            "// --", "// ==", "// **", "// ##"
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
                         trimmed.matches(CPP_MACRO_ATTRIBUTE_RE)
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
        ): String? = PythonAstExtractor.extractPythonDocDescription(lines, declStartLine, hdrEndLine, bodyEndLine)

        internal fun pruneScopeStack(scopeStack: MutableList<ScopeEntry>, startLine: Int) {
            while (scopeStack.isNotEmpty() && scopeStack.last().endLine < startLine) {
                scopeStack.removeAt(scopeStack.size - 1)
            }
        }

        internal fun buildChildRanges(nodes: List<GraphNode>, parent: GraphNode): List<IntRange> {
            return nodes.filter { child ->
                child.nodeId != parent.nodeId &&
                    child.startLine >= parent.startLine &&
                    child.endLine <= parent.endLine &&
                    (child.startLine > parent.startLine || child.endLine < parent.endLine)
            }.map { it.startLine..it.endLine }
        }

        fun resolveCrossFileEdges(
            projectId: String,
            nodes: List<GraphNode>,
            rawEdges: List<GraphEdge>
        ): List<GraphEdge> = CrossFileEdgeResolver.resolveCrossFileEdges(projectId, nodes, rawEdges)
    }
}
