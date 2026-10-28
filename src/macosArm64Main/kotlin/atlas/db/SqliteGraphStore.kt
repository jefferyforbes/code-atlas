package atlas.db

import atlas.extract.NameSegmentTokenizer
import atlas.extract.UrlTargetResolver
import atlas.model.*
import atlas.util.PlatformFiles
import atlas.util.Sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.abs
import kotlin.math.ln

/**
 * Persistent SQLite WAL knowledge graph store with FTS5, segment vocabulary, and freshness slicing.
 */
class SqliteGraphStore(val dbPath: String) {

    private val db = NativeSqliteDatabase(dbPath)
    private val figmaDao = FigmaGraphStoreDao(db)
    private val composeDao = ComposeGraphStoreDao(db)
    private val traversalDao = GraphTraversalDao(
        db = db,
        figmaDao = figmaDao,
        nodeLookup = ::getNode,
        fileNodesLookup = ::getNodesForFile,
        rowToNode = ::rowToNode
    )

    init {
        initSchema()
    }

    private fun migrateLegacySchema() {
        try {
            var sqlDef = ""
            db.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name='figma_nodes';").use { stmt ->
                if (stmt.step()) {
                    sqlDef = stmt.columnString(0)
                }
            }
            if (sqlDef.contains("node_id TEXT NOT NULL UNIQUE")) {
                db.exec("DROP TABLE IF EXISTS figma_spatial_index;")
                db.exec("DROP TABLE IF EXISTS figma_nodes;")
            }
        } catch (e: Exception) {
            PlatformFiles.logWarning("SqliteGraphStore", "legacy schema migration failed: ${e.message}")
        }
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
                is_top_level INTEGER NOT NULL DEFAULT 1,
                cyclomatic_complexity INTEGER NOT NULL DEFAULT 1,
                cognitive_complexity INTEGER NOT NULL DEFAULT 0,
                max_nesting_depth INTEGER NOT NULL DEFAULT 0,
                complexity_score REAL NOT NULL DEFAULT 100.0
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
                node_id,
                project_id,
                name,
                qualified_name,
                signature,
                file_id UNINDEXED
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

    private fun queryTableColumns(tableName: String): Set<String> {
        val cols = mutableSetOf<String>()
        val stmt = db.prepare("PRAGMA table_info($tableName);")
        try {
            while (stmt.step()) {
                cols.add(stmt.columnString(1))
            }
        } finally {
            stmt.close()
        }
        return cols
    }

    private fun addMissingColumn(tableName: String, cols: Set<String>, column: String, definition: String) {
        if (column !in cols) {
            db.exec("ALTER TABLE $tableName ADD COLUMN $column $definition;")
        }
    }

    fun migrateComposeSchema() {
        try {
            val cols = queryTableColumns("compose_nodes")
            if (cols.isEmpty()) return
            addMissingColumn("compose_nodes", cols, "source_set", "TEXT NOT NULL DEFAULT 'commonMain'")
            addMissingColumn("compose_nodes", cols, "violation_mask", "INTEGER NOT NULL DEFAULT 0")
        } catch (e: Exception) {
            PlatformFiles.logWarning("SqliteGraphStore", "compose nodes schema migration failed: ${e.message}")
        }
    }

    fun migrateNodesSchema() {
        try {
            val cols = queryTableColumns("nodes")
            if (cols.isEmpty()) return
            addMissingColumn("nodes", cols, "description", "TEXT DEFAULT NULL")
            addMissingColumn("nodes", cols, "is_top_level", "INTEGER NOT NULL DEFAULT 1")
            addMissingColumn("nodes", cols, "cyclomatic_complexity", "INTEGER NOT NULL DEFAULT 1")
            addMissingColumn("nodes", cols, "cognitive_complexity", "INTEGER NOT NULL DEFAULT 0")
            addMissingColumn("nodes", cols, "max_nesting_depth", "INTEGER NOT NULL DEFAULT 0")
            addMissingColumn("nodes", cols, "complexity_score", "REAL NOT NULL DEFAULT 100.0")
            db.exec("CREATE INDEX IF NOT EXISTS idx_nodes_complexity ON nodes(project_id, cognitive_complexity) WHERE cognitive_complexity >= 10;")
        } catch (e: Exception) {
            PlatformFiles.logWarning("SqliteGraphStore", "nodes schema migration failed: ${e.message}")
        }
    }

    fun <T> withSavepoint(name: String, block: () -> T): T = db.withSavepoint(name, block)

    internal class StatementScope(private val db: NativeSqliteDatabase) {
        private val statements = mutableListOf<NativeSqliteStatement>()

        fun prepare(sql: String): NativeSqliteStatement {
            val stmt = db.prepare(sql)
            try {
                statements.add(stmt)
            } catch (t: Throwable) {
                stmt.close()
                throw t
            }
            return stmt
        }

        fun closeAll() {
            for (i in statements.indices.reversed()) {
                try { statements[i].close() } catch (e: Exception) {
                    PlatformFiles.logWarning("SqliteGraphStore", "failed to close statement: ${e.message}")
                }
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
        val stmt = db.prepare("SELECT rel_path, sha256, mtime FROM files WHERE project_id = ? AND rel_path != '__endpoints__';")
        val result = mutableMapOf<String, Pair<String, Double>>()
        try {
            stmt.bindString(1, projectId)
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

    private fun resolveTargetSymbol(projectId: String, targetNodeId: String): String? {
        if (targetNodeId.startsWith("sym:")) return targetNodeId
        if (targetNodeId.startsWith("$projectId:endpoint#") ||
            targetNodeId.startsWith("$projectId:url_ref#") ||
            targetNodeId.startsWith("endpoint#") ||
            targetNodeId.startsWith("url_ref#")
        ) {
            return targetNodeId
        }
        if (!targetNodeId.startsWith("$projectId:")) {
            return "sym:$targetNodeId"
        }
        return null
    }

    private fun bindRawSymbolEdgeRow(
        stmt: NativeSqliteStatement,
        fileContext: Pair<String, String>,
        edge: GraphEdge,
        targetSym: String
    ) {
        val (projectId, normalizedFileId) = fileContext
        stmt.reset()
        stmt.clearBindings()
        stmt.bindString(1, projectId)
        stmt.bindString(2, normalizedFileId)
        stmt.bindString(3, edge.sourceNodeId)
        stmt.bindString(4, targetSym)
        stmt.bindString(5, edge.edgeType)
        stmt.bindDouble(6, edge.confidence)
        stmt.bindString(7, edge.provenance)
        stmt.bindInt(8, edge.lineNumber)
        stmt.bindString(9, edge.edgeId)
        stmt.step()
    }

    private fun bindIndexedMarker(
        stmt: NativeSqliteStatement,
        projectId: String,
        normalizedFileId: String
    ) {
        val markerEdge = GraphEdge(
            edgeId = "",
            projectId = projectId,
            sourceNodeId = "",
            targetNodeId = "",
            edgeType = "INDEXED_MARKER",
            provenance = "EXTRACTED_AST",
            confidence = 1.0,
            lineNumber = 0
        )
        bindRawSymbolEdgeRow(stmt, projectId to normalizedFileId, markerEdge, "")
    }

    private fun replaceFileRawSymbolEdgesInBatch(
        delStmt: NativeSqliteStatement,
        insStmt: NativeSqliteStatement,
        projectId: String,
        fileId: String,
        edges: List<GraphEdge>
    ) {
        val normalizedFileId = if (fileId.startsWith("$projectId:")) fileId else "$projectId:$fileId"
        delStmt.reset()
        delStmt.clearBindings()
        delStmt.bindString(1, projectId)
        delStmt.bindString(2, normalizedFileId)
        delStmt.step()

        var insertedCount = 0
        val fileCtx = projectId to normalizedFileId
        for (edge in edges) {
            if (edge.edgeType == "CONTAINS") continue
            val targetSym = resolveTargetSymbol(projectId, edge.targetNodeId) ?: continue
            bindRawSymbolEdgeRow(insStmt, fileCtx, edge, targetSym)
            insertedCount++
        }
        if (insertedCount == 0) {
            bindIndexedMarker(insStmt, projectId, normalizedFileId)
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
                    replaceFileRawSymbolEdgesInBatch(delStmt, insStmt, projectId, fileId, edges)
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
        val results = mutableListOf<GraphEdge>()
        try {
            stmt.bindString(1, projectId)
            while (stmt.step()) {
                val rawEdgeId = stmt.columnString(0)
                val projId = stmt.columnString(1)
                val sourceNodeId = stmt.columnString(2)
                val targetSym = stmt.columnString(3)
                val edgeType = stmt.columnString(4)
                val confidence = stmt.columnDouble(5)
                val provenance = stmt.columnString(6)
                val lineNumber = stmt.columnInt(7)
                val normalizedTarget = when {
                    targetSym.startsWith("sym:") ||
                        targetSym.startsWith("$projId:endpoint#") ||
                        targetSym.startsWith("$projId:url_ref#") ||
                        targetSym.startsWith("endpoint#") ||
                        targetSym.startsWith("url_ref#") -> targetSym
                    else -> "sym:$targetSym"
                }
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
              AND edge_type NOT IN (
                  'PLAN_SCOPES_FILE', 'WIKILINK_REFS', 'CODE_SPAN_REFS', 'URL_REFS',
                  'TAGGED_WITH', 'TRIGGERS_SKILL', 'DEPENDS_ON_SKILL', 'REFERENCES_FILE', 'EXECUTES_SCRIPT'
              );
            """.trimIndent()
        )
        try {
            stmt.bindString(1, projectId)
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    fun ensureVirtualFileRecord(projectId: String, virtualRelPath: String = "__endpoints__"): String {
        val fileId = "$projectId:$virtualRelPath"
        val stmt = db.prepare(
            """
            INSERT OR IGNORE INTO files (
                file_id, project_id, rel_path, language, module_id, source_set,
                semiotic_layer, arch_layer, sha256, mtime, loc, top_level_decl_count
            ) VALUES (?, ?, ?, 'virtual', 'endpoints', 'main', 'syntactic', 'interface', 'virtual', 0.0, 0, 0);
            """.trimIndent()
        )
        try {
            stmt.bindString(1, fileId)
            stmt.bindString(2, projectId)
            stmt.bindString(3, virtualRelPath)
            stmt.step()
        } finally {
            stmt.close()
        }
        return fileId
    }

    data class FileRecordUpsert(
        val projectId: String,
        val relPath: String,
        val language: String,
        val moduleId: String,
        val sourceSet: String,
        val semioticLayer: String,
        val archLayer: String,
        val sha256: String,
        val mtime: Double,
        val loc: Int,
        val topLevelDeclCount: Int
    )

    fun upsertFileRecord(record: FileRecordUpsert): String {
        val fileId = "${record.projectId}:${record.relPath}"
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
                    val delFts = scope.prepare("DELETE FROM nodes_fts WHERE rowid IN (SELECT rowid FROM nodes WHERE file_id = ?);")
                    delFts.bindString(1, fileId)
                    delFts.step()

                    val delFtsByFile = scope.prepare("DELETE FROM nodes_fts WHERE file_id = ?;")
                    delFtsByFile.bindString(1, fileId)
                    delFtsByFile.step()

                    val delVocab = scope.prepare("DELETE FROM name_segment_vocab WHERE node_id = ?;")
                    val delEdges = scope.prepare("DELETE FROM edges WHERE project_id = ? AND (source_node_id = ? OR target_node_id = ?);")
                    val delNodes = scope.prepare("DELETE FROM nodes WHERE file_id = ?;")

                    for (nodeId in oldNodes) {
                        delVocab.reset()
                        delVocab.clearBindings()
                        delVocab.bindString(1, nodeId)
                        delVocab.step()

                        delEdges.reset()
                        delEdges.clearBindings()
                        delEdges.bindString(1, record.projectId)
                        delEdges.bindString(2, nodeId)
                        delEdges.bindString(3, nodeId)
                        delEdges.step()
                    }
                    delNodes.bindString(1, fileId)
                    delNodes.step()
                }

                val delRawEdges = scope.prepare("DELETE FROM raw_symbol_edges WHERE project_id = ? AND file_id = ?;")
                delRawEdges.bindString(1, record.projectId)
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
                upsertStmt.bindString(2, record.projectId)
                upsertStmt.bindString(3, record.relPath)
                upsertStmt.bindString(4, record.language)
                upsertStmt.bindString(5, record.moduleId)
                upsertStmt.bindString(6, record.sourceSet)
                upsertStmt.bindString(7, record.semioticLayer)
                upsertStmt.bindString(8, record.archLayer)
                upsertStmt.bindString(9, record.sha256)
                upsertStmt.bindDouble(10, record.mtime)
                upsertStmt.bindInt(11, record.loc)
                upsertStmt.bindInt(12, record.topLevelDeclCount)
                upsertStmt.step()
            } finally {
                scope.closeAll()
            }
        }
        return fileId
    }

    fun listIndexedFiles(projectId: String): List<String> {
        val res = mutableListOf<String>()
        val stmt = db.prepare("SELECT rel_path FROM files WHERE project_id = ? AND rel_path != '__endpoints__';")
        try {
            stmt.bindString(1, projectId)
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
                in_degree = (SELECT COUNT(*) FROM edges WHERE project_id = ? AND target_node_id = nodes.node_id),
                out_degree = (SELECT COUNT(*) FROM edges WHERE project_id = ? AND source_node_id = nodes.node_id)
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
                    val delFts = scope.prepare("DELETE FROM nodes_fts WHERE rowid IN (SELECT rowid FROM nodes WHERE file_id = ?);")
                    delFts.bindString(1, fileId)
                    delFts.step()

                    val delFtsByFile = scope.prepare("DELETE FROM nodes_fts WHERE file_id = ?;")
                    delFtsByFile.bindString(1, fileId)
                    delFtsByFile.step()

                    val delVocab = scope.prepare("DELETE FROM name_segment_vocab WHERE node_id = ?;")
                    val delEdges = scope.prepare("DELETE FROM edges WHERE project_id = ? AND (source_node_id = ? OR target_node_id = ?);")
                    val delNodes = scope.prepare("DELETE FROM nodes WHERE file_id = ?;")

                    for (nodeId in oldNodes) {
                        delVocab.reset()
                        delVocab.clearBindings()
                        delVocab.bindString(1, nodeId)
                        delVocab.step()

                        delEdges.reset()
                        delEdges.clearBindings()
                        delEdges.bindString(1, projectId)
                        delEdges.bindString(2, nodeId)
                        delEdges.bindString(3, nodeId)
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

            deleteComposeNodesForFile(projectId, relPath)

            if (recomputeDegrees && oldNodes.isNotEmpty()) {
                recomputeDegrees(projectId)
            }
        }
    }

    private fun bindUpsertNode(stmt: NativeSqliteStatement, node: GraphNode) {
        stmt.reset()
        stmt.clearBindings()
        stmt.bindString(1, node.nodeId)
        stmt.bindString(2, node.projectId)
        stmt.bindString(3, node.fileId)
        stmt.bindString(4, node.name)
        stmt.bindString(5, node.qualifiedName)
        stmt.bindString(6, node.kind)
        stmt.bindString(7, node.signature)
        stmt.bindInt(8, node.startLine)
        stmt.bindInt(9, node.endLine)
        stmt.bindString(10, node.moduleId)
        stmt.bindString(11, node.sourceSet)
        stmt.bindString(12, node.semioticLayer)
        stmt.bindString(13, node.archLayer)
        stmt.bindInt(14, if (node.isTestDouble) 1 else 0)
        stmt.bindInt(15, node.inDegree)
        stmt.bindInt(16, node.outDegree)
        if (node.description != null) {
            stmt.bindString(17, node.description)
        } else {
            stmt.bindNull(17)
        }
        stmt.bindInt(18, if (node.isTopLevel) 1 else 0)
        stmt.bindInt(19, node.cyclomaticComplexity)
        stmt.bindInt(20, node.cognitiveComplexity)
        stmt.bindInt(21, node.maxNestingDepth)
        stmt.bindDouble(22, node.complexityScore)
        stmt.step()
    }

    private fun upsertNodeSearchIndexes(
        delVocab: NativeSqliteStatement,
        delFtsByNodeId: NativeSqliteStatement,
        delFts: NativeSqliteStatement,
        insFts: NativeSqliteStatement,
        insVocab: NativeSqliteStatement,
        node: GraphNode
    ) {
        delVocab.reset(); delVocab.clearBindings(); delVocab.bindString(1, node.nodeId); delVocab.step()
        delFtsByNodeId.reset(); delFtsByNodeId.clearBindings(); delFtsByNodeId.bindString(1, node.nodeId); delFtsByNodeId.step()
        delFts.reset(); delFts.clearBindings(); delFts.bindString(1, node.nodeId); delFts.step()

        insFts.reset()
        insFts.clearBindings()
        insFts.bindString(1, node.nodeId)
        insFts.bindString(2, node.nodeId)
        insFts.bindString(3, node.projectId)
        insFts.bindString(4, node.name)
        insFts.bindString(5, node.qualifiedName)
        insFts.bindString(6, node.signature)
        insFts.bindString(7, node.fileId)
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

    private fun bindUpsertEdge(stmt: NativeSqliteStatement, edge: GraphEdge) {
        stmt.reset()
        stmt.clearBindings()
        stmt.bindString(1, edge.edgeId)
        stmt.bindString(2, edge.projectId)
        stmt.bindString(3, edge.sourceNodeId)
        stmt.bindString(4, edge.targetNodeId)
        stmt.bindString(5, edge.edgeType)
        stmt.bindString(6, edge.provenance)
        stmt.bindDouble(7, edge.confidence)
        stmt.bindInt(8, edge.lineNumber)
        stmt.step()
    }

    private fun sweepOrphanEndpoints(
        scope: StatementScope,
        projectId: String,
        delFtsByNodeId: NativeSqliteStatement,
        delFts: NativeSqliteStatement,
        delVocab: NativeSqliteStatement
    ) {
        val selectOrphanEndpoints = scope.prepare(
            """
            SELECT node_id FROM nodes
            WHERE project_id = ?
              AND kind = 'endpoint'
              AND file_id = ?
              AND node_id NOT IN (SELECT target_node_id FROM edges WHERE project_id = ?)
              AND node_id NOT IN (SELECT source_node_id FROM edges WHERE project_id = ?);
            """.trimIndent()
        )
        selectOrphanEndpoints.bindString(1, projectId)
        selectOrphanEndpoints.bindString(2, "$projectId:__endpoints__")
        selectOrphanEndpoints.bindString(3, projectId)
        selectOrphanEndpoints.bindString(4, projectId)
        val orphanEpIds = mutableListOf<String>()
        while (selectOrphanEndpoints.step()) {
            orphanEpIds.add(selectOrphanEndpoints.columnString(0))
        }
        if (orphanEpIds.isNotEmpty()) {
            val delEpNode = scope.prepare("DELETE FROM nodes WHERE node_id = ?;")
            for (epId in orphanEpIds) {
                delFtsByNodeId.reset(); delFtsByNodeId.clearBindings(); delFtsByNodeId.bindString(1, epId); delFtsByNodeId.step()
                delFts.reset(); delFts.clearBindings(); delFts.bindString(1, epId); delFts.step()
                delVocab.reset(); delVocab.clearBindings(); delVocab.bindString(1, epId); delVocab.step()
                delEpNode.reset(); delEpNode.clearBindings(); delEpNode.bindString(1, epId); delEpNode.step()
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
                val delFts = scope.prepare("DELETE FROM nodes_fts WHERE rowid = (SELECT rowid FROM nodes WHERE node_id = ?);")
                val delFtsByNodeId = scope.prepare("DELETE FROM nodes_fts WHERE node_id = ?;")
                val delVocab = scope.prepare("DELETE FROM name_segment_vocab WHERE node_id = ?;")
                val insNode = scope.prepare(
                    """
                    INSERT INTO nodes (
                        node_id, project_id, file_id, name, qualified_name, kind, signature,
                        start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                        is_test_double, in_degree, out_degree, description, is_top_level,
                        cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        is_top_level = excluded.is_top_level,
                        cyclomatic_complexity = excluded.cyclomatic_complexity,
                        cognitive_complexity = excluded.cognitive_complexity,
                        max_nesting_depth = excluded.max_nesting_depth,
                        complexity_score = excluded.complexity_score;
                    """.trimIndent()
                )
                val insFts = scope.prepare(
                    """
                    INSERT INTO nodes_fts (rowid, node_id, project_id, name, qualified_name, signature, file_id)
                    VALUES ((SELECT rowid FROM nodes WHERE node_id = ?), ?, ?, ?, ?, ?, ?);
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
                          provenance != 'EXTRACTED_LOCAL_FILE_REF'
                          AND target_node_id NOT LIKE 'vault_ref#%'
                          AND target_node_id NOT LIKE '%:vault_ref#%'
                          AND target_node_id NOT LIKE 'url_ref#%'
                          AND target_node_id NOT LIKE '%:url_ref#%'
                          AND target_node_id NOT LIKE 'skill#%'
                          AND target_node_id NOT LIKE '%:skill#%'
                          AND target_node_id NOT IN (SELECT node_id FROM nodes WHERE project_id = ?)
                        )
                      );
                    """.trimIndent()
                )

                for (node in nodes) {
                    bindUpsertNode(insNode, node)
                    upsertNodeSearchIndexes(delVocab, delFtsByNodeId, delFts, insFts, insVocab, node)
                }

                for (edge in edges) {
                    bindUpsertEdge(insEdge, edge)
                }

                sweepOrphanEdges.bindString(1, projectId)
                sweepOrphanEdges.bindString(2, projectId)
                sweepOrphanEdges.bindString(3, projectId)
                sweepOrphanEdges.step()

                sweepOrphanEndpoints(scope, projectId, delFtsByNodeId, delFts, delVocab)

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
            isTopLevel = stmt.columnInt(17) != 0,
            cyclomaticComplexity = stmt.columnInt(18),
            cognitiveComplexity = stmt.columnInt(19),
            maxNestingDepth = stmt.columnInt(20),
            complexityScore = stmt.columnDouble(21)
        )
    }

    private fun getNodeByCodeLocation(projectId: String, codeLink: UrlTargetResolver.CodeLocationLink): GraphNode? {
        val escapedSuffix = codeLink.filePathSuffix
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        if (codeLink.startLine != null) {
            val targetLine = codeLink.startLine
            val spanStmt = db.prepare(
                """
                SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                       start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                       is_test_double, in_degree, out_degree, description, is_top_level,
                       cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
                FROM nodes
                WHERE project_id = ?
                  AND kind NOT IN ('file', 'file_scope', 'module')
                  AND start_line <= ? AND end_line >= ?
                  AND (file_id = ? OR file_id = ? OR file_id LIKE ? ESCAPE '\' OR file_id LIKE ? ESCAPE '\')
                ORDER BY (end_line - start_line) ASC, start_line DESC, in_degree DESC
                LIMIT 1;
                """.trimIndent()
            )
            try {
                spanStmt.bindString(1, projectId)
                spanStmt.bindInt(2, targetLine)
                spanStmt.bindInt(3, targetLine)
                spanStmt.bindString(4, "$projectId:${codeLink.filePathSuffix}")
                spanStmt.bindString(5, codeLink.filePathSuffix)
                spanStmt.bindString(6, "%:$escapedSuffix")
                spanStmt.bindString(7, "%/$escapedSuffix")
                if (spanStmt.step()) return rowToNode(spanStmt)
            } finally {
                spanStmt.close()
            }
        }
        if (codeLink.symbolFragment != null) {
            val symStmt = db.prepare(
                """
                SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                       start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                       is_test_double, in_degree, out_degree, description, is_top_level,
                       cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
                FROM nodes
                WHERE project_id = ?
                  AND (name = ? OR qualified_name LIKE ? ESCAPE '\')
                  AND (file_id = ? OR file_id = ? OR file_id LIKE ? ESCAPE '\' OR file_id LIKE ? ESCAPE '\')
                ORDER BY (name = ?) DESC, in_degree DESC
                LIMIT 1;
                """.trimIndent()
            )
            try {
                val escFrag = codeLink.symbolFragment
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                symStmt.bindString(1, projectId)
                symStmt.bindString(2, codeLink.symbolFragment)
                symStmt.bindString(3, "%$escFrag%")
                symStmt.bindString(4, "$projectId:${codeLink.filePathSuffix}")
                symStmt.bindString(5, codeLink.filePathSuffix)
                symStmt.bindString(6, "%:$escapedSuffix")
                symStmt.bindString(7, "%/$escapedSuffix")
                symStmt.bindString(8, codeLink.symbolFragment)
                if (symStmt.step()) return rowToNode(symStmt)
            } finally {
                symStmt.close()
            }
        }
        val fileNodes = getNodesForFile(projectId, codeLink.filePathSuffix)
        return fileNodes.firstOrNull { it.kind !in setOf("file", "file_scope", "module") }
            ?: fileNodes.firstOrNull()
    }

    private fun getNodeByHttpRoute(projectId: String, target: String): GraphNode? {
        if (!UrlTargetResolver.isHttpRouteCandidate(target, hasExplicitRouteCall = false)) return null
        val canonRoute = UrlTargetResolver.normalizeRouteTemplate(target)
        val epNodeId = "$projectId:endpoint#$canonRoute"
        val epStmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree, description, is_top_level,
                   cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
            FROM nodes
            WHERE project_id = ? AND (node_id = ? OR name = ?)
            LIMIT 1;
            """.trimIndent()
        )
        try {
            epStmt.bindString(1, projectId)
            epStmt.bindString(2, epNodeId)
            epStmt.bindString(3, canonRoute)
            if (epStmt.step()) return rowToNode(epStmt)
        } finally {
            epStmt.close()
        }
        return null
    }

    private fun getNodeByNameOrId(projectId: String, target: String): GraphNode? {
        val escapedTarget = target
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val cleanHashTarget = target.removePrefix("#")
        val tagTarget = "tag#$cleanHashTarget"
        val projTarget = "$projectId:$target"
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree, description, is_top_level,
                   cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
            FROM nodes
            WHERE project_id = ? AND (
                node_id = ?
                OR node_id = ?
                OR qualified_name = ?
                OR qualified_name = ?
                OR name = ?
                OR name = ?
                OR file_id = ?
                OR file_id LIKE ? ESCAPE '\'
                OR file_id LIKE ? ESCAPE '\'
            )
            ORDER BY (node_id = ?) DESC,
                     (node_id = ?) DESC,
                     (qualified_name = ?) DESC,
                     (name = ?) DESC,
                     in_degree DESC
            LIMIT 1;
            """.trimIndent()
        )
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, target)
            stmt.bindString(3, projTarget)
            stmt.bindString(4, target)
            stmt.bindString(5, tagTarget)
            stmt.bindString(6, target)
            stmt.bindString(7, cleanHashTarget)
            stmt.bindString(8, target)
            stmt.bindString(9, "%:$escapedTarget")
            stmt.bindString(10, "%/$escapedTarget")
            stmt.bindString(11, target)
            stmt.bindString(12, projTarget)
            stmt.bindString(13, target)
            stmt.bindString(14, target)
            if (stmt.step()) {
                return rowToNode(stmt)
            }
            return null
        } finally {
            stmt.close()
        }
    }

    fun getNode(projectId: String, target: String): GraphNode? {
        val codeLink = UrlTargetResolver.parseCodeLocationUrl(target)
        if (codeLink != null) {
            return getNodeByCodeLocation(projectId, codeLink)
        }

        val routeNode = getNodeByHttpRoute(projectId, target)
        if (routeNode != null) {
            return routeNode
        }

        return getNodeByNameOrId(projectId, target)
    }

    fun getNodesForFile(projectId: String, relPath: String): List<GraphNode> {
        val norm = relPath.trim().trim('\'', '"').removePrefix("./").replace('\\', '/')
        val escaped = norm.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val stmt = db.prepare(
            """
            SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                   start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                   is_test_double, in_degree, out_degree, description, is_top_level,
                   cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
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
                       is_test_double, in_degree, out_degree, description, is_top_level,
                       cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
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

    private fun scoreFtsSegments(
        projectId: String,
        segments: List<String>,
        limit: Int,
        scores: MutableMap<String, Double>
    ) {
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
        } catch (e: Exception) {
            PlatformFiles.logWarning("SqliteGraphStore", "FTS segment ranking failed: ${e.message}")
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
        query: String,
        limit: Int,
        scores: MutableMap<String, Double>
    ) {
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
    }

    private fun loadRankedNodesMap(rankedIds: List<String>, limit: Int): Map<String, GraphNode> {
        val nodeMap = mutableMapOf<String, GraphNode>()
        val batchIds = rankedIds.take((limit * 5).coerceAtLeast(50))
        for (chunk in batchIds.chunked(250)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val nodesStmt = db.prepare(
                """
                SELECT node_id, project_id, file_id, name, qualified_name, kind, signature,
                       start_line, end_line, module_id, source_set, semiotic_layer, arch_layer,
                       is_test_double, in_degree, out_degree, description, is_top_level,
                       cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
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
        return nodeMap
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
            scoreFtsSegments(projectId, segments, limit, scores)
            scoreVocabCoverage(projectId, segments, scores)
        }

        scoreExactAndFuzzyNames(projectId, query, limit, scores)

        if (scores.isEmpty()) return emptyList()

        val rankedIds = scores.entries.sortedByDescending { it.value }.map { it.key }
        val nodeMap = loadRankedNodesMap(rankedIds, limit)

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

    fun getCallers(projectId: String, nodeId: String, depth: Int = 2): List<GraphHop> =
        traversalDao.getCallers(projectId, nodeId, depth)

    fun getCallees(projectId: String, nodeId: String, depth: Int = 2): List<GraphHop> =
        traversalDao.getCallees(projectId, nodeId, depth)

    fun getImpactRadius(projectId: String, nodeId: String, depth: Int = 2): ImpactRadiusResult =
        traversalDao.getImpactRadius(projectId, nodeId, depth)

    fun getGodNodes(projectId: String, topN: Int = 10): List<GraphNode> =
        traversalDao.getGodNodes(projectId, topN)

    fun resolveNodesByUrlOrRoute(projectId: String, rawUrlOrRoute: String, limit: Int = 25): List<GraphNode> =
        traversalDao.resolveNodesByUrlOrRoute(projectId, rawUrlOrRoute, limit)

    fun searchByUrl(projectId: String, rawUrlOrRoute: String, limit: Int = 20): JsonObject =
        traversalDao.searchByUrl(projectId, rawUrlOrRoute, limit)

    fun getShortestPath(
        projectId: String,
        sourceNodeId: String,
        targetNodeId: String,
        maxHops: Int = 6
    ): List<String> = traversalDao.getShortestPath(projectId, sourceNodeId, targetNodeId, maxHops)

    fun sliceLiveSource(
        projectRoot: String,
        projectId: String,
        node: GraphNode
    ): LiveSourceSlice = traversalDao.sliceLiveSource(projectRoot, node)

    fun listFiles(projectId: String): List<FileRecord> {
        val stmt = db.prepare(
            """
            SELECT file_id, project_id, rel_path, language, module_id, source_set,
                   semiotic_layer, arch_layer, sha256, mtime, loc, top_level_decl_count
            FROM files WHERE project_id = ? AND rel_path != '__endpoints__' ORDER BY rel_path ASC;
            """.trimIndent()
        )
        val results = mutableListOf<FileRecord>()
        try {
            stmt.bindString(1, projectId)
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
                   is_test_double, in_degree, out_degree, description, is_top_level,
                   cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score
            FROM nodes WHERE project_id = ? ORDER BY node_id ASC;
            """.trimIndent()
        )
        val results = mutableListOf<GraphNode>()
        try {
            stmt.bindString(1, projectId)
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
        return db.prepare(sql).use { stmt ->
            stmt.bindString(1, projectId)
            if (edgeTypes != null && edgeTypes.isNotEmpty()) {
                edgeTypes.forEachIndexed { i, t -> stmt.bindString(2 + i, t) }
            }
            val results = mutableListOf<GraphEdge>()
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
            results
        }
    }

    fun getStats(projectId: String): GraphStats {
        val filesCount = db.prepare("SELECT COUNT(*) FROM files WHERE project_id = ? AND rel_path != '__endpoints__';").use { stmt ->
            stmt.bindString(1, projectId)
            if (stmt.step()) stmt.columnInt(0) else 0
        }

        val nodesCount = db.prepare("SELECT COUNT(*) FROM nodes WHERE project_id = ?;").use { stmt ->
            stmt.bindString(1, projectId)
            if (stmt.step()) stmt.columnInt(0) else 0
        }

        val edgesCount = db.prepare("SELECT COUNT(*) FROM edges WHERE project_id = ?;").use { stmt ->
            stmt.bindString(1, projectId)
            if (stmt.step()) stmt.columnInt(0) else 0
        }

        val provMap = mutableMapOf<String, Int>()
        db.prepare(
            """
            SELECT provenance, COUNT(*)
            FROM edges WHERE project_id = ?
            GROUP BY provenance;
            """.trimIndent()
        ).use { stmt ->
            stmt.bindString(1, projectId)
            while (stmt.step()) {
                provMap[stmt.columnString(0)] = stmt.columnInt(1)
            }
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

    fun insertFigmaDocument(document: FigmaDocument) = figmaDao.insertFigmaDocument(document)

    fun getFigmaDocument(fileKey: String): FigmaDocument? = figmaDao.getFigmaDocument(fileKey)

    fun deleteFigmaDocument(fileKey: String) = figmaDao.deleteFigmaDocument(fileKey)

    fun insertFigmaNodes(fileKey: String, nodes: List<FigmaNode>) =
        figmaDao.insertFigmaNodes(fileKey, nodes)

    fun insertFigmaVariables(fileKey: String, variables: List<FigmaVariable>) =
        figmaDao.insertFigmaVariables(fileKey, variables)

    fun insertFigmaEdges(fileKey: String, edges: List<FigmaEdge>) =
        figmaDao.insertFigmaEdges(fileKey, edges)

    fun queryFigmaSpatialIntersection(
        fileKey: String,
        bounds: FigmaSpatialBounds
    ): List<FigmaNode> = figmaDao.queryFigmaSpatialIntersection(fileKey, bounds)

    fun queryFigmaSpatialIntersection(
        fileKey: String,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double
    ): List<FigmaNode> = figmaDao.queryFigmaSpatialIntersection(fileKey, minX, maxX, minY, maxY)

    fun queryFigmaNodeById(fileKey: String, nodeId: String): FigmaNode? =
        figmaDao.queryFigmaNodeById(fileKey, nodeId)

    fun queryFigmaNodesByName(fileKey: String, name: String): List<FigmaNode> =
        figmaDao.queryFigmaNodesByName(fileKey, name)

    fun queryFigmaNodesByType(fileKey: String, nodeType: String): List<FigmaNode> =
        figmaDao.queryFigmaNodesByType(fileKey, nodeType)

    fun queryFigmaVariableById(fileKey: String, variableId: String): FigmaVariable? =
        figmaDao.queryFigmaVariableById(fileKey, variableId)

    fun queryFigmaVariableByName(fileKey: String, name: String): FigmaVariable? =
        figmaDao.queryFigmaVariableByName(fileKey, name)

    fun queryFigmaEdgesBySource(fileKey: String, sourceNodeId: String): List<FigmaEdge> =
        figmaDao.queryFigmaEdgesBySource(fileKey, sourceNodeId)

    fun queryFigmaEdgesByTarget(fileKey: String, targetNodeId: String): List<FigmaEdge> =
        figmaDao.queryFigmaEdgesByTarget(fileKey, targetNodeId)

    fun queryFigmaEdgesByType(fileKey: String, edgeType: String): List<FigmaEdge> =
        figmaDao.queryFigmaEdgesByType(fileKey, edgeType)

    fun resolveVariableAliasImpact(
        fileKey: String,
        variableIdOrName: String,
        depth: Int = 10
    ): List<FigmaVariable> = figmaDao.resolveVariableAliasImpact(fileKey, variableIdOrName, depth)

    fun queryNodesBoundToVariable(fileKey: String, variableId: String): List<FigmaNode> =
        figmaDao.queryNodesBoundToVariable(fileKey, variableId)

    fun resolveVariableBlastRadius(
        fileKey: String,
        variableIdOrName: String,
        depth: Int = 10
    ): FigmaVariableImpact = figmaDao.resolveVariableBlastRadius(fileKey, variableIdOrName, depth)

    fun queryFigmaCallers(
        fileKey: String,
        componentNameOrId: String,
        depth: Int = 2
    ): List<FigmaNode> = figmaDao.queryFigmaCallers(fileKey, componentNameOrId, depth)

    fun prepare(sql: String): NativeSqliteStatement = db.prepare(sql)

    fun exec(sql: String) {
        db.exec(sql)
    }

    fun getLatestFigmaFileKey(): String? = figmaDao.getLatestFigmaFileKey()

    fun queryFigmaGodNodes(fileKey: String, limit: Int = 10): List<Pair<FigmaNode, Int>> =
        figmaDao.queryFigmaGodNodes(fileKey, limit)

    fun queryFigmaNodesByIds(fileKey: String, nodeIds: List<String>): List<FigmaNode> =
        figmaDao.queryFigmaNodesByIds(fileKey, nodeIds)

    fun findFigmaShortestPath(
        fileKey: String,
        sourceTarget: String,
        destTarget: String,
        depth: Int = 10
    ): List<FigmaNode> = figmaDao.findFigmaShortestPath(fileKey, sourceTarget, destTarget, depth)

    fun countFigmaNodes(fileKey: String): Int = figmaDao.countFigmaNodes(fileKey)

    fun queryFigmaNodesByNameLike(fileKey: String, substring: String): List<FigmaNode> =
        figmaDao.queryFigmaNodesByNameLike(fileKey, substring)

    fun queryCircularFillInHugViolations(fileKey: String): List<FigmaViolation> =
        figmaDao.queryCircularFillInHugViolations(fileKey)

    fun queryStaticContainerFrameViolations(fileKey: String): List<FigmaViolation> =
        figmaDao.queryStaticContainerFrameViolations(fileKey)

    fun queryDefault100PxFrameHeightViolations(fileKey: String): List<FigmaViolation> =
        figmaDao.queryDefault100PxFrameHeightViolations(fileKey)

    fun queryMinTouchTargetViolations(fileKey: String): List<FigmaViolation> =
        figmaDao.queryMinTouchTargetViolations(fileKey)

    fun queryNegativeCoordinateViolations(fileKey: String): List<FigmaViolation> =
        figmaDao.queryNegativeCoordinateViolations(fileKey)

    fun queryChildAmputationWidthViolations(fileKey: String): List<FigmaViolation> =
        figmaDao.queryChildAmputationWidthViolations(fileKey)

    fun queryNoEmojiViolations(fileKey: String): List<FigmaViolation> =
        figmaDao.queryNoEmojiViolations(fileKey)

    fun queryUnboundTokenViolations(fileKey: String): List<FigmaViolation> =
        figmaDao.queryUnboundTokenViolations(fileKey)

    fun queryAdHocDetachedSpecimenViolations(fileKey: String): List<FigmaViolation> =
        figmaDao.queryAdHocDetachedSpecimenViolations(fileKey)

    fun queryCanvasSpatialTopologyViolations(fileKey: String): List<FigmaViolation> =
        figmaDao.queryCanvasSpatialTopologyViolations(fileKey)

    fun queryWcagContrastViolations(fileKey: String): List<FigmaViolation> =
        figmaDao.queryWcagContrastViolations(fileKey)

    // =========================================================================
    // Jetpack Compose & KMP Stability & Compliance DAO Operations
    // =========================================================================

    fun provenanceRank(provenance: String): Int = composeDao.provenanceRank(provenance)

    fun upsertComposeNodesAndParams(
        nodes: List<ComposeNodeRecord>,
        params: List<ComposeParamRecord>,
        preserveHigherProvenance: Boolean = true
    ) = composeDao.upsertComposeNodesAndParams(nodes, params, preserveHigherProvenance)

    fun deleteComposeNodesForFile(projectSlug: String, filePath: String) =
        composeDao.deleteComposeNodesForFile(projectSlug, filePath)

    fun upsertComposeTraceMetric(
        nodeId: String,
        sessionId: String,
        recompositionCount: Int,
        skipCount: Int,
        dirtyParamsJson: String,
        recordedAt: Long
    ) = composeDao.upsertComposeTraceMetric(
        ComposeGraphStoreDao.ComposeTraceMetricUpsert(
            nodeId = nodeId,
            sessionId = sessionId,
            recompositionCount = recompositionCount,
            skipCount = skipCount,
            dirtyParamsJson = dirtyParamsJson,
            recordedAt = recordedAt
        )
    )

    fun listComposeNodes(projectSlug: String, onlyViolating: Boolean = false): List<ComposeNodeRecord> =
        composeDao.listComposeNodes(projectSlug, onlyViolating)

    fun getComposeNode(projectSlug: String, target: String): ComposeNodeRecord? =
        composeDao.getComposeNode(projectSlug, target)

    fun listComposeParams(nodeId: String): List<ComposeParamRecord> =
        composeDao.listComposeParams(nodeId)

    fun listComposeParamsForProject(projectSlug: String): Map<String, List<ComposeParamRecord>> =
        composeDao.listComposeParamsForProject(projectSlug)

    fun listComposeTraceMetrics(projectSlug: String): Map<String, Triple<Int, Int, String>> =
        composeDao.listComposeTraceMetrics(projectSlug)

    fun queryComposeCascade(
        projectSlug: String,
        rootNodeId: String,
        maxDepth: Int = 5
    ): List<ComposeQueryResult.CascadeHop> =
        composeDao.queryComposeCascade(projectSlug, rootNodeId, maxDepth)

    fun queryComposeBlame(
        projectSlug: String,
        composableId: String
    ): List<ComposeQueryResult.BlameHop> =
        composeDao.queryComposeBlame(projectSlug, composableId)

    fun countPropertyMutations(
        projectSlug: String,
        targetSymbolOrNodeId: String,
        ownerClassName: String? = null
    ): Int = composeDao.countPropertyMutations(projectSlug, targetSymbolOrNodeId, ownerClassName)

    fun upsertNodeComplexity(
        nodeId: String,
        cyclomatic: Int,
        cognitive: Int,
        maxNesting: Int,
        score: Double
    ) = traversalDao.upsertNodeComplexity(nodeId, cyclomatic, cognitive, maxNesting, score)

    fun queryComplexNodes(
        projectId: String,
        minCognitive: Int = 10,
        limit: Int = 50
    ): List<ComplexityScoreRecord> = traversalDao.queryComplexNodes(projectId, minCognitive, limit)

    fun queryArchitecturalRiskNodes(
        projectId: String,
        minRisk: Double = 0.0,
        limit: Int = 50
    ): List<ComplexityScoreRecord> = traversalDao.queryArchitecturalRiskNodes(projectId, minRisk, limit)
}



