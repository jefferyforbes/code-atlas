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

    private fun migrateLegacySchema() {
        try {
            val stmt = db.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name='figma_nodes';")
            var sqlDef = ""
            if (stmt.step()) {
                sqlDef = stmt.columnString(0)
            }
            stmt.close()
            if (sqlDef.contains("node_id TEXT NOT NULL UNIQUE")) {
                db.exec("DROP TABLE IF EXISTS figma_spatial_index;")
                db.exec("DROP TABLE IF EXISTS figma_nodes;")
            }
        } catch (_: Throwable) {}
    }

    private fun initSchema() {
        migrateLegacySchema()
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
                out_degree INTEGER NOT NULL DEFAULT 0,
                description TEXT DEFAULT NULL,
                is_top_level INTEGER NOT NULL DEFAULT 1
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
            CREATE INDEX IF NOT EXISTS idx_vocab_node ON name_segment_vocab(node_id);

            CREATE TABLE IF NOT EXISTS raw_symbol_edges (
                project_id TEXT NOT NULL,
                file_id TEXT NOT NULL,
                source_node_id TEXT NOT NULL,
                target_symbol TEXT NOT NULL,
                edge_type TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 1.0,
                provenance TEXT NOT NULL DEFAULT 'EXTRACTED_AST',
                line_number INTEGER NOT NULL DEFAULT 1,
                edge_id TEXT NOT NULL DEFAULT ''
            );
            CREATE INDEX IF NOT EXISTS idx_raw_edges_proj ON raw_symbol_edges(project_id);
            CREATE INDEX IF NOT EXISTS idx_raw_edges_file ON raw_symbol_edges(file_id);

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
            CREATE UNIQUE INDEX IF NOT EXISTS idx_fnodes_file_node ON figma_nodes(file_key, node_id);
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

            CREATE TABLE IF NOT EXISTS compose_nodes (
                node_id TEXT NOT NULL PRIMARY KEY,
                project_slug TEXT NOT NULL,
                fqname TEXT NOT NULL,
                file_path TEXT NOT NULL,
                start_line INTEGER NOT NULL,
                end_line INTEGER NOT NULL,
                source_set TEXT NOT NULL DEFAULT 'commonMain',
                is_restartable INTEGER NOT NULL DEFAULT 1,
                is_skippable INTEGER NOT NULL DEFAULT 1,
                is_readonly INTEGER NOT NULL DEFAULT 0,
                is_inline INTEGER NOT NULL DEFAULT 0,
                returns_unit INTEGER NOT NULL DEFAULT 1,
                has_modifier_param INTEGER NOT NULL DEFAULT 0,
                modifier_is_first_optional INTEGER NOT NULL DEFAULT 1,
                modifier_has_default INTEGER NOT NULL DEFAULT 1,
                unstable_param_count INTEGER NOT NULL DEFAULT 0,
                runtime_param_count INTEGER NOT NULL DEFAULT 0,
                receiver_stability TEXT NOT NULL DEFAULT 'STABLE',
                provenance TEXT NOT NULL DEFAULT 'STATIC_INFERRED',
                violation_mask INTEGER NOT NULL DEFAULT 0
            ) WITHOUT ROWID;

            CREATE INDEX IF NOT EXISTS idx_compose_nodes_project_skippable
                ON compose_nodes(project_slug, is_skippable, unstable_param_count DESC);

            CREATE INDEX IF NOT EXISTS idx_compose_nodes_violations
                ON compose_nodes(project_slug, violation_mask)
                WHERE violation_mask != 0;

            CREATE INDEX IF NOT EXISTS idx_compose_nodes_file
                ON compose_nodes(project_slug, file_path);

            CREATE TABLE IF NOT EXISTS compose_params (
                node_id TEXT NOT NULL,
                param_index INTEGER NOT NULL,
                param_name TEXT NOT NULL,
                type_signature TEXT NOT NULL,
                stability TEXT NOT NULL,
                stability_reason TEXT NOT NULL,
                has_default INTEGER NOT NULL DEFAULT 0,
                is_used_in_body INTEGER NOT NULL DEFAULT 1,
                provenance TEXT NOT NULL DEFAULT 'STATIC_INFERRED',
                PRIMARY KEY(node_id, param_index)
            ) WITHOUT ROWID;

            CREATE INDEX IF NOT EXISTS idx_compose_params_unstable
                ON compose_params(stability, type_signature)
                WHERE stability IN ('UNSTABLE', 'RUNTIME', 'UNCERTAIN');

            CREATE TABLE IF NOT EXISTS compose_trace_metrics (
                node_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                recomposition_count INTEGER NOT NULL DEFAULT 0,
                skip_count INTEGER NOT NULL DEFAULT 0,
                dirty_params_json TEXT NOT NULL DEFAULT '[]',
                recorded_at INTEGER NOT NULL,
                PRIMARY KEY(node_id, session_id)
            ) WITHOUT ROWID;

            CREATE TABLE IF NOT EXISTS project_metadata (
                project_id TEXT NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                PRIMARY KEY (project_id, key)
            );
            """.trimIndent()
        )
        migrateComposeSchema()
        migrateNodesSchema()
    }

    fun getProjectMetadata(projectId: String, key: String): String? {
        val stmt = db.prepare("SELECT value FROM project_metadata WHERE project_id = ? AND key = ?;")
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, key)
            if (stmt.step()) {
                return stmt.columnString(0)
            }
            return null
        } finally {
            stmt.close()
        }
    }

    fun setProjectMetadata(projectId: String, key: String, value: String) {
        val stmt = db.prepare(
            """
            INSERT INTO project_metadata (project_id, key, value)
            VALUES (?, ?, ?)
            ON CONFLICT(project_id, key) DO UPDATE SET value = excluded.value;
            """.trimIndent()
        )
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, key)
            stmt.bindString(3, value)
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    fun migrateComposeSchema() {
        try {
            val cols = mutableSetOf<String>()
            val stmt = db.prepare("PRAGMA table_info(compose_nodes);")
            try {
                while (stmt.step()) {
                    cols.add(stmt.columnString(1))
                }
            } finally {
                stmt.close()
            }
            if (cols.isNotEmpty()) {
                if ("source_set" !in cols) {
                    db.exec("ALTER TABLE compose_nodes ADD COLUMN source_set TEXT NOT NULL DEFAULT 'commonMain';")
                }
                if ("violation_mask" !in cols) {
                    db.exec("ALTER TABLE compose_nodes ADD COLUMN violation_mask INTEGER NOT NULL DEFAULT 0;")
                }
            }
        } catch (_: Throwable) {}
    }

    fun migrateNodesSchema() {
        try {
            val cols = mutableSetOf<String>()
            val stmt = db.prepare("PRAGMA table_info(nodes);")
            try {
                while (stmt.step()) {
                    cols.add(stmt.columnString(1))
                }
            } finally {
                stmt.close()
            }
            if (cols.isNotEmpty()) {
                if ("description" !in cols) {
                    db.exec("ALTER TABLE nodes ADD COLUMN description TEXT DEFAULT NULL;")
                }
                if ("is_top_level" !in cols) {
                    db.exec("ALTER TABLE nodes ADD COLUMN is_top_level INTEGER NOT NULL DEFAULT 1;")
                }
            }
        } catch (_: Throwable) {}
    }

    fun <T> withSavepoint(name: String, block: () -> T): T = db.withSavepoint(name, block)

    private class StatementScope(private val db: NativeSqliteDatabase) {
        private val statements = mutableListOf<NativeSqliteStatement>()

        fun prepare(sql: String): NativeSqliteStatement {
            val stmt = db.prepare(sql)
            statements.add(stmt)
            return stmt
        }

        fun closeAll() {
            for (i in statements.indices.reversed()) {
                try { statements[i].close() } catch (_: Throwable) {}
            }
            statements.clear()
        }
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

    fun getFileRecordsMap(projectId: String): Map<String, Pair<String, Double>> {
        val stmt = db.prepare("SELECT rel_path, sha256, mtime FROM files WHERE project_id = ?;")
        stmt.bindString(1, projectId)
        val result = mutableMapOf<String, Pair<String, Double>>()
        try {
            while (stmt.step()) {
                val relPath = stmt.columnString(0)
                val sha256 = stmt.columnString(1)
                val mtime = stmt.columnDouble(2)
                result[relPath] = Pair(sha256, mtime)
            }
        } finally {
            stmt.close()
        }
        return result
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

    fun replaceBatchRawSymbolEdges(projectId: String, fileEdgesMap: Map<String, List<GraphEdge>>) {
        if (fileEdgesMap.isEmpty()) return
        withSavepoint("sp_batch_raw_edges") {
            val scope = StatementScope(db)
            try {
                val delStmt = scope.prepare("DELETE FROM raw_symbol_edges WHERE project_id = ? AND file_id = ?;")
                val insStmt = scope.prepare(
                    """
                    INSERT INTO raw_symbol_edges (
                        project_id, file_id, source_node_id, target_symbol,
                        edge_type, confidence, provenance, line_number, edge_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
                    """.trimIndent()
                )
                for ((fileId, edges) in fileEdgesMap) {
                    val normalizedFileId = if (fileId.startsWith("$projectId:")) fileId else "$projectId:$fileId"
                    delStmt.reset()
                    delStmt.clearBindings()
                    delStmt.bindString(1, projectId)
                    delStmt.bindString(2, normalizedFileId)
                    delStmt.step()

                    var insertedCount = 0
                    for (edge in edges) {
                        if (edge.edgeType == "CONTAINS") continue
                        val targetSym = if (edge.targetNodeId.startsWith("sym:")) {
                            edge.targetNodeId
                        } else if (!edge.targetNodeId.startsWith("$projectId:")) {
                            "sym:${edge.targetNodeId}"
                        } else {
                            continue
                        }
                        insStmt.reset()
                        insStmt.clearBindings()
                        insStmt.bindString(1, projectId)
                        insStmt.bindString(2, normalizedFileId)
                        insStmt.bindString(3, edge.sourceNodeId)
                        insStmt.bindString(4, targetSym)
                        insStmt.bindString(5, edge.edgeType)
                        insStmt.bindDouble(6, edge.confidence)
                        insStmt.bindString(7, edge.provenance)
                        insStmt.bindInt(8, edge.lineNumber)
                        insStmt.bindString(9, edge.edgeId)
                        insStmt.step()
                        insertedCount++
                    }
                    if (insertedCount == 0) {
                        insStmt.reset()
                        insStmt.clearBindings()
                        insStmt.bindString(1, projectId)
                        insStmt.bindString(2, normalizedFileId)
                        insStmt.bindString(3, "")
                        insStmt.bindString(4, "")
                        insStmt.bindString(5, "INDEXED_MARKER")
                        insStmt.bindDouble(6, 1.0)
                        insStmt.bindString(7, "EXTRACTED_AST")
                        insStmt.bindInt(8, 0)
                        insStmt.bindString(9, "")
                        insStmt.step()
                    }
                }
            } finally {
                scope.closeAll()
            }
        }
    }

    fun replaceFileRawSymbolEdges(projectId: String, fileId: String, edges: List<GraphEdge>) {
        replaceBatchRawSymbolEdges(projectId, mapOf(fileId to edges))
    }

    fun listRawSymbolEdges(projectId: String): List<GraphEdge> {
        val stmt = db.prepare(
            """
            SELECT edge_id, project_id, source_node_id, target_symbol, edge_type,
                   confidence, provenance, line_number
            FROM raw_symbol_edges
            WHERE project_id = ? AND edge_type != 'INDEXED_MARKER'
            ORDER BY rowid ASC;
            """.trimIndent()
        )
        stmt.bindString(1, projectId)
        val results = mutableListOf<GraphEdge>()
        try {
            while (stmt.step()) {
                val rawEdgeId = stmt.columnString(0)
                val projId = stmt.columnString(1)
                val sourceNodeId = stmt.columnString(2)
                val targetSym = stmt.columnString(3)
                val edgeType = stmt.columnString(4)
                val confidence = stmt.columnDouble(5)
                val provenance = stmt.columnString(6)
                val lineNumber = stmt.columnInt(7)
                val normalizedTarget = if (targetSym.startsWith("sym:")) targetSym else "sym:$targetSym"
                val finalEdgeId = rawEdgeId.ifEmpty {
                    "$sourceNodeId:${edgeType.lowercase()}:${normalizedTarget.removePrefix("sym:")}:$lineNumber"
                }
                results.add(
                    GraphEdge(
                        edgeId = finalEdgeId,
                        projectId = projId,
                        sourceNodeId = sourceNodeId,
                        targetNodeId = normalizedTarget,
                        edgeType = edgeType,
                        provenance = provenance,
                        confidence = confidence,
                        lineNumber = lineNumber
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun filesWithRawSymbolEdges(projectId: String): Set<String> {
        val stmt = db.prepare("SELECT DISTINCT file_id FROM raw_symbol_edges WHERE project_id = ?;")
        val result = mutableSetOf<String>()
        try {
            stmt.bindString(1, projectId)
            while (stmt.step()) {
                result.add(stmt.columnString(0))
            }
        } finally {
            stmt.close()
        }
        return result
    }

    fun deleteFileRawSymbolEdges(projectId: String, fileId: String) {
        val normalizedFileId = if (fileId.startsWith("$projectId:")) fileId else "$projectId:$fileId"
        val stmt = db.prepare("DELETE FROM raw_symbol_edges WHERE project_id = ? AND file_id = ?;")
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, normalizedFileId)
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    fun deleteOutgoingEdgesForFile(projectId: String, relPath: String) {
        val fileId = if (relPath.startsWith("$projectId:")) relPath else "$projectId:$relPath"
        val stmt = db.prepare(
            """
            DELETE FROM edges
            WHERE project_id = ?
              AND source_node_id IN (SELECT node_id FROM nodes WHERE file_id = ?);
            """.trimIndent()
        )
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, fileId)
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    fun deleteAllResolvedCodeEdges(projectId: String) {
        val stmt = db.prepare(
            """
            DELETE FROM edges
            WHERE project_id = ?
              AND edge_type NOT IN ('PLAN_SCOPES_FILE', 'WIKILINK_REFS', 'CODE_SPAN_REFS');
            """.trimIndent()
        )
        try {
            stmt.bindString(1, projectId)
            stmt.step()
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
        withSavepoint("sp_index_file") {
            val scope = StatementScope(db)
            try {
                // Find old nodes to purge associated indexes
                val oldNodes = mutableListOf<String>()
                val selectOld = scope.prepare("SELECT node_id FROM nodes WHERE file_id = ?;")
                selectOld.bindString(1, fileId)
                while (selectOld.step()) {
                    oldNodes.add(selectOld.columnString(0))
                }

                if (oldNodes.isNotEmpty()) {
                    val delVocab = scope.prepare("DELETE FROM name_segment_vocab WHERE node_id = ?;")
                    val delFts = scope.prepare("DELETE FROM nodes_fts WHERE node_id = ?;")
                    val delEdges = scope.prepare("DELETE FROM edges WHERE source_node_id = ?;")
                    val delNodes = scope.prepare("DELETE FROM nodes WHERE file_id = ?;")

                    for (nodeId in oldNodes) {
                        delVocab.reset()
                        delVocab.clearBindings()
                        delVocab.bindString(1, nodeId)
                        delVocab.step()

                        delFts.reset()
                        delFts.clearBindings()
                        delFts.bindString(1, nodeId)
                        delFts.step()

                        delEdges.reset()
                        delEdges.clearBindings()
                        delEdges.bindString(1, nodeId)
                        delEdges.step()
                    }
                    delNodes.bindString(1, fileId)
                    delNodes.step()
                }

                val delRawEdges = scope.prepare("DELETE FROM raw_symbol_edges WHERE project_id = ? AND file_id = ?;")
                delRawEdges.bindString(1, projectId)
                delRawEdges.bindString(2, fileId)
                delRawEdges.step()

                val upsertStmt = scope.prepare(
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
                scope.closeAll()
            }
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

    fun recomputeDegrees(projectId: String) {
        val updateDeg = db.prepare(
            """
            UPDATE nodes SET
                in_degree = COALESCE((
                    SELECT inc.cnt FROM (
                        SELECT target_node_id, COUNT(*) AS cnt
                        FROM edges
                        WHERE project_id = ?
                        GROUP BY target_node_id
                    ) inc WHERE inc.target_node_id = nodes.node_id
                ), 0),
                out_degree = COALESCE((
                    SELECT outc.cnt FROM (
                        SELECT source_node_id, COUNT(*) AS cnt
                        FROM edges
                        WHERE project_id = ?
                        GROUP BY source_node_id
                    ) outc WHERE outc.source_node_id = nodes.node_id
                ), 0)
            WHERE project_id = ?;
            """.trimIndent()
        )
        try {
            updateDeg.bindString(1, projectId)
            updateDeg.bindString(2, projectId)
            updateDeg.bindString(3, projectId)
            updateDeg.step()
        } finally {
            updateDeg.close()
        }
    }

    fun deleteFileRecord(projectId: String, relPath: String, recomputeDegrees: Boolean = true) {
        val fileId = "$projectId:$relPath"
        withSavepoint("sp_delete_file") {
            val scope = StatementScope(db)
            val oldNodes = mutableListOf<String>()
            try {
                val selectOld = scope.prepare("SELECT node_id FROM nodes WHERE file_id = ?;")
                selectOld.bindString(1, fileId)
                while (selectOld.step()) {
                    oldNodes.add(selectOld.columnString(0))
                }

                if (oldNodes.isNotEmpty()) {
                    val delVocab = scope.prepare("DELETE FROM name_segment_vocab WHERE node_id = ?;")
                    val delFts = scope.prepare("DELETE FROM nodes_fts WHERE node_id = ?;")
                    val delEdges = scope.prepare("DELETE FROM edges WHERE source_node_id = ? OR target_node_id = ?;")
                    val delNodes = scope.prepare("DELETE FROM nodes WHERE file_id = ?;")

                    for (nodeId in oldNodes) {
                        delVocab.reset()
                        delVocab.clearBindings()
                        delVocab.bindString(1, nodeId)
                        delVocab.step()

                        delFts.reset()
                        delFts.clearBindings()
                        delFts.bindString(1, nodeId)
                        delFts.step()

                        delEdges.reset()
                        delEdges.clearBindings()
                        delEdges.bindString(1, nodeId)
                        delEdges.bindString(2, nodeId)
                        delEdges.step()
                    }
                    delNodes.bindString(1, fileId)
                    delNodes.step()
                }

                val delRawEdges = scope.prepare("DELETE FROM raw_symbol_edges WHERE project_id = ? AND file_id = ?;")
                delRawEdges.bindString(1, projectId)
                delRawEdges.bindString(2, fileId)
                delRawEdges.step()

                val delFile = scope.prepare("DELETE FROM files WHERE file_id = ?;")
                delFile.bindString(1, fileId)
                delFile.step()
            } finally {
                scope.closeAll()
            }

            if (recomputeDegrees && oldNodes.isNotEmpty()) {
                recomputeDegrees(projectId)
            }
        }
    }

    fun upsertNodesAndEdges(
        projectId: String,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>
    ) {
        if (nodes.isEmpty() && edges.isEmpty()) return
        withSavepoint("sp_upsert_nodes_edges") {
            val scope = StatementScope(db)
            try {
                val delFts = scope.prepare("DELETE FROM nodes_fts WHERE node_id = ?;")
                val delVocab = scope.prepare("DELETE FROM name_segment_vocab WHERE node_id = ?;")
                val insNode = scope.prepare(
                    """
                    INSERT INTO nodes (
                        node_id, project_id, file_id, name, qualified_name, kind, signature,
                        start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                        is_test_double, in_degree, out_degree, description, is_top_level
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        is_test_double = excluded.is_test_double,
                        description = excluded.description,
                        is_top_level = excluded.is_top_level;
                    """.trimIndent()
                )
                val insFts = scope.prepare(
                    """
                    INSERT INTO nodes_fts (node_id, project_id, name, qualified_name, signature, file_id)
                    VALUES (?, ?, ?, ?, ?, ?);
                    """.trimIndent()
                )
                val insVocab = scope.prepare(
                    """
                    INSERT OR IGNORE INTO name_segment_vocab (segment, node_id, project_id)
                    VALUES (?, ?, ?);
                    """.trimIndent()
                )
                val insEdge = scope.prepare(
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
                val sweepOrphanEdges = scope.prepare(
                    """
                    DELETE FROM edges
                    WHERE project_id = ?
                    AND (
                        source_node_id NOT IN (SELECT node_id FROM nodes WHERE project_id = ?)
                        OR (
                          target_node_id NOT LIKE 'vault_ref#%'
                          AND target_node_id NOT LIKE '%:vault_ref#%'
                          AND target_node_id NOT IN (SELECT node_id FROM nodes WHERE project_id = ?)
                        )
                      );
                    """.trimIndent()
                )

                for (node in nodes) {
                    delFts.reset()
                    delFts.clearBindings()
                    delFts.bindString(1, node.nodeId)
                    delFts.step()

                    delVocab.reset()
                    delVocab.clearBindings()
                    delVocab.bindString(1, node.nodeId)
                    delVocab.step()

                    insNode.reset()
                    insNode.clearBindings()
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
                    if (node.description != null) {
                        insNode.bindString(17, node.description)
                    } else {
                        insNode.bindNull(17)
                    }
                    insNode.bindInt(18, if (node.isTopLevel) 1 else 0)
                    insNode.step()

                    insFts.reset()
                    insFts.clearBindings()
                    insFts.bindString(1, node.nodeId)
                    insFts.bindString(2, node.projectId)
                    insFts.bindString(3, node.name)
                    insFts.bindString(4, node.qualifiedName)
                    insFts.bindString(5, node.signature)
                    insFts.bindString(6, node.fileId)
                    insFts.step()

                    for (seg in NameSegmentTokenizer.tokenize("${node.name} ${node.qualifiedName}")) {
                        insVocab.reset()
                        insVocab.clearBindings()
                        insVocab.bindString(1, seg)
                        insVocab.bindString(2, node.nodeId)
                        insVocab.bindString(3, node.projectId)
                        insVocab.step()
                    }
                }

                for (edge in edges) {
                    insEdge.reset()
                    insEdge.clearBindings()
                    insEdge.bindString(1, edge.edgeId)
                    insEdge.bindString(2, edge.projectId)
                    insEdge.bindString(3, edge.sourceNodeId)
                    insEdge.bindString(4, edge.targetNodeId)
                    insEdge.bindString(5, edge.edgeType)
                    insEdge.bindString(6, edge.provenance)
                    insEdge.bindDouble(7, edge.confidence)
                    insEdge.bindInt(8, edge.lineNumber)
                    insEdge.step()
                }

                sweepOrphanEdges.bindString(1, projectId)
                sweepOrphanEdges.bindString(2, projectId)
                sweepOrphanEdges.bindString(3, projectId)
                sweepOrphanEdges.step()

                recomputeDegrees(projectId)
            } finally {
                scope.closeAll()
            }
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
            outDegree = stmt.columnInt(15),
            description = stmt.columnStringNullable(16),
            isTopLevel = stmt.columnInt(17) != 0
        )
    }

    fun getNode(projectId: String, target: String): GraphNode? {
        val escapedTarget = target
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree, description, is_top_level
            FROM nodes
            WHERE project_id = ? AND (
                node_id = ?
                OR qualified_name = ?
                OR name = ?
                OR file_id = ?
                OR file_id LIKE ? ESCAPE '\'
                OR file_id LIKE ? ESCAPE '\'
            )
            ORDER BY (node_id = ?) DESC,
                     (qualified_name = ?) DESC,
                     (name = ?) DESC,
                     in_degree DESC
            LIMIT 1;
            """.trimIndent()
        )
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, target)
            stmt.bindString(3, target)
            stmt.bindString(4, target)
            stmt.bindString(5, target)
            stmt.bindString(6, "%:$escapedTarget")
            stmt.bindString(7, "%/$escapedTarget")
            stmt.bindString(8, target)
            stmt.bindString(9, target)
            stmt.bindString(10, target)
            if (stmt.step()) {
                return rowToNode(stmt)
            }
            return null
        } finally {
            stmt.close()
        }
    }

    fun getNodesForFile(projectId: String, relPath: String): List<GraphNode> {
        val norm = relPath.trim().trim('\'', '"').removePrefix("./").replace('\\', '/')
        val escaped = norm.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree, description, is_top_level
            FROM nodes
            WHERE project_id = ? AND (
                file_id = ?
                OR file_id = ?
                OR file_id LIKE ? ESCAPE '\'
                OR file_id LIKE ? ESCAPE '\'
            )
            ORDER BY in_degree DESC;
            """.trimIndent()
        )
        val results = mutableListOf<GraphNode>()
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, "$projectId:$norm")
            stmt.bindString(3, norm)
            stmt.bindString(4, "%:$escaped")
            stmt.bindString(5, "%/$escaped")
            while (stmt.step()) {
                results.add(rowToNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun getNodesForFileIds(projectId: String, fileIds: List<String>): List<GraphNode> {
        if (fileIds.isEmpty()) return emptyList()
        val results = mutableListOf<GraphNode>()
        for (chunk in fileIds.chunked(250)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val stmt = db.prepare(
                """
                SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                       start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                       is_test_double, in_degree, out_degree, description, is_top_level
                FROM nodes
                WHERE project_id = ? AND file_id IN ($placeholders)
                ORDER BY in_degree DESC;
                """.trimIndent()
            )
            try {
                stmt.bindString(1, projectId)
                chunk.forEachIndexed { i, fid -> stmt.bindString(2 + i, fid) }
                while (stmt.step()) {
                    results.add(rowToNode(stmt))
                }
            } finally {
                stmt.close()
            }
        }
        return results
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
                    ORDER BY rank
                    LIMIT ?;
                    """.trimIndent()
                )
                try {
                    ftsStmt.bindString(1, projectId)
                    ftsStmt.bindString(2, ftsExpr)
                    ftsStmt.bindInt(3, (limit * 10).coerceAtLeast(100))
                    while (ftsStmt.step()) {
                        val nid = ftsStmt.columnString(0)
                        val rank = ftsStmt.columnDouble(1)
                        val current = scores.getOrElse(nid) { 0.0 }
                        scores[nid] = current + (10.0 / (1.0 + abs(rank)))
                    }
                } finally {
                    ftsStmt.close()
                }
            } catch (_: Throwable) {}

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

        val escapedQuery = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

        val likeStmt = db.prepare(
            """
            SELECT node_id, name, in_degree FROM nodes
            WHERE project_id = ?
              AND (name LIKE ? ESCAPE '\' OR qualified_name LIKE ? ESCAPE '\')
            ORDER BY (LOWER(name) = LOWER(?)) DESC,
                     (LOWER(name) LIKE LOWER(?) ESCAPE '\') DESC,
                     in_degree DESC,
                     name ASC
            LIMIT ?;
            """.trimIndent()
        )
        try {
            likeStmt.bindString(1, projectId)
            likeStmt.bindString(2, "%$escapedQuery%")
            likeStmt.bindString(3, "%$escapedQuery%")
            likeStmt.bindString(4, query)
            likeStmt.bindString(5, "$escapedQuery%")
            likeStmt.bindInt(6, (limit * 5).coerceAtLeast(50))
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

        if (scores.isEmpty()) return emptyList()

        val rankedIds = scores.entries.sortedByDescending { it.value }.map { it.key }
        val nodeMap = mutableMapOf<String, GraphNode>()

        val batchIds = rankedIds.take((limit * 5).coerceAtLeast(50))
        for (chunk in batchIds.chunked(250)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val nodesStmt = db.prepare(
                """
                SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                       start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                       is_test_double, in_degree, out_degree, description, is_top_level
                FROM nodes WHERE node_id IN ($placeholders);
                """.trimIndent()
            )
            try {
                chunk.forEachIndexed { i, id -> nodesStmt.bindString(1 + i, id) }
                while (nodesStmt.step()) {
                    val node = rowToNode(nodesStmt)
                    nodeMap[node.nodeId] = node
                }
            } finally {
                nodesStmt.close()
            }
        }

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

        val nodes = mutableListOf<GraphNode>()
        for (chunk in impactedIds.chunked(250)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val stmt = db.prepare(
                """
                SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                       start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                       is_test_double, in_degree, out_degree, description, is_top_level
                FROM nodes WHERE project_id = ? AND node_id IN ($placeholders);
                """.trimIndent()
            )
            try {
                stmt.bindString(1, projectId)
                chunk.forEachIndexed { i, id -> stmt.bindString(2 + i, id) }
                while (stmt.step()) {
                    nodes.add(rowToNode(stmt))
                }
            } finally {
                stmt.close()
            }
        }

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
                   is_test_double, in_degree, out_degree, description, is_top_level
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
                edgeStmt.clearBindings()
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
                   is_test_double, in_degree, out_degree, description, is_top_level
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
        val filesCount = try {
            filesStmt.bindString(1, projectId)
            if (filesStmt.step()) filesStmt.columnInt(0) else 0
        } finally {
            filesStmt.close()
        }

        val nodesStmt = db.prepare("SELECT COUNT(*) FROM nodes WHERE project_id = ?;")
        val nodesCount = try {
            nodesStmt.bindString(1, projectId)
            if (nodesStmt.step()) nodesStmt.columnInt(0) else 0
        } finally {
            nodesStmt.close()
        }

        val edgesStmt = db.prepare("SELECT COUNT(*) FROM edges WHERE project_id = ?;")
        val edgesCount = try {
            edgesStmt.bindString(1, projectId)
            if (edgesStmt.step()) edgesStmt.columnInt(0) else 0
        } finally {
            edgesStmt.close()
        }

        val provStmt = db.prepare(
            """
            SELECT provenance, COUNT(*)
            FROM edges WHERE project_id = ?
            GROUP BY provenance;
            """.trimIndent()
        )
        val provMap = mutableMapOf<String, Int>()
        try {
            provStmt.bindString(1, projectId)
            while (provStmt.step()) {
                provMap[provStmt.columnString(0)] = provStmt.columnInt(1)
            }
        } finally {
            provStmt.close()
        }

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
        withSavepoint("sp_del_figma_doc") {
            val scope = StatementScope(db)
            try {
                val delSpatial = scope.prepare("DELETE FROM figma_spatial_index WHERE id IN (SELECT rowid_pk FROM figma_nodes WHERE file_key = ?);")
                val delNodes = scope.prepare("DELETE FROM figma_nodes WHERE file_key = ?;")
                val delEdges = scope.prepare("DELETE FROM figma_edges WHERE file_key = ?;")
                val delVars = scope.prepare("DELETE FROM figma_variables WHERE file_key = ?;")
                val delDoc = scope.prepare("DELETE FROM figma_documents WHERE file_key = ?;")

                delSpatial.bindString(1, fileKey)
                delSpatial.step()

                delNodes.bindString(1, fileKey)
                delNodes.step()

                delEdges.bindString(1, fileKey)
                delEdges.step()

                delVars.bindString(1, fileKey)
                delVars.step()

                delDoc.bindString(1, fileKey)
                delDoc.step()
            } finally {
                scope.closeAll()
            }
        }
    }

    fun insertFigmaNodes(fileKey: String, nodes: List<FigmaNode>) {
        if (nodes.isEmpty()) return

        withSavepoint("sp_insert_figma_nodes") {
            val scope = StatementScope(db)
            try {
                val nodeStmt = scope.prepare(
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

                val spatialStmt = scope.prepare(
                    """
                    INSERT OR REPLACE INTO figma_spatial_index (id, min_x, max_x, min_y, max_y)
                    VALUES (?, ?, ?, ?, ?);
                    """.trimIndent()
                )

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
                scope.closeAll()
            }
        }
    }

    fun insertFigmaVariables(fileKey: String, variables: List<FigmaVariable>) {
        if (variables.isEmpty()) return

        withSavepoint("sp_insert_figma_vars") {
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
        }
    }

    fun insertFigmaEdges(fileKey: String, edges: List<FigmaEdge>) {
        if (edges.isEmpty()) return

        withSavepoint("sp_insert_figma_edges") {
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
        }
    }

    fun queryFigmaSpatialIntersection(
        fileKey: String,
        bounds: FigmaSpatialBounds
    ): List<FigmaNode> = queryFigmaSpatialIntersection(fileKey, bounds.minX, bounds.maxX, bounds.minY, bounds.maxY)

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

    fun queryFigmaEdgesByType(fileKey: String, edgeType: String): List<FigmaEdge> {
        val stmt = db.prepare(
            """
            SELECT edge_id, file_key, source_node_id, target_node_id, edge_type, property_slot
            FROM figma_edges
            WHERE file_key = ? AND edge_type = ?;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindString(2, edgeType)
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

    fun prepare(sql: String): NativeSqliteStatement = db.prepare(sql)

    fun exec(sql: String) {
        db.exec(sql)
    }

    fun getLatestFigmaFileKey(): String? {
        val stmt = db.prepare("SELECT file_key FROM figma_documents ORDER BY last_modified DESC LIMIT 1;")
        try {
            if (stmt.step()) {
                val key = stmt.columnString(0)
                if (key.isNotEmpty()) return key
            }
        } finally {
            stmt.close()
        }
        val stmt2 = db.prepare("SELECT file_key FROM figma_nodes LIMIT 1;")
        try {
            if (stmt2.step()) {
                val key = stmt2.columnString(0)
                if (key.isNotEmpty()) return key
            }
        } finally {
            stmt2.close()
        }
        return null
    }

    fun queryFigmaGodNodes(fileKey: String, limit: Int = 10): List<Pair<FigmaNode, Int>> {
        val stmt = db.prepare(
            """
            SELECT n.rowid_pk, n.node_id, n.file_key, n.page_name, n.section_id, n.parent_id,
                   n.name, n.node_type, n.layout_mode, n.sizing_horizontal, n.sizing_vertical,
                   n.text_auto_resize, n.padding_left, n.padding_right, n.padding_top, n.padding_bottom,
                   n.item_spacing, n.local_x, n.local_y, n.canvas_x, n.canvas_y, n.width, n.height,
                   n.is_interactive, n.has_emoji, n.unbound_fill, n.unbound_stroke, n.wcag_contrast,
                   n.semiotic_layer, COUNT(e.edge_id) as ref_count
            FROM figma_edges e
            JOIN figma_nodes n ON n.node_id = e.target_node_id AND n.file_key = e.file_key
            WHERE e.file_key = ? AND e.edge_type != 'CONTAINS'
            GROUP BY n.rowid_pk
            ORDER BY ref_count DESC
            LIMIT ?;
            """.trimIndent()
        )
        stmt.bindString(1, fileKey)
        stmt.bindInt(2, limit)
        val results = mutableListOf<Pair<FigmaNode, Int>>()
        try {
            while (stmt.step()) {
                val node = rowToFigmaNode(stmt)
                val count = stmt.columnInt(29)
                results.add(Pair(node, count))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun queryFigmaNodesByIds(fileKey: String, nodeIds: List<String>): List<FigmaNode> {
        if (nodeIds.isEmpty()) return emptyList()
        val byId = mutableMapOf<String, FigmaNode>()
        for (chunk in nodeIds.chunked(250)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val stmt = db.prepare(
                """
                SELECT rowid_pk, node_id, file_key, page_name, section_id, parent_id,
                       name, node_type, layout_mode, sizing_horizontal, sizing_vertical,
                       text_auto_resize, padding_left, padding_right, padding_top, padding_bottom,
                       item_spacing, local_x, local_y, canvas_x, canvas_y, width, height,
                       is_interactive, has_emoji, unbound_fill, unbound_stroke, wcag_contrast,
                       semiotic_layer
                FROM figma_nodes
                WHERE file_key = ? AND node_id IN ($placeholders);
                """.trimIndent()
            )
            try {
                stmt.bindString(1, fileKey)
                chunk.forEachIndexed { idx, id -> stmt.bindString(2 + idx, id) }
                while (stmt.step()) {
                    val node = rowToFigmaNode(stmt)
                    byId[node.nodeId] = node
                }
            } finally {
                stmt.close()
            }
        }
        return nodeIds.mapNotNull { byId[it] }
    }

    fun findFigmaShortestPath(
        fileKey: String,
        sourceTarget: String,
        destTarget: String,
        depth: Int = 10
    ): List<FigmaNode> {
        val srcNode = queryFigmaNodeById(fileKey, sourceTarget)
            ?: queryFigmaNodesByName(fileKey, sourceTarget).firstOrNull()
        val dstNode = queryFigmaNodeById(fileKey, destTarget)
            ?: queryFigmaNodesByName(fileKey, destTarget).firstOrNull()
        if (srcNode == null || dstNode == null) return emptyList()
        if (srcNode.nodeId == dstNode.nodeId) return listOf(srcNode)

        val queue = mutableListOf<Pair<String, List<String>>>()
        val visited = mutableSetOf<String>()
        queue.add(Pair(srcNode.nodeId, listOf(srcNode.nodeId)))
        visited.add(srcNode.nodeId)

        val edgeStmt = db.prepare(
            """
            SELECT target_node_id FROM figma_edges
            WHERE file_key = ? AND source_node_id = ?
            UNION
            SELECT e.target_node_id FROM figma_edges e
            JOIN figma_nodes n ON e.source_node_id = n.node_id AND e.file_key = n.file_key
            WHERE e.file_key = ? AND n.parent_id = ?;
            """.trimIndent()
        )
        try {
            var head = 0
            while (head < queue.size) {
                val (curr, path) = queue[head++]
                if (path.size > depth) break

                edgeStmt.reset()
                edgeStmt.clearBindings()
                edgeStmt.bindString(1, fileKey)
                edgeStmt.bindString(2, curr)
                edgeStmt.bindString(3, fileKey)
                edgeStmt.bindString(4, curr)

                while (edgeStmt.step()) {
                    val nextId = edgeStmt.columnString(0)
                    if (nextId == dstNode.nodeId) {
                        val fullPathIds = path + nextId
                        return queryFigmaNodesByIds(fileKey, fullPathIds)
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

    fun countFigmaNodes(fileKey: String): Int {
        val stmt = db.prepare("SELECT COUNT(*) FROM figma_nodes WHERE file_key = ?;")
        try {
            stmt.bindString(1, fileKey)
            return if (stmt.step()) stmt.columnInt(0) else 0
        } finally {
            stmt.close()
        }
    }

    fun queryFigmaNodesByNameLike(fileKey: String, substring: String): List<FigmaNode> {
        val escaped = substring
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val stmt = db.prepare(
            """
            SELECT rowid_pk, node_id, file_key, page_name, section_id, parent_id,
                   name, node_type, layout_mode, sizing_horizontal, sizing_vertical,
                   text_auto_resize, padding_left, padding_right, padding_top, padding_bottom,
                   item_spacing, local_x, local_y, canvas_x, canvas_y, width, height,
                   is_interactive, has_emoji, unbound_fill, unbound_stroke, wcag_contrast,
                   semiotic_layer
            FROM figma_nodes
            WHERE file_key = ? AND name LIKE ? ESCAPE '\'
            ORDER BY name ASC;
            """.trimIndent()
        )
        val results = mutableListOf<FigmaNode>()
        try {
            stmt.bindString(1, fileKey)
            stmt.bindString(2, "%$escaped%")
            while (stmt.step()) {
                results.add(rowToFigmaNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun queryDistinctInstantiatedTargets(fileKey: String): Set<String> {
        val stmt = db.prepare(
            """
            SELECT DISTINCT target_node_id
            FROM figma_edges
            WHERE file_key = ? AND edge_type = 'INSTANTIATES';
            """.trimIndent()
        )
        val targets = mutableSetOf<String>()
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                targets.add(stmt.columnString(0))
            }
        } finally {
            stmt.close()
        }
        return targets
    }

    fun queryCircularFillInHugViolations(fileKey: String): List<FigmaViolation> {
        val violations = mutableListOf<FigmaViolation>()

        val vStmt = db.prepare(
            """
            SELECT c.node_id, c.name, p.name
            FROM figma_nodes c
            JOIN figma_nodes p ON c.parent_id = p.node_id AND c.file_key = p.file_key
            WHERE c.file_key = ? AND c.sizing_vertical = 'FILL' AND p.sizing_vertical = 'HUG';
            """.trimIndent()
        )
        try {
            vStmt.bindString(1, fileKey)
            while (vStmt.step()) {
                val cId = vStmt.columnString(0)
                val cName = vStmt.columnString(1)
                val pName = vStmt.columnString(2)
                violations.add(
                    FigmaViolation(
                        rule = "CIRCULAR_V_FILL_IN_HUG",
                        nodeId = cId,
                        nodeName = cName,
                        message = "Child node '$cName' has vertical sizing FILL inside parent '$pName' with vertical sizing HUG"
                    )
                )
            }
        } finally {
            vStmt.close()
        }

        val hStmt = db.prepare(
            """
            SELECT c.node_id, c.name, p.name
            FROM figma_nodes c
            JOIN figma_nodes p ON c.parent_id = p.node_id AND c.file_key = p.file_key
            WHERE c.file_key = ? AND c.sizing_horizontal = 'FILL' AND p.sizing_horizontal = 'HUG';
            """.trimIndent()
        )
        try {
            hStmt.bindString(1, fileKey)
            while (hStmt.step()) {
                val cId = hStmt.columnString(0)
                val cName = hStmt.columnString(1)
                val pName = hStmt.columnString(2)
                violations.add(
                    FigmaViolation(
                        rule = "CIRCULAR_H_FILL_IN_HUG",
                        nodeId = cId,
                        nodeName = cName,
                        message = "Child node '$cName' has horizontal sizing FILL inside parent '$pName' with horizontal sizing HUG"
                    )
                )
            }
        } finally {
            hStmt.close()
        }

        return violations
    }

    fun queryStaticContainerFrameViolations(fileKey: String): List<FigmaViolation> {
        val stmt = db.prepare(
            """
            SELECT node_id, name, node_type
            FROM figma_nodes
            WHERE file_key = ?
              AND layout_mode = 'NONE'
              AND (
                  node_type IN ('COMPONENT', 'COMPONENT_SET')
                  OR is_interactive = 1
                  OR LOWER(name) LIKE '%button%'
                  OR LOWER(name) LIKE '%chip%'
                  OR LOWER(name) LIKE '%card%'
                  OR LOWER(name) LIKE '%input%'
              )
              AND node_type NOT IN ('CANVAS_SECTION', 'SECTION', 'PAGE', 'DOCUMENT', 'VECTOR', 'RECTANGLE', 'TEXT', 'ELLIPSE', 'LINE', 'STAR', 'REGULAR_POLYGON', 'GROUP');
            """.trimIndent()
        )
        val violations = mutableListOf<FigmaViolation>()
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                val id = stmt.columnString(0)
                val name = stmt.columnString(1)
                val type = stmt.columnString(2)
                violations.add(
                    FigmaViolation(
                        rule = "STATIC_CONTAINER_FRAME",
                        nodeId = id,
                        nodeName = name,
                        message = "Interactive container or component '$name' ($type) uses static layoutMode NONE instead of AutoLayout"
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return violations
    }

    fun queryDefault100PxFrameHeightViolations(fileKey: String): List<FigmaViolation> {
        val stmt = db.prepare(
            """
            SELECT node_id, name
            FROM figma_nodes
            WHERE file_key = ?
              AND node_type IN ('FRAME', 'COMPONENT', 'INSTANCE')
              AND abs(height - 100.0) < 0.001
              AND sizing_vertical = 'FIXED'
              AND (parent_id IS NOT NULL OR node_type = 'FRAME');
            """.trimIndent()
        )
        val violations = mutableListOf<FigmaViolation>()
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                val id = stmt.columnString(0)
                val name = stmt.columnString(1)
                violations.add(
                    FigmaViolation(
                        rule = "DEFAULT_100PX_FRAME_HEIGHT",
                        nodeId = id,
                        nodeName = name,
                        message = "Frame '$name' has default fixed height 100.0px (stuck at figma.createFrame() default)"
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return violations
    }

    fun queryMinTouchTargetViolations(fileKey: String): List<FigmaViolation> {
        val stmt = db.prepare(
            """
            SELECT c.node_id, c.name, c.width, c.height
            FROM figma_nodes c
            LEFT JOIN figma_nodes p ON c.parent_id = p.node_id AND c.file_key = p.file_key
            WHERE c.file_key = ?
              AND c.is_interactive = 1
              AND (c.width < 48.0 OR c.height < 48.0)
              AND (
                  p.node_id IS NULL
                  OR p.width < 48.0
                  OR p.height < 48.0
                  OR p.layout_mode = 'NONE'
                  OR (
                      p.node_type != 'TOUCH_TARGET_FRAME'
                      AND LOWER(p.name) NOT LIKE '%touch%'
                      AND LOWER(p.name) NOT LIKE '%hitbox%'
                      AND (p.width > 80.0 OR p.height > 80.0)
                      AND (SELECT COUNT(*) FROM figma_nodes WHERE parent_id = p.node_id AND file_key = c.file_key) > 1
                  )
              );
            """.trimIndent()
        )
        val violations = mutableListOf<FigmaViolation>()
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                val id = stmt.columnString(0)
                val name = stmt.columnString(1)
                val w = stmt.columnDouble(2)
                val h = stmt.columnDouble(3)
                violations.add(
                    FigmaViolation(
                        rule = "MIN_TOUCH_TARGET",
                        nodeId = id,
                        nodeName = name,
                        message = "Interactive control '$name' has hitbox dimensions ${w}x${h} (< 48x48dp) without a >= 48dp AutoLayout container"
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return violations
    }

    fun queryNegativeCoordinateViolations(fileKey: String): List<FigmaViolation> {
        val stmt = db.prepare(
            """
            SELECT node_id, name, local_x, local_y
            FROM figma_nodes
            WHERE file_key = ?
              AND (local_x < -0.001 OR local_y < -0.001)
              AND parent_id IS NOT NULL;
            """.trimIndent()
        )
        val violations = mutableListOf<FigmaViolation>()
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                val id = stmt.columnString(0)
                val name = stmt.columnString(1)
                val lx = stmt.columnDouble(2)
                val ly = stmt.columnDouble(3)
                violations.add(
                    FigmaViolation(
                        rule = "NEGATIVE_COORDINATES",
                        nodeId = id,
                        nodeName = name,
                        message = "Node '$name' has negative local offset coordinates (localX=$lx, localY=$ly)"
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return violations
    }

    fun queryChildAmputationWidthViolations(fileKey: String): List<FigmaViolation> {
        val stmt = db.prepare(
            """
            SELECT c.node_id, c.name, c.width, p.name, p.width
            FROM figma_nodes c
            JOIN figma_nodes p ON c.parent_id = p.node_id AND c.file_key = p.file_key
            WHERE c.file_key = ?
              AND p.layout_mode IN ('HORIZONTAL', 'VERTICAL')
              AND (p.padding_left > 0.0 OR p.padding_right > 0.0)
              AND c.sizing_horizontal = 'FIXED'
              AND c.width >= (p.width - 0.001)
              AND p.width > 0.0;
            """.trimIndent()
        )
        val violations = mutableListOf<FigmaViolation>()
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                val cId = stmt.columnString(0)
                val cName = stmt.columnString(1)
                val cWidth = stmt.columnDouble(2)
                val pName = stmt.columnString(3)
                val pWidth = stmt.columnDouble(4)
                violations.add(
                    FigmaViolation(
                        rule = "CHILD_AMPUTATION_WIDTH",
                        nodeId = cId,
                        nodeName = cName,
                        message = "Child node '$cName' has fixed width ($cWidth) >= parent width ($pWidth) inside padded AutoLayout parent '$pName' (must use layoutSizingHorizontal = FILL)"
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return violations
    }

    fun queryNoEmojiViolations(fileKey: String): List<FigmaViolation> {
        val stmt = db.prepare(
            """
            SELECT node_id, name
            FROM figma_nodes
            WHERE file_key = ? AND has_emoji = 1;
            """.trimIndent()
        )
        val violations = mutableListOf<FigmaViolation>()
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                val id = stmt.columnString(0)
                val name = stmt.columnString(1)
                violations.add(
                    FigmaViolation(
                        rule = "NO_EMOJI",
                        nodeId = id,
                        nodeName = name,
                        message = "Node '$name' contains unicode emojis in node name or text content"
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return violations
    }

    fun queryUnboundTokenViolations(fileKey: String): List<FigmaViolation> {
        val totalCount = countFigmaNodes(fileKey)
        if (totalCount == 0) return emptyList()

        var unboundCount = 0
        val unbStmt = db.prepare(
            "SELECT COUNT(*) FROM figma_nodes WHERE file_key = ? AND (unbound_fill = 1 OR unbound_stroke = 1);"
        )
        try {
            unbStmt.bindString(1, fileKey)
            if (unbStmt.step()) {
                unboundCount = unbStmt.columnInt(0)
            }
        } finally {
            unbStmt.close()
        }

        val ratio = unboundCount.toDouble() / totalCount.toDouble()
        if (ratio <= 0.05) return emptyList()

        val violations = mutableListOf<FigmaViolation>()
        val stmt = db.prepare(
            """
            SELECT node_id, name, unbound_fill, unbound_stroke
            FROM figma_nodes
            WHERE file_key = ? AND (unbound_fill = 1 OR unbound_stroke = 1);
            """.trimIndent()
        )
        try {
            stmt.bindString(1, fileKey)
            val pctStr = (ratio * 100.0).toString()
            val shortPct = if (pctStr.length > 5) pctStr.substring(0, 5) else pctStr
            while (stmt.step()) {
                val id = stmt.columnString(0)
                val name = stmt.columnString(1)
                val uf = stmt.columnInt(2) != 0
                val us = stmt.columnInt(3) != 0
                val kind = if (uf && us) "fill and stroke" else if (uf) "fill" else "stroke"
                violations.add(
                    FigmaViolation(
                        rule = "UNBOUND_TOKENS",
                        nodeId = id,
                        nodeName = name,
                        message = "Node '$name' has unbound $kind (file unbound ratio: $shortPct%, exceeds 5% threshold)"
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return violations
    }

    fun queryAdHocDetachedSpecimenViolations(fileKey: String): List<FigmaViolation> {
        val stmt = db.prepare(
            """
            SELECT n.node_id, n.name, p.name
            FROM figma_nodes n
            JOIN figma_nodes p ON n.parent_id = p.node_id AND n.file_key = p.file_key
            WHERE n.file_key = ?
              AND n.node_type = 'FRAME'
              AND (
                  LOWER(p.name) LIKE '%gym%'
                  OR LOWER(p.name) LIKE '%arena%'
                  OR LOWER(p.name) LIKE '%bay%'
                  OR (n.canvas_x >= 3800.0 AND n.canvas_y >= 4800.0 AND p.parent_id IS NOT NULL)
              )
              AND LOWER(n.name) NOT LIKE '%gym%'
              AND LOWER(n.name) NOT LIKE '%arena%'
              AND LOWER(n.name) NOT LIKE '%bay%';
            """.trimIndent()
        )
        val violations = mutableListOf<FigmaViolation>()
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                val id = stmt.columnString(0)
                val name = stmt.columnString(1)
                val pName = stmt.columnString(2)
                violations.add(
                    FigmaViolation(
                        rule = "AD_HOC_DETACHED_SPECIMEN",
                        nodeId = id,
                        nodeName = name,
                        message = "Node '$name' is a detached static FRAME inside gym container '$pName'; must instantiate a live master component INSTANCE"
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return violations
    }

    fun queryCanvasSpatialTopologyViolations(fileKey: String): List<FigmaViolation> {
        val stmt = db.prepare(
            """
            SELECT n.node_id, n.name, s.min_x, s.min_y
            FROM figma_nodes n
            JOIN figma_spatial_index s ON n.rowid_pk = s.id
            WHERE n.file_key = ?
              AND n.node_type NOT IN ('DOCUMENT', 'PAGE')
              AND (n.node_type IN ('CANVAS_SECTION', 'SECTION') OR n.parent_id IS NULL)
              AND (
                  (abs(s.min_y) > 200.0 AND (s.min_y < 4500.0 OR s.min_y > 15000.0))
                  OR s.min_y < -100.0 OR s.min_x < -100.0
                  OR ((LOWER(n.name) LIKE '%flow%' OR n.name LIKE '0%' OR LOWER(n.name) LIKE 'section 0%') AND abs(s.min_y) > 200.0)
                  OR (LOWER(n.name) LIKE '%design system%' AND (abs(s.min_x) > 200.0 OR abs(s.min_y - 5000.0) > 500.0))
                  OR (LOWER(n.name) LIKE '%gym%' AND (s.min_x < 3800.0 OR s.min_y < 4500.0))
              );
            """.trimIndent()
        )
        val violations = mutableListOf<FigmaViolation>()
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                val id = stmt.columnString(0)
                val name = stmt.columnString(1)
                val mx = stmt.columnDouble(2)
                val my = stmt.columnDouble(3)
                violations.add(
                    FigmaViolation(
                        rule = "CANVAS_SPATIAL_TOPOLOGY",
                        nodeId = id,
                        nodeName = name,
                        message = "Top-level canvas section '$name' at (x=$mx, y=$my) violates the 2D matrix spatial grid (Row 1 at y=0, Design System at y=5000/x=0, Gyms at y>=5000/x>=4000)"
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return violations
    }

    fun queryWcagContrastViolations(fileKey: String): List<FigmaViolation> {
        val stmt = db.prepare(
            """
            SELECT node_id, name, wcag_contrast, height
            FROM figma_nodes
            WHERE file_key = ?
              AND wcag_contrast IS NOT NULL
              AND (
                  (height >= 24.0 AND wcag_contrast < 3.0)
                  OR (height < 24.0 AND wcag_contrast < 4.5)
              );
            """.trimIndent()
        )
        val violations = mutableListOf<FigmaViolation>()
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                val id = stmt.columnString(0)
                val name = stmt.columnString(1)
                val contrast = stmt.columnDouble(2)
                val height = stmt.columnDouble(3)
                val req = if (height >= 24.0) "3.0:1 for large text" else "4.5:1 for normal text"
                violations.add(
                    FigmaViolation(
                        rule = "WCAG_CONTRAST",
                        nodeId = id,
                        nodeName = name,
                        message = "Node '$name' has WCAG contrast ratio $contrast:1 (below required $req)"
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return violations
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

    // =========================================================================
    // Jetpack Compose & KMP Stability & Compliance DAO Operations
    // =========================================================================

    fun provenanceRank(provenance: String): Int = when (provenance.uppercase()) {
        "RUNTIME_TRACED" -> 4
        "COMPILER_VERIFIED" -> 3
        "STABILITY_CONFIG" -> 2
        else -> 1 // STATIC_INFERRED
    }

    fun upsertComposeNodesAndParams(
        nodes: List<ComposeNodeRecord>,
        params: List<ComposeParamRecord>,
        preserveHigherProvenance: Boolean = true
    ) {
        if (nodes.isEmpty() && params.isEmpty()) return
        db.withSavepoint("sp_compose_upsert") {
            val existingProv = mutableMapOf<String, Int>()
            if (preserveHigherProvenance && nodes.isNotEmpty()) {
                val pSlug = nodes.first().projectSlug
                val qStmt = db.prepare("SELECT node_id, provenance FROM compose_nodes WHERE project_slug = ?;")
                try {
                    qStmt.bindString(1, pSlug)
                    while (qStmt.step()) {
                        existingProv[qStmt.columnString(0)] = provenanceRank(qStmt.columnString(1))
                    }
                } finally {
                    qStmt.close()
                }
            }

            val nodeStmt = db.prepare(
                """
                INSERT OR REPLACE INTO compose_nodes (
                    node_id, project_slug, fqname, file_path, start_line, end_line,
                    source_set, is_restartable, is_skippable, is_readonly, is_inline,
                    returns_unit, has_modifier_param, modifier_is_first_optional,
                    modifier_has_default, unstable_param_count, runtime_param_count,
                    receiver_stability, provenance, violation_mask
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """.trimIndent()
            )
            try {
                for (n in nodes) {
                    val prevRank = existingProv[n.nodeId] ?: 0
                    val newRank = provenanceRank(n.provenance)
                    if (preserveHigherProvenance && prevRank > newRank) {
                        val updStmt = db.prepare(
                            """
                            UPDATE compose_nodes
                            SET start_line = ?, end_line = ?, violation_mask = ?
                            WHERE node_id = ?;
                            """.trimIndent()
                        )
                        try {
                            updStmt.bindInt(1, n.startLine)
                            updStmt.bindInt(2, n.endLine)
                            updStmt.bindInt(3, n.violationMask)
                            updStmt.bindString(4, n.nodeId)
                            updStmt.step()
                        } finally {
                            updStmt.close()
                        }
                        continue
                    }
                    nodeStmt.reset()
                    nodeStmt.bindString(1, n.nodeId)
                    nodeStmt.bindString(2, n.projectSlug)
                    nodeStmt.bindString(3, n.fqName)
                    nodeStmt.bindString(4, n.filePath)
                    nodeStmt.bindInt(5, n.startLine)
                    nodeStmt.bindInt(6, n.endLine)
                    nodeStmt.bindString(7, n.sourceSet)
                    nodeStmt.bindInt(8, if (n.isRestartable) 1 else 0)
                    nodeStmt.bindInt(9, if (n.isSkippable) 1 else 0)
                    nodeStmt.bindInt(10, if (n.isReadonly) 1 else 0)
                    nodeStmt.bindInt(11, if (n.isInline) 1 else 0)
                    nodeStmt.bindInt(12, if (n.returnsUnit) 1 else 0)
                    nodeStmt.bindInt(13, if (n.hasModifierParam) 1 else 0)
                    nodeStmt.bindInt(14, if (n.modifierIsFirstOptional) 1 else 0)
                    nodeStmt.bindInt(15, if (n.modifierHasDefault) 1 else 0)
                    nodeStmt.bindInt(16, n.unstableParamCount)
                    nodeStmt.bindInt(17, n.runtimeParamCount)
                    nodeStmt.bindString(18, n.receiverStability)
                    nodeStmt.bindString(19, n.provenance)
                    nodeStmt.bindInt(20, n.violationMask)
                    nodeStmt.step()
                }
            } finally {
                nodeStmt.close()
            }

            val paramStmt = db.prepare(
                """
                INSERT OR REPLACE INTO compose_params (
                    node_id, param_index, param_name, type_signature,
                    stability, stability_reason, has_default, is_used_in_body, provenance
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
                """.trimIndent()
            )
            try {
                for (p in params) {
                    val prevRank = existingProv[p.nodeId] ?: 0
                    val newRank = provenanceRank(p.provenance)
                    if (preserveHigherProvenance && prevRank > newRank) continue
                    paramStmt.reset()
                    paramStmt.bindString(1, p.nodeId)
                    paramStmt.bindInt(2, p.paramIndex)
                    paramStmt.bindString(3, p.paramName)
                    paramStmt.bindString(4, p.typeSignature)
                    paramStmt.bindString(5, p.stability)
                    paramStmt.bindString(6, p.stabilityReason)
                    paramStmt.bindInt(7, if (p.hasDefault) 1 else 0)
                    paramStmt.bindInt(8, if (p.isUsedInBody) 1 else 0)
                    paramStmt.bindString(9, p.provenance)
                    paramStmt.step()
                }
            } finally {
                paramStmt.close()
            }
        }
    }

    fun deleteComposeNodesForFile(projectSlug: String, filePath: String) {
        db.withSavepoint("sp_compose_del_file") {
            val delParams = db.prepare(
                """
                DELETE FROM compose_params
                WHERE node_id IN (
                    SELECT node_id FROM compose_nodes WHERE project_slug = ? AND file_path = ?
                );
                """.trimIndent()
            )
            try {
                delParams.bindString(1, projectSlug)
                delParams.bindString(2, filePath)
                delParams.step()
            } finally {
                delParams.close()
            }

            val delNodes = db.prepare("DELETE FROM compose_nodes WHERE project_slug = ? AND file_path = ?;")
            try {
                delNodes.bindString(1, projectSlug)
                delNodes.bindString(2, filePath)
                delNodes.step()
            } finally {
                delNodes.close()
            }
        }
    }

    fun upsertComposeTraceMetric(
        nodeId: String,
        sessionId: String,
        recompositionCount: Int,
        skipCount: Int,
        dirtyParamsJson: String,
        recordedAt: Long
    ) {
        db.withSavepoint("sp_compose_trace") {
            val stmt = db.prepare(
                """
                INSERT OR REPLACE INTO compose_trace_metrics (
                    node_id, session_id, recomposition_count, skip_count, dirty_params_json, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?);
                """.trimIndent()
            )
            try {
                stmt.bindString(1, nodeId)
                stmt.bindString(2, sessionId)
                stmt.bindInt(3, recompositionCount)
                stmt.bindInt(4, skipCount)
                stmt.bindString(5, dirtyParamsJson)
                stmt.bindLong(6, recordedAt)
                stmt.step()
            } finally {
                stmt.close()
            }

            val upd = db.prepare("UPDATE compose_nodes SET provenance = 'RUNTIME_TRACED' WHERE node_id = ?;")
            try {
                upd.bindString(1, nodeId)
                upd.step()
            } finally {
                upd.close()
            }
        }
    }

    fun listComposeNodes(projectSlug: String, onlyViolating: Boolean = false): List<ComposeNodeRecord> {
        val sql = if (onlyViolating) {
            """
            SELECT node_id, project_slug, fqname, file_path, start_line, end_line,
                   source_set, is_restartable, is_skippable, is_readonly, is_inline,
                   returns_unit, has_modifier_param, modifier_is_first_optional,
                   modifier_has_default, unstable_param_count, runtime_param_count,
                   receiver_stability, provenance, violation_mask
            FROM compose_nodes
            WHERE project_slug = ? AND violation_mask != 0
            ORDER BY file_path ASC, start_line ASC;
            """.trimIndent()
        } else {
            """
            SELECT node_id, project_slug, fqname, file_path, start_line, end_line,
                   source_set, is_restartable, is_skippable, is_readonly, is_inline,
                   returns_unit, has_modifier_param, modifier_is_first_optional,
                   modifier_has_default, unstable_param_count, runtime_param_count,
                   receiver_stability, provenance, violation_mask
            FROM compose_nodes
            WHERE project_slug = ?
            ORDER BY file_path ASC, start_line ASC;
            """.trimIndent()
        }
        val stmt = db.prepare(sql)
        val out = mutableListOf<ComposeNodeRecord>()
        try {
            stmt.bindString(1, projectSlug)
            while (stmt.step()) {
                out.add(rowToComposeNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return out
    }

    fun getComposeNode(projectSlug: String, target: String): ComposeNodeRecord? {
        val stmt = db.prepare(
            """
            SELECT node_id, project_slug, fqname, file_path, start_line, end_line,
                   source_set, is_restartable, is_skippable, is_readonly, is_inline,
                   returns_unit, has_modifier_param, modifier_is_first_optional,
                   modifier_has_default, unstable_param_count, runtime_param_count,
                   receiver_stability, provenance, violation_mask
            FROM compose_nodes
            WHERE project_slug = ?
              AND (node_id = ? OR fqname = ? OR fqname LIKE ?)
            ORDER BY CASE WHEN node_id = ? THEN 0 WHEN fqname = ? THEN 1 ELSE 2 END
            LIMIT 1;
            """.trimIndent()
        )
        try {
            stmt.bindString(1, projectSlug)
            stmt.bindString(2, target)
            stmt.bindString(3, target)
            stmt.bindString(4, "%.$target")
            stmt.bindString(5, target)
            stmt.bindString(6, target)
            if (stmt.step()) {
                return rowToComposeNode(stmt)
            }
        } finally {
            stmt.close()
        }
        return null
    }

    fun listComposeParams(nodeId: String): List<ComposeParamRecord> {
        val stmt = db.prepare(
            """
            SELECT node_id, param_index, param_name, type_signature,
                   stability, stability_reason, has_default, is_used_in_body, provenance
            FROM compose_params
            WHERE node_id = ?
            ORDER BY param_index ASC;
            """.trimIndent()
        )
        val out = mutableListOf<ComposeParamRecord>()
        try {
            stmt.bindString(1, nodeId)
            while (stmt.step()) {
                out.add(rowToComposeParam(stmt))
            }
        } finally {
            stmt.close()
        }
        return out
    }

    fun listComposeParamsForProject(projectSlug: String): Map<String, List<ComposeParamRecord>> {
        val stmt = db.prepare(
            """
            SELECT p.node_id, p.param_index, p.param_name, p.type_signature,
                   p.stability, p.stability_reason, p.has_default, p.is_used_in_body, p.provenance
            FROM compose_params p
            JOIN compose_nodes n ON n.node_id = p.node_id
            WHERE n.project_slug = ?
            ORDER BY p.node_id ASC, p.param_index ASC;
            """.trimIndent()
        )
        val out = mutableMapOf<String, MutableList<ComposeParamRecord>>()
        try {
            stmt.bindString(1, projectSlug)
            while (stmt.step()) {
                val rec = rowToComposeParam(stmt)
                out.getOrPut(rec.nodeId) { mutableListOf() }.add(rec)
            }
        } finally {
            stmt.close()
        }
        return out
    }

    fun listComposeTraceMetrics(projectSlug: String): Map<String, Triple<Int, Int, String>> {
        val stmt = db.prepare(
            """
            SELECT m.node_id, SUM(m.recomposition_count), SUM(m.skip_count), MAX(m.dirty_params_json)
            FROM compose_trace_metrics m
            JOIN compose_nodes n ON n.node_id = m.node_id
            WHERE n.project_slug = ?
            GROUP BY m.node_id;
            """.trimIndent()
        )
        val out = mutableMapOf<String, Triple<Int, Int, String>>()
        try {
            stmt.bindString(1, projectSlug)
            while (stmt.step()) {
                out[stmt.columnString(0)] = Triple(
                    stmt.columnInt(1),
                    stmt.columnInt(2),
                    stmt.columnString(3)
                )
            }
        } finally {
            stmt.close()
        }
        return out
    }

    fun queryComposeCascade(
        projectSlug: String,
        rootNodeId: String,
        maxDepth: Int = 5
    ): List<ComposeQueryResult.CascadeHop> {
        val boundedDepth = maxDepth.coerceIn(1, 8)
        val stmt = db.prepare(
            """
            WITH RECURSIVE cascade_cte(caller_id, callee_id, depth, edge_kind, path) AS (
                SELECT e.source_node_id, e.target_node_id, 1, e.edge_type,
                       '|' || e.source_node_id || '|' || e.target_node_id || '|'
                FROM edges e
                WHERE e.project_id = ?
                  AND e.source_node_id = ?
                  AND e.edge_type IN ('CALLS_COMPOSABLE', 'CALLS', 'EXPECT_ACTUAL')
                UNION ALL
                SELECT e.source_node_id, e.target_node_id, c.depth + 1, e.edge_type,
                       c.path || e.target_node_id || '|'
                FROM cascade_cte c
                JOIN edges e ON e.project_id = ? AND e.source_node_id = c.callee_id
                WHERE e.edge_type IN ('CALLS_COMPOSABLE', 'CALLS', 'EXPECT_ACTUAL')
                  AND c.depth < ?
                  AND INSTR(c.path, '|' || e.target_node_id || '|') = 0
            )
            SELECT c.caller_id, c.callee_id, cn.fqname, cn.file_path, cn.start_line,
                   c.depth, cn.is_skippable, c.edge_kind,
                   COALESCE(
                       (SELECT p.param_name FROM compose_params p
                        WHERE p.node_id = c.callee_id
                          AND p.stability IN ('UNSTABLE', 'RUNTIME', 'UNCERTAIN')
                        ORDER BY p.param_index ASC LIMIT 1),
                       ''
                   ) AS forwarded_param
            FROM cascade_cte c
            JOIN compose_nodes cn ON cn.node_id = c.callee_id
            ORDER BY c.depth ASC, cn.unstable_param_count DESC;
            """.trimIndent()
        )
        val hops = mutableListOf<ComposeQueryResult.CascadeHop>()
        val seen = mutableSetOf<String>()
        try {
            stmt.bindString(1, projectSlug)
            stmt.bindString(2, rootNodeId)
            stmt.bindString(3, projectSlug)
            stmt.bindInt(4, boundedDepth)
            while (stmt.step()) {
                val callerId = stmt.columnString(0)
                val calleeId = stmt.columnString(1)
                val key = "$callerId->$calleeId"
                if (seen.add(key)) {
                    hops.add(
                        ComposeQueryResult.CascadeHop(
                            callerId = callerId,
                            calleeId = calleeId,
                            calleeFqName = stmt.columnString(2),
                            filePath = stmt.columnString(3),
                            line = stmt.columnInt(4),
                            depth = stmt.columnInt(5),
                            forwardedParam = stmt.columnString(8),
                            calleeSkippable = stmt.columnInt(6) != 0,
                            viaEdgeKind = stmt.columnString(7)
                        )
                    )
                }
            }
        } finally {
            stmt.close()
        }
        return hops
    }

    fun queryComposeBlame(
        projectSlug: String,
        composableId: String
    ): List<ComposeQueryResult.BlameHop> {
        val params = listComposeParams(composableId)
            .filter { it.stability in setOf("UNSTABLE", "RUNTIME", "UNCERTAIN") }
        if (params.isEmpty()) return emptyList()

        val hops = mutableListOf<ComposeQueryResult.BlameHop>()
        for (p in params) {
            val cleanType = p.typeSignature
                .substringBefore('<')
                .substringAfterLast('.')
                .removeSuffix("?")
                .trim()
            val nodeStmt = db.prepare(
                """
                SELECT node_id, file_id, start_line, signature
                FROM nodes
                WHERE project_id = ? AND name = ? AND kind IN ('class', 'interface')
                LIMIT 1;
                """.trimIndent()
            )
            var rootNodeId: String? = null
            var rootFile: String? = null
            var rootLine: Int? = null
            try {
                nodeStmt.bindString(1, projectSlug)
                nodeStmt.bindString(2, cleanType)
                if (nodeStmt.step()) {
                    rootNodeId = nodeStmt.columnString(0)
                    rootFile = nodeStmt.columnString(1).substringAfter(':')
                    rootLine = nodeStmt.columnInt(2)
                }
            } finally {
                nodeStmt.close()
            }
            hops.add(
                ComposeQueryResult.BlameHop(
                    composableId = composableId,
                    paramName = p.paramName,
                    typeSignature = p.typeSignature,
                    stability = p.stability,
                    rootCauseNodeId = rootNodeId,
                    rootCauseFile = rootFile,
                    rootCauseLine = rootLine,
                    blameReason = p.stabilityReason
                )
            )
        }
        return hops
    }

    fun countPropertyMutations(projectSlug: String, targetSymbolOrNodeId: String): Int {
        val cleanSym = targetSymbolOrNodeId.removePrefix("sym:")
        val stmt = db.prepare(
            """
            SELECT COUNT(*) FROM edges
            WHERE project_id = ?
              AND (target_node_id = ? OR target_node_id = ? OR target_node_id LIKE ?)
              AND edge_type = 'MUTATES_PROPERTY';
            """.trimIndent()
        )
        try {
            stmt.bindString(1, projectSlug)
            stmt.bindString(2, targetSymbolOrNodeId)
            stmt.bindString(3, "sym:$cleanSym")
            stmt.bindString(4, "%#$cleanSym")
            if (stmt.step()) {
                return stmt.columnInt(0)
            }
        } finally {
            stmt.close()
        }
        return 0
    }

    private fun rowToComposeNode(stmt: NativeSqliteStatement): ComposeNodeRecord {
        return ComposeNodeRecord(
            nodeId = stmt.columnString(0),
            projectSlug = stmt.columnString(1),
            fqName = stmt.columnString(2),
            filePath = stmt.columnString(3),
            startLine = stmt.columnInt(4),
            endLine = stmt.columnInt(5),
            sourceSet = stmt.columnString(6),
            isRestartable = stmt.columnInt(7) != 0,
            isSkippable = stmt.columnInt(8) != 0,
            isReadonly = stmt.columnInt(9) != 0,
            isInline = stmt.columnInt(10) != 0,
            returnsUnit = stmt.columnInt(11) != 0,
            hasModifierParam = stmt.columnInt(12) != 0,
            modifierIsFirstOptional = stmt.columnInt(13) != 0,
            modifierHasDefault = stmt.columnInt(14) != 0,
            unstableParamCount = stmt.columnInt(15),
            runtimeParamCount = stmt.columnInt(16),
            receiverStability = stmt.columnString(17),
            provenance = stmt.columnString(18),
            violationMask = stmt.columnInt(19)
        )
    }

    private fun rowToComposeParam(stmt: NativeSqliteStatement): ComposeParamRecord {
        return ComposeParamRecord(
            nodeId = stmt.columnString(0),
            paramIndex = stmt.columnInt(1),
            paramName = stmt.columnString(2),
            typeSignature = stmt.columnString(3),
            stability = stmt.columnString(4),
            stabilityReason = stmt.columnString(5),
            hasDefault = stmt.columnInt(6) != 0,
            isUsedInBody = stmt.columnInt(7) != 0,
            provenance = stmt.columnString(8)
        )
    }
}


