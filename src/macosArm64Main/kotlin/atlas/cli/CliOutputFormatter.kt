package atlas.cli

import atlas.engine.ToolMigrationEngine
import atlas.engine.WorkspaceInitEngine
import atlas.mcp.AtlasMcpServer
import atlas.util.PlatformFiles
import kotlin.math.roundToInt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Standardized CLI output formatter, summary serializer, and table renderer for [AtlasCli].
 * Adheres to BOOST-003 with a single top-level class.
 */
class CliOutputFormatter {
    data class AuditSummaryCounts(
        val bViolations: Int, val lInversions: Int, val pCollisions: Int,
        val sViolations: Int, val cViolations: Int, val dSuggestions: Int, val pAdvisories: Int
    )

    companion object {
        internal val jsonPretty = Json { prettyPrint = true }
        internal val jsonCompact = Json { prettyPrint = false }

        fun truncateSummary(text: String, isJson: Boolean, ceilingBytes: Int = AtlasCli.SUMMARY_CEILING_BYTES): String {
            if (isJson) return text
            val bytes = text.encodeToByteArray()
            if (bytes.size <= ceilingBytes) return text
            var end = ceilingBytes - 3
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return bytes.copyOfRange(0, end).decodeToString() + "..."
        }

        fun loadSpilledData(payload: JsonObject): JsonObject {
            val isSpillover = payload["spillover"]?.jsonPrimitive?.booleanOrNull ?: false
            val uri = payload["artifact_uri"]?.jsonPrimitive?.contentOrNull
            if (isSpillover && uri != null && PlatformFiles.exists(uri)) {
                try {
                    return Json.parseToJsonElement(PlatformFiles.readText(uri)).jsonObject
                } catch (e: Exception) {
                    PlatformFiles.logWarning("CliOutputFormatter", "failed to load spilled data from $uri: ${e.message}")
                }
            }
            return payload
        }

        fun printVersion(isJson: Boolean, version: String = AtlasMcpServer.VERSION) {
            if (isJson) {
                println(jsonPretty.encodeToString(buildJsonObject { put("name", "atlas"); put("version", version) }))
            } else {
                println("atlas $version")
            }
        }

        fun formatIndexSummary(project: String, res: JsonObject): String {
            val fTotal = res["files"]?.jsonPrimitive?.intOrNull ?: 0
            val fIdx = res["files_indexed"]?.jsonPrimitive?.intOrNull ?: 0
            val fSkip = res["files_skipped"]?.jsonPrimitive?.intOrNull ?: 0
            val nTotal = res["nodes"]?.jsonPrimitive?.intOrNull ?: 0
            val eTotal = res["edges"]?.jsonPrimitive?.intOrNull ?: 0
            return "[Atlas] project=$project files=$fTotal (indexed=$fIdx, skipped=$fSkip) nodes=$nTotal edges=$eTotal"
        }

        fun formatSynthesizeScopeOutput(data: JsonObject, project: String, seedList: List<String>, isJson: Boolean): String {
            if (isJson) return jsonPretty.encodeToString(data)
            val fCount = (data["files"] ?: data["files_count"])?.jsonArray?.size ?: 0
            val mods = extractStringList(data, "modules", "impacted_modules")
            val arch = extractStringList(data, "architecture", "impacted_layers")
            val collisions = extractStringList(data, "colliding_plans", "collidingPlans")
            return "[3D Scope] project=$project seeds=$seedList files=$fCount modules=$mods arch=$arch collisions=$collisions"
        }

        fun formatAuditArchitectureSummary(project: String, data: JsonObject, counts: AuditSummaryCounts, passed: Boolean, isJson: Boolean): String {
            if (isJson) return jsonPretty.encodeToString(data)
            val paramStr = if (counts.pAdvisories > 0) " parameter_advisories=${counts.pAdvisories}" else ""
            return "[Audit] project=$project passed=$passed boost003_violations=${counts.bViolations} layer_inversions=${counts.lInversions} plan_3d_collisions=${counts.pCollisions} skill_violations=${counts.sViolations} complexity_violations=${counts.cViolations}$paramStr description_suggestions=${counts.dSuggestions}"
        }

        fun formatAuditSkillsSummary(project: String, filteredViolations: JsonArray, ruleId: String?, passed: Boolean, isJson: Boolean): String {
            if (isJson) {
                return jsonPretty.encodeToString(buildJsonObject {
                    put("project", project); put("passed", passed); put("rule", ruleId ?: "ALL")
                    put("violations_count", filteredViolations.size); put("violations", filteredViolations)
                })
            }
            val count001 = filteredViolations.count { it.jsonObject["rule"]?.jsonPrimitive?.content == "SKILL-001" }
            val count002 = filteredViolations.count { it.jsonObject["rule"]?.jsonPrimitive?.content == "SKILL-002" }
            val count003 = filteredViolations.count { it.jsonObject["rule"]?.jsonPrimitive?.content == "SKILL-003" }
            return "[Audit] project=$project passed=$passed skill_violations=${filteredViolations.size} (SKILL-001: $count001, SKILL-002: $count002, SKILL-003: $count003)"
        }

        fun renderAuditLegacyToolsOutput(report: ToolMigrationEngine.MigrationReport, isJson: Boolean) {
            if (isJson) {
                println(jsonPretty.encodeToString(report))
            } else {
                val sample = formatLegacyToolSample(report.matches)
                val text = "[Audit Legacy Tools] passed=${report.totalOccurrences == 0} occurrences=${report.totalOccurrences} files=${report.filesAffected}$sample"
                println(truncateSummary(text, false))
            }
        }

        fun renderFixLegacyToolsOutput(report: ToolMigrationEngine.MigrationReport, applyRemediation: Boolean, isJson: Boolean) {
            if (isJson) {
                println(jsonPretty.encodeToString(report))
            } else {
                val mode = if (applyRemediation) "applied" else "dry-run"
                val sample = formatLegacyToolSample(report.matches)
                val text = "[Fix Legacy Tools] mode=$mode occurrences=${report.totalOccurrences} files=${report.filesAffected}$sample"
                println(truncateSummary(text, false))
            }
        }

        fun formatLegacyToolSample(matches: List<ToolMigrationEngine.MigrationMatch>): String {
            if (matches.isEmpty()) return ""
            return " top=[" + matches.take(5).joinToString("; ") { "${it.legacyTool} in ${it.file}:${it.line}" } + "]"
        }

        fun formatFixArchitectureText(data: JsonObject, project: String, dryRun: Boolean, filterPaths: List<String>, prescriptions: List<JsonElement>, isJson: Boolean): String {
            if (isJson) return jsonPretty.encodeToString(data)
            val total = data["total_violations"]?.jsonPrimitive?.intOrNull ?: 0
            val bCount = data["boost003_count"]?.jsonPrimitive?.intOrNull ?: 0
            val lCount = data["layer_inversion_count"]?.jsonPrimitive?.intOrNull ?: 0
            val vCount = data["verified_count"]?.jsonPrimitive?.intOrNull ?: 0
            val aCount = data["applied_count"]?.jsonPrimitive?.intOrNull ?: 0
            val mode = if (dryRun) "dry-run" else "applied"
            val header = "[Remediation] project=$project mode=$mode violations=$total boost003=$bCount layer_inversions=$lCount verified=$vCount applied=$aCount"
            return header + formatFixPrescriptionsDetails(filterPaths, prescriptions)
        }

        fun formatDeadCodeOutput(project: String, data: JsonObject, isJson: Boolean): String {
            if (isJson) return jsonPretty.encodeToString(data)
            val count = data["dead_code_count"]?.jsonPrimitive?.intOrNull ?: 0
            val names = data["dead_nodes"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content } ?: emptyList()
            return "[Dead Code] project=$project dead_symbols=$count top=[${names.take(5).joinToString(", ")}]"
        }

        fun formatRecordMemoryOutput(res: JsonObject, outcome: String?, targetNode: String, isJson: Boolean): String {
            if (isJson) return jsonPretty.encodeToString(res)
            val annId = res["annotation_id"]?.jsonPrimitive?.content ?: "unknown"
            val nId = res["node_id"]?.jsonPrimitive?.content ?: targetNode
            val out = res["outcome"]?.jsonPrimitive?.content ?: (outcome ?: "useful")
            return "[Atlas Memory] Recorded outcome=$out node=$nId id=$annId"
        }

        fun formatVerifyRemediationOutput(data: JsonObject, filePath: String, valid: Boolean, isJson: Boolean): String {
            if (isJson) return jsonPretty.encodeToString(data)
            val phase = data["phase"]?.jsonPrimitive?.content ?: "UNKNOWN"
            val msg = data["message"]?.jsonPrimitive?.content ?: ""
            return "[Remediation Verification] valid=$valid phase=$phase file=$filePath message=\"$msg\""
        }

        fun printDoctorTriageOutput(project: String, apply: Boolean, archViolations: Int, compPrescriptions: Int, composePrescriptions: Int, verifiedComp: Int) {
            val mode = if (apply) "apply" else "triage"
            val text = "[Doctor: $mode] project=$project architecture_violations=$archViolations complexity_prescriptions=$compPrescriptions compose_prescriptions=$composePrescriptions"
            println(truncateSummary(text, false))
            if (archViolations > 0 && !apply) println("  - Architecture: $archViolations violation(s) detected. Run 'atlas fix' or 'atlas doctor --apply' to remediate.")
            if (compPrescriptions > 0) {
                val detail = if (apply) "$verifiedComp verified & applied." else "available. Run 'atlas doctor --complexity' for breakdown."
                println("  - Complexity: $compPrescriptions prescription(s) $detail")
            }
            if (composePrescriptions > 0) println("  - Compose: $composePrescriptions prescription(s) available. Run 'atlas compose doctor' for breakdown.")
        }

        fun printDoctorJson(project: String, apply: Boolean, archData: JsonObject, compData: JsonObject, composeData: JsonObject?) {
            println(jsonPretty.encodeToString(buildJsonObject {
                put("project", project); put("mode", if (apply) "applied" else "triage")
                put("architecture", archData); put("complexity", compData)
                composeData?.let { put("compose", it) }
            }))
        }

        fun printComplexityDoctorOutput(project: String, data: JsonObject, limit: Int) {
            val total = data["total_prescriptions"]?.jsonPrimitive?.intOrNull ?: 0
            val verified = data["verified_count"]?.jsonPrimitive?.intOrNull ?: 0
            val rolledBack = data["rolled_back_count"]?.jsonPrimitive?.intOrNull ?: 0
            val prescriptions = data["prescriptions"]?.jsonArray ?: JsonArray(emptyList())
            println("[Complexity Doctor] project=$project prescriptions=$total applied=$verified rolled_back=$rolledBack")
            prescriptions.take(limit).forEach { printSinglePrescription(it) }
        }

        fun renderComplexityAuditOutput(data: JsonObject, project: String, passed: Boolean, thresh: Int, isJson: Boolean): String {
            if (isJson) return jsonPretty.encodeToString(data)
            val total = data["total_violations"]?.jsonPrimitive?.intOrNull ?: 0
            val high = data["high_severity_count"]?.jsonPrimitive?.intOrNull ?: 0
            val warn = data["warning_count"]?.jsonPrimitive?.intOrNull ?: 0
            val rStr = formatRatchetSegment("ratchet", data["ratchet"]?.jsonPrimitive?.intOrNull, data["baseline_delta"]?.jsonPrimitive?.intOrNull)
            val hrStr = formatRatchetSegment("high_ratchet", data["high_ratchet"]?.jsonPrimitive?.intOrNull, data["high_delta"]?.jsonPrimitive?.intOrNull)
            val phrStr = formatRatchetSegment("param_high_ratchet", data["param_high_ratchet"]?.jsonPrimitive?.intOrNull, data["param_high_delta"]?.jsonPrimitive?.intOrNull)
            val advisory = data["advisory_count"]?.jsonPrimitive?.intOrNull ?: 0
            val advStr = if (advisory > 0) ", ADVISORY=$advisory" else ""
            return "[Complexity Audit] project=$project passed=$passed threshold=$thresh violations=$total$rStr$hrStr$phrStr (HIGH=$high, WARNING=$warn$advStr)"
        }

        fun printComplexityQueryTable(results: JsonArray) {
            val header = "SYMBOL".padEnd(30) + " " + "LOCATION".padEnd(25) + " " + "COGNITIVE".padStart(9) + " " +
                "CYCLO".padStart(7) + " " + "NESTING".padStart(7) + " " + "RISK_INDEX".padStart(10) + " " + "GRADE".padStart(5)
            println(header)
            println("-".repeat(98))
            results.forEach { println(formatComplexityQueryRow(it.jsonObject)) }
        }

        fun renderInitOutput(effectiveProject: String, result: WorkspaceInitEngine.InitResult, isJson: Boolean) {
            if (isJson) {
                println(jsonPretty.encodeToString(buildJsonObject {
                    put("success", result.success); put("project", effectiveProject); put("hook_path", result.hookPath)
                    put("hook_type", result.hookType); put("message", result.message); put("overwritten", result.overwritten)
                    put("dry_run", result.dryRun)
                    if (result.scriptContent != null) put("script_content", result.scriptContent)
                }))
                return
            }
            if (!result.success) {
                println("[Init Error] $effectiveProject: ${result.message}")
            } else {
                println("[Init] $effectiveProject: ${result.message}")
                if (result.dryRun && result.scriptContent != null) {
                    println("--- Script Preview ---\n${result.scriptContent}\n----------------------")
                }
            }
        }

        private fun extractStringList(data: JsonObject, key1: String, key2: String): List<String> =
            (data[key1] ?: data[key2])?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        private fun formatSinglePrescriptionLine(p: JsonElement): String {
            val obj = p.jsonObject
            val f = obj["file_path"]?.jsonPrimitive?.content ?: ""
            val r = obj["rule_id"]?.jsonPrimitive?.content ?: ""
            val s = obj["fix_strategy"]?.jsonPrimitive?.content ?: ""
            val v = obj["verified"]?.jsonPrimitive?.booleanOrNull ?: false
            val app = obj["applied"]?.jsonPrimitive?.booleanOrNull ?: false
            val line = "  - $r: $f [$s, verified=$v, applied=$app]"
            val diff = obj["diff_preview"]?.jsonPrimitive?.contentOrNull
            if (diff.isNullOrBlank()) return line
            return "$line\n" + diff.lines().take(20).joinToString("\n") { "    $it" }
        }

        private fun formatFixPrescriptionsDetails(filterPaths: List<String>, prescriptions: List<JsonElement>): String {
            if (prescriptions.isEmpty() || (filterPaths.isEmpty() && prescriptions.size > 5)) return ""
            return "\n" + prescriptions.take(5).joinToString("\n") { formatSinglePrescriptionLine(it) }
        }

        private fun printSinglePrescription(elem: JsonElement) {
            val p = elem.jsonObject
            val rank = p["rank"]?.jsonPrimitive?.intOrNull ?: 1
            val strat = p["fix_strategy"]?.jsonPrimitive?.content ?: "UNKNOWN"
            val file = p["file_path"]?.jsonPrimitive?.content ?: ""
            val line = p["line"]?.jsonPrimitive?.intOrNull ?: 0
            val cogB = p["cognitive_before"]?.jsonPrimitive?.intOrNull ?: 0
            val cogA = p["cognitive_after"]?.jsonPrimitive?.intOrNull ?: 0
            val red = p["reduction_percent"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            println("  #$rank $strat at $file:$line (Cognitive: $cogB -> $cogA, reduction: ${red}%)")
        }

        private fun formatRatchetSegment(label: String, ratchet: Int?, delta: Int?): String {
            if (ratchet == null) return ""
            val deltaStr = if (delta != null && delta > 0) "+$delta" else "${delta ?: 0}"
            return " $label=$ratchet (delta=$deltaStr)"
        }

        private fun formatComplexityQueryRow(r: JsonObject): String {
            val name = (r["name"]?.jsonPrimitive?.content ?: "").take(30).padEnd(30)
            val loc = ("${r["file_path"]?.jsonPrimitive?.content ?: ""}:${r["line"]?.jsonPrimitive?.intOrNull ?: 0}").take(25).padEnd(25)
            val cog = (r["cognitive"]?.jsonPrimitive?.intOrNull ?: 0).toString().padStart(9)
            val cyclo = (r["cyclomatic"]?.jsonPrimitive?.intOrNull ?: 0).toString().padStart(7)
            val nest = (r["max_nesting"]?.jsonPrimitive?.intOrNull ?: 0).toString().padStart(7)
            val risk = r["risk_index"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val roundedRisk = ((risk * 10.0).roundToInt() / 10.0).toString().padStart(10)
            val grade = (r["grade"]?.jsonPrimitive?.content ?: "A").padStart(5)
            return "$name $loc $cog $cyclo $nest $roundedRisk $grade"
        }
    }
}
