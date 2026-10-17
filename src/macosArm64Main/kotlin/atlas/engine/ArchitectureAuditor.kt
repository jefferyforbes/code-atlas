package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.model.AuditReport
import atlas.model.LayerInversionViolation
import atlas.model.PlanCollision
import atlas.model.SingleDeclarationViolation

/**
 * AST-backed Architecture Auditor enforcing Clean Architecture layers, BOOST-003, and 3D collision safety.
 */
class ArchitectureAuditor(private val store: SqliteGraphStore) {

    fun runFullAudit(projectId: String): AuditReport {
        val singleDeclViolations = checkSingleDeclarationPrinciple(projectId)
        val layerInversions = checkLayerInversions(projectId)
        val planCollisions = checkPlan3dCollisions(projectId)

        val passed = singleDeclViolations.isEmpty() && layerInversions.isEmpty() && planCollisions.isEmpty()
        return AuditReport(
            projectId = projectId,
            passed = passed,
            singleDeclarationViolations = singleDeclViolations,
            layerInversions = layerInversions,
            plan3dCollisions = planCollisions
        )
    }

    fun checkSingleDeclarationPrinciple(projectId: String): List<SingleDeclarationViolation> {
        val violations = mutableListOf<SingleDeclarationViolation>()
        for (fileRec in store.listFiles(projectId)) {
            val relPath = fileRec.relPath
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

    fun checkLayerInversions(projectId: String): List<LayerInversionViolation> {
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

    fun checkPlan3dCollisions(projectId: String): List<PlanCollision> {
        val planEdges = store.listEdges(projectId, edgeTypes = listOf("PLAN_SCOPES_FILE"))
        val nodesById = store.listNodes(projectId).associateBy { it.nodeId }

        val planToFiles = mutableMapOf<String, MutableSet<String>>()
        val planToModules = mutableMapOf<String, MutableSet<String>>()

        for (edge in planEdges) {
            val planNode = nodesById[edge.sourceNodeId]
            val targetNode = nodesById[edge.targetNodeId]
            if (planNode == null || targetNode == null) continue
            if (planNode.signature.contains("(merged)") || planNode.signature.contains("(completed)")) {
                continue
            }
            val planId = planNode.name
            val relFile = targetNode.fileId.substringAfter(':')
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
}
