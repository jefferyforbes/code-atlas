package atlas.cli

import atlas.engine.AutonomousRemediationVerifier
import atlas.engine.CodegraphCompatibilityEngine
import atlas.engine.FigmaDesignAuditor
import atlas.engine.ToolMigrationEngine
import atlas.engine.WorkspaceInitEngine
import atlas.extract.FigmaSnapshotExtractor
import atlas.extract.UrlTargetResolver
import atlas.mcp.AtlasMcpServer
import atlas.model.FigmaNode
import atlas.model.FigmaSpatialBounds
import atlas.model.FigmaVariable
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
import kotlinx.serialization.json.buildJsonArray
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
        const val VERSION = "0.7.0"
        const val SUMMARY_CEILING_BYTES = 400

        private val jsonPretty = Json { prettyPrint = true }
        private val jsonCompact = Json { prettyPrint = false }

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
            val isJson = argv.contains("--json")
            if (isJson) {
                println(jsonPretty.encodeToString(buildJsonObject {
                    put("name", "atlas")
                    put("version", VERSION)
                }))
            } else {
                println("atlas $VERSION")
            }
            return 0
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

        private fun dispatchFigma(server: AtlasMcpServer, args: CliArgs): Int {
            return runFigmaCommand(server, args)
        }

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

        private fun dispatchCompose(server: AtlasMcpServer, args: CliArgs): Int {
            return runComposeCommand(server, args)
        }

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
            val text = if (args.isJson) jsonPretty.encodeToString(res) else formatIndexSummary(args.project, res)
            println(truncateSummary(text, args.isJson))
            return 0
        }

        private fun resolveUserHome(path: String): String =
            if (path.startsWith("~")) path.replaceFirst("~", PlatformFiles.userHome()) else path

        private fun formatIndexSummary(project: String, res: JsonObject): String {
            val fTotal = res["files"]?.jsonPrimitive?.intOrNull ?: 0
            val fIdx = res["files_indexed"]?.jsonPrimitive?.intOrNull ?: 0
            val fSkip = res["files_skipped"]?.jsonPrimitive?.intOrNull ?: 0
            val nTotal = res["nodes"]?.jsonPrimitive?.intOrNull ?: 0
            val eTotal = res["edges"]?.jsonPrimitive?.intOrNull ?: 0
            return "[Atlas] project=$project files=$fTotal (indexed=$fIdx, skipped=$fSkip) nodes=$nTotal edges=$eTotal"
        }

        private fun runSynthesizeScopeCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val seedList = if (args.paths.isNotEmpty()) args.paths else (if (args.target.isNotEmpty()) listOf(args.target) else emptyList())
            val toolArgs = buildJsonObject {
                put("action", "synthesize_3d_scope")
                put("project", args.project)
                putJsonArray("targets") { seedList.forEach { add(it) } }
            }
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            val text = formatSynthesizeScopeOutput(data, args.project, seedList, args.isJson)
            println(truncateSummary(text, args.isJson))
            return 0
        }

        private fun formatSynthesizeScopeOutput(data: JsonObject, project: String, seedList: List<String>, isJson: Boolean): String {
            if (isJson) return jsonPretty.encodeToString(data)
            val fCount = (data["files"] ?: data["files_count"])?.jsonArray?.size ?: 0
            val mods = extractAuditStringList(data, "modules", "impacted_modules")
            val arch = extractAuditStringList(data, "architecture", "impacted_layers")
            val collisions = extractAuditStringList(data, "colliding_plans", "collidingPlans")
            return "[3D Scope] project=$project seeds=$seedList files=$fCount modules=$mods arch=$arch collisions=$collisions"
        }

        private fun extractAuditStringList(data: JsonObject, key1: String, key2: String): List<String> {
            val element = data[key1] ?: data[key2]
            return element?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
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
            if (isJson) {
                println(jsonPretty.encodeToString(report))
            } else {
                val passed = report.totalOccurrences == 0
                val sample = if (report.matches.isNotEmpty()) {
                    val s = report.matches.take(5).joinToString("; ") { "${it.legacyTool} in ${it.file}:${it.line}" }
                    " top=[$s]"
                } else ""
                val text = "[Audit Legacy Tools] passed=$passed occurrences=${report.totalOccurrences} files=${report.filesAffected}$sample"
                println(truncateSummary(text, false))
            }
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
            return bViolations == 0 && lInversions == 0 && pCollisions == 0 && cViolations == 0 && sViolations == 0
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
            val text = if (isJson) {
                buildJsonObject {
                    put("project", project)
                    put("passed", passed)
                    put("rule", ruleId ?: "ALL")
                    put("violations_count", filteredViolations.size)
                    put("violations", filteredViolations)
                }.let { jsonPretty.encodeToString(it) }
            } else {
                val count001 = filteredViolations.count { it.jsonObject["rule"]?.jsonPrimitive?.content == "SKILL-001" }
                val count002 = filteredViolations.count { it.jsonObject["rule"]?.jsonPrimitive?.content == "SKILL-002" }
                val count003 = filteredViolations.count { it.jsonObject["rule"]?.jsonPrimitive?.content == "SKILL-003" }
                "[Audit] project=$project passed=$passed skill_violations=${filteredViolations.size} (SKILL-001: $count001, SKILL-002: $count002, SKILL-003: $count003)"
            }
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
            val passed = isArchitectureAuditPassed(data, bViolations, lInversions, pCollisions, cViolations, sViolations)
            val text = if (args.isJson) {
                jsonPretty.encodeToString(data)
            } else {
                "[Audit] project=${args.project} passed=$passed boost003_violations=$bViolations layer_inversions=$lInversions plan_3d_collisions=$pCollisions skill_violations=$sViolations complexity_violations=$cViolations description_suggestions=$dSuggestions"
            }
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
            renderFixLegacyToolsOutput(report, applyRemediation, isJson)
            return if (applyRemediation || report.totalOccurrences == 0) 0 else 1
        }

        private fun renderFixLegacyToolsOutput(report: ToolMigrationEngine.MigrationReport, applyRemediation: Boolean, isJson: Boolean) {
            if (isJson) {
                println(jsonPretty.encodeToString(report))
                return
            }
            val mode = if (applyRemediation) "applied" else "dry-run"
            val sample = formatLegacyToolSample(report.matches)
            val text = "[Fix Legacy Tools] mode=$mode occurrences=${report.totalOccurrences} files=${report.filesAffected}$sample"
            println(truncateSummary(text, false))
        }

        private fun formatLegacyToolSample(matches: List<ToolMigrationEngine.MigrationMatch>): String {
            if (matches.isEmpty()) return ""
            val s = matches.take(5).joinToString("; ") { "${it.legacyTool} in ${it.file}:${it.line}" }
            return " top=[$s]"
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

            val text = formatFixArchitectureText(data, args.project, dryRun, filterPaths, prescriptions, args.isJson)
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

        private fun formatFixArchitectureText(
            data: JsonObject,
            project: String,
            dryRun: Boolean,
            filterPaths: List<String>,
            prescriptions: List<JsonElement>,
            isJson: Boolean
        ): String {
            if (isJson) return jsonPretty.encodeToString(data)
            val total = extractIntField(data, "total_violations")
            val bCount = extractIntField(data, "boost003_count")
            val lCount = extractIntField(data, "layer_inversion_count")
            val vCount = extractIntField(data, "verified_count")
            val aCount = extractIntField(data, "applied_count")
            val mode = if (dryRun) "dry-run" else "applied"
            val header = "[Remediation] project=$project mode=$mode violations=$total boost003=$bCount layer_inversions=$lCount verified=$vCount applied=$aCount"
            val details = formatFixPrescriptionsDetails(filterPaths, prescriptions)
            return header + details
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
            val preview = diff.lines().take(20).joinToString("\n") { "    $it" }
            return "$line\n$preview"
        }

        private fun formatFixPrescriptionsDetails(filterPaths: List<String>, prescriptions: List<JsonElement>): String {
            if (prescriptions.isEmpty() || (filterPaths.isEmpty() && prescriptions.size > 5)) return ""
            val lines = prescriptions.take(5).map { formatSinglePrescriptionLine(it) }
            return "\n" + lines.joinToString("\n")
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
            val count = data["dead_code_count"]?.jsonPrimitive?.intOrNull ?: 0
            val names = data["dead_nodes"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content } ?: emptyList()
            val text = if (args.isJson) {
                jsonPretty.encodeToString(data)
            } else {
                "[Dead Code] project=${args.project} dead_symbols=$count top=[${names.take(5).joinToString(", ")}]"
            }
            println(truncateSummary(text, args.isJson))
            return 0
        }

        private fun runRecordMemoryCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val targetNode = args.target.ifEmpty { args.paths.firstOrNull() ?: "" }
            val toolArgs = buildRecordMemoryArgs(args, targetNode)
            val res = server.callTool("atlas_audit", toolArgs)
            val text = formatRecordMemoryOutput(res, args.outcome, targetNode, args.isJson)
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

        private fun formatRecordMemoryOutput(res: JsonObject, outcome: String?, targetNode: String, isJson: Boolean): String {
            if (isJson) return jsonPretty.encodeToString(res)
            val annId = res["annotation_id"]?.jsonPrimitive?.content ?: "unknown"
            val nId = res["node_id"]?.jsonPrimitive?.content ?: targetNode
            val out = res["outcome"]?.jsonPrimitive?.content ?: (outcome ?: "useful")
            return "[Atlas Memory] Recorded outcome=$out node=$nId id=$annId"
        }

        private fun runVerifyRemediationCommand(server: AtlasMcpServer, args: CliArgs): Int {
            val filePath = resolveVerifyFilePath(args)
            val toolArgs = buildVerifyRemediationArgs(args, filePath)
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            val valid = data["valid"]?.jsonPrimitive?.booleanOrNull ?: false
            val text = formatVerifyRemediationOutput(data, filePath, valid, args.isJson)
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

        private fun formatVerifyRemediationOutput(data: JsonObject, filePath: String, valid: Boolean, isJson: Boolean): String {
            if (isJson) return jsonPretty.encodeToString(data)
            val phase = data["phase"]?.jsonPrimitive?.content ?: "UNKNOWN"
            val msg = data["message"]?.jsonPrimitive?.content ?: ""
            return "[Remediation Verification] valid=$valid phase=$phase file=$filePath message=\"$msg\""
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

        private fun resolveFigmaFileKey(
            server: AtlasMcpServer,
            fileKeyArg: String?,
            parsedFromFileArg: UrlTargetResolver.FigmaDeepLink?,
            parsedFromTarget: UrlTargetResolver.FigmaDeepLink?,
            parsedSeedUrls: List<UrlTargetResolver.FigmaDeepLink>
        ): String {
            return parsedFromFileArg?.fileKey
                ?: parsedFromTarget?.fileKey
                ?: parsedSeedUrls.firstOrNull()?.fileKey
                ?: fileKeyArg
                ?: server.store.getLatestFigmaFileKey()
                ?: "default"
        }

        private fun resolveFigmaSeeds(
            seeds: List<String>,
            normTarget: String,
            fileArgNodeId: String?
        ): List<String> {
            if (seeds.isNotEmpty()) {
                return seeds.map { s -> UrlTargetResolver.parseFigmaUrl(s)?.nodeId ?: UrlTargetResolver.normalizeFigmaNodeId(s) }
            }
            if (normTarget.isNotEmpty()) {
                return listOf(normTarget)
            }
            if (fileArgNodeId != null) {
                return listOf(fileArgNodeId)
            }
            return emptyList()
        }

        private fun readFigmaAstJson(isStdin: Boolean, snapshotPath: String?): String? {
            if (isStdin) return readStdin()
            if (snapshotPath != null) {
                if (!PlatformFiles.exists(snapshotPath)) {
                    println("Error: Snapshot file not found at $snapshotPath")
                    return null
                }
                return PlatformFiles.readText(snapshotPath)
            }
            println("Error: atlas figma index requires --snapshot <path> or --stdin")
            return null
        }

        private fun runFigmaIndexSubcommand(
            server: AtlasMcpServer,
            args: CliArgs,
            fileKey: String
        ): Int {
            val astJson = readFigmaAstJson(args.isStdin, args.snapshotPath) ?: return 1
            val tokensPath = args.tokensPath
            val tokensJson = if (tokensPath != null && PlatformFiles.exists(tokensPath)) {
                PlatformFiles.readText(tokensPath)
            } else null

            val extractor = FigmaSnapshotExtractor()
            val snapshot = extractor.ingest(server.store, fileKey, astJson, tokensJson)

            if (args.isJson) {
                val resObj = buildJsonObject {
                    put("file_key", fileKey)
                    put("nodes_indexed", snapshot.nodes.size)
                    put("variables_indexed", snapshot.variables.size)
                    put("edges_indexed", snapshot.edges.size)
                    put("document_name", snapshot.document.name)
                }
                println(jsonPretty.encodeToString(resObj))
            } else {
                val text = "[Atlas Figma] Indexed project=${args.project} fileKey=$fileKey nodes=${snapshot.nodes.size} variables=${snapshot.variables.size} edges=${snapshot.edges.size}"
                println(truncateSummary(text, false))
            }
            return 0
        }

        private fun renderFigmaSymbolOutput(
            fileKey: String,
            normTarget: String,
            nodes: List<FigmaNode>,
            variable: FigmaVariable?,
            isJson: Boolean
        ) {
            if (isJson) {
                val jsonElement = buildJsonObject {
                    put("file_key", fileKey)
                    put("target", normTarget)
                    putJsonArray("nodes") {
                        nodes.forEach { n -> add(jsonCompact.parseToJsonElement(jsonCompact.encodeToString(n))) }
                    }
                    if (variable != null) {
                        put("variable", jsonCompact.parseToJsonElement(jsonCompact.encodeToString(variable)))
                    }
                }
                println(jsonPretty.encodeToString(jsonElement))
                return
            }
            val hasVar = if (variable != null) 1 else 0
            val count = nodes.size + hasVar
            val names = nodes.map { it.name } + listOfNotNull(variable?.name)
            val text = "[Figma Query: symbol] target=$normTarget found=$count matches=[${names.take(3).joinToString(", ")}]"
            println(truncateSummary(text, false))
        }

        private fun queryFigmaSymbol(server: AtlasMcpServer, fileKey: String, normTarget: String, isJson: Boolean): Int {
            val node = server.store.queryFigmaNodeById(fileKey, normTarget)
            val nodes = if (node != null) listOf(node) else server.store.queryFigmaNodesByName(fileKey, normTarget)
            val variable = if (nodes.isEmpty()) server.store.queryFigmaVariableByName(fileKey, normTarget) ?: server.store.queryFigmaVariableById(fileKey, normTarget) else null
            renderFigmaSymbolOutput(fileKey, normTarget, nodes, variable, isJson)
            return 0
        }

        private fun queryFigmaCallers(server: AtlasMcpServer, fileKey: String, normTarget: String, depth: Int, isJson: Boolean): Int {
            val callers = server.store.queryFigmaCallers(fileKey, normTarget, depth)
            if (isJson) {
                println(jsonPretty.encodeToString(callers))
            } else {
                val names = callers.map { it.name }
                val text = "[Figma Query: callers] target=$normTarget callers=${callers.size} instances=[${names.take(3).joinToString(", ")}]"
                println(truncateSummary(text, false))
            }
            return 0
        }

        private fun queryFigmaImpact(server: AtlasMcpServer, fileKey: String, normTarget: String, depth: Int, isJson: Boolean): Int {
            val impact = server.store.resolveVariableBlastRadius(fileKey, normTarget, depth)
            if (isJson) {
                println(jsonPretty.encodeToString(impact))
            } else {
                val vars = impact.impactedVariables.map { it.name }
                val bound = impact.boundNodes.map { it.name }
                val text = "[Figma Query: impact] target=$normTarget impacted_vars=${vars.size} bound_nodes=${bound.size} vars=[${vars.take(3).joinToString(", ")}]"
                println(truncateSummary(text, false))
            }
            return 0
        }

        private fun parseSpatialBounds(server: AtlasMcpServer, fileKey: String, normTarget: String, boundsArg: String?): FigmaSpatialBounds {
            if (boundsArg != null) {
                val parts = boundsArg.split(',').filter { it.isNotBlank() }.map { it.trim().toDoubleOrNull() ?: 0.0 }
                if (parts.size >= 4) {
                    return FigmaSpatialBounds(parts[0], parts[1], parts[2], parts[3])
                }
                return FigmaSpatialBounds(0.0, 0.0, 0.0, 0.0)
            }
            val targetNode = server.store.queryFigmaNodeById(fileKey, normTarget)
                ?: server.store.queryFigmaNodesByName(fileKey, normTarget).firstOrNull()
            return targetNode?.spatialBounds ?: FigmaSpatialBounds(0.0, 0.0, 0.0, 0.0)
        }

        private fun queryFigmaSpatial(server: AtlasMcpServer, fileKey: String, normTarget: String, boundsArg: String?, isJson: Boolean): Int {
            val bounds = parseSpatialBounds(server, fileKey, normTarget, boundsArg)
            val intersecting = server.store.queryFigmaSpatialIntersection(fileKey, bounds)
            if (isJson) {
                println(jsonPretty.encodeToString(intersecting))
            } else {
                val names = intersecting.map { it.name }
                val text = "[Figma Query: spatial] bounds=[${bounds.minX.toInt()}..${bounds.maxX.toInt()}, ${bounds.minY.toInt()}..${bounds.maxY.toInt()}] nodes=${intersecting.size} items=[${names.take(3).joinToString(", ")}]"
                println(truncateSummary(text, false))
            }
            return 0
        }

        private fun queryFigmaGodNodes(server: AtlasMcpServer, fileKey: String, isJson: Boolean): Int {
            val godNodes = server.store.queryFigmaGodNodes(fileKey, limit = 10)
            if (isJson) {
                val arr = buildJsonArray {
                    for ((node, count) in godNodes) {
                        add(buildJsonObject {
                            put("node_id", node.nodeId)
                            put("name", node.name)
                            put("type", node.nodeType)
                            put("references", count)
                        })
                    }
                }
                println(jsonPretty.encodeToString(arr))
            } else {
                val items = godNodes.map { "${it.first.name} (${it.second} refs)" }
                val text = "[Figma Query: god_nodes] fileKey=$fileKey top_nodes=${godNodes.size} nodes=[${items.take(3).joinToString(", ")}]"
                println(truncateSummary(text, false))
            }
            return 0
        }

        private fun queryFigmaShortestPath(server: AtlasMcpServer, fileKey: String, normTarget: String, normSecondaryTarget: String, depth: Int, isJson: Boolean): Int {
            val path = server.store.findFigmaShortestPath(fileKey, normTarget, normSecondaryTarget, depth = maxOf(depth, 10))
            if (isJson) {
                println(jsonPretty.encodeToString(path))
            } else {
                val names = path.map { it.name }
                val text = "[Figma Query: shortest_path] fileKey=$fileKey from=$normTarget to=$normSecondaryTarget hops=${maxOf(0, path.size - 1)} path=[${names.joinToString(" -> ")}]"
                println(truncateSummary(text, false))
            }
            return 0
        }

        private fun runFigmaQuerySubcommand(
            server: AtlasMcpServer,
            args: CliArgs,
            fileKey: String,
            figmaAction: String,
            normTarget: String,
            normSecondaryTarget: String
        ): Int {
            return when (figmaAction) {
                "symbol" -> queryFigmaSymbol(server, fileKey, normTarget, args.isJson)
                "callers" -> queryFigmaCallers(server, fileKey, normTarget, args.depth, args.isJson)
                "impact" -> queryFigmaImpact(server, fileKey, normTarget, args.depth, args.isJson)
                "spatial" -> queryFigmaSpatial(server, fileKey, normTarget, args.boundsArg, args.isJson)
                "god_nodes" -> queryFigmaGodNodes(server, fileKey, args.isJson)
                "shortest_path" -> queryFigmaShortestPath(server, fileKey, normTarget, normSecondaryTarget, args.depth, args.isJson)
                else -> {
                    println("Unknown figma query action: $figmaAction. Valid: symbol, callers, impact, spatial, god_nodes, shortest_path")
                    1
                }
            }
        }

        private fun runFigmaAuditSubcommand(server: AtlasMcpServer, project: String, fileKey: String, isJson: Boolean): Int {
            val auditor = FigmaDesignAuditor(server.store)
            val result = auditor.audit(fileKey)
            if (isJson) {
                println(jsonPretty.encodeToString(result))
            } else {
                val violationSnippets = if (result.violations.isNotEmpty()) {
                    " violations=[${result.violations.take(3).joinToString("; ") { "${it.rule}: ${it.nodeName}" }}]"
                } else ""
                val text = "[Figma Audit] project=$project file=$fileKey passed=${result.passed} score=${result.maturityScore.toInt()}/100 violations=${result.violations.size} polish_delta=${result.polishDelta.size}$violationSnippets"
                println(truncateSummary(text, false))
            }
            return if (result.passed) 0 else 1
        }

        private fun runFigmaScopeSubcommand(server: AtlasMcpServer, project: String, fileKey: String, normSeeds: List<String>, isJson: Boolean): Int {
            val auditor = FigmaDesignAuditor(server.store)
            val scope = auditor.synthesizeFigmaScope(project, fileKey, normSeeds)
            if (isJson) {
                println(jsonPretty.encodeToString(scope))
            } else {
                val collisionSnippets = if (scope.potentialCollisions.isNotEmpty()) {
                    " collisions=${scope.potentialCollisions.size}"
                } else ""
                val text = "[Figma 3D Scope] project=$project file=$fileKey bounds=[${scope.spatialBounds.minX.toInt()}..${scope.spatialBounds.maxX.toInt()}, ${scope.spatialBounds.minY.toInt()}..${scope.spatialBounds.maxY.toInt()}] nodes=${scope.scopedNodesCount} shared_components=${scope.sharedMasterComponents.size} tokens=${scope.tokenLayers.size}$collisionSnippets"
                println(truncateSummary(text, false))
            }
            return 0
        }

        private fun resolveNormTarget(
            target: String,
            parsedFromTarget: UrlTargetResolver.FigmaDeepLink?,
            parsedFromFileArg: UrlTargetResolver.FigmaDeepLink?
        ): String {
            if (parsedFromTarget?.nodeId != null) return parsedFromTarget.nodeId
            if (target.isEmpty()) return parsedFromFileArg?.nodeId ?: ""
            return UrlTargetResolver.normalizeFigmaNodeId(target)
        }

        private fun resolveNormSecondaryTarget(
            secondaryTarget: String,
            parsedFromSecondary: UrlTargetResolver.FigmaDeepLink?
        ): String {
            return parsedFromSecondary?.nodeId ?: UrlTargetResolver.normalizeFigmaNodeId(secondaryTarget)
        }

        private fun printCliVersion(isJson: Boolean) {
            if (isJson) {
                println(jsonPretty.encodeToString(buildJsonObject {
                    put("name", "atlas")
                    put("version", VERSION)
                }))
            } else {
                println("atlas $VERSION")
            }
        }

        private fun runFigmaCommand(
            server: AtlasMcpServer,
            args: CliArgs
        ): Int {
            val parsedFromFileArg = args.fileKey?.let { UrlTargetResolver.parseFigmaUrl(it) }
            val parsedFromTarget = UrlTargetResolver.parseFigmaUrl(args.target)
            val parsedFromSecondary = UrlTargetResolver.parseFigmaUrl(args.secondaryTarget)
            val parsedSeedUrls = args.seeds.mapNotNull { UrlTargetResolver.parseFigmaUrl(it) }
            val fileKey = resolveFigmaFileKey(server, args.fileKey, parsedFromFileArg, parsedFromTarget, parsedSeedUrls)
            val normTarget = resolveNormTarget(args.target, parsedFromTarget, parsedFromFileArg)
            val normSecondaryTarget = resolveNormSecondaryTarget(args.secondaryTarget, parsedFromSecondary)
            val normSeeds = resolveFigmaSeeds(args.seeds, normTarget, parsedFromFileArg?.nodeId)
            val figmaAction = if (args.action == "search") "symbol" else args.action

            when (args.command) {
                "index" -> return runFigmaIndexSubcommand(server, args, fileKey)
                "query" -> return runFigmaQuerySubcommand(server, args, fileKey, figmaAction, normTarget, normSecondaryTarget)
                "audit" -> return runFigmaAuditSubcommand(server, args.project, fileKey, args.isJson)
                "synthesize-scope" -> return runFigmaScopeSubcommand(server, args.project, fileKey, normSeeds, args.isJson)
                "version" -> {
                    printCliVersion(args.isJson)
                    return 0
                }
                else -> {
                    println("Unknown figma command: ${args.command}")
                    printHelp()
                    return 1
                }
            }
        }

        private fun renderComposeIndexOutput(res: JsonObject, project: String, isJson: Boolean) {
            if (isJson) {
                println(jsonPretty.encodeToString(res))
                return
            }
            val cTotal = res["compose_nodes"]?.jsonPrimitive?.intOrNull ?: 0
            val cSkip = res["skippable_composables"]?.jsonPrimitive?.intOrNull ?: 0
            val compUp = res["compiler_upgraded"]?.jsonPrimitive?.intOrNull ?: 0
            val trCnt = res["trace_records_ingested"]?.jsonPrimitive?.intOrNull ?: 0
            val text = "[Atlas Compose] project=$project composables=$cTotal skippable=$cSkip compiler_upgraded=$compUp traces=$trCnt"
            println(truncateSummary(text, false))
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
            project: String,
            target: String,
            depth: Int,
            strongSkipping: Boolean
        ): JsonObject = buildJsonObject {
            put("action", mappedAction)
            put("project", project)
            if (target.isNotEmpty()) put("target", target)
            val effDepth = if (depth <= 2) 5 else depth
            put("depth", effDepth)
            put("strong_skipping", strongSkipping)
        }

        private fun renderComposeQueryOutput(
            data: JsonObject,
            mappedAction: String,
            project: String,
            isJson: Boolean
        ) {
            if (isJson) {
                println(jsonPretty.encodeToString(data))
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
            println(truncateSummary(text, false))
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
            val toolArgs = buildComposeQueryArgs(mappedAction, args.project, args.target, args.depth, args.strongSkipping)
            val res = server.callTool("atlas_query", toolArgs)
            val data = loadSpilledData(res)
            renderComposeQueryOutput(data, mappedAction, args.project, args.isJson)
            return 0
        }

        private fun buildComposeAuditArgs(
            project: String,
            paths: List<String>,
            stabilityConfigPath: String?,
            compilerReportsDir: String?,
            traceLogPath: String?,
            strongSkipping: Boolean
        ): JsonObject = buildJsonObject {
            put("action", "audit_compose")
            put("project", project)
            put("strong_skipping", strongSkipping)
            if (paths.isNotEmpty()) putJsonArray("targets") { paths.forEach { add(it) } }
            if (!stabilityConfigPath.isNullOrBlank()) put("stability_config_path", stabilityConfigPath)
            if (!compilerReportsDir.isNullOrBlank()) put("compiler_reports_dir", compilerReportsDir)
            if (!traceLogPath.isNullOrBlank()) put("trace_log_path", traceLogPath)
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
                println(jsonPretty.encodeToString(data))
            } else {
                val text = "[Compose Audit] project=$project status=$status score=$score/100 composables=$totalComp violations=$vCount strong_skipping=$strongSkipping"
                println(truncateSummary(text, false))
            }
            return passed
        }

        private fun runComposeAuditSubcommand(
            server: AtlasMcpServer,
            args: CliArgs
        ): Int {
            val toolArgs = buildComposeAuditArgs(
                args.project, args.paths, args.stabilityConfigPath, args.compilerReportsDir, args.traceLogPath, args.strongSkipping
            )
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
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
            project: String,
            isJson: Boolean
        ) {
            if (isJson) {
                val resObj = buildJsonObject {
                    data.forEach { (k, v) -> put(k, v) }
                    putJsonArray("applied_remediations") {
                        results.forEach { add(it) }
                    }
                    put("all_passed", allPassed)
                }
                println(jsonPretty.encodeToString(resObj))
            } else {
                val attempted = results.size
                val succeeded = results.count { it["valid"]?.jsonPrimitive?.booleanOrNull == true }
                val failed = attempted - succeeded
                val text = "[Compose Doctor: Apply] project=$project attempted=$attempted succeeded=$succeeded failed=$failed"
                println(truncateSummary(text, false))
                if (failed > 0) {
                    results.filter { it["valid"]?.jsonPrimitive?.booleanOrNull != true }.take(3).forEach { f ->
                        println("  - Failed: ${f["rule_id"]?.jsonPrimitive?.content} in ${f["file_path"]?.jsonPrimitive?.content}: ${f["message"]?.jsonPrimitive?.content}")
                    }
                }
            }
        }

        private fun applyComposeDoctorRemediations(
            server: AtlasMcpServer,
            project: String,
            data: JsonObject,
            ruleIdArg: String?,
            testCmdArg: String?,
            isJson: Boolean
        ): Int {
            val prescriptionsArray = data["prescriptions"]?.jsonArray ?: emptyList()
            val filteredPrescriptions = if (!ruleIdArg.isNullOrBlank()) {
                prescriptionsArray.filter { it.jsonObject["rule_id"]?.jsonPrimitive?.content == ruleIdArg }
            } else {
                prescriptionsArray
            }

            val results = mutableListOf<JsonObject>()
            var allPassed = true

            for (rxJson in filteredPrescriptions) {
                val outcome = executeComposeRemediation(server, project, rxJson.jsonObject, testCmdArg) ?: continue
                if (!outcome.first) allPassed = false
                results.add(outcome.second)
            }

            renderComposeRemediations(data, results, allPassed, project, isJson)
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
            val data = loadSpilledData(res)

            if (args.applyRemediation) {
                return applyComposeDoctorRemediations(server, args.project, data, args.ruleIdArg, args.testCmdArg, args.isJson)
            }

            if (args.isJson) {
                println(jsonPretty.encodeToString(data))
            } else {
                val mode = data["scoring_mode"]?.jsonPrimitive?.content ?: "ESTIMATED"
                val pCount = data["total_prescriptions"]?.jsonPrimitive?.intOrNull ?: 0
                val topRule = data["prescriptions"]?.jsonArray?.firstOrNull()?.jsonObject?.get("rule_id")?.jsonPrimitive?.content ?: "NONE"
                val text = "[Compose Doctor] project=${args.project} mode=$mode prescriptions=$pCount top_rule=$topRule"
                println(truncateSummary(text, false))
            }
            return 0
        }

        private fun runComposeScopeSubcommand(
            server: AtlasMcpServer,
            project: String,
            seeds: List<String>,
            paths: List<String>,
            target: String,
            isJson: Boolean
        ): Int {
            val seedList = when {
                seeds.isNotEmpty() -> seeds
                paths.isNotEmpty() -> paths
                target.isNotEmpty() -> listOf(target)
                else -> emptyList()
            }
            val toolArgs = buildJsonObject {
                put("action", "synthesize_compose_scope")
                put("project", project)
                putJsonArray("targets") { seedList.forEach { add(it) } }
            }
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            if (isJson) {
                println(jsonPretty.encodeToString(data))
            } else {
                val fCnt = data["target_files"]?.jsonArray?.size ?: 0
                val sModels = data["shared_stable_models"]?.jsonArray?.size ?: 0
                val cLocals = data["composition_locals"]?.jsonArray?.size ?: 0
                val safe = data["safe_for_parallel_dispatch"]?.jsonPrimitive?.booleanOrNull ?: true
                val text = "[Compose 3D Scope] project=$project files=$fCnt shared_models=$sModels locals=$cLocals parallel_safe=$safe"
                println(truncateSummary(text, false))
            }
            return 0
        }

        private fun runComposeCommand(
            server: AtlasMcpServer,
            args: CliArgs
        ): Int {
            val command = if (args.command == "audit" && args.isComposeAuditFlag) "audit" else args.command
            val action = if (args.action == "search") "summary" else args.action
            when (command) {
                "index" -> return runComposeIndexSubcommand(server, args)
                "query" -> return runComposeQuerySubcommand(server, args, action)
                "audit" -> return runComposeAuditSubcommand(server, args)
                "doctor" -> return runComposeDoctorSubcommand(server, args)
                "synthesize-scope" -> return runComposeScopeSubcommand(
                    server, args.project, args.seeds, args.paths, args.target, args.isJson
                )
                "version" -> {
                    printCliVersion(args.isJson)
                    return 0
                }
                else -> {
                    println("Unknown compose command: $command")
                    printHelp()
                    return 1
                }
            }
        }

        private fun readStdin(): String {
            val sb = StringBuilder()
            while (true) {
                val line = readlnOrNull() ?: break
                sb.append(line).append('\n')
            }
            return sb.toString()
        }

        private fun loadSpilledData(payload: JsonObject): JsonObject {
            val isSpillover = payload["spillover"]?.jsonPrimitive?.booleanOrNull ?: false
            val uri = payload["artifact_uri"]?.jsonPrimitive?.contentOrNull
            if (isSpillover && uri != null && PlatformFiles.exists(uri)) {
                try {
                    val text = PlatformFiles.readText(uri)
                    return Json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    PlatformFiles.logWarning("AtlasCli", "failed to load spilled data from $uri: ${e.message}")
                }
            }
            return payload
        }

        fun truncateSummary(text: String, isJson: Boolean): String {
            if (isJson) return text
            val bytes = text.encodeToByteArray()
            if (bytes.size <= SUMMARY_CEILING_BYTES) return text
            var end = SUMMARY_CEILING_BYTES - 3
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
                end--
            }
            val truncatedBytes = bytes.copyOfRange(0, end)
            return truncatedBytes.decodeToString() + "..."
        }

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
            val c = extractAuditArraySize(data, "complexity_violations", "complexityViolations")
            return b + l + p + c
        }

        private fun printDoctorTriageOutput(
            project: String,
            apply: Boolean,
            archViolations: Int,
            compPrescriptions: Int,
            composePrescriptions: Int,
            verifiedComp: Int
        ) {
            val mode = if (apply) "apply" else "triage"
            val text = "[Doctor: $mode] project=$project architecture_violations=$archViolations complexity_prescriptions=$compPrescriptions compose_prescriptions=$composePrescriptions"
            println(truncateSummary(text, false))
            if (archViolations > 0 && !apply) {
                println("  - Architecture: $archViolations violation(s) detected. Run 'atlas fix' or 'atlas doctor --apply' to remediate.")
            }
            if (compPrescriptions > 0) {
                if (apply) {
                    println("  - Complexity: $compPrescriptions prescription(s), $verifiedComp verified & applied.")
                } else {
                    println("  - Complexity: $compPrescriptions prescription(s) available. Run 'atlas doctor --complexity' for breakdown.")
                }
            }
            if (composePrescriptions > 0) {
                println("  - Compose: $composePrescriptions prescription(s) available. Run 'atlas compose doctor' for breakdown.")
            }
        }

        private fun resolveFilterPaths(paths: List<String>, target: String): List<String> =
            paths.ifEmpty { if (target.isNotEmpty()) listOf(target) else emptyList() }

        private fun printDoctorJson(
            project: String,
            apply: Boolean,
            archData: JsonObject,
            compData: JsonObject,
            composeData: JsonObject?
        ) {
            val combined = buildJsonObject {
                put("project", project)
                put("mode", if (apply) "applied" else "triage")
                put("architecture", archData)
                put("complexity", compData)
                composeData?.let { put("compose", it) }
            }
            println(jsonPretty.encodeToString(combined))
        }

        private fun runGeneralDoctorCommand(
            server: AtlasMcpServer,
            args: CliArgs
        ): Int {
            val filterPaths = resolveFilterPaths(args.paths, args.target)
            val archData = runArchTriage(server, args, filterPaths)
            val compData = runCompTriage(server, args)
            val composeData = runComposeTriage(server, args.project, args.target, args.limit)

            if (args.isJson) {
                printDoctorJson(args.project, args.applyRemediation, archData, compData, composeData)
            } else {
                val archViolations = extractArchViolationCount(archData, args.applyRemediation)
                val compPrescriptions = compData["total_prescriptions"]?.jsonPrimitive?.intOrNull ?: 0
                val composePrescriptions = composeData?.get("total_prescriptions")?.jsonPrimitive?.intOrNull ?: 0
                val verified = compData["verified_count"]?.jsonPrimitive?.intOrNull ?: 0
                printDoctorTriageOutput(args.project, args.applyRemediation, archViolations, compPrescriptions, composePrescriptions, verified)
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

        private fun printComplexityDoctorOutput(
            project: String,
            data: JsonObject,
            limit: Int
        ) {
            val total = data["total_prescriptions"]?.jsonPrimitive?.intOrNull ?: 0
            val verified = data["verified_count"]?.jsonPrimitive?.intOrNull ?: 0
            val rolledBack = data["rolled_back_count"]?.jsonPrimitive?.intOrNull ?: 0
            val prescriptions = data["prescriptions"]?.jsonArray ?: JsonArray(emptyList())

            println("[Complexity Doctor] project=$project prescriptions=$total applied=$verified rolled_back=$rolledBack")
            for (elem in prescriptions.take(limit)) {
                printSinglePrescription(elem)
            }
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
                printComplexityDoctorOutput(args.project, data, args.limit)
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

        private fun formatRatchetSegment(label: String, ratchet: Int?, delta: Int?): String {
            if (ratchet == null) return ""
            val deltaStr = when {
                delta == null -> "0"
                delta > 0 -> "+$delta"
                else -> "$delta"
            }
            return " $label=$ratchet (delta=$deltaStr)"
        }

        private fun formatComplexityAuditSummary(
            data: JsonObject,
            project: String,
            passed: Boolean,
            thresh: Int
        ): String {
            val total = extractIntField(data, "total_violations")
            val high = extractIntField(data, "high_severity_count")
            val warn = extractIntField(data, "warning_count")
            val resolvedRatchet = data["ratchet"]?.jsonPrimitive?.intOrNull
            val baselineDelta = data["baseline_delta"]?.jsonPrimitive?.intOrNull
            val resolvedHighRatchet = data["high_ratchet"]?.jsonPrimitive?.intOrNull
            val highDelta = data["high_delta"]?.jsonPrimitive?.intOrNull
            val resolvedParamHighRatchet = data["param_high_ratchet"]?.jsonPrimitive?.intOrNull
            val paramHighDelta = data["param_high_delta"]?.jsonPrimitive?.intOrNull
            val advisory = data["advisory_count"]?.jsonPrimitive?.intOrNull ?: 0
            val ratchetStr = formatRatchetSegment("ratchet", resolvedRatchet, baselineDelta)
            val highRatchetStr = formatRatchetSegment("high_ratchet", resolvedHighRatchet, highDelta)
            val paramHighRatchetStr = formatRatchetSegment("param_high_ratchet", resolvedParamHighRatchet, paramHighDelta)
            val advStr = if (advisory > 0) ", ADVISORY=$advisory" else ""
            return "[Complexity Audit] project=$project passed=$passed threshold=$thresh violations=$total$ratchetStr$highRatchetStr$paramHighRatchetStr (HIGH=$high, WARNING=$warn$advStr)"
        }

        private fun resolveAuditFilterPaths(paths: List<String>, target: String): List<String> =
            if (paths.isNotEmpty()) paths else (if (target.isNotEmpty()) listOf(target) else emptyList())

        private fun renderComplexityAuditOutput(
            data: JsonObject,
            project: String,
            passed: Boolean,
            thresh: Int,
            isJson: Boolean
        ): String {
            if (isJson) return jsonPretty.encodeToString(data)
            return formatComplexityAuditSummary(data, project, passed, thresh)
        }

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

            val text = renderComplexityAuditOutput(data, args.project, passed, thresh, args.isJson)
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

        private fun formatComplexityQueryRow(r: JsonObject): String {
            val name = (r["name"]?.jsonPrimitive?.content ?: "").take(30)
            val file = r["file_path"]?.jsonPrimitive?.content ?: ""
            val line = extractIntField(r, "line")
            val loc = ("$file:$line").take(25)
            val cog = extractIntField(r, "cognitive")
            val cyclo = extractIntField(r, "cyclomatic")
            val nest = extractIntField(r, "max_nesting")
            val risk = r["risk_index"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val roundedRisk = ((risk * 10.0).roundToInt() / 10.0).toString()
            val grade = r["grade"]?.jsonPrimitive?.content ?: "A"
            return name.padEnd(30) + " " +
                loc.padEnd(25) + " " +
                cog.toString().padStart(9) + " " +
                cyclo.toString().padStart(7) + " " +
                nest.toString().padStart(7) + " " +
                roundedRisk.padStart(10) + " " +
                grade.padStart(5)
        }

        private fun printComplexityQueryTable(results: JsonArray) {
            val header = "SYMBOL".padEnd(30) + " " +
                "LOCATION".padEnd(25) + " " +
                "COGNITIVE".padStart(9) + " " +
                "CYCLO".padStart(7) + " " +
                "NESTING".padStart(7) + " " +
                "RISK_INDEX".padStart(10) + " " +
                "GRADE".padStart(5)
            println(header)
            println("-".repeat(98))
            for (elem in results) {
                println(formatComplexityQueryRow(elem.jsonObject))
            }
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
                printComplexityQueryTable(results)
            }
            return 0
        }

        private fun renderInitOutput(
            effectiveProject: String,
            result: WorkspaceInitEngine.InitResult,
            isJson: Boolean
        ) {
            if (isJson) {
                val jsonObject = buildJsonObject {
                    put("success", result.success)
                    put("project", effectiveProject)
                    put("hook_path", result.hookPath)
                    put("hook_type", result.hookType)
                    put("message", result.message)
                    put("overwritten", result.overwritten)
                    put("dry_run", result.dryRun)
                    if (result.scriptContent != null) {
                        put("script_content", result.scriptContent)
                    }
                }
                println(jsonPretty.encodeToString(jsonObject))
                return
            }
            if (!result.success) {
                println("[Init Error] $effectiveProject: ${result.message}")
                return
            }
            println("[Init] $effectiveProject: ${result.message}")
            if (result.dryRun && result.scriptContent != null) {
                println("--- Script Preview ---")
                println(result.scriptContent)
                println("----------------------")
            }
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
            renderInitOutput(effectiveProject, result, args.isJson)
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
                val sample = formatLegacyToolSample(report.matches)
                val text = "[Atlas Migrate Tools] mode=$mode occurrences=${report.totalOccurrences} files=${report.filesAffected}$sample"
                println(truncateSummary(text, false))
            }
            return if (applyRemediation || report.totalOccurrences == 0) 0 else 1
        }

        private fun printHelp() {
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
