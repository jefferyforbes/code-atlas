package atlas.db

import atlas.extract.NameSegmentTokenizer
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.math.abs

/**
 * Data Access Object encapsulating SQLite FTS5 text search, segment vocabulary indexing,
 * vocabulary rebuilding, and multi-tier symbol candidate scoring for [SqliteGraphStore].
 */
class SearchAndVocabDao(
    private val db: NativeSqliteDatabase,
    private val rowToNode: (NativeSqliteStatement) -> GraphNode
) {

    private data class NameSearchFilter(
        val query: String,
        val limit: Int,
        val kind: String?
    )

    fun searchNodes(
        projectId: String,
        query: String,
        limit: Int = 15,
        kind: String? = null
    ): List<GraphNode> {
        val scores = mutableMapOf<String, Double>()
        val segments = NameSegmentTokenizer.tokenize(query)

        if (segments.isNotEmpty()) {
            scoreFtsSegments(projectId, segments, limit, scores)
            scoreVocabCoverage(projectId, segments, scores)
        }

        scoreExactAndFuzzyNames(projectId, NameSearchFilter(query, limit, kind), scores)

        if (scores.isEmpty()) return emptyList()

        val rankedIds = scores.entries.sortedByDescending { it.value }.map { it.key }
        val nodeMap = loadRankedNodesMap(rankedIds, limit, kind)

        val candidates = rankedIds.mapNotNull { nid ->
            val node = nodeMap[nid]
            if (node != null && (kind == null || node.kind == kind)) node else null
        }.sortedWith(
            compareByDescending<GraphNode> { scores[it.nodeId] ?: 0.0 }
                .thenByDescending { it.name.equals(query, ignoreCase = true) }
                .thenByDescending { it.name.startsWith(query, ignoreCase = true) }
                .thenByDescending { it.inDegree }
                .thenBy { it.name }
        )

        return candidates.take(limit)
    }

    fun rebuildVocabulary(projectId: String) {
        val delVocab = db.prepare("DELETE FROM name_segment_vocab WHERE project_id = ?;")
        try {
            delVocab.bindString(1, projectId)
            delVocab.step()
        } finally {
            delVocab.close()
        }

        val selectNodes = db.prepare(
            "SELECT node_id, name, qualified_name FROM nodes WHERE project_id = ?;"
        )
        val insVocab = db.prepare(
            "INSERT OR IGNORE INTO name_segment_vocab (segment, node_id, project_id) VALUES (?, ?, ?);"
        )
        try {
            selectNodes.bindString(1, projectId)
            while (selectNodes.step()) {
                val nodeId = selectNodes.columnString(0)
                val name = selectNodes.columnString(1)
                val qname = selectNodes.columnString(2)
                insertNodeVocabulary(insVocab, nodeId, projectId, "$name $qname")
            }
        } finally {
            selectNodes.close()
            insVocab.close()
        }
    }

    fun scoreCandidate(node: GraphNode, query: String, baseScore: Double = 0.0): Double {
        var score = baseScore
        if (node.name.equals(query, ignoreCase = true)) {
            score += 25.0
        } else if (node.name.startsWith(query, ignoreCase = true)) {
            score += 16.0
        } else if (node.name.contains(query, ignoreCase = true)) {
            score += 12.0
        }
        score += minOf(10.0, node.inDegree.coerceAtLeast(0).toDouble() * 0.5)
        return score
    }

    fun insertNodeVocabulary(
        stmt: NativeSqliteStatement,
        nodeId: String,
        projectId: String,
        text: String
    ) {
        for (seg in NameSegmentTokenizer.tokenize(text)) {
            stmt.reset()
            stmt.clearBindings()
            stmt.bindString(1, seg)
            stmt.bindString(2, nodeId)
            stmt.bindString(3, projectId)
            stmt.step()
        }
    }

    private fun scoreFtsSegments(
        projectId: String,
        segments: List<String>,
        limit: Int,
        scores: MutableMap<String, Double>
    ) {
        val ftsExpr = segments.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"*" }
        try {
            db.prepare(
                """
                SELECT node_id, bm25(nodes_fts) AS rank
                FROM nodes_fts
                WHERE project_id = ? AND nodes_fts MATCH ?
                ORDER BY rank
                LIMIT ?;
                """.trimIndent()
            ).use { ftsStmt ->
                ftsStmt.bindString(1, projectId)
                ftsStmt.bindString(2, ftsExpr)
                ftsStmt.bindInt(3, (limit * 10).coerceAtLeast(100))
                while (ftsStmt.step()) {
                    val nid = ftsStmt.columnString(0)
                    val rank = ftsStmt.columnDouble(1)
                    val current = scores.getOrElse(nid) { 0.0 }
                    scores[nid] = current + (10.0 / (1.0 + abs(rank)))
                }
            }
        } catch (e: Exception) {
            PlatformFiles.logWarning("SearchAndVocabDao", "FTS segment ranking failed: ${e.message}")
        }
    }

    private fun scoreVocabCoverage(
        projectId: String,
        segments: List<String>,
        scores: MutableMap<String, Double>
    ) {
        for (chunk in segments.chunked(250)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val vocabSql = """
                SELECT node_id, COUNT(*) AS matched_segments
                FROM name_segment_vocab
                WHERE project_id = ? AND segment IN ($placeholders)
                GROUP BY node_id;
            """.trimIndent()
            val vocabStmt = db.prepare(vocabSql)
            try {
                vocabStmt.bindString(1, projectId)
                chunk.forEachIndexed { i, s -> vocabStmt.bindString(2 + i, s) }
                while (vocabStmt.step()) {
                    val nid = vocabStmt.columnString(0)
                    val matched = vocabStmt.columnInt(1)
                    val coverage = matched.toDouble() / segments.size.toDouble()
                    val current = scores.getOrElse(nid) { 0.0 }
                    scores[nid] = current + (coverage * coverage * 15.0)
                }
            } finally {
                vocabStmt.close()
            }
        }
    }

    private fun scoreExactAndFuzzyNames(
        projectId: String,
        filter: NameSearchFilter,
        scores: MutableMap<String, Double>
    ) {
        val query = filter.query
        val limit = filter.limit
        val kind = filter.kind
        val escapedQuery = NativeSqliteStatement.escapeSqlLike(query)
        val kindClause = if (kind != null) " AND kind = ?" else ""

        val likeStmt = db.prepare(
            """
            SELECT node_id, name, in_degree FROM nodes
            WHERE project_id = ?
              AND (name LIKE ? ESCAPE '\' OR qualified_name LIKE ? ESCAPE '\')$kindClause
            ORDER BY (LOWER(name) = LOWER(?)) DESC,
                     (LOWER(name) LIKE LOWER(?) ESCAPE '\') DESC,
                     in_degree DESC,
                     name ASC
            LIMIT ?;
            """.trimIndent()
        )
        try {
            var idx = 1
            likeStmt.bindString(idx++, projectId)
            likeStmt.bindString(idx++, "%$escapedQuery%")
            likeStmt.bindString(idx++, "%$escapedQuery%")
            if (kind != null) likeStmt.bindString(idx++, kind)
            likeStmt.bindString(idx++, query)
            likeStmt.bindString(idx++, "$escapedQuery%")
            likeStmt.bindInt(idx, (limit * 5).coerceAtLeast(50))
            while (likeStmt.step()) {
                val nid = likeStmt.columnString(0)
                val name = likeStmt.columnString(1)
                val inDeg = likeStmt.columnInt(2)
                val bonus = when {
                    name.equals(query, ignoreCase = true) -> 25.0
                    name.startsWith(query, ignoreCase = true) -> 16.0
                    else -> 12.0
                } + minOf(10.0, inDeg.coerceAtLeast(0).toDouble() * 0.5)
                val current = scores.getOrElse(nid) { 0.0 }
                scores[nid] = current + bonus
            }
        } finally {
            likeStmt.close()
        }
    }

    private fun queryRankedNodesChunk(
        chunk: List<String>,
        kind: String?,
        nodeMap: MutableMap<String, GraphNode>
    ) {
        val placeholders = chunk.joinToString(",") { "?" }
        val kindClause = if (kind != null) " AND kind = ?" else ""
        val nodesStmt = db.prepare(
            "SELECT ${SqliteGraphStore.NODES_SELECT_COLUMNS} FROM nodes WHERE node_id IN ($placeholders)$kindClause;"
        )
        try {
            chunk.forEachIndexed { i, id -> nodesStmt.bindString(1 + i, id) }
            if (kind != null) nodesStmt.bindString(1 + chunk.size, kind)
            while (nodesStmt.step()) {
                val node = rowToNode(nodesStmt)
                nodeMap[node.nodeId] = node
            }
        } finally {
            nodesStmt.close()
        }
    }

    private fun loadRankedNodesMap(
        rankedIds: List<String>,
        limit: Int,
        kind: String? = null
    ): Map<String, GraphNode> {
        val nodeMap = mutableMapOf<String, GraphNode>()
        val targetCap = (limit * 5).coerceAtLeast(50)
        val batchIds = if (kind == null) rankedIds.take(targetCap) else rankedIds
        for (chunk in batchIds.chunked(250)) {
            if (nodeMap.size >= targetCap) break
            queryRankedNodesChunk(chunk, kind, nodeMap)
        }
        return nodeMap
    }
}
