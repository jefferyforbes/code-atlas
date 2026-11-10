package atlas.db

import atlas.extract.FigmaSnapshotExtractor
import atlas.model.ComplexityScoreRecord
import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.ComposeQueryResult
import atlas.model.FigmaDocument
import atlas.model.FigmaEdge
import atlas.model.FigmaNode
import atlas.model.FigmaSpatialBounds
import atlas.model.FigmaVariable
import atlas.model.FigmaVariableImpact
import atlas.model.FigmaViolation
import atlas.model.FileRecord
import atlas.model.GraphEdge
import atlas.model.GraphHop
import atlas.model.GraphNode
import atlas.model.GraphStats
import atlas.model.ImpactRadiusResult
import atlas.model.LiveSourceSlice
import atlas.model.NodeAnnotation
import atlas.util.PlatformFiles
import atlas.util.Sha256
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/**
 * Persistent SQLite WAL knowledge graph store with FTS5, segment vocabulary, and freshness slicing.
 */
class SqliteGraphStore(val dbPath: String) : FigmaSnapshotExtractor.StoreSink {

    internal companion object {
        internal const val NODES_SELECT_COLUMNS =
            "node_id, project_id, file_id, name, qualified_name, kind, signature, " +
                "start_line, end_line, module_id, source_set, semiotic_layer, arch_layer, " +
                "is_test_double, in_degree, out_degree, description, is_top_level, " +
                "cyclomatic_complexity, cognitive_complexity, max_nesting_depth, complexity_score"
    }

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
    private val searchAndVocabDao = SearchAndVocabDao(
        db = db,
        rowToNode = ::rowToNode
    )
    private val urlResolutionDao = UrlResolutionDao(
        db = db,
        rowToNode = ::rowToNode,
        fileNodesLookup = ::getNodesForFile
    )

    init {
        GraphSchemaMigrator.initSchema(db)
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

    fun migrateComposeSchema() = GraphSchemaMigrator.migrateComposeSchema(db)

    fun migrateNodesSchema() = GraphSchemaMigrator.migrateNodesSchema(db)

    fun <T> withImmediateTransaction(block: () -> T): T = db.withImmediateTransaction(block)

    fun <T> withSavepoint(name: String, block: () -> T): T = db.withSavepoint(name, block)

    internal class StatementScope(private val db: NativeSqliteDatabase) : AutoCloseable {
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

        override fun close() {
            closeAll()
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

    private data class RawSymbolFileContext(
        val projectId: String,
        val normalizedFileId: String
    )

    private data class RawSymbolBatchStatements(
        val deleteStmt: NativeSqliteStatement,
        val insertStmt: NativeSqliteStatement
    )

    private fun bindRawSymbolEdgeRow(
        stmt: NativeSqliteStatement,
        fileContext: RawSymbolFileContext,
        edge: GraphEdge,
        targetSym: String
    ) {
        stmt.reset()
        stmt.clearBindings()
        stmt.bindString(1, fileContext.projectId)
        stmt.bindString(2, fileContext.normalizedFileId)
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
        fileContext: RawSymbolFileContext
    ) {
        val markerEdge = GraphEdge(
            edgeId = "",
            projectId = fileContext.projectId,
            sourceNodeId = "",
            targetNodeId = "",
            edgeType = "INDEXED_MARKER",
            provenance = "EXTRACTED_AST",
            confidence = 1.0,
            lineNumber = 0
        )
        bindRawSymbolEdgeRow(stmt, fileContext, markerEdge, "")
    }

    private fun replaceFileRawSymbolEdgesInBatch(
        stmts: RawSymbolBatchStatements,
        projectId: String,
        fileId: String,
        edges: List<GraphEdge>
    ) {
        val normalizedFileId = if (fileId.startsWith("$projectId:")) fileId else "$projectId:$fileId"
        stmts.deleteStmt.reset()
        stmts.deleteStmt.clearBindings()
        stmts.deleteStmt.bindString(1, projectId)
        stmts.deleteStmt.bindString(2, normalizedFileId)
        stmts.deleteStmt.step()

        var insertedCount = 0
        val fileCtx = RawSymbolFileContext(projectId, normalizedFileId)
        for (edge in edges) {
            if (edge.edgeType == "CONTAINS") continue
            val targetSym = resolveTargetSymbol(projectId, edge.targetNodeId) ?: continue
            bindRawSymbolEdgeRow(stmts.insertStmt, fileCtx, edge, targetSym)
            insertedCount++
        }
        if (insertedCount == 0) {
            bindIndexedMarker(stmts.insertStmt, fileCtx)
        }
    }

    fun replaceBatchRawSymbolEdges(projectId: String, fileEdgesMap: Map<String, List<GraphEdge>>) {
        if (fileEdgesMap.isEmpty()) return
        withImmediateTransaction {
            val scope = StatementScope(db)
            try {
                val stmts = RawSymbolBatchStatements(
                    deleteStmt = scope.prepare("DELETE FROM raw_symbol_edges WHERE project_id = ? AND file_id = ?;"),
                    insertStmt = scope.prepare(
                        """
                        INSERT INTO raw_symbol_edges (
                            project_id, file_id, source_node_id, target_symbol,
                            edge_type, confidence, provenance, line_number, edge_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
                        """.trimIndent()
                    )
                )
                for ((fileId, edges) in fileEdgesMap) {
                    replaceFileRawSymbolEdgesInBatch(stmts, projectId, fileId, edges)
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

    fun getDeclaredSymbolsForFile(projectId: String, fileId: String): List<String> {
        val normalizedFileId = if (fileId.startsWith("$projectId:")) fileId else "$projectId:$fileId"
        val stmt = db.prepare("SELECT name, qualified_name FROM nodes WHERE project_id = ? AND file_id = ?;")
        val symbols = mutableListOf<String>()
        try {
            stmt.bindString(1, projectId)
            stmt.bindString(2, normalizedFileId)
            while (stmt.step()) {
                val name = stmt.columnString(0)
                val qname = stmt.columnString(1)
                if (name.isNotEmpty()) symbols.add(name)
                if (qname.isNotEmpty() && qname != name) symbols.add(qname)
            }
        } finally {
            stmt.close()
        }
        return symbols
    }

    fun listRawSymbolEdgesTargetingSymbols(
        projectId: String,
        symbols: Set<String>
    ): List<GraphEdge> {
        if (symbols.isEmpty()) return emptyList()
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
                val targetSym = stmt.columnString(3)
                val cleanSym = targetSym.removePrefix("sym:")
                    .removePrefix("$projectId:endpoint#")
                    .removePrefix("$projectId:url_ref#")
                    .removePrefix("endpoint#")
                    .removePrefix("url_ref#")
                val shortSym = cleanSym.substringAfterLast('.').substringAfterLast("::")
                if (cleanSym in symbols || shortSym in symbols || targetSym in symbols) {
                    val rawEdgeId = stmt.columnString(0)
                    val projId = stmt.columnString(1)
                    val sourceNodeId = stmt.columnString(2)
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
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun deleteEdgesForRawEdges(projectId: String, rawEdges: List<GraphEdge>) {
        if (rawEdges.isEmpty()) return
        val stmt = db.prepare(
            "DELETE FROM edges WHERE project_id = ? AND (edge_id = ? OR (edge_id >= ? AND edge_id < ?));"
        )
        try {
            for (re in rawEdges) {
                stmt.reset()
                stmt.clearBindings()
                stmt.bindString(1, projectId)
                stmt.bindString(2, re.edgeId)
                stmt.bindString(3, "${re.edgeId}->")
                stmt.bindString(4, "${re.edgeId}-?")
                stmt.step()
            }
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

    private fun purgeExistingFileNodesAndEdges(
        scope: StatementScope,
        projectId: String,
        fileId: String
    ): List<String> {
        val oldNodes = mutableListOf<String>()
        val selectOld = scope.prepare("SELECT node_id FROM nodes WHERE file_id = ?;")
        selectOld.bindString(1, fileId)
        while (selectOld.step()) {
            oldNodes.add(selectOld.columnString(0))
        }

        val delFtsByRowid = scope.prepare("DELETE FROM nodes_fts WHERE rowid IN (SELECT rowid FROM nodes WHERE file_id = ?);")
        delFtsByRowid.bindString(1, fileId)
        delFtsByRowid.step()

        val delVocabByFile = scope.prepare(
            "DELETE FROM name_segment_vocab WHERE node_id IN (SELECT node_id FROM nodes WHERE file_id = ?);"
        )
        delVocabByFile.bindString(1, fileId)
        delVocabByFile.step()

        val delSourceEdges = scope.prepare(
            "DELETE FROM edges WHERE project_id = ? AND source_node_id IN (SELECT node_id FROM nodes WHERE file_id = ?);"
        )
        delSourceEdges.bindString(1, projectId)
        delSourceEdges.bindString(2, fileId)
        delSourceEdges.step()

        val delTargetEdges = scope.prepare(
            "DELETE FROM edges WHERE project_id = ? AND target_node_id IN (SELECT node_id FROM nodes WHERE file_id = ?);"
        )
        delTargetEdges.bindString(1, projectId)
        delTargetEdges.bindString(2, fileId)
        delTargetEdges.step()

        val delNodes = scope.prepare("DELETE FROM nodes WHERE file_id = ?;")
        delNodes.bindString(1, fileId)
        delNodes.step()

        val delRawEdges = scope.prepare("DELETE FROM raw_symbol_edges WHERE project_id = ? AND file_id = ?;")
        delRawEdges.bindString(1, projectId)
        delRawEdges.bindString(2, fileId)
        delRawEdges.step()
        return oldNodes
    }

    fun upsertFileRecord(record: FileRecordUpsert): String {
        val fileId = "${record.projectId}:${record.relPath}"
        withImmediateTransaction {
            val scope = StatementScope(db)
            try {
                purgeExistingFileNodesAndEdges(scope, record.projectId, fileId)

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
        withImmediateTransaction {
            val scope = StatementScope(db)
            val oldNodes: List<String>
            try {
                oldNodes = purgeExistingFileNodesAndEdges(scope, projectId, fileId)

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

    private data class NodeSearchIndexStatements(
        val delVocab: NativeSqliteStatement,
        val insFts: NativeSqliteStatement,
        val insVocab: NativeSqliteStatement
    )

    private fun upsertNodeSearchIndexes(
        stmts: NodeSearchIndexStatements,
        node: GraphNode
    ) {
        stmts.delVocab.reset(); stmts.delVocab.clearBindings(); stmts.delVocab.bindString(1, node.nodeId); stmts.delVocab.step()

        stmts.insFts.reset()
        stmts.insFts.clearBindings()
        stmts.insFts.bindString(1, node.nodeId)
        stmts.insFts.bindString(2, node.nodeId)
        stmts.insFts.bindString(3, node.projectId)
        stmts.insFts.bindString(4, node.name)
        stmts.insFts.bindString(5, node.qualifiedName)
        stmts.insFts.bindString(6, node.signature)
        stmts.insFts.bindString(7, node.fileId)
        stmts.insFts.step()

        searchAndVocabDao.insertNodeVocabulary(
            stmts.insVocab,
            node.nodeId,
            node.projectId,
            "${node.name} ${node.qualifiedName}"
        )
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
            val delFtsByRowid = scope.prepare("DELETE FROM nodes_fts WHERE rowid IN (SELECT rowid FROM nodes WHERE node_id = ?);")
            val delEpNode = scope.prepare("DELETE FROM nodes WHERE node_id = ?;")
            for (epId in orphanEpIds) {
                delFtsByRowid.reset(); delFtsByRowid.clearBindings(); delFtsByRowid.bindString(1, epId); delFtsByRowid.step()
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
        withImmediateTransaction {
            val scope = StatementScope(db)
            try {
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
                    INSERT OR REPLACE INTO nodes_fts (rowid, node_id, project_id, name, qualified_name, signature, file_id)
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

                val searchIndexStmts = NodeSearchIndexStatements(
                    delVocab = delVocab,
                    insFts = insFts,
                    insVocab = insVocab
                )
                for (node in nodes) {
                    bindUpsertNode(insNode, node)
                    upsertNodeSearchIndexes(searchIndexStmts, node)
                }

                for (edge in edges) {
                    bindUpsertEdge(insEdge, edge)
                }

                sweepOrphanEdges.bindString(1, projectId)
                sweepOrphanEdges.bindString(2, projectId)
                sweepOrphanEdges.bindString(3, projectId)
                sweepOrphanEdges.step()

                sweepOrphanEndpoints(scope, projectId, delVocab)

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

    fun getNode(projectId: String, target: String): GraphNode? =
        urlResolutionDao.getNode(projectId, target)

    fun queryByUrl(projectId: String, target: String): GraphNode? =
        urlResolutionDao.queryByUrl(projectId, target)

    fun queryNodesAtLocation(projectId: String, filePath: String, line: Int): List<GraphNode> =
        urlResolutionDao.queryNodesAtLocation(projectId, filePath, line)

    fun getNodesForFile(projectId: String, relPath: String): List<GraphNode> {
        val norm = relPath.trim().trim('\'', '"').removePrefix("./").replace('\\', '/')
        val escaped = NativeSqliteStatement.escapeSqlLike(norm)
        val stmt = db.prepare(
            """
            SELECT $NODES_SELECT_COLUMNS
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
                SELECT $NODES_SELECT_COLUMNS
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
    ): List<GraphNode> = searchAndVocabDao.searchNodes(projectId, query, limit, kind)

    fun rebuildVocabulary(projectId: String) =
        searchAndVocabDao.rebuildVocabulary(projectId)

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
            "SELECT $NODES_SELECT_COLUMNS FROM nodes WHERE project_id = ? ORDER BY node_id ASC;"
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

    override fun insertFigmaDocument(document: FigmaDocument) = figmaDao.insertFigmaDocument(document)

    fun getFigmaDocument(fileKey: String): FigmaDocument? = figmaDao.getFigmaDocument(fileKey)

    override fun deleteFigmaDocument(fileKey: String) = figmaDao.deleteFigmaDocument(fileKey)

    override fun insertFigmaNodes(fileKey: String, nodes: List<FigmaNode>) =
        figmaDao.insertFigmaNodes(fileKey, nodes)

    override fun insertFigmaVariables(fileKey: String, variables: List<FigmaVariable>) =
        figmaDao.insertFigmaVariables(fileKey, variables)

    override fun insertFigmaEdges(fileKey: String, edges: List<FigmaEdge>) =
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

    internal fun prepare(sql: String): NativeSqliteStatement = db.prepare(sql)

    internal fun exec(sql: String) {
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



