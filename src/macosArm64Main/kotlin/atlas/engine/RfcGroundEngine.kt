package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.extract.NameSegmentTokenizer
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.model.RfcGroundResult

/**
 * Engine extracting dense subsystem discovery tables (<= 800B) for RFC-0 research grounding.
 */
class RfcGroundEngine(
    private val store: SqliteGraphStore
) {

    fun groundSubsystem(projectId: String, query: String): RfcGroundResult {
        val cleanQuery = query.trim().trim('\'', '"')
        if (cleanQuery.isEmpty()) {
            val emptyTable = renderTable(cleanQuery, SubsystemGroundingTiers.EMPTY, 0)
            return RfcGroundResult(
                query = cleanQuery,
                projectId = projectId,
                matchedNodesCount = 0,
                domainContracts = emptyList(),
                afferentEntryPoints = emptyList(),
                efferentDependencies = emptyList(),
                syntacticTier = emptyList(),
                semanticTier = emptyList(),
                pragmaticTier = emptyList(),
                markdownTable = emptyTable,
                byteSize = emptyTable.encodeToByteArray().size
            )
        }

        val allNodesMap = store.listNodes(projectId).associateBy { it.nodeId }
        val matchingNodes = resolveMatchingNodes(projectId, cleanQuery, allNodesMap)
        val subsystemNodeIds = matchingNodes.map { it.nodeId }.toSet()
        val allEdges = store.listEdges(projectId)

        val domainContracts = extractDomainContracts(matchingNodes)
        val syntacticTier = extractSyntacticTier(matchingNodes)
        val semanticTier = extractSemanticTier(matchingNodes)
        val pragmaticTier = extractPragmaticTier(matchingNodes)

        val afferentEntryPoints = extractAfferentEntryPoints(matchingNodes, subsystemNodeIds, allEdges, allNodesMap)
        val efferentDependencies = extractEfferentDependencies(subsystemNodeIds, allEdges, allNodesMap)

        val tiers = SubsystemGroundingTiers(
            contracts = domainContracts,
            entryPoints = afferentEntryPoints,
            dependencies = efferentDependencies,
            syntactic = syntacticTier,
            semantic = semanticTier,
            pragmatic = pragmaticTier
        )

        var maxItems = 4
        var table = renderTable(cleanQuery, tiers, maxItems)

        while (table.encodeToByteArray().size > 800 && maxItems > 1) {
            maxItems--
            table = renderTable(cleanQuery, tiers, maxItems)
        }

        if (table.encodeToByteArray().size > 800) {
            table = truncateToBudget(cleanQuery, tiers.takeFirst(1))
        }

        val byteSize = table.encodeToByteArray().size

        return RfcGroundResult(
            query = cleanQuery,
            projectId = projectId,
            matchedNodesCount = matchingNodes.size,
            domainContracts = domainContracts,
            afferentEntryPoints = afferentEntryPoints,
            efferentDependencies = efferentDependencies,
            syntacticTier = syntacticTier,
            semanticTier = semanticTier,
            pragmaticTier = pragmaticTier,
            markdownTable = table,
            byteSize = byteSize
        )
    }

    private fun resolveMatchingNodes(
        projectId: String,
        query: String,
        allNodesMap: Map<String, GraphNode>
    ): List<GraphNode> {
        val candidates = mutableMapOf<String, GraphNode>()

        store.getNode(projectId, query)?.let { candidates[it.nodeId] = it }

        val searchMatches = store.searchNodes(projectId, query, limit = 50)
        for (node in searchMatches) {
            if (nodeMatchesSubsystem(node, query)) {
                candidates[node.nodeId] = node
            }
        }

        for (node in allNodesMap.values) {
            if (nodeMatchesSubsystem(node, query)) {
                candidates[node.nodeId] = node
            }
        }

        return candidates.values.filter { node ->
            node.semioticLayer != "test" &&
                node.archLayer != "test" &&
                !node.isTestDouble &&
                node.kind !in EXCLUDED_KINDS
        }
    }

    private fun matchesDirectQuery(node: GraphNode, relPath: String, query: String): Boolean =
        node.moduleId.contains(query, ignoreCase = true) ||
            relPath.contains(query, ignoreCase = true) ||
            node.name.contains(query, ignoreCase = true) ||
            node.qualifiedName.contains(query, ignoreCase = true) ||
            node.signature.contains(query, ignoreCase = true) ||
            (node.description?.contains(query, ignoreCase = true) == true)

    private fun matchesNormalizedQuery(node: GraphNode, relPath: String, query: String): Boolean {
        val normQuery = query.filter { it.isLetterOrDigit() }.lowercase()
        if (normQuery.length < 2) return false
        val nameNorm = node.name.filter { it.isLetterOrDigit() }
        val qNameNorm = node.qualifiedName.filter { it.isLetterOrDigit() }
        val pathNorm = relPath.filter { it.isLetterOrDigit() }
        val moduleNorm = node.moduleId.filter { it.isLetterOrDigit() }
        return nameNorm.contains(normQuery, ignoreCase = true) ||
            qNameNorm.contains(normQuery, ignoreCase = true) ||
            pathNorm.contains(normQuery, ignoreCase = true) ||
            moduleNorm.contains(normQuery, ignoreCase = true)
    }

    private fun matchesTokenizedQuery(node: GraphNode, relPath: String, query: String): Boolean {
        val tokens = NameSegmentTokenizer.tokenize(query)
        if (tokens.size < 2) return false
        return tokens.all { token ->
            node.name.contains(token, ignoreCase = true) ||
                node.qualifiedName.contains(token, ignoreCase = true) ||
                relPath.contains(token, ignoreCase = true) ||
                node.moduleId.contains(token, ignoreCase = true)
        }
    }

    private fun nodeMatchesSubsystem(node: GraphNode, query: String): Boolean {
        val relPath = if (node.fileId.contains(':')) node.fileId.substringAfter(':') else node.fileId
        return matchesDirectQuery(node, relPath, query) ||
            matchesNormalizedQuery(node, relPath, query) ||
            matchesTokenizedQuery(node, relPath, query)
    }

    private fun extractDomainContracts(nodes: List<GraphNode>): List<String> =
        nodes.filter { n ->
            n.kind in CONTRACT_KINDS &&
                (n.archLayer == "domain" || n.semioticLayer in setOf("syntactic", "semantic"))
        }
            .sortedWith(compareByDescending<GraphNode> { it.inDegree }.thenBy { it.name })
            .map { it.name }
            .distinct()

    private fun extractSyntacticTier(nodes: List<GraphNode>): List<String> =
        nodes.filter { n ->
            n.semioticLayer == "syntactic" ||
                n.kind in SYNTACTIC_KINDS ||
                (n.archLayer in setOf("domain", "data") && n.kind in CONTRACT_KINDS)
        }
            .sortedWith(compareByDescending<GraphNode> { it.inDegree }.thenBy { it.name })
            .map { it.name }
            .distinct()

    private fun extractSemanticTier(nodes: List<GraphNode>): List<String> =
        nodes.filter { n ->
            n.semioticLayer == "semantic" ||
                n.archLayer == "domain" ||
                n.kind in SEMANTIC_KINDS
        }
            .sortedWith(compareByDescending<GraphNode> { it.inDegree }.thenBy { it.name })
            .map { it.name }
            .distinct()

    private fun extractPragmaticTier(nodes: List<GraphNode>): List<String> =
        nodes.filter { n ->
            n.semioticLayer == "pragmatic" ||
                n.archLayer in setOf("presentation", "infrastructure") ||
                n.kind in PRAGMATIC_KINDS
        }
            .sortedWith(compareByDescending<GraphNode> { it.inDegree }.thenBy { it.name })
            .map { it.name }
            .distinct()

    private data class SubsystemGroundingTiers(
        val contracts: List<String>,
        val entryPoints: List<String>,
        val dependencies: List<String>,
        val syntactic: List<String>,
        val semantic: List<String>,
        val pragmatic: List<String>
    ) {
        companion object {
            val EMPTY = SubsystemGroundingTiers(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList()
            )
        }

        fun takeFirst(n: Int = 1): SubsystemGroundingTiers = SubsystemGroundingTiers(
            contracts = contracts.take(n),
            entryPoints = entryPoints.take(n),
            dependencies = dependencies.take(n),
            syntactic = syntactic.take(n),
            semantic = semantic.take(n),
            pragmatic = pragmatic.take(n)
        )
    }

    private fun isExcludedAfferentSource(node: GraphNode?): Boolean {
        if (node == null) return true
        return node.semioticLayer == "test" ||
            node.archLayer == "test" ||
            node.isTestDouble ||
            node.kind in EXCLUDED_KINDS
    }

    private fun extractAfferentEntryPoints(
        nodes: List<GraphNode>,
        subsystemNodeIds: Set<String>,
        allEdges: List<GraphEdge>,
        allNodesMap: Map<String, GraphNode>
    ): List<String> {
        val entryPoints = mutableMapOf<String, Int>()
        collectExternalIncomingEdges(allEdges, subsystemNodeIds, allNodesMap, entryPoints)
        collectFallbackEntryPoints(nodes, entryPoints)

        return entryPoints.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (name, inDeg) -> if (inDeg > 0) "$name (in: $inDeg)" else name }
    }

    private fun extractValidAfferentTarget(
        edge: GraphEdge,
        subsystemNodeIds: Set<String>,
        allNodesMap: Map<String, GraphNode>
    ): GraphNode? {
        if (edge.edgeType in EXCLUDED_EDGE_TYPES) return null
        if (edge.targetNodeId !in subsystemNodeIds || edge.sourceNodeId in subsystemNodeIds) return null
        if (isExcludedAfferentSource(allNodesMap[edge.sourceNodeId])) return null
        val tgtNode = allNodesMap[edge.targetNodeId] ?: return null
        return if (tgtNode.kind in EXCLUDED_KINDS) null else tgtNode
    }

    private fun collectExternalIncomingEdges(
        allEdges: List<GraphEdge>,
        subsystemNodeIds: Set<String>,
        allNodesMap: Map<String, GraphNode>,
        entryPoints: MutableMap<String, Int>
    ) {
        for (edge in allEdges) {
            val tgt = extractValidAfferentTarget(edge, subsystemNodeIds, allNodesMap) ?: continue
            entryPoints[tgt.name] = (entryPoints[tgt.name] ?: 0) + 1
        }
    }

    private fun collectFallbackEntryPoints(
        nodes: List<GraphNode>,
        entryPoints: MutableMap<String, Int>
    ) {
        for (node in nodes) {
            if (node.inDegree > 0 && node.name !in entryPoints) {
                entryPoints[node.name] = node.inDegree
            } else if (node.isTopLevel && node.kind in ENTRY_POINT_KINDS && node.name !in entryPoints) {
                entryPoints[node.name] = 0
            }
        }
    }

    private fun resolveDependencyTargetName(edge: GraphEdge, allNodesMap: Map<String, GraphNode>): String? {
        val targetNode = allNodesMap[edge.targetNodeId]
        if (targetNode != null && (targetNode.semioticLayer == "test" || targetNode.kind in EXCLUDED_KINDS)) {
            return null
        }
        val targetName = targetNode?.name ?: (if (edge.targetNodeId.contains('#')) edge.targetNodeId.substringAfter('#') else edge.targetNodeId)
        if (targetName.isEmpty() || targetName.endsWith(".md") || targetName.endsWith(".txt")) {
            return null
        }
        return targetName
    }

    private fun extractEfferentDependencies(
        subsystemNodeIds: Set<String>,
        allEdges: List<GraphEdge>,
        allNodesMap: Map<String, GraphNode>
    ): List<String> {
        val deps = mutableSetOf<String>()
        for (edge in allEdges) {
            if (edge.edgeType in EXCLUDED_EDGE_TYPES) continue
            if (edge.sourceNodeId in subsystemNodeIds && edge.targetNodeId !in subsystemNodeIds) {
                resolveDependencyTargetName(edge, allNodesMap)?.let { deps.add(it) }
            }
        }
        return deps.sorted()
    }

    private fun renderTable(
        query: String,
        tiers: SubsystemGroundingTiers,
        maxItems: Int
    ): String {
        val formatList: (List<String>) -> String = { list ->
            if (list.isEmpty()) {
                "(none)"
            } else {
                val taken = list.take(maxItems)
                val base = taken.joinToString(", ") { "`$it`" }
                if (list.size > maxItems) "$base (+${list.size - maxItems} more)" else base
            }
        }

        return buildString {
            append("| Dimension | Subsystem Grounding (`").append(query.ifEmpty { "all" }).append("`) |\n")
            append("| :--- | :--- |\n")
            append("| **Domain Contracts** | ").append(formatList(tiers.contracts)).append(" |\n")
            append("| **Afferent Entry Points** | ").append(formatList(tiers.entryPoints)).append(" |\n")
            append("| **Efferent Dependencies** | ").append(formatList(tiers.dependencies)).append(" |\n")
            append("| **Syntactic Tier** | ").append(formatList(tiers.syntactic)).append(" |\n")
            append("| **Semantic Tier** | ").append(formatList(tiers.semantic)).append(" |\n")
            append("| **Pragmatic Tier** | ").append(formatList(tiers.pragmatic)).append(" |\n")
        }
    }

    private fun truncateToBudget(
        query: String,
        tiers: SubsystemGroundingTiers
    ): String {
        val shorten: (String) -> String = { s -> if (s.length > 25) s.take(22) + "..." else s }
        val fmt: (List<String>) -> String = { l ->
            if (l.isEmpty()) "(none)" else "`" + shorten(l.first()) + "`"
        }
        val safeQuery = shorten(query.ifEmpty { "all" })
        return buildString {
            append("| Dimension | Subsystem Grounding (`").append(safeQuery).append("`) |\n")
            append("| :--- | :--- |\n")
            append("| **Domain Contracts** | ").append(fmt(tiers.contracts)).append(" |\n")
            append("| **Afferent Entry Points** | ").append(fmt(tiers.entryPoints)).append(" |\n")
            append("| **Efferent Dependencies** | ").append(fmt(tiers.dependencies)).append(" |\n")
            append("| **Syntactic Tier** | ").append(fmt(tiers.syntactic)).append(" |\n")
            append("| **Semantic Tier** | ").append(fmt(tiers.semantic)).append(" |\n")
            append("| **Pragmatic Tier** | ").append(fmt(tiers.pragmatic)).append(" |\n")
        }
    }

    companion object {
        private val EXCLUDED_KINDS = setOf(
            "file", "file_scope", "module", "markdown_doc", "markdown_note", "plan_contract", "obsidian_note", "tag"
        )
        private val EXCLUDED_EDGE_TYPES = setOf(
            "CONTAINS", "FILE_CONTAINS", "MODULE_CONTAINS", "PLAN_SCOPES_FILE", "SCOPES", "DOCUMENTS", "INDEXED_MARKER"
        )
        private val CONTRACT_KINDS = setOf("interface", "class", "data_class", "struct", "model")
        private val SYNTACTIC_KINDS = setOf("interface", "data_class", "struct", "typealias", "enum", "annotation")
        private val SEMANTIC_KINDS = setOf("class", "object", "service", "engine", "repository", "use_case", "manager")
        private val PRAGMATIC_KINDS = setOf("composable", "viewmodel", "cli", "command", "controller", "endpoint", "adapter", "client")
        private val ENTRY_POINT_KINDS = setOf("class", "interface", "function", "composable", "object")
    }
}
