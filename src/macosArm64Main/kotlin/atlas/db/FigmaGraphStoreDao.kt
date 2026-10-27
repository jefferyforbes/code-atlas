package atlas.db

import atlas.extract.UrlTargetResolver
import atlas.model.FigmaDocument
import atlas.model.FigmaEdge
import atlas.model.FigmaNode
import atlas.model.FigmaSpatialBounds
import atlas.model.FigmaVariable
import atlas.model.FigmaVariableImpact
import atlas.model.FigmaViolation

/**
 * Internal DAO encapsulating Figma document, node, R-Tree spatial index, design token variable,
 * and 12 deterministic SQL design audit queries for [SqliteGraphStore].
 */
internal class FigmaGraphStoreDao(private val db: NativeSqliteDatabase) {

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
        try {
            stmt.bindString(1, fileKey)
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
        db.withSavepoint("sp_del_figma_doc") {
            val scope = SqliteGraphStore.StatementScope(db)
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

    private fun sanitizeFiniteDouble(value: Double): Double =
        if (value.isNaN() || value.isInfinite()) 0.0 else value

    private fun boolToInt(value: Boolean): Int = if (value) 1 else 0

    private fun bindNullableString(stmt: NativeSqliteStatement, index: Int, value: String?) {
        if (value != null) stmt.bindString(index, value) else stmt.bindNull(index)
    }

    private fun bindNullableFiniteDouble(stmt: NativeSqliteStatement, index: Int, value: Double?) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            stmt.bindNull(index)
        } else {
            stmt.bindDouble(index, value)
        }
    }

    private fun bindFigmaNodeRow(stmt: NativeSqliteStatement, node: FigmaNode, fileKey: String) {
        val effectiveFileKey = node.fileKey.ifBlank { fileKey }
        stmt.reset()
        stmt.clearBindings()
        stmt.bindString(1, node.nodeId)
        stmt.bindString(2, effectiveFileKey)
        stmt.bindString(3, node.pageName)
        bindNullableString(stmt, 4, node.sectionId)
        bindNullableString(stmt, 5, node.parentId)
        stmt.bindString(6, node.name)
        stmt.bindString(7, node.nodeType)
        stmt.bindString(8, node.layoutMode)
        stmt.bindString(9, node.sizingHorizontal)
        stmt.bindString(10, node.sizingVertical)
        stmt.bindString(11, node.textAutoResize)
        bindFigmaNodeGeometry(stmt, node)
        bindFigmaNodeFlagsAndContrast(stmt, node)
    }

    private fun bindFigmaNodeGeometry(stmt: NativeSqliteStatement, node: FigmaNode) {
        stmt.bindDouble(12, sanitizeFiniteDouble(node.paddingLeft))
        stmt.bindDouble(13, sanitizeFiniteDouble(node.paddingRight))
        stmt.bindDouble(14, sanitizeFiniteDouble(node.paddingTop))
        stmt.bindDouble(15, sanitizeFiniteDouble(node.paddingBottom))
        stmt.bindDouble(16, sanitizeFiniteDouble(node.itemSpacing))
        stmt.bindDouble(17, sanitizeFiniteDouble(node.localX))
        stmt.bindDouble(18, sanitizeFiniteDouble(node.localY))
        stmt.bindDouble(19, sanitizeFiniteDouble(node.canvasX))
        stmt.bindDouble(20, sanitizeFiniteDouble(node.canvasY))
        stmt.bindDouble(21, sanitizeFiniteDouble(node.width))
        stmt.bindDouble(22, sanitizeFiniteDouble(node.height))
    }

    private fun bindFigmaNodeFlagsAndContrast(stmt: NativeSqliteStatement, node: FigmaNode) {
        stmt.bindInt(23, boolToInt(node.isInteractive))
        stmt.bindInt(24, boolToInt(node.hasEmoji))
        stmt.bindInt(25, boolToInt(node.unboundFill))
        stmt.bindInt(26, boolToInt(node.unboundStroke))
        bindNullableFiniteDouble(stmt, 27, node.wcagContrast)
        stmt.bindString(28, node.semioticLayer)
    }

    private fun bindFigmaSpatialRow(
        spatialStmt: NativeSqliteStatement,
        rowidPk: Long,
        node: FigmaNode
    ) {
        val cX = sanitizeFiniteDouble(node.canvasX)
        val cY = sanitizeFiniteDouble(node.canvasY)
        val w = sanitizeFiniteDouble(node.width)
        val h = sanitizeFiniteDouble(node.height)
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

    private fun insertSingleFigmaNode(
        nodeStmt: NativeSqliteStatement,
        spatialStmt: NativeSqliteStatement,
        fileKey: String,
        node: FigmaNode
    ) {
        bindFigmaNodeRow(nodeStmt, node, fileKey)
        if (nodeStmt.step()) {
            val rowidPk = nodeStmt.columnLong(0)
            bindFigmaSpatialRow(spatialStmt, rowidPk, node)
        }
    }

    fun insertFigmaNodes(fileKey: String, nodes: List<FigmaNode>) {
        if (nodes.isEmpty()) return

        db.withSavepoint("sp_insert_figma_nodes") {
            val scope = SqliteGraphStore.StatementScope(db)
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
                    insertSingleFigmaNode(nodeStmt, spatialStmt, fileKey, node)
                }
            } finally {
                scope.closeAll()
            }
        }
    }

    private fun bindFigmaVariableRow(stmt: NativeSqliteStatement, v: FigmaVariable, fileKey: String) {
        val effectiveFileKey = if (v.fileKey.isNotBlank()) v.fileKey else fileKey
        stmt.reset()
        stmt.clearBindings()
        stmt.bindString(1, v.variableId)
        stmt.bindString(2, effectiveFileKey)
        stmt.bindString(3, v.collectionName)
        stmt.bindString(4, v.name)
        stmt.bindString(5, v.resolvedType)
        stmt.bindInt(6, if (v.isAlias) 1 else 0)
        bindNullableString(stmt, 7, v.aliasedVariableId)
        stmt.bindString(8, v.modesJson)
        bindNullableString(stmt, 9, v.codeSyntaxCompose)
        bindNullableString(stmt, 10, v.codeSyntaxWeb)
        stmt.step()
    }

    fun insertFigmaVariables(fileKey: String, variables: List<FigmaVariable>) {
        if (variables.isEmpty()) return

        db.withSavepoint("sp_insert_figma_vars") {
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
                    bindFigmaVariableRow(stmt, v, fileKey)
                }
            } finally {
                stmt.close()
            }
        }
    }

    fun insertFigmaEdges(fileKey: String, edges: List<FigmaEdge>) {
        if (edges.isEmpty()) return

        db.withSavepoint("sp_insert_figma_edges") {
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
        val safeMinX = sanitizeFiniteDouble(minX)
        val safeMaxX = sanitizeFiniteDouble(maxX)
        val safeMinY = sanitizeFiniteDouble(minY)
        val safeMaxY = sanitizeFiniteDouble(maxY)
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
        val results = mutableListOf<FigmaNode>()
        try {
            stmt.bindString(1, fileKey)
            stmt.bindDouble(2, qMinX)
            stmt.bindDouble(3, qMaxX)
            stmt.bindDouble(4, qMinY)
            stmt.bindDouble(5, qMaxY)
            while (stmt.step()) {
                results.add(rowToFigmaNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    fun queryFigmaNodeById(fileKey: String, nodeId: String): FigmaNode? {
        val parsedFromFileKey = UrlTargetResolver.parseFigmaUrl(fileKey)
        val parsedFromNodeId = UrlTargetResolver.parseFigmaUrl(nodeId)
        val normFileKey = parsedFromFileKey?.fileKey ?: parsedFromNodeId?.fileKey ?: fileKey
        val normNodeId = parsedFromNodeId?.nodeId
            ?: (if (nodeId.isBlank()) parsedFromFileKey?.nodeId ?: "" else UrlTargetResolver.normalizeFigmaNodeId(nodeId))
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
        try {
            stmt.bindString(1, normFileKey)
            stmt.bindString(2, normNodeId)
            if (stmt.step()) {
                return rowToFigmaNode(stmt)
            }
            return null
        } finally {
            stmt.close()
        }
    }

    fun queryFigmaNodesForFile(fileKey: String, limit: Int): List<FigmaNode> {
        val stmt = db.prepare(
            """
            SELECT rowid_pk, node_id, file_key, page_name, section_id, parent_id,
                   name, node_type, layout_mode, sizing_horizontal, sizing_vertical,
                   text_auto_resize, padding_left, padding_right, padding_top, padding_bottom,
                   item_spacing, local_x, local_y, canvas_x, canvas_y, width, height,
                   is_interactive, has_emoji, unbound_fill, unbound_stroke, wcag_contrast, semiotic_layer
            FROM figma_nodes WHERE file_key = ? LIMIT ?;
            """.trimIndent()
        )
        val list = mutableListOf<FigmaNode>()
        try {
            stmt.bindString(1, fileKey)
            stmt.bindInt(2, limit)
            while (stmt.step()) list.add(rowToFigmaNode(stmt))
        } finally {
            stmt.close()
        }
        return list
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
        val results = mutableListOf<FigmaNode>()
        try {
            stmt.bindString(1, fileKey)
            stmt.bindString(2, name)
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
        val results = mutableListOf<FigmaNode>()
        try {
            stmt.bindString(1, fileKey)
            stmt.bindString(2, nodeType)
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
        try {
            stmt.bindString(1, fileKey)
            stmt.bindString(2, variableId)
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
        try {
            stmt.bindString(1, fileKey)
            stmt.bindString(2, name)
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
        val results = mutableListOf<FigmaEdge>()
        try {
            stmt.bindString(1, fileKey)
            stmt.bindString(2, sourceNodeId)
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
        val results = mutableListOf<FigmaEdge>()
        try {
            stmt.bindString(1, fileKey)
            stmt.bindString(2, targetNodeId)
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
        val results = mutableListOf<FigmaEdge>()
        try {
            stmt.bindString(1, fileKey)
            stmt.bindString(2, edgeType)
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
        val results = mutableListOf<FigmaVariable>()
        try {
            stmt.bindString(1, fileKey)
            stmt.bindString(2, variableIdOrName)
            stmt.bindString(3, variableIdOrName)
            stmt.bindString(4, fileKey)
            stmt.bindInt(5, maxDepth)
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
        val results = mutableListOf<FigmaNode>()
        try {
            stmt.bindString(1, fileKey)
            stmt.bindString(2, variableId)
            stmt.bindString(3, fileKey)
            stmt.bindString(4, variableId)
            stmt.bindString(5, fileKey)
            stmt.bindString(6, variableId)
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
        val parsedFromFileKey = UrlTargetResolver.parseFigmaUrl(fileKey)
        val parsedFromTarget = UrlTargetResolver.parseFigmaUrl(componentNameOrId)
        val normFileKey = parsedFromFileKey?.fileKey ?: parsedFromTarget?.fileKey ?: fileKey
        val normTarget = parsedFromTarget?.nodeId
            ?: (if (componentNameOrId.isBlank()) parsedFromFileKey?.nodeId ?: "" else UrlTargetResolver.normalizeFigmaNodeId(componentNameOrId))
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
                   n.name, n.node_type, layout_mode, n.sizing_horizontal, n.sizing_vertical,
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
        val results = mutableListOf<FigmaNode>()
        try {
            stmt.bindString(1, normFileKey)
            stmt.bindString(2, normTarget)
            stmt.bindString(3, normFileKey)
            stmt.bindString(4, normTarget)
            stmt.bindString(5, normFileKey)
            stmt.bindString(6, normFileKey)
            stmt.bindInt(7, maxDepth)
            stmt.bindString(8, normFileKey)
            while (stmt.step()) {
                results.add(rowToFigmaNode(stmt))
            }
        } finally {
            stmt.close()
        }
        return results
    }

    internal fun rowToFigmaNode(stmt: NativeSqliteStatement): FigmaNode {
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
        val results = mutableListOf<Pair<FigmaNode, Int>>()
        try {
            stmt.bindString(1, fileKey)
            stmt.bindInt(2, limit)
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
            fetchFigmaNodeChunk(fileKey, chunk, byId)
        }
        return nodeIds.mapNotNull { byId[it] }
    }

    private fun fetchFigmaNodeChunk(
        fileKey: String,
        chunk: List<String>,
        outById: MutableMap<String, FigmaNode>
    ) {
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
                outById[node.nodeId] = node
            }
        } finally {
            stmt.close()
        }
    }

    private fun resolveEffectiveFigmaFileKey(fileKey: String, src: String, dst: String): String =
        UrlTargetResolver.parseFigmaUrl(fileKey)?.fileKey
            ?: UrlTargetResolver.parseFigmaUrl(src)?.fileKey
            ?: UrlTargetResolver.parseFigmaUrl(dst)?.fileKey
            ?: fileKey

    private fun resolveFigmaNode(fileKey: String, target: String): FigmaNode? =
        queryFigmaNodeById(fileKey, target)
            ?: queryFigmaNodesByName(fileKey, target).firstOrNull()

    private data class FigmaBfsStep(
        val fileKey: String,
        val currentId: String,
        val currentPath: List<String>,
        val destNodeId: String
    )

    private fun expandFigmaShortestPathNeighbors(
        edgeStmt: NativeSqliteStatement,
        step: FigmaBfsStep,
        visited: MutableSet<String>,
        queue: MutableList<Pair<String, List<String>>>
    ): List<String>? {
        edgeStmt.reset()
        edgeStmt.clearBindings()
        edgeStmt.bindString(1, step.fileKey)
        edgeStmt.bindString(2, step.currentId)
        edgeStmt.bindString(3, step.fileKey)
        edgeStmt.bindString(4, step.currentId)

        while (edgeStmt.step()) {
            val nextId = edgeStmt.columnString(0)
            if (nextId == step.destNodeId) {
                return step.currentPath + nextId
            }
            if (visited.add(nextId)) {
                queue.add(Pair(nextId, step.currentPath + nextId))
            }
        }
        return null
    }

    fun findFigmaShortestPath(
        fileKey: String,
        sourceTarget: String,
        destTarget: String,
        depth: Int = 10
    ): List<FigmaNode> {
        val effectiveKey = resolveEffectiveFigmaFileKey(fileKey, sourceTarget, destTarget)
        val srcNode = resolveFigmaNode(effectiveKey, sourceTarget) ?: return emptyList()
        val dstNode = resolveFigmaNode(effectiveKey, destTarget) ?: return emptyList()
        if (srcNode.nodeId == dstNode.nodeId) return listOf(srcNode)

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
        return try {
            bfsFigmaPath(edgeStmt, effectiveKey, srcNode.nodeId to dstNode.nodeId, depth)
        } finally {
            edgeStmt.close()
        }
    }

    private fun bfsFigmaPath(
        edgeStmt: NativeSqliteStatement,
        effectiveKey: String,
        endpoints: Pair<String, String>,
        depth: Int
    ): List<FigmaNode> {
        val (srcId, dstId) = endpoints
        val queue = mutableListOf(Pair(srcId, listOf(srcId)))
        val visited = mutableSetOf(srcId)
        var head = 0
        while (head < queue.size) {
            val (curr, path) = queue[head++]
            if (path.size > depth) break
            val step = FigmaBfsStep(effectiveKey, curr, path, dstId)
            val foundPath = expandFigmaShortestPathNeighbors(edgeStmt, step, visited, queue)
            if (foundPath != null) {
                return queryFigmaNodesByIds(effectiveKey, foundPath)
            }
        }
        return emptyList()
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

    fun queryCircularFillInHugViolations(fileKey: String): List<FigmaViolation> {
        val violations = mutableListOf<FigmaViolation>()
        queryAxisFillInHug(fileKey, "sizing_vertical", "CIRCULAR_V_FILL_IN_HUG", "vertical", violations)
        queryAxisFillInHug(fileKey, "sizing_horizontal", "CIRCULAR_H_FILL_IN_HUG", "horizontal", violations)
        return violations
    }

    private fun queryAxisFillInHug(
        fileKey: String,
        column: String,
        rule: String,
        axisLabel: String,
        out: MutableList<FigmaViolation>
    ) {
        val stmt = db.prepare(
            """
            SELECT c.node_id, c.name, p.name
            FROM figma_nodes c
            JOIN figma_nodes p ON c.parent_id = p.node_id AND c.file_key = p.file_key
            WHERE c.file_key = ? AND c.$column = 'FILL' AND p.$column = 'HUG';
            """.trimIndent()
        )
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                val cId = stmt.columnString(0)
                val cName = stmt.columnString(1)
                val pName = stmt.columnString(2)
                out.add(
                    FigmaViolation(
                        rule = rule,
                        nodeId = cId,
                        nodeName = cName,
                        message = "Child node '$cName' has $axisLabel sizing FILL inside parent '$pName' with $axisLabel sizing HUG"
                    )
                )
            }
        } finally {
            stmt.close()
        }
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

        val unboundCount = countUnboundFigmaNodes(fileKey)
        val ratio = unboundCount.toDouble() / totalCount.toDouble()
        if (ratio <= 0.05) return emptyList()

        val shortPct = (ratio * 100.0).toString().take(5)
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
            while (stmt.step()) {
                violations.add(mapUnboundTokenRow(stmt, shortPct))
            }
        } finally {
            stmt.close()
        }
        return violations
    }

    private fun countUnboundFigmaNodes(fileKey: String): Int {
        val unbStmt = db.prepare(
            "SELECT COUNT(*) FROM figma_nodes WHERE file_key = ? AND (unbound_fill = 1 OR unbound_stroke = 1);"
        )
        return try {
            unbStmt.bindString(1, fileKey)
            if (unbStmt.step()) unbStmt.columnInt(0) else 0
        } finally {
            unbStmt.close()
        }
    }

    private fun mapUnboundTokenRow(stmt: NativeSqliteStatement, shortPct: String): FigmaViolation {
        val id = stmt.columnString(0)
        val name = stmt.columnString(1)
        val uf = stmt.columnInt(2) != 0
        val us = stmt.columnInt(3) != 0
        val kind = unboundTokenSlotKind(uf, us)
        return FigmaViolation(
            rule = "UNBOUND_TOKENS",
            nodeId = id,
            nodeName = name,
            message = "Node '$name' has unbound $kind (file unbound ratio: $shortPct%, exceeds 5% threshold)"
        )
    }

    private fun unboundTokenSlotKind(unboundFill: Boolean, unboundStroke: Boolean): String = when {
        unboundFill && unboundStroke -> "fill and stroke"
        unboundFill -> "fill"
        else -> "stroke"
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
}
