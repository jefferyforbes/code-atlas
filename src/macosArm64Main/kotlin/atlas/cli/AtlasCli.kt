package atlas.cli

import atlas.engine.FigmaDesignAuditor
import atlas.engine.ToolMigrationEngine
import atlas.engine.WorkspaceInitEngine
import atlas.extract.FigmaSnapshotExtractor
import atlas.extract.UrlTargetResolver
import atlas.mcp.AtlasMcpServer
import atlas.model.FigmaSpatialBounds
import atlas.util.PlatformFiles
import kotlin.math.roundToInt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
        const val VERSION = "0.5.2"
        const val SUMMARY_CEILING_BYTES = 400

        private val jsonPretty = Json { prettyPrint = true }
        private val jsonCompact = Json { prettyPrint = false }

        fun run(argv: Array<String>): Int {
            if (argv.contains("--version") || argv.contains("-v") || (argv.isNotEmpty() && argv[0] == "version") ||
                (argv.size >= 2 && (argv[0] == "figma" || argv[0] == "compose") && (argv[1] == "version" || argv[1] == "--version" || argv[1] == "-v"))
            ) {
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

            if (argv.isEmpty() || argv.contains("--help") || argv.contains("-h") ||
                (argv.size == 1 && (argv[0] == "figma" || argv[0] == "compose" || argv[0] == "complexity" || argv[0] == "help"))
            ) {
                printHelp()
                return 0
            }

            var subsystem = ""
            var command = ""
            var project = "organisation"
            var workspace = "."
            var customDb: String? = null
            var cacheProfile: String? = null
            var target = ""
            val paths = mutableListOf<String>()
            var action = "search"
            var secondaryTarget = ""
            var depth = 2
            var isJson = false
            var includeSource = false
            var fileKey: String? = null
            var snapshotPath: String? = null
            var tokensPath: String? = null
            var isStdin = false
            var boundsArg: String? = null
            val seeds = mutableListOf<String>()
            var stabilityConfigPath: String? = null
            var compilerReportsDir: String? = null
            var traceLogPath: String? = null
            var strongSkipping = true
            var limit = 10
            var isComposeAuditFlag = false
            var isComplexityFlag = false
            var thresholdArg: Int? = null
            var maxCyclomaticArg: Int? = null
            var outcome: String? = null
            var note: String? = null
            var beforeSnippet: String? = null
            var afterSnippet: String? = null
            var ruleIdArg: String? = null
            var targetNodeArg: String? = null
            var testCmdArg: String? = null
            var applyRemediation = false
            var splitBoost003 = true
            var makePrivateBoost003 = false
            var descriptionMode: String? = null
            var forceIndex = false
            var hookTypeArg = "pre-commit"
            var isDryRun = false
            var forceFlag = false
            var legacyToolsFlag = false

            var idx = 0
            while (idx < argv.size) {
                val arg = argv[idx]
                when {
                    arg == "figma" -> subsystem = "figma"
                    arg == "compose" -> subsystem = "compose"
                    arg == "complexity" -> {
                        subsystem = "complexity"
                        isComplexityFlag = true
                    }
                    (subsystem == "figma" || arg == "figma") && (arg == "index" || arg == "query" || arg == "audit" || arg == "synthesize-scope" || arg == "shortest-path" || arg == "shortest_path" || arg == "version") -> {
                        if (arg == "shortest-path" || arg == "shortest_path") {
                            command = "query"
                            action = "shortest_path"
                        } else {
                            command = arg
                        }
                    }
                    (subsystem == "compose" || arg == "compose") && (arg == "index" || arg == "query" || arg == "audit" || arg == "doctor" || arg == "synthesize-scope" || arg == "version") -> {
                        command = arg
                    }
                    (subsystem == "complexity" || arg == "complexity") && (arg == "query" || arg == "audit" || arg == "doctor") -> {
                        command = arg
                        isComplexityFlag = true
                    }
                    arg == "figma-shortest-path" -> {
                        subsystem = "figma"
                        command = "query"
                        action = "shortest_path"
                    }
                    arg == "index" || arg == "query" || arg == "synthesize-scope" || arg == "audit" || arg == "doctor" || arg == "fix" || arg == "remedy" || arg == "remediate" || arg == "dead-code" || arg == "dead_code" || arg == "record-memory" || arg == "record_memory" || arg == "serve-mcp" || arg == "version" || arg == "verify-remediation" || arg == "verify_remediation" || arg == "init" || arg == "migrate-tools" || arg == "migrate_tools" -> {
                        command = when (arg) {
                            "dead_code" -> "dead-code"
                            "record_memory" -> "record-memory"
                            "verify_remediation" -> "verify-remediation"
                            "remedy", "remediate" -> "fix"
                            "migrate_tools" -> "migrate-tools"
                            else -> arg
                        }
                    }
                    arg == "--compose" -> isComposeAuditFlag = true
                    arg == "--complexity" -> isComplexityFlag = true
                    arg == "--legacy-tools" -> legacyToolsFlag = true
                    (arg == "--threshold" || arg == "--max-cognitive") && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> thresholdArg = argv[++idx].toIntOrNull()
                    arg.startsWith("--threshold=") -> thresholdArg = arg.substringAfter('=').toIntOrNull()
                    arg.startsWith("--max-cognitive=") -> thresholdArg = arg.substringAfter('=').toIntOrNull()
                    arg == "--max-cyclomatic" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> maxCyclomaticArg = argv[++idx].toIntOrNull()
                    arg.startsWith("--max-cyclomatic=") -> maxCyclomaticArg = arg.substringAfter('=').toIntOrNull()
                    arg == "--outcome" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> outcome = argv[++idx]
                    arg == "--note" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> note = argv[++idx]
                    arg == "--project" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> project = argv[++idx]
                    arg == "--workspace" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> workspace = argv[++idx]
                    arg == "--db" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> customDb = argv[++idx]
                    arg == "--cache-profile" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> cacheProfile = argv[++idx]
                    (arg == "--target" || arg == "--from") && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> target = argv[++idx]
                    arg == "--url" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> {
                        target = argv[++idx]
                        action = "url"
                        if (command.isEmpty()) command = "query"
                    }
                    arg.startsWith("--url=") -> {
                        target = arg.substringAfter('=')
                        action = "url"
                        if (command.isEmpty()) command = "query"
                    }
                    arg == "--action" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> action = argv[++idx]
                    arg.startsWith("--action=") -> action = arg.substringAfter('=')
                    (arg == "--secondary-target" || arg == "--to") && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> secondaryTarget = argv[++idx]
                    arg == "--depth" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> depth = argv[++idx].toIntOrNull() ?: 2
                    arg == "--limit" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> limit = argv[++idx].toIntOrNull() ?: 10
                    arg == "--file" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> fileKey = argv[++idx]
                    arg == "--snapshot" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> snapshotPath = argv[++idx]
                    arg == "--tokens" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> tokensPath = argv[++idx]
                    arg == "--stability-config" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> stabilityConfigPath = argv[++idx]
                    arg == "--compiler-reports" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> compilerReportsDir = argv[++idx]
                    arg == "--trace-log" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> traceLogPath = argv[++idx]
                    arg == "--strong-skipping" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> {
                        strongSkipping = argv[++idx].lowercase() != "false"
                    }
                    arg.startsWith("--strong-skipping=") -> {
                        strongSkipping = arg.substringAfter('=').lowercase() != "false"
                    }
                    arg == "--no-strong-skipping" -> strongSkipping = false
                    arg == "--stdin" -> isStdin = true
                    arg == "--bounds" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> boundsArg = argv[++idx]
                    arg == "--seeds" -> {
                        idx++
                        while (idx < argv.size && !argv[idx].startsWith("--")) {
                            seeds.add(argv[idx])
                            idx++
                        }
                        continue
                    }
                    arg == "--json" -> isJson = true
                    arg == "--summary" -> isJson = false
                    arg == "--include-source" -> includeSource = true
                    arg == "--before" && idx + 1 < argv.size -> beforeSnippet = argv[++idx]
                    arg.startsWith("--before=") -> beforeSnippet = arg.substringAfter('=')
                    arg == "--after" && idx + 1 < argv.size -> afterSnippet = argv[++idx]
                    arg.startsWith("--after=") -> afterSnippet = arg.substringAfter('=')
                    arg == "--rule" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> ruleIdArg = argv[++idx]
                    arg == "--target-node" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> targetNodeArg = argv[++idx]
                    arg == "--test-cmd" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> testCmdArg = argv[++idx]
                    arg == "--apply" -> applyRemediation = true
                    arg == "--dry-run" -> {
                        applyRemediation = false
                        isDryRun = true
                    }
                    arg == "--split" -> splitBoost003 = true
                    arg == "--no-split" -> splitBoost003 = false
                    arg == "--make-private" -> makePrivateBoost003 = true
                    arg == "--force" -> {
                        forceIndex = true
                        forceFlag = true
                    }
                    arg == "--hook-type" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> hookTypeArg = argv[++idx]
                    arg.startsWith("--hook-type=") -> hookTypeArg = arg.substringAfter('=')
                    (arg == "--description-mode" || arg == "--descriptions") && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> descriptionMode = argv[++idx]
                    arg.startsWith("--description-mode=") -> descriptionMode = arg.substringAfter('=')
                    arg.startsWith("--descriptions=") -> descriptionMode = arg.substringAfter('=')
                    arg == "--no-description-check" || arg == "--no-descriptions" -> descriptionMode = "off"
                    arg == "--path" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> paths.add(argv[++idx])
                    arg == "--paths" -> {
                        idx++
                        while (idx < argv.size && !argv[idx].startsWith("--")) {
                            paths.add(argv[idx])
                            idx++
                        }
                        continue
                    }
                    !arg.startsWith("--") && command.isEmpty() && arg != "figma" && arg != "compose" -> command = arg
                    !arg.startsWith("--") && target.isEmpty() && arg != "figma" && arg != "compose" -> target = arg
                }
                idx++
            }

            if (command.isEmpty()) {
                printHelp()
                return 1
            }

            val server = AtlasMcpServer(
                workspaceRoot = workspace,
                customDbPath = customDb,
                cacheProfile = cacheProfile
            )

            try {
                if (subsystem == "figma") {
                    return runFigmaCommand(
                        server = server,
                        command = command,
                        project = project,
                        fileKeyArg = fileKey,
                        snapshotPath = snapshotPath,
                        tokensPath = tokensPath,
                        isStdin = isStdin,
                        action = action,
                        target = target,
                        secondaryTarget = secondaryTarget,
                        depth = depth,
                        boundsArg = boundsArg,
                        seeds = seeds,
                        isJson = isJson
                    )
                }

                if ((command == "doctor" && isComplexityFlag) || (subsystem == "complexity" && command == "doctor")) {
                    return runComplexityDoctorCommand(
                        server = server,
                        project = project,
                        target = target,
                        limit = limit,
                        apply = applyRemediation,
                        testCmd = testCmdArg,
                        threshold = thresholdArg,
                        isJson = isJson
                    )
                }

                if ((command == "audit" && isComplexityFlag) || (subsystem == "complexity" && command == "audit")) {
                    return runComplexityAuditCommand(
                        server = server,
                        project = project,
                        paths = paths,
                        target = target,
                        threshold = thresholdArg,
                        maxCyclomatic = maxCyclomaticArg,
                        isJson = isJson
                    )
                }

                if (command == "query" && (action == "complexity" || isComplexityFlag || subsystem == "complexity")) {
                    return runComplexityQueryCommand(
                        server = server,
                        project = project,
                        target = target,
                        threshold = thresholdArg,
                        limit = limit,
                        isJson = isJson
                    )
                }

                if (subsystem == "compose" || (command == "audit" && isComposeAuditFlag) || command == "doctor") {
                    val effCmd = if (command == "audit" && isComposeAuditFlag) "audit" else command
                    return runComposeCommand(
                        server = server,
                        command = effCmd,
                        project = project,
                        action = if (action == "search") "summary" else action,
                        target = target,
                        paths = paths,
                        seeds = seeds,
                        depth = depth,
                        limit = limit,
                        stabilityConfigPath = stabilityConfigPath,
                        compilerReportsDir = compilerReportsDir,
                        traceLogPath = traceLogPath,
                        strongSkipping = strongSkipping,
                        isJson = isJson,
                        applyRemediation = applyRemediation,
                        ruleIdArg = ruleIdArg,
                        testCmdArg = testCmdArg
                    )
                }

                when (command) {
                    "migrate-tools" -> {
                        return runMigrateToolsCommand(
                            server = server,
                            paths = paths,
                            target = target,
                            applyRemediation = applyRemediation,
                            isJson = isJson
                        )
                    }
                    "serve-mcp" -> {
                        server.serveStdio()
                        return 0
                    }
                    "index" -> {
                        val res = server.indexWorkspace(project, subpaths = if (paths.isNotEmpty()) paths else null, force = forceIndex)
                        val text = if (isJson) {
                            jsonPretty.encodeToString(res)
                        } else {
                            val fTotal = res["files"]?.jsonPrimitive?.intOrNull ?: 0
                            val fIdx = res["files_indexed"]?.jsonPrimitive?.intOrNull ?: 0
                            val fSkip = res["files_skipped"]?.jsonPrimitive?.intOrNull ?: 0
                            val nTotal = res["nodes"]?.jsonPrimitive?.intOrNull ?: 0
                            val eTotal = res["edges"]?.jsonPrimitive?.intOrNull ?: 0
                            "[Atlas] project=$project files=$fTotal (indexed=$fIdx, skipped=$fSkip) nodes=$nTotal edges=$eTotal"
                        }
                        println(truncateSummary(text, isJson))
                        return 0
                    }
                    "synthesize-scope" -> {
                        val seedList = if (paths.isNotEmpty()) paths else (if (target.isNotEmpty()) listOf(target) else emptyList())
                        val toolArgs = buildJsonObject {
                            put("action", "synthesize_3d_scope")
                            put("project", project)
                            putJsonArray("targets") { seedList.forEach { add(it) } }
                        }
                        val res = server.callTool("atlas_audit", toolArgs)
                        val data = loadSpilledData(res)
                        val text = if (isJson) {
                            jsonPretty.encodeToString(data)
                        } else {
                            val fCount = (data["files"] ?: data["files_count"])?.jsonArray?.size ?: 0
                            val mods = (data["modules"] ?: data["impacted_modules"])?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                            val arch = (data["architecture"] ?: data["impacted_layers"])?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                            val collisions = (data["colliding_plans"] ?: data["collidingPlans"])?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                            "[3D Scope] project=$project seeds=$seedList files=$fCount modules=$mods arch=$arch collisions=$collisions"
                        }
                        println(truncateSummary(text, isJson))
                        return 0
                    }
                    "audit" -> {
                        val filterPaths = if (paths.isNotEmpty()) paths else (if (target.isNotEmpty()) listOf(target) else emptyList())
                        if (legacyToolsFlag) {
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
                        val toolArgs = buildJsonObject {
                            put("action", "verify_architecture")
                            put("project", project)
                            if (filterPaths.isNotEmpty()) {
                                putJsonArray("targets") { filterPaths.forEach { add(it) } }
                            }
                            if (!descriptionMode.isNullOrBlank()) {
                                put("description_mode", descriptionMode)
                            }
                            if (!outcome.isNullOrBlank()) put("outcome", outcome)
                            if (!note.isNullOrBlank()) put("note", note)
                        }
                        val res = server.callTool("atlas_audit", toolArgs)
                        val data = loadSpilledData(res)
                        val bViolations = (data["single_declaration_violations"] ?: data["singleDeclarationViolations"])?.jsonArray?.size ?: 0
                        val lInversions = (data["layer_inversions"] ?: data["layerInversions"])?.jsonArray?.size ?: 0
                        val pCollisions = (data["plan_3d_collisions"] ?: data["plan3dCollisions"])?.jsonArray?.size ?: 0
                        val dSuggestions = (data["description_suggestions"] ?: data["descriptionSuggestions"])?.jsonArray?.size ?: 0
                        val cViolations = (data["complexity_violations"] ?: data["complexityViolations"])?.jsonArray?.size ?: 0
                        val passed = data["passed"]?.jsonPrimitive?.booleanOrNull ?: (bViolations == 0 && lInversions == 0 && pCollisions == 0 && cViolations == 0)
                        val text = if (isJson) {
                            jsonPretty.encodeToString(data)
                        } else {
                            "[Audit] project=$project passed=$passed boost003_violations=$bViolations layer_inversions=$lInversions plan_3d_collisions=$pCollisions complexity_violations=$cViolations description_suggestions=$dSuggestions"
                        }
                        println(truncateSummary(text, isJson))
                        return if (passed) 0 else 1
                    }
                    "fix" -> {
                        val filterPaths = if (paths.isNotEmpty()) paths else (if (target.isNotEmpty()) listOf(target) else emptyList())
                        if (legacyToolsFlag || ruleIdArg == "LEGACY-TOOLS" || ruleIdArg == "MIGRATE-TOOLS") {
                            val scanPaths = if (filterPaths.isNotEmpty()) filterPaths else listOf(server.workspaceRoot)
                            val report = ToolMigrationEngine.scanAndMigrate(scanPaths, apply = applyRemediation)
                            if (isJson) {
                                println(jsonPretty.encodeToString(report))
                            } else {
                                val mode = if (applyRemediation) "applied" else "dry-run"
                                val sample = if (report.matches.isNotEmpty()) {
                                    val s = report.matches.take(5).joinToString("; ") { "${it.legacyTool} in ${it.file}:${it.line}" }
                                    " top=[$s]"
                                } else ""
                                val text = "[Fix Legacy Tools] mode=$mode occurrences=${report.totalOccurrences} files=${report.filesAffected}$sample"
                                println(truncateSummary(text, false))
                            }
                            return if (applyRemediation || report.totalOccurrences == 0) 0 else 1
                        }
                        val toolArgs = buildJsonObject {
                            put("action", "remedy_architecture")
                            put("project", project)
                            if (filterPaths.isNotEmpty()) {
                                putJsonArray("targets") { filterPaths.forEach { add(it) } }
                            }
                            if (!ruleIdArg.isNullOrBlank()) put("rule_id", ruleIdArg)
                            put("dry_run", !applyRemediation)
                            put("split", splitBoost003)
                            put("make_private", makePrivateBoost003)
                            if (!testCmdArg.isNullOrBlank()) put("test_command", testCmdArg)
                            put("limit", limit)
                        }
                        val res = server.callTool("atlas_audit", toolArgs)
                        val data = loadSpilledData(res)
                        val total = data["total_violations"]?.jsonPrimitive?.intOrNull ?: 0
                        val bCount = data["boost003_count"]?.jsonPrimitive?.intOrNull ?: 0
                        val lCount = data["layer_inversion_count"]?.jsonPrimitive?.intOrNull ?: 0
                        val vCount = data["verified_count"]?.jsonPrimitive?.intOrNull ?: 0
                        val aCount = data["applied_count"]?.jsonPrimitive?.intOrNull ?: 0
                        val dryRun = data["dry_run"]?.jsonPrimitive?.booleanOrNull ?: (!applyRemediation)
                        val prescriptions = data["prescriptions"]?.jsonArray ?: emptyList()

                        if (applyRemediation && aCount > 0) {
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

                        val text = if (isJson) {
                            jsonPretty.encodeToString(data)
                        } else {
                            val mode = if (dryRun) "dry-run" else "applied"
                            val header = "[Remediation] project=$project mode=$mode violations=$total boost003=$bCount layer_inversions=$lCount verified=$vCount applied=$aCount"
                            val details = if (prescriptions.isNotEmpty() && (filterPaths.isNotEmpty() || prescriptions.size <= 5)) {
                                val lines = mutableListOf<String>()
                                for (p in prescriptions.take(5)) {
                                    val obj = p.jsonObject
                                    val f = obj["file_path"]?.jsonPrimitive?.content ?: ""
                                    val r = obj["rule_id"]?.jsonPrimitive?.content ?: ""
                                    val s = obj["fix_strategy"]?.jsonPrimitive?.content ?: ""
                                    val v = obj["verified"]?.jsonPrimitive?.booleanOrNull ?: false
                                    val app = obj["applied"]?.jsonPrimitive?.booleanOrNull ?: false
                                    lines.add("  - $r: $f [$s, verified=$v, applied=$app]")
                                    val diff = obj["diff_preview"]?.jsonPrimitive?.contentOrNull
                                    if (!diff.isNullOrBlank()) {
                                        lines.add(diff.lines().take(20).joinToString("\n") { "    $it" })
                                    }
                                }
                                "\n" + lines.joinToString("\n")
                            } else ""
                            header + details
                        }
                        println(truncateSummary(text, isJson))
                        return if (applyRemediation) {
                            if (aCount == prescriptions.size || total == 0) 0 else 1
                        } else {
                            if (vCount > 0 || total == 0) 0 else 1
                        }
                    }
                    "dead-code" -> {
                        val filterPaths = if (paths.isNotEmpty()) paths else (if (target.isNotEmpty()) listOf(target) else emptyList())
                        val toolArgs = buildJsonObject {
                            put("action", "dead_code")
                            put("project", project)
                            if (filterPaths.isNotEmpty()) {
                                putJsonArray("targets") { filterPaths.forEach { add(it) } }
                            }
                        }
                        val res = server.callTool("atlas_audit", toolArgs)
                        val data = loadSpilledData(res)
                        val count = data["dead_code_count"]?.jsonPrimitive?.intOrNull ?: 0
                        val names = data["dead_nodes"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content } ?: emptyList()
                        val text = if (isJson) {
                            jsonPretty.encodeToString(data)
                        } else {
                            "[Dead Code] project=$project dead_symbols=$count top=[${names.take(5).joinToString(", ")}]"
                        }
                        println(truncateSummary(text, isJson))
                        return 0
                    }
                    "record-memory" -> {
                        val targetNode = if (target.isNotEmpty()) target else (if (paths.isNotEmpty()) paths.first() else "")
                        val toolArgs = buildJsonObject {
                            put("action", "record_memory")
                            put("project", project)
                            putJsonArray("targets") { if (targetNode.isNotEmpty()) add(targetNode) }
                            if (!outcome.isNullOrBlank()) put("outcome", outcome)
                            if (!note.isNullOrBlank()) put("note", note)
                        }
                        val res = server.callTool("atlas_audit", toolArgs)
                        val text = if (isJson) {
                            jsonPretty.encodeToString(res)
                        } else {
                            val annId = res["annotation_id"]?.jsonPrimitive?.content ?: "unknown"
                            val nId = res["node_id"]?.jsonPrimitive?.content ?: targetNode
                            val out = res["outcome"]?.jsonPrimitive?.content ?: (outcome ?: "useful")
                            "[Atlas Memory] Recorded outcome=$out node=$nId id=$annId"
                        }
                        println(truncateSummary(text, isJson))
                        return 0
                    }
                    "verify-remediation" -> {
                        val filePath = if (paths.isNotEmpty()) paths.first() else (if (target.isNotEmpty()) target else (fileKey ?: ""))
                        val toolArgs = buildJsonObject {
                            put("action", "verify_remediation")
                            put("project", project)
                            put("file_path", filePath)
                            put("before_snippet", beforeSnippet ?: "")
                            put("after_snippet", afterSnippet ?: "")
                            if (!ruleIdArg.isNullOrBlank()) put("rule_id", ruleIdArg)
                            if (!targetNodeArg.isNullOrBlank()) put("target_node_id", targetNodeArg)
                            if (!testCmdArg.isNullOrBlank()) put("test_command", testCmdArg)
                            put("dry_run", !applyRemediation)
                        }
                        val res = server.callTool("atlas_audit", toolArgs)
                        val data = loadSpilledData(res)
                        val valid = data["valid"]?.jsonPrimitive?.booleanOrNull ?: false
                        val phase = data["phase"]?.jsonPrimitive?.content ?: "UNKNOWN"
                        val msg = data["message"]?.jsonPrimitive?.content ?: ""
                        val text = if (isJson) {
                            jsonPretty.encodeToString(data)
                        } else {
                            "[Remediation Verification] valid=$valid phase=$phase file=$filePath message=\"$msg\""
                        }
                        println(truncateSummary(text, isJson))
                        return if (valid) 0 else 1
                    }
                    "query" -> {
                        val toolArgs = buildJsonObject {
                            put("action", action)
                            put("project", project)
                            put("target", target)
                            put("depth", depth)
                            if (secondaryTarget.isNotEmpty()) put("secondary_target", secondaryTarget)
                            if (includeSource) put("include_source", true)
                            if (!outcome.isNullOrBlank()) put("outcome", outcome)
                            if (!note.isNullOrBlank()) put("note", note)
                        }
                        val res = server.callTool("atlas_query", toolArgs)
                        val data = if (isJson) loadSpilledData(res) else res
                        if (isJson) {
                            println(jsonPretty.encodeToString(data))
                        } else {
                            val compact = jsonCompact.encodeToString(data)
                            println(truncateSummary(compact, false))
                        }
                        return 0
                    }
                    "init" -> {
                        return runInitCommand(
                            workspace = workspace,
                            project = project,
                            hookType = hookTypeArg,
                            force = forceFlag,
                            threshold = thresholdArg ?: 15,
                            dryRun = isDryRun,
                            isJson = isJson
                        )
                    }
                    "version" -> {
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
                    else -> {
                        println("Unknown command: $command")
                        printHelp()
                        return 1
                    }
                }
            } finally {
                server.close()
            }
        }

        private fun runFigmaCommand(
            server: AtlasMcpServer,
            command: String,
            project: String,
            fileKeyArg: String?,
            snapshotPath: String?,
            tokensPath: String?,
            isStdin: Boolean,
            action: String,
            target: String,
            secondaryTarget: String,
            depth: Int,
            boundsArg: String?,
            seeds: List<String>,
            isJson: Boolean
        ): Int {
            val parsedFromFileArg = fileKeyArg?.let { UrlTargetResolver.parseFigmaUrl(it) }
            val parsedFromTarget = UrlTargetResolver.parseFigmaUrl(target)
            val parsedFromSecondary = UrlTargetResolver.parseFigmaUrl(secondaryTarget)
            val parsedSeedUrls = seeds.mapNotNull { UrlTargetResolver.parseFigmaUrl(it) }
            val fileKey = parsedFromFileArg?.fileKey
                ?: parsedFromTarget?.fileKey
                ?: parsedSeedUrls.firstOrNull()?.fileKey
                ?: fileKeyArg
                ?: server.store.getLatestFigmaFileKey()
                ?: "default"
            val normTarget = parsedFromTarget?.nodeId
                ?: (if (target.isEmpty()) parsedFromFileArg?.nodeId ?: "" else UrlTargetResolver.normalizeFigmaNodeId(target))
            val normSecondaryTarget = parsedFromSecondary?.nodeId
                ?: UrlTargetResolver.normalizeFigmaNodeId(secondaryTarget)
            val normSeeds = if (seeds.isNotEmpty()) {
                seeds.map { s -> UrlTargetResolver.parseFigmaUrl(s)?.nodeId ?: UrlTargetResolver.normalizeFigmaNodeId(s) }
            } else if (normTarget.isNotEmpty()) {
                listOf(normTarget)
            } else if (parsedFromFileArg?.nodeId != null) {
                listOf(parsedFromFileArg.nodeId)
            } else {
                emptyList()
            }
            val figmaAction = if (action == "search") "symbol" else action

            when (command) {
                "index" -> {
                    val astJson = when {
                        isStdin -> readStdin()
                        snapshotPath != null -> {
                            if (!PlatformFiles.exists(snapshotPath)) {
                                println("Error: Snapshot file not found at $snapshotPath")
                                return 1
                            }
                            PlatformFiles.readText(snapshotPath)
                        }
                        else -> {
                            println("Error: atlas figma index requires --snapshot <path> or --stdin")
                            return 1
                        }
                    }
                    val tokensJson = if (tokensPath != null && PlatformFiles.exists(tokensPath)) {
                        PlatformFiles.readText(tokensPath)
                    } else null

                    val extractor = FigmaSnapshotExtractor()
                    val snapshot = extractor.ingest(server.store, fileKey, astJson, tokensJson)

                    if (isJson) {
                        val resObj = buildJsonObject {
                            put("file_key", fileKey)
                            put("nodes_indexed", snapshot.nodes.size)
                            put("variables_indexed", snapshot.variables.size)
                            put("edges_indexed", snapshot.edges.size)
                            put("document_name", snapshot.document.name)
                        }
                        println(jsonPretty.encodeToString(resObj))
                    } else {
                        val text = "[Atlas Figma] Indexed project=$project fileKey=$fileKey nodes=${snapshot.nodes.size} variables=${snapshot.variables.size} edges=${snapshot.edges.size}"
                        println(truncateSummary(text, false))
                    }
                    return 0
                }
                "query" -> {
                    when (figmaAction) {
                        "symbol" -> {
                            val node = server.store.queryFigmaNodeById(fileKey, normTarget)
                            val nodes = if (node != null) listOf(node) else server.store.queryFigmaNodesByName(fileKey, normTarget)
                            val variable = if (nodes.isEmpty()) server.store.queryFigmaVariableByName(fileKey, normTarget) ?: server.store.queryFigmaVariableById(fileKey, normTarget) else null
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
                            } else {
                                val count = nodes.size + (if (variable != null) 1 else 0)
                                val names = nodes.map { it.name } + listOfNotNull(variable?.name)
                                val text = "[Figma Query: symbol] target=$normTarget found=$count matches=[${names.take(3).joinToString(", ")}]"
                                println(truncateSummary(text, false))
                            }
                            return 0
                        }
                        "callers" -> {
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
                        "impact" -> {
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
                        "spatial" -> {
                            val bounds = if (boundsArg != null) {
                                val parts = boundsArg.split(',').map { it.trim().toDoubleOrNull() ?: 0.0 }
                                if (parts.size >= 4) {
                                    FigmaSpatialBounds(parts[0], parts[1], parts[2], parts[3])
                                } else {
                                    FigmaSpatialBounds(0.0, 0.0, 0.0, 0.0)
                                }
                            } else {
                                val targetNode = server.store.queryFigmaNodeById(fileKey, normTarget)
                                    ?: server.store.queryFigmaNodesByName(fileKey, normTarget).firstOrNull()
                                targetNode?.spatialBounds ?: FigmaSpatialBounds(0.0, 0.0, 0.0, 0.0)
                            }
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
                        "god_nodes" -> {
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
                        "shortest_path" -> {
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
                        else -> {
                            println("Unknown figma query action: $figmaAction. Valid: symbol, callers, impact, spatial, god_nodes, shortest_path")
                            return 1
                        }
                    }
                }
                "audit" -> {
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
                "synthesize-scope" -> {
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
                "version" -> {
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
                else -> {
                    println("Unknown figma command: $command")
                    printHelp()
                    return 1
                }
            }
        }

        private fun runComposeCommand(
            server: AtlasMcpServer,
            command: String,
            project: String,
            action: String,
            target: String,
            paths: List<String>,
            seeds: List<String>,
            depth: Int,
            limit: Int,
            stabilityConfigPath: String?,
            compilerReportsDir: String?,
            traceLogPath: String?,
            strongSkipping: Boolean,
            isJson: Boolean,
            applyRemediation: Boolean = false,
            ruleIdArg: String? = null,
            testCmdArg: String? = null
        ): Int {
            when (command) {
                "index" -> {
                    val res = server.indexComposeArtifacts(
                        projectId = project,
                        subpaths = if (paths.isNotEmpty()) paths else null,
                        stabilityConfigPath = stabilityConfigPath,
                        compilerReportsDir = compilerReportsDir,
                        traceLogPath = traceLogPath
                    )
                    if (isJson) {
                        println(jsonPretty.encodeToString(res))
                    } else {
                        val cTotal = res["compose_nodes"]?.jsonPrimitive?.intOrNull ?: 0
                        val cSkip = res["skippable_composables"]?.jsonPrimitive?.intOrNull ?: 0
                        val compUp = res["compiler_upgraded"]?.jsonPrimitive?.intOrNull ?: 0
                        val trCnt = res["trace_records_ingested"]?.jsonPrimitive?.intOrNull ?: 0
                        val text = "[Atlas Compose] project=$project composables=$cTotal skippable=$cSkip compiler_upgraded=$compUp traces=$trCnt"
                        println(truncateSummary(text, false))
                    }
                    return 0
                }
                "query" -> {
                    val mappedAction = when (action.lowercase()) {
                        "cascade", "compose_cascade" -> "compose_cascade"
                        "blame", "compose_blame" -> "compose_blame"
                        else -> "compose_summary"
                    }
                    val toolArgs = buildJsonObject {
                        put("action", mappedAction)
                        put("project", project)
                        if (target.isNotEmpty()) put("target", target)
                        put("depth", if (depth <= 2) 5 else depth)
                        put("strong_skipping", strongSkipping)
                    }
                    val res = server.callTool("atlas_query", toolArgs)
                    val data = loadSpilledData(res)
                    if (isJson) {
                        println(jsonPretty.encodeToString(data))
                    } else {
                        val total = data["total_composables"]?.jsonPrimitive?.intOrNull ?: 0
                        val skipCnt = data["skippable_count"]?.jsonPrimitive?.intOrNull ?: 0
                        val skipPct = data["skippable_pct"]?.jsonPrimitive?.doubleOrNull ?: 100.0
                        val unstable = data["unstable_param_total"]?.jsonPrimitive?.intOrNull ?: 0
                        val cascadeSize = data["cascade"]?.jsonArray?.size ?: 0
                        val blameSize = data["blame"]?.jsonArray?.size ?: 0
                        val text = "[Compose Query: ${mappedAction.removePrefix("compose_")}] project=$project composables=$total skippable=$skipCnt ($skipPct%) unstable_params=$unstable cascade=$cascadeSize blame=$blameSize"
                        println(truncateSummary(text, false))
                    }
                    return 0
                }
                "audit" -> {
                    val toolArgs = buildJsonObject {
                        put("action", "audit_compose")
                        put("project", project)
                        put("strong_skipping", strongSkipping)
                        if (paths.isNotEmpty()) putJsonArray("targets") { paths.forEach { add(it) } }
                        if (!stabilityConfigPath.isNullOrBlank()) put("stability_config_path", stabilityConfigPath)
                        if (!compilerReportsDir.isNullOrBlank()) put("compiler_reports_dir", compilerReportsDir)
                        if (!traceLogPath.isNullOrBlank()) put("trace_log_path", traceLogPath)
                    }
                    val res = server.callTool("atlas_audit", toolArgs)
                    val data = loadSpilledData(res)
                    val score = data["overall_score"]?.jsonPrimitive?.doubleOrNull ?: 95.0
                    val status = data["status"]?.jsonPrimitive?.content ?: (if (score >= 80.0) "PASS" else "FAIL")
                    val vCount = data["violations"]?.jsonArray?.size ?: 0
                    val totalComp = data["total_composables"]?.jsonPrimitive?.intOrNull ?: 0
                    if (isJson) {
                        println(jsonPretty.encodeToString(data))
                    } else {
                        val text = "[Compose Audit] project=$project status=$status score=$score/100 (Gate1<=95.0) composables=$totalComp violations=$vCount strong_skipping=$strongSkipping"
                        println(truncateSummary(text, false))
                    }
                    return if (score >= 80.0) 0 else 1
                }
                "doctor" -> {
                    val toolArgs = buildJsonObject {
                        put("action", "compose_doctor")
                        put("project", project)
                        put("limit", limit)
                        if (target.isNotEmpty()) putJsonArray("targets") { add(target) }
                    }
                    val res = server.callTool("atlas_audit", toolArgs)
                    val data = loadSpilledData(res)

                    if (applyRemediation) {
                        val prescriptionsArray = data["prescriptions"]?.jsonArray ?: emptyList()
                        val filteredPrescriptions = if (!ruleIdArg.isNullOrBlank()) {
                            prescriptionsArray.filter { it.jsonObject["rule_id"]?.jsonPrimitive?.content == ruleIdArg }
                        } else {
                            prescriptionsArray
                        }

                        val results = mutableListOf<JsonObject>()
                        var allPassed = true

                        for (rxJson in filteredPrescriptions) {
                            val rx = rxJson.jsonObject
                            val rxFilePath = rx["file_path"]?.jsonPrimitive?.content ?: continue
                            val rxBefore = rx["before_snippet"]?.jsonPrimitive?.content ?: continue
                            val rxAfter = rx["after_snippet"]?.jsonPrimitive?.content ?: continue
                            val rxRuleId = rx["rule_id"]?.jsonPrimitive?.content ?: "COMPOSE-REMEDIATION"
                            val rxNodeId = rx["target_node_id"]?.jsonPrimitive?.content

                            val rxWriteSiteSafe = rx["write_site_safe"]?.jsonPrimitive?.booleanOrNull

                            val vResult = server.remediationVerifier.verifyAndApply(
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
                            if (!vResult.valid) allPassed = false
                            results.add(buildJsonObject {
                                put("rule_id", rxRuleId)
                                put("file_path", rxFilePath)
                                if (rxNodeId != null) put("target_node_id", rxNodeId)
                                put("valid", vResult.valid)
                                put("phase", vResult.phase)
                                put("message", vResult.message)
                            })
                        }

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
                        return if (allPassed) 0 else 1
                    }

                    if (isJson) {
                        println(jsonPretty.encodeToString(data))
                    } else {
                        val mode = data["scoring_mode"]?.jsonPrimitive?.content ?: "ESTIMATED"
                        val pCount = data["total_prescriptions"]?.jsonPrimitive?.intOrNull ?: 0
                        val topRule = data["prescriptions"]?.jsonArray?.firstOrNull()?.jsonObject?.get("rule_id")?.jsonPrimitive?.content ?: "NONE"
                        val text = "[Compose Doctor] project=$project mode=$mode prescriptions=$pCount top_rule=$topRule"
                        println(truncateSummary(text, false))
                    }
                    return 0
                }
                "synthesize-scope" -> {
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
                "version" -> {
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
                } catch (_: Throwable) {}
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

        private fun runComplexityDoctorCommand(
            server: AtlasMcpServer,
            project: String,
            target: String,
            limit: Int,
            apply: Boolean,
            testCmd: String?,
            threshold: Int?,
            isJson: Boolean
        ): Int {
            val toolArgs = buildJsonObject {
                put("action", "complexity_doctor")
                put("project", project)
                if (target.isNotEmpty()) put("target", target)
                put("limit", limit)
                put("apply", apply)
                if (threshold != null) put("threshold", threshold)
                if (!testCmd.isNullOrBlank()) put("test_command", testCmd)
            }
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            val total = data["total_prescriptions"]?.jsonPrimitive?.intOrNull ?: 0
            val verified = data["verified_count"]?.jsonPrimitive?.intOrNull ?: 0
            val rolledBack = data["rolled_back_count"]?.jsonPrimitive?.intOrNull ?: 0
            val prescriptions = data["prescriptions"]?.jsonArray ?: JsonArray(emptyList())

            if (isJson) {
                println(jsonPretty.encodeToString(data))
            } else {
                println("[Complexity Doctor] project=$project prescriptions=$total applied=$verified rolled_back=$rolledBack")
                for (elem in prescriptions.take(limit)) {
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
            }
            return 0
        }

        private fun runComplexityAuditCommand(
            server: AtlasMcpServer,
            project: String,
            paths: List<String>,
            target: String,
            threshold: Int?,
            maxCyclomatic: Int? = null,
            isJson: Boolean
        ): Int {
            val filterPaths = if (paths.isNotEmpty()) paths else (if (target.isNotEmpty()) listOf(target) else emptyList())
            val toolArgs = buildJsonObject {
                put("action", "audit_complexity")
                put("project", project)
                if (filterPaths.isNotEmpty()) {
                    putJsonArray("targets") { filterPaths.forEach { add(it) } }
                }
                if (threshold != null) put("threshold", threshold)
                if (maxCyclomatic != null) put("max_cyclomatic", maxCyclomatic)
            }
            val res = server.callTool("atlas_audit", toolArgs)
            val data = loadSpilledData(res)
            val passed = data["passed"]?.jsonPrimitive?.booleanOrNull ?: true
            val total = data["total_violations"]?.jsonPrimitive?.intOrNull ?: 0
            val high = data["high_severity_count"]?.jsonPrimitive?.intOrNull ?: 0
            val warn = data["warning_count"]?.jsonPrimitive?.intOrNull ?: 0
            val thresh = data["threshold"]?.jsonPrimitive?.intOrNull ?: (threshold ?: 10)

            val text = if (isJson) {
                jsonPretty.encodeToString(data)
            } else {
                "[Complexity Audit] project=$project passed=$passed threshold=$thresh violations=$total (HIGH=$high, WARNING=$warn)"
            }
            println(truncateSummary(text, isJson))
            return if (passed) 0 else 1
        }

        private fun runComplexityQueryCommand(
            server: AtlasMcpServer,
            project: String,
            target: String,
            threshold: Int?,
            limit: Int,
            isJson: Boolean
        ): Int {
            val toolArgs = buildJsonObject {
                put("action", "complexity")
                put("project", project)
                if (target.isNotEmpty()) put("target", target)
                if (threshold != null) put("threshold", threshold)
                put("limit", limit)
            }
            val res = server.callTool("atlas_query", toolArgs)
            val data = loadSpilledData(res)
            val results = data["results"]?.jsonArray ?: JsonArray(emptyList())

            if (isJson) {
                println(jsonPretty.encodeToString(data))
            } else {
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
                    val r = elem.jsonObject
                    val name = (r["name"]?.jsonPrimitive?.content ?: "").take(30)
                    val file = r["file_path"]?.jsonPrimitive?.content ?: ""
                    val line = r["line"]?.jsonPrimitive?.intOrNull ?: 0
                    val loc = ("$file:$line").take(25)
                    val cog = r["cognitive"]?.jsonPrimitive?.intOrNull ?: 0
                    val cyclo = r["cyclomatic"]?.jsonPrimitive?.intOrNull ?: 0
                    val nest = r["max_nesting"]?.jsonPrimitive?.intOrNull ?: 0
                    val risk = r["risk_index"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val roundedRisk = ((risk * 10.0).roundToInt() / 10.0).toString()
                    val grade = r["grade"]?.jsonPrimitive?.content ?: "A"
                    val row = name.padEnd(30) + " " +
                        loc.padEnd(25) + " " +
                        cog.toString().padStart(9) + " " +
                        cyclo.toString().padStart(7) + " " +
                        nest.toString().padStart(7) + " " +
                        roundedRisk.padStart(10) + " " +
                        grade.padStart(5)
                    println(row)
                }
            }
            return 0
        }

        private fun runInitCommand(
            workspace: String,
            project: String,
            hookType: String,
            force: Boolean,
            threshold: Int,
            dryRun: Boolean,
            isJson: Boolean
        ): Int {
            val engine = WorkspaceInitEngine(workspace)
            val result = engine.initHook(
                project = project,
                hookType = hookType,
                force = force,
                threshold = threshold,
                dryRun = dryRun
            )

            if (isJson) {
                val jsonObject = buildJsonObject {
                    put("success", result.success)
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
            } else {
                if (result.success) {
                    println("[Init] $project: ${result.message}")
                    if (result.dryRun && result.scriptContent != null) {
                        println("--- Script Preview ---")
                        println(result.scriptContent)
                        println("----------------------")
                    }
                } else {
                    println("[Init Error] $project: ${result.message}")
                }
            }
            return if (result.success) 0 else 1
        }

        private fun runMigrateToolsCommand(
            server: AtlasMcpServer,
            paths: List<String>,
            target: String,
            applyRemediation: Boolean,
            isJson: Boolean
        ): Int {
            val scanPaths = if (paths.isNotEmpty()) {
                paths
            } else if (target.isNotEmpty()) {
                listOf(target)
            } else {
                listOf(server.workspaceRoot)
            }
            val report = ToolMigrationEngine.scanAndMigrate(scanPaths, apply = applyRemediation)
            if (isJson) {
                println(jsonPretty.encodeToString(report))
            } else {
                val mode = if (applyRemediation) "applied" else "dry-run"
                val sample = if (report.matches.isNotEmpty()) {
                    val s = report.matches.take(5).joinToString("; ") { "${it.legacyTool} in ${it.file}:${it.line}" }
                    " top=[$s]"
                } else ""
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
