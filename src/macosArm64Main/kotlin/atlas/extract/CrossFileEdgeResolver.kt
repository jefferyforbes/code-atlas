package atlas.extract

import atlas.model.GraphEdge
import atlas.model.GraphNode

/**
 * Cross-file symbol target disambiguator and lexical/receiver/module scope resolver for [PolyglotAstExtractor].
 */
internal object CrossFileEdgeResolver {

    private class NodeLookupIndex(
        val byName: Map<String, List<GraphNode>>,
        val byQName: Map<String, GraphNode>,
        val byNodeId: Map<String, GraphNode>,
        private val simpleNames: MutableMap<String, String> = mutableMapOf(),
        private val fileDirs: MutableMap<String, String> = mutableMapOf(),
        private val parentScopes: MutableMap<String, String> = mutableMapOf(),
        private val tokenSets: MutableMap<String, Set<String>> = mutableMapOf(),
        private val receiverTokens: MutableMap<String, Set<String>> = mutableMapOf()
    ) {
        fun simpleNameOf(cand: GraphNode): String =
            simpleNames.getOrPut(cand.fileId) {
                cand.fileId.substringAfterLast('/').substringBeforeLast('.')
            }

        fun dirOf(fileId: String): String =
            fileDirs.getOrPut(fileId) {
                fileId.substringBeforeLast('/', "")
            }

        fun parentScopeOf(node: GraphNode): String =
            parentScopes.getOrPut(node.nodeId) {
                node.qualifiedName.substringBeforeLast('.', "")
            }

        fun tokensOf(cand: GraphNode, candSimpleName: String): Set<String> =
            tokenSets.getOrPut(cand.nodeId) {
                val set = LinkedHashSet<String>()
                set.addAll(NameSegmentTokenizer.tokenize(candSimpleName))
                set.addAll(NameSegmentTokenizer.tokenize(cand.name))
                set.addAll(NameSegmentTokenizer.tokenize(cand.qualifiedName))
                set
            }

        fun tokensOfReceiver(clean: String): Set<String> =
            receiverTokens.getOrPut(clean) {
                NameSegmentTokenizer.tokenize(clean).toSet()
            }
    }

    private data class DisambiguationContext(
        val srcNode: GraphNode?,
        val srcFileId: String,
        val srcModId: String,
        val fileImports: Set<String>,
        val index: NodeLookupIndex
    )

    private data class DisambiguatedTarget(
        val targetId: String,
        val confidence: Double
    )

    fun resolveCrossFileEdges(
        projectId: String,
        nodes: List<GraphNode>,
        rawEdges: List<GraphEdge>
    ): List<GraphEdge> {
        val index = buildNodeLookupIndex(nodes, projectId)
        val importsByFile = buildImportsByFileMap(rawEdges, index.byNodeId)
        val resolved = mutableListOf<GraphEdge>()
        val seen = mutableSetOf<String>()

        for (edge in rawEdges) {
            val targetId: String
            val conf: Double
            if (edge.targetNodeId.startsWith("sym:")) {
                val disambiguated = disambiguateSymbolTarget(
                    edge.targetNodeId.removePrefix("sym:"),
                    edge,
                    index,
                    importsByFile
                ) ?: continue
                targetId = disambiguated.targetId
                conf = disambiguated.confidence
            } else {
                targetId = edge.targetNodeId
                conf = edge.confidence
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

    private fun buildNodeLookupIndex(nodes: List<GraphNode>, projectId: String): NodeLookupIndex {
        val byName = mutableMapOf<String, MutableList<GraphNode>>()
        val byQName = mutableMapOf<String, GraphNode>()
        val byNodeId = mutableMapOf<String, GraphNode>()
        for (node in nodes) {
            if (node.projectId != projectId) continue
            byName.getOrPut(node.name) { mutableListOf() }.add(node)
            byQName[node.qualifiedName] = node
            byNodeId[node.nodeId] = node
        }
        return NodeLookupIndex(byName, byQName, byNodeId)
    }

    private fun buildImportsByFileMap(
        rawEdges: List<GraphEdge>,
        byNodeId: Map<String, GraphNode>
    ): Map<String, Set<String>> {
        val importsByFile = mutableMapOf<String, MutableSet<String>>()
        for (edge in rawEdges) {
            if (edge.edgeType == "IMPORTS") {
                val srcFileId = byNodeId[edge.sourceNodeId]?.fileId ?: edge.sourceNodeId.substringBefore("#")
                val impSym = edge.targetNodeId.removePrefix("sym:")
                importsByFile.getOrPut(srcFileId) { mutableSetOf() }.add(impSym)
            }
        }
        return importsByFile
    }

    private fun scoreSpecialReceiver(clean: String, candFileId: String, srcFileId: String): Double? {
        if (clean == "this") return if (candFileId == srcFileId) 100.0 else 0.0
        if (clean == "super") return if (candFileId == srcFileId) 90.0 else 0.0
        return null
    }

    private fun isExactReceiverMatch(clean: String, candSimpleName: String, candName: String, qn: String): Boolean {
        return candSimpleName.equals(clean, ignoreCase = true) ||
            candName.equals(clean, ignoreCase = true) ||
            qn.contains(".$clean.", ignoreCase = true) ||
            qn.endsWith(".$clean", ignoreCase = true)
    }

    private fun scoreTokenOverlap(
        recTokens: Set<String>,
        candTokens: Set<String>
    ): Double {
        if (recTokens.isEmpty()) return 0.0
        val tokenMatches = recTokens.count { it in candTokens }
        if (tokenMatches == 0) return 0.0
        val fraction = tokenMatches.toDouble() / recTokens.size
        return fraction * 40.0
    }

    private fun scoreReceiverCandidate(
        cand: GraphNode,
        cleanReceiver: String,
        srcFileId: String,
        index: NodeLookupIndex
    ): Double {
        val specialScore = scoreSpecialReceiver(cleanReceiver, cand.fileId, srcFileId)
        if (specialScore != null) return specialScore

        val candSimpleName = index.simpleNameOf(cand)
        val qn = cand.qualifiedName

        if (isExactReceiverMatch(cleanReceiver, candSimpleName, cand.name, qn)) {
            return 100.0
        }
        if (qn.contains(cleanReceiver, ignoreCase = true) || cand.signature.contains(cleanReceiver, ignoreCase = true)) {
            return 60.0
        }

        val recTokens = index.tokensOfReceiver(cleanReceiver)
        if (recTokens.isEmpty()) return 0.0
        val candTokens = index.tokensOf(cand, candSimpleName)
        return scoreTokenOverlap(recTokens, candTokens)
    }

    private fun extractSymbolReceiver(sym: String): String? = when {
        sym.contains('.') -> sym.substringBeforeLast('.')
        sym.contains("::") -> sym.substringBeforeLast("::")
        else -> null
    }

    private fun resolveReceiverMatches(
        candidates: List<GraphNode>,
        receiver: String?,
        srcFileId: String,
        index: NodeLookupIndex
    ): List<GraphNode> {
        val cleanReceiver = receiver?.trim().orEmpty()
        if (cleanReceiver.isEmpty()) return emptyList()
        var maxScore = 0.0
        val scored = ArrayList<Pair<GraphNode, Double>>(candidates.size)
        for (cand in candidates) {
            val s = scoreReceiverCandidate(cand, cleanReceiver, srcFileId, index)
            if (s > maxScore) maxScore = s
            scored.add(cand to s)
        }
        if (maxScore <= 0.0) return emptyList()
        return scored.filter { it.second == maxScore }.map { it.first }
    }

    private fun isSymbolImported(qn: String, fileImports: Set<String>): Boolean {
        val pkg = qn.substringBeforeLast('.', "")
        if (qn in fileImports) return true
        if ("$pkg.*" in fileImports) return true
        return fileImports.any { imp -> qn.endsWith(".$imp") }
    }

    private fun filterByLexicalScope(
        pool: List<GraphNode>,
        srcNode: GraphNode?,
        index: NodeLookupIndex
    ): List<GraphNode> {
        if (pool.size <= 1 || srcNode == null) return pool
        val srcScope = index.parentScopeOf(srcNode)
        if (srcScope.isEmpty()) return pool

        val exactScope = pool.filter { index.parentScopeOf(it) == srcScope }
        if (exactScope.isNotEmpty()) return exactScope

        val companionScope = pool.filter { cand ->
            index.parentScopeOf(cand) == "$srcScope.Companion"
        }
        if (companionScope.isNotEmpty()) return companionScope

        val outerScope = pool.filter { cand ->
            val candScope = index.parentScopeOf(cand)
            candScope.isNotEmpty() && srcScope.startsWith("$candScope.")
        }
        if (outerScope.isNotEmpty()) {
            val maxLen = outerScope.maxOf { index.parentScopeOf(it).length }
            return outerScope.filter { index.parentScopeOf(it).length == maxLen }
        }

        val topLevel = pool.filter { it.isTopLevel }
        if (topLevel.isNotEmpty()) return topLevel

        return pool
    }

    private fun narrowCandidatePool(
        initialPool: List<GraphNode>,
        ctx: DisambiguationContext
    ): List<GraphNode> {
        var pool = initialPool
        val srcDir = ctx.index.dirOf(ctx.srcFileId)
        if (srcDir.isNotEmpty()) {
            val sameDir = pool.filter { ctx.index.dirOf(it.fileId) == srcDir }
            if (sameDir.isNotEmpty()) pool = sameDir
        }
        if (ctx.srcModId.isNotEmpty()) {
            val sameModule = pool.filter { it.moduleId == ctx.srcModId }
            if (sameModule.isNotEmpty()) pool = sameModule
        }
        val srcArch = ctx.srcNode?.archLayer.orEmpty()
        if (srcArch.isNotEmpty()) {
            val sameArchLayer = pool.filter { it.archLayer == srcArch }
            if (sameArchLayer.isNotEmpty()) pool = sameArchLayer
        }
        return pool
    }

    private fun selectCandidatePool(
        candidatePool: List<GraphNode>,
        receiverMatches: List<GraphNode>,
        fallback: List<GraphNode>,
        ctx: DisambiguationContext
    ): List<GraphNode> {
        val sameFile = candidatePool.filter { it.fileId == ctx.srcFileId }
        if (sameFile.isNotEmpty()) return filterByLexicalScope(sameFile, ctx.srcNode, ctx.index)

        val imported = candidatePool.filter { isSymbolImported(it.qualifiedName, ctx.fileImports) }
        if (imported.isNotEmpty()) return filterByLexicalScope(imported, ctx.srcNode, ctx.index)

        if (receiverMatches.isNotEmpty()) {
            return filterByLexicalScope(narrowCandidatePool(receiverMatches, ctx), ctx.srcNode, ctx.index)
        }

        val narrowed = narrowCandidatePool(candidatePool, ctx)
        if (narrowed.size < candidatePool.size) {
            return filterByLexicalScope(narrowed, ctx.srcNode, ctx.index)
        }
        return filterByLexicalScope(fallback, ctx.srcNode, ctx.index)
    }

    private fun pickBestCandidate(pool: List<GraphNode>): GraphNode =
        pool.minBy { it.nodeId }

    private fun disambiguateSymbolTarget(
        sym: String,
        edge: GraphEdge,
        index: NodeLookupIndex,
        importsByFile: Map<String, Set<String>>
    ): DisambiguatedTarget? {
        val initialConf = edge.confidence
        val exact = index.byQName[sym]
        if (exact != null) {
            return DisambiguatedTarget(exact.nodeId, initialConf)
        }

        val shortSym = sym.substringAfterLast('.').substringAfterLast("::")
        val candidates = index.byName[shortSym]
        if (candidates.isNullOrEmpty()) return null
        if (candidates.size == 1) {
            return DisambiguatedTarget(candidates[0].nodeId, initialConf)
        }

        val srcNode = index.byNodeId[edge.sourceNodeId]
        val srcFileId = srcNode?.fileId ?: edge.sourceNodeId.substringBefore("#")
        val srcModId = srcNode?.moduleId.orEmpty()
        val fileImports = importsByFile[srcFileId].orEmpty()
        val ctx = DisambiguationContext(srcNode, srcFileId, srcModId, fileImports, index)

        val receiver = extractSymbolReceiver(sym)
        val receiverMatches = resolveReceiverMatches(candidates, receiver, srcFileId, index)
        val candidatePool = if (receiverMatches.isNotEmpty()) receiverMatches else candidates
        val chosenPool = selectCandidatePool(candidatePool, receiverMatches, candidates, ctx)
        val chosen = pickBestCandidate(chosenPool)

        var conf = initialConf
        if (chosenPool.size > 1 && edge.provenance.startsWith("INFERRED_CALL")) {
            conf = minOf(conf, 0.75)
        }
        return DisambiguatedTarget(chosen.nodeId, conf)
    }
}
