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
        val byNodeId: Map<String, GraphNode>
    )

    private data class DisambiguationContext(
        val srcNode: GraphNode?,
        val srcFileId: String,
        val srcModId: String,
        val fileImports: Set<String>
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

    private fun scoreTokenOverlap(clean: String, candSimpleName: String, candName: String, qn: String): Double {
        val recTokens = NameSegmentTokenizer.tokenize(clean).toSet()
        if (recTokens.isEmpty()) return 0.0

        val candTokens = (
            NameSegmentTokenizer.tokenize(candSimpleName) +
            NameSegmentTokenizer.tokenize(candName) +
            NameSegmentTokenizer.tokenize(qn)
        ).toSet()

        val tokenMatches = recTokens.count { it in candTokens }
        if (tokenMatches == 0) return 0.0

        val fraction = tokenMatches.toDouble() / recTokens.size
        return fraction * 40.0
    }

    private fun scoreReceiverCandidate(
        cand: GraphNode,
        receiver: String?,
        srcFileId: String
    ): Double {
        if (receiver.isNullOrBlank()) return 0.0
        val clean = receiver.trim()

        val specialScore = scoreSpecialReceiver(clean, cand.fileId, srcFileId)
        if (specialScore != null) return specialScore

        val candSimpleName = cand.fileId.substringAfterLast('/').substringBeforeLast('.')
        val qn = cand.qualifiedName

        if (isExactReceiverMatch(clean, candSimpleName, cand.name, qn)) {
            return 100.0
        }
        if (qn.contains(clean, ignoreCase = true) || cand.signature.contains(clean, ignoreCase = true)) {
            return 60.0
        }

        return scoreTokenOverlap(clean, candSimpleName, cand.name, qn)
    }

    private fun extractSymbolReceiver(sym: String): String? = when {
        sym.contains('.') -> sym.substringBeforeLast('.')
        sym.contains("::") -> sym.substringBeforeLast("::")
        else -> null
    }

    private fun resolveReceiverMatches(
        candidates: List<GraphNode>,
        receiver: String?,
        srcFileId: String
    ): List<GraphNode> {
        if (receiver == null) return emptyList()
        val scored = candidates.map { cand ->
            cand to scoreReceiverCandidate(cand, receiver, srcFileId)
        }
        val maxScore = scored.maxOfOrNull { it.second } ?: 0.0
        if (maxScore <= 0.0) return emptyList()
        return scored.filter { it.second == maxScore }.map { it.first }
    }

    private fun isSymbolImported(qn: String, fileImports: Set<String>): Boolean {
        val pkg = qn.substringBeforeLast('.', "")
        if (qn in fileImports) return true
        if ("$pkg.*" in fileImports) return true
        return fileImports.any { imp -> qn.endsWith(".$imp") }
    }

    private fun filterByLexicalScope(pool: List<GraphNode>, srcNode: GraphNode?): List<GraphNode> {
        if (pool.size <= 1 || srcNode == null) return pool
        val srcScope = srcNode.qualifiedName.substringBeforeLast('.', "")
        if (srcScope.isEmpty()) return pool

        val exactScope = pool.filter { it.qualifiedName.substringBeforeLast('.', "") == srcScope }
        if (exactScope.isNotEmpty()) return exactScope

        val companionScope = pool.filter { cand ->
            cand.qualifiedName.substringBeforeLast('.', "") == "$srcScope.Companion"
        }
        if (companionScope.isNotEmpty()) return companionScope

        val outerScope = pool.filter { cand ->
            val candScope = cand.qualifiedName.substringBeforeLast('.', "")
            candScope.isNotEmpty() && srcScope.startsWith("$candScope.")
        }
        if (outerScope.isNotEmpty()) {
            val maxLen = outerScope.maxOf { it.qualifiedName.substringBeforeLast('.', "").length }
            return outerScope.filter { it.qualifiedName.substringBeforeLast('.', "").length == maxLen }
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
        val srcDir = ctx.srcFileId.substringBeforeLast('/', "")
        if (srcDir.isNotEmpty()) {
            val sameDir = pool.filter { it.fileId.substringBeforeLast('/', "") == srcDir }
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
        if (sameFile.isNotEmpty()) return filterByLexicalScope(sameFile, ctx.srcNode)

        val imported = candidatePool.filter { isSymbolImported(it.qualifiedName, ctx.fileImports) }
        if (imported.isNotEmpty()) return filterByLexicalScope(imported, ctx.srcNode)

        if (receiverMatches.isNotEmpty()) {
            return filterByLexicalScope(narrowCandidatePool(receiverMatches, ctx), ctx.srcNode)
        }

        val narrowed = narrowCandidatePool(candidatePool, ctx)
        if (narrowed.size < candidatePool.size) {
            return filterByLexicalScope(narrowed, ctx.srcNode)
        }
        return filterByLexicalScope(fallback, ctx.srcNode)
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
        val ctx = DisambiguationContext(srcNode, srcFileId, srcModId, fileImports)

        val receiver = extractSymbolReceiver(sym)
        val receiverMatches = resolveReceiverMatches(candidates, receiver, srcFileId)
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
