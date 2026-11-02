package atlas.db

import atlas.model.FigmaViolation

/**
 * Internal DAO encapsulating the 12 deterministic SQL design lint rules (AutoLayout circular
 * sizing, static containers, default 100px frames, 48dp touch targets, negative coordinates,
 * child amputation width, zero-emoji rule, unbound tokens, ad-hoc gym specimens, 2D spatial
 * topology, and WCAG contrast) for [FigmaGraphStoreDao].
 */
internal class FigmaAuditRuleDao(
    private val db: NativeSqliteDatabase,
    private val countNodes: (String) -> Int
) {

    fun queryCircularFillInHugViolations(fileKey: String): List<FigmaViolation> =
        queryAxisFillInHug(fileKey, "sizing_vertical", "CIRCULAR_V_FILL_IN_HUG", "vertical") +
            queryAxisFillInHug(fileKey, "sizing_horizontal", "CIRCULAR_H_FILL_IN_HUG", "horizontal")

    private fun queryAxisFillInHug(
        fileKey: String,
        column: String,
        rule: String,
        axisLabel: String
    ): List<FigmaViolation> = queryViolations(
        fileKey,
        "SELECT c.node_id, c.name, p.name FROM figma_nodes c " +
            "JOIN figma_nodes p ON c.parent_id = p.node_id AND c.file_key = p.file_key " +
            "WHERE c.file_key = ? AND c.$column = 'FILL' AND p.$column = 'HUG';"
    ) { stmt ->
        val cName = stmt.columnString(1)
        val pName = stmt.columnString(2)
        FigmaViolation(
            rule = rule,
            nodeId = stmt.columnString(0),
            nodeName = cName,
            message = "Child node '$cName' has $axisLabel sizing FILL inside parent '$pName' with $axisLabel sizing HUG"
        )
    }

    fun queryStaticContainerFrameViolations(fileKey: String): List<FigmaViolation> =
        queryViolations(
            fileKey,
            """
            SELECT node_id, name, node_type FROM figma_nodes
            WHERE file_key = ? AND layout_mode = 'NONE'
              AND (node_type IN ('COMPONENT', 'COMPONENT_SET') OR is_interactive = 1
                   OR LOWER(name) LIKE '%button%' OR LOWER(name) LIKE '%chip%'
                   OR LOWER(name) LIKE '%card%' OR LOWER(name) LIKE '%input%')
              AND node_type NOT IN ('CANVAS_SECTION', 'SECTION', 'PAGE', 'DOCUMENT', 'VECTOR', 'RECTANGLE', 'TEXT', 'ELLIPSE', 'LINE', 'STAR', 'REGULAR_POLYGON', 'GROUP');
            """.trimIndent()
        ) { stmt ->
            val name = stmt.columnString(1)
            val type = stmt.columnString(2)
            FigmaViolation(
                rule = "STATIC_CONTAINER_FRAME",
                nodeId = stmt.columnString(0),
                nodeName = name,
                message = "Interactive container or component '$name' ($type) uses static layoutMode NONE instead of AutoLayout"
            )
        }

    fun queryDefault100PxFrameHeightViolations(fileKey: String): List<FigmaViolation> =
        queryViolations(
            fileKey,
            """
            SELECT node_id, name FROM figma_nodes
            WHERE file_key = ? AND node_type IN ('FRAME', 'COMPONENT', 'INSTANCE')
              AND abs(height - 100.0) < 0.001 AND sizing_vertical = 'FIXED'
              AND (parent_id IS NOT NULL OR node_type = 'FRAME');
            """.trimIndent()
        ) { stmt ->
            val name = stmt.columnString(1)
            FigmaViolation(
                rule = "DEFAULT_100PX_FRAME_HEIGHT",
                nodeId = stmt.columnString(0),
                nodeName = name,
                message = "Frame '$name' has default fixed height 100.0px (stuck at figma.createFrame() default)"
            )
        }

    fun queryMinTouchTargetViolations(fileKey: String): List<FigmaViolation> =
        queryViolations(
            fileKey,
            """
            SELECT c.node_id, c.name, c.width, c.height FROM figma_nodes c
            LEFT JOIN figma_nodes p ON c.parent_id = p.node_id AND c.file_key = p.file_key
            WHERE c.file_key = ? AND c.is_interactive = 1 AND (c.width < 48.0 OR c.height < 48.0)
              AND (p.node_id IS NULL OR p.width < 48.0 OR p.height < 48.0 OR p.layout_mode = 'NONE'
                   OR (p.node_type != 'TOUCH_TARGET_FRAME' AND LOWER(p.name) NOT LIKE '%touch%'
                       AND LOWER(p.name) NOT LIKE '%hitbox%' AND (p.width > 80.0 OR p.height > 80.0)
                       AND (SELECT COUNT(*) FROM figma_nodes WHERE parent_id = p.node_id AND file_key = c.file_key) > 1));
            """.trimIndent()
        ) { stmt ->
            val name = stmt.columnString(1)
            val w = stmt.columnDouble(2)
            val h = stmt.columnDouble(3)
            FigmaViolation(
                rule = "MIN_TOUCH_TARGET",
                nodeId = stmt.columnString(0),
                nodeName = name,
                message = "Interactive control '$name' has hitbox dimensions ${w}x${h} (< 48x48dp) without a >= 48dp AutoLayout container"
            )
        }

    fun queryNegativeCoordinateViolations(fileKey: String): List<FigmaViolation> =
        queryViolations(
            fileKey,
            "SELECT node_id, name, local_x, local_y FROM figma_nodes " +
                "WHERE file_key = ? AND (local_x < -0.001 OR local_y < -0.001) AND parent_id IS NOT NULL;"
        ) { stmt ->
            val name = stmt.columnString(1)
            val lx = stmt.columnDouble(2)
            val ly = stmt.columnDouble(3)
            FigmaViolation(
                rule = "NEGATIVE_COORDINATES",
                nodeId = stmt.columnString(0),
                nodeName = name,
                message = "Node '$name' has negative local offset coordinates (localX=$lx, localY=$ly)"
            )
        }

    fun queryChildAmputationWidthViolations(fileKey: String): List<FigmaViolation> =
        queryViolations(
            fileKey,
            """
            SELECT c.node_id, c.name, c.width, p.name, p.width FROM figma_nodes c
            JOIN figma_nodes p ON c.parent_id = p.node_id AND c.file_key = p.file_key
            WHERE c.file_key = ? AND p.layout_mode IN ('HORIZONTAL', 'VERTICAL')
              AND (p.padding_left > 0.0 OR p.padding_right > 0.0) AND c.sizing_horizontal = 'FIXED'
              AND c.width >= (p.width - 0.001) AND p.width > 0.0;
            """.trimIndent()
        ) { stmt ->
            val cName = stmt.columnString(1)
            val cWidth = stmt.columnDouble(2)
            val pName = stmt.columnString(3)
            val pWidth = stmt.columnDouble(4)
            FigmaViolation(
                rule = "CHILD_AMPUTATION_WIDTH",
                nodeId = stmt.columnString(0),
                nodeName = cName,
                message = "Child node '$cName' has fixed width ($cWidth) >= parent width ($pWidth) inside padded AutoLayout parent '$pName' (must use layoutSizingHorizontal = FILL)"
            )
        }

    fun queryNoEmojiViolations(fileKey: String): List<FigmaViolation> =
        queryViolations(
            fileKey,
            "SELECT node_id, name FROM figma_nodes WHERE file_key = ? AND has_emoji = 1;"
        ) { stmt ->
            val name = stmt.columnString(1)
            FigmaViolation(
                rule = "NO_EMOJI",
                nodeId = stmt.columnString(0),
                nodeName = name,
                message = "Node '$name' contains unicode emojis in node name or text content"
            )
        }

    fun queryUnboundTokenViolations(fileKey: String): List<FigmaViolation> {
        val totalCount = countNodes(fileKey)
        if (totalCount == 0) return emptyList()

        val unboundCount = countUnboundFigmaNodes(fileKey)
        val ratio = unboundCount.toDouble() / totalCount.toDouble()
        if (ratio <= 0.05) return emptyList()

        val shortPct = (ratio * 100.0).toString().take(5)
        return queryViolations(
            fileKey,
            "SELECT node_id, name, unbound_fill, unbound_stroke FROM figma_nodes WHERE file_key = ? AND (unbound_fill = 1 OR unbound_stroke = 1);"
        ) { stmt ->
            mapUnboundTokenRow(stmt, shortPct)
        }
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

    fun queryAdHocDetachedSpecimenViolations(fileKey: String): List<FigmaViolation> =
        queryViolations(
            fileKey,
            """
            SELECT n.node_id, n.name, p.name FROM figma_nodes n
            JOIN figma_nodes p ON n.parent_id = p.node_id AND n.file_key = p.file_key
            WHERE n.file_key = ? AND n.node_type = 'FRAME'
              AND (LOWER(p.name) LIKE '%gym%' OR LOWER(p.name) LIKE '%arena%' OR LOWER(p.name) LIKE '%bay%'
                   OR (n.canvas_x >= 3800.0 AND n.canvas_y >= 4800.0 AND p.parent_id IS NOT NULL))
              AND LOWER(n.name) NOT LIKE '%gym%' AND LOWER(n.name) NOT LIKE '%arena%' AND LOWER(n.name) NOT LIKE '%bay%';
            """.trimIndent()
        ) { stmt ->
            val name = stmt.columnString(1)
            val pName = stmt.columnString(2)
            FigmaViolation(
                rule = "AD_HOC_DETACHED_SPECIMEN",
                nodeId = stmt.columnString(0),
                nodeName = name,
                message = "Node '$name' is a detached static FRAME inside gym container '$pName'; must instantiate a live master component INSTANCE"
            )
        }

    fun queryCanvasSpatialTopologyViolations(fileKey: String): List<FigmaViolation> =
        queryViolations(
            fileKey,
            """
            SELECT n.node_id, n.name, s.min_x, s.min_y FROM figma_nodes n
            JOIN figma_spatial_index s ON n.rowid_pk = s.id
            WHERE n.file_key = ? AND n.node_type NOT IN ('DOCUMENT', 'PAGE')
              AND (n.node_type IN ('CANVAS_SECTION', 'SECTION') OR n.parent_id IS NULL)
              AND ((abs(s.min_y) > 200.0 AND (s.min_y < 4500.0 OR s.min_y > 15000.0))
                   OR s.min_y < -100.0 OR s.min_x < -100.0
                   OR ((LOWER(n.name) LIKE '%flow%' OR n.name LIKE '0%' OR LOWER(n.name) LIKE 'section 0%') AND abs(s.min_y) > 200.0)
                   OR (LOWER(n.name) LIKE '%design system%' AND (abs(s.min_x) > 200.0 OR abs(s.min_y - 5000.0) > 500.0))
                   OR (LOWER(n.name) LIKE '%gym%' AND (s.min_x < 3800.0 OR s.min_y < 4500.0)));
            """.trimIndent()
        ) { stmt ->
            val name = stmt.columnString(1)
            val mx = stmt.columnDouble(2)
            val my = stmt.columnDouble(3)
            FigmaViolation(
                rule = "CANVAS_SPATIAL_TOPOLOGY",
                nodeId = stmt.columnString(0),
                nodeName = name,
                message = "Top-level canvas section '$name' at (x=$mx, y=$my) violates the 2D matrix spatial grid (Row 1 at y=0, Design System at y=5000/x=0, Gyms at y>=5000/x>=4000)"
            )
        }

    fun queryWcagContrastViolations(fileKey: String): List<FigmaViolation> =
        queryViolations(
            fileKey,
            """
            SELECT node_id, name, wcag_contrast, height FROM figma_nodes
            WHERE file_key = ? AND wcag_contrast IS NOT NULL
              AND ((height >= 24.0 AND wcag_contrast < 3.0) OR (height < 24.0 AND wcag_contrast < 4.5));
            """.trimIndent()
        ) { stmt ->
            val name = stmt.columnString(1)
            val contrast = stmt.columnDouble(2)
            val height = stmt.columnDouble(3)
            val req = if (height >= 24.0) "3.0:1 for large text" else "4.5:1 for normal text"
            FigmaViolation(
                rule = "WCAG_CONTRAST",
                nodeId = stmt.columnString(0),
                nodeName = name,
                message = "Node '$name' has WCAG contrast ratio $contrast:1 (below required $req)"
            )
        }

    private fun queryViolations(
        fileKey: String,
        sql: String,
        mapper: (NativeSqliteStatement) -> FigmaViolation
    ): List<FigmaViolation> {
        val stmt = db.prepare(sql)
        val violations = mutableListOf<FigmaViolation>()
        try {
            stmt.bindString(1, fileKey)
            while (stmt.step()) {
                violations.add(mapper(stmt))
            }
        } finally {
            stmt.close()
        }
        return violations
    }
}
