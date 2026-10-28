package atlas.extract

import atlas.extract.PolyglotAstExtractor.Companion.CALL_RE
import atlas.extract.PolyglotAstExtractor.Companion.CALL_STOPWORDS
import atlas.extract.PolyglotAstExtractor.Companion.cleanLinesFrom
import atlas.extract.PolyglotAstExtractor.Companion.computeComplexityMetrics
import atlas.extract.PolyglotAstExtractor.Companion.extractDocDescription
import atlas.extract.PolyglotAstExtractor.Companion.findBlockEndLine
import atlas.extract.PolyglotAstExtractor.Companion.maskChildLineRanges
import atlas.extract.PolyglotAstExtractor.Companion.maskCommentsAndStrings
import atlas.extract.PolyglotAstExtractor.Companion.stripLeadingInlineAnnotations
import atlas.extract.PolyglotAstExtractor.FileExtractionContext
import atlas.extract.PolyglotAstExtractor.ScopeEntry
import atlas.model.GraphEdge
import atlas.model.GraphNode

/**
 * Internal Kotlin AST extractor encapsulating package/import resolution, supertypes,
 * declaration scanning, and receiver/infix call extraction for [PolyglotAstExtractor].
 */
internal object KotlinAstExtractor {

    private val KT_PKG_RE = Regex("""^\s*package\s+([a-zA-Z0-9_.]+)""", RegexOption.MULTILINE)
    private val KT_IMP_RE = Regex("""^\s*import\s+([a-zA-Z0-9_.*]+)""", RegexOption.MULTILINE)
    private val KT_DECL_RE = Regex(
        """^(?<indent>[ \t]*)(?<annotations>(?:@[A-Za-z0-9_]+(?:\((?:[^()]|\([^()]*\))*\))?\s*)*)""" +
            """(?<modifiers>(?:(?:public|internal|private|protected|open|abstract|sealed|data|value|inline|suspend|expect|actual|override|companion|fun|final|inner|enum|annotation|operator|infix|tailrec|external)\s+)*)""" +
            """(?<kw>class|interface|object|fun|typealias)(?:\s+(?:[A-Za-z0-9_<>?,.\t ]+\.)?(?<name>[A-Za-z0-9_]+))?""",
        RegexOption.MULTILINE
    )

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

    private fun buildDeclarationHeader(
        lines: List<String>,
        startLine: Int,
        endLine: Int
    ): String {
        val safeEnd = minOf(lines.size, endLine)
        val headerBuilder = StringBuilder()
        for (lineNo in startLine..safeEnd) {
            val raw = lines[lineNo - 1]
            val line = if (lineNo == startLine) PolyglotAstExtractor.stripLeadingInlineAnnotations(raw) else raw
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

    private fun isStandaloneColon(header: String, i: Int): Boolean {
        val nextChar = if (i + 1 < header.length) header[i + 1] else ' '
        val prevChar = if (i > 0) header[i - 1] else ' '
        return nextChar != ':' && prevChar != ':'
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
        val endLine = PolyglotAstExtractor.findBlockEndLine(lines, startLine, cleanLines)
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
        return if (isEligible) PolyglotAstExtractor.extractDocDescription(lines, matchStartLine, "kotlin") else null
    }

    private fun addKotlinEdges(
        nodeId: String,
        decl: KotlinDeclSpec,
        ctx: PolyglotAstExtractor.FileExtractionContext,
        edges: MutableList<GraphEdge>
    ) {
        if (decl.isTopLevel) {
            for ((impTarget, impLine) in ctx.imports) {
                edges.add(
                    GraphEdge(
                        edgeId = "$nodeId:imp:$impTarget:$impLine",
                        projectId = ctx.projectId,
                        sourceNodeId = nodeId,
                        targetNodeId = "sym:$impTarget",
                        edgeType = "IMPORTS",
                        lineNumber = impLine
                    )
                )
            }
        }
        if (decl.kw in listOf("class", "interface", "object")) {
            for ((superName, edgeType) in extractKotlinSupertypes(ctx.lines, decl.startLine, decl.endLine)) {
                edges.add(
                    GraphEdge(
                        edgeId = "$nodeId:sup:$superName:${decl.startLine}",
                        projectId = ctx.projectId,
                        sourceNodeId = nodeId,
                        targetNodeId = "sym:$superName",
                        edgeType = edgeType,
                        lineNumber = decl.startLine
                    )
                )
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
        seenCallees: MutableSet<String>,
        edges: MutableList<GraphEdge>
    ) {
        for (callMatch in PolyglotAstExtractor.CALL_RE.findAll(body)) {
            val receiver = callMatch.groups["receiver"]?.value?.trim()
            val callee = callMatch.groups["callee"]?.value ?: continue
            val isSelf = callee == node.name && (receiver.isNullOrEmpty() || receiver == "this")
            if (isSelf || callee in PolyglotAstExtractor.CALL_STOPWORDS) continue

            val (targetSym, provenance) = resolveCallTargetAndProvenance(receiver, callee, localReceiverTypes)
            if (seenCallees.add(targetSym)) {
                val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                edges.add(
                    GraphEdge(
                        edgeId = "${node.nodeId}:call:$targetSym:$callLine",
                        projectId = node.projectId,
                        sourceNodeId = node.nodeId,
                        targetNodeId = "sym:$targetSym",
                        edgeType = "CALLS",
                        provenance = provenance,
                        confidence = 0.85,
                        lineNumber = callLine
                    )
                )
            }
        }
    }

    private fun extractKotlinInfixCallEdges(
        node: GraphNode,
        body: String,
        seenCallees: MutableSet<String>,
        edges: MutableList<GraphEdge>
    ) {
        for (infixMatch in INFIX_CALL_RE.findAll(body)) {
            val callee = infixMatch.groups["callee"]?.value ?: continue
            val isIgnored = callee == node.name || callee in INFIX_STOPWORDS || callee in PolyglotAstExtractor.CALL_STOPWORDS
            if (!isIgnored && seenCallees.add(callee)) {
                val callLine = node.startLine + body.substring(0, infixMatch.range.first).count { it == '\n' }
                edges.add(
                    GraphEdge(
                        edgeId = "${node.nodeId}:infix:$callee:$callLine",
                        projectId = node.projectId,
                        sourceNodeId = node.nodeId,
                        targetNodeId = "sym:$callee",
                        edgeType = "CALLS",
                        provenance = "INFERRED_INFIX_CALL",
                        confidence = 0.85,
                        lineNumber = callLine
                    )
                )
            }
        }
    }

    private fun extractKotlinCallEdges(
        node: GraphNode,
        body: String,
        localReceiverTypes: Map<String, String>,
        edges: MutableList<GraphEdge>
    ) {
        val seenCallees = mutableSetOf<String>()
        extractKotlinStandardCallEdges(node, body, localReceiverTypes, seenCallees, edges)
        extractKotlinInfixCallEdges(node, body, seenCallees, edges)
    }

    private fun isPublicTopLevel(isTopLevel: Boolean, modTokens: Set<String>, kw: String): Boolean =
        isTopLevel && "private" !in modTokens && "companion" !in modTokens && kw != "typealias"

    private fun buildKotlinNode(
        ctx: PolyglotAstExtractor.FileExtractionContext,
        pkgName: String,
        decl: KotlinDeclSpec
    ): GraphNode {
        val (nodeId, qualifiedName) = if (decl.enclosingPrefix.isNotEmpty()) {
            Pair("${ctx.fileId}#${decl.enclosingPrefix}.${decl.name}", "$pkgName.${decl.enclosingPrefix}.${decl.name}")
        } else {
            Pair("${ctx.fileId}#${decl.name}", "$pkgName.${decl.name}")
        }

        val annTokens = Regex("""@([A-Za-z0-9_]+)""").findAll(decl.annotations).map { it.groupValues[1] }.toList()
        val sigLine = if (decl.startLine <= ctx.lines.size) {
            PolyglotAstExtractor.stripLeadingInlineAnnotations(ctx.lines[decl.startLine - 1])
        } else {
            decl.name
        }
        val kind = extractKotlinKind(decl.kw, decl.modTokens, annTokens)
        val (sem, arch, isTd) = SemioticLayerClassifier.classify(ctx.relPath, decl.name, annTokens)
        val description = extractKotlinDoc(decl.isTopLevel, decl.kw, annTokens, ctx.lines, decl.matchStartLine)

        val (cVec, cScore) = PolyglotAstExtractor.computeComplexityMetrics(
            ctx = ctx,
            span = PolyglotAstExtractor.Companion.DeclMetricSpan(
                kind = kind,
                startLine = decl.startLine,
                endLine = decl.endLine,
                name = decl.name,
                isComposable = "Composable" in annTokens
            ),
            language = "kotlin"
        )

        return GraphNode(
            nodeId = nodeId,
            projectId = ctx.projectId,
            fileId = ctx.fileId,
            name = decl.name,
            qualifiedName = qualifiedName,
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

    private fun pushScopeEntryIfContainer(
        kw: String,
        name: String,
        startLine: Int,
        endLine: Int,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>
    ) {
        if (kw in setOf("class", "interface", "object", "enum", "fun")) {
            scopeStack.add(PolyglotAstExtractor.ScopeEntry(name, startLine, endLine))
        }
    }

    private fun processSingleKotlinDeclaration(
        match: MatchResult,
        ctx: PolyglotAstExtractor.FileExtractionContext,
        pkgName: String,
        scopeStack: MutableList<PolyglotAstExtractor.ScopeEntry>,
        edges: MutableList<GraphEdge>
    ): Pair<GraphNode, Boolean>? {
        val (kw, name, modTokens) = extractKotlinDeclHeader(match) ?: return null
        val annotations = match.groups["annotations"]?.value ?: ""
        val (matchStartLine, startLine, endLine) = resolveKotlinDeclLines(match, ctx.maskedText, ctx.lines, ctx.cleanLines)
        PolyglotAstExtractor.pruneScopeStack(scopeStack, startLine)

        val isTopLevel = scopeStack.isEmpty()
        val isPublicTop = isPublicTopLevel(isTopLevel, modTokens, kw)
        val enclosingPrefix = scopeStack.joinToString(".") { it.name }
        val decl = KotlinDeclSpec(
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
        val node = buildKotlinNode(ctx = ctx, pkgName = pkgName, decl = decl)
        addKotlinEdges(node.nodeId, decl, ctx, edges)
        pushScopeEntryIfContainer(kw, name, startLine, endLine, scopeStack)
        return Pair(node, isPublicTop)
    }

    private fun parseKotlinDeclarations(
        ctx: PolyglotAstExtractor.FileExtractionContext,
        pkgName: String,
        edges: MutableList<GraphEdge>
    ): Pair<List<GraphNode>, Int> {
        val nodes = mutableListOf<GraphNode>()
        val scopeStack = mutableListOf<PolyglotAstExtractor.ScopeEntry>()
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
        edges: MutableList<GraphEdge>
    ) {
        for (node in nodes) {
            val childRanges = PolyglotAstExtractor.buildChildRanges(nodes, node)
            val body = PolyglotAstExtractor.maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
            val localReceiverTypes = extractKotlinReceiverTypes(body, fileVarTypes)
            extractKotlinCallEdges(node, body, localReceiverTypes, edges)
        }
    }

    fun extractKotlin(
        projectId: String,
        relPath: String,
        sourceText: String,
        moduleId: String,
        sourceSet: String,
        precomputedMaskedText: String? = null
    ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
        val fileId = "$projectId:$relPath"
        val edges = mutableListOf<GraphEdge>()
        val lines = sourceText.lines()
        val maskedText = precomputedMaskedText ?: PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "kotlin")
        val maskedLines = maskedText.lines()
        val cleanLines = PolyglotAstExtractor.cleanLinesFrom(lines, 0)

        val pkgMatch = KT_PKG_RE.find(maskedText)
        val pkgName = pkgMatch?.groupValues?.get(1) ?: relPath.replace('/', '.')
        val imports = extractKotlinImports(maskedText)
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

        val (nodes, topLevelCount) = parseKotlinDeclarations(ctx, pkgName, edges)
        val fileVarTypes = extractKotlinReceiverTypes(maskedText)
        parseKotlinCalls(nodes, maskedLines, fileVarTypes, edges)

        return Triple(nodes, edges, topLevelCount)
    }
}
