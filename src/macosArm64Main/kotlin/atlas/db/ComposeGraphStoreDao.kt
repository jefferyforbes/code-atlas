package atlas.db

import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.ComposeQueryResult

/**
 * Internal DAO encapsulating Jetpack Compose & KMP stability node persistence, parameter provenance
 * tracking, runtime recomposition trace metrics, and stability cascade/blame queries for [SqliteGraphStore].
 */
internal class ComposeGraphStoreDao(private val db: NativeSqliteDatabase) {

    fun provenanceRank(provenance: String): Int = when (provenance.uppercase()) {
        "RUNTIME_TRACED" -> 4
        "COMPILER_VERIFIED" -> 3
        "STABILITY_CONFIG" -> 2
        else -> 1 // STATIC_INFERRED
    }

    private fun queryExistingComposeProvenance(
        scope: SqliteGraphStore.StatementScope,
        projectSlug: String
    ): Map<String, Int> {
        val existingProv = mutableMapOf<String, Int>()
        val qStmt = scope.prepare("SELECT node_id, provenance FROM compose_nodes WHERE project_slug = ?;")
        qStmt.bindString(1, projectSlug)
        while (qStmt.step()) {
            existingProv[qStmt.columnString(0)] = provenanceRank(qStmt.columnString(1))
        }
        return existingProv
    }

    private fun boolToInt(value: Boolean): Int = if (value) 1 else 0

    private fun bindComposeNodeFlags(stmt: NativeSqliteStatement, n: ComposeNodeRecord) {
        stmt.bindInt(8, boolToInt(n.isRestartable))
        stmt.bindInt(9, boolToInt(n.isSkippable))
        stmt.bindInt(10, boolToInt(n.isReadonly))
        stmt.bindInt(11, boolToInt(n.isInline))
        stmt.bindInt(12, boolToInt(n.returnsUnit))
        stmt.bindInt(13, boolToInt(n.hasModifierParam))
        stmt.bindInt(14, boolToInt(n.modifierIsFirstOptional))
        stmt.bindInt(15, boolToInt(n.modifierHasDefault))
    }

    private fun bindComposeNodeRow(stmt: NativeSqliteStatement, n: ComposeNodeRecord) {
        stmt.reset()
        stmt.bindString(1, n.nodeId)
        stmt.bindString(2, n.projectSlug)
        stmt.bindString(3, n.fqName)
        stmt.bindString(4, n.filePath)
        stmt.bindInt(5, n.startLine)
        stmt.bindInt(6, n.endLine)
        stmt.bindString(7, n.sourceSet)
        bindComposeNodeFlags(stmt, n)
        stmt.bindInt(16, n.unstableParamCount)
        stmt.bindInt(17, n.runtimeParamCount)
        stmt.bindString(18, n.receiverStability)
        stmt.bindString(19, n.provenance)
        stmt.bindInt(20, n.violationMask)
        stmt.step()
    }

    private fun updateComposeNodeLinesAndMask(stmt: NativeSqliteStatement, n: ComposeNodeRecord) {
        stmt.reset()
        stmt.clearBindings()
        stmt.bindInt(1, n.startLine)
        stmt.bindInt(2, n.endLine)
        stmt.bindInt(3, n.violationMask)
        stmt.bindString(4, n.nodeId)
        stmt.step()
    }

    private fun upsertSingleComposeNode(
        stmts: Pair<NativeSqliteStatement, NativeSqliteStatement>,
        n: ComposeNodeRecord,
        existingProv: Map<String, Int>,
        preserveHigherProvenance: Boolean
    ) {
        val (nodeStmt, updStmt) = stmts
        val prevRank = existingProv[n.nodeId] ?: 0
        val newRank = provenanceRank(n.provenance)
        if (preserveHigherProvenance && prevRank > newRank) {
            updateComposeNodeLinesAndMask(updStmt, n)
        } else {
            bindComposeNodeRow(nodeStmt, n)
        }
    }

    private fun bindComposeParamRow(stmt: NativeSqliteStatement, p: ComposeParamRecord) {
        stmt.reset()
        stmt.bindString(1, p.nodeId)
        stmt.bindInt(2, p.paramIndex)
        stmt.bindString(3, p.paramName)
        stmt.bindString(4, p.typeSignature)
        stmt.bindString(5, p.stability)
        stmt.bindString(6, p.stabilityReason)
        stmt.bindInt(7, if (p.hasDefault) 1 else 0)
        stmt.bindInt(8, if (p.isUsedInBody) 1 else 0)
        stmt.bindString(9, p.provenance)
        stmt.step()
    }

    private fun upsertSingleComposeParam(
        paramStmt: NativeSqliteStatement,
        p: ComposeParamRecord,
        existingProv: Map<String, Int>,
        preserveHigherProvenance: Boolean
    ) {
        val prevRank = existingProv[p.nodeId] ?: 0
        val newRank = provenanceRank(p.provenance)
        if (preserveHigherProvenance && prevRank > newRank) return
        bindComposeParamRow(paramStmt, p)
    }

    fun upsertComposeNodesAndParams(
        nodes: List<ComposeNodeRecord>,
        params: List<ComposeParamRecord>,
        preserveHigherProvenance: Boolean = true
    ) {
        if (nodes.isEmpty() && params.isEmpty()) return
        db.withSavepoint("sp_compose_upsert") {
            val scope = SqliteGraphStore.StatementScope(db)
            try {
                val existingProv = if (preserveHigherProvenance && nodes.isNotEmpty()) {
                    queryExistingComposeProvenance(scope, nodes.first().projectSlug)
                } else {
                    emptyMap()
                }

                val nodeStmt = scope.prepare(
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
                val updStmt = scope.prepare(
                    """
                    UPDATE compose_nodes
                    SET start_line = ?, end_line = ?, violation_mask = ?
                    WHERE node_id = ?;
                    """.trimIndent()
                )
                val nodeStmts = nodeStmt to updStmt
                for (n in nodes) {
                    upsertSingleComposeNode(nodeStmts, n, existingProv, preserveHigherProvenance)
                }

                val paramStmt = scope.prepare(
                    """
                    INSERT OR REPLACE INTO compose_params (
                        node_id, param_index, param_name, type_signature,
                        stability, stability_reason, has_default, is_used_in_body, provenance
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
                    """.trimIndent()
                )
                for (p in params) {
                    upsertSingleComposeParam(paramStmt, p, existingProv, preserveHigherProvenance)
                }
            } finally {
                scope.closeAll()
            }
        }
    }

    fun deleteComposeNodesForFile(projectSlug: String, filePath: String) {
        db.withSavepoint("sp_compose_del_file") {
            val delMetrics = db.prepare(
                """
                DELETE FROM compose_trace_metrics
                WHERE node_id IN (
                    SELECT node_id FROM compose_nodes WHERE project_slug = ? AND file_path = ?
                );
                """.trimIndent()
            )
            try {
                delMetrics.bindString(1, projectSlug)
                delMetrics.bindString(2, filePath)
                delMetrics.step()
            } finally {
                delMetrics.close()
            }

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

    data class ComposeTraceMetricUpsert(
        val nodeId: String,
        val sessionId: String,
        val recompositionCount: Int,
        val skipCount: Int,
        val dirtyParamsJson: String,
        val recordedAt: Long
    )

    fun upsertComposeTraceMetric(record: ComposeTraceMetricUpsert) {
        db.withSavepoint("sp_compose_trace") {
            val stmt = db.prepare(
                """
                INSERT OR REPLACE INTO compose_trace_metrics (
                    node_id, session_id, recomposition_count, skip_count, dirty_params_json, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?);
                """.trimIndent()
            )
            try {
                stmt.bindString(1, record.nodeId)
                stmt.bindString(2, record.sessionId)
                stmt.bindInt(3, record.recompositionCount)
                stmt.bindInt(4, record.skipCount)
                stmt.bindString(5, record.dirtyParamsJson)
                stmt.bindLong(6, record.recordedAt)
                stmt.step()
            } finally {
                stmt.close()
            }

            val upd = db.prepare("UPDATE compose_nodes SET provenance = 'RUNTIME_TRACED' WHERE node_id = ?;")
            try {
                upd.bindString(1, record.nodeId)
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

        return params.map { p -> buildBlameHopForParam(projectSlug, composableId, p) }
    }

    private fun buildBlameHopForParam(
        projectSlug: String,
        composableId: String,
        p: ComposeParamRecord
    ): ComposeQueryResult.BlameHop {
        val cleanType = p.typeSignature
            .substringBefore('<')
            .substringAfterLast('.')
            .removeSuffix("?")
            .trim()
        val (rootNodeId, rootFile, rootLine) = lookupBlameRootNode(projectSlug, cleanType)
        return ComposeQueryResult.BlameHop(
            composableId = composableId,
            paramName = p.paramName,
            typeSignature = p.typeSignature,
            stability = p.stability,
            rootCauseNodeId = rootNodeId,
            rootCauseFile = rootFile,
            rootCauseLine = rootLine,
            blameReason = p.stabilityReason
        )
    }

    private fun lookupBlameRootNode(projectSlug: String, cleanType: String): Triple<String?, String?, Int?> {
        val nodeStmt = db.prepare(
            """
            SELECT node_id, file_id, start_line
            FROM nodes
            WHERE project_id = ? AND name = ? AND kind IN ('class', 'interface')
            LIMIT 1;
            """.trimIndent()
        )
        return try {
            nodeStmt.bindString(1, projectSlug)
            nodeStmt.bindString(2, cleanType)
            if (nodeStmt.step()) {
                Triple(
                    nodeStmt.columnString(0),
                    nodeStmt.columnString(1).substringAfter(':'),
                    nodeStmt.columnInt(2)
                )
            } else {
                Triple(null, null, null)
            }
        } finally {
            nodeStmt.close()
        }
    }

    fun countPropertyMutations(
        projectSlug: String,
        targetSymbolOrNodeId: String,
        ownerClassName: String? = null
    ): Int {
        val cleanSym = targetSymbolOrNodeId.removePrefix("sym:")
        val escSym = cleanSym.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        if (!ownerClassName.isNullOrEmpty()) {
            return countOwnerPropertyMutations(projectSlug, targetSymbolOrNodeId, cleanSym, escSym, ownerClassName)
        }
        return countStandalonePropertyMutations(projectSlug, targetSymbolOrNodeId, cleanSym, escSym)
    }

    private fun countOwnerPropertyMutations(
        projectSlug: String,
        targetSymbolOrNodeId: String,
        cleanSym: String,
        escSym: String,
        ownerClassName: String
    ): Int {
        val stmt = db.prepare(
            """
            SELECT COUNT(*) FROM edges
            WHERE project_id = ?
              AND edge_type = 'MUTATES_PROPERTY'
              AND (
                target_node_id = ?
                OR target_node_id = ?
                OR target_node_id = ?
                OR target_node_id LIKE ? ESCAPE '\'
                OR target_node_id LIKE ? ESCAPE '\'
              );
            """.trimIndent()
        )
        val cleanClass = ownerClassName.substringAfterLast('.').removePrefix("sym:")
        val escClass = cleanClass.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return try {
            stmt.bindString(1, projectSlug)
            stmt.bindString(2, "sym:$cleanClass.$cleanSym")
            stmt.bindString(3, "$cleanClass.$cleanSym")
            stmt.bindString(4, targetSymbolOrNodeId)
            stmt.bindString(5, "%#$escClass.$escSym")
            stmt.bindString(6, "%#$escSym")
            if (stmt.step()) stmt.columnInt(0) else 0
        } finally {
            stmt.close()
        }
    }

    private fun countStandalonePropertyMutations(
        projectSlug: String,
        targetSymbolOrNodeId: String,
        cleanSym: String,
        escSym: String
    ): Int {
        val stmt = db.prepare(
            """
            SELECT COUNT(*) FROM edges
            WHERE project_id = ?
              AND edge_type = 'MUTATES_PROPERTY'
              AND (
                target_node_id = ?
                OR target_node_id = ?
                OR target_node_id LIKE ? ESCAPE '\'
              );
            """.trimIndent()
        )
        return try {
            stmt.bindString(1, projectSlug)
            stmt.bindString(2, targetSymbolOrNodeId)
            stmt.bindString(3, "sym:$cleanSym")
            stmt.bindString(4, "%#$escSym")
            if (stmt.step()) stmt.columnInt(0) else 0
        } finally {
            stmt.close()
        }
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
