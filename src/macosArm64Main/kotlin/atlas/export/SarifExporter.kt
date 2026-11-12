package atlas.export

import atlas.model.AuditReport
import atlas.model.ComplexityViolation
import atlas.model.LayerInversionViolation
import atlas.model.PlanCollision
import atlas.model.SingleDeclarationViolation
import atlas.model.SkillAuditViolation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Exporter converting Code Atlas architectural audit reports into standard OASIS SARIF 2.1.0 format.
 * Suitable for direct ingestion by GitHub Code Scanning, GitLab SAST, and SonarQube.
 */
object SarifExporter {

    private val jsonPretty = Json { prettyPrint = true }

    /**
     * Convert an [AuditReport] into a SARIF 2.1.0 JSON string.
     */
    fun toSarifJson(report: AuditReport, pretty: Boolean = true): String {
        val root = toSarifJsonObject(report)
        return if (pretty) jsonPretty.encodeToString(JsonObject.serializer(), root)
        else root.toString()
    }

    /**
     * Convert an [AuditReport] into a SARIF 2.1.0 [JsonObject].
     */
    fun toSarifJsonObject(report: AuditReport): JsonObject {
        return buildJsonObject {
            put("\$schema", "https://json.schemastore.org/sarif-2.1.0.json")
            put("version", "2.1.0")
            putJsonArray("runs") {
                addJsonObject {
                    putJsonObject("tool") {
                        putJsonObject("driver") {
                            put("name", "Code Atlas")
                            put("version", "0.9.0")
                            put("informationUri", "https://github.com/jefferyforbes/code-atlas")
                            putJsonArray("rules") {
                                buildStandardRules()
                            }
                        }
                    }

                    putJsonArray("results") {
                        buildAuditResults(report)
                    }
                }
            }
        }
    }

    private fun JsonArrayBuilder.buildStandardRules() {
        addRule(
            id = "BOOST-003",
            name = "SingleDeclarationPrinciple",
            desc = "Production files declare at most 1 public top-level class, interface, object, or primary function.",
            defaultLevel = "error"
        )
        addRule(
            id = "ARCH-LAYER-INVERSION",
            name = "CleanArchitectureLayerInversion",
            desc = "Domain layer cannot depend on Presentation or Data layer implementations.",
            defaultLevel = "error"
        )
        addRule(
            id = "COMPLEXITY-001",
            name = "HighCognitiveComplexity",
            desc = "Function cognitive complexity exceeds the architectural maintainability threshold.",
            defaultLevel = "warning"
        )
        addRule(
            id = "PARAM-001",
            name = "ExcessiveParameterCount",
            desc = "Function parameter count exceeds the threshold; introduce a parameter object.",
            defaultLevel = "note"
        )
        addRule(
            id = "TEAMWORK-3D-COLLISION",
            name = "ConcurrencyPlanCollision",
            desc = "Simultaneous task plans have overlapping modification boundaries without declared serialization.",
            defaultLevel = "warning"
        )
        addRule(
            id = "SKILL-VIOLATION",
            name = "AgentSkillConstraintViolation",
            desc = "Skill instructions violate agent engineering or runtime constraints.",
            defaultLevel = "warning"
        )
    }

    private fun JsonArrayBuilder.buildAuditResults(report: AuditReport) {
        addSingleDeclarationResults(report.singleDeclarationViolations)
        addLayerInversionResults(report.layerInversions)
        addComplexityResults(report.complexityViolations)
        addParamAdvisoryResults(report.parameterAdvisories)
        addPlanCollisionResults(report.plan3dCollisions)
        addSkillViolationResults(report.skillViolations)
    }

    private fun JsonArrayBuilder.addSingleDeclarationResults(violations: List<SingleDeclarationViolation>) {
        for (v in violations) {
            addJsonObject {
                put("ruleId", "BOOST-003")
                put("level", "error")
                putJsonObject("message") { put("text", v.message) }
                putJsonArray("locations") {
                    addLocation(uri = v.relPath, line = 1)
                }
            }
        }
    }

    private fun JsonArrayBuilder.addLayerInversionResults(inversions: List<LayerInversionViolation>) {
        for (inv in inversions) {
            addJsonObject {
                put("ruleId", "ARCH-LAYER-INVERSION")
                put("level", "error")
                putJsonObject("message") { put("text", inv.message) }
                putJsonArray("locations") {
                    val uri = inv.source.substringBefore(":")
                    val resolvedUri = if (uri.isNotBlank()) uri else "architecture"
                    addLocation(uri = resolvedUri, line = if (inv.lineNumber > 0) inv.lineNumber else 1)
                }
            }
        }
    }

    private fun JsonArrayBuilder.addComplexityResults(violations: List<ComplexityViolation>) {
        for (comp in violations) {
            addJsonObject {
                put("ruleId", comp.ruleId)
                put("level", "warning")
                putJsonObject("message") { put("text", comp.message) }
                putJsonArray("locations") {
                    val startLine = parseStartLine(comp.lineSpan)
                    val uri = resolveUriFromNodeId(comp.nodeId)
                    addLocation(uri = if (uri.isNotBlank()) uri else "source", line = startLine)
                }
            }
        }
    }

    private fun JsonArrayBuilder.addParamAdvisoryResults(advisories: List<ComplexityViolation>) {
        for (param in advisories) {
            addJsonObject {
                put("ruleId", param.ruleId)
                put("level", "note")
                putJsonObject("message") { put("text", param.message) }
                putJsonArray("locations") {
                    val startLine = parseStartLine(param.lineSpan)
                    val uri = resolveUriFromNodeId(param.nodeId)
                    addLocation(uri = if (uri.isNotBlank()) uri else "source", line = startLine)
                }
            }
        }
    }

    private fun JsonArrayBuilder.addPlanCollisionResults(collisions: List<PlanCollision>) {
        for (plan in collisions) {
            addJsonObject {
                put("ruleId", "TEAMWORK-3D-COLLISION")
                put("level", "warning")
                putJsonObject("message") {
                    put(
                        "text",
                        "3D Concurrency Collision between '${plan.planA}' and '${plan.planB}' on files: ${plan.sharedFiles.joinToString()}"
                    )
                }
                putJsonArray("locations") {
                    addLocation(uri = plan.planA, line = 1)
                }
            }
        }
    }

    private fun JsonArrayBuilder.addSkillViolationResults(violations: List<SkillAuditViolation>) {
        for (sk in violations) {
            addJsonObject {
                put("ruleId", sk.rule)
                put("level", "warning")
                putJsonObject("message") { put("text", sk.message) }
                putJsonArray("locations") {
                    addLocation(uri = sk.relPath, line = if (sk.lineNumber > 0) sk.lineNumber else 1)
                }
            }
        }
    }

    private fun JsonArrayBuilder.addRule(
        id: String,
        name: String,
        desc: String,
        defaultLevel: String
    ) {
        addJsonObject {
            put("id", id)
            put("name", name)
            putJsonObject("shortDescription") { put("text", desc) }
            putJsonObject("defaultConfiguration") { put("level", defaultLevel) }
        }
    }

    private fun JsonArrayBuilder.addLocation(uri: String, line: Int) {
        addJsonObject {
            putJsonObject("physicalLocation") {
                putJsonObject("artifactLocation") {
                    put("uri", uri)
                }
                putJsonObject("region") {
                    put("startLine", line)
                    put("startColumn", 1)
                }
            }
        }
    }

    private fun resolveUriFromNodeId(nodeId: String): String {
        val withoutProject = if (nodeId.contains(":")) nodeId.substringAfter(":") else nodeId
        return withoutProject.substringBefore("#")
    }

    private fun parseStartLine(lineSpan: String): Int {
        if (lineSpan.isBlank()) return 1
        val parts = lineSpan.split('-').filter { it.isNotBlank() }
        val rawNum = parts.firstOrNull()?.trim()?.removePrefix("L")?.removePrefix("l") ?: return 1
        return rawNum.toIntOrNull() ?: 1
    }
}
