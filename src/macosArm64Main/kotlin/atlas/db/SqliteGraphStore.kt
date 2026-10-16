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

            CREATE TABLE IF NOT EXISTS figma_documents (
                file_key TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                version TEXT NOT NULL DEFAULT '',
                last_modified TEXT NOT NULL DEFAULT '',
                thumbnail_url TEXT NOT NULL DEFAULT ''
            );

            CREATE TABLE IF NOT EXISTS figma_nodes (
                rowid_pk INTEGER PRIMARY KEY AUTOINCREMENT,
                node_id TEXT NOT NULL,
                file_key TEXT NOT NULL,
                page_name TEXT NOT NULL,
                section_id TEXT,
                parent_id TEXT,
                name TEXT NOT NULL,
                node_type TEXT NOT NULL,
                layout_mode TEXT NOT NULL DEFAULT 'NONE',
                sizing_horizontal TEXT NOT NULL DEFAULT 'FIXED',
                sizing_vertical TEXT NOT NULL DEFAULT 'FIXED',
                text_auto_resize TEXT NOT NULL DEFAULT 'NONE',
                padding_left REAL NOT NULL DEFAULT 0.0,
                padding_right REAL NOT NULL DEFAULT 0.0,
                padding_top REAL NOT NULL DEFAULT 0.0,
                padding_bottom REAL NOT NULL DEFAULT 0.0,
                item_spacing REAL NOT NULL DEFAULT 0.0,
                local_x REAL NOT NULL DEFAULT 0.0,
                local_y REAL NOT NULL DEFAULT 0.0,
                canvas_x REAL NOT NULL DEFAULT 0.0,
                canvas_y REAL NOT NULL DEFAULT 0.0,
                width REAL NOT NULL DEFAULT 0.0,
                height REAL NOT NULL DEFAULT 0.0,
                is_interactive INTEGER NOT NULL DEFAULT 0,
                has_emoji INTEGER NOT NULL DEFAULT 0,
                unbound_fill INTEGER NOT NULL DEFAULT 0,
                unbound_stroke INTEGER NOT NULL DEFAULT 0,
                wcag_contrast REAL,
                semiotic_layer TEXT NOT NULL DEFAULT 'syntactic',
                UNIQUE (file_key, node_id)
            );
            CREATE INDEX IF NOT EXISTS idx_fnodes_file ON figma_nodes(file_key);
            CREATE INDEX IF NOT EXISTS idx_fnodes_name ON figma_nodes(file_key, name);
            CREATE INDEX IF NOT EXISTS idx_fnodes_type ON figma_nodes(file_key, node_type);
            CREATE INDEX IF NOT EXISTS idx_fnodes_parent ON figma_nodes(file_key, parent_id);

            CREATE VIRTUAL TABLE IF NOT EXISTS figma_spatial_index USING rtree(
                id,
                min_x, max_x,
                min_y, max_y
            );

            CREATE TABLE IF NOT EXISTS figma_variables (
                variable_id TEXT NOT NULL,
                file_key TEXT NOT NULL,
                collection_name TEXT NOT NULL,
                name TEXT NOT NULL,
                resolved_type TEXT NOT NULL,
                is_alias INTEGER NOT NULL DEFAULT 0,
                aliased_variable_id TEXT,
                modes_json TEXT NOT NULL DEFAULT '{}',
                code_syntax_compose TEXT,
                code_syntax_web TEXT,
                PRIMARY KEY (file_key, variable_id)
            );
            CREATE INDEX IF NOT EXISTS idx_fvars_file ON figma_variables(file_key);
            CREATE INDEX IF NOT EXISTS idx_fvars_name ON figma_variables(file_key, name);
            CREATE INDEX IF NOT EXISTS idx_fvars_alias ON figma_variables(aliased_variable_id);

            CREATE TABLE IF NOT EXISTS figma_edges (
                edge_id TEXT NOT NULL,
                file_key TEXT NOT NULL,
                source_node_id TEXT NOT NULL,
                target_node_id TEXT NOT NULL,
                edge_type TEXT NOT NULL,
                property_slot TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (file_key, edge_id)
            );
            CREATE INDEX IF NOT EXISTS idx_fedges_file ON figma_edges(file_key);
            CREATE INDEX IF NOT EXISTS idx_fedges_src ON figma_edges(file_key, source_node_id);
            CREATE INDEX IF NOT EXISTS idx_fedges_tgt ON figma_edges(file_key, target_node_id);
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

    // ==========================================
    // Figma Knowledge Graph & Spatial Index
    // ==========================================

    fun insertFigmaDocument(document: FigmaDocument) {
        val stmt = db.prepare(
            """
            INSERT OR REPLACE INTO figma_documents (
                file_key, name, version, last_modified, thumbnail_url
            ) VALUES (?, ?, ?, ?, ?);
            """.trimIndent()
        )
        try {
            stmt.bindString(1, document.fileKey)
            stmt.bindString(2, document.name)
            stmt.bindString(3, document.version)
            stmt.bindString(4, document.lastModified)
            stmt.bindString(5, document.thumbnailUrl)
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    fun getFigmaDocument(fileKey: String): FigmaDocument? {
        val stmt = db.prepare(
            """
            SELECT file_key, name, version, last_modified, thumbnail_url
            FROM figma_documents WHERE file_key = ?;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        try {
            if (stmt.step()) {
                return FigmaDocument(
                    fileKey = stmt.columnString(0),
                    name = stmt.columnString(1),
                    version = stmt.columnString(2),
                    lastModified = stmt.columnString(3),
                    thumbnailUrl = stmt.columnString(4)
                )
            }
            return null
        } finally {
            stmt.close()
        }
    }

    fun deleteFigmaDocument(fileKey: String) {
        db.exec("BEGIN TRANSACTION;")
        try {
            val delSpatial = db.prepare("DELETE FROM figma_spatial_index WHERE id IN (SELECT rowid_pk FROM figma_nodes WHERE file_key = ?);")
            delSpatial.bindString(1, fileKey)
            delSpatial.step()
            delSpatial.close()

            val delNodes = db.prepare("DELETE FROM figma_nodes WHERE file_key = ?;")
            delNodes.bindString(1, fileKey)
            delNodes.step()
            delNodes.close()

            val delEdges = db.prepare("DELETE FROM figma_edges WHERE file_key = ?;")
            delEdges.bindString(1, fileKey)
            delEdges.step()
            delEdges.close()

            val delVars = db.prepare("DELETE FROM figma_variables WHERE file_key = ?;")
            delVars.bindString(1, fileKey)
            delVars.step()
            delVars.close()

            val delDoc = db.prepare("DELETE FROM figma_documents WHERE file_key = ?;")
            delDoc.bindString(1, fileKey)
            delDoc.step()
            delDoc.close()

            db.exec("COMMIT;")
        } catch (e: Throwable) {
            db.exec("ROLLBACK;")
            throw e
        }
    }

    fun insertFigmaNodes(fileKey: String, nodes: List<FigmaNode>) {
        if (nodes.isEmpty()) return

        db.exec("BEGIN TRANSACTION;")
        try {
            val nodeStmt = db.prepare(
                """
                INSERT INTO figma_nodes (
                    node_id, file_key, page_name, section_id, parent_id, name, node_type,
                    layout_mode, sizing_horizontal, sizing_vertical, text_auto_resize,
                    padding_left, padding_right, padding_top, padding_bottom, item_spacing,
                    local_x, local_y, canvas_x, canvas_y, width, height,
                    is_interactive, has_emoji, unbound_fill, unbound_stroke, wcag_contrast,
                    semiotic_layer
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(file_key, node_id) DO UPDATE SET
                    page_name = excluded.page_name,
                    section_id = excluded.section_id,
                    parent_id = excluded.parent_id,
                    name = excluded.name,
                    node_type = excluded.node_type,
                    layout_mode = excluded.layout_mode,
                    sizing_horizontal = excluded.sizing_horizontal,
                    sizing_vertical = excluded.sizing_vertical,
                    text_auto_resize = excluded.text_auto_resize,
                    padding_left = excluded.padding_left,
                    padding_right = excluded.padding_right,
                    padding_top = excluded.padding_top,
                    padding_bottom = excluded.padding_bottom,
                    item_spacing = excluded.item_spacing,
                    local_x = excluded.local_x,
                    local_y = excluded.local_y,
                    canvas_x = excluded.canvas_x,
                    canvas_y = excluded.canvas_y,
                    width = excluded.width,
                    height = excluded.height,
                    is_interactive = excluded.is_interactive,
                    has_emoji = excluded.has_emoji,
                    unbound_fill = excluded.unbound_fill,
                    unbound_stroke = excluded.unbound_stroke,
                    wcag_contrast = excluded.wcag_contrast,
                    semiotic_layer = excluded.semiotic_layer
                RETURNING rowid_pk;
                """.trimIndent()
            )

            val spatialStmt = db.prepare(
                """
                INSERT OR REPLACE INTO figma_spatial_index (id, min_x, max_x, min_y, max_y)
                VALUES (?, ?, ?, ?, ?);
                """.trimIndent()
            )

            try {
                for (node in nodes) {
                    val effectiveFileKey = if (node.fileKey.isNotBlank()) node.fileKey else fileKey
                    nodeStmt.reset()
                    nodeStmt.clearBindings()
                    nodeStmt.bindString(1, node.nodeId)
                    nodeStmt.bindString(2, effectiveFileKey)
                    nodeStmt.bindString(3, node.pageName)
                    if (node.sectionId != null) nodeStmt.bindString(4, node.sectionId) else nodeStmt.bindNull(4)
                    if (node.parentId != null) nodeStmt.bindString(5, node.parentId) else nodeStmt.bindNull(5)
                    nodeStmt.bindString(6, node.name)
                    nodeStmt.bindString(7, node.nodeType)
                    nodeStmt.bindString(8, node.layoutMode)
                    nodeStmt.bindString(9, node.sizingHorizontal)
                    nodeStmt.bindString(10, node.sizingVertical)
                    nodeStmt.bindString(11, node.textAutoResize)
                    val pL = if (node.paddingLeft.isNaN() || node.paddingLeft.isInfinite()) 0.0 else node.paddingLeft
                    val pR = if (node.paddingRight.isNaN() || node.paddingRight.isInfinite()) 0.0 else node.paddingRight
                    val pT = if (node.paddingTop.isNaN() || node.paddingTop.isInfinite()) 0.0 else node.paddingTop
                    val pB = if (node.paddingBottom.isNaN() || node.paddingBottom.isInfinite()) 0.0 else node.paddingBottom
                    val sp = if (node.itemSpacing.isNaN() || node.itemSpacing.isInfinite()) 0.0 else node.itemSpacing
                    val lX = if (node.localX.isNaN() || node.localX.isInfinite()) 0.0 else node.localX
                    val lY = if (node.localY.isNaN() || node.localY.isInfinite()) 0.0 else node.localY
                    val cX = if (node.canvasX.isNaN() || node.canvasX.isInfinite()) 0.0 else node.canvasX
                    val cY = if (node.canvasY.isNaN() || node.canvasY.isInfinite()) 0.0 else node.canvasY
                    val w = if (node.width.isNaN() || node.width.isInfinite()) 0.0 else node.width
                    val h = if (node.height.isNaN() || node.height.isInfinite()) 0.0 else node.height
                    val safeContrast = node.wcagContrast?.takeUnless { it.isNaN() || it.isInfinite() }

                    nodeStmt.bindDouble(12, pL)
                    nodeStmt.bindDouble(13, pR)
                    nodeStmt.bindDouble(14, pT)
                    nodeStmt.bindDouble(15, pB)
                    nodeStmt.bindDouble(16, sp)
                    nodeStmt.bindDouble(17, lX)
                    nodeStmt.bindDouble(18, lY)
                    nodeStmt.bindDouble(19, cX)
                    nodeStmt.bindDouble(20, cY)
                    nodeStmt.bindDouble(21, w)
                    nodeStmt.bindDouble(22, h)
                    nodeStmt.bindInt(23, if (node.isInteractive) 1 else 0)
                    nodeStmt.bindInt(24, if (node.hasEmoji) 1 else 0)
                    nodeStmt.bindInt(25, if (node.unboundFill) 1 else 0)
                    nodeStmt.bindInt(26, if (node.unboundStroke) 1 else 0)
                    if (safeContrast != null) nodeStmt.bindDouble(27, safeContrast) else nodeStmt.bindNull(27)
                    nodeStmt.bindString(28, node.semioticLayer)

                    if (nodeStmt.step()) {
                        val rowidPk = nodeStmt.columnLong(0)
                        val minX = minOf(cX, cX + w)
                        val maxX = maxOf(cX, cX + w)
                        val minY = minOf(cY, cY + h)
                        val maxY = maxOf(cY, cY + h)

                        spatialStmt.reset()
                        spatialStmt.clearBindings()
                        spatialStmt.bindLong(1, rowidPk)
                        spatialStmt.bindDouble(2, minX)
                        spatialStmt.bindDouble(3, maxX)
                        spatialStmt.bindDouble(4, minY)
                        spatialStmt.bindDouble(5, maxY)
                        spatialStmt.step()
                    }
                }
            } finally {
                nodeStmt.close()
                spatialStmt.close()
            }

            db.exec("COMMIT;")
        } catch (e: Throwable) {
            db.exec("ROLLBACK;")
            throw e
        }
    }

    fun insertFigmaVariables(fileKey: String, variables: List<FigmaVariable>) {
        if (variables.isEmpty()) return

        db.exec("BEGIN TRANSACTION;")
        try {
            val stmt = db.prepare(
                """
                INSERT OR REPLACE INTO figma_variables (
                    variable_id, file_key, collection_name, name, resolved_type,
                    is_alias, aliased_variable_id, modes_json, code_syntax_compose, code_syntax_web
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """.trimIndent()
            )
            try {
                for (v in variables) {
                    val effectiveFileKey = if (v.fileKey.isNotBlank()) v.fileKey else fileKey
                    stmt.reset()
                    stmt.clearBindings()
                    stmt.bindString(1, v.variableId)
                    stmt.bindString(2, effectiveFileKey)
                    stmt.bindString(3, v.collectionName)
                    stmt.bindString(4, v.name)
                    stmt.bindString(5, v.resolvedType)
                    stmt.bindInt(6, if (v.isAlias) 1 else 0)
                    if (v.aliasedVariableId != null) stmt.bindString(7, v.aliasedVariableId) else stmt.bindNull(7)
                    stmt.bindString(8, v.modesJson)
                    if (v.codeSyntaxCompose != null) stmt.bindString(9, v.codeSyntaxCompose) else stmt.bindNull(9)
                    if (v.codeSyntaxWeb != null) stmt.bindString(10, v.codeSyntaxWeb) else stmt.bindNull(10)
                    stmt.step()
                }
            } finally {
                stmt.close()
            }
            db.exec("COMMIT;")
        } catch (e: Throwable) {
            db.exec("ROLLBACK;")
            throw e
        }
    }

    fun insertFigmaEdges(fileKey: String, edges: List<FigmaEdge>) {
        if (edges.isEmpty()) return

        db.exec("BEGIN TRANSACTION;")
        try {
            val stmt = db.prepare(
                """
                INSERT OR REPLACE INTO figma_edges (
                    edge_id, file_key, source_node_id, target_node_id, edge_type, property_slot
                ) VALUES (?, ?, ?, ?, ?, ?);
                """.trimIndent()
            )
            try {
                for (edge in edges) {
                    val effectiveFileKey = if (edge.fileKey.isNotBlank()) edge.fileKey else fileKey
                    stmt.reset()
                    stmt.clearBindings()
                    stmt.bindString(1, edge.edgeId)
                    stmt.bindString(2, effectiveFileKey)
                    stmt.bindString(3, edge.sourceNodeId)
                    stmt.bindString(4, edge.targetNodeId)
                    stmt.bindString(5, edge.edgeType)
                    stmt.bindString(6, edge.propertySlot)
                    stmt.step()
                }
            } finally {
                stmt.close()
            }
            db.exec("COMMIT;")
        } catch (e: Throwable) {
            db.exec("ROLLBACK;")
            throw e
        }
    }

    fun queryFigmaSpatialIntersection(
        fileKey: String,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double
    ): List<FigmaNode> {
        val safeMinX = if (minX.isNaN() || minX.isInfinite()) 0.0 else minX
        val safeMaxX = if (maxX.isNaN() || maxX.isInfinite()) 0.0 else maxX
        val safeMinY = if (minY.isNaN() || minY.isInfinite()) 0.0 else minY
        val safeMaxY = if (maxY.isNaN() || maxY.isInfinite()) 0.0 else maxY
        val qMinX = minOf(safeMinX, safeMaxX)
        val qMaxX = maxOf(safeMinX, safeMaxX)
        val qMinY = minOf(safeMinY, safeMaxY)
        val qMaxY = maxOf(safeMinY, safeMaxY)

        val stmt = db.prepare(
            """
            SELECT n.rowid_pk, n.node_id, n.file_key, n.page_name, n.section_id, n.parent_id,
                   n.name, n.node_type, n.layout_mode, n.sizing_horizontal, n.sizing_vertical,
                   n.text_auto_resize, n.padding_left, n.padding_right, n.padding_top, n.padding_bottom,
                   n.item_spacing, n.local_x, n.local_y, n.canvas_x, n.canvas_y, n.width, n.height,
                   n.is_interactive, n.has_emoji, n.unbound_fill, n.unbound_stroke, n.wcag_contrast,
                   n.semiotic_layer
            FROM figma_spatial_index s
            CROSS JOIN figma_nodes n ON n.rowid_pk = s.id
            WHERE n.file_key = ?
              AND s.max_x >= ? AND s.min_x <= ?
              AND s.max_y >= ? AND s.min_y <= ?
            ORDER BY n.canvas_y, n.canvas_x;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindDouble(2, qMinX)
        stmt.bindDouble(3, qMaxX)
        stmt.bindDouble(4, qMinY)
        stmt.bindDouble(5, qMaxY)

        val results = mutableListOf<FigmaNode>()
        try {
            while (stmt.step()) {
                results.add(rowToFigmaNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun queryFigmaNodeById(fileKey: String, nodeId: String): FigmaNode? {
        val stmt = db.prepare(
            """
            SELECT rowid_pk, node_id, file_key, page_name, section_id, parent_id,
                   name, node_type, layout_mode, sizing_horizontal, sizing_vertical,
                   text_auto_resize, padding_left, padding_right, padding_top, padding_bottom,
                   item_spacing, local_x, local_y, canvas_x, canvas_y, width, height,
                   is_interactive, has_emoji, unbound_fill, unbound_stroke, wcag_contrast,
                   semiotic_layer
            FROM figma_nodes
            WHERE file_key = ? AND node_id = ?;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindString(2, nodeId)
        try {
            if (stmt.step()) {
                return rowToFigmaNode(stmt)
            }
            return null
        } finally {
            stmt.close()
        }
    }

    fun queryFigmaNodesByName(fileKey: String, name: String): List<FigmaNode> {
        val stmt = db.prepare(
            """
            SELECT rowid_pk, node_id, file_key, page_name, section_id, parent_id,
                   name, node_type, layout_mode, sizing_horizontal, sizing_vertical,
                   text_auto_resize, padding_left, padding_right, padding_top, padding_bottom,
                   item_spacing, local_x, local_y, canvas_x, canvas_y, width, height,
                   is_interactive, has_emoji, unbound_fill, unbound_stroke, wcag_contrast,
                   semiotic_layer
            FROM figma_nodes
            WHERE file_key = ? AND name = ?;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindString(2, name)
        val results = mutableListOf<FigmaNode>()
        try {
            while (stmt.step()) {
                results.add(rowToFigmaNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun queryFigmaNodesByType(fileKey: String, nodeType: String): List<FigmaNode> {
        val stmt = db.prepare(
            """
            SELECT rowid_pk, node_id, file_key, page_name, section_id, parent_id,
                   name, node_type, layout_mode, sizing_horizontal, sizing_vertical,
                   text_auto_resize, padding_left, padding_right, padding_top, padding_bottom,
                   item_spacing, local_x, local_y, canvas_x, canvas_y, width, height,
                   is_interactive, has_emoji, unbound_fill, unbound_stroke, wcag_contrast,
                   semiotic_layer
            FROM figma_nodes
            WHERE file_key = ? AND node_type = ?;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindString(2, nodeType)
        val results = mutableListOf<FigmaNode>()
        try {
            while (stmt.step()) {
                results.add(rowToFigmaNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun queryFigmaVariableById(fileKey: String, variableId: String): FigmaVariable? {
        val stmt = db.prepare(
            """
            SELECT variable_id, file_key, collection_name, name, resolved_type,
                   is_alias, aliased_variable_id, modes_json, code_syntax_compose, code_syntax_web
            FROM figma_variables
            WHERE file_key = ? AND variable_id = ?;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindString(2, variableId)
        try {
            if (stmt.step()) {
                return rowToFigmaVariable(stmt)
            }
            return null
        } finally {
            stmt.close()
        }
    }

    fun queryFigmaVariableByName(fileKey: String, name: String): FigmaVariable? {
        val stmt = db.prepare(
            """
            SELECT variable_id, file_key, collection_name, name, resolved_type,
                   is_alias, aliased_variable_id, modes_json, code_syntax_compose, code_syntax_web
            FROM figma_variables
            WHERE file_key = ? AND name = ?;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindString(2, name)
        try {
            if (stmt.step()) {
                return rowToFigmaVariable(stmt)
            }
            return null
        } finally {
            stmt.close()
        }
    }

    fun queryFigmaEdgesBySource(fileKey: String, sourceNodeId: String): List<FigmaEdge> {
        val stmt = db.prepare(
            """
            SELECT edge_id, file_key, source_node_id, target_node_id, edge_type, property_slot
            FROM figma_edges
            WHERE file_key = ? AND source_node_id = ?;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindString(2, sourceNodeId)
        val results = mutableListOf<FigmaEdge>()
        try {
            while (stmt.step()) {
                results.add(rowToFigmaEdge(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun queryFigmaEdgesByTarget(fileKey: String, targetNodeId: String): List<FigmaEdge> {
        val stmt = db.prepare(
            """
            SELECT edge_id, file_key, source_node_id, target_node_id, edge_type, property_slot
            FROM figma_edges
            WHERE file_key = ? AND target_node_id = ?;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindString(2, targetNodeId)
        val results = mutableListOf<FigmaEdge>()
        try {
            while (stmt.step()) {
                results.add(rowToFigmaEdge(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun resolveVariableAliasImpact(
        fileKey: String,
        variableIdOrName: String,
        depth: Int = 10
    ): List<FigmaVariable> {
        val maxDepth = if (depth < 0) 0 else depth
        val stmt = db.prepare(
            """
            WITH RECURSIVE var_dag(variable_id, file_key, collection_name, name, resolved_type,
                                   is_alias, aliased_variable_id, modes_json, code_syntax_compose, code_syntax_web, depth, path) AS (
                SELECT variable_id, file_key, collection_name, name, resolved_type,
                       is_alias, aliased_variable_id, modes_json, code_syntax_compose, code_syntax_web, 0,
                       ',' || variable_id || ','
                FROM figma_variables
                WHERE file_key = ? AND (variable_id = ? OR name = ?)
                UNION ALL
                SELECT v.variable_id, v.file_key, v.collection_name, v.name, v.resolved_type,
                       v.is_alias, v.aliased_variable_id, v.modes_json, v.code_syntax_compose, v.code_syntax_web, d.depth + 1,
                       d.path || v.variable_id || ','
                FROM figma_variables v
                JOIN var_dag d ON (v.aliased_variable_id = d.variable_id OR v.aliased_variable_id = d.name)
                WHERE v.file_key = ? AND d.depth < ? AND instr(d.path, ',' || v.variable_id || ',') = 0
            )
            SELECT variable_id, file_key, collection_name, name, resolved_type,
                   is_alias, aliased_variable_id, modes_json, code_syntax_compose, code_syntax_web
            FROM var_dag
            GROUP BY variable_id, file_key, collection_name, name, resolved_type, is_alias, aliased_variable_id, modes_json, code_syntax_compose, code_syntax_web
            ORDER BY MIN(depth), name;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindString(2, variableIdOrName)
        stmt.bindString(3, variableIdOrName)
        stmt.bindString(4, fileKey)
        stmt.bindInt(5, maxDepth)

        val results = mutableListOf<FigmaVariable>()
        try {
            while (stmt.step()) {
                results.add(rowToFigmaVariable(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun queryNodesBoundToVariable(fileKey: String, variableId: String): List<FigmaNode> {
        val stmt = db.prepare(
            """
            SELECT n.rowid_pk, n.node_id, n.file_key, n.page_name, n.section_id, n.parent_id,
                   n.name, n.node_type, n.layout_mode, n.sizing_horizontal, n.sizing_vertical,
                   n.text_auto_resize, n.padding_left, n.padding_right, n.padding_top, n.padding_bottom,
                   n.item_spacing, n.local_x, n.local_y, n.canvas_x, n.canvas_y, n.width, n.height,
                   n.is_interactive, n.has_emoji, n.unbound_fill, n.unbound_stroke, n.wcag_contrast,
                   n.semiotic_layer
            FROM figma_nodes n
            JOIN figma_edges e ON n.node_id = e.source_node_id AND n.file_key = e.file_key
            WHERE e.file_key = ?
              AND (e.target_node_id = ? OR e.target_node_id IN (
                  SELECT name FROM figma_variables WHERE file_key = ? AND variable_id = ?
              ) OR e.target_node_id IN (
                  SELECT variable_id FROM figma_variables WHERE file_key = ? AND name = ?
              ))
              AND e.edge_type = 'BINDS_VARIABLE'
            GROUP BY n.rowid_pk
            ORDER BY n.name;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindString(2, variableId)
        stmt.bindString(3, fileKey)
        stmt.bindString(4, variableId)
        stmt.bindString(5, fileKey)
        stmt.bindString(6, variableId)

        val results = mutableListOf<FigmaNode>()
        try {
            while (stmt.step()) {
                results.add(rowToFigmaNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun resolveVariableBlastRadius(
        fileKey: String,
        variableIdOrName: String,
        depth: Int = 10
    ): FigmaVariableImpact {
        val impactedVars = resolveVariableAliasImpact(fileKey, variableIdOrName, depth)
        val impactedVarIds = impactedVars.map { it.variableId }.toSet()
        val boundNodes = mutableListOf<FigmaNode>()
        val seenNodeIds = mutableSetOf<String>()

        for (varId in impactedVarIds) {
            val nodes = queryNodesBoundToVariable(fileKey, varId)
            for (node in nodes) {
                if (seenNodeIds.add(node.nodeId)) {
                    boundNodes.add(node)
                }
            }
        }

        return FigmaVariableImpact(
            seedVariableIdOrName = variableIdOrName,
            depth = depth,
            impactedVariables = impactedVars,
            boundNodes = boundNodes
        )
    }

    fun queryFigmaCallers(
        fileKey: String,
        componentNameOrId: String,
        depth: Int = 2
    ): List<FigmaNode> {
        val maxDepth = if (depth < 1) 1 else depth
        val stmt = db.prepare(
            """
            WITH RECURSIVE caller_chain(source_id, target_id, hop, path) AS (
                SELECT source_node_id, target_node_id, 1, ',' || source_node_id || ','
                FROM figma_edges
                WHERE file_key = ?
                  AND edge_type = 'INSTANTIATES'
                  AND (target_node_id = ? OR target_node_id IN (
                      SELECT node_id FROM figma_nodes WHERE file_key = ? AND name = ?
                  ))
                UNION
                SELECT e.source_node_id, e.target_node_id, c.hop + 1, c.path || e.source_node_id || ','
                FROM figma_edges e
                JOIN caller_chain c ON (
                    e.target_node_id = c.source_id
                    OR e.target_node_id IN (
                        SELECT parent_id FROM figma_nodes WHERE file_key = ? AND node_id = c.source_id AND parent_id IS NOT NULL
                    )
                )
                WHERE e.file_key = ? AND e.edge_type = 'INSTANTIATES' AND c.hop < ?
                  AND instr(c.path, ',' || e.source_node_id || ',') = 0
            )
            SELECT n.rowid_pk, n.node_id, n.file_key, n.page_name, n.section_id, n.parent_id,
                   n.name, n.node_type, n.layout_mode, n.sizing_horizontal, n.sizing_vertical,
                   n.text_auto_resize, n.padding_left, n.padding_right, n.padding_top, n.padding_bottom,
                   n.item_spacing, n.local_x, n.local_y, n.canvas_x, n.canvas_y, n.width, n.height,
                   n.is_interactive, n.has_emoji, n.unbound_fill, n.unbound_stroke, n.wcag_contrast,
                   n.semiotic_layer
            FROM caller_chain c
            JOIN figma_nodes n ON n.node_id = c.source_id AND n.file_key = ?
            GROUP BY n.rowid_pk
            ORDER BY MIN(c.hop), n.name;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindString(2, componentNameOrId)
        stmt.bindString(3, fileKey)
        stmt.bindString(4, componentNameOrId)
        stmt.bindString(5, fileKey)
        stmt.bindString(6, fileKey)
        stmt.bindInt(7, maxDepth)
        stmt.bindString(8, fileKey)

        val results = mutableListOf<FigmaNode>()
        try {
            while (stmt.step()) {
                results.add(rowToFigmaNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    private fun rowToFigmaNode(stmt: NativeSqliteStatement): FigmaNode {
        return FigmaNode(
            rowidPk = stmt.columnLong(0),
            nodeId = stmt.columnString(1),
            fileKey = stmt.columnString(2),
            pageName = stmt.columnString(3),
            sectionId = if (stmt.columnIsNull(4)) null else stmt.columnString(4).ifEmpty { null },
            parentId = if (stmt.columnIsNull(5)) null else stmt.columnString(5).ifEmpty { null },
            name = stmt.columnString(6),
            nodeType = stmt.columnString(7),
            layoutMode = stmt.columnString(8),
            sizingHorizontal = stmt.columnString(9),
            sizingVertical = stmt.columnString(10),
            textAutoResize = stmt.columnString(11),
            paddingLeft = stmt.columnDouble(12),
            paddingRight = stmt.columnDouble(13),
            paddingTop = stmt.columnDouble(14),
            paddingBottom = stmt.columnDouble(15),
            itemSpacing = stmt.columnDouble(16),
            localX = stmt.columnDouble(17),
            localY = stmt.columnDouble(18),
            canvasX = stmt.columnDouble(19),
            canvasY = stmt.columnDouble(20),
            width = stmt.columnDouble(21),
            height = stmt.columnDouble(22),
            isInteractive = stmt.columnInt(23) != 0,
            hasEmoji = stmt.columnInt(24) != 0,
            unboundFill = stmt.columnInt(25) != 0,
            unboundStroke = stmt.columnInt(26) != 0,
            wcagContrast = if (stmt.columnIsNull(27)) null else stmt.columnDouble(27),
            semioticLayer = stmt.columnString(28)
        )
    }

    private fun rowToFigmaVariable(stmt: NativeSqliteStatement): FigmaVariable {
        return FigmaVariable(
            variableId = stmt.columnString(0),
            fileKey = stmt.columnString(1),
            collectionName = stmt.columnString(2),
            name = stmt.columnString(3),
            resolvedType = stmt.columnString(4),
            isAlias = stmt.columnInt(5) != 0,
            aliasedVariableId = if (stmt.columnIsNull(6)) null else stmt.columnString(6).ifEmpty { null },
            modesJson = stmt.columnString(7),
            codeSyntaxCompose = if (stmt.columnIsNull(8)) null else stmt.columnString(8).ifEmpty { null },
            codeSyntaxWeb = if (stmt.columnIsNull(9)) null else stmt.columnString(9).ifEmpty { null }
        )
    }

    private fun rowToFigmaEdge(stmt: NativeSqliteStatement): FigmaEdge {
        return FigmaEdge(
            edgeId = stmt.columnString(0),
            fileKey = stmt.columnString(1),
            sourceNodeId = stmt.columnString(2),
            targetNodeId = stmt.columnString(3),
            edgeType = stmt.columnString(4),
            propertySlot = stmt.columnString(5)
        )
    }
}
