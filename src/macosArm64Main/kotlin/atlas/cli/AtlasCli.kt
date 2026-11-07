package atlas.cli

import atlas.engine.AutonomousRemediationVerifier
import atlas.engine.CodegraphCompatibilityEngine
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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Command-line entrypoint for indexing, querying, synthesizing 3D scopes, and auditing code graphs.
 */
class AtlasCli {
    companion object {
        const val VERSION = AtlasMcpServer.VERSION
        const val SUMMARY_CEILING_BYTES = 400

        internal val jsonPretty = Json { prettyPrint = true }
        internal val jsonCompact = Json { prettyPrint = false }

        fun run(argv: Array<String>): Int {
            if (isVersionCheck(argv)) return printVersion(argv)
            if (isHelpCheck(argv)) {
                printHelp()
                return 0
            }

            val args = CliArgs.parse(argv) ?: run {
                printHelp()
                return 1
            }

            val isMcpServe = args.command == "serve-mcp" || argv.contains("--mcp") || argv.contains("serve")
            val server = AtlasMcpServer(
                workspaceRoot = args.workspace,
                customDbPath = args.customDb,
                cacheProfile = args.cacheProfile,
                enableCodegraphTools = isMcpServe || (PlatformFiles.getenv("ATLAS_CODEGRAPH_COMPAT") == "1")
            )

            return try {
                dispatchCommand(server, args)
            } finally {
                server.close()
            }
        }

        private fun isVersionCheck(argv: Array<String>): Boolean {
            if (argv.contains("--version") || argv.contains("-v") || (argv.isNotEmpty() && argv[0] == "version")) return true
            return argv.size >= 2 && (argv[0] == "figma" || argv[0] == "compose") && (argv[1] == "version" || argv[1] == "--version" || argv[1] == "-v")
        }

        private fun printVersion(argv: Array<String>): Int {
            printCliVersion(argv.contains("--json"))
            return 0
        }

        internal fun printCliVersion(isJson: Boolean) {
            CliOutputFormatter.printVersion(isJson, VERSION)
        }

        private fun isHelpCheck(argv: Array<String>): Boolean {
            if (argv.isEmpty() || argv.contains("--help") || argv.contains("-h")) return true
            return argv.size == 1 && (argv[0] == "figma" || argv[0] == "compose" || argv[0] == "complexity" || argv[0] == "help")
        }

        private fun isComplexityDoctor(args: CliArgs): Boolean {
            if (args.command == "doctor" && args.isComplexityFlag) return true
            return args.subsystem == "complexity" && args.command == "doctor"
        }

        private fun isComplexityAudit(args: CliArgs): Boolean {
            if (args.command == "audit" && args.isComplexityFlag) return true
            return args.subsystem == "complexity" && args.command == "audit"
        }

        private fun isComplexityQuery(args: CliArgs): Boolean {
            if (args.command != "query") return false
            return args.action == "complexity" || args.isComplexityFlag || args.subsystem == "complexity"
        }

        private fun dispatchCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val specialCode = dispatchSpecialCommand(server, args)
            if (specialCode != null) return specialCode
            return dispatchStandardCommand(server, args)
        }

        private fun dispatchSpecialCommand(server: AtlasMcpServer, args: CliArgs): Int? {
            if (args.subsystem == "figma") return dispatchFigma(server, args)
            if (args.isComplexityFlag || args.subsystem == "complexity") return dispatchComplexity(server, args)
            if (isComposeDispatch(args)) return dispatchCompose(server, args)
            return null
        }

        private fun dispatchFigma(server: AtlasMcpServer, args: CliArgs): Int =
            FigmaCliCommands.runFigmaCommand(server, args)

        private fun dispatchComplexity(server: AtlasMcpServer, args: CliArgs): Int {
            if (isComplexityDoctor(args)) {
                return runComplexityDoctorCommand(server, args)
            }
            if (isComplexityAudit(args)) {
                return runComplexityAuditCommand(server, args)
            }
            return runComplexityQueryCommand(
                server = server,
                project = args.project,
                target = args.target,
                threshold = args.thresholdArg,
                limit = args.limit,
                isJson = args.isJson
            )
        }

        private fun dispatchCompose(server: AtlasMcpServer, args: CliArgs): Int =
            ComposeCliCommands.runComposeCommand(server, args)

        private fun isComposeDispatch(args: CliArgs): Boolean {
            return args.subsystem == "compose" ||
                (args.command == "query" && args.action in setOf("summary", "cascade", "blame", "compose_summary", "compose_cascade", "compose_blame")) ||
                (args.command == "audit" && args.isComposeAuditFlag) ||
                (args.command == "doctor" && (args.subsystem == "compose" || args.isComposeAuditFlag))
        }

        private val CODEGRAPH_INSPECTION_COMMANDS = setOf(
            "explore", "callers", "callees", "impact", "status", "files", "search", "node", "context", "index", "synthesize-scope"
        )
        private val CODEGRAPH_REMEDIATION_COMMANDS = setOf(
            "audit", "fix", "doctor", "dead-code", "record-memory", "verify-remediation", "query"
        )

        private fun dispatchStandardCommand(server: AtlasMcpServer, args: CliArgs): Int {
            if (args.command in CODEGRAPH_INSPECTION_COMMANDS) {
                return dispatchInspectionCommand(server, args)
            }
            if (args.command in CODEGRAPH_REMEDIATION_COMMANDS) {
                return dispatchRemediationCommand(server, args)
            }
            return dispatchLifecycleCommand(server, args)
        }

        private fun dispatchInspectionCommand(server: AtlasMcpServer, args: CliArgs): Int = when (args.command) {
            "explore", "callers", "callees", "impact", "status", "files", "search", "node" -> runCodegraphCommand(server, args)
            "context" -> runContextCommand(server, args)
            "index" -> runIndexCommand(server, args)
            "synthesize-scope" -> runSynthesizeScopeCommand(server, args)
            else -> 1
        }

        private fun dispatchRemediationCommand(server: AtlasMcpServer, args: CliArgs): Int = when (args.command) {
            "audit" -> runAuditCommand(server, args)
            "fix" -> runFixCommand(server, args)
            "doctor" -> runGeneralDoctorCommand(server, args)
            "dead-code" -> runDeadCodeCommand(server, args)
            "record-memory" -> runRecordMemoryCommand(server, args)
            "verify-remediation" -> runVerifyRemediationCommand(server, args)
            "query" -> runQueryCommand(server, args)
            else -> 1
        }

        private fun dispatchLifecycleCommand(server: AtlasMcpServer, args: CliArgs): Int = when (args.command) {
            "migrate-tools" -> runMigrateToolsCommand(server, args.paths, args.target, args.applyRemediation, args.isJson)
            "serve-mcp" -> { server.serveStdio(); 0 }
            "init" -> runInitCommand(args)
            "version" -> printVersion(if (args.isJson) arrayOf("--json") else emptyArray())
            else -> {
                println("Unknown command: ${args.command}")
                printHelp()
                1
            }
        }

        private fun runCodegraphCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val effectiveServer = server.resolveServerForProject(args.project, args.workspace)
            val res = queryCodegraphEngine(effectiveServer, args)
            val text = if (args.isJson) jsonPretty.encodeToString(res) else res["text"]?.jsonPrimitive?.contentOrNull ?: ""
            println(text)
            return 0
        }

        private fun queryCodegraphEngine(effectiveServer: AtlasMcpServer, args: CliArgs): JsonObject = when (args.command) {
            "explore" -> effectiveServer.codegraphEngine.explore(args.project, getQueryText(args), maxFiles = args.limit)
            "callers" -> effectiveServer.codegraphEngine.callers(args.project, args.target, file = args.fileKey, limit = args.limit)
            "callees" -> effectiveServer.codegraphEngine.callees(args.project, args.target, file = args.fileKey, limit = args.limit)
            "impact" -> effectiveServer.codegraphEngine.impact(args.project, args.target, file = args.fileKey, depth = args.depth)
            "status" -> effectiveServer.codegraphEngine.status(args.project)
            "files" -> effectiveServer.codegraphEngine.files(args.project, path = args.paths.firstOrNull(), pattern = args.target.ifEmpty { null })
            "search" -> effectiveServer.codegraphEngine.search(args.project, getQueryText(args), limit = args.limit)
            "node" -> effectiveServer.codegraphEngine.node(
                CodegraphCompatibilityEngine.NodeQueryRequest(
                    projectId = args.project,
                    symbol = args.target.ifEmpty { null },
                    file = args.fileKey,
                    includeCode = args.includeSource
                )
            )
            else -> JsonObject(emptyMap())
        }

        private fun getQueryText(args: CliArgs): String =
            if (args.target.isNotEmpty()) args.target else args.paths.joinToString(" ")

        private fun runContextCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val effectiveServer = server.resolveServerForProject(args.project, args.workspace)
            val targetsList = if (args.seeds.isNotEmpty()) args.seeds else (if (args.paths.isNotEmpty()) args.paths else (if (args.target.isNotEmpty()) listOf(args.target) else emptyList()))
            val res = effectiveServer.architectContextEngine.synthesizeContext(
                projectId = args.project,
                seedTargets = targetsList,
                depth = args.depth
            )
            val text = if (args.isJson) jsonPretty.encodeToString(res) else res.inlineMarkdown
            println(text)
            return 0
        }

        private fun runIndexCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val effectiveServer = server.resolveServerForProject(args.project)
            val expandedPaths = args.paths.map { resolveUserHome(it) }
            val res = effectiveServer.indexWorkspace(args.project, subpaths = if (expandedPaths.isNotEmpty()) expandedPaths else null, force = args.forceIndex)
            val text = if (args.isJson) jsonPretty.encodeToString(res) else CliOutputFormatter.formatIndexSummary(args.project, res)
            println(truncateSummary(text, args.isJson))
            return 0
        }

        private fun resolveUserHome(path: String): String =
            if (path.startsWith("~")) path.replaceFirst("~", PlatformFiles.userHome()) else path

        private fun runSynthesizeScopeCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val seedList = if (args.paths.isNotEmpty()) args.paths else (if (args.target.isNotEmpty()) listOf(args.target) else emptyList())
            val toolArgs = buildJsonObject {
                put("action", "synthesize_3d_scope")
                put("project", args.project)
                putJsonArray("targets") { seedList.forEach { add(it) } }
            }
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            val text = CliOutputFormatter.formatSynthesizeScopeOutput(data, args.project, seedList, args.isJson)
            println(truncateSummary(text, args.isJson))
            return 0
        }

        private fun runAuditCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val filterPaths = if (args.paths.isNotEmpty()) args.paths else (if (args.target.isNotEmpty()) listOf(args.target) else emptyList())
            if (args.legacyToolsFlag) {
                return runAuditLegacyTools(server, filterPaths, args.isJson)
            }
            if (args.action == "architect_context" || args.action == "architect-context") {
                return runAuditArchitectContext(server, args.project, filterPaths, args.depth, args.isJson)
            }
            if (args.ruleIdArg?.startsWith("SKILL") == true) {
                return runAuditSkills(server, args.project, filterPaths, args.ruleIdArg, args.isJson)
            }
            return runAuditArchitecture(server, args, filterPaths)
        }

        private fun runAuditLegacyTools(server: AtlasMcpServer, filterPaths: List<String>, isJson: Boolean): Int {
            val scanPaths = if (filterPaths.isNotEmpty()) filterPaths else listOf(server.workspaceRoot)
            val report = ToolMigrationEngine.scanAndMigrate(scanPaths, apply = false)
            CliOutputFormatter.renderAuditLegacyToolsOutput(report, isJson)
            return if (report.totalOccurrences == 0) 0 else 1
        }

        private fun runAuditArchitectContext(server: AtlasMcpServer, project: String, filterPaths: List<String>, depth: Int, isJson: Boolean): Int {
            val toolArgs = buildJsonObject {
                put("action", "architect_context")
                put("project", project)
                if (filterPaths.isNotEmpty()) {
                    putJsonArray("targets") { filterPaths.forEach { add(it) } }
                }
                put("depth", depth)
            }
            val res = server.callTool("atlas_audit", toolArgs)
            if (isJson) {
                println(jsonPretty.encodeToString(res))
            } else {
                val md = res["inlineMarkdown"]?.jsonPrimitive?.contentOrNull
                    ?: res["inline_markdown"]?.jsonPrimitive?.contentOrNull
                    ?: jsonPretty.encodeToString(res)
                println(md)
            }
            return 0
        }

        private fun extractAuditArraySize(data: JsonObject, key1: String, key2: String): Int {
            if (key1 == "complexity_violations") {
                val total = data["total_complexity_violations"]?.jsonPrimitive?.intOrNull
                if (total != null) return total
            }
            if (key1 == "parameter_advisories") {
                val total = data["total_parameter_advisories"]?.jsonPrimitive?.intOrNull
                if (total != null) return total
            }
            val arr = data[key1]?.jsonArray ?: data[key2]?.jsonArray
            return arr?.size ?: 0
        }

        private fun isArchitectureAuditPassed(
            data: JsonObject,
            bViolations: Int,
            lInversions: Int,
            pCollisions: Int,
            cViolations: Int,
            sViolations: Int = 0
        ): Boolean {
            val explicit = data["passed"]?.jsonPrimitive?.booleanOrNull
            if (explicit != null) return explicit
            return bViolations == 0 && lInversions == 0 && pCollisions == 0 && sViolations == 0
        }

        private fun runAuditSkills(
            server: AtlasMcpServer,
            project: String,
            filterPaths: List<String>,
            ruleId: String?,
            isJson: Boolean
        ): Int {
            val toolArgs = buildJsonObject {
                put("action", "audit_skills")
                put("project", project)
                if (filterPaths.isNotEmpty()) {
                    putJsonArray("targets") { filterPaths.forEach { add(it) } }
                }
            }
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            val rawViolations = data["violations"]?.jsonArray ?: JsonArray(emptyList())
            val filteredViolations = if (ruleId.isNullOrBlank() || ruleId == "SKILL") {
                rawViolations
            } else {
                JsonArray(rawViolations.filter { it.jsonObject["rule"]?.jsonPrimitive?.content == ruleId })
            }
            val passed = filteredViolations.isEmpty()
            val text = CliOutputFormatter.formatAuditSkillsSummary(
                project = project,
                filteredViolations = filteredViolations,
                ruleId = ruleId,
                passed = passed,
                isJson = isJson
            )
            println(truncateSummary(text, isJson))
            return if (passed) 0 else 1
        }

        private fun runAuditArchitecture(
            server: AtlasMcpServer,
            args: CliArgs,
            filterPaths: List<String>
        ): Int {
            val toolArgs = buildJsonObject {
                put("action", "verify_architecture")
                put("project", args.project)
                if (filterPaths.isNotEmpty()) {
                    putJsonArray("targets") { filterPaths.forEach { add(it) } }
                }
                if (!args.descriptionMode.isNullOrBlank()) {
                    put("description_mode", args.descriptionMode)
                }
                if (!args.outcome.isNullOrBlank()) put("outcome", args.outcome)
                if (!args.note.isNullOrBlank()) put("note", args.note)
            }
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            val bViolations = extractAuditArraySize(data, "single_declaration_violations", "singleDeclarationViolations")
            val lInversions = extractAuditArraySize(data, "layer_inversions", "layerInversions")
            val pCollisions = extractAuditArraySize(data, "plan_3d_collisions", "plan3dCollisions")
            val dSuggestions = extractAuditArraySize(data, "description_suggestions", "descriptionSuggestions")
            val cViolations = extractAuditArraySize(data, "complexity_violations", "complexityViolations")
            val sViolations = extractAuditArraySize(data, "skill_violations", "skillViolations")
            val pAdvisories = extractAuditArraySize(data, "parameter_advisories", "parameterAdvisories")
            val passed = isArchitectureAuditPassed(data, bViolations, lInversions, pCollisions, cViolations, sViolations)
            val counts = CliOutputFormatter.AuditSummaryCounts(
                bViolations = bViolations,
                lInversions = lInversions,
                pCollisions = pCollisions,
                sViolations = sViolations,
                cViolations = cViolations,
                dSuggestions = dSuggestions,
                pAdvisories = pAdvisories
            )
            val text = CliOutputFormatter.formatAuditArchitectureSummary(
                project = args.project,
                data = data,
                counts = counts,
                passed = passed,
                isJson = args.isJson
            )
            println(truncateSummary(text, args.isJson))
            return if (passed) 0 else 1
        }

        private fun runFixCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val filterPaths = if (args.paths.isNotEmpty()) args.paths else (if (args.target.isNotEmpty()) listOf(args.target) else emptyList())
            if (args.legacyToolsFlag || args.ruleIdArg == "LEGACY-TOOLS" || args.ruleIdArg == "MIGRATE-TOOLS") {
                return runFixLegacyTools(server, filterPaths, args.applyRemediation, args.isJson)
            }
            return runFixArchitecture(server, args, filterPaths)
        }

        private fun runFixLegacyTools(server: AtlasMcpServer, filterPaths: List<String>, applyRemediation: Boolean, isJson: Boolean): Int {
            val scanPaths = if (filterPaths.isNotEmpty()) filterPaths else listOf(server.workspaceRoot)
            val report = ToolMigrationEngine.scanAndMigrate(scanPaths, apply = applyRemediation)
            CliOutputFormatter.renderFixLegacyToolsOutput(report, applyRemediation, isJson)
            return if (applyRemediation || report.totalOccurrences == 0) 0 else 1
        }

        private fun extractIntField(data: JsonObject, key: String): Int =
            data[key]?.jsonPrimitive?.intOrNull ?: 0

        private fun isRemediationSuccess(apply: Boolean, aCount: Int, vCount: Int, total: Int, prescriptionCount: Int): Boolean {
            if (total == 0) return true
            return if (apply) aCount == prescriptionCount else vCount > 0
        }

        private fun runFixArchitecture(server: AtlasMcpServer, args: CliArgs, filterPaths: List<String>): Int {
            val toolArgs = buildFixArchitectureArgs(args, filterPaths)
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            val total = extractIntField(data, "total_violations")
            val bCount = extractIntField(data, "boost003_count")
            val lCount = extractIntField(data, "layer_inversion_count")
            val vCount = extractIntField(data, "verified_count")
            val aCount = extractIntField(data, "applied_count")
            val dryRun = data["dry_run"]?.jsonPrimitive?.booleanOrNull ?: (!args.applyRemediation)
            val prescriptions = data["prescriptions"]?.jsonArray ?: emptyList()

            if (args.applyRemediation && aCount > 0) {
                reindexFixPrescriptions(server, args.project, filterPaths, prescriptions)
            }

            val text = CliOutputFormatter.formatFixArchitectureText(data, args.project, dryRun, filterPaths, prescriptions, args.isJson)
            println(truncateSummary(text, args.isJson))
            return if (isRemediationSuccess(args.applyRemediation, aCount, vCount, total, prescriptions.size)) 0 else 1
        }

        private fun buildFixArchitectureArgs(args: CliArgs, filterPaths: List<String>): JsonObject = buildJsonObject {
            put("action", "remedy_architecture")
            put("project", args.project)
            if (filterPaths.isNotEmpty()) {
                putJsonArray("targets") { filterPaths.forEach { add(it) } }
            }
            if (!args.ruleIdArg.isNullOrBlank()) put("rule_id", args.ruleIdArg)
            put("dry_run", !args.applyRemediation)
            put("split", args.splitBoost003)
            put("make_private", args.makePrivateBoost003)
            if (!args.testCmdArg.isNullOrBlank()) put("test_command", args.testCmdArg)
            put("limit", args.limit)
        }

        private fun reindexFixPrescriptions(server: AtlasMcpServer, project: String, filterPaths: List<String>, prescriptions: List<JsonElement>) {
            val reindexPaths = mutableListOf<String>()
            if (filterPaths.isNotEmpty()) {
                reindexPaths.addAll(filterPaths)
            }
            for (p in prescriptions) {
                val f = p.jsonObject["file_path"]?.jsonPrimitive?.content ?: continue
                reindexPaths.add(f)
                val sfArray = p.jsonObject["spawned_files"]?.jsonArray ?: emptyList()
                for (sf in sfArray) {
                    val spRel = sf.jsonObject["rel_path"]?.jsonPrimitive?.content ?: continue
                    reindexPaths.add(spRel)
                }
            }
            server.indexWorkspace(project, subpaths = if (reindexPaths.isNotEmpty()) reindexPaths.distinct() else null, force = true)
        }

        private fun runDeadCodeCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val filterPaths = if (args.paths.isNotEmpty()) args.paths else (if (args.target.isNotEmpty()) listOf(args.target) else emptyList())
            val toolArgs = buildJsonObject {
                put("action", "dead_code")
                put("project", args.project)
                if (filterPaths.isNotEmpty()) {
                    putJsonArray("targets") { filterPaths.forEach { add(it) } }
                }
            }
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            val text = CliOutputFormatter.formatDeadCodeOutput(args.project, data, args.isJson)
            println(truncateSummary(text, args.isJson))
            return 0
        }

        private fun runRecordMemoryCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val targetNode = args.target.ifEmpty { args.paths.firstOrNull() ?: "" }
            val toolArgs = buildRecordMemoryArgs(args, targetNode)
            val res = server.callTool("atlas_audit", toolArgs)
            val text = CliOutputFormatter.formatRecordMemoryOutput(res, args.outcome, targetNode, args.isJson)
            println(truncateSummary(text, args.isJson))
            return 0
        }

        private fun buildRecordMemoryArgs(args: CliArgs, targetNode: String): JsonObject = buildJsonObject {
            put("action", "record_memory")
            put("project", args.project)
            putJsonArray("targets") { if (targetNode.isNotEmpty()) add(targetNode) }
            if (!args.outcome.isNullOrBlank()) put("outcome", args.outcome)
            if (!args.note.isNullOrBlank()) put("note", args.note)
        }

        private fun runVerifyRemediationCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val filePath = resolveVerifyFilePath(args)
            val toolArgs = buildVerifyRemediationArgs(args, filePath)
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            val valid = data["valid"]?.jsonPrimitive?.booleanOrNull ?: false
            val text = CliOutputFormatter.formatVerifyRemediationOutput(data, filePath, valid, args.isJson)
            println(truncateSummary(text, args.isJson))
            return if (valid) 0 else 1
        }

        private fun resolveVerifyFilePath(args: CliArgs): String {
            if (args.paths.isNotEmpty()) return args.paths.first()
            if (args.target.isNotEmpty()) return args.target
            return args.fileKey ?: ""
        }

        private fun buildVerifyRemediationArgs(args: CliArgs, filePath: String): JsonObject = buildJsonObject {
            put("action", "verify_remediation")
            put("project", args.project)
            put("file_path", filePath)
            put("before_snippet", args.beforeSnippet ?: "")
            put("after_snippet", args.afterSnippet ?: "")
            if (!args.ruleIdArg.isNullOrBlank()) put("rule_id", args.ruleIdArg)
            if (!args.targetNodeArg.isNullOrBlank()) put("target_node_id", args.targetNodeArg)
            if (!args.testCmdArg.isNullOrBlank()) put("test_command", args.testCmdArg)
            put("dry_run", !args.applyRemediation)
        }

        private fun runQueryCommand(server: AtlasMcpServer, args: CliArgs): Int {
            if (args.action == "explore") {
                return runExploreQuery(server, args)
            }
            return runStandardQuery(server, args)
        }

        private fun runExploreQuery(server: AtlasMcpServer, args: CliArgs): Int {
            val queryText = if (args.target.isNotEmpty()) args.target else args.paths.joinToString(" ")
            val toolArgs = buildJsonObject {
                put("action", "explore")
                put("project", args.project)
                put("target", queryText)
                put("limit", args.limit)
            }
            val res = server.callTool("atlas_query", toolArgs)
            if (args.isJson) {
                println(jsonPretty.encodeToString(res))
            } else {
                val text = res["text"]?.jsonPrimitive?.contentOrNull ?: jsonPretty.encodeToString(res)
                println(text)
            }
            return 0
        }

        private fun runStandardQuery(server: AtlasMcpServer, args: CliArgs): Int {
            val toolArgs = buildStandardQueryArgs(args)
            val res = server.callTool("atlas_query", toolArgs)
            val data = if (args.isJson) loadSpilledData(res) else res
            printStandardQueryResult(data, args.isJson)
            return 0
        }

        private fun buildStandardQueryArgs(args: CliArgs): JsonObject = buildJsonObject {
            put("action", args.action)
            put("project", args.project)
            put("target", args.target)
            put("depth", args.depth)
            if (args.secondaryTarget.isNotEmpty()) put("secondary_target", args.secondaryTarget)
            if (args.includeSource) put("include_source", true)
            if (!args.outcome.isNullOrBlank()) put("outcome", args.outcome)
            if (!args.note.isNullOrBlank()) put("note", args.note)
        }

        private fun printStandardQueryResult(data: JsonObject, isJson: Boolean) {
            if (isJson) {
                println(jsonPretty.encodeToString(data))
            } else {
                val compact = jsonCompact.encodeToString(data)
                println(truncateSummary(compact, false))
            }
        }

        internal fun readStdin(): String {
            val sb = StringBuilder()
            while (true) {
                val line = readlnOrNull() ?: break
                sb.append(line).append('\n')
            }
            return sb.toString()
        }

        internal fun loadSpilledData(payload: JsonObject): JsonObject =
            CliOutputFormatter.loadSpilledData(payload)

        fun truncateSummary(text: String, isJson: Boolean): String =
            CliOutputFormatter.truncateSummary(text, isJson, SUMMARY_CEILING_BYTES)

        private fun runArchTriage(
            server: AtlasMcpServer,
            args: CliArgs,
            targets: List<String>
        ): JsonObject {
            val toolArgs = buildJsonObject {
                put("action", if (args.applyRemediation) "remedy_architecture" else "verify_architecture")
                put("project", args.project)
                if (targets.isNotEmpty()) putJsonArray("targets") { targets.forEach { add(it) } }
                if (args.applyRemediation) {
                    put("dry_run", false)
                    put("split", args.splitBoost003)
                    put("make_private", args.makePrivateBoost003)
                    if (!args.testCmdArg.isNullOrBlank()) put("test_command", args.testCmdArg)
                    put("limit", args.limit)
                }
            }
            return loadSpilledData(server.callTool("atlas_audit", toolArgs))
        }

        private fun runCompTriage(
            server: AtlasMcpServer,
            args: CliArgs
        ): JsonObject {
            val toolArgs = buildJsonObject {
                put("action", "complexity_doctor")
                put("project", args.project)
                if (args.target.isNotEmpty()) put("target", args.target)
                put("limit", args.limit)
                put("apply", args.applyRemediation)
                if (args.thresholdArg != null) put("threshold", args.thresholdArg)
                if (!args.testCmdArg.isNullOrBlank()) put("test_command", args.testCmdArg)
            }
            return loadSpilledData(server.callTool("atlas_audit", toolArgs))
        }

        private fun runComposeTriage(
            server: AtlasMcpServer,
            project: String,
            target: String,
            limit: Int
        ): JsonObject? {
            val hasCompose = try {
                server.store.listComposeNodes(project).isNotEmpty()
            } catch (_: Exception) {
                false
            }
            if (!hasCompose) return null
            val args = buildJsonObject {
                put("action", "compose_doctor")
                put("project", project)
                put("limit", limit)
                if (target.isNotEmpty()) putJsonArray("targets") { add(target) }
            }
            return loadSpilledData(server.callTool("atlas_audit", args))
        }

        private fun extractArchViolationCount(data: JsonObject, apply: Boolean): Int {
            if (apply) return data["total_violations"]?.jsonPrimitive?.intOrNull ?: 0
            val b = extractAuditArraySize(data, "single_declaration_violations", "singleDeclarationViolations")
            val l = extractAuditArraySize(data, "layer_inversions", "layerInversions")
            val p = extractAuditArraySize(data, "plan_3d_collisions", "plan3dCollisions")
            val s = extractAuditArraySize(data, "skill_violations", "skillViolations")
            return b + l + p + s
        }

        private fun resolveFilterPaths(paths: List<String>, target: String): List<String> =
            paths.ifEmpty { if (target.isNotEmpty()) listOf(target) else emptyList() }

        private fun runGeneralDoctorCommand(
            server: AtlasMcpServer,
            args: CliArgs
        ): Int {
            val filterPaths = resolveFilterPaths(args.paths, args.target)
            val archData = runArchTriage(server, args, filterPaths)
            val compData = runCompTriage(server, args)
            val composeData = runComposeTriage(server, args.project, args.target, args.limit)

            if (args.isJson) {
                CliOutputFormatter.printDoctorJson(args.project, args.applyRemediation, archData, compData, composeData)
            } else {
                val archViolations = extractArchViolationCount(archData, args.applyRemediation)
                val compPrescriptions = compData["total_prescriptions"]?.jsonPrimitive?.intOrNull ?: 0
                val composePrescriptions = composeData?.get("total_prescriptions")?.jsonPrimitive?.intOrNull ?: 0
                val verified = compData["verified_count"]?.jsonPrimitive?.intOrNull ?: 0
                CliOutputFormatter.printDoctorTriageOutput(args.project, args.applyRemediation, archViolations, compPrescriptions, composePrescriptions, verified)
            }
            return 0
        }

        private fun buildComplexityDoctorToolArgs(
            project: String,
            target: String,
            limit: Int,
            apply: Boolean,
            testCmd: String?,
            threshold: Int?
        ): JsonObject = buildJsonObject {
            put("action", "complexity_doctor")
            put("project", project)
            if (target.isNotEmpty()) put("target", target)
            put("limit", limit)
            put("apply", apply)
            if (threshold != null) put("threshold", threshold)
            if (!testCmd.isNullOrBlank()) put("test_command", testCmd)
        }

        private fun runComplexityDoctorCommand(
            server: AtlasMcpServer,
            args: CliArgs
        ): Int {
            val toolArgs = buildComplexityDoctorToolArgs(
                args.project, args.target, args.limit, args.applyRemediation, args.testCmdArg, args.thresholdArg
            )
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)

            if (args.isJson) {
                println(jsonPretty.encodeToString(data))
            } else {
                CliOutputFormatter.printComplexityDoctorOutput(args.project, data, args.limit)
            }
            return 0
        }

        private fun putIfNotNull(builder: JsonObjectBuilder, key: String, value: Int?) {
            if (value != null) builder.put(key, value)
        }

        private fun putIfNotNull(builder: JsonObjectBuilder, key: String, value: String?) {
            if (value != null) builder.put(key, value)
        }

        private fun buildComplexityAuditToolArgs(
            args: CliArgs,
            filterPaths: List<String>,
            effectiveRatchet: Int?,
            effectiveBaselineFile: String?
        ): JsonObject = buildJsonObject {
            put("action", "audit_complexity")
            put("project", args.project)
            if (filterPaths.isNotEmpty()) {
                putJsonArray("targets") { filterPaths.forEach { add(it) } }
            }
            putIfNotNull(this, "threshold", args.thresholdArg)
            putIfNotNull(this, "max_cyclomatic", args.maxCyclomaticArg)
            putIfNotNull(this, "warn_params", args.warnParamsArg)
            putIfNotNull(this, "max_params", args.maxParamsArg)
            putIfNotNull(this, "compose_warn_params", args.composeWarnParamsArg)
            putIfNotNull(this, "compose_max_params", args.composeMaxParamsArg)
            if (!args.checkParams) put("check_params", false)
            putIfNotNull(this, "ratchet", effectiveRatchet)
            putIfNotNull(this, "high_ratchet", args.highRatchetArg)
            putIfNotNull(this, "param_high_ratchet", args.paramHighRatchetArg)
            putIfNotNull(this, "baseline", effectiveBaselineFile)
        }

        private fun saveComplexityBaselineFile(
            args: CliArgs,
            data: JsonObject,
            thresh: Int
        ) {
            val total = extractIntField(data, "total_violations")
            val high = extractIntField(data, "high_severity_count")
            val warn = extractIntField(data, "warning_count")
            val paramHigh = extractIntField(data, "param_high_count")
            val maxCyc = data["max_cyclomatic"]?.jsonPrimitive?.intOrNull ?: (args.maxCyclomaticArg ?: 15)
            val targetPath = args.saveBaselinePath ?: if (args.workspace == ".") "complexity-baseline.json" else "${args.workspace}/complexity-baseline.json"
            val baselineObj = buildJsonObject {
                put("project", args.project)
                put("ratchet", total)
                put("high_ratchet", high)
                put("param_high_ratchet", paramHigh)
                put("threshold", thresh)
                put("max_cyclomatic", maxCyc)
                put("total_violations", total)
                put("high_severity_count", high)
                put("warning_count", warn)
            }
            PlatformFiles.writeText(targetPath, jsonPretty.encodeToString(baselineObj) + "\n")
            if (!args.isJson) {
                println("[Complexity Audit] Saved baseline to $targetPath ($total violations)")
            }
        }

        private fun resolveAuditFilterPaths(paths: List<String>, target: String): List<String> =
            if (paths.isNotEmpty()) paths else (if (target.isNotEmpty()) listOf(target) else emptyList())

        private fun resolveEffectiveAuditRatchet(args: CliArgs): Pair<Int?, String?> {
            val parsedBaselineInt = args.baselineArg?.toIntOrNull()
            val effectiveRatchet = args.ratchetArg ?: parsedBaselineInt
            val effectiveBaselineFile = if (parsedBaselineInt == null) args.baselineArg else null
            return Pair(effectiveRatchet, effectiveBaselineFile)
        }

        private fun runComplexityAuditCommand(
            server: AtlasMcpServer,
            args: CliArgs
        ): Int {
            val filterPaths = resolveAuditFilterPaths(args.paths, args.target)
            val (effectiveRatchet, effectiveBaselineFile) = resolveEffectiveAuditRatchet(args)

            val toolArgs = buildComplexityAuditToolArgs(
                args, filterPaths, effectiveRatchet, effectiveBaselineFile
            )
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            val passed = data["passed"]?.jsonPrimitive?.booleanOrNull ?: true
            val thresh = data["threshold"]?.jsonPrimitive?.intOrNull ?: (args.thresholdArg ?: 10)

            if (args.saveBaseline) {
                saveComplexityBaselineFile(args, data, thresh)
            }

            val text = CliOutputFormatter.renderComplexityAuditOutput(data, args.project, passed, thresh, args.isJson)
            println(truncateSummary(text, args.isJson))
            return if (args.saveBaseline || passed) 0 else 1
        }

        private fun buildComplexityQueryArgs(
            project: String,
            target: String,
            threshold: Int?,
            limit: Int
        ): JsonObject = buildJsonObject {
            put("action", "complexity")
            put("project", project)
            if (target.isNotEmpty()) put("target", target)
            if (threshold != null) put("threshold", threshold)
            put("limit", limit)
        }

        private fun runComplexityQueryCommand(
            server: AtlasMcpServer,
            project: String,
            target: String,
            threshold: Int?,
            limit: Int,
            isJson: Boolean
        ): Int {
            val toolArgs = buildComplexityQueryArgs(project, target, threshold, limit)
            val res = server.callTool("atlas_query", toolArgs)
            val data = loadSpilledData(res)
            val results = data["results"]?.jsonArray ?: JsonArray(emptyList())

            if (isJson) {
                println(jsonPretty.encodeToString(data))
            } else {
                CliOutputFormatter.printComplexityQueryTable(results)
            }
            return 0
        }

        private fun runInitCommand(args: CliArgs): Int {
            val engine = WorkspaceInitEngine(args.workspace)
            val effectiveProject = engine.resolveProjectSlug(args.project)
            val result = engine.initHook(
                project = effectiveProject,
                hookType = args.hookTypeArg,
                force = args.forceFlag,
                threshold = args.thresholdArg ?: 15,
                dryRun = args.isDryRun
            )
            CliOutputFormatter.renderInitOutput(effectiveProject, result, args.isJson)
            return if (result.success) 0 else 1
        }

        private fun resolveMigrateScanPaths(server: AtlasMcpServer, paths: List<String>, target: String): List<String> = when {
            paths.isNotEmpty() -> paths
            target.isNotEmpty() -> listOf(target)
            else -> listOf(server.workspaceRoot)
        }

        private fun runMigrateToolsCommand(
            server: AtlasMcpServer,
            paths: List<String>,
            target: String,
            applyRemediation: Boolean,
            isJson: Boolean
        ): Int {
            val scanPaths = resolveMigrateScanPaths(server, paths, target)
            val report = ToolMigrationEngine.scanAndMigrate(scanPaths, apply = applyRemediation)
            if (isJson) {
                println(jsonPretty.encodeToString(report))
            } else {
                val mode = if (applyRemediation) "applied" else "dry-run"
                val sample = CliOutputFormatter.formatLegacyToolSample(report.matches)
                val text = "[Atlas Migrate Tools] mode=$mode occurrences=${report.totalOccurrences} files=${report.filesAffected}$sample"
                println(truncateSummary(text, false))
            }
            return if (applyRemediation || report.totalOccurrences == 0) 0 else 1
        }

        internal fun printHelp() {
            println(
                """
                Atlas: Sovereign Polyglot AST & Vault Knowledge Graph Engine (macOS Native)

                Usage: atlas <command> [options]
                       atlas figma <command> [options]
                       atlas compose <command> [options]

                Code Commands:
                  init               Configure and install git pre-commit/pre-push hooks in repository
                  index              Incrementally index workspace AST symbols and markdown notes
                  query              Query AST symbols, callers, callees, blast radius, or status
                  search             Search indexed symbols and code snippets across project
                  callers            List upstream callers for a symbol or file
                  callees            List downstream callees for a symbol or file
                  impact             Compute downstream blast-radius and impact tree for a symbol
                  node               Inspect detailed node attributes, signature, and live source
                  files              List indexed source files and symbol distribution
                  status             Report indexed graph status, node counts, and storage metrics
                  explore            Explore codebase semantics and matched files for query
                  context            Synthesize 3D Concurrency Scope and structural skeletons for task
                  synthesize-scope   Synthesize 3D Concurrency Scope (files, modules, architecture)
                  audit              Verify Clean Architecture layers, Single Declaration Principle, or complexity
                  fix                Remediate architecture violations (BOOST-003 AST split & layer inversions)
                  doctor             Synthesize and apply autonomous remediation prescriptions
                  dead-code          Detect dead code unreferenced across indexed modules
                  migrate-tools      Detect and optionally migrate deprecated codegraph_* tool calls
                  record-memory      Record execution outcome annotation for a node into work memory
                  verify-remediation Verify and optionally apply autonomous remediation with pre-flight & post-verification checks
                  serve-mcp          Run JSON-RPC 2.0 MCP server over stdio
                  version            Print version information

                Compose Commands:
                  compose index              Index Kotlin @Composable functions, stability_config.conf, compiler reports, and traces
                  compose query              Query Compose stability (summary, cascade, blame)
                  compose audit              Run 12-rule Compose Gate 1 compliance & stability audit (exit code 0/1)
                  compose doctor             Synthesize prioritized write-site verified Compose remediation previews
                  compose synthesize-scope   Synthesize Compose-aware 3D Concurrency Scope (shared models, CompositionLocals)

                Complexity Commands:
                  atlas query --action complexity [--target <symbol>] [--threshold <int>]
                  atlas audit --complexity [--threshold <int>]
                  atlas doctor --complexity [--target <symbol>] [--apply] [--test-cmd <cmd>]

                Figma Commands:
                  figma index              Index Figma document snapshot and DTCG design tokens
                  figma query              Query Figma nodes (symbol, callers, impact, spatial, god_nodes, shortest_path)
                  figma audit              Run 12 deterministic SQL design audits (exit code 0/1)
                  figma synthesize-scope   Synthesize 3D Concurrency Scope (spatial, components, tokens)

                General Options:
                  --version, -v            Print version information
                  --help, -h               Print help information
                  --project <name>         Target project slug (default: organisation)
                  --workspace <dir>        Workspace root directory (default: .)
                  --db <path>              Custom SQLite database path
                  --cache-profile <p>      'enterprise' (user cache) or 'in-repo' (.atlas/)
                  --json                   Emit full formatted JSON output
                  --summary                Emit compact summary <= 400 bytes (default)
                  --hook-type <type>       Git hook type to install ('pre-commit' or 'pre-push'; default: pre-commit)
                  --force                  Force overwrite of existing git hook
                  --dry-run                Preview generated git hook script without disk mutations
                  --outcome <status>       Outcome status for memory recording (default: useful)
                  --note <text>            Descriptive note or rationale for memory recording

                Audit Options:
                  --complexity             Run Gate 1 function complexity audit rule (exit code 0/1)
                  --threshold <int>        Cognitive complexity threshold for audit or query (default: 10)
                  --ratchet <int>          Maximum allowed violations for complexity audit ratchet
                  --high-ratchet <int>     Maximum allowed high severity violations for complexity audit ratchet
                  --param-high-ratchet <n> Maximum allowed high parameter count violations for complexity audit ratchet
                  --baseline <int|file>    Explicit baseline violation count or path to baseline JSON file
                  --save-baseline [path]   Save current complexity violations as baseline JSON file
                  --description-mode <m>   Filter mode for description suggestions (domain-only, all, off; default: domain-only)
                  --no-description-check   Disable top-level description suggestions check
                  --legacy-tools           Audit or fix deprecated codegraph_* tool calls across files

                Query & Compose Options:
                  --target <sym>           Symbol name, node name, URL/deep-link, or ID
                  --url <url>              Shorthand for '--action url --target <url>' (HTTP routes, URLs, or deep links)
                  --secondary-target <sym> Target for shortest_path
                  --action <action>        Query action (symbol, callers, callees, impact, url, complexity, summary, cascade, blame)
                  --depth <int>            Traversal depth (default: 2)
                  --limit <int>            Max prescriptions for doctor (default: 10)
                  --paths <p1> [p2...]     Subpaths to index or seed targets for scoping
                  --include-source         Include live disk source lines in query
                  --compose                Run Compose Gate 1 audit when passed to 'atlas audit --compose'
                  --stability-config <p>   Path to Compose stability_config.conf
                  --compiler-reports <d>   Path to Compose Compiler metrics directory or report file
                  --trace-log <p>          Path to @TraceRecomposition JSONL/Logcat telemetry log
                  --strong-skipping <bool> Evaluate under Kotlin 2.0+ Strong Skipping mode (default: true)

                Remediation Options:
                  --complexity             Run complexity doctor mode
                  --split                  Enable automated AST file-splitting for BOOST-003 (default: true)
                  --no-split               Disable AST file-splitting
                  --make-private           Convert unreferenced secondary declarations to private
                  --dry-run                Preview remediations without modifying disk (default)
                  --apply                  Commit verified remediation to disk
                  --before <snippet>       Original code snippet to replace
                  --after <snippet>        Target replacement snippet
                  --rule <id>              Target rule ID (e.g. BOOST-003, ARCH-LAYER-INVERSION)
                  --target-node <id>       Target composable/symbol ID for scoped rule verification
                  --test-cmd <cmd>         Shell command to verify test suite preservation

                Figma Options:
                  --file <key>             Figma file key
                  --snapshot <path>        Path to Figma document JSON snapshot
                  --tokens <path>          Path to W3C DTCG design tokens JSON
                  --stdin                  Read Figma document snapshot from stdin
                  --bounds <x1,x2,y1,y2>   Bounding box coordinates for spatial query
                  --seeds <s1> [s2...]     Seed node names or IDs for 3D scope synthesis
                """.trimIndent()
            )
        }
    }
}
