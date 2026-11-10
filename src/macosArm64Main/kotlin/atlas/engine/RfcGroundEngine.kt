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
            val emptyTable = renderTable(cleanQuery, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0)
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

        var maxItems = 4
        var table = renderTable(
            query = cleanQuery,
            contracts = domainContracts,
            entryPoints = afferentEntryPoints,
            dependencies = efferentDependencies,
            syntactic = syntacticTier,
            semantic = semanticTier,
            pragmatic = pragmaticTier,
            maxItems = maxItems
        )

        while (table.encodeToByteArray().size > 800 && maxItems > 1) {
            maxItems--
            table = renderTable(
                query = cleanQuery,
                contracts = domainContracts,
                entryPoints = afferentEntryPoints,
                dependencies = efferentDependencies,
                syntactic = syntacticTier,
                semantic = semanticTier,
                pragmatic = pragmaticTier,
                maxItems = maxItems
            )
        }

        if (table.encodeToByteArray().size > 800) {
            table = truncateToBudget(
                query = cleanQuery,
                contracts = domainContracts.take(1),
                entryPoints = afferentEntryPoints.take(1),
                dependencies = efferentDependencies.take(1),
                syntactic = syntacticTier.take(1),
                semantic = semanticTier.take(1),
                pragmatic = pragmaticTier.take(1)
            )
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

    private fun nodeMatchesSubsystem(node: GraphNode, query: String): Boolean {
        val relPath = if (node.fileId.contains(':')) node.fileId.substringAfter(':') else node.fileId
        if (node.moduleId.contains(query, ignoreCase = true) ||
            relPath.contains(query, ignoreCase = true) ||
            node.name.contains(query, ignoreCase = true) ||
            node.qualifiedName.contains(query, ignoreCase = true) ||
            node.signature.contains(query, ignoreCase = true) ||
            (node.description?.contains(query, ignoreCase = true) == true)
        ) {
            return true
        }

        val normQuery = query.filter { it.isLetterOrDigit() }.lowercase()
        if (normQuery.length >= 2) {
            val nameNorm = node.name.filter { it.isLetterOrDigit() }
            val qNameNorm = node.qualifiedName.filter { it.isLetterOrDigit() }
            val pathNorm = relPath.filter { it.isLetterOrDigit() }
            val moduleNorm = node.moduleId.filter { it.isLetterOrDigit() }

            if (nameNorm.contains(normQuery, ignoreCase = true) ||
                qNameNorm.contains(normQuery, ignoreCase = true) ||
                pathNorm.contains(normQuery, ignoreCase = true) ||
                moduleNorm.contains(normQuery, ignoreCase = true)
            ) {
                return true
            }
        }

        val tokens = NameSegmentTokenizer.tokenize(query)
        if (tokens.size >= 2) {
            val allPresent = tokens.all { token ->
                node.name.contains(token, ignoreCase = true) ||
                    node.qualifiedName.contains(token, ignoreCase = true) ||
                    relPath.contains(token, ignoreCase = true) ||
                    node.moduleId.contains(token, ignoreCase = true)
            }
            if (allPresent) return true
        }

        return false
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

    private fun extractAfferentEntryPoints(
        nodes: List<GraphNode>,
        subsystemNodeIds: Set<String>,
        allEdges: List<GraphEdge>,
        allNodesMap: Map<String, GraphNode>
    ): List<String> {
        val entryPoints = mutableMapOf<String, Int>()

        for (edge in allEdges) {
            if (edge.edgeType in EXCLUDED_EDGE_TYPES) continue
            if (edge.targetNodeId in subsystemNodeIds && edge.sourceNodeId !in subsystemNodeIds) {
                val srcNode = allNodesMap[edge.sourceNodeId]
                if (srcNode != null && (srcNode.semioticLayer == "test" || srcNode.archLayer == "test" || srcNode.isTestDouble || srcNode.kind in EXCLUDED_KINDS)) {
                    continue
                }
                val tgtNode = allNodesMap[edge.targetNodeId]
                if (tgtNode != null && tgtNode.kind !in EXCLUDED_KINDS) {
                    entryPoints[tgtNode.name] = (entryPoints[tgtNode.name] ?: 0) + 1
                }
            }
        }

        for (node in nodes) {
            if (node.inDegree > 0 && node.name !in entryPoints) {
                entryPoints[node.name] = node.inDegree
            } else if (node.isTopLevel && node.kind in ENTRY_POINT_KINDS && node.name !in entryPoints) {
                entryPoints[node.name] = 0
            }
        }

        return entryPoints.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (name, inDeg) -> if (inDeg > 0) "$name (in: $inDeg)" else name }
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
                val targetNode = allNodesMap[edge.targetNodeId]
                if (targetNode != null && (targetNode.semioticLayer == "test" || targetNode.kind in EXCLUDED_KINDS)) {
                    continue
                }
                val targetName = targetNode?.name ?: (if (edge.targetNodeId.contains('#')) edge.targetNodeId.substringAfter('#') else edge.targetNodeId)
                if (targetName.isNotEmpty() && !targetName.endsWith(".md") && !targetName.endsWith(".txt")) {
                    deps.add(targetName)
                }
            }
        }

        return deps.sorted()
    }

    private fun renderTable(
        query: String,
        contracts: List<String>,
        entryPoints: List<String>,
        dependencies: List<String>,
        syntactic: List<String>,
        semantic: List<String>,
        pragmatic: List<String>,
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
            append("| **Domain Contracts** | ").append(formatList(contracts)).append(" |\n")
            append("| **Afferent Entry Points** | ").append(formatList(entryPoints)).append(" |\n")
            append("| **Efferent Dependencies** | ").append(formatList(dependencies)).append(" |\n")
            append("| **Syntactic Tier** | ").append(formatList(syntactic)).append(" |\n")
            append("| **Semantic Tier** | ").append(formatList(semantic)).append(" |\n")
            append("| **Pragmatic Tier** | ").append(formatList(pragmatic)).append(" |\n")
        }
    }

    private fun truncateToBudget(
        query: String,
        contracts: List<String>,
        entryPoints: List<String>,
        dependencies: List<String>,
        syntactic: List<String>,
        semantic: List<String>,
        pragmatic: List<String>
    ): String {
        val shorten: (String) -> String = { s -> if (s.length > 25) s.take(22) + "..." else s }
        val fmt: (List<String>) -> String = { l ->
            if (l.isEmpty()) "(none)" else "`" + shorten(l.first()) + "`"
        }
        val safeQuery = shorten(query.ifEmpty { "all" })
        return buildString {
            append("| Dimension | Subsystem Grounding (`").append(safeQuery).append("`) |\n")
            append("| :--- | :--- |\n")
            append("| **Domain Contracts** | ").append(fmt(contracts)).append(" |\n")
            append("| **Afferent Entry Points** | ").append(fmt(entryPoints)).append(" |\n")
            append("| **Efferent Dependencies** | ").append(fmt(dependencies)).append(" |\n")
            append("| **Syntactic Tier** | ").append(fmt(syntactic)).append(" |\n")
            append("| **Semantic Tier** | ").append(fmt(semantic)).append(" |\n")
            append("| **Pragmatic Tier** | ").append(fmt(pragmatic)).append(" |\n")
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
