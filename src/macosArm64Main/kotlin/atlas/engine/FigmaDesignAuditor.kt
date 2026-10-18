package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.extract.UrlTargetResolver
import atlas.model.Figma3DScope
import atlas.model.FigmaAuditResult
import atlas.model.FigmaNode
import atlas.model.FigmaSpatialBounds
import atlas.model.FigmaViolation
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
        val rawFileKey = fileKey
        val fileKey = UrlTargetResolver.parseFigmaUrl(rawFileKey)?.fileKey ?: rawFileKey
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
        val totalNodes = store.countFigmaNodes(fileKey)

        if (totalNodes == 0) {
            return FigmaAuditResult(
                fileKey = fileKey,
                passed = false,
                maturityScore = 0.0,
                polishDelta = listOf("Index document snapshot with 'atlas figma index' before auditing"),
                violations = listOf(
                    FigmaViolation(
                        rule = "GATE_1_MATURITY_SCORE",
                        nodeId = fileKey,
                        nodeName = fileKey,
                        message = "Figma document '$fileKey' has 0 indexed nodes in database. File does not exist or has not been indexed."
                    )
                ),
                totalNodes = 0
            )
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
    fun checkCircularFillInHug(fileKey: String): List<FigmaViolation> =
        store.queryCircularFillInHugViolations(fileKey)

    /**
     * Audit 2: Flags interactive containers or component variants with layoutMode = 'NONE'.
     */
    fun checkStaticContainerFrames(fileKey: String): List<FigmaViolation> =
        store.queryStaticContainerFrameViolations(fileKey)

    /**
     * Audit 3: Detects frames stuck at default fixed 100px height.
     */
    fun checkDefault100PxFrameHeight(fileKey: String): List<FigmaViolation> =
        store.queryDefault100PxFrameHeightViolations(fileKey)

    /**
     * Audit 4: Flags interactive elements under 48dp lacking a >= 48dp AutoLayout container.
     */
    fun checkMinTouchTargets(fileKey: String): List<FigmaViolation> =
        store.queryMinTouchTargetViolations(fileKey)

    /**
     * Audit 5: Detects negative local coordinates (local_x < 0 or local_y < 0).
     */
    fun checkNegativeCoordinates(fileKey: String): List<FigmaViolation> =
        store.queryNegativeCoordinateViolations(fileKey)

    /**
     * Audit 6: Detects child element in padded AutoLayout container with fixed width >= parent width.
     */
    fun checkChildAmputationWidth(fileKey: String): List<FigmaViolation> =
        store.queryChildAmputationWidthViolations(fileKey)

    /**
     * Audit 7: Enforces zero unicode emojis in UI layouts.
     */
    fun checkNoEmoji(fileKey: String): List<FigmaViolation> =
        store.queryNoEmojiViolations(fileKey)

    /**
     * Audit 8: Enforces that <= 5% of fills and strokes in a document are unbound.
     */
    fun checkUnboundTokens(fileKey: String): List<FigmaViolation> =
        store.queryUnboundTokenViolations(fileKey)

    /**
     * Audit 9: Detects detached static frames inside Generative Gym arenas instead of live master instances.
     */
    fun checkAdHocDetachedSpecimens(fileKey: String): List<FigmaViolation> =
        store.queryAdHocDetachedSpecimenViolations(fileKey)

    /**
     * Audit 10: Enforces the 2D matrix canvas spatial grid via SQLite R*Tree.
     */
    fun checkCanvasSpatialTopology(fileKey: String): List<FigmaViolation> =
        store.queryCanvasSpatialTopologyViolations(fileKey)

    /**
     * Audit 11: Evaluates WCAG relative luminance contrast ratios (>= 4.5:1 text, >= 3.0:1 large text).
     */
    fun checkWcagContrast(fileKey: String): List<FigmaViolation> =
        store.queryWcagContrastViolations(fileKey)

    /**
     * Audit 12: Evaluates overall Gate 1 maturity compliance, enforces <= 95 scoring ceiling,
     * and itemizes remaining polish delta.
     */
    fun evaluateGate1Maturity(
        fileKey: String,
        violations: List<FigmaViolation>
    ): Pair<Double, List<FigmaViolation>> {
        var deductions = 0.0
        var unboundViolationCount = 0
        for (v in violations) {
            if (v.rule == "UNBOUND_TOKENS") {
                unboundViolationCount++
                continue
            }
            deductions += when (v.rule) {
                "CIRCULAR_V_FILL_IN_HUG", "CIRCULAR_H_FILL_IN_HUG" -> 10.0
                "STATIC_CONTAINER_FRAME" -> 8.0
                "DEFAULT_100PX_FRAME_HEIGHT" -> 5.0
                "MIN_TOUCH_TARGET" -> 7.0
                "NEGATIVE_COORDINATES" -> 5.0
                "CHILD_AMPUTATION_WIDTH" -> 7.0
                "NO_EMOJI" -> 5.0
                "AD_HOC_DETACHED_SPECIMEN" -> 8.0
                "CANVAS_SPATIAL_TOPOLOGY" -> 5.0
                "WCAG_CONTRAST" -> 6.0
                else -> 5.0
            }
        }

        if (unboundViolationCount > 0) {
            val totalNodes = store.countFigmaNodes(fileKey)
            val unboundDeduction = if (totalNodes > 0) {
                val ratio = unboundViolationCount.toDouble() / totalNodes.toDouble()
                val excessPct = ((ratio - 0.05).coerceAtLeast(0.0)) * 100.0
                min(25.0, 2.0 + excessPct)
            } else {
                min(25.0, unboundViolationCount * 2.0)
            }
            deductions += unboundDeduction
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
        val rawFileKey = fileKey
        val parsedFileLink = UrlTargetResolver.parseFigmaUrl(rawFileKey)
        val fileKey = parsedFileLink?.fileKey ?: rawFileKey
        val rawExpanded = seedNodeNames.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (rawExpanded.isEmpty() && parsedFileLink?.nodeId != null) {
            rawExpanded.add(parsedFileLink.nodeId)
        }
        val expandedSeeds = rawExpanded.map { seed ->
            val seedLink = UrlTargetResolver.parseFigmaUrl(seed)
            when {
                seedLink?.nodeId != null -> seedLink.nodeId
                else -> UrlTargetResolver.normalizeFigmaNodeId(seed)
            }
        }
        val seedNodes = mutableListOf<FigmaNode>()
        for (seed in expandedSeeds) {
            val byName = store.queryFigmaNodesByName(fileKey, seed)
            if (byName.isNotEmpty()) {
                seedNodes.addAll(byName)
            } else {
                val byId = store.queryFigmaNodeById(fileKey, seed)
                if (byId != null) {
                    seedNodes.add(byId)
                } else {
                    seedNodes.addAll(store.queryFigmaNodesByNameLike(fileKey, seed))
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

        // Dimension 2: Component Hierarchy & Shared Master Components (Batched)
        val sharedMasterComponents = mutableListOf<String>()
        val potentialCollisions = mutableListOf<String>()

        if (scopedNodeIds.isNotEmpty()) {
            val allInstantiatesEdges = store.queryFigmaEdgesByType(fileKey, "INSTANTIATES")
            val edgesByTarget = allInstantiatesEdges.groupBy { it.targetNodeId }

            for ((targetId, inEdges) in edgesByTarget) {
                var instantiatedInside = false
                var externalCount = 0

                for (edge in inEdges) {
                    if (edge.sourceNodeId in scopedNodeIds) {
                        instantiatedInside = true
                    } else {
                        externalCount++
                    }
                }

                val isMasterInScope = targetId in scopedNodeIds
                if (!instantiatedInside && !isMasterInScope) continue

                val masterNode = store.queryFigmaNodeById(fileKey, targetId)
                val masterName = masterNode?.name ?: targetId

                if (externalCount > 0) {
                    sharedMasterComponents.add(masterName)
                    val msg = if (isMasterInScope && !instantiatedInside) {
                        "Master Component '$masterName' ($targetId) defined in scope has $externalCount external instance(s) outside scope"
                    } else if (isMasterInScope && instantiatedInside) {
                        "Master Component '$masterName' ($targetId) defined in scope is instantiated both inside and outside scope ($externalCount external instance(s))"
                    } else {
                        "Master Component '$masterName' ($targetId) is instantiated inside scope and shared with $externalCount external instance(s)"
                    }
                    potentialCollisions.add(msg)
                }
            }
        }

        // Dimension 3: Design Tokens & Variable Boundary (Batched)
        val tokenLayers = mutableSetOf<String>()
        if (scopedNodeIds.isNotEmpty()) {
            val allBindsEdges = store.queryFigmaEdgesByType(fileKey, "BINDS_VARIABLE")
            val scopedBindsEdges = allBindsEdges.filter { it.sourceNodeId in scopedNodeIds }
            val distinctVarTargets = scopedBindsEdges.map { it.targetNodeId }.distinct()

            for (varTarget in distinctVarTargets) {
                tokenLayers.add(varTarget)

                val currentVar = store.queryFigmaVariableById(fileKey, varTarget)
                    ?: store.queryFigmaVariableByName(fileKey, varTarget)
                if (currentVar != null) {
                    tokenLayers.add(currentVar.name)
                    val visitedAliases = mutableSetOf<String>()
                    var upstreamId = currentVar.aliasedVariableId
                    var hops = 0
                    while (!upstreamId.isNullOrEmpty() && hops < 50 && visitedAliases.add(upstreamId)) {
                        hops++
                        val parentVar = store.queryFigmaVariableById(fileKey, upstreamId)
                            ?: store.queryFigmaVariableByName(fileKey, upstreamId)
                        if (parentVar != null) {
                            tokenLayers.add(parentVar.name)
                            tokenLayers.add(parentVar.variableId)
                            upstreamId = parentVar.aliasedVariableId
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

        return Figma3DScope(
            project = project,
            fileKey = fileKey,
            seedNodes = expandedSeeds,
            spatialBounds = spatialBounds,
            scopedNodesCount = scopedNodes.size,
            sharedMasterComponents = sharedMasterComponents.distinct().sorted(),
            tokenLayers = tokenLayers.toList().sorted(),
            potentialCollisions = potentialCollisions
        )
    }
}
