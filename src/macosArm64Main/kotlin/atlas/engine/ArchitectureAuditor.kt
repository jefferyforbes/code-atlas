package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.extract.ComplexityExtractor
import atlas.extract.PolyglotAstExtractor
import atlas.model.AuditReport
import atlas.model.ComplexityViolation
import atlas.model.ComposeAuditResult
import atlas.model.DescriptionSuggestion
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.model.LayerInversionViolation
import atlas.model.PlanCollision
import atlas.model.SingleDeclarationViolation
import atlas.util.PlatformFiles
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * AST-backed Architecture Auditor enforcing Clean Architecture layers, BOOST-003, and 3D collision safety.
 */
class ArchitectureAuditor(
    private val store: SqliteGraphStore,
    private val workspaceRoot: String = "."
) {

    private val composeEngine = ComposeStabilityEngine(store)

    fun runComposeAudit(projectId: String, strongSkipping: Boolean = true): ComposeAuditResult {
        return composeEngine.auditCompose(projectId, strongSkipping = strongSkipping)
    }

    fun runFullAudit(
        projectId: String,
        pathPrefixes: List<String>? = null,
        descriptionMode: String = "domain-only"
    ): AuditReport {
        val normalizedPrefixes = normalizePathPrefixes(pathPrefixes)
        val singleDeclViolations = checkSingleDeclarationPrinciple(projectId, normalizedPrefixes)
        val layerInversions = checkLayerInversions(projectId, normalizedPrefixes)
        val planCollisions = checkPlan3dCollisions(projectId, normalizedPrefixes)
        val descriptionSuggestions = checkTopLevelDescriptions(projectId, normalizedPrefixes, descriptionMode)
        val complexityViolations = checkComplexityViolations(projectId, pathPrefixes = normalizedPrefixes)

        val passed = singleDeclViolations.isEmpty() && layerInversions.isEmpty() && planCollisions.isEmpty()
        return AuditReport(
            projectId = projectId,
            passed = passed,
            singleDeclarationViolations = singleDeclViolations,
            layerInversions = layerInversions,
            plan3dCollisions = planCollisions,
            descriptionSuggestions = descriptionSuggestions,
            complexityViolations = complexityViolations
        )
    }

    companion object {
        private val COMPLEXITY_CANDIDATE_KINDS = setOf("function", "method", "composable")
        private val DISABLED_DESCRIPTION_MODES = setOf("off", "none", "disabled", "false")
        private val DOC_CANDIDATE_KINDS = setOf(
            "class", "interface", "protocol", "function", "composable",
            "object", "enum", "struct", "actor", "annotation", "expect_decl"
        )
        private val LOWER_TIER_LAYERS = setOf("presentation", "data", "infrastructure")
        private val NON_PRESENTATION_LAYERS = setOf("application", "data", "infrastructure")
        private val TERMINAL_PLAN_STATUSES = setOf("(merged)", "(completed)", "(done)", "(landed)", "(closed)")
        private val CONTAINER_KINDS = setOf("class", "object", "interface", "struct")
        private val DEAD_CODE_ENTRYPOINTS = setOf(
            "main", "init", "run", "invoke", "Companion", "onCreate", "onStart",
            "close", "dispose", "destroy", "shutdown", "stop"
        )
        private val EXCLUDED_DEAD_CODE_KINDS = setOf(
            "module", "markdown_doc", "markdown_note", "plan_contract", "expect_decl", "actual_decl",
            "typealias", "namespace", "variable", "composable", "file_scope", "endpoint", "url_ref", "enum",
            "tag", "skill", "slash_command", "obsidian_note"
        )
    }

    /**
     * Evaluates function complexity thresholds and reports violating functions with severity
     * (WARNING for cognitive 11-15, HIGH for cognitive > 15) and advisory parameter count violations
     * (COMPLEXITY-002-PARAMS, warning at warnParams default 5, error at maxParams default 7;
     * with higher thresholds or exclusion for Compose functions, and excluding data class / primary constructors).
     * Computes the Topological Architectural Risk Index incorporating afferent coupling:
     * Risk Index = Complexity Metric * (1 + ln(1 + inDegree))
     */
    data class ComplexityThresholds(
        val maxCognitive: Int = 10,
        val maxCyclomatic: Int = 15,
        val warnParams: Int = 5,
        val maxParams: Int = 7,
        val composeWarnParams: Int = 10,
        val composeMaxParams: Int = 14,
        val checkParams: Boolean = true
    )

    /**
     * Evaluates function complexity thresholds and reports violating functions with severity
     * (WARNING for cognitive 11-15, HIGH for cognitive > 15) and advisory parameter count violations
     * (COMPLEXITY-002-PARAMS, warning at warnParams default 5, error at maxParams default 7;
     * with higher thresholds or exclusion for Compose functions, and excluding data class / primary constructors).
     * Computes the Topological Architectural Risk Index incorporating afferent coupling:
     * Risk Index = Complexity Metric * (1 + ln(1 + inDegree))
     */
    fun checkComplexityViolations(
        projectId: String,
        maxCognitive: Int = 10,
        maxCyclomatic: Int = 15,
        pathPrefixes: List<String>? = null
    ): List<ComplexityViolation> = checkComplexityViolations(
        projectId,
        pathPrefixes,
        ComplexityThresholds(maxCognitive = maxCognitive, maxCyclomatic = maxCyclomatic)
    )

    fun checkComplexityViolations(
        projectId: String,
        pathPrefixes: List<String>?,
        thresholds: ComplexityThresholds
    ): List<ComplexityViolation> {
        val prefixes = normalizePathPrefixes(pathPrefixes)
        val violations = mutableListOf<ComplexityViolation>()
        val fileLinesCache = mutableMapOf<String, List<String>?>()

        for (node in store.listNodes(projectId)) {
            if (!isComplexityCandidate(node, prefixes)) continue
            checkCognitiveViolation(node, thresholds.maxCognitive, thresholds.maxCyclomatic)?.let {
                violations.add(it)
            }
            checkNodeParamViolation(node, thresholds, fileLinesCache)?.let {
                violations.add(it)
            }
        }

        return sortViolations(violations)
    }

    private fun checkCognitiveViolation(
        node: GraphNode,
        maxCognitive: Int,
        maxCyclomatic: Int
    ): ComplexityViolation? {
        if (node.cognitiveComplexity > maxCognitive || node.cyclomaticComplexity > maxCyclomatic) {
            return buildComplexityViolation(node, maxCognitive, maxCyclomatic)
        }
        return null
    }

    private fun sortViolations(violations: List<ComplexityViolation>): List<ComplexityViolation> {
        return violations.sortedWith(
            compareByDescending<ComplexityViolation> { it.riskIndex }
                .thenByDescending { it.cognitiveComplexity }
                .thenByDescending { it.paramCount }
                .thenBy { it.nodeId }
        )
    }

    /**
     * Checks parameter count complexity violations (COMPLEXITY-002-PARAMS) as an advisory rule.
     * Recommended defaults: warn at 5, error at 7. Higher threshold for Compose functions (warn at 10, error at 14);
     * excludes data classes and primary constructors.
     */
    fun checkParamCountViolations(
        projectId: String,
        pathPrefixes: List<String>? = null,
        thresholds: ComplexityThresholds = ComplexityThresholds()
    ): List<ComplexityViolation> {
        val prefixes = normalizePathPrefixes(pathPrefixes)
        val violations = mutableListOf<ComplexityViolation>()
        val fileLinesCache = mutableMapOf<String, List<String>?>()

        for (node in store.listNodes(projectId)) {
            if (!isComplexityCandidate(node, prefixes)) continue
            checkNodeParamViolation(node, thresholds, fileLinesCache)?.let {
                violations.add(it)
            }
        }

        return violations.sortedWith(
            compareByDescending<ComplexityViolation> { it.riskIndex }
                .thenByDescending { it.paramCount }
                .thenBy { it.nodeId }
        )
    }

    private val CLASS_DECL_PATTERN = Regex("""\b(?:data\s+class|class|value\s+class|struct)\b""")

    private fun isComposableNode(node: GraphNode, sourceLines: List<String>? = null): Boolean {
        if (node.kind == "composable" || node.signature.contains("@Composable")) return true
        if (sourceLines != null && node.startLine > 1) {
            val checkIdx = minOf(node.startLine - 1, sourceLines.size - 1)
            val lookbackStart = maxOf(0, checkIdx - 3)
            for (idx in lookbackStart until checkIdx) {
                if (sourceLines[idx].contains("@Composable")) return true
            }
        }
        return false
    }

    private fun checkNodeParamViolation(
        node: GraphNode,
        thresholds: ComplexityThresholds,
        cache: MutableMap<String, List<String>?>
    ): ComplexityViolation? {
        if (!thresholds.checkParams || isDataClassOrPrimaryConstructor(node)) return null
        val lines = resolveNodeSourceLines(node, cache)
        val isComp = isComposableNode(node, lines)
        val warn = if (isComp) thresholds.composeWarnParams else thresholds.warnParams
        val max = if (isComp) thresholds.composeMaxParams else thresholds.maxParams
        if (warn <= 0) return null
        val count = computeParamCount(node, cache)
        if (count < warn) return null
        return buildParamCountViolation(node, count, warn, max, isComp)
    }

    private fun isDataClassOrPrimaryConstructor(node: GraphNode): Boolean {
        if (node.kind == "class" || node.kind == "struct") return true
        if (node.kind == "primary_constructor") return true
        val sig = node.signature.trim()
        return CLASS_DECL_PATTERN.containsMatchIn(sig)
    }

    private fun resolveCandidatePaths(relPath: String, fileId: String): List<String> {
        val rootPath = if (workspaceRoot.isNotEmpty() && workspaceRoot != ".") "$workspaceRoot/$relPath" else null
        return listOfNotNull(relPath, rootPath, fileId).distinct()
    }

    private fun tryReadFileLines(path: String): List<String>? {
        if (!PlatformFiles.exists(path)) return null
        return try {
            PlatformFiles.readText(path).lines()
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveNodeSourceLines(
        node: GraphNode,
        cache: MutableMap<String, List<String>?>
    ): List<String>? {
        val relPath = node.fileId.substringAfter(':')
        if (cache.containsKey(relPath)) return cache[relPath]

        for (p in resolveCandidatePaths(relPath, node.fileId)) {
            val lines = tryReadFileLines(p)
            if (lines != null) {
                cache[relPath] = lines
                return lines
            }
        }
        cache[relPath] = null
        return null
    }

    fun computeParamCount(
        node: GraphNode,
        fileLinesCache: MutableMap<String, List<String>?> = mutableMapOf()
    ): Int {
        if (isDataClassOrPrimaryConstructor(node)) return 0

        val ext = if (node.fileId.contains('.')) "." + node.fileId.substringAfterLast('.') else ""
        val language = PolyglotAstExtractor.supportedLanguage(ext).ifEmpty { "kotlin" }

        val lines = resolveNodeSourceLines(node, fileLinesCache)
        if (lines != null && node.startLine > 0 && node.startLine <= lines.size) {
            val startIdx = (node.startLine - 1).coerceAtLeast(0)
            val endIdx = minOf(node.endLine, lines.size)
            if (startIdx < endIdx) {
                val nodeLines = lines.subList(startIdx, endIdx)
                val count = ComplexityExtractor.extractParamCount(nodeLines, node.name, language)
                return count
            }
        }

        if (node.signature.isNotBlank()) {
            return ComplexityExtractor.extractParamCount(listOf(node.signature), node.name, language)
        }

        return 0
    }

    private fun buildParamCountViolation(
        node: GraphNode,
        paramCount: Int,
        warnThreshold: Int,
        errorThreshold: Int,
        isComp: Boolean = isComposableNode(node)
    ): ComplexityViolation {
        val severity = if (paramCount >= errorThreshold) "HIGH" else "WARNING"
        val lineSpan = if (node.endLine > node.startLine) {
            "L${node.startLine}-L${node.endLine}"
        } else {
            "L${node.startLine}"
        }
        val inDeg = node.inDegree.toDouble()
        val riskIndex = paramCount.toDouble() * (1.0 + ln(1.0 + inDeg))
        val roundedRisk = (riskIndex * 10.0).roundToInt() / 10.0
        val kindLabel = if (isComp) "Composable" else "Function"
        val thresholdVal = if (severity == "HIGH") errorThreshold else warnThreshold
        val levelLabel = if (severity == "HIGH") "error" else "warning"
        val message = "$kindLabel '${node.name}' has $paramCount parameters, exceeding $levelLabel threshold ($thresholdVal)."

        return ComplexityViolation(
            ruleId = "COMPLEXITY-002-PARAMS",
            severity = severity,
            message = message,
            nodeId = node.nodeId,
            lineSpan = lineSpan,
            riskIndex = roundedRisk,
            cognitiveComplexity = node.cognitiveComplexity,
            cyclomaticComplexity = node.cyclomaticComplexity,
            paramCount = paramCount
        )
    }

    private fun isComplexityCandidate(node: GraphNode, prefixes: List<String>): Boolean {
        if (node.kind !in COMPLEXITY_CANDIDATE_KINDS) return false
        if (node.semioticLayer == "test" || node.archLayer == "test" || node.isTestDouble) return false
        val relPath = node.fileId.substringAfter(':')
        if (relPath.endsWith("__init__.py") || relPath.endsWith(".md")) return false
        return matchesAnyPrefix(relPath, prefixes)
    }

    private fun buildComplexityViolation(
        node: GraphNode,
        maxCognitive: Int,
        maxCyclomatic: Int
    ): ComplexityViolation {
        val severity = if (node.cognitiveComplexity > 15) "HIGH" else "WARNING"
        val lineSpan = if (node.endLine > node.startLine) {
            "L${node.startLine}-L${node.endLine}"
        } else {
            "L${node.startLine}"
        }
        val inDeg = node.inDegree.toDouble()
        val riskIndex = node.cognitiveComplexity.toDouble() * (1.0 + ln(1.0 + inDeg))
        val roundedRisk = (riskIndex * 10.0).roundToInt() / 10.0
        val message = "Function '${node.name}' exceeds complexity thresholds (Cognitive: ${node.cognitiveComplexity}/$maxCognitive, Cyclomatic: ${node.cyclomaticComplexity}/$maxCyclomatic, Risk: $roundedRisk)."
        return ComplexityViolation(
            ruleId = "COMPLEXITY-001",
            severity = severity,
            message = message,
            nodeId = node.nodeId,
            lineSpan = lineSpan,
            riskIndex = roundedRisk,
            cognitiveComplexity = node.cognitiveComplexity,
            cyclomaticComplexity = node.cyclomaticComplexity
        )
    }

    /**
     * Checks for top-level classes and functions missing doc descriptions.
     * Supported modes:
     * - "domain-only" (default): only flags domain layer symbols
     * - "all": flags all production symbols across layers (excluding tests)
     * - "off" / "none" / "disabled": disables the check and returns empty list
     */
    fun checkTopLevelDescriptions(
        projectId: String,
        pathPrefixes: List<String> = emptyList(),
        mode: String = "domain-only"
    ): List<DescriptionSuggestion> {
        val normalizedMode = mode.trim().lowercase()
        if (normalizedMode in DISABLED_DESCRIPTION_MODES) {
            return emptyList()
        }

        val prefixes = normalizePathPrefixes(pathPrefixes)
        val suggestions = mutableListOf<DescriptionSuggestion>()

        for (node in store.listNodes(projectId)) {
            val relPath = node.fileId.substringAfter(':')
            if (!isDescriptionCandidate(node, relPath, prefixes, normalizedMode)) continue
            if (node.description.isNullOrBlank()) {
                suggestions.add(buildDescriptionSuggestion(node, relPath))
            }
        }

        return suggestions.sortedWith(compareBy({ it.relPath }, { it.lineNumber }))
    }

    private fun isDescriptionCandidate(
        node: GraphNode,
        relPath: String,
        prefixes: List<String>,
        mode: String
    ): Boolean {
        if (!node.isTopLevel) return false
        if (node.kind !in DOC_CANDIDATE_KINDS) return false
        if (node.semioticLayer == "test" || node.archLayer == "test" || node.isTestDouble) return false
        if (relPath.endsWith("__init__.py") || relPath.endsWith(".md")) return false
        if (!matchesAnyPrefix(relPath, prefixes)) return false
        return if (mode == "all") true else node.archLayer == "domain"
    }

    private fun formatKindDescription(kind: String): String = when (kind) {
        "class" -> "class"
        "interface", "protocol" -> "interface"
        "function", "composable" -> "function"
        "object" -> "object"
        "struct" -> "struct"
        "enum" -> "enum"
        "actor" -> "actor"
        "annotation" -> "annotation class"
        "expect_decl" -> "expect declaration"
        else -> kind
    }

    private fun buildDescriptionSuggestion(node: GraphNode, relPath: String): DescriptionSuggestion {
        val kindDesc = formatKindDescription(node.kind)
        return DescriptionSuggestion(
            rule = "DOC-TOP-LEVEL",
            nodeId = node.nodeId,
            name = node.name,
            kind = node.kind,
            relPath = relPath,
            lineNumber = node.startLine,
            semioticLayer = node.semioticLayer,
            archLayer = node.archLayer,
            message = "Top-level $kindDesc '${node.name}' in layer '${node.archLayer}' is missing a doc description."
        )
    }

    private fun normalizePathPrefixes(pathPrefixes: List<String>?): List<String> {
        if (pathPrefixes == null) return emptyList()
        return pathPrefixes.mapNotNull { raw ->
            val cleaned = raw.replace('\\', '/').trim().removePrefix("./").trimEnd('/')
            if (cleaned.isNotEmpty()) cleaned else null
        }
    }

    private fun matchesAnyPrefix(relPath: String, prefixes: List<String>): Boolean {
        if (prefixes.isEmpty()) return true
        val norm = relPath.replace('\\', '/').removePrefix("./")
        return prefixes.any { p -> norm == p || norm.startsWith("$p/") }
    }

    fun checkSingleDeclarationPrinciple(
        projectId: String,
        pathPrefixes: List<String> = emptyList()
    ): List<SingleDeclarationViolation> {
        val prefixes = normalizePathPrefixes(pathPrefixes)
        val violations = mutableListOf<SingleDeclarationViolation>()
        for (fileRec in store.listFiles(projectId)) {
            val relPath = fileRec.relPath
            if (!matchesAnyPrefix(relPath, prefixes)) continue
            val semiotic = fileRec.semioticLayer
            val declCount = fileRec.topLevelDeclCount
            if (semiotic == "test" || relPath.endsWith("__init__.py")) {
                continue
            }
            if (declCount > 1) {
                violations.add(
                    SingleDeclarationViolation(
                        rule = "BOOST-003",
                        relPath = relPath,
                        topLevelDeclCount = declCount,
                        message = "$relPath declares $declCount top-level classes/functions (max 1 allowed per Single Declaration Principle)."
                    )
                )
            }
        }
        return violations
    }

    fun checkLayerInversions(
        projectId: String,
        pathPrefixes: List<String> = emptyList()
    ): List<LayerInversionViolation> {
        val prefixes = normalizePathPrefixes(pathPrefixes)
        val nodesById = store.listNodes(projectId).associateBy { it.nodeId }
        val inversions = mutableListOf<LayerInversionViolation>()
        val edges = store.listEdges(
            projectId,
            edgeTypes = listOf("IMPORTS", "CALLS", "INHERITS", "IMPLEMENTS")
        )
        for (edge in edges) {
            val src = nodesById[edge.sourceNodeId] ?: continue
            val tgt = nodesById[edge.targetNodeId] ?: continue
            val violation = detectLayerInversionViolation(src, tgt, edge, prefixes)
            if (violation != null) {
                inversions.add(violation)
            }
        }
        return inversions
    }

    private fun detectLayerInversionViolation(
        src: GraphNode,
        tgt: GraphNode,
        edge: GraphEdge,
        prefixes: List<String>
    ): LayerInversionViolation? {
        if (src.semioticLayer == "test" || tgt.semioticLayer == "test") return null
        val srcRelPath = src.fileId.substringAfter(':')
        if (!matchesAnyPrefix(srcRelPath, prefixes)) return null

        if (src.archLayer == "domain" && tgt.archLayer in LOWER_TIER_LAYERS) {
            val layerDesc = if (tgt.archLayer == "presentation") "Presentation/Pragmatic" else "Data/Infrastructure"
            return LayerInversionViolation(
                rule = "ARCH-LAYER-INVERSION",
                source = src.qualifiedName,
                target = tgt.qualifiedName,
                edgeType = edge.edgeType,
                lineNumber = edge.lineNumber,
                message = "Domain symbol '${src.name}' (${src.archLayer}) illegally references $layerDesc symbol '${tgt.name}' (${tgt.archLayer})."
            )
        }
        if (src.archLayer in NON_PRESENTATION_LAYERS && tgt.archLayer == "presentation") {
            return LayerInversionViolation(
                rule = "ARCH-LAYER-INVERSION",
                source = src.qualifiedName,
                target = tgt.qualifiedName,
                edgeType = edge.edgeType,
                lineNumber = edge.lineNumber,
                message = "Symbol '${src.name}' (${src.archLayer}) illegally references Presentation/Pragmatic symbol '${tgt.name}' (${tgt.archLayer})."
            )
        }
        return null
    }

    fun checkPlan3dCollisions(
        projectId: String,
        pathPrefixes: List<String> = emptyList()
    ): List<PlanCollision> {
        val prefixes = normalizePathPrefixes(pathPrefixes)
        val planEdges = store.listEdges(projectId, edgeTypes = listOf("PLAN_SCOPES_FILE"))
        val nodesById = store.listNodes(projectId).associateBy { it.nodeId }

        val planToFiles = mutableMapOf<String, MutableSet<String>>()
        val planToModules = mutableMapOf<String, MutableSet<String>>()
        populatePlanScopes(planEdges, nodesById, prefixes, planToFiles, planToModules)

        return detectCollisionsBetweenPlans(planToFiles, planToModules)
    }

    private fun populatePlanScopes(
        planEdges: List<GraphEdge>,
        nodesById: Map<String, GraphNode>,
        prefixes: List<String>,
        planToFiles: MutableMap<String, MutableSet<String>>,
        planToModules: MutableMap<String, MutableSet<String>>
    ) {
        for (edge in planEdges) {
            val planNode = nodesById[edge.sourceNodeId] ?: continue
            val targetNode = nodesById[edge.targetNodeId] ?: continue
            if (TERMINAL_PLAN_STATUSES.any { planNode.signature.contains(it) }) continue
            val relFile = targetNode.fileId.substringAfter(':')
            if (!matchesAnyPrefix(relFile, prefixes)) continue
            planToFiles.getOrPut(planNode.name) { mutableSetOf() }.add(relFile)
            if (targetNode.moduleId.isNotEmpty()) {
                planToModules.getOrPut(planNode.name) { mutableSetOf() }.add(targetNode.moduleId)
            }
        }
    }

    private fun detectCollisionsBetweenPlans(
        planToFiles: Map<String, Set<String>>,
        planToModules: Map<String, Set<String>>
    ): List<PlanCollision> {
        val collisions = mutableListOf<PlanCollision>()
        val planIds = planToFiles.keys.sorted()
        for (i in planIds.indices) {
            for (j in (i + 1) until planIds.size) {
                checkPlanPairCollision(planIds[i], planIds[j], planToFiles, planToModules)?.let {
                    collisions.add(it)
                }
            }
        }
        return collisions
    }

    private fun checkPlanPairCollision(
        planA: String,
        planB: String,
        planToFiles: Map<String, Set<String>>,
        planToModules: Map<String, Set<String>>
    ): PlanCollision? {
        val sharedFiles = ((planToFiles[planA] ?: emptySet()) intersect (planToFiles[planB] ?: emptySet())).sorted()
        val sharedMods = ((planToModules[planA] ?: emptySet()) intersect (planToModules[planB] ?: emptySet())).sorted()
        if (sharedFiles.isEmpty() && sharedMods.isEmpty()) return null
        return PlanCollision(
            planA = planA,
            planB = planB,
            sharedFiles = sharedFiles,
            sharedModules = sharedMods
        )
    }

    /**
     * Detects potentially dead production symbols (`inDegree == 0`) excluding tests, test doubles,
     * module/doc/plan/variable/typealias/namespace nodes, and standard entrypoints (`main`, `init`, etc.).
     */
    fun detectDeadCode(projectId: String, pathPrefixes: List<String>? = null): List<GraphNode> {
        val prefixes = normalizePathPrefixes(pathPrefixes)
        val allNodes = store.listNodes(projectId)
        val activeContainers = findContainersWithActiveChildren(allNodes)

        return allNodes
            .filter { isPotentiallyDeadSymbol(it, prefixes, activeContainers) }
            .sortedWith(compareBy({ it.fileId }, { it.startLine }))
    }

    private fun findContainersWithActiveChildren(allNodes: List<GraphNode>): Set<String> {
        val containersWithActiveChildren = mutableSetOf<String>()
        for ((_, fileNodes) in allNodes.groupBy { it.fileId }) {
            val containers = fileNodes.filter { it.kind in CONTAINER_KINDS }
            val activeChildren = fileNodes.filter { it.inDegree > 0 }
            for (container in containers) {
                if (hasActiveChild(container, activeChildren)) {
                    containersWithActiveChildren.add(container.nodeId)
                }
            }
        }
        return containersWithActiveChildren
    }

    private fun hasActiveChild(container: GraphNode, activeChildren: List<GraphNode>): Boolean {
        return activeChildren.any {
            it.nodeId != container.nodeId && it.startLine >= container.startLine && it.endLine <= container.endLine
        }
    }

    private fun isExcludedByNameOrKind(node: GraphNode): Boolean {
        if (node.kind in EXCLUDED_DEAD_CODE_KINDS) return true
        if (node.name in DEAD_CODE_ENTRYPOINTS) return true
        if (node.name.endsWith("Test")) return true
        if (node.name.endsWith("Cli")) return true
        if (node.name.endsWith("Server")) return true
        return false
    }

    private fun isExcludedBySignature(node: GraphNode): Boolean {
        if (node.signature.contains("override ")) return true
        if (node.signature.contains("@Preview")) return true
        if (node.signature.contains("@Test")) return true
        return false
    }

    private fun isPotentiallyDeadSymbol(
        node: GraphNode,
        prefixes: List<String>,
        activeContainers: Set<String>
    ): Boolean {
        if (node.inDegree != 0) return false
        if (node.nodeId in activeContainers) return false
        if (node.semioticLayer == "test") return false
        if (node.archLayer == "test") return false
        if (node.isTestDouble) return false
        val relFile = node.fileId.substringAfter(':')
        if (relFile.endsWith(".md")) return false
        if (!matchesAnyPrefix(relFile, prefixes)) return false
        if (isExcludedByNameOrKind(node)) return false
        if (isExcludedBySignature(node)) return false
        return true
    }
}

