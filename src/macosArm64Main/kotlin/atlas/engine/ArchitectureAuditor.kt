package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.model.AuditReport
import atlas.model.ComplexityViolation
import atlas.model.ComposeAuditResult
import atlas.model.DescriptionSuggestion
import atlas.model.GraphNode
import atlas.model.LayerInversionViolation
import atlas.model.PlanCollision
import atlas.model.SingleDeclarationViolation
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * AST-backed Architecture Auditor enforcing Clean Architecture layers, BOOST-003, and 3D collision safety.
 */
class ArchitectureAuditor(private val store: SqliteGraphStore) {

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

    /**
     * Evaluates function complexity thresholds and reports violating functions with severity
     * (WARNING for cognitive 11-15, HIGH for cognitive > 15).
     * Computes the Topological Architectural Risk Index incorporating afferent coupling:
     * Risk Index = Cognitive Complexity * (1 + ln(1 + inDegree))
     */
    fun checkComplexityViolations(
        projectId: String,
        maxCognitive: Int = 10,
        maxCyclomatic: Int = 15,
        pathPrefixes: List<String>? = null
    ): List<ComplexityViolation> {
        val prefixes = normalizePathPrefixes(pathPrefixes)
        val candidateKinds = setOf("function", "method", "composable")
        val violations = mutableListOf<ComplexityViolation>()

        for (node in store.listNodes(projectId)) {
            if (node.kind !in candidateKinds) continue
            if (node.semioticLayer == "test" || node.archLayer == "test" || node.isTestDouble) continue
            val relPath = node.fileId.substringAfter(':')
            if (relPath.endsWith("__init__.py") || relPath.endsWith(".md")) continue
            if (!matchesAnyPrefix(relPath, prefixes)) continue

            if (node.cognitiveComplexity > maxCognitive || node.cyclomaticComplexity > maxCyclomatic) {
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
                violations.add(
                    ComplexityViolation(
                        ruleId = "COMPLEXITY-001",
                        severity = severity,
                        message = message,
                        nodeId = node.nodeId,
                        lineSpan = lineSpan,
                        riskIndex = roundedRisk,
                        cognitiveComplexity = node.cognitiveComplexity,
                        cyclomaticComplexity = node.cyclomaticComplexity
                    )
                )
            }
        }

        return violations.sortedWith(
            compareByDescending<ComplexityViolation> { it.riskIndex }
                .thenByDescending { it.cognitiveComplexity }
                .thenBy { it.nodeId }
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
        if (normalizedMode in setOf("off", "none", "disabled", "false")) {
            return emptyList()
        }

        val prefixes = normalizePathPrefixes(pathPrefixes)
        val candidateKinds = setOf(
            "class", "interface", "protocol", "function", "composable",
            "object", "enum", "struct", "actor", "annotation", "expect_decl"
        )
        val suggestions = mutableListOf<DescriptionSuggestion>()

        for (node in store.listNodes(projectId)) {
            if (!node.isTopLevel) continue
            if (node.kind !in candidateKinds) continue
            // Ignore tests and test doubles
            if (node.semioticLayer == "test" || node.archLayer == "test" || node.isTestDouble) continue
            val relPath = node.fileId.substringAfter(':')
            if (relPath.endsWith("__init__.py") || relPath.endsWith(".md")) continue
            if (!matchesAnyPrefix(relPath, prefixes)) continue

            val inScope = when (normalizedMode) {
                "all" -> true
                "domain-only", "domain" -> node.archLayer == "domain"
                else -> node.archLayer == "domain"
            }
            if (!inScope) continue

            if (node.description.isNullOrBlank()) {
                val kindDesc = when (node.kind) {
                    "class" -> "class"
                    "interface", "protocol" -> "interface"
                    "function", "composable" -> "function"
                    "object" -> "object"
                    "struct" -> "struct"
                    "enum" -> "enum"
                    "actor" -> "actor"
                    "annotation" -> "annotation class"
                    "expect_decl" -> "expect declaration"
                    else -> node.kind
                }
                suggestions.add(
                    DescriptionSuggestion(
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
                )
            }
        }

        return suggestions.sortedWith(compareBy({ it.relPath }, { it.lineNumber }))
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
            val src = nodesById[edge.sourceNodeId]
            val tgt = nodesById[edge.targetNodeId]
            if (src == null || tgt == null) continue
            if (src.semioticLayer == "test" || tgt.semioticLayer == "test") continue
            val srcRelPath = src.fileId.substringAfter(':')
            if (!matchesAnyPrefix(srcRelPath, prefixes)) continue

            if (src.archLayer == "domain" && tgt.archLayer in setOf("presentation", "data", "infrastructure")) {
                val layerDesc = if (tgt.archLayer == "presentation") "Presentation/Pragmatic" else "Data/Infrastructure"
                inversions.add(
                    LayerInversionViolation(
                        rule = "ARCH-LAYER-INVERSION",
                        source = src.qualifiedName,
                        target = tgt.qualifiedName,
                        edgeType = edge.edgeType,
                        lineNumber = edge.lineNumber,
                        message = "Domain symbol '${src.name}' (${src.archLayer}) illegally references $layerDesc symbol '${tgt.name}' (${tgt.archLayer})."
                    )
                )
            } else if (src.archLayer in setOf("application", "data", "infrastructure") && tgt.archLayer == "presentation") {
                inversions.add(
                    LayerInversionViolation(
                        rule = "ARCH-LAYER-INVERSION",
                        source = src.qualifiedName,
                        target = tgt.qualifiedName,
                        edgeType = edge.edgeType,
                        lineNumber = edge.lineNumber,
                        message = "Symbol '${src.name}' (${src.archLayer}) illegally references Presentation/Pragmatic symbol '${tgt.name}' (${tgt.archLayer})."
                    )
                )
            }
        }
        return inversions
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

        val terminalPlanStatuses = setOf("(merged)", "(completed)", "(done)", "(landed)", "(closed)")
        for (edge in planEdges) {
            val planNode = nodesById[edge.sourceNodeId]
            val targetNode = nodesById[edge.targetNodeId]
            if (planNode == null || targetNode == null) continue
            if (terminalPlanStatuses.any { planNode.signature.contains(it) }) {
                continue
            }
            val planId = planNode.name
            val relFile = targetNode.fileId.substringAfter(':')
            if (!matchesAnyPrefix(relFile, prefixes)) continue
            planToFiles.getOrPut(planId) { mutableSetOf() }.add(relFile)
            if (targetNode.moduleId.isNotEmpty()) {
                planToModules.getOrPut(planId) { mutableSetOf() }.add(targetNode.moduleId)
            }
        }

        val collisions = mutableListOf<PlanCollision>()
        val planIds = planToFiles.keys.sorted()
        for (i in planIds.indices) {
            val planA = planIds[i]
            for (j in (i + 1) until planIds.size) {
                val planB = planIds[j]
                val filesA = planToFiles[planA] ?: emptySet()
                val filesB = planToFiles[planB] ?: emptySet()
                val sharedFiles = (filesA intersect filesB).sorted()

                val modsA = planToModules[planA] ?: emptySet()
                val modsB = planToModules[planB] ?: emptySet()
                val sharedMods = (modsA intersect modsB).sorted()

                if (sharedFiles.isNotEmpty() || sharedMods.isNotEmpty()) {
                    collisions.add(
                        PlanCollision(
                            planA = planA,
                            planB = planB,
                            sharedFiles = sharedFiles,
                            sharedModules = sharedMods
                        )
                    )
                }
            }
        }
        return collisions
    }

    /**
     * Detects potentially dead production symbols (`inDegree == 0`) excluding tests, test doubles,
     * module/doc/plan/variable/typealias/namespace nodes, and standard entrypoints (`main`, `init`, etc.).
     */
    fun detectDeadCode(projectId: String, pathPrefixes: List<String>? = null): List<GraphNode> {
        val prefixes = normalizePathPrefixes(pathPrefixes)
        val entrypointNames = setOf("main", "init", "run", "invoke", "Companion", "onCreate", "onStart")
        val excludedKinds = setOf(
            "module", "markdown_doc", "markdown_note", "plan_contract", "expect_decl", "actual_decl",
            "typealias", "namespace", "variable", "composable", "file_scope", "endpoint", "url_ref"
        )
        return store.listNodes(projectId)
            .filter { node ->
                val relFile = node.fileId.substringAfter(':')
                matchesAnyPrefix(relFile, prefixes) &&
                    node.inDegree == 0 &&
                    node.semioticLayer != "test" &&
                    node.archLayer != "test" &&
                    !node.isTestDouble &&
                    !node.fileId.endsWith(".md") &&
                    node.kind !in excludedKinds &&
                    node.name !in entrypointNames &&
                    !node.name.endsWith("Test") &&
                    !node.name.endsWith("Cli") &&
                    !node.name.endsWith("Server") &&
                    !node.signature.contains("override ") &&
                    !node.signature.contains("@Preview") &&
                    !node.signature.contains("@Test")
            }
            .sortedWith(compareBy({ it.fileId }, { it.startLine }))
    }
}

