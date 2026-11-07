package atlas.extract

import atlas.extract.PolyglotAstExtractor.Companion.CALL_RE
import atlas.extract.PolyglotAstExtractor.Companion.CALL_STOPWORDS
import atlas.extract.PolyglotAstExtractor.Companion.buildChildRanges
import atlas.extract.PolyglotAstExtractor.Companion.computeComplexityMetrics
import atlas.extract.PolyglotAstExtractor.Companion.maskChildLineRanges
import atlas.extract.PolyglotAstExtractor.Companion.maskCommentsAndStrings
import atlas.extract.PolyglotAstExtractor.Companion.pruneScopeStack
import atlas.extract.PolyglotAstExtractor.FileExtractionContext
import atlas.extract.PolyglotAstExtractor.ScopeEntry
import atlas.model.GraphEdge
import atlas.model.GraphNode

/**
 * Internal Python AST extractor encapsulating indentation-aware block scanning (`PythonBlockScanner`),
 * PEP 257 docstring parsing, import resolution, and class/def/call extraction for [PolyglotAstExtractor].
 */
internal object PythonAstExtractor {

    private val PY_IMP_RE = Regex("""^\s*(?:from\s+([a-zA-Z0-9_.]+)\s+import|import\s+([a-zA-Z0-9_.]+))""", RegexOption.MULTILINE)
    private val PY_DECL_RE = Regex("""^(?<indent>[ \t]*)(?<kw>class|def|async\s+def)\s+(?<name>[A-Za-z0-9_]+)""", RegexOption.MULTILINE)
    private val WHITESPACE_RE = Regex("""\s+""")

    private val TRIPLE_QUOTE_TOKENS = setOf("\"\"\"", "'''")

    private val PYTHON_NON_DOC_PREFIXES = listOf(
        "# copyright", "# (c)", "# license", "# spdx-", "# all rights reserved",
        "# todo", "# fixme", "# note:", "# type:", "# noqa", "# pylint:", "# flake8:",
        "# mypy:", "# pyright:", "# --", "# ==", "# **", "# ##"
    )

    private val PYTHON_TRIPLE_QUOTES = listOf(
        "\"\"\"" to "\"\"\"",
        "r\"\"\"" to "\"\"\"",
        "f\"\"\"" to "\"\"\"",
        "'''" to "'''",
        "r'''" to "'''",
        "f'''" to "'''"
    )

    private val ALLOWED_PYTHON_DUNDERS = setOf(
        "__init__", "__enter__", "__exit__", "__call__", "__str__", "__repr__",
        "__getitem__", "__setitem__", "__len__", "__iter__", "__next__", "__contains__",
        "__aenter__", "__aexit__", "__eq__", "__hash__"
    )

    private data class PythonResolvedDecl(
        val nodeId: String,
        val qualifiedName: String,
        val name: String,
        val kw: String,
        val startLine: Int,
        val endLine: Int,
        val isTopLevel: Boolean
    )

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

        private fun isEscaped(s: String, pos: Int): Boolean {
            var slashCount = 0
            var k = pos - 1
            while (k >= 0 && s[k] == '\\') {
                slashCount++
                k--
            }
            return slashCount % 2 != 0
        }

        private fun findUnescapedTripleQuote(s: String, quote: String, startIndex: Int = 0): Int {
            var pos = s.indexOf(quote, startIndex)
            while (pos >= 0) {
                if (!isEscaped(s, pos)) return pos
                pos = s.indexOf(quote, pos + 1)
            }
            return -1
        }

        fun handleInTripleQuote(raw: String, masked: String): Boolean {
            val quote = inTripleQuote ?: return false
            val delim = quote[0]
            val closed = masked.contains(delim) && findUnescapedTripleQuote(raw, quote) >= 0
            if (closed) {
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
            scanTripleQuotes(raw, masked)
            updateDepths(masked)
            if (isBalanced() && masked.trim().endsWith(":")) {
                headerEnded = true
            }
        }

        private fun scanTripleQuotes(raw: String, masked: String) {
            var j = 0
            while (j < raw.length) {
                j = stepTripleQuote(raw, masked, j)
                if (inTripleQuote != null) break
            }
        }

        private fun stepTripleQuote(raw: String, masked: String, j: Int): Int {
            if (j + 2 >= raw.length) return j + 1
            val sub3 = raw.substring(j, j + 3)
            if (sub3 !in TRIPLE_QUOTE_TOKENS) return j + 1
            if (j >= masked.length || masked[j] != raw[j]) return j + 1
            if (isEscaped(raw, j)) return j + 1
            val closeOnSameLine = findUnescapedTripleQuote(raw, sub3, j + 3)
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
            val masked = resolveMaskedLine(idx, raw)
            if (state.handleInTripleQuote(raw, masked)) {
                lastContentLine = idx + 1
                return false
            }
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
        return parts.joinToString(" ").replace(WHITESPACE_RE, " ")
    }

    private fun detectPythonTripleQuote(text: String): String? {
        for (entry in PYTHON_TRIPLE_QUOTES) {
            if (text.startsWith(entry.first)) return entry.second
        }
        return null
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
        lineRange: IntRange,
        quote: String,
        afterFirst: String
    ): String? {
        val collector = MultilineDocCollector(quote)
        collector.addInitial(afterFirst)
        for (idx in lineRange.first until lineRange.last) {
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
        return collectMultilineDocContent(lines, (docLineIdx + 1)..searchEnd, quote, afterFirst)
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

    private fun isAllowedPythonDeclaration(name: String): Boolean {
        if (!name.startsWith("_")) return true
        val isDunder = name.startsWith("__") && name.endsWith("__")
        return isDunder && name in ALLOWED_PYTHON_DUNDERS
    }

    private fun extractPythonImports(
        maskedText: String,
        lineIndex: LineOffsetIndex = LineOffsetIndex.from(maskedText)
    ): List<Pair<String, Int>> {
        return PY_IMP_RE.findAll(maskedText).map { match ->
            val mod = match.groupValues[1].ifEmpty { match.groupValues[2] }
            Pair(mod, lineIndex.lineNumberAt(match.range.first))
        }.toList()
    }

    private fun buildPythonNodeIdentifiers(
        fileId: String,
        relPath: String,
        name: String,
        scopeStack: List<PolyglotAstExtractor.ScopeEntry>
    ): Pair<String, String> {
        val prefix = scopeStack.joinToString(".") { it.name }
        return if (prefix.isNotEmpty()) {
            Pair("$fileId#$prefix.$name", "$relPath:$prefix.$name")
        } else {
            Pair("$fileId#$name", "$relPath:$name")
        }
    }

    private fun buildPythonNode(
        ctx: PolyglotAstExtractor.FileExtractionContext,
        decl: PythonResolvedDecl
    ): GraphNode {
        val sigLine = buildPythonHeaderSignature(ctx.lines, ctx.maskedLines, decl.startLine, decl.endLine)
        val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, decl.name)
        val headerEndLine = findPythonHeaderEndLine(ctx.maskedLines, decl.startLine, decl.endLine)
        val description = if (decl.isTopLevel) {
            extractPythonDocDescription(ctx.lines, decl.startLine, headerEndLine, decl.endLine)
        } else null
        val kind = if ("class" in decl.kw) "class" else "function"
        val (cVec, cScore) = PolyglotAstExtractor.computeComplexityMetrics(
            ctx = ctx,
            span = PolyglotAstExtractor.Companion.DeclMetricSpan(kind, decl.startLine, decl.endLine, decl.name),
            language = "python"
        )

        return GraphNode(
            nodeId = decl.nodeId,
            projectId = ctx.projectId,
            fileId = ctx.fileId,
            name = decl.name,
            qualifiedName = decl.qualifiedName,
            kind = kind,
            signature = sigLine,
            startLine = decl.startLine,
            endLine = decl.endLine,
            moduleId = ctx.moduleId,
            sourceSet = ctx.sourceSet,
            semioticLayer = sem,
            archLayer = arch,
            isTestDouble = isTd,
            description = description,
            isTopLevel = decl.isTopLevel,
            cyclomaticComplexity = cVec.cyclomatic,
            cognitiveComplexity = cVec.cognitive,
            maxNestingDepth = cVec.maxNesting,
            complexityScore = cScore
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
        return !isSelfCall && callee !in PolyglotAstExtractor.CALL_STOPWORDS && !callee.startsWith("_")
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

    private fun appendScopeIfBlock(
        decl: PythonResolvedDecl,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>
    ) {
        if ("class" in decl.kw || "def" in decl.kw) {
            scopeStack.add(PolyglotAstExtractor.ScopeEntry(decl.name, decl.startLine, decl.endLine))
        }
    }

    private fun recordNodeAndEdges(
        node: GraphNode,
        ctx: PolyglotAstExtractor.FileExtractionContext,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>
    ) {
        nodes.add(node)
        if (node.isTopLevel) {
            edges.addAll(buildPythonImportEdges(node.nodeId, ctx.projectId, ctx.imports))
        }
    }

    private fun processPythonDeclaration(
        match: MatchResult,
        ctx: PolyglotAstExtractor.FileExtractionContext,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>
    ): Boolean {
        val (indent, kw, name) = extractDeclHeader(match) ?: return false
        val startLine = ctx.lineIndex.lineNumberAt(match.range.first)
        val endLine = findPythonBlockEndLine(ctx.lines, ctx.maskedLines, startLine)

        PolyglotAstExtractor.pruneScopeStack(scopeStack, startLine)
        val (nodeId, qualifiedName) = buildPythonNodeIdentifiers(ctx.fileId, ctx.relPath, name, scopeStack)
        val isTopLevel = indent.isEmpty() && scopeStack.isEmpty()

        val decl = PythonResolvedDecl(
            nodeId = nodeId,
            qualifiedName = qualifiedName,
            name = name,
            kw = kw,
            startLine = startLine,
            endLine = endLine,
            isTopLevel = isTopLevel
        )
        val node = buildPythonNode(ctx, decl)
        recordNodeAndEdges(node, ctx, nodes, edges)
        appendScopeIfBlock(decl, scopeStack)
        return isTopLevel
    }

    private fun parseCallEdge(
        callMatch: MatchResult,
        node: GraphNode,
        scanStartLine: Int,
        body: String,
        seenCallees: MutableSet<String>
    ): GraphEdge? {
        val receiver = callMatch.groups["receiver"]?.value?.trim()
        val callee = callMatch.groups["callee"]?.value ?: return null
        val targetSym = if (!receiver.isNullOrEmpty()) "$receiver.$callee" else callee
        if (!isAllowedPythonCallee(callee, node.name, receiver) || !seenCallees.add(targetSym)) {
            return null
        }
        val callLine = scanStartLine + PolyglotAstExtractor.countNewlinesBefore(body, callMatch.range.first)
        return GraphEdge(
            edgeId = "${node.nodeId}:call:$targetSym:$callLine",
            projectId = node.projectId,
            sourceNodeId = node.nodeId,
            targetNodeId = "sym:$targetSym",
            edgeType = "CALLS",
            provenance = "INFERRED_CALL",
            confidence = 0.85,
            lineNumber = callLine
        )
    }

    private fun extractNodeCallEdges(
        node: GraphNode,
        nodes: List<GraphNode>,
        maskedLines: List<String>
    ): List<GraphEdge> {
        val childRanges = PolyglotAstExtractor.buildChildRanges(nodes, node)
        val headerEndLine = findPythonHeaderEndLine(maskedLines, node.startLine, node.endLine)
        val scanStartLine = if (headerEndLine < node.endLine) headerEndLine + 1 else node.startLine
        val body = PolyglotAstExtractor.maskChildLineRanges(maskedLines, scanStartLine, node.endLine, childRanges)
        val seenCallees = mutableSetOf<String>()
        val edges = mutableListOf<GraphEdge>()

        for (callMatch in PolyglotAstExtractor.CALL_RE.findAll(body)) {
            val edge = parseCallEdge(callMatch, node, scanStartLine, body, seenCallees)
            if (edge != null) edges.add(edge)
        }
        return edges
    }

    fun extractPython(
        projectId: String,
        relPath: String,
        sourceText: String,
        moduleId: String,
        sourceSet: String,
        precomputedMaskedText: String? = null
    ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
        val fileId = "$projectId:$relPath"
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val lines = sourceText.lines()
        val maskedText = precomputedMaskedText ?: PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "python")
        val lineIndex = LineOffsetIndex.from(maskedText)
        val maskedLines = maskedText.lines()
        val scopeStack = mutableListOf<PolyglotAstExtractor.ScopeEntry>()
        val imports = extractPythonImports(maskedText, lineIndex)
        val ctx = PolyglotAstExtractor.FileExtractionContext(
            projectId = projectId,
            relPath = relPath,
            fileId = fileId,
            moduleId = moduleId,
            sourceSet = sourceSet,
            maskedText = maskedText,
            lines = lines,
            maskedLines = maskedLines,
            cleanLines = emptyList(),
            imports = imports,
            lineIndex = lineIndex
        )

        var topLevelCount = 0
        for (match in PY_DECL_RE.findAll(maskedText)) {
            val isTop = processPythonDeclaration(match, ctx, scopeStack, nodes, edges)
            if (isTop) topLevelCount++
        }

        for (node in nodes) {
            edges.addAll(extractNodeCallEdges(node, nodes, maskedLines))
        }
        return Triple(nodes, edges, topLevelCount)
    }
}
