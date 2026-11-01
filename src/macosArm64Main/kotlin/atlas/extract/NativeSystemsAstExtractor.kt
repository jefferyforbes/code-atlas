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
 * Internal AST extractor for native and systems languages (Swift, Rust, C/C++)
 * encapsulating import/include/use resolution, declaration scanning, and call edge extraction
 * for [PolyglotAstExtractor].
 */
internal object NativeSystemsAstExtractor {

    private val SWIFT_IMP_RE = Regex("""^\s*(?:@\w+\s+)?(?:public\s+|internal\s+)?import\s+([a-zA-Z0-9_.]+)""", RegexOption.MULTILINE)
    private val SWIFT_DECL_RE = Regex(
        """^(?<indent>[ \t]*)(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|fileprivate|internal|open|final|actor|indirect|nonisolated|isolated|override|static|mutating|class|convenience|required|lazy|weak|unowned)\s+)*""" +
            """(?<kw>struct|class|protocol|actor|enum|func|extension)\s+(?<name>[A-Za-z0-9_]+)""",
        RegexOption.MULTILINE
    )
    private val SWIFT_SCOPE_KEYWORDS = setOf("class", "struct", "protocol", "actor", "enum", "func", "extension")

    private val RS_USE_RE = Regex("""^\s*(?:pub(?:\([^)]*\))?\s+)?use\s+([a-zA-Z0-9_:*]+)""", RegexOption.MULTILINE)
    private val RS_DECL_RE = Regex(
        """^(?<indent>[ \t]*)(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?<kw>struct|enum|trait|fn|type|mod)\s+(?<name>[A-Za-z0-9_]+)""",
        RegexOption.MULTILINE
    )
    private val RUST_DOC_KINDS = setOf("class", "interface", "enum", "function")
    private val RUST_SCOPE_KEYWORDS = setOf("struct", "enum", "trait", "fn", "mod")
    private val RUST_NON_COUNTABLE_TOP_LEVEL = setOf("typealias", "module")

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

    private data class NativeDeclSpec(
        val nodeId: String,
        val qualifiedName: String,
        val name: String,
        val kwOrKind: String,
        val startLine: Int,
        val endLine: Int,
        val isTopLevel: Boolean
    )

    private fun extractSwiftImports(maskedText: String): List<Pair<String, Int>> =
        SWIFT_IMP_RE.findAll(maskedText).map { match ->
            Pair(match.groupValues[1], PolyglotAstExtractor.lineNumberAtOffset(maskedText, match.range.first))
        }.toList()

    private fun parseSwiftCallEdge(
        callMatch: MatchResult,
        node: GraphNode,
        body: String,
        seenCallees: MutableSet<String>
    ): GraphEdge? {
        val receiver = callMatch.groups["receiver"]?.value?.trim()
        val callee = callMatch.groups["callee"]?.value ?: return null
        val isSelfCall = callee == node.name && (receiver.isNullOrEmpty() || receiver == "self")
        if (isSelfCall || callee in PolyglotAstExtractor.CALL_STOPWORDS) return null
        val targetSym = if (!receiver.isNullOrEmpty()) "$receiver.$callee" else callee
        if (!seenCallees.add(targetSym)) return null

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

    private fun extractSwiftCallEdges(
        node: GraphNode,
        allNodes: List<GraphNode>,
        maskedLines: List<String>
    ): List<GraphEdge> {
        val childRanges = PolyglotAstExtractor.buildChildRanges(allNodes, node)
        val body = PolyglotAstExtractor.maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
        val seenCallees = mutableSetOf<String>()
        val edges = mutableListOf<GraphEdge>()
        for (callMatch in PolyglotAstExtractor.CALL_RE.findAll(body)) {
            val edge = parseSwiftCallEdge(callMatch, node, body, seenCallees)
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

    private fun buildSwiftNodeIdentifiers(
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

    private fun buildSwiftNode(
        ctx: PolyglotAstExtractor.FileExtractionContext,
        decl: NativeDeclSpec
    ): GraphNode {
        val sigLine = if (decl.startLine <= ctx.lines.size) ctx.lines[decl.startLine - 1].trim() else decl.name
        val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, decl.name)
        val description = if (decl.isTopLevel) {
            PolyglotAstExtractor.extractDocDescription(ctx.lines, decl.startLine, "swift")
        } else null
        val kind = if (decl.kwOrKind == "func") "function" else decl.kwOrKind
        val (cVec, cScore) = PolyglotAstExtractor.computeComplexityMetrics(
            ctx = ctx,
            span = PolyglotAstExtractor.Companion.DeclMetricSpan(kind, decl.startLine, decl.endLine, decl.name),
            language = "swift"
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

    private fun buildImportEdges(
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

    private fun isTopLevelScope(indent: String, scopeStack: List<PolyglotAstExtractor.ScopeEntry>): Boolean =
        indent.isEmpty() && scopeStack.isEmpty()

    private fun recordSwiftScopeEntry(
        decl: NativeDeclSpec,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>
    ) {
        if (decl.kwOrKind in SWIFT_SCOPE_KEYWORDS) {
            scopeStack.add(PolyglotAstExtractor.ScopeEntry(decl.name, decl.startLine, decl.endLine))
        }
    }

    private fun recordNodeAndImports(
        node: GraphNode,
        ctx: PolyglotAstExtractor.FileExtractionContext,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>
    ) {
        nodes.add(node)
        if (node.isTopLevel) {
            edges.addAll(buildImportEdges(node.nodeId, ctx.projectId, ctx.imports))
        }
    }

    private fun processSwiftDeclaration(
        match: MatchResult,
        ctx: PolyglotAstExtractor.FileExtractionContext,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>
    ): Boolean {
        val (indent, kw, name) = extractSwiftDeclHeader(match) ?: return false
        val startLine = PolyglotAstExtractor.lineNumberAtOffset(ctx.maskedText, match.range.first)
        val endLine = PolyglotAstExtractor.findBlockEndLine(ctx.lines, startLine, ctx.cleanLines)

        PolyglotAstExtractor.pruneScopeStack(scopeStack, startLine)
        val (nodeId, qualifiedName) = buildSwiftNodeIdentifiers(ctx.fileId, ctx.relPath, name, scopeStack)
        val isTopLevel = isTopLevelScope(indent, scopeStack)

        val decl = NativeDeclSpec(
            nodeId = nodeId,
            qualifiedName = qualifiedName,
            name = name,
            kwOrKind = kw,
            startLine = startLine,
            endLine = endLine,
            isTopLevel = isTopLevel
        )
        val node = buildSwiftNode(ctx, decl)
        recordNodeAndImports(node, ctx, nodes, edges)
        recordSwiftScopeEntry(decl, scopeStack)

        return isTopLevel && kw != "extension"
    }

    fun extractSwift(
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
        val maskedText = precomputedMaskedText ?: PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "swift")
        val maskedLines = maskedText.lines()
        val cleanLines = PolyglotAstExtractor.cleanLinesFrom(lines, 0)
        val scopeStack = mutableListOf<PolyglotAstExtractor.ScopeEntry>()
        val imports = extractSwiftImports(maskedText)
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
            imports = imports
        )

        var topLevelCount = 0
        for (match in SWIFT_DECL_RE.findAll(maskedText)) {
            if (processSwiftDeclaration(match, ctx, scopeStack, nodes, edges)) {
                topLevelCount++
            }
        }

        for (node in nodes) {
            edges.addAll(extractSwiftCallEdges(node, nodes, maskedLines))
        }
        return Triple(nodes, edges, topLevelCount)
    }

    private fun extractRustImports(maskedText: String): List<Pair<String, Int>> =
        RS_USE_RE.findAll(maskedText).map { match ->
            val sym = match.groupValues[1].substringAfterLast("::")
            Pair(sym, PolyglotAstExtractor.lineNumberAtOffset(maskedText, match.range.first))
        }.toList()

    private fun resolveRustKind(kw: String): String = when (kw) {
        "fn" -> "function"
        "trait" -> "interface"
        "enum" -> "enum"
        "type" -> "typealias"
        "mod" -> "module"
        else -> "class"
    }

    private fun buildColonNodeIdentifiers(
        fileId: String,
        relPath: String,
        name: String,
        scopeStack: List<PolyglotAstExtractor.ScopeEntry>
    ): Pair<String, String> {
        val prefix = scopeStack.joinToString("::") { it.name }
        return if (prefix.isNotEmpty()) {
            Pair("$fileId#$prefix::$name", "$relPath:$prefix::$name")
        } else {
            Pair("$fileId#$name", "$relPath:$name")
        }
    }

    private fun buildRustNode(
        ctx: PolyglotAstExtractor.FileExtractionContext,
        decl: NativeDeclSpec
    ): GraphNode {
        val sigLine = if (decl.startLine <= ctx.lines.size) ctx.lines[decl.startLine - 1].trim() else decl.name
        val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, decl.name)
        val description = if (decl.isTopLevel && decl.kwOrKind in RUST_DOC_KINDS) {
            PolyglotAstExtractor.extractDocDescription(ctx.lines, decl.startLine, "rust")
        } else null
        val (cVec, cScore) = PolyglotAstExtractor.computeComplexityMetrics(
            ctx = ctx,
            span = PolyglotAstExtractor.Companion.DeclMetricSpan(decl.kwOrKind, decl.startLine, decl.endLine, decl.name),
            language = "rust"
        )
        return GraphNode(
            nodeId = decl.nodeId,
            projectId = ctx.projectId,
            fileId = ctx.fileId,
            name = decl.name,
            qualifiedName = decl.qualifiedName,
            kind = decl.kwOrKind,
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

    private fun recordRustScopeEntry(
        kw: String,
        decl: NativeDeclSpec,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>
    ) {
        if (kw in RUST_SCOPE_KEYWORDS) {
            scopeStack.add(PolyglotAstExtractor.ScopeEntry(decl.name, decl.startLine, decl.endLine))
        }
    }

    private fun processRustDeclaration(
        match: MatchResult,
        ctx: PolyglotAstExtractor.FileExtractionContext,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>
    ): Boolean {
        val (indent, kw, name) = extractSwiftDeclHeader(match) ?: return false
        val kind = resolveRustKind(kw)
        val startLine = PolyglotAstExtractor.lineNumberAtOffset(ctx.maskedText, match.range.first)
        val endLine = PolyglotAstExtractor.findBlockEndLine(ctx.lines, startLine, ctx.cleanLines)

        PolyglotAstExtractor.pruneScopeStack(scopeStack, startLine)
        val (nodeId, qualifiedName) = buildColonNodeIdentifiers(ctx.fileId, ctx.relPath, name, scopeStack)
        val isTopLevel = isTopLevelScope(indent, scopeStack)

        val decl = NativeDeclSpec(
            nodeId = nodeId,
            qualifiedName = qualifiedName,
            name = name,
            kwOrKind = kind,
            startLine = startLine,
            endLine = endLine,
            isTopLevel = isTopLevel
        )
        val node = buildRustNode(ctx, decl)
        recordNodeAndImports(node, ctx, nodes, edges)
        recordRustScopeEntry(kw, decl, scopeStack)

        return isTopLevel && kind !in RUST_NON_COUNTABLE_TOP_LEVEL
    }

    private fun parseRustCallEdge(
        callMatch: MatchResult,
        node: GraphNode,
        body: String,
        seenCallees: MutableSet<String>
    ): GraphEdge? {
        val receiver = callMatch.groups["receiver"]?.value?.trim()
        val callee = callMatch.groups["callee"]?.value ?: return null
        val targetSym = when {
            callee == "new" && !receiver.isNullOrEmpty() -> receiver
            !receiver.isNullOrEmpty() -> "$receiver::$callee"
            else -> callee
        }
        val isSelfCall = targetSym == node.name || (callee == node.name && (receiver.isNullOrEmpty() || receiver == "self"))
        if (isSelfCall || targetSym in PolyglotAstExtractor.CALL_STOPWORDS || !seenCallees.add(targetSym)) return null

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

    private fun extractRustCallEdges(
        node: GraphNode,
        allNodes: List<GraphNode>,
        maskedLines: List<String>
    ): List<GraphEdge> {
        val childRanges = PolyglotAstExtractor.buildChildRanges(allNodes, node)
        val body = PolyglotAstExtractor.maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
        val seenCallees = mutableSetOf<String>()
        val edges = mutableListOf<GraphEdge>()
        for (callMatch in PolyglotAstExtractor.CALL_RE.findAll(body)) {
            val edge = parseRustCallEdge(callMatch, node, body, seenCallees)
            if (edge != null) edges.add(edge)
        }
        return edges
    }

    fun extractRust(
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
        val maskedText = precomputedMaskedText ?: PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "rust")
        val maskedLines = maskedText.lines()
        val cleanLines = PolyglotAstExtractor.cleanLinesFrom(lines, 0)
        val scopeStack = mutableListOf<PolyglotAstExtractor.ScopeEntry>()
        val imports = extractRustImports(maskedText)
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
            imports = imports
        )

        var topLevelCount = 0
        for (match in RS_DECL_RE.findAll(maskedText)) {
            if (processRustDeclaration(match, ctx, scopeStack, nodes, edges)) {
                topLevelCount++
            }
        }

        for (node in nodes) {
            edges.addAll(extractRustCallEdges(node, nodes, maskedLines))
        }
        return Triple(nodes, edges, topLevelCount)
    }

    private fun extractCppImports(sourceText: String, maskedLines: List<String>): List<Pair<String, Int>> =
        CPP_INC_RE.findAll(sourceText).mapNotNull { match ->
            val lineIdx = PolyglotAstExtractor.countNewlinesBefore(sourceText, match.range.first)
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

    private fun isInsideCppClassOrStruct(scopeStack: List<PolyglotAstExtractor.ScopeEntry>): Boolean {
        for (entry in scopeStack) {
            if (entry.kind == "class" || entry.kind == "struct") return true
        }
        return false
    }

    private fun isTopLevelCppType(indent: String, scopeStack: List<PolyglotAstExtractor.ScopeEntry>): Boolean {
        if (indent.isNotEmpty()) return false
        return !isInsideCppClassOrStruct(scopeStack)
    }

    private fun updateCppScopeStack(
        decl: NativeDeclSpec,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>
    ) {
        if (decl.kwOrKind in CPP_SCOPE_KINDS) {
            scopeStack.add(PolyglotAstExtractor.ScopeEntry(decl.name, decl.startLine, decl.endLine, decl.kwOrKind))
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
        return PolyglotAstExtractor.extractDocDescription(lines, startLine, "cpp")
    }

    private fun createCppTypeNode(
        ctx: PolyglotAstExtractor.FileExtractionContext,
        decl: NativeDeclSpec,
        sigLine: String,
        description: String?
    ): GraphNode {
        val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, decl.name)
        return GraphNode(
            nodeId = decl.nodeId,
            projectId = ctx.projectId,
            fileId = ctx.fileId,
            name = decl.name,
            qualifiedName = decl.qualifiedName,
            kind = decl.kwOrKind,
            signature = sigLine,
            startLine = decl.startLine,
            endLine = decl.endLine,
            moduleId = ctx.moduleId,
            sourceSet = ctx.sourceSet,
            semioticLayer = sem,
            archLayer = arch,
            isTestDouble = isTd,
            description = description,
            isTopLevel = decl.isTopLevel
        )
    }

    private fun processCppTypeDeclaration(
        declMatch: MatchResult,
        ctx: PolyglotAstExtractor.FileExtractionContext,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>,
        seenNodeIds: MutableSet<String>,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>
    ): Boolean {
        val header = extractSwiftDeclHeader(declMatch) ?: return false
        val (indent, kw, name) = header
        val kind = resolveCppDeclKind(kw)
        val startLine = PolyglotAstExtractor.lineNumberAtOffset(ctx.maskedText, declMatch.range.first)
        val endLine = PolyglotAstExtractor.findBlockEndLine(ctx.lines, startLine, ctx.cleanLines)

        PolyglotAstExtractor.pruneScopeStack(scopeStack, startLine)
        val (nodeId, qualifiedName) = buildColonNodeIdentifiers(ctx.fileId, ctx.relPath, name, scopeStack)

        val isTopLevel = isTopLevelCppType(indent, scopeStack)
        val sigLine = resolveDeclarationSignature(ctx.lines, startLine, name)
        if (!seenNodeIds.add(nodeId)) return false

        val description = resolveCppTypeDoc(isTopLevel, kind, ctx.lines, startLine)
        val decl = NativeDeclSpec(
            nodeId = nodeId,
            qualifiedName = qualifiedName,
            name = name,
            kwOrKind = kind,
            startLine = startLine,
            endLine = endLine,
            isTopLevel = isTopLevel
        )
        val node = createCppTypeNode(ctx, decl, sigLine, description)
        recordNodeAndImports(node, ctx, nodes, edges)
        updateCppScopeStack(decl, scopeStack)
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
        name in PolyglotAstExtractor.CALL_STOPWORDS || name in CPP_FUNC_STOPWORDS

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
        return PolyglotAstExtractor.extractDocDescription(lines, line, "cpp")
    }

    private fun createCppFunctionNode(
        ctx: PolyglotAstExtractor.FileExtractionContext,
        decl: NativeDeclSpec,
        sigLine: String,
        description: String?
    ): GraphNode {
        val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, decl.name)
        val (cVec, cScore) = PolyglotAstExtractor.computeComplexityMetrics(
            ctx = ctx,
            span = PolyglotAstExtractor.Companion.DeclMetricSpan("function", decl.startLine, decl.endLine, decl.name),
            language = "cpp"
        )
        return GraphNode(
            nodeId = decl.nodeId,
            projectId = ctx.projectId,
            fileId = ctx.fileId,
            name = decl.name,
            qualifiedName = "${ctx.relPath}:${decl.qualifiedName}",
            kind = "function",
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

    private fun processCppFunctionDeclaration(
        declMatch: MatchResult,
        ctx: PolyglotAstExtractor.FileExtractionContext,
        seenNodeIds: MutableSet<String>,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>
    ): Boolean {
        val rawName = declMatch.groups["name"]?.value ?: return false
        val startLine = PolyglotAstExtractor.lineNumberAtOffset(ctx.maskedText, declMatch.range.first)
        if (isSkippedCppFunction(rawName, nodes, startLine)) return false

        val shortName = rawName.substringAfterLast("::")
        val endLine = PolyglotAstExtractor.findBlockEndLine(ctx.lines, startLine, ctx.cleanLines)
        val enclosingClass = findEnclosingCppClass(nodes, startLine)
        val qualifiedCppName = resolveCppFunctionQualifiedName(rawName, enclosingClass)
        val indent = declMatch.groups["indent"]?.value.orEmpty()
        val isTopLevel = isTopLevelCppFunction(indent, enclosingClass, rawName)
        val nodeId = "${ctx.fileId}#$qualifiedCppName"
        if (!seenNodeIds.add(nodeId)) return false

        val sigLine = resolveDeclarationSignature(ctx.lines, startLine, shortName)
        val description = resolveCppFunctionDoc(isTopLevel, ctx.lines, startLine)
        val decl = NativeDeclSpec(
            nodeId = nodeId,
            qualifiedName = qualifiedCppName,
            name = shortName,
            kwOrKind = "function",
            startLine = startLine,
            endLine = endLine,
            isTopLevel = isTopLevel
        )
        val node = createCppFunctionNode(ctx, decl, sigLine, description)
        recordNodeAndImports(node, ctx, nodes, edges)
        return isTopLevel
    }

    private fun parseCppCallEdge(
        callMatch: MatchResult,
        node: GraphNode,
        body: String,
        seenCallees: MutableSet<String>
    ): GraphEdge? {
        val receiver = callMatch.groups["receiver"]?.value?.trim()
        val callee = callMatch.groups["callee"]?.value ?: return null
        val targetSym = if (!receiver.isNullOrEmpty()) "$receiver::$callee" else callee
        val isSelfCall = callee == node.name && (receiver.isNullOrEmpty() || receiver == "this")
        if (isSelfCall || callee in PolyglotAstExtractor.CALL_STOPWORDS || !seenCallees.add(targetSym)) return null

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

    private fun extractCppCallEdges(
        node: GraphNode,
        allNodes: List<GraphNode>,
        maskedLines: List<String>
    ): List<GraphEdge> {
        val childRanges = PolyglotAstExtractor.buildChildRanges(allNodes, node)
        val body = PolyglotAstExtractor.maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
        val seenCallees = mutableSetOf<String>()
        val edges = mutableListOf<GraphEdge>()
        for (callMatch in PolyglotAstExtractor.CALL_RE.findAll(body)) {
            val edge = parseCppCallEdge(callMatch, node, body, seenCallees)
            if (edge != null) edges.add(edge)
        }
        return edges
    }

    private fun collectCppTypeDeclarations(
        ctx: PolyglotAstExtractor.FileExtractionContext,
        seenNodeIds: MutableSet<String>,
        nodes: MutableList<GraphNode>,
        edges: MutableList<GraphEdge>
    ): Int {
        val scopeStack = mutableListOf<PolyglotAstExtractor.ScopeEntry>()
        var count = 0
        for (declMatch in CPP_DECL_RE.findAll(ctx.maskedText)) {
            if (processCppTypeDeclaration(declMatch, ctx, scopeStack, seenNodeIds, nodes, edges)) {
                count++
            }
        }
        return count
    }

    private fun collectCppFunctionDeclarations(
        ctx: PolyglotAstExtractor.FileExtractionContext,
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
        nodes: List<GraphNode>,
        maskedLines: List<String>
    ): List<GraphEdge> {
        val edges = mutableListOf<GraphEdge>()
        for (node in nodes) {
            edges.addAll(extractCppCallEdges(node, nodes, maskedLines))
        }
        return edges
    }

    fun extractCpp(
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
        val maskedText = precomputedMaskedText ?: PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "cpp")
        val maskedLines = maskedText.lines()
        val cleanLines = PolyglotAstExtractor.cleanLinesFrom(lines, 0)
        val imports = extractCppImports(sourceText, maskedLines)
        val seenNodeIds = mutableSetOf<String>()
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
            imports = imports
        )

        val typeTopLevelCount = collectCppTypeDeclarations(ctx, seenNodeIds, nodes, edges)
        val funcTopLevelCount = collectCppFunctionDeclarations(ctx, seenNodeIds, nodes, edges)
        edges.addAll(collectAllCppCallEdges(nodes, maskedLines))

        return Triple(nodes, edges, typeTopLevelCount + funcTopLevelCount)
    }
}
