package atlas.rule

import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.model.GraphReader

/**
 * Declarative, polyglot architecture rule suite for enforcing architectural boundaries,
 * layer dependencies, and code health invariants across any codebase indexed by Code Atlas.
 */
class ArchitectureRules private constructor(
    private val layers: List<LayerDefinition>,
    private val forbiddenDependencies: List<ForbiddenDependencyRule>,
    private val allowedDependencies: List<AllowedDependencyRule>,
    private val nodeRules: List<NodePredicateRule>,
    private val edgeRules: List<EdgePredicateRule>
) {

    enum class Severity {
        Error,
        Warning,
        Info
    }

    data class RuleViolation(
        val ruleId: String,
        val severity: Severity,
        val message: String,
        val nodeId: String? = null,
        val filePath: String? = null,
        val lineNumber: Int = 1
    )

    data class EvaluationReport(
        val passed: Boolean,
        val violations: List<RuleViolation>,
        val rulesChecked: Int
    )

    private data class LayerDefinition(
        val name: String,
        val predicate: (GraphNode) -> Boolean
    )

    private data class ForbiddenDependencyRule(
        val fromLayer: String,
        val toLayer: String,
        val reason: String?
    )

    private data class AllowedDependencyRule(
        val fromLayer: String,
        val allowedLayers: Set<String>,
        val reason: String?
    )

    private data class NodePredicateRule(
        val name: String,
        val severity: Severity,
        val predicate: (GraphNode) -> Boolean,
        val messageBuilder: ((GraphNode) -> String)?
    )

    private data class EdgePredicateRule(
        val name: String,
        val severity: Severity,
        val predicate: (GraphEdge, GraphNode?, GraphNode?) -> Boolean,
        val messageBuilder: ((GraphEdge, GraphNode?, GraphNode?) -> String)?
    )

    class Builder {
        private val layers = mutableListOf<LayerDefinition>()
        private val forbidden = mutableListOf<ForbiddenDependencyRule>()
        private val allowed = mutableListOf<AllowedDependencyRule>()
        private val nodeRules = mutableListOf<NodePredicateRule>()
        private val edgeRules = mutableListOf<EdgePredicateRule>()

        fun layer(name: String, predicate: (GraphNode) -> Boolean): Builder {
            layers.add(LayerDefinition(name, predicate))
            return this
        }

        fun layer(name: String, pathSubstring: String): Builder {
            return layer(name) { it.fileId.contains(pathSubstring) }
        }

        infix fun String.shouldNotDependOn(targetLayer: String) {
            forbidden.add(ForbiddenDependencyRule(this, targetLayer, null))
        }

        infix fun String.mayOnlyDependOn(allowedLayers: Set<String>) {
            allowed.add(AllowedDependencyRule(this, allowedLayers, null))
        }

        fun forbidDependency(fromLayer: String, toLayer: String, reason: String? = null): Builder {
            forbidden.add(ForbiddenDependencyRule(fromLayer, toLayer, reason))
            return this
        }

        fun nodeRule(
            name: String,
            severity: Severity = Severity.Error,
            message: ((GraphNode) -> String)? = null,
            predicate: (GraphNode) -> Boolean
        ): Builder {
            nodeRules.add(NodePredicateRule(name, severity, predicate, message))
            return this
        }

        fun edgeRule(
            name: String,
            severity: Severity = Severity.Error,
            message: ((GraphEdge, GraphNode?, GraphNode?) -> String)? = null,
            predicate: (GraphEdge, GraphNode?, GraphNode?) -> Boolean
        ): Builder {
            edgeRules.add(EdgePredicateRule(name, severity, predicate, message))
            return this
        }

        fun build(): ArchitectureRules = ArchitectureRules(
            layers = layers.toList(),
            forbiddenDependencies = forbidden.toList(),
            allowedDependencies = allowed.toList(),
            nodeRules = nodeRules.toList(),
            edgeRules = edgeRules.toList()
        )
    }

    companion object {
        fun define(block: Builder.() -> Unit): ArchitectureRules {
            val builder = Builder()
            builder.block()
            return builder.build()
        }
    }

    /**
     * Evaluate all declared architectural rules against the given graph store.
     */
    fun evaluate(store: GraphReader, projectSlug: String): EvaluationReport {
        val violations = mutableListOf<RuleViolation>()
        val allNodes = store.listNodes(projectSlug)
        val allEdges = store.listEdges(projectSlug)
        val nodeMap = allNodes.associateBy { it.nodeId }
        val nodeToLayers = buildNodeToLayersMap(allNodes)

        var totalRulesChecked = 0
        totalRulesChecked += evaluateForbiddenRules(allEdges, nodeToLayers, nodeMap, violations)
        totalRulesChecked += evaluateAllowedRules(allEdges, nodeToLayers, nodeMap, violations)
        totalRulesChecked += evaluateNodeRules(allNodes, violations)
        totalRulesChecked += evaluateEdgeRules(allEdges, nodeMap, violations)

        val hasErrors = violations.any { it.severity == Severity.Error }
        return EvaluationReport(
            passed = !hasErrors,
            violations = violations,
            rulesChecked = totalRulesChecked
        )
    }

    private fun buildNodeToLayersMap(allNodes: List<GraphNode>): Map<String, Set<String>> {
        val nodeToLayers = mutableMapOf<String, MutableSet<String>>()
        for (node in allNodes) {
            for (layerDef in layers) {
                if (layerDef.predicate(node)) {
                    nodeToLayers.getOrPut(node.nodeId) { mutableSetOf() }.add(layerDef.name)
                }
            }
        }
        return nodeToLayers
    }

    private fun evaluateForbiddenRules(
        allEdges: List<GraphEdge>,
        nodeToLayers: Map<String, Set<String>>,
        nodeMap: Map<String, GraphNode>,
        violations: MutableList<RuleViolation>
    ): Int {
        for (fRule in forbiddenDependencies) {
            checkForbiddenRule(fRule, allEdges, nodeToLayers, nodeMap, violations)
        }
        return forbiddenDependencies.size
    }

    private fun checkForbiddenRule(
        fRule: ForbiddenDependencyRule,
        allEdges: List<GraphEdge>,
        nodeToLayers: Map<String, Set<String>>,
        nodeMap: Map<String, GraphNode>,
        violations: MutableList<RuleViolation>
    ) {
        for (edge in allEdges) {
            val srcLayers = nodeToLayers[edge.sourceNodeId] ?: emptySet()
            val tgtLayers = nodeToLayers[edge.targetNodeId] ?: emptySet()

            if (fRule.fromLayer in srcLayers && fRule.toLayer in tgtLayers) {
                val srcNode = nodeMap[edge.sourceNodeId]
                val tgtNode = nodeMap[edge.targetNodeId]
                val srcName = srcNode?.name ?: edge.sourceNodeId
                val tgtName = tgtNode?.name ?: edge.targetNodeId
                val reasonSuffix = if (fRule.reason != null) ": ${fRule.reason}" else ""
                violations.add(
                    RuleViolation(
                        ruleId = "ARCH-FORBIDDEN-DEPENDENCY",
                        severity = Severity.Error,
                        message = "Layer '${fRule.fromLayer}' ($srcName) must not depend on layer '${fRule.toLayer}' ($tgtName)$reasonSuffix",
                        nodeId = edge.sourceNodeId,
                        filePath = srcNode?.fileId,
                        lineNumber = edge.lineNumber
                    )
                )
            }
        }
    }

    private fun evaluateAllowedRules(
        allEdges: List<GraphEdge>,
        nodeToLayers: Map<String, Set<String>>,
        nodeMap: Map<String, GraphNode>,
        violations: MutableList<RuleViolation>
    ): Int {
        for (aRule in allowedDependencies) {
            checkAllowedRule(aRule, allEdges, nodeToLayers, nodeMap, violations)
        }
        return allowedDependencies.size
    }

    private fun checkAllowedRule(
        aRule: AllowedDependencyRule,
        allEdges: List<GraphEdge>,
        nodeToLayers: Map<String, Set<String>>,
        nodeMap: Map<String, GraphNode>,
        violations: MutableList<RuleViolation>
    ) {
        for (edge in allEdges) {
            val srcLayers = nodeToLayers[edge.sourceNodeId] ?: emptySet()
            if (aRule.fromLayer !in srcLayers) continue

            val tgtLayers = nodeToLayers[edge.targetNodeId] ?: emptySet()
            if (tgtLayers.isEmpty()) continue

            val isDisallowed = tgtLayers.any { it !in aRule.allowedLayers }
            if (isDisallowed) {
                val srcNode = nodeMap[edge.sourceNodeId]
                val tgtNode = nodeMap[edge.targetNodeId]
                val srcName = srcNode?.name ?: edge.sourceNodeId
                val tgtName = tgtNode?.name ?: edge.targetNodeId
                val disallowedLayers = tgtLayers.filter { it !in aRule.allowedLayers }.joinToString()
                violations.add(
                    RuleViolation(
                        ruleId = "ARCH-DISALLOWED-DEPENDENCY",
                        severity = Severity.Error,
                        message = "Layer '${aRule.fromLayer}' ($srcName) is not permitted to depend on layer(s) [$disallowedLayers] ($tgtName)",
                        nodeId = edge.sourceNodeId,
                        filePath = srcNode?.fileId,
                        lineNumber = edge.lineNumber
                    )
                )
            }
        }
    }

    private fun evaluateNodeRules(
        allNodes: List<GraphNode>,
        violations: MutableList<RuleViolation>
    ): Int {
        for (nRule in nodeRules) {
            for (node in allNodes) {
                if (!nRule.predicate(node)) {
                    val msg = nRule.messageBuilder?.invoke(node) ?: "Node '${node.name}' violated rule '${nRule.name}'"
                    violations.add(
                        RuleViolation(
                            ruleId = nRule.name,
                            severity = nRule.severity,
                            message = msg,
                            nodeId = node.nodeId,
                            filePath = node.fileId,
                            lineNumber = node.startLine
                        )
                    )
                }
            }
        }
        return nodeRules.size
    }

    private fun evaluateEdgeRules(
        allEdges: List<GraphEdge>,
        nodeMap: Map<String, GraphNode>,
        violations: MutableList<RuleViolation>
    ): Int {
        for (eRule in edgeRules) {
            for (edge in allEdges) {
                val srcNode = nodeMap[edge.sourceNodeId]
                val tgtNode = nodeMap[edge.targetNodeId]
                if (!eRule.predicate(edge, srcNode, tgtNode)) {
                    val msg = eRule.messageBuilder?.invoke(edge, srcNode, tgtNode) ?: "Edge '${edge.edgeId}' violated rule '${eRule.name}'"
                    violations.add(
                        RuleViolation(
                            ruleId = eRule.name,
                            severity = eRule.severity,
                            message = msg,
                            nodeId = edge.sourceNodeId,
                            filePath = srcNode?.fileId,
                            lineNumber = edge.lineNumber
                        )
                    )
                }
            }
        }
        return edgeRules.size
    }
}
