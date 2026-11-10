package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.model.LayerInversionViolation
import atlas.model.SpikeAnalysisResult

/**
 * Engine executing topological hypothesis tests across decoupling and Clean Architecture boundaries.
 */
class SpikeAnalysisEngine(
    private val store: SqliteGraphStore
) {

    fun runSpike(
        projectId: String,
        hypothesis: String,
        from: String? = null,
        to: String? = null,
        target: String? = null,
        forbiddenLayer: String? = null,
        maxFiles: Int? = null,
        maxHops: Int = 10
    ): SpikeAnalysisResult {
        val normHypothesis = hypothesis.trim().lowercase()
        return when (normHypothesis) {
            "decoupling" -> runDecouplingSpike(projectId, from ?: target, to, maxHops)
            "clean-arch", "clean_arch", "clean-architecture" -> runCleanArchSpike(projectId, target ?: from, forbiddenLayer, maxHops)
            "blast-ceiling", "blast_ceiling" -> runBlastCeilingSpike(projectId, target ?: from, maxFiles ?: 20)
            else -> SpikeAnalysisResult(
                hypothesis = hypothesis,
                projectId = projectId,
                status = "FALSIFIED",
                passed = false,
                message = "FALSIFIED (unknown hypothesis '$hypothesis'; supported: decoupling, clean-arch, blast-ceiling)"
            )
        }
    }

    private fun runDecouplingSpike(
        projectId: String,
        fromArg: String?,
        toArg: String?,
        maxHops: Int
    ): SpikeAnalysisResult {
        val from = fromArg?.trim().orEmpty()
        val to = toArg?.trim().orEmpty()

        if (from.isEmpty() || to.isEmpty()) {
            val msg = "FALSIFIED (source '--from' and target '--to' are required for decoupling spike)"
            return SpikeAnalysisResult(
                hypothesis = "decoupling",
                projectId = projectId,
                status = "FALSIFIED",
                passed = false,
                message = msg,
                markdownBlock = renderMarkdownBlock("decoupling", "FALSIFIED", msg, "Missing endpoints")
            )
        }

        val allNodes = store.listNodes(projectId).associateBy { it.nodeId }
        val sourceNodes = resolveNodes(projectId, from, allNodes)
        val targetNodes = resolveNodes(projectId, to, allNodes)

        if (sourceNodes.isEmpty() || targetNodes.isEmpty()) {
            val msg = "CONFIRMED (zero coupling paths)"
            return SpikeAnalysisResult(
                hypothesis = "decoupling",
                projectId = projectId,
                status = "CONFIRMED",
                passed = true,
                message = msg,
                markdownBlock = renderMarkdownBlock("decoupling", "CONFIRMED", msg, "Endpoints unlinked in graph")
            )
        }

        val sourceIds = sourceNodes.map { it.nodeId }.toSet()
        val targetIds = targetNodes.map { it.nodeId }.toSet()

        val directOverlap = sourceIds.intersect(targetIds)
        if (directOverlap.isNotEmpty()) {
            val node = allNodes[directOverlap.first()]
            val name = node?.name ?: directOverlap.first()
            val msg = "FALSIFIED (path: $name -> $name with edge types and lines: self-reference)"
            return SpikeAnalysisResult(
                hypothesis = "decoupling",
                projectId = projectId,
                status = "FALSIFIED",
                passed = false,
                message = msg,
                path = listOf("$name -> $name"),
                markdownBlock = renderMarkdownBlock("decoupling", "FALSIFIED", msg, "Direct overlap in symbol resolution")
            )
        }

        val allEdges = store.listEdges(projectId)
        val outgoingEdges = allEdges.groupBy { it.sourceNodeId }

        val shortestPath = findShortestCouplingPath(sourceIds, targetIds, outgoingEdges, maxHops)
        if (shortestPath == null) {
            val msg = "CONFIRMED (zero coupling paths)"
            return SpikeAnalysisResult(
                hypothesis = "decoupling",
                projectId = projectId,
                status = "CONFIRMED",
                passed = true,
                message = msg,
                markdownBlock = renderMarkdownBlock("decoupling", "CONFIRMED", msg, "0 dependency paths found up to $maxHops hops")
            )
        }

        val symbolChain = buildSymbolChain(shortestPath, allNodes)
        val edgeChain = buildEdgeChain(shortestPath, allNodes)
        val msg = "FALSIFIED (path: $symbolChain with edge types and lines: $edgeChain)"

        return SpikeAnalysisResult(
            hypothesis = "decoupling",
            projectId = projectId,
            status = "FALSIFIED",
            passed = false,
            message = msg,
            path = listOf(symbolChain, edgeChain),
            markdownBlock = renderMarkdownBlock("decoupling", "FALSIFIED", msg, edgeChain)
        )
    }

    private fun findShortestCouplingPath(
        sourceIds: Set<String>,
        targetIds: Set<String>,
        outgoingMap: Map<String, List<GraphEdge>>,
        maxHops: Int
    ): List<EdgeHop>? {
        val queue = mutableListOf<Pair<String, List<EdgeHop>>>()
        val visited = mutableSetOf<String>()

        val sortedSources = sourceIds.sorted()
        for (src in sortedSources) {
            queue.add(Pair(src, emptyList()))
            visited.add(src)
        }

        var head = 0
        while (head < queue.size) {
            val (currId, currentPath) = queue[head++]
            if (currentPath.size >= maxHops) continue

            val edges = outgoingMap[currId] ?: emptyList()
            val sortedEdges = edges.sortedWith(compareBy({ it.targetNodeId }, { it.lineNumber }, { it.edgeType }))
            for (edge in sortedEdges) {
                if (edge.edgeType in EXCLUDED_EDGE_TYPES) continue
                val hop = EdgeHop(edge.sourceNodeId, edge.targetNodeId, edge.edgeType, edge.lineNumber)

                if (edge.targetNodeId in targetIds) {
                    return currentPath + hop
                }

                if (visited.add(edge.targetNodeId)) {
                    queue.add(Pair(edge.targetNodeId, currentPath + hop))
                }
            }
        }

        return null
    }

    private fun buildSymbolChain(path: List<EdgeHop>, allNodes: Map<String, GraphNode>): String {
        val names = mutableListOf<String>()
        names.add(displayNodeName(path.first().sourceId, allNodes))
        for (hop in path) {
            names.add(displayNodeName(hop.targetId, allNodes))
        }
        return names.joinToString(" -> ")
    }

    private fun buildEdgeChain(path: List<EdgeHop>, allNodes: Map<String, GraphNode>): String = buildString {
        append(displayNodeName(path.first().sourceId, allNodes))
        for (hop in path) {
            append(" -[${hop.edgeType}:${hop.lineNumber}]-> ")
            append(displayNodeName(hop.targetId, allNodes))
        }
    }

    private fun displayNodeName(nodeId: String, allNodes: Map<String, GraphNode>): String {
        val node = allNodes[nodeId]
        if (node != null && node.name.isNotEmpty()) return node.name
        return if (nodeId.contains('#')) nodeId.substringAfter('#') else nodeId
    }

    private fun runCleanArchSpike(
        projectId: String,
        targetArg: String?,
        forbiddenLayer: String?,
        maxHops: Int
    ): SpikeAnalysisResult {
        val target = targetArg?.trim().orEmpty()
        if (target.isEmpty()) {
            val msg = "FALSIFIED (target source '--target' or '--from' is required for clean-arch spike)"
            return SpikeAnalysisResult(
                hypothesis = "clean-arch",
                projectId = projectId,
                status = "FALSIFIED",
                passed = false,
                message = msg,
                markdownBlock = renderMarkdownBlock("clean-arch", "FALSIFIED", msg, "Missing target")
            )
        }

        val allNodes = store.listNodes(projectId).associateBy { it.nodeId }
        val sourceNodes = resolveNodes(projectId, target, allNodes)

        if (sourceNodes.isEmpty()) {
            val msg = "CONFIRMED (Clean Architecture invariants satisfied, zero layer inversions)"
            return SpikeAnalysisResult(
                hypothesis = "clean-arch",
                projectId = projectId,
                status = "CONFIRMED",
                passed = true,
                message = msg,
                markdownBlock = renderMarkdownBlock("clean-arch", "CONFIRMED", msg, "No symbols found matching '$target'")
            )
        }

        val allEdges = store.listEdges(projectId)
        val outgoingEdges = allEdges.groupBy { it.sourceNodeId }
        val violations = mutableListOf<LayerInversionViolation>()

        val sourceIds = sourceNodes.map { it.nodeId }.toSet()
        val visited = mutableSetOf<String>()
        val queue = mutableListOf<Pair<String, Int>>()

        for (src in sourceIds) {
            queue.add(Pair(src, 0))
            visited.add(src)
        }

        var head = 0
        while (head < queue.size) {
            val (currId, depth) = queue[head++]
            if (depth >= maxHops) continue

            val srcNode = allNodes[currId] ?: continue
            val edges = outgoingEdges[currId] ?: emptyList()

            for (edge in edges) {
                if (edge.edgeType in EXCLUDED_EDGE_TYPES) continue
                val tgtNode = allNodes[edge.targetNodeId] ?: continue

                val violation = checkLayerInversion(srcNode, tgtNode, edge, forbiddenLayer)
                if (violation != null) {
                    violations.add(violation)
                }

                if (visited.add(edge.targetNodeId)) {
                    queue.add(Pair(edge.targetNodeId, depth + 1))
                }
            }
        }

        if (violations.isNotEmpty()) {
            val first = violations.first()
            val msg = "FALSIFIED (Clean Architecture violation: ${first.message})"
            return SpikeAnalysisResult(
                hypothesis = "clean-arch",
                projectId = projectId,
                status = "FALSIFIED",
                passed = false,
                message = msg,
                violations = violations,
                markdownBlock = renderMarkdownBlock("clean-arch", "FALSIFIED", msg, "${violations.size} inversion violation(s)")
            )
        }

        val msg = "CONFIRMED (Clean Architecture invariants satisfied, zero layer inversions)"
        return SpikeAnalysisResult(
            hypothesis = "clean-arch",
            projectId = projectId,
            status = "CONFIRMED",
            passed = true,
            message = msg,
            markdownBlock = renderMarkdownBlock("clean-arch", "CONFIRMED", msg, "All traversed efferent boundaries adhere to Clean Architecture")
        )
    }

    private fun checkLayerInversion(
        src: GraphNode,
        tgt: GraphNode,
        edge: GraphEdge,
        forbiddenLayer: String?
    ): LayerInversionViolation? {
        if (src.semioticLayer == "test" || tgt.semioticLayer == "test") return null

        if (forbiddenLayer != null && forbiddenLayer.isNotBlank()) {
            val normForbidden = forbiddenLayer.lowercase().trim()
            val tgtArch = tgt.archLayer.lowercase()
            val tgtSemiotic = tgt.semioticLayer.lowercase()
            val combined = "$tgtSemiotic:$tgtArch"

            if (tgtArch == normForbidden || tgtSemiotic == normForbidden || combined == normForbidden) {
                return LayerInversionViolation(
                    rule = "ARCH-LAYER-INVERSION",
                    source = src.qualifiedName.ifEmpty { src.name },
                    target = tgt.qualifiedName.ifEmpty { tgt.name },
                    edgeType = edge.edgeType,
                    lineNumber = edge.lineNumber,
                    message = "Symbol '${src.name}' illegally references forbidden layer '$forbiddenLayer' symbol '${tgt.name}' (${tgt.semioticLayer}:${tgt.archLayer}) via ${edge.edgeType} at line ${edge.lineNumber}."
                )
            }
        }

        if (src.archLayer == "domain" && tgt.archLayer in LOWER_TIER_LAYERS) {
            val layerDesc = if (tgt.archLayer == "presentation") "Presentation/Pragmatic" else "Data/Infrastructure"
            return LayerInversionViolation(
                rule = "ARCH-LAYER-INVERSION",
                source = src.qualifiedName.ifEmpty { src.name },
                target = tgt.qualifiedName.ifEmpty { tgt.name },
                edgeType = edge.edgeType,
                lineNumber = edge.lineNumber,
                message = "Domain symbol '${src.name}' (${src.archLayer}) illegally references $layerDesc symbol '${tgt.name}' (${tgt.archLayer}) via ${edge.edgeType} at line ${edge.lineNumber}."
            )
        }

        if (src.archLayer in NON_PRESENTATION_LAYERS && tgt.archLayer == "presentation") {
            return LayerInversionViolation(
                rule = "ARCH-LAYER-INVERSION",
                source = src.qualifiedName.ifEmpty { src.name },
                target = tgt.qualifiedName.ifEmpty { tgt.name },
                edgeType = edge.edgeType,
                lineNumber = edge.lineNumber,
                message = "Symbol '${src.name}' (${src.archLayer}) illegally references Presentation/Pragmatic symbol '${tgt.name}' (${tgt.archLayer}) via ${edge.edgeType} at line ${edge.lineNumber}."
            )
        }

        return null
    }

    private fun runBlastCeilingSpike(
        projectId: String,
        targetArg: String?,
        maxFiles: Int
    ): SpikeAnalysisResult {
        val target = targetArg?.trim().orEmpty()
        if (target.isEmpty()) {
            val msg = "FALSIFIED (target source '--target' is required for blast-ceiling spike)"
            return SpikeAnalysisResult(
                hypothesis = "blast-ceiling",
                projectId = projectId,
                status = "FALSIFIED",
                passed = false,
                message = msg,
                markdownBlock = renderMarkdownBlock("blast-ceiling", "FALSIFIED", msg, "Missing target")
            )
        }

        val allNodes = store.listNodes(projectId).associateBy { it.nodeId }
        val sourceNodes = resolveNodes(projectId, target, allNodes)
        val allEdges = store.listEdges(projectId).groupBy { it.sourceNodeId }

        val reachedFiles = mutableSetOf<String>()
        for (src in sourceNodes) {
            val f = src.fileId.substringAfter(':')
            if (f.isNotEmpty()) reachedFiles.add(f)
        }

        val visited = sourceNodes.map { it.nodeId }.toMutableSet()
        val queue = sourceNodes.map { it.nodeId }.toMutableList()

        var head = 0
        while (head < queue.size) {
            val currId = queue[head++]
            val edges = allEdges[currId] ?: emptyList()
            for (edge in edges) {
                val tgtNode = allNodes[edge.targetNodeId] ?: continue
                val f = tgtNode.fileId.substringAfter(':')
                if (f.isNotEmpty()) reachedFiles.add(f)

                if (visited.add(edge.targetNodeId)) {
                    queue.add(edge.targetNodeId)
                }
            }
        }

        if (reachedFiles.size > maxFiles) {
            val msg = "FALSIFIED (blast radius of ${reachedFiles.size} files exceeds ceiling of $maxFiles)"
            return SpikeAnalysisResult(
                hypothesis = "blast-ceiling",
                projectId = projectId,
                status = "FALSIFIED",
                passed = false,
                message = msg,
                markdownBlock = renderMarkdownBlock("blast-ceiling", "FALSIFIED", msg, "${reachedFiles.size} files touched")
            )
        }

        val msg = "CONFIRMED (blast radius of ${reachedFiles.size} files <= ceiling of $maxFiles)"
        return SpikeAnalysisResult(
            hypothesis = "blast-ceiling",
            projectId = projectId,
            status = "CONFIRMED",
            passed = true,
            message = msg,
            markdownBlock = renderMarkdownBlock("blast-ceiling", "CONFIRMED", msg, "${reachedFiles.size} files within ceiling of $maxFiles")
        )
    }

    private fun resolveNodes(
        projectId: String,
        target: String,
        allNodes: Map<String, GraphNode>
    ): List<GraphNode> {
        val includeDocs = target.endsWith(".md") || target.endsWith(".txt")
        val candidateNodes = if (includeDocs) allNodes.values.toList() else allNodes.values.filter { it.kind !in EXCLUDED_KINDS }

        allNodes[target]?.let { if (includeDocs || it.kind !in EXCLUDED_KINDS) return listOf(it) }

        val exactMatches = candidateNodes.filter { node ->
            node.name.equals(target, ignoreCase = true) ||
                node.qualifiedName.equals(target, ignoreCase = true) ||
                node.qualifiedName.endsWith(".$target", ignoreCase = true) ||
                node.moduleId.trimStart(':').equals(target.trimStart(':'), ignoreCase = true) ||
                node.fileId.endsWith(":$target") ||
                node.fileId.endsWith("/$target")
        }
        if (exactMatches.isNotEmpty()) {
            return exactMatches.distinctBy { it.nodeId }
        }

        val normTarget = target.filter { it.isLetterOrDigit() }.lowercase()
        val structuralMatches = candidateNodes.filter { node ->
            val relPath = if (node.fileId.contains(':')) node.fileId.substringAfter(':') else node.fileId
            val normName = node.name.filter { it.isLetterOrDigit() }.lowercase()
            val normQName = node.qualifiedName.filter { it.isLetterOrDigit() }.lowercase()

            node.qualifiedName.startsWith("$target.", ignoreCase = true) ||
                relPath.equals(target, ignoreCase = true) ||
                relPath.contains("/$target/") ||
                relPath.startsWith("$target/") ||
                (normTarget.length >= 3 && (normName == normTarget || normQName.endsWith(normTarget)))
        }

        return structuralMatches.distinctBy { it.nodeId }
    }

    private fun renderMarkdownBlock(
        hypothesis: String,
        status: String,
        message: String,
        evidence: String
    ): String = buildString {
        append("### Spike Analysis: ").append(hypothesis).append(" (").append(status).append(")\n\n")
        append("- **Hypothesis**: `").append(hypothesis).append("`\n")
        append("- **Status**: `").append(status).append("`\n")
        append("- **Observed Result**: ").append(message).append("\n")
        append("- **Evidence**: ").append(evidence).append("\n")
    }

    private data class EdgeHop(
        val sourceId: String,
        val targetId: String,
        val edgeType: String,
        val lineNumber: Int
    )

    companion object {
        private val LOWER_TIER_LAYERS = setOf("presentation", "data", "infrastructure")
        private val NON_PRESENTATION_LAYERS = setOf("application", "data", "infrastructure", "domain")
        private val EXCLUDED_KINDS = setOf(
            "file", "file_scope", "module", "markdown_doc", "markdown_note", "plan_contract", "obsidian_note", "tag"
        )
        private val EXCLUDED_EDGE_TYPES = setOf(
            "CONTAINS", "FILE_CONTAINS", "MODULE_CONTAINS", "PLAN_SCOPES_FILE", "SCOPES", "DOCUMENTS",
            "INDEXED_MARKER", "REFERENCES_FILE", "EXECUTES_SCRIPT", "TRIGGERS_SKILL"
        )
    }
}
