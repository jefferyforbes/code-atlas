package atlas.engine

import atlas.db.NativeSqliteStatement
import atlas.db.SqliteGraphStore
import atlas.model.Figma3DScope
import atlas.model.FigmaAuditResult
import atlas.model.FigmaNode
import atlas.model.FigmaSpatialBounds
import atlas.model.FigmaViolation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sovereign Design Invariant Auditor executing 12 deterministic SQL invariant audits
 * against SqliteGraphStore with sub-5ms latency and synthesizing 3D Figma Concurrency Scopes.
 */
class FigmaDesignAuditor(private val store: SqliteGraphStore) {

    companion object {
        val DEFAULT_POLISH_DELTA = listOf(
            "Optical kerning and tabular numeral alignment on dynamic data labels",
            "Responsive fluid auto-wrap transitions between mobile and tablet breakpoints",
            "Micro-interaction spring physics and capacitive press state damping",
            "Dynamic multi-line text wrapping resilience and localized string safety"
        )
    }

    /**
     * Runs the 12 deterministic design invariant audits and evaluates Gate 1 maturity compliance.
     */
    fun audit(fileKey: String): FigmaAuditResult {
        val violations = mutableListOf<FigmaViolation>()
        violations.addAll(checkCircularFillInHug(fileKey))
        violations.addAll(checkStaticContainerFrames(fileKey))
        violations.addAll(checkDefault100PxFrameHeight(fileKey))
        violations.addAll(checkMinTouchTargets(fileKey))
        violations.addAll(checkNegativeCoordinates(fileKey))
        violations.addAll(checkChildAmputationWidth(fileKey))
        violations.addAll(checkNoEmoji(fileKey))
        violations.addAll(checkUnboundTokens(fileKey))
        violations.addAll(checkAdHocDetachedSpecimens(fileKey))
        violations.addAll(checkCanvasSpatialTopology(fileKey))
        violations.addAll(checkWcagContrast(fileKey))

        val (score, finalViolations) = evaluateGate1Maturity(fileKey, violations)

        var totalNodes = 0
        val countStmt = store.prepare("SELECT COUNT(*) FROM figma_nodes WHERE file_key = ?;")
        try {
            countStmt.bindString(1, fileKey)
            if (countStmt.step()) {
                totalNodes = countStmt.columnInt(0)
            }
        } finally {
            countStmt.close()
        }

        val polishDelta = if (score >= 90.0 || finalViolations.isEmpty()) {
            DEFAULT_POLISH_DELTA
        } else {
            listOf(
                "Resolve ${finalViolations.size} blocking invariant violation(s) to reach 90+ Gate 1 maturity"
            ) + DEFAULT_POLISH_DELTA
        }

        val passed = finalViolations.isEmpty() && score >= 80.0

        return FigmaAuditResult(
            fileKey = fileKey,
            passed = passed,
            maturityScore = score,
            polishDelta = polishDelta,
            violations = finalViolations,
            totalNodes = totalNodes
        )
    }

    /**
     * Audit 1: Detects child frames set to FILL along an axis where the parent is set to HUG.
     * Evaluates both CIRCULAR_V_FILL_IN_HUG and CIRCULAR_H_FILL_IN_HUG.
     */
    fun checkCircularFillInHug(fileKey: String): List<FigmaViolation> {
        val violations = mutableListOf<FigmaViolation>()

        val vStmt = store.prepare(
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

        val hStmt = store.prepare(
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

    /**
     * Audit 2: Flags interactive containers or component variants with layoutMode = 'NONE'.
     */
    fun checkStaticContainerFrames(fileKey: String): List<FigmaViolation> {
        val stmt = store.prepare(
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

    /**
     * Audit 3: Detects frames stuck at default fixed 100px height.
     */
    fun checkDefault100PxFrameHeight(fileKey: String): List<FigmaViolation> {
        val stmt = store.prepare(
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

    /**
     * Audit 4: Flags interactive elements under 48dp lacking a >= 48dp AutoLayout container.
     */
    fun checkMinTouchTargets(fileKey: String): List<FigmaViolation> {
        val stmt = store.prepare(
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

    /**
     * Audit 5: Detects negative local coordinates (local_x < 0 or local_y < 0).
     */
    fun checkNegativeCoordinates(fileKey: String): List<FigmaViolation> {
        val stmt = store.prepare(
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

    /**
     * Audit 6: Detects child element in padded AutoLayout container with fixed width >= parent width.
     */
    fun checkChildAmputationWidth(fileKey: String): List<FigmaViolation> {
        val stmt = store.prepare(
            """
            SELECT c.node_id, c.name, c.width, p.name, p.width
            FROM figma_nodes c
            JOIN figma_nodes p ON c.parent_id = p.node_id AND c.file_key = p.file_key
            WHERE c.file_key = ?
              AND p.layout_mode IN ('HORIZONTAL', 'VERTICAL')
              AND (p.padding_left > 0.0 OR p.padding_right > 0.0)
              AND c.sizing_horizontal = 'FIXED'
              AND c.width >= p.width
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

    /**
     * Audit 7: Enforces zero unicode emojis in UI layouts.
     */
    fun checkNoEmoji(fileKey: String): List<FigmaViolation> {
        val stmt = store.prepare(
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

    /**
     * Audit 8: Enforces that <= 5% of fills and strokes in a document are unbound.
     */
    fun checkUnboundTokens(fileKey: String): List<FigmaViolation> {
        var totalCount = 0
        val countStmt = store.prepare("SELECT COUNT(*) FROM figma_nodes WHERE file_key = ?;")
        try {
            countStmt.bindString(1, fileKey)
            if (countStmt.step()) {
                totalCount = countStmt.columnInt(0)
            }
        } finally {
            countStmt.close()
        }

        if (totalCount == 0) return emptyList()

        var unboundCount = 0
        val unbStmt = store.prepare(
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
        val stmt = store.prepare(
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

    /**
     * Audit 9: Detects detached static frames inside Generative Gym arenas instead of live master instances.
     */
    fun checkAdHocDetachedSpecimens(fileKey: String): List<FigmaViolation> {
        val stmt = store.prepare(
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

    /**
     * Audit 10: Enforces the 2D matrix canvas spatial grid via SQLite R*Tree.
     */
    fun checkCanvasSpatialTopology(fileKey: String): List<FigmaViolation> {
        val stmt = store.prepare(
            """
            SELECT n.node_id, n.name, s.min_x, s.min_y
            FROM figma_nodes n
            JOIN figma_spatial_index s ON n.rowid_pk = s.id
            WHERE n.file_key = ?
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

    /**
     * Audit 11: Evaluates WCAG relative luminance contrast ratios (>= 4.5:1 text, >= 3.0:1 large text).
     */
    fun checkWcagContrast(fileKey: String): List<FigmaViolation> {
        val stmt = store.prepare(
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

    /**
     * Audit 12: Evaluates overall Gate 1 maturity compliance, enforces <= 95 scoring ceiling,
     * and itemizes remaining polish delta.
     */
    fun evaluateGate1Maturity(
        fileKey: String,
        violations: List<FigmaViolation>
    ): Pair<Double, List<FigmaViolation>> {
        var deductions = 0.0
        for (v in violations) {
            deductions += when (v.rule) {
                "CIRCULAR_V_FILL_IN_HUG", "CIRCULAR_H_FILL_IN_HUG" -> 10.0
                "STATIC_CONTAINER_FRAME" -> 8.0
                "DEFAULT_100PX_FRAME_HEIGHT" -> 5.0
                "MIN_TOUCH_TARGET" -> 7.0
                "NEGATIVE_COORDINATES" -> 5.0
                "CHILD_AMPUTATION_WIDTH" -> 7.0
                "NO_EMOJI" -> 5.0
                "UNBOUND_TOKENS" -> 8.0
                "AD_HOC_DETACHED_SPECIMEN" -> 8.0
                "CANVAS_SPATIAL_TOPOLOGY" -> 5.0
                "WCAG_CONTRAST" -> 6.0
                else -> 5.0
            }
        }

        val rawScore = 95.0 - deductions
        val score = max(0.0, min(95.0, rawScore))

        val resultViolations = violations.toMutableList()
        if (score < 80.0) {
            resultViolations.add(
                FigmaViolation(
                    rule = "GATE_1_MATURITY_SCORE",
                    nodeId = fileKey,
                    nodeName = fileKey,
                    message = "Gate 1 maturity score is $score/100 (below required 80.0 passing threshold)"
                )
            )
        }
        return Pair(score, resultViolations)
    }

    /**
     * Synthesizes 3D Concurrency Scope for concurrent multi-agent design tasks:
     * 1. Spatial R*Tree bounding box
     * 2. Component hierarchy & shared master component boundaries
     * 3. Design token variable alias layers
     */
    fun synthesizeFigmaScope(
        project: String,
        fileKey: String,
        seedNodeNames: List<String>
    ): Figma3DScope {
        val seedNodes = mutableListOf<FigmaNode>()
        for (seed in seedNodeNames) {
            val byName = store.queryFigmaNodesByName(fileKey, seed)
            if (byName.isNotEmpty()) {
                seedNodes.addAll(byName)
            } else {
                val byId = store.queryFigmaNodeById(fileKey, seed)
                if (byId != null) {
                    seedNodes.add(byId)
                } else {
                    val stmt = store.prepare("SELECT node_id FROM figma_nodes WHERE file_key = ? AND name LIKE ?;")
                    try {
                        stmt.bindString(1, fileKey)
                        stmt.bindString(2, "%$seed%")
                        while (stmt.step()) {
                            val id = stmt.columnString(0)
                            store.queryFigmaNodeById(fileKey, id)?.let { seedNodes.add(it) }
                        }
                    } finally {
                        stmt.close()
                    }
                }
            }
        }

        val spatialBounds = if (seedNodes.isEmpty()) {
            FigmaSpatialBounds(0.0, 0.0, 0.0, 0.0)
        } else {
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (n in seedNodes) {
                minX = min(minX, n.canvasX)
                minY = min(minY, n.canvasY)
                maxX = max(maxX, n.canvasX + n.width)
                maxY = max(maxY, n.canvasY + n.height)
            }
            FigmaSpatialBounds(minX, maxX, minY, maxY)
        }

        val scopedNodes = if (seedNodes.isEmpty()) emptyList() else store.queryFigmaSpatialIntersection(fileKey, spatialBounds)
        val scopedNodeIds = scopedNodes.map { it.nodeId }.toSet()

        // Dimension 2: Component Hierarchy & Shared Master Components
        val sharedMasterComponents = mutableListOf<String>()
        val potentialCollisions = mutableListOf<String>()

        if (scopedNodeIds.isNotEmpty()) {
            val instStmt = store.prepare(
                """
                SELECT DISTINCT target_node_id
                FROM figma_edges
                WHERE file_key = ? AND edge_type = 'INSTANTIATES';
                """.trimIndent()
            )
            val instantiatedTargets = mutableSetOf<String>()
            try {
                instStmt.bindString(1, fileKey)
                while (instStmt.step()) {
                    instantiatedTargets.add(instStmt.columnString(0))
                }
            } finally {
                instStmt.close()
            }

            for (targetId in instantiatedTargets) {
                var instantiatedInside = false
                val inEdges = store.queryFigmaEdgesByTarget(fileKey, targetId)
                for (edge in inEdges) {
                    if (edge.edgeType == "INSTANTIATES" && edge.sourceNodeId in scopedNodeIds) {
                        instantiatedInside = true
                        break
                    }
                }
                if (!instantiatedInside) continue

                var externalCount = 0
                for (edge in inEdges) {
                    if (edge.edgeType == "INSTANTIATES" && edge.sourceNodeId !in scopedNodeIds) {
                        externalCount++
                    }
                }

                val masterNode = store.queryFigmaNodeById(fileKey, targetId)
                val masterName = masterNode?.name ?: targetId

                if (externalCount > 0) {
                    sharedMasterComponents.add(masterName)
                    potentialCollisions.add(
                        "Master Component '$masterName' ($targetId) is instantiated both inside and outside scope ($externalCount external instance(s))"
                    )
                }
            }
        }

        // Dimension 3: Design Tokens & Variable Boundary
        val tokenLayers = mutableSetOf<String>()
        if (scopedNodeIds.isNotEmpty()) {
            for (nodeId in scopedNodeIds) {
                val edges = store.queryFigmaEdgesBySource(fileKey, nodeId)
                for (e in edges) {
                    if (e.edgeType == "BINDS_VARIABLE") {
                        val varTarget = e.targetNodeId
                        tokenLayers.add(varTarget)

                        val currentVar = store.queryFigmaVariableById(fileKey, varTarget)
                            ?: store.queryFigmaVariableByName(fileKey, varTarget)
                        if (currentVar != null) {
                            tokenLayers.add(currentVar.name)
                            var upstreamId = currentVar.aliasedVariableId
                            var hops = 0
                            while (!upstreamId.isNullOrEmpty() && hops < 10) {
                                hops++
                                val parentVar = store.queryFigmaVariableById(fileKey, upstreamId)
                                    ?: store.queryFigmaVariableByName(fileKey, upstreamId)
                                if (parentVar != null) {
                                    tokenLayers.add(parentVar.name)
                                    tokenLayers.add(parentVar.variableId)
                                    upstreamId = if (parentVar.aliasedVariableId != upstreamId) parentVar.aliasedVariableId else null
                                } else {
                                    tokenLayers.add(upstreamId)
                                    break
                                }
                            }
                        }

                        val impact = store.resolveVariableAliasImpact(fileKey, varTarget, depth = 5)
                        for (v in impact) {
                            tokenLayers.add(v.name)
                        }
                    }
                }
            }
        }

        return Figma3DScope(
            project = project,
            fileKey = fileKey,
            seedNodes = seedNodeNames,
            spatialBounds = spatialBounds,
            scopedNodesCount = scopedNodes.size,
            sharedMasterComponents = sharedMasterComponents.distinct().sorted(),
            tokenLayers = tokenLayers.toList().sorted(),
            potentialCollisions = potentialCollisions
        )
    }
}
