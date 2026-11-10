package atlas.extract

import atlas.extract.PolyglotAstExtractor.Companion.CALL_RE
import atlas.extract.PolyglotAstExtractor.Companion.CALL_STOPWORDS
import atlas.extract.PolyglotAstExtractor.Companion.buildChildRanges
import atlas.extract.PolyglotAstExtractor.Companion.cleanLinesFrom
import atlas.extract.PolyglotAstExtractor.Companion.computeComplexityMetrics
import atlas.extract.PolyglotAstExtractor.Companion.extractDocDescription
import atlas.extract.PolyglotAstExtractor.Companion.findBlockEndLine
import atlas.extract.PolyglotAstExtractor.Companion.maskChildLineRanges
import atlas.extract.PolyglotAstExtractor.Companion.maskCommentsAndStrings
import atlas.extract.PolyglotAstExtractor.Companion.pruneScopeStack
import atlas.extract.PolyglotAstExtractor.FileExtractionContext
import atlas.extract.PolyglotAstExtractor.ScopeEntry
import atlas.model.GraphEdge
import atlas.model.GraphNode

/**
 * Internal TypeScript and JavaScript AST extractor encapsulating import parsing,
 * class/interface/function/arrow-function declaration scanning, and call edge extraction
 * for [PolyglotAstExtractor].
 */
internal object TypeScriptAstExtractor {

    private val TS_IMP_RE = Regex("""import\s+(?:type\s+)?(?<clause>[^'"]+?)\s+from\s+['"](?<source>[^'"]+)['"]""")
    private val TS_DECL_RE = Regex(
        """^(?<indent>[ \t]*)(?<export>export\s+)?(?:(?:default|async|abstract|declare)\s+)*(?<kw>class|interface|type|function|const\s+enum|enum|const|let|var)\s+(?<name>[A-Za-z0-9_]+)""",
        RegexOption.MULTILINE
    )
    private val TS_METHOD_DECL_RE = Regex(
        """^(?<indent>[ \t]+)(?:(?:public|private|protected|static|async|override|readonly|abstract)\s+)*(?:(?<getter>get|set)\s+)?(?<name>[A-Za-z0-9_$]+)\s*(?:<(?:[^<>]|<[^<>]*>)+>)?\s*\(""",
        RegexOption.MULTILINE
    )
    private val TS_ARROW_OR_FN_RE = Regex("""=\s*(?:async\s*)?(?:function\b|(?:\([^)]*\)|[A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]+)?=>)""")
    private val TS_METHOD_STOPWORDS = setOf(
        "if", "while", "for", "switch", "catch", "function", "return", "throw", "new", "typeof", "instanceof"
    )
    private val TS_DOC_KINDS = setOf("class", "interface", "function", "enum")
    private val TS_SCOPE_KINDS = setOf("class", "interface", "function", "enum")
    private val TS_NON_COUNTABLE_TOP_LEVEL = setOf("typealias", "variable")

    private data class TsDeclCandidate(
        val rangeStart: Int,
        val indent: String,
        val kw: String,
        val name: String,
        val isMethod: Boolean,
        val isExported: Boolean = false
    )

    private data class TsResolvedDecl(
        val nodeId: String,
        val qualifiedName: String,
        val name: String,
        val kind: String,
        val startLine: Int,
        val endLine: Int,
        val isTopLevel: Boolean
    )

    private fun extractNamedTsImports(clause: String): Pair<List<String>, String> {
        if (!clause.contains('{') || !clause.contains('}')) return Pair(emptyList(), clause)
        val namedPart = clause.substringAfter('{').substringBefore('}')
        val list = mutableListOf<String>()
        for (sym in namedPart.split(',')) {
            val clean = sym.trim().removePrefix("type ").trim().substringBefore(" as ").trim()
            if (clean.isNotEmpty()) list.add(clean)
        }
        val remaining = clause.substringBefore('{').trim().trimEnd(',')
        return Pair(list, remaining)
    }

    private fun extractNamespaceTsImport(clause: String): Pair<String?, String> {
        if (!clause.contains("* as ")) return Pair(null, clause)
        val nsName = clause.substringAfter("* as ").trim().substringBefore(',').trim()
        val remaining = clause.substringBefore("* as ").trim().trimEnd(',')
        return Pair(nsName.ifEmpty { null }, remaining)
    }

    private fun extractDefaultTsImport(remaining: String): String? {
        val defaultSym = remaining.trim().removePrefix("type ").trim()
        if (defaultSym.isEmpty() || defaultSym.contains(' ') || defaultSym == "*") return null
        return defaultSym
    }

    private fun parseTsImportClause(clause: String): List<String> {
        val results = mutableListOf<String>()
        val (named, afterNamed) = extractNamedTsImports(clause.trim())
        results.addAll(named)

        val (ns, afterNs) = extractNamespaceTsImport(afterNamed)
        if (ns != null) results.add(ns)

        val defaultSym = extractDefaultTsImport(afterNs)
        if (defaultSym != null) results.add(defaultSym)

        return results
    }

    private fun extractTypeScriptImports(
        maskedText: String,
        sourceText: String,
        lineIndex: LineOffsetIndex = LineOffsetIndex.from(maskedText)
    ): List<Pair<String, Int>> {
        val imports = mutableListOf<Pair<String, Int>>()
        for (m in TS_IMP_RE.findAll(maskedText)) {
            val origMatch = sourceText.substring(m.range)
            val parsed = TS_IMP_RE.find(origMatch) ?: m
            val clause = parsed.groups["clause"]?.value ?: continue
            val lineNo = lineIndex.lineNumberAt(m.range.first)
            for (sym in parseTsImportClause(clause)) {
                imports.add(Pair(sym, lineNo))
            }
        }
        return imports
    }

    private fun extractTsDeclCandidate(match: MatchResult): TsDeclCandidate? {
        val groups = match.groups
        val name = groups["name"]?.value ?: return null
        val indent = groups["indent"]?.value.orEmpty()
        val kw = groups["kw"]?.value ?: "class"
        val isExported = groups["export"] != null
        return TsDeclCandidate(match.range.first, indent, kw, name, isMethod = false, isExported = isExported)
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
        return if (hasBrace) PolyglotAstExtractor.findBlockEndLine(lines, startLine, cleanLines) else startLine
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

    private fun buildTsNodeIdentifiers(
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

    private fun buildTsNode(
        ctx: PolyglotAstExtractor.FileExtractionContext,
        decl: TsResolvedDecl
    ): GraphNode {
        val sigLine = if (decl.startLine <= ctx.lines.size) ctx.lines[decl.startLine - 1].trim() else decl.name
        val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, decl.name)
        val description = if (decl.isTopLevel && decl.kind in TS_DOC_KINDS) {
            PolyglotAstExtractor.extractDocDescription(ctx.lines, decl.startLine, "typescript")
        } else null
        val (cVec, cScore) = PolyglotAstExtractor.computeComplexityMetrics(
            ctx = ctx,
            span = PolyglotAstExtractor.Companion.DeclMetricSpan(decl.kind, decl.startLine, decl.endLine, decl.name),
            language = "typescript"
        )
        return GraphNode(
            nodeId = decl.nodeId,
            projectId = ctx.projectId,
            fileId = ctx.fileId,
            name = decl.name,
            qualifiedName = decl.qualifiedName,
            kind = decl.kind,
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

    private fun buildTsImportEdges(
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

    private fun recordTsScopeEntry(
        decl: TsResolvedDecl,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>
    ) {
        if (decl.kind in TS_SCOPE_KINDS) {
            scopeStack.add(PolyglotAstExtractor.ScopeEntry(decl.name, decl.startLine, decl.endLine))
        }
    }

    private fun recordTsNodeAndEdges(
        node: GraphNode,
        ctx: PolyglotAstExtractor.FileExtractionContext,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>
    ) {
        nodes.add(node)
        if (node.isTopLevel) {
            edges.addAll(buildTsImportEdges(node.nodeId, ctx.projectId, ctx.imports))
        }
    }

    private fun processTsCandidate(
        candidate: TsDeclCandidate,
        ctx: PolyglotAstExtractor.FileExtractionContext,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>
    ): Boolean {
        val startLine = ctx.lineIndex.lineNumberAt(candidate.rangeStart)
        PolyglotAstExtractor.pruneScopeStack(scopeStack, startLine)

        if (candidate.isMethod && scopeStack.isEmpty()) {
            return false
        }

        val endLine = findTsBlockEndLine(ctx.lines, ctx.cleanLines, startLine)
        val (nodeId, qualifiedName) = buildTsNodeIdentifiers(ctx.fileId, ctx.relPath, candidate.name, scopeStack)

        val declHeaderSlice = ctx.maskedLines.subList(
            startLine - 1,
            minOf(ctx.maskedLines.size, minOf(endLine, startLine + 2))
        ).joinToString(" ")
        val kind = resolveTsKind(candidate, candidate.kw, declHeaderSlice)
        val isTopLevel = !candidate.isMethod && candidate.indent.isEmpty() && scopeStack.isEmpty()

        if (!isTopLevel && kind == "variable") return false

        val decl = TsResolvedDecl(
            nodeId = nodeId,
            qualifiedName = qualifiedName,
            name = candidate.name,
            kind = kind,
            startLine = startLine,
            endLine = endLine,
            isTopLevel = isTopLevel
        )
        val node = buildTsNode(ctx, decl)
        recordTsNodeAndEdges(node, ctx, nodes, edges)
        recordTsScopeEntry(decl, scopeStack)

        return isTopLevel && candidate.isExported && kind !in TS_NON_COUNTABLE_TOP_LEVEL
    }

    private fun parseTsCallEdge(
        callMatch: MatchResult,
        node: GraphNode,
        body: String,
        seenCallees: MutableSet<String>
    ): GraphEdge? {
        val receiver = callMatch.groups["receiver"]?.value?.trim()
        val callee = callMatch.groups["callee"]?.value ?: return null
        val callType = callMatch.groups["callType"]?.value ?: "("
        if (callType == "{" && receiver.isNullOrEmpty()) return null
        val targetSym = if (!receiver.isNullOrEmpty()) "$receiver.$callee" else callee
        val isSelfCall = callee == node.name && (receiver.isNullOrEmpty() || receiver == "this")
        if (isSelfCall || !PolyglotAstExtractor.isValidCallMatch(callee, receiver, callType) || !seenCallees.add(targetSym)) return null

        val callLine = node.startLine + PolyglotAstExtractor.countNewlinesBefore(body, callMatch.range.first)
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

    private fun extractTsCallEdges(
        node: GraphNode,
        allNodes: List<GraphNode>,
        maskedLines: List<String>
    ): List<GraphEdge> {
        val childRanges = PolyglotAstExtractor.buildChildRanges(allNodes, node)
        val body = PolyglotAstExtractor.maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
        val seenCallees = mutableSetOf<String>()
        val edges = mutableListOf<GraphEdge>()
        for (callMatch in PolyglotAstExtractor.CALL_RE.findAll(body)) {
            val edge = parseTsCallEdge(callMatch, node, body, seenCallees)
            if (edge != null) edges.add(edge)
        }
        return edges
    }

    fun extractTypeScript(
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
        val maskedText = precomputedMaskedText ?: PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "typescript")
        val lineIndex = LineOffsetIndex.from(maskedText)
        val maskedLines = maskedText.lines()
        val cleanLines = PolyglotAstExtractor.cleanLinesFrom(lines, 0)
        val scopeStack = mutableListOf<PolyglotAstExtractor.ScopeEntry>()
        val imports = extractTypeScriptImports(maskedText, sourceText, lineIndex)
        val declCandidates = collectTsDeclCandidates(maskedText)
        val ctx = PolyglotAstExtractor.FileExtractionContext(
            projectId = projectId,
            relPath = relPath,
            fileId = fileId,
            moduleId = moduleId,
            sourceSet = sourceSet,
            maskedText = maskedText,
            lines = lines,
            maskedLines = maskedLines,
            cleanLines = cleanLines,
            imports = imports,
            lineIndex = lineIndex
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
            edges.addAll(extractTsCallEdges(node, nodes, maskedLines))
        }
        return Triple(nodes, edges, topLevelCount)
    }
}
