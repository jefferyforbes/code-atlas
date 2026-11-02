package atlas.cli

import atlas.engine.AutonomousRemediationVerifier
import atlas.mcp.AtlasMcpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * CLI command handlers for `atlas compose` subcommands (`index`, `query`, `audit`, `doctor`, `synthesize-scope`).
 */
internal object ComposeCliCommands {

    fun runComposeCommand(
        server: AtlasMcpServer,
        args: CliArgs
    ): Int {
        val command = if (args.command == "audit" && args.isComposeAuditFlag) "audit" else args.command
        val action = if (args.action == "search") "summary" else args.action
        return when (command) {
            "index" -> runComposeIndexSubcommand(server, args)
            "query" -> runComposeQuerySubcommand(server, args, action)
            "audit" -> runComposeAuditSubcommand(server, args)
            "doctor" -> runComposeDoctorSubcommand(server, args)
            "synthesize-scope" -> runComposeScopeSubcommand(server, args)
            "version" -> {
                AtlasCli.printCliVersion(args.isJson)
                0
            }
            else -> {
                println("Unknown compose command: $command")
                AtlasCli.printHelp()
                1
            }
        }
    }

    private fun renderComposeIndexOutput(res: JsonObject, project: String, isJson: Boolean) {
        if (isJson) {
            println(AtlasCli.jsonPretty.encodeToString(res))
            return
        }
        val cTotal = res["compose_nodes"]?.jsonPrimitive?.intOrNull ?: 0
        val cSkip = res["skippable_composables"]?.jsonPrimitive?.intOrNull ?: 0
        val compUp = res["compiler_upgraded"]?.jsonPrimitive?.intOrNull ?: 0
        val trCnt = res["trace_records_ingested"]?.jsonPrimitive?.intOrNull ?: 0
        val text = "[Atlas Compose] project=$project composables=$cTotal skippable=$cSkip compiler_upgraded=$compUp traces=$trCnt"
        println(AtlasCli.truncateSummary(text, false))
    }

    private fun runComposeIndexSubcommand(
        server: AtlasMcpServer,
        args: CliArgs
    ): Int {
        val res = server.indexComposeArtifacts(
            projectId = args.project,
            subpaths = if (args.paths.isNotEmpty()) args.paths else null,
            stabilityConfigPath = args.stabilityConfigPath,
            compilerReportsDir = args.compilerReportsDir,
            traceLogPath = args.traceLogPath
        )
        renderComposeIndexOutput(res, args.project, args.isJson)
        return 0
    }

    private fun buildComposeQueryArgs(
        mappedAction: String,
        args: CliArgs
    ): JsonObject = buildJsonObject {
        put("action", mappedAction)
        put("project", args.project)
        if (args.target.isNotEmpty()) put("target", args.target)
        val effDepth = if (args.depth <= 2) 5 else args.depth
        put("depth", effDepth)
        put("strong_skipping", args.strongSkipping)
    }

    private fun renderComposeQueryOutput(
        data: JsonObject,
        mappedAction: String,
        project: String,
        isJson: Boolean
    ) {
        if (isJson) {
            println(AtlasCli.jsonPretty.encodeToString(data))
            return
        }
        val total = data["total_composables"]?.jsonPrimitive?.intOrNull ?: 0
        val skipCnt = data["skippable_count"]?.jsonPrimitive?.intOrNull ?: 0
        val skipPct = data["skippable_pct"]?.jsonPrimitive?.doubleOrNull ?: 100.0
        val unstable = data["unstable_param_total"]?.jsonPrimitive?.intOrNull ?: 0
        val cascadeSize = data["cascade"]?.jsonArray?.size ?: 0
        val blameSize = data["blame"]?.jsonArray?.size ?: 0
        val actionTag = mappedAction.removePrefix("compose_")
        val text = "[Compose Query: $actionTag] project=$project composables=$total skippable=$skipCnt ($skipPct%) unstable_params=$unstable cascade=$cascadeSize blame=$blameSize"
        println(AtlasCli.truncateSummary(text, false))
    }

    private fun runComposeQuerySubcommand(
        server: AtlasMcpServer,
        args: CliArgs,
        action: String
    ): Int {
        val mappedAction = when (action.lowercase()) {
            "cascade", "compose_cascade" -> "compose_cascade"
            "blame", "compose_blame" -> "compose_blame"
            else -> "compose_summary"
        }
        val toolArgs = buildComposeQueryArgs(mappedAction, args)
        val res = server.callTool("atlas_query", toolArgs)
        val data = AtlasCli.loadSpilledData(res)
        renderComposeQueryOutput(data, mappedAction, args.project, args.isJson)
        return 0
    }

    private fun buildComposeAuditArgs(args: CliArgs): JsonObject = buildJsonObject {
        put("action", "audit_compose")
        put("project", args.project)
        put("strong_skipping", args.strongSkipping)
        if (args.paths.isNotEmpty()) putJsonArray("targets") { args.paths.forEach { add(it) } }
        if (!args.stabilityConfigPath.isNullOrBlank()) put("stability_config_path", args.stabilityConfigPath)
        if (!args.compilerReportsDir.isNullOrBlank()) put("compiler_reports_dir", args.compilerReportsDir)
        if (!args.traceLogPath.isNullOrBlank()) put("trace_log_path", args.traceLogPath)
    }

    private fun renderComposeAuditOutput(
        data: JsonObject,
        project: String,
        strongSkipping: Boolean,
        isJson: Boolean
    ): Boolean {
        val score = data["overall_score"]?.jsonPrimitive?.doubleOrNull ?: 100.0
        val passed = score >= 80.0
        val status = data["status"]?.jsonPrimitive?.content ?: (if (passed) "PASS" else "FAIL")
        val vCount = data["violations"]?.jsonArray?.size ?: 0
        val totalComp = data["total_composables"]?.jsonPrimitive?.intOrNull ?: 0
        if (isJson) {
            println(AtlasCli.jsonPretty.encodeToString(data))
        } else {
            val text = "[Compose Audit] project=$project status=$status score=$score/100 composables=$totalComp violations=$vCount strong_skipping=$strongSkipping"
            println(AtlasCli.truncateSummary(text, false))
        }
        return passed
    }

    private fun runComposeAuditSubcommand(
        server: AtlasMcpServer,
        args: CliArgs
    ): Int {
        val toolArgs = buildComposeAuditArgs(args)
        val res = server.callTool("atlas_audit", toolArgs)
        val data = AtlasCli.loadSpilledData(res)
        val passed = renderComposeAuditOutput(data, args.project, args.strongSkipping, args.isJson)
        return if (passed) 0 else 1
    }

    private fun executeComposeRemediation(
        server: AtlasMcpServer,
        project: String,
        rx: JsonObject,
        testCmdArg: String?
    ): Pair<Boolean, JsonObject>? {
        val rxFilePath = rx["file_path"]?.jsonPrimitive?.content ?: return null
        val rxBefore = rx["before_snippet"]?.jsonPrimitive?.content ?: return null
        val rxAfter = rx["after_snippet"]?.jsonPrimitive?.content ?: return null
        val rxRuleId = rx["rule_id"]?.jsonPrimitive?.content ?: "COMPOSE-REMEDIATION"
        val rxNodeId = rx["target_node_id"]?.jsonPrimitive?.content
        val rxWriteSiteSafe = rx["write_site_safe"]?.jsonPrimitive?.booleanOrNull

        val vResult = server.remediationVerifier.verifyAndApply(
            AutonomousRemediationVerifier.VerificationRequest(
                projectSlug = project,
                relPath = rxFilePath,
                beforeSnippet = rxBefore,
                afterSnippet = rxAfter,
                ruleId = rxRuleId,
                targetNodeId = rxNodeId,
                writeSiteSafe = rxWriteSiteSafe ?: true,
                dryRun = false,
                testCommand = testCmdArg
            )
        )
        val json = buildJsonObject {
            put("rule_id", rxRuleId)
            put("file_path", rxFilePath)
            if (rxNodeId != null) put("target_node_id", rxNodeId)
            put("valid", vResult.valid)
            put("phase", vResult.phase)
            put("message", vResult.message)
        }
        return Pair(vResult.valid, json)
    }

    private fun renderComposeRemediations(
        data: JsonObject,
        results: List<JsonObject>,
        allPassed: Boolean,
        args: CliArgs
    ) {
        if (args.isJson) {
            val resObj = buildJsonObject {
                data.forEach { (k, v) -> put(k, v) }
                putJsonArray("applied_remediations") {
                    results.forEach { add(it) }
                }
                put("all_passed", allPassed)
            }
            println(AtlasCli.jsonPretty.encodeToString(resObj))
        } else {
            val attempted = results.size
            val succeeded = results.count { it["valid"]?.jsonPrimitive?.booleanOrNull == true }
            val failed = attempted - succeeded
            val text = "[Compose Doctor: Apply] project=${args.project} attempted=$attempted succeeded=$succeeded failed=$failed"
            println(AtlasCli.truncateSummary(text, false))
            if (failed > 0) {
                results.filter { it["valid"]?.jsonPrimitive?.booleanOrNull != true }.take(3).forEach { f ->
                    println("  - Failed: ${f["rule_id"]?.jsonPrimitive?.content} in ${f["file_path"]?.jsonPrimitive?.content}: ${f["message"]?.jsonPrimitive?.content}")
                }
            }
        }
    }

    private fun applyComposeDoctorRemediations(
        server: AtlasMcpServer,
        args: CliArgs,
        data: JsonObject
    ): Int {
        val prescriptionsArray = data["prescriptions"]?.jsonArray ?: emptyList()
        val filteredPrescriptions = if (!args.ruleIdArg.isNullOrBlank()) {
            prescriptionsArray.filter { it.jsonObject["rule_id"]?.jsonPrimitive?.content == args.ruleIdArg }
        } else {
            prescriptionsArray
        }

        val results = mutableListOf<JsonObject>()
        var allPassed = true

        for (rxJson in filteredPrescriptions) {
            val outcome = executeComposeRemediation(server, args.project, rxJson.jsonObject, args.testCmdArg) ?: continue
            if (!outcome.first) allPassed = false
            results.add(outcome.second)
        }

        renderComposeRemediations(data, results, allPassed, args)
        return if (allPassed) 0 else 1
    }

    private fun runComposeDoctorSubcommand(
        server: AtlasMcpServer,
        args: CliArgs
    ): Int {
        val toolArgs = buildJsonObject {
            put("action", "compose_doctor")
            put("project", args.project)
            put("limit", args.limit)
            if (args.target.isNotEmpty()) putJsonArray("targets") { add(args.target) }
        }
        val res = server.callTool("atlas_audit", toolArgs)
        val data = AtlasCli.loadSpilledData(res)

        if (args.applyRemediation) {
            return applyComposeDoctorRemediations(server, args, data)
        }

        if (args.isJson) {
            println(AtlasCli.jsonPretty.encodeToString(data))
        } else {
            val mode = data["scoring_mode"]?.jsonPrimitive?.content ?: "ESTIMATED"
            val pCount = data["total_prescriptions"]?.jsonPrimitive?.intOrNull ?: 0
            val topRule = data["prescriptions"]?.jsonArray?.firstOrNull()?.jsonObject?.get("rule_id")?.jsonPrimitive?.content ?: "NONE"
            val text = "[Compose Doctor] project=${args.project} mode=$mode prescriptions=$pCount top_rule=$topRule"
            println(AtlasCli.truncateSummary(text, false))
        }
        return 0
    }

    private fun runComposeScopeSubcommand(
        server: AtlasMcpServer,
        args: CliArgs
    ): Int {
        val seedList = when {
            args.seeds.isNotEmpty() -> args.seeds
            args.paths.isNotEmpty() -> args.paths
            args.target.isNotEmpty() -> listOf(args.target)
            else -> emptyList()
        }
        val toolArgs = buildJsonObject {
            put("action", "synthesize_compose_scope")
            put("project", args.project)
            putJsonArray("targets") { seedList.forEach { add(it) } }
        }
        val res = server.callTool("atlas_audit", toolArgs)
        val data = AtlasCli.loadSpilledData(res)
        if (args.isJson) {
            println(AtlasCli.jsonPretty.encodeToString(data))
        } else {
            val fCnt = data["target_files"]?.jsonArray?.size ?: 0
            val sModels = data["shared_stable_models"]?.jsonArray?.size ?: 0
            val cLocals = data["composition_locals"]?.jsonArray?.size ?: 0
            val safe = data["safe_for_parallel_dispatch"]?.jsonPrimitive?.booleanOrNull ?: true
            val text = "[Compose 3D Scope] project=${args.project} files=$fCnt shared_models=$sModels locals=$cLocals parallel_safe=$safe"
            println(AtlasCli.truncateSummary(text, false))
        }
        return 0
    }
}
