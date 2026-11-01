package atlas.db

import atlas.extract.ComplexityExtractor
import atlas.extract.UrlTargetResolver
import atlas.model.ComplexityScoreRecord
import atlas.model.GraphEdge
import atlas.model.GraphHop
import atlas.model.GraphNode
import atlas.model.ImpactRadiusResult
import atlas.model.LiveSourceSlice
import atlas.util.PlatformFiles
import atlas.util.Sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Internal DAO encapsulating graph traversal (callers, callees, impact radius, shortest path),
 * URL/route resolution, live source freshness slicing, and complexity risk queries for [SqliteGraphStore].
 */
internal class GraphTraversalDao(
    private val db: NativeSqliteDatabase,
    private val figmaDao: FigmaGraphStoreDao,
    private val nodeLookup: (String, String) -> GraphNode?,
    private val fileNodesLookup: (String, String) -> List<GraphNode>,
    private val rowToNode: (NativeSqliteStatement) -> GraphNode
) {

    fun getCallers(projectId: String, nodeId: String, depth: Int = 2): List<GraphHop> {
        val maxDepth = if (depth < 1) 1 else depth
        val stmt = db.prepare(
            """
            WITH RECURSIVE caller_chain(source_id, target_id, edge_type, provenance, hop, path) AS (
                SELECT source_node_id, target_node_id, edge_type, provenance, 1,
                       ',' || source_node_id || ',' || target_node_id || ','
                FROM edges
                WHERE project_id = ? AND target_node_id = ?
                UNION ALL
                SELECT e.source_node_id, e.target_node_id, e.edge_type, e.provenance, c.hop + 1,
                       c.path || e.source_node_id || ','
                FROM edges e
                JOIN caller_chain c ON e.target_node_id = c.source_id
                WHERE e.project_id = ? AND c.hop < ?
                  AND instr(c.path, ',' || e.source_node_id || ',') = 0
            )
            SELECT DISTINCT c.source_id, c.target_id, c.edge_type, c.provenance, MIN(c.hop) AS hop,
                            COALESCE(n.name, ''), COALESCE(n.file_id, ''), COALESCE(n.start_line, 1),
                            COALESCE(n.semiotic_layer, 'semantic'), COALESCE(n.arch_layer, 'domain')
            FROM caller_chain c
            LEFT JOIN nodes n ON n.node_id = c.source_id
            GROUP BY c.source_id, c.target_id, c.edge_type
            ORDER BY hop ASC;
            """.trimIndent()
        )
        val results = mutableListOf<GraphHop>()
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, nodeId)
            stmt.bindString(3, projectId)
            stmt.bindInt(4, maxDepth)
            while (stmt.step()) {
                results.add(rowToGraphHop(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun getCallees(projectId: String, nodeId: String, depth: Int = 2): List<GraphHop> {
        val maxDepth = if (depth < 1) 1 else depth
        val stmt = db.prepare(
            """
            WITH RECURSIVE callee_chain(source_id, target_id, edge_type, provenance, hop, path) AS (
                SELECT source_node_id, target_node_id, edge_type, provenance, 1,
                       ',' || source_node_id || ',' || target_node_id || ','
                FROM edges
                WHERE project_id = ? AND source_node_id = ?
                UNION ALL
                SELECT e.source_node_id, e.target_node_id, e.edge_type, e.provenance, c.hop + 1,
                       c.path || e.target_node_id || ','
                FROM edges e
                JOIN callee_chain c ON e.source_node_id = c.target_id
                WHERE e.project_id = ? AND c.hop < ?
                  AND instr(c.path, ',' || e.target_node_id || ',') = 0
            )
            SELECT DISTINCT c.source_id, c.target_id, c.edge_type, c.provenance, MIN(c.hop) AS hop,
                            COALESCE(n.name, ''), COALESCE(n.file_id, ''), COALESCE(n.start_line, 1),
                            COALESCE(n.semiotic_layer, 'semantic'), COALESCE(n.arch_layer, 'domain')
            FROM callee_chain c
            LEFT JOIN nodes n ON n.node_id = c.target_id
            GROUP BY c.source_id, c.target_id, c.edge_type
            ORDER BY hop ASC;
            """.trimIndent()
        )
        val results = mutableListOf<GraphHop>()
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, nodeId)
            stmt.bindString(3, projectId)
            stmt.bindInt(4, maxDepth)
            while (stmt.step()) {
                results.add(rowToGraphHop(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    private fun rowToGraphHop(stmt: NativeSqliteStatement): GraphHop {
        return GraphHop(
            sourceId = stmt.columnString(0),
            targetId = stmt.columnString(1),
            edgeType = stmt.columnString(2),
            provenance = stmt.columnString(3),
            hop = stmt.columnInt(4),
            name = stmt.columnString(5),
            fileId = stmt.columnString(6),
            startLine = stmt.columnInt(7),
            semioticLayer = stmt.columnString(8),
            archLayer = stmt.columnString(9)
        )
    }

    fun getImpactRadius(projectId: String, nodeId: String, depth: Int = 2): ImpactRadiusResult {
        val callers = getCallers(projectId, nodeId, depth)
        val callees = getCallees(projectId, nodeId, depth)
        val impactedIds = collectImpactedNodeIds(nodeId, callers, callees)
        val nodes = fetchNodesByChunks(projectId, impactedIds)

        val files = nodes.mapNotNull { n ->
            if (n.fileId.isNotEmpty()) n.fileId.substringAfter(":") else null
        }.distinct().sorted()

        val modules = nodes.mapNotNull { n ->
            if (n.moduleId.isNotEmpty()) n.moduleId else null
        }.distinct().sorted()

        val layers = nodes.map { "${it.semioticLayer}:${it.archLayer}" }.distinct().sorted()

        return ImpactRadiusResult(
            seedNodeId = nodeId,
            depth = depth,
            impactedNodes = nodes,
            impactedFiles = files,
            impactedModules = modules,
            impactedLayers = layers,
            callers = callers,
            callees = callees
        )
    }

    private fun collectImpactedNodeIds(
        seedNodeId: String,
        callers: List<GraphHop>,
        callees: List<GraphHop>
    ): Set<String> {
        val impactedIds = mutableSetOf(seedNodeId)
        for (item in callers) {
            if (item.sourceId.isNotEmpty()) impactedIds.add(item.sourceId)
        }
        for (item in callees) {
            if (item.targetId.isNotEmpty()) impactedIds.add(item.targetId)
        }
        return impactedIds
    }

    private fun fetchNodesByChunks(projectId: String, impactedIds: Set<String>): List<GraphNode> {
        val nodes = mutableListOf<GraphNode>()
        for (chunk in impactedIds.chunked(250)) {
            fetchNodeChunk(projectId, chunk, nodes)
        }
        return nodes
    }

    private fun fetchNodeChunk(projectId: String, chunk: List<String>, outNodes: MutableList<GraphNode>) {
        val placeholders = chunk.joinToString(",") { "?" }
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree, description, is_top_level,
                   cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
            FROM nodes WHERE project_id = ? AND node_id IN ($placeholders);
            """.trimIndent()
        )
        try {
            stmt.bindString(1, projectId)
            chunk.forEachIndexed { i, id -> stmt.bindString(2 + i, id) }
            while (stmt.step()) {
                outNodes.add(rowToNode(stmt))
            }
        } finally {
            stmt.close()
        }
    }

    fun getGodNodes(projectId: String, topN: Int = 10): List<GraphNode> {
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree, description, is_top_level,
                   cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
            FROM nodes
            WHERE project_id = ? AND kind NOT IN ('file', 'file_scope', 'module', 'plan_contract', 'obsidian_note', 'endpoint', 'url_ref', 'tag', 'skill', 'slash_command')
            ORDER BY (in_degree * 2 + out_degree) DESC, in_degree DESC
            LIMIT ?;
            """.trimIndent()
        )
        val results = mutableListOf<GraphNode>()
        try {
            stmt.bindString(1, projectId)
            stmt.bindInt(2, topN)
            while (stmt.step()) {
                results.add(rowToNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    private fun resolveCodeLocationNodes(
        projectId: String,
        query: String,
        codeLink: UrlTargetResolver.CodeLocationLink
    ): List<GraphNode> =
        if (codeLink.startLine != null || codeLink.symbolFragment != null) {
            listOfNotNull(nodeLookup(projectId, query))
        } else {
            fileNodesLookup(projectId, codeLink.filePathSuffix)
        }

    private fun queryMatchedEndpoints(
        projectId: String,
        canonRoute: String,
        limit: Int,
        outMatched: MutableMap<String, GraphNode>
    ) {
        val epNodeId = "$projectId:endpoint#$canonRoute"
        nodeLookup(projectId, epNodeId)?.let { outMatched[it.nodeId] = it }
        val escRoute = canonRoute.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val epStmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree, description, is_top_level,
                   cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
            FROM nodes
            WHERE project_id = ? AND kind = 'endpoint'
              AND (node_id = ? OR name = ? OR name LIKE ? ESCAPE '\')
            ORDER BY (name = ?) DESC, in_degree DESC
            LIMIT ?;
            """.trimIndent()
        )
        try {
            epStmt.bindString(1, projectId)
            epStmt.bindString(2, epNodeId)
            epStmt.bindString(3, canonRoute)
            epStmt.bindString(4, "%$escRoute%")
            epStmt.bindString(5, canonRoute)
            epStmt.bindInt(6, limit)
            while (epStmt.step()) {
                val n = rowToNode(epStmt)
                outMatched[n.nodeId] = n
            }
        } finally {
            epStmt.close()
        }
    }

    private fun queryMatchedUrlEdges(
        projectId: String,
        canonRouteAndUrl: Pair<String?, String>,
        limit: Int,
        outMatched: MutableMap<String, GraphNode>
    ) {
        val (canonRoute, canonUrl) = canonRouteAndUrl
        val escUrl = canonUrl.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val edgeStmt = db.prepare(
            """
            SELECT DISTINCT n.node_id, n.project_id, n.file_id, n.name, n.qualified_name, n.kind, n.signature,
                            n.start_line, n.end_line, n.module_id, n.source_set, n.semiotic_layer, n.arch_layer,
                            n.is_test_double, n.in_degree, n.out_degree, n.description, n.is_top_level,
                            n.cyclomatic_complexity, n.cognitive_complexity, n.max_nesting_depth, n.complexity_score
            FROM edges e
            JOIN nodes n ON n.node_id = e.source_node_id AND n.project_id = e.project_id
            WHERE e.project_id = ?
              AND e.edge_type IN ('EXPOSES_ENDPOINT', 'CALLS_ENDPOINT', 'URL_REFS')
              AND (e.target_node_id = ? OR e.target_node_id = ? OR e.target_node_id LIKE ? ESCAPE '\')
            LIMIT ?;
            """.trimIndent()
        )
        try {
            edgeStmt.bindString(1, projectId)
            edgeStmt.bindString(2, if (canonRoute != null) "$projectId:endpoint#$canonRoute" else "$projectId:url_ref#$canonUrl")
            edgeStmt.bindString(3, "$projectId:url_ref#$canonUrl")
            edgeStmt.bindString(4, "%$escUrl%")
            edgeStmt.bindInt(5, limit)
            while (edgeStmt.step()) {
                val n = rowToNode(edgeStmt)
                outMatched[n.nodeId] = n
            }
        } finally {
            edgeStmt.close()
        }
    }

    fun resolveNodesByUrlOrRoute(projectId: String, rawUrlOrRoute: String, limit: Int = 25): List<GraphNode> {
        val trimmed = rawUrlOrRoute.trim().trim('\'', '"')
        if (trimmed.isEmpty()) return emptyList()

        val codeLink = UrlTargetResolver.parseCodeLocationUrl(trimmed)
        if (codeLink != null) {
            return resolveCodeLocationNodes(projectId, trimmed, codeLink)
        }

        val matchedNodes = LinkedHashMap<String, GraphNode>()
        val canonRoute = resolveCanonicalRoute(trimmed)

        if (canonRoute != null) {
            queryMatchedEndpoints(projectId, canonRoute, limit, matchedNodes)
        }

        val canonUrl = UrlTargetResolver.normalizeExternalUrl(trimmed)
        queryMatchedUrlEdges(projectId, canonRoute to canonUrl, limit, matchedNodes)

        return matchedNodes.values.toList()
    }

    private fun searchFigmaDeepLink(
        projectAndQuery: Pair<String, String>,
        figmaLink: UrlTargetResolver.FigmaDeepLink,
        limit: Int,
        jsonCodec: Json
    ): JsonObject {
        val (projectId, query) = projectAndQuery
        val figmaNodes = if (figmaLink.nodeId != null) {
            listOfNotNull(figmaDao.queryFigmaNodeById(figmaLink.fileKey, figmaLink.nodeId))
        } else {
            figmaDao.queryFigmaNodesForFile(figmaLink.fileKey, limit)
        }
        val escFileKey = figmaLink.fileKey.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val refEdges = queryUrlEdgeRecords(projectId, "%$escFileKey%", limit)
        return buildJsonObject {
            put("project", projectId)
            put("query", query)
            put("kind", "figma_deep_link")
            put("file_key", figmaLink.fileKey)
            if (figmaLink.nodeId != null) put("node_id", figmaLink.nodeId)
            put("figma_nodes", jsonCodec.encodeToJsonElement(figmaNodes))
            putJsonArray("document_references") {
                for ((srcNode, edge) in refEdges) {
                    addJsonObject {
                        put("source_node_id", srcNode.nodeId)
                        put("name", srcNode.name)
                        put("file_id", srcNode.fileId)
                        put("line", edge.lineNumber)
                        put("edge_type", edge.edgeType)
                        put("target", edge.targetNodeId)
                    }
                }
            }
        }
    }

    private fun searchCodeLocationLink(
        projectId: String,
        query: String,
        codeLink: UrlTargetResolver.CodeLocationLink,
        jsonCodec: Json
    ): JsonObject {
        val resolvedNode = nodeLookup(projectId, query)
        val callers = if (resolvedNode != null) getCallers(projectId, resolvedNode.nodeId, depth = 2) else emptyList()
        val callees = if (resolvedNode != null) getCallees(projectId, resolvedNode.nodeId, depth = 2) else emptyList()
        return buildJsonObject {
            put("project", projectId)
            put("query", query)
            put("kind", "code_location_link")
            put("file_path_suffix", codeLink.filePathSuffix)
            if (codeLink.startLine != null) put("start_line", codeLink.startLine)
            if (codeLink.endLine != null) put("end_line", codeLink.endLine)
            if (resolvedNode != null) put("node", jsonCodec.encodeToJsonElement(resolvedNode))
            put("callers", jsonCodec.encodeToJsonElement(callers))
            put("callees", jsonCodec.encodeToJsonElement(callees))
        }
    }

    private fun resolveCanonicalRoute(trimmed: String): String? {
        val isRoute = UrlTargetResolver.isHttpRouteCandidate(trimmed, hasExplicitRouteCall = true) || trimmed.startsWith("/")
        val extractedPath = UrlTargetResolver.extractPathFromHttpUrl(trimmed)
        return when {
            isRoute -> UrlTargetResolver.normalizeRouteTemplate(trimmed)
            extractedPath != null && UrlTargetResolver.isHttpRouteCandidate(extractedPath, hasExplicitRouteCall = false) ->
                UrlTargetResolver.normalizeRouteTemplate(extractedPath)
            else -> null
        }
    }

    private fun queryEndpointNodes(projectId: String, canonRoute: String, limit: Int): List<GraphNode> {
        val exactId = "$projectId:endpoint#$canonRoute"
        val escRoute = canonRoute.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val epStmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree, description, is_top_level,
                   cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
            FROM nodes
            WHERE project_id = ? AND kind = 'endpoint'
              AND (node_id = ? OR name = ? OR name LIKE ? ESCAPE '\')
            ORDER BY (name = ?) DESC, in_degree DESC
            LIMIT ?;
            """.trimIndent()
        )
        val endpointNodes = mutableListOf<GraphNode>()
        try {
            epStmt.bindString(1, projectId)
            epStmt.bindString(2, exactId)
            epStmt.bindString(3, canonRoute)
            epStmt.bindString(4, "%$escRoute%")
            epStmt.bindString(5, canonRoute)
            epStmt.bindInt(6, limit)
            while (epStmt.step()) endpointNodes.add(rowToNode(epStmt))
        } finally {
            epStmt.close()
        }
        return endpointNodes
    }

    private fun searchEndpointOrExternalUrl(
        projectId: String,
        trimmed: String,
        limit: Int,
        jsonCodec: Json
    ): JsonObject {
        val canonRoute = resolveCanonicalRoute(trimmed)
        val canonUrl = UrlTargetResolver.normalizeExternalUrl(trimmed)
        val endpointNodes = if (canonRoute != null) queryEndpointNodes(projectId, canonRoute, limit) else emptyList()

        val targetIds = mutableSetOf<String>()
        endpointNodes.forEach { targetIds.add(it.nodeId) }
        if (canonRoute != null) targetIds.add("$projectId:endpoint#$canonRoute")
        targetIds.add("$projectId:url_ref#$canonUrl")
        targetIds.add("url_ref#$canonUrl")

        val likeSource = canonRoute ?: canonUrl
        val likePattern = "%" + likeSource.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val edgeMatches = queryUrlEdgeRecords(projectId, likePattern, limit * 3, targetIds)
        val producers = edgeMatches.filter { it.second.edgeType == "EXPOSES_ENDPOINT" }
        val consumers = edgeMatches.filter { it.second.edgeType == "CALLS_ENDPOINT" }
        val docRefs = edgeMatches.filter { it.second.edgeType == "URL_REFS" }

        return buildJsonObject {
            put("project", projectId)
            put("query", trimmed)
            put("kind", if (canonRoute != null) "http_endpoint" else "external_url")
            if (canonRoute != null) put("canonical_route", canonRoute)
            put("normalized_url", canonUrl)
            put("endpoints", jsonCodec.encodeToJsonElement(endpointNodes))
            putJsonArray("producers") {
                for ((src, edge) in producers) {
                    addJsonObject {
                        put("node_id", src.nodeId); put("name", src.name); put("qualified_name", src.qualifiedName)
                        put("file_id", src.fileId); put("line", edge.lineNumber); put("target", edge.targetNodeId)
                    }
                }
            }
            putJsonArray("consumers") {
                for ((src, edge) in consumers) {
                    addJsonObject {
                        put("node_id", src.nodeId); put("name", src.name); put("qualified_name", src.qualifiedName)
                        put("file_id", src.fileId); put("line", edge.lineNumber); put("target", edge.targetNodeId)
                    }
                }
            }
            putJsonArray("document_references") {
                for ((src, edge) in docRefs) {
                    addJsonObject {
                        put("node_id", src.nodeId); put("name", src.name); put("qualified_name", src.qualifiedName)
                        put("file_id", src.fileId); put("kind", src.kind); put("line", edge.lineNumber); put("target", edge.targetNodeId)
                    }
                }
            }
        }
    }

    fun searchByUrl(projectId: String, rawUrlOrRoute: String, limit: Int = 20): JsonObject {
        val jsonCodec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val trimmed = rawUrlOrRoute.trim().trim('\'', '"')

        val figmaLink = UrlTargetResolver.parseFigmaUrl(trimmed)
        if (figmaLink != null) {
            return searchFigmaDeepLink(projectId to trimmed, figmaLink, limit, jsonCodec)
        }

        val codeLink = UrlTargetResolver.parseCodeLocationUrl(trimmed)
        if (codeLink != null) {
            return searchCodeLocationLink(projectId, trimmed, codeLink, jsonCodec)
        }

        return searchEndpointOrExternalUrl(projectId, trimmed, limit, jsonCodec)
    }

    private fun queryUrlEdgeRecords(
        projectId: String,
        likePattern: String,
        limit: Int,
        exactTargetIds: Set<String> = emptySet()
    ): List<Pair<GraphNode, GraphEdge>> {
        val placeholders = if (exactTargetIds.isNotEmpty()) exactTargetIds.joinToString(",") { "?" } else ""
        val inClause = if (exactTargetIds.isNotEmpty()) "e.target_node_id IN ($placeholders) OR " else ""
        val stmt = db.prepare(
            """
            SELECT n.node_id, n.project_id, n.file_id, n.name, n.qualified_name, n.kind, n.signature,
                   n.start_line, n.end_line, n.module_id, n.source_set, n.semiotic_layer, n.arch_layer,
                   n.is_test_double, n.in_degree, n.out_degree, n.description, n.is_top_level,
                   n.cyclomatic_complexity, n.cognitive_complexity, n.max_nesting_depth, n.complexity_score,
                   e.edge_id, e.project_id, e.source_node_id, e.target_node_id, e.edge_type, e.provenance, e.confidence, e.line_number
            FROM edges e
            JOIN nodes n ON n.node_id = e.source_node_id AND n.project_id = e.project_id
            WHERE e.project_id = ?
              AND e.edge_type IN ('EXPOSES_ENDPOINT', 'CALLS_ENDPOINT', 'URL_REFS')
              AND ($inClause e.target_node_id LIKE ? ESCAPE '\')
            ORDER BY e.edge_type ASC, e.line_number ASC
            LIMIT ?;
            """.trimIndent()
        )
        val results = mutableListOf<Pair<GraphNode, GraphEdge>>()
        try {
            var bindIdx = 1
            stmt.bindString(bindIdx++, projectId)
            for (tid in exactTargetIds) stmt.bindString(bindIdx++, tid)
            stmt.bindString(bindIdx++, likePattern)
            stmt.bindInt(bindIdx, limit)
            while (stmt.step()) {
                val node = rowToNode(stmt)
                val edge = GraphEdge(
                    edgeId = stmt.columnString(22), projectId = stmt.columnString(23),
                    sourceNodeId = stmt.columnString(24), targetNodeId = stmt.columnString(25),
                    edgeType = stmt.columnString(26), provenance = stmt.columnString(27),
                    confidence = stmt.columnDouble(28), lineNumber = stmt.columnInt(29)
                )
                results.add(Pair(node, edge))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun getShortestPath(
        projectId: String,
        sourceNodeId: String,
        targetNodeId: String,
        maxHops: Int = 6
    ): List<String> {
        if (sourceNodeId == targetNodeId) return listOf(sourceNodeId)

        val edgeStmt = db.prepare(
            """
            SELECT target_node_id FROM edges
            WHERE project_id = ? AND source_node_id = ?
            """.trimIndent()
        )
        return try {
            bfsShortestPath(edgeStmt, projectId, sourceNodeId to targetNodeId, maxHops)
        } finally {
            edgeStmt.close()
        }
    }

    private fun bfsShortestPath(
        edgeStmt: NativeSqliteStatement,
        projectId: String,
        endpoints: Pair<String, String>,
        maxHops: Int
    ): List<String> {
        val (sourceNodeId, targetNodeId) = endpoints
        val queue = mutableListOf(Pair(sourceNodeId, listOf(sourceNodeId)))
        val visited = mutableSetOf(sourceNodeId)
        var head = 0
        while (head < queue.size) {
            val (curr, path) = queue[head++]
            if (path.size > maxHops) break
            val neighbors = queryOutgoingTargets(edgeStmt, projectId, curr)
            if (targetNodeId in neighbors) return path + targetNodeId
            enqueueUnvisitedNeighbors(neighbors, path, visited, queue)
        }
        return emptyList()
    }

    private fun enqueueUnvisitedNeighbors(
        neighbors: List<String>,
        currentPath: List<String>,
        visited: MutableSet<String>,
        queue: MutableList<Pair<String, List<String>>>
    ) {
        for (nextId in neighbors) {
            if (visited.add(nextId)) {
                queue.add(Pair(nextId, currentPath + nextId))
            }
        }
    }

    private fun queryOutgoingTargets(
        edgeStmt: NativeSqliteStatement,
        projectId: String,
        sourceNodeId: String
    ): List<String> {
        edgeStmt.reset()
        edgeStmt.clearBindings()
        edgeStmt.bindString(1, projectId)
        edgeStmt.bindString(2, sourceNodeId)
        val targets = mutableListOf<String>()
        while (edgeStmt.step()) {
            targets.add(edgeStmt.columnString(0))
        }
        return targets
    }

    fun sliceLiveSource(
        projectRoot: String,
        node: GraphNode
    ): LiveSourceSlice {
        val relPath = node.fileId.substringAfter(":")
        val fullPath = if (projectRoot.endsWith("/")) "$projectRoot$relPath" else "$projectRoot/$relPath"
        if (!PlatformFiles.exists(fullPath)) {
            return LiveSourceSlice(
                fresh = false,
                relPath = relPath,
                startLine = node.startLine,
                endLine = node.endLine,
                indexedSha256 = "",
                liveSha256 = "",
                source = "",
                reason = "file_missing_on_disk"
            )
        }

        val rawBytes = PlatformFiles.readBytes(fullPath)
        val liveSha256 = Sha256.digest(rawBytes)

        val stmt = db.prepare("SELECT sha256 FROM files WHERE file_id = ?;")
        val indexedSha256 = try {
            stmt.bindString(1, node.fileId)
            if (stmt.step()) stmt.columnString(0) else ""
        } finally {
            stmt.close()
        }

        val isFresh = liveSha256 == indexedSha256
        val text = rawBytes.decodeToString()
        val lines = text.lines()

        val startIdx = (node.startLine - 1).coerceAtLeast(0)
        val endIdx = (node.endLine.coerceAtLeast(node.startLine)).coerceAtMost(lines.size)

        val numbered = (startIdx until endIdx).joinToString("\n") { idx ->
            "${idx + 1}: ${lines[idx]}"
        }

        return LiveSourceSlice(
            fresh = isFresh,
            relPath = relPath,
            startLine = node.startLine,
            endLine = node.endLine,
            indexedSha256 = indexedSha256,
            liveSha256 = liveSha256,
            source = numbered
        )
    }

    fun upsertNodeComplexity(
        nodeId: String,
        cyclomatic: Int,
        cognitive: Int,
        maxNesting: Int,
        score: Double
    ) {
        val stmt = db.prepare(
            """
            UPDATE nodes
            SET cyclomatic_complexity = ?,
                cognitive_complexity = ?,
                max_nesting_depth = ?,
                complexity_score = ?
            WHERE node_id = ?;
            """.trimIndent()
        )
        try {
            stmt.bindInt(1, cyclomatic)
            stmt.bindInt(2, cognitive)
            stmt.bindInt(3, maxNesting)
            stmt.bindDouble(4, score)
            stmt.bindString(5, nodeId)
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    fun queryComplexNodes(
        projectId: String,
        minCognitive: Int = 10,
        limit: Int = 50
    ): List<ComplexityScoreRecord> {
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, name, qualified_name, file_id, start_line,
               cyclomatic_complexity, cognitive_complexity, max_nesting_depth, in_degree,
               complexity_score
            FROM nodes
            WHERE project_id = ? AND cognitive_complexity >= ? AND kind IN ('function', 'method', 'composable')
            ORDER BY cognitive_complexity DESC, in_degree DESC
            LIMIT ?;
            """.trimIndent()
        )
        val results = mutableListOf<ComplexityScoreRecord>()
        try {
            stmt.bindString(1, projectId)
            stmt.bindInt(2, minCognitive)
            stmt.bindInt(3, limit)
            while (stmt.step()) {
                results.add(rowToComplexityScoreRecord(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun queryArchitecturalRiskNodes(
        projectId: String,
        minRisk: Double = 0.0,
        limit: Int = 50
    ): List<ComplexityScoreRecord> {
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, name, qualified_name, file_id, start_line,
               cyclomatic_complexity, cognitive_complexity, max_nesting_depth, in_degree,
               complexity_score
            FROM nodes
            WHERE project_id = ? AND kind IN ('function', 'method', 'composable') AND cognitive_complexity > 0
            ORDER BY cognitive_complexity DESC, in_degree DESC;
            """.trimIndent()
        )
        val results = mutableListOf<ComplexityScoreRecord>()
        try {
            stmt.bindString(1, projectId)
            while (stmt.step()) {
                val rec = rowToComplexityScoreRecord(stmt)
                if (rec.riskIndex >= minRisk) {
                    results.add(rec)
                }
            }
        } finally {
            stmt.close()
        }
        return results.sortedByDescending { it.riskIndex }.take(limit)
    }

    private fun rowToComplexityScoreRecord(stmt: NativeSqliteStatement): ComplexityScoreRecord {
        val fileId = stmt.columnString(4)
        val cog = stmt.columnInt(7)
        val inDeg = stmt.columnInt(9)
        val score = stmt.columnDouble(10)
        return ComplexityScoreRecord(
            nodeId = stmt.columnString(0),
            projectId = stmt.columnString(1),
            name = stmt.columnString(2),
            qualifiedName = stmt.columnString(3),
            filePath = if (fileId.contains(':')) fileId.substringAfter(':') else fileId,
            line = stmt.columnInt(5),
            cyclomatic = stmt.columnInt(6),
            cognitive = cog,
            maxNesting = stmt.columnInt(8),
            inDegree = inDeg,
            overallScore = score,
            grade = ComplexityExtractor.computeGrade(score),
            riskIndex = ComplexityExtractor.computeRiskIndex(cog, inDeg)
        )
    }
}
