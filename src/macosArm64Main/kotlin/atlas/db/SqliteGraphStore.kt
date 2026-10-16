package atlas.db

import atlas.extract.NameSegmentTokenizer
import atlas.model.*
import atlas.util.PlatformFiles
import atlas.util.Sha256
import kotlin.math.abs

/**
 * Persistent SQLite WAL knowledge graph store with FTS5, segment vocabulary, and freshness slicing.
 */
class SqliteGraphStore(val dbPath: String) {

    private val db = NativeSqliteDatabase(dbPath)

    init {
        initSchema()
    }

    private fun initSchema() {
        db.exec(
            """
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = NORMAL;

            CREATE TABLE IF NOT EXISTS files (
                file_id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                rel_path TEXT NOT NULL,
                language TEXT NOT NULL,
                module_id TEXT NOT NULL DEFAULT '',
                source_set TEXT NOT NULL DEFAULT 'main',
                semiotic_layer TEXT NOT NULL DEFAULT 'semantic',
                arch_layer TEXT NOT NULL DEFAULT 'domain',
                sha256 TEXT NOT NULL,
                mtime REAL NOT NULL DEFAULT 0.0,
                loc INTEGER NOT NULL DEFAULT 0,
                top_level_decl_count INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_files_project ON files(project_id, rel_path);

            CREATE TABLE IF NOT EXISTS nodes (
                node_id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                file_id TEXT NOT NULL,
                name TEXT NOT NULL,
                qualified_name TEXT NOT NULL,
                kind TEXT NOT NULL,
                signature TEXT NOT NULL DEFAULT '',
                start_line INTEGER NOT NULL DEFAULT 1,
                end_line INTEGER NOT NULL DEFAULT 1,
                module_id TEXT NOT NULL DEFAULT '',
                source_set TEXT NOT NULL DEFAULT 'main',
                semiotic_layer TEXT NOT NULL DEFAULT 'semantic',
                arch_layer TEXT NOT NULL DEFAULT 'domain',
                is_test_double INTEGER NOT NULL DEFAULT 0,
                in_degree INTEGER NOT NULL DEFAULT 0,
                out_degree INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_nodes_project_name ON nodes(project_id, name);
            CREATE INDEX IF NOT EXISTS idx_nodes_file ON nodes(file_id);

            CREATE TABLE IF NOT EXISTS edges (
                edge_id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                source_node_id TEXT NOT NULL,
                target_node_id TEXT NOT NULL,
                edge_type TEXT NOT NULL,
                provenance TEXT NOT NULL DEFAULT 'EXTRACTED_AST',
                confidence REAL NOT NULL DEFAULT 1.0,
                line_number INTEGER NOT NULL DEFAULT 1
            );
            CREATE INDEX IF NOT EXISTS idx_edges_source ON edges(project_id, source_node_id);
            CREATE INDEX IF NOT EXISTS idx_edges_target ON edges(project_id, target_node_id);

            CREATE TABLE IF NOT EXISTS name_segment_vocab (
                segment TEXT NOT NULL,
                node_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                PRIMARY KEY (segment, node_id)
            ) WITHOUT ROWID;

            CREATE TABLE IF NOT EXISTS node_annotations (
                annotation_id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                node_id TEXT NOT NULL,
                outcome TEXT NOT NULL,
                note TEXT NOT NULL,
                created_at_epoch REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_annotations_node ON node_annotations(project_id, node_id);

            CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(
                node_id UNINDEXED,
                project_id UNINDEXED,
                name,
                qualified_name,
                signature,
                file_id
            );
            """.trimIndent()
        )
    }

    fun close() {
        db.close()
    }

    fun insertNodeAnnotation(
        annotationId: String,
        projectId: String,
        nodeId: String,
        outcome: String,
        note: String,
        createdAtEpoch: Double
    ) {
        val stmt = db.prepare(
            """
            INSERT OR REPLACE INTO node_annotations (
                annotation_id, project_id, node_id, outcome, note, created_at_epoch
            ) VALUES (?, ?, ?, ?, ?, ?);
            """.trimIndent()
        )
        try {
            stmt.bindString(1, annotationId)
            stmt.bindString(2, projectId)
            stmt.bindString(3, nodeId)
            stmt.bindString(4, outcome)
            stmt.bindString(5, note)
            stmt.bindDouble(6, createdAtEpoch)
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    fun getNodeAnnotations(projectId: String, nodeId: String): List<NodeAnnotation> {
        val stmt = db.prepare(
            """
            SELECT annotation_id, project_id, node_id, outcome, note, created_at_epoch
            FROM node_annotations
            WHERE project_id = ? AND node_id = ?
            ORDER BY created_at_epoch DESC;
            """.trimIndent()
        )
        val results = mutableListOf<NodeAnnotation>()
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, nodeId)
            while (stmt.step()) {
                results.add(
                    NodeAnnotation(
                        annotationId = stmt.columnString(0),
                        projectId = stmt.columnString(1),
                        nodeId = stmt.columnString(2),
                        outcome = stmt.columnString(3),
                        note = stmt.columnString(4),
                        createdAtEpoch = stmt.columnDouble(5)
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun needsReindex(projectId: String, relPath: String, sha256: String, mtime: Double): Boolean {
        val fileId = "$projectId:$relPath"
        val stmt = db.prepare("SELECT sha256, mtime FROM files WHERE file_id = ?;")
        stmt.bindString(1, fileId)
        try {
            if (!stmt.step()) return true
            val rowSha = stmt.columnString(0)
            val rowMtime = stmt.columnDouble(1)
            if (abs(rowMtime - mtime) < 1e-6 && rowSha == sha256) {
                return false
            }
            return rowSha != sha256
        } finally {
            stmt.close()
        }
    }

    fun upsertFileRecord(
        projectId: String,
        relPath: String,
        language: String,
        moduleId: String,
        sourceSet: String,
        semioticLayer: String,
        archLayer: String,
        sha256: String,
        mtime: Double,
        loc: Int,
        topLevelDeclCount: Int
    ): String {
        val fileId = "$projectId:$relPath"

        // Find old nodes to purge associated indexes
        val oldNodes = mutableListOf<String>()
        val selectOld = db.prepare("SELECT node_id FROM nodes WHERE file_id = ?;")
        selectOld.bindString(1, fileId)
        try {
            while (selectOld.step()) {
                oldNodes.add(selectOld.columnString(0))
            }
        } finally {
            selectOld.close()
        }

        if (oldNodes.isNotEmpty()) {
            for (nodeId in oldNodes) {
                val delVocab = db.prepare("DELETE FROM name_segment_vocab WHERE node_id = ?;")
                delVocab.bindString(1, nodeId)
                delVocab.step()
                delVocab.close()

                val delFts = db.prepare("DELETE FROM nodes_fts WHERE node_id = ?;")
                delFts.bindString(1, nodeId)
                delFts.step()
                delFts.close()

                val delEdges = db.prepare("DELETE FROM edges WHERE source_node_id = ?;")
                delEdges.bindString(1, nodeId)
                delEdges.step()
                delEdges.close()
            }
            val delNodes = db.prepare("DELETE FROM nodes WHERE file_id = ?;")
            delNodes.bindString(1, fileId)
            delNodes.step()
            delNodes.close()
        }

        val upsertStmt = db.prepare(
            """
            INSERT INTO files (
                file_id, project_id, rel_path, language, module_id, source_set,
                semiotic_layer, arch_layer, sha256, mtime, loc, top_level_decl_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(file_id) DO UPDATE SET
                language = excluded.language,
                module_id = excluded.module_id,
                source_set = excluded.source_set,
                semiotic_layer = excluded.semiotic_layer,
                arch_layer = excluded.arch_layer,
                sha256 = excluded.sha256,
                mtime = excluded.mtime,
                loc = excluded.loc,
                top_level_decl_count = excluded.top_level_decl_count;
            """.trimIndent()
        )
        try {
            upsertStmt.bindString(1, fileId)
            upsertStmt.bindString(2, projectId)
            upsertStmt.bindString(3, relPath)
            upsertStmt.bindString(4, language)
            upsertStmt.bindString(5, moduleId)
            upsertStmt.bindString(6, sourceSet)
            upsertStmt.bindString(7, semioticLayer)
            upsertStmt.bindString(8, archLayer)
            upsertStmt.bindString(9, sha256)
            upsertStmt.bindDouble(10, mtime)
            upsertStmt.bindInt(11, loc)
            upsertStmt.bindInt(12, topLevelDeclCount)
            upsertStmt.step()
        } finally {
            upsertStmt.close()
        }
        return fileId
    }

    fun listIndexedFiles(projectId: String): List<String> {
        val res = mutableListOf<String>()
        val stmt = db.prepare("SELECT rel_path FROM files WHERE project_id = ?;")
        stmt.bindString(1, projectId)
        try {
            while (stmt.step()) {
                res.add(stmt.columnString(0))
            }
        } finally {
            stmt.close()
        }
        return res
    }

    fun deleteFileRecord(projectId: String, relPath: String) {
        val fileId = "$projectId:$relPath"
        val oldNodes = mutableListOf<String>()
        val selectOld = db.prepare("SELECT node_id FROM nodes WHERE file_id = ?;")
        selectOld.bindString(1, fileId)
        try {
            while (selectOld.step()) {
                oldNodes.add(selectOld.columnString(0))
            }
        } finally {
            selectOld.close()
        }

        if (oldNodes.isNotEmpty()) {
            for (nodeId in oldNodes) {
                val delVocab = db.prepare("DELETE FROM name_segment_vocab WHERE node_id = ?;")
                delVocab.bindString(1, nodeId)
                delVocab.step()
                delVocab.close()

                val delFts = db.prepare("DELETE FROM nodes_fts WHERE node_id = ?;")
                delFts.bindString(1, nodeId)
                delFts.step()
                delFts.close()

                val delEdges = db.prepare("DELETE FROM edges WHERE source_node_id = ? OR target_node_id = ?;")
                delEdges.bindString(1, nodeId)
                delEdges.bindString(2, nodeId)
                delEdges.step()
                delEdges.close()
            }
            val delNodes = db.prepare("DELETE FROM nodes WHERE file_id = ?;")
            delNodes.bindString(1, fileId)
            delNodes.step()
            delNodes.close()
        }

        val delFile = db.prepare("DELETE FROM files WHERE file_id = ?;")
        delFile.bindString(1, fileId)
        delFile.step()
        delFile.close()
    }

    fun upsertNodesAndEdges(
        projectId: String,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>
    ) {
        db.exec("BEGIN TRANSACTION;")
        try {
            for (node in nodes) {
                val delFts = db.prepare("DELETE FROM nodes_fts WHERE node_id = ?;")
                delFts.bindString(1, node.nodeId)
                delFts.step()
                delFts.close()

                val delVocab = db.prepare("DELETE FROM name_segment_vocab WHERE node_id = ?;")
                delVocab.bindString(1, node.nodeId)
                delVocab.step()
                delVocab.close()

                val insNode = db.prepare(
                    """
                    INSERT INTO nodes (
                        node_id, project_id, file_id, name, qualified_name, kind, signature,
                        start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                        is_test_double, in_degree, out_degree
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(node_id) DO UPDATE SET
                        file_id = excluded.file_id,
                        name = excluded.name,
                        qualified_name = excluded.qualified_name,
                        kind = excluded.kind,
                        signature = excluded.signature,
                        start_line = excluded.start_line,
                        end_line = excluded.end_line,
                        module_id = excluded.module_id,
                        source_set = excluded.source_set,
                        semiotic_layer = excluded.semiotic_layer,
                        arch_layer = excluded.arch_layer,
                        is_test_double = excluded.is_test_double;
                    """.trimIndent()
                )
                insNode.bindString(1, node.nodeId)
                insNode.bindString(2, node.projectId)
                insNode.bindString(3, node.fileId)
                insNode.bindString(4, node.name)
                insNode.bindString(5, node.qualifiedName)
                insNode.bindString(6, node.kind)
                insNode.bindString(7, node.signature)
                insNode.bindInt(8, node.startLine)
                insNode.bindInt(9, node.endLine)
                insNode.bindString(10, node.moduleId)
                insNode.bindString(11, node.sourceSet)
                insNode.bindString(12, node.semioticLayer)
                insNode.bindString(13, node.archLayer)
                insNode.bindInt(14, if (node.isTestDouble) 1 else 0)
                insNode.bindInt(15, node.inDegree)
                insNode.bindInt(16, node.outDegree)
                insNode.step()
                insNode.close()

                val insFts = db.prepare(
                    """
                    INSERT INTO nodes_fts (node_id, project_id, name, qualified_name, signature, file_id)
                    VALUES (?, ?, ?, ?, ?, ?);
                    """.trimIndent()
                )
                insFts.bindString(1, node.nodeId)
                insFts.bindString(2, node.projectId)
                insFts.bindString(3, node.name)
                insFts.bindString(4, node.qualifiedName)
                insFts.bindString(5, node.signature)
                insFts.bindString(6, node.fileId)
                insFts.step()
                insFts.close()

                for (seg in NameSegmentTokenizer.tokenize("${node.name} ${node.qualifiedName}")) {
                    val insVocab = db.prepare(
                        """
                        INSERT OR IGNORE INTO name_segment_vocab (segment, node_id, project_id)
                        VALUES (?, ?, ?);
                        """.trimIndent()
                    )
                    insVocab.bindString(1, seg)
                    insVocab.bindString(2, node.nodeId)
                    insVocab.bindString(3, node.projectId)
                    insVocab.step()
                    insVocab.close()
                }
            }

            for (edge in edges) {
                val insEdge = db.prepare(
                    """
                    INSERT INTO edges (
                        edge_id, project_id, source_node_id, target_node_id,
                        edge_type, provenance, confidence, line_number
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(edge_id) DO UPDATE SET
                        edge_type = excluded.edge_type,
                        provenance = excluded.provenance,
                        confidence = excluded.confidence,
                        line_number = excluded.line_number;
                    """.trimIndent()
                )
                insEdge.bindString(1, edge.edgeId)
                insEdge.bindString(2, edge.projectId)
                insEdge.bindString(3, edge.sourceNodeId)
                insEdge.bindString(4, edge.targetNodeId)
                insEdge.bindString(5, edge.edgeType)
                insEdge.bindString(6, edge.provenance)
                insEdge.bindDouble(7, edge.confidence)
                insEdge.bindInt(8, edge.lineNumber)
                insEdge.step()
                insEdge.close()
            }

            val updateDeg = db.prepare(
                """
                UPDATE nodes SET
                    in_degree = (
                        SELECT COUNT(*) FROM edges e
                        WHERE e.project_id = nodes.project_id AND e.target_node_id = nodes.node_id
                    ),
                    out_degree = (
                        SELECT COUNT(*) FROM edges e
                        WHERE e.project_id = nodes.project_id AND e.source_node_id = nodes.node_id
                    )
                WHERE project_id = ?;
                """.trimIndent()
            )
            updateDeg.bindString(1, projectId)
            updateDeg.step()
            updateDeg.close()

            db.exec("COMMIT;")
        } catch (e: Throwable) {
            db.exec("ROLLBACK;")
            throw e
        }
    }

    private fun rowToNode(stmt: NativeSqliteStatement): GraphNode {
        return GraphNode(
            nodeId = stmt.columnString(0),
            projectId = stmt.columnString(1),
            fileId = stmt.columnString(2),
            name = stmt.columnString(3),
            qualifiedName = stmt.columnString(4),
            kind = stmt.columnString(5),
            signature = stmt.columnString(6),
            startLine = stmt.columnInt(7),
            endLine = stmt.columnInt(8),
            moduleId = stmt.columnString(9),
            sourceSet = stmt.columnString(10),
            semioticLayer = stmt.columnString(11),
            archLayer = stmt.columnString(12),
            isTestDouble = stmt.columnInt(13) != 0,
            inDegree = stmt.columnInt(14),
            outDegree = stmt.columnInt(15)
        )
    }

    fun getNode(projectId: String, target: String): GraphNode? {
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree
            FROM nodes
            WHERE project_id = ? AND (
                node_id = ? OR name = ? OR qualified_name = ? OR file_id LIKE ?
            )
            ORDER BY (node_id = ?) DESC, (name = ?) DESC, in_degree DESC
            LIMIT 1;
            """.trimIndent()
        )
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, target)
            stmt.bindString(3, target)
            stmt.bindString(4, target)
            stmt.bindString(5, "%$target")
            stmt.bindString(6, target)
            stmt.bindString(7, target)
            if (stmt.step()) {
                return rowToNode(stmt)
            }
            return null
        } finally {
            stmt.close()
        }
    }

    fun searchNodes(
        projectId: String,
        query: String,
        limit: Int = 15,
        kind: String? = null
    ): List<GraphNode> {
        val scores = mutableMapOf<String, Double>()
        val segments = NameSegmentTokenizer.tokenize(query)

        if (segments.isNotEmpty()) {
            val ftsExpr = segments.joinToString(" OR ") { "\"$it\"*" }
            try {
                val ftsStmt = db.prepare(
                    """
                    SELECT node_id, bm25(nodes_fts) AS rank
                    FROM nodes_fts
                    WHERE project_id = ? AND nodes_fts MATCH ?
                    LIMIT ?;
                    """.trimIndent()
                )
                ftsStmt.bindString(1, projectId)
                ftsStmt.bindString(2, ftsExpr)
                ftsStmt.bindInt(3, limit * 3)
                while (ftsStmt.step()) {
                    val nid = ftsStmt.columnString(0)
                    val rank = ftsStmt.columnDouble(1)
                    val current = scores.getOrElse(nid) { 0.0 }
                    scores[nid] = current + (10.0 / (1.0 + abs(rank)))
                }
                ftsStmt.close()
            } catch (_: Throwable) {}

            val placeholders = segments.joinToString(",") { "?" }
            val vocabSql = """
                SELECT node_id, COUNT(*) AS matched_segments
                FROM name_segment_vocab
                WHERE project_id = ? AND segment IN ($placeholders)
                GROUP BY node_id;
            """.trimIndent()
            val vocabStmt = db.prepare(vocabSql)
            vocabStmt.bindString(1, projectId)
            segments.forEachIndexed { i, s -> vocabStmt.bindString(2 + i, s) }
            while (vocabStmt.step()) {
                val nid = vocabStmt.columnString(0)
                val matched = vocabStmt.columnInt(1)
                val coverage = matched.toDouble() / segments.size.toDouble()
                val current = scores.getOrElse(nid) { 0.0 }
                scores[nid] = current + (coverage * coverage * 15.0)
            }
            vocabStmt.close()
        }

        val likeStmt = db.prepare(
            """
            SELECT node_id, name FROM nodes
            WHERE project_id = ? AND (name LIKE ? OR qualified_name LIKE ?)
            LIMIT ?;
            """.trimIndent()
        )
        likeStmt.bindString(1, projectId)
        likeStmt.bindString(2, "%$query%")
        likeStmt.bindString(3, "%$query%")
        likeStmt.bindInt(4, limit * 2)
        while (likeStmt.step()) {
            val nid = likeStmt.columnString(0)
            val name = likeStmt.columnString(1)
            val bonus = if (name.equals(query, ignoreCase = true)) 25.0 else 12.0
            val current = scores.getOrElse(nid) { 0.0 }
            scores[nid] = current + bonus
        }
        likeStmt.close()

        if (scores.isEmpty()) return emptyList()

        val rankedIds = scores.entries.sortedByDescending { it.value }.map { it.key }
        val nodeMap = mutableMapOf<String, GraphNode>()

        val batchIds = rankedIds.take(limit * 2)
        val placeholders = batchIds.joinToString(",") { "?" }
        val nodesStmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree
            FROM nodes WHERE node_id IN ($placeholders);
            """.trimIndent()
        )
        batchIds.forEachIndexed { i, id -> nodesStmt.bindString(1 + i, id) }
        while (nodesStmt.step()) {
            val node = rowToNode(nodesStmt)
            nodeMap[node.nodeId] = node
        }
        nodesStmt.close()

        val results = mutableListOf<GraphNode>()
        for (nid in rankedIds) {
            val node = nodeMap[nid]
            if (node != null && (kind == null || node.kind == kind)) {
                results.add(node)
                if (results.size >= limit) break
            }
        }
        return results
    }

    fun getCallers(projectId: String, nodeId: String, depth: Int = 2): List<GraphHop> {
        val maxDepth = if (depth < 1) 1 else depth
        val stmt = db.prepare(
            """
            WITH RECURSIVE caller_chain(source_id, target_id, edge_type, provenance, hop) AS (
                SELECT source_node_id, target_node_id, edge_type, provenance, 1
                FROM edges
                WHERE project_id = ? AND target_node_id = ?
                UNION
                SELECT e.source_node_id, e.target_node_id, e.edge_type, e.provenance, c.hop + 1
                FROM edges e
                JOIN caller_chain c ON e.target_node_id = c.source_id
                WHERE e.project_id = ? AND c.hop < ?
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
                results.add(
                    GraphHop(
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
                )
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
            WITH RECURSIVE callee_chain(source_id, target_id, edge_type, provenance, hop) AS (
                SELECT source_node_id, target_node_id, edge_type, provenance, 1
                FROM edges
                WHERE project_id = ? AND source_node_id = ?
                UNION
                SELECT e.source_node_id, e.target_node_id, e.edge_type, e.provenance, c.hop + 1
                FROM edges e
                JOIN callee_chain c ON e.source_node_id = c.target_id
                WHERE e.project_id = ? AND c.hop < ?
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
                results.add(
                    GraphHop(
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
                )
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun getImpactRadius(projectId: String, nodeId: String, depth: Int = 2): ImpactRadiusResult {
        val callers = getCallers(projectId, nodeId, depth)
        val callees = getCallees(projectId, nodeId, depth)
        val impactedIds = mutableSetOf(nodeId)
        for (item in callers) {
            if (item.sourceId.isNotEmpty()) impactedIds.add(item.sourceId)
        }
        for (item in callees) {
            if (item.targetId.isNotEmpty()) impactedIds.add(item.targetId)
        }

        val placeholders = impactedIds.joinToString(",") { "?" }
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree
            FROM nodes WHERE project_id = ? AND node_id IN ($placeholders);
            """.trimIndent()
        )
        stmt.bindString(1, projectId)
        impactedIds.forEachIndexed { i, id -> stmt.bindString(2 + i, id) }

        val nodes = mutableListOf<GraphNode>()
        while (stmt.step()) {
            nodes.add(rowToNode(stmt))
        }
        stmt.close()

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

    fun getGodNodes(projectId: String, topN: Int = 10): List<GraphNode> {
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree
            FROM nodes
            WHERE project_id = ? AND kind NOT IN ('file', 'module', 'plan_contract', 'obsidian_note')
            ORDER BY (in_degree * 2 + out_degree) DESC, in_degree DESC
            LIMIT ?;
            """.trimIndent()
        )
        stmt.bindString(1, projectId)
        stmt.bindInt(2, topN)
        val results = mutableListOf<GraphNode>()
        try {
            while (stmt.step()) {
                results.add(rowToNode(stmt))
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

        val queue = mutableListOf<Pair<String, List<String>>>()
        val visited = mutableSetOf<String>()

        queue.add(Pair(sourceNodeId, listOf(sourceNodeId)))
        visited.add(sourceNodeId)

        val edgeStmt = db.prepare(
            """
            SELECT target_node_id FROM edges
            WHERE project_id = ? AND source_node_id = ?
            """.trimIndent()
        )

        try {
            var head = 0
            while (head < queue.size) {
                val (curr, path) = queue[head++]
                if (path.size > maxHops) break

                edgeStmt.reset()
                edgeStmt.bindString(1, projectId)
                edgeStmt.bindString(2, curr)

                while (edgeStmt.step()) {
                    val nextId = edgeStmt.columnString(0)
                    if (nextId == targetNodeId) {
                        return path + nextId
                    }
                    if (visited.add(nextId)) {
                        queue.add(Pair(nextId, path + nextId))
                    }
                }
            }
            return emptyList()
        } finally {
            edgeStmt.close()
        }
    }

    fun sliceLiveSource(
        projectRoot: String,
        projectId: String,
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
        stmt.bindString(1, node.fileId)
        val indexedSha256 = if (stmt.step()) stmt.columnString(0) else ""
        stmt.close()

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

    fun listFiles(projectId: String): List<FileRecord> {
        val stmt = db.prepare(
            """
            SELECT file_id, project_id, rel_path, language, module_id, source_set,
                   semiotic_layer, arch_layer, sha256, mtime, loc, top_level_decl_count
            FROM files WHERE project_id = ? ORDER BY rel_path ASC;
            """.trimIndent()
        )
        stmt.bindString(1, projectId)
        val results = mutableListOf<FileRecord>()
        try {
            while (stmt.step()) {
                results.add(
                    FileRecord(
                        fileId = stmt.columnString(0),
                        projectId = stmt.columnString(1),
                        relPath = stmt.columnString(2),
                        language = stmt.columnString(3),
                        moduleId = stmt.columnString(4),
                        sourceSet = stmt.columnString(5),
                        semioticLayer = stmt.columnString(6),
                        archLayer = stmt.columnString(7),
                        sha256 = stmt.columnString(8),
                        mtime = stmt.columnDouble(9),
                        loc = stmt.columnInt(10),
                        topLevelDeclCount = stmt.columnInt(11)
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun listNodes(projectId: String): List<GraphNode> {
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree
            FROM nodes WHERE project_id = ? ORDER BY node_id ASC;
            """.trimIndent()
        )
        stmt.bindString(1, projectId)
        val results = mutableListOf<GraphNode>()
        try {
            while (stmt.step()) {
                results.add(rowToNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun listEdges(projectId: String, edgeTypes: List<String>? = null): List<GraphEdge> {
        val sql = if (edgeTypes != null && edgeTypes.isNotEmpty()) {
            val placeholders = edgeTypes.joinToString(",") { "?" }
            "SELECT edge_id, project_id, source_node_id, target_node_id, edge_type, provenance, confidence, line_number FROM edges WHERE project_id = ? AND edge_type IN ($placeholders);"
        } else {
            "SELECT edge_id, project_id, source_node_id, target_node_id, edge_type, provenance, confidence, line_number FROM edges WHERE project_id = ?;"
        }
        val stmt = db.prepare(sql)
        stmt.bindString(1, projectId)
        if (edgeTypes != null && edgeTypes.isNotEmpty()) {
            edgeTypes.forEachIndexed { i, t -> stmt.bindString(2 + i, t) }
        }
        val results = mutableListOf<GraphEdge>()
        try {
            while (stmt.step()) {
                results.add(
                    GraphEdge(
                        edgeId = stmt.columnString(0),
                        projectId = stmt.columnString(1),
                        sourceNodeId = stmt.columnString(2),
                        targetNodeId = stmt.columnString(3),
                        edgeType = stmt.columnString(4),
                        provenance = stmt.columnString(5),
                        confidence = stmt.columnDouble(6),
                        lineNumber = stmt.columnInt(7)
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun getStats(projectId: String): GraphStats {
        val filesStmt = db.prepare("SELECT COUNT(*) FROM files WHERE project_id = ?;")
        filesStmt.bindString(1, projectId)
        val filesCount = if (filesStmt.step()) filesStmt.columnInt(0) else 0
        filesStmt.close()

        val nodesStmt = db.prepare("SELECT COUNT(*) FROM nodes WHERE project_id = ?;")
        nodesStmt.bindString(1, projectId)
        val nodesCount = if (nodesStmt.step()) nodesStmt.columnInt(0) else 0
        nodesStmt.close()

        val edgesStmt = db.prepare("SELECT COUNT(*) FROM edges WHERE project_id = ?;")
        edgesStmt.bindString(1, projectId)
        val edgesCount = if (edgesStmt.step()) edgesStmt.columnInt(0) else 0
        edgesStmt.close()

        val provStmt = db.prepare(
            """
            SELECT provenance, COUNT(*)
            FROM edges WHERE project_id = ?
            GROUP BY provenance;
            """.trimIndent()
        )
        provStmt.bindString(1, projectId)
        val provMap = mutableMapOf<String, Int>()
        while (provStmt.step()) {
            provMap[provStmt.columnString(0)] = provStmt.columnInt(1)
        }
        provStmt.close()

        return GraphStats(
            projectId = projectId,
            files = filesCount,
            nodes = nodesCount,
            edges = edgesCount,
            provenanceBreakdown = provMap
        )
    }
}
