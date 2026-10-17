package atlas.cli

import atlas.engine.FigmaDesignAuditor
import atlas.extract.FigmaSnapshotExtractor
import atlas.mcp.CodeGraphMcpServer
import atlas.model.FigmaSpatialBounds
import atlas.util.PlatformFiles
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * Command-line entrypoint for indexing, querying, synthesizing 3D scopes, and auditing code graphs.
 */
class CodeGraphCli {
    companion object {
        const val VERSION = "0.4.0"
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
                (argv.size == 1 && (argv[0] == "figma" || argv[0] == "compose"))
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
            var outcome: String? = null
            var note: String? = null

            var idx = 0
            while (idx < argv.size) {
                val arg = argv[idx]
                when {
                    arg == "figma" -> subsystem = "figma"
                    arg == "compose" -> subsystem = "compose"
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
                    arg == "figma-shortest-path" -> {
                        subsystem = "figma"
                        command = "query"
                        action = "shortest_path"
                    }
                    arg == "index" || arg == "query" || arg == "synthesize-scope" || arg == "audit" || arg == "doctor" || arg == "dead-code" || arg == "dead_code" || arg == "record-memory" || arg == "record_memory" || arg == "serve-mcp" || arg == "version" -> {
                        command = when (arg) {
                            "dead_code" -> "dead-code"
                            "record_memory" -> "record-memory"
                            else -> arg
                        }
                    }
                    arg == "--compose" -> isComposeAuditFlag = true
                    arg == "--outcome" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> outcome = argv[++idx]
                    arg == "--note" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> note = argv[++idx]
                    arg == "--project" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> project = argv[++idx]
                    arg == "--workspace" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> workspace = argv[++idx]
                    arg == "--db" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> customDb = argv[++idx]
                    arg == "--cache-profile" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> cacheProfile = argv[++idx]
                    (arg == "--target" || arg == "--from") && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> target = argv[++idx]
                    arg == "--action" && idx + 1 < argv.size && !argv[idx + 1].startsWith("--") -> action = argv[++idx]
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

            val server = CodeGraphMcpServer(
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
                        isJson = isJson
                    )
                }

                when (command) {
                    "serve-mcp" -> {
                        server.serveStdio()
                        return 0
                    }
                    "index" -> {
                        val res = server.indexWorkspace(project, subpaths = if (paths.isNotEmpty()) paths else null)
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
                        val res = server.callTool("codegraph_audit", toolArgs)
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
                        val toolArgs = buildJsonObject {
                            put("action", "verify_architecture")
                            put("project", project)
                            if (filterPaths.isNotEmpty()) {
                                putJsonArray("targets") { filterPaths.forEach { add(it) } }
                            }
                            if (!outcome.isNullOrBlank()) put("outcome", outcome)
                            if (!note.isNullOrBlank()) put("note", note)
                        }
                        val res = server.callTool("codegraph_audit", toolArgs)
                        val data = loadSpilledData(res)
                        val bViolations = (data["single_declaration_violations"] ?: data["singleDeclarationViolations"])?.jsonArray?.size ?: 0
                        val lInversions = (data["layer_inversions"] ?: data["layerInversions"])?.jsonArray?.size ?: 0
                        val pCollisions = (data["plan_3d_collisions"] ?: data["plan3dCollisions"])?.jsonArray?.size ?: 0
                        val passed = data["passed"]?.jsonPrimitive?.booleanOrNull ?: (bViolations == 0 && lInversions == 0 && pCollisions == 0)
                        val text = if (isJson) {
                            jsonPretty.encodeToString(data)
                        } else {
                            "[Audit] project=$project passed=$passed boost003_violations=$bViolations layer_inversions=$lInversions plan_3d_collisions=$pCollisions"
                        }
                        println(truncateSummary(text, isJson))
                        return if (passed) 0 else 1
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
                        val res = server.callTool("codegraph_audit", toolArgs)
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
                        val res = server.callTool("codegraph_audit", toolArgs)
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
                        val res = server.callTool("codegraph_query", toolArgs)
                        val data = if (isJson) loadSpilledData(res) else res
                        if (isJson) {
                            println(jsonPretty.encodeToString(data))
                        } else {
                            val compact = jsonCompact.encodeToString(data)
                            println(truncateSummary(compact, false))
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
            server: CodeGraphMcpServer,
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
            val fileKey = fileKeyArg ?: server.store.getLatestFigmaFileKey() ?: "default"
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
                            val node = server.store.queryFigmaNodeById(fileKey, target)
                            val nodes = if (node != null) listOf(node) else server.store.queryFigmaNodesByName(fileKey, target)
                            val variable = if (nodes.isEmpty()) server.store.queryFigmaVariableByName(fileKey, target) ?: server.store.queryFigmaVariableById(fileKey, target) else null
                            if (isJson) {
                                val jsonElement = buildJsonObject {
                                    put("file_key", fileKey)
                                    put("target", target)
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
                                val text = "[Figma Query: symbol] target=$target found=$count matches=[${names.take(3).joinToString(", ")}]"
                                println(truncateSummary(text, false))
                            }
                            return 0
                        }
                        "callers" -> {
                            val callers = server.store.queryFigmaCallers(fileKey, target, depth)
                            if (isJson) {
                                println(jsonPretty.encodeToString(callers))
                            } else {
                                val names = callers.map { it.name }
                                val text = "[Figma Query: callers] target=$target callers=${callers.size} instances=[${names.take(3).joinToString(", ")}]"
                                println(truncateSummary(text, false))
                            }
                            return 0
                        }
                        "impact" -> {
                            val impact = server.store.resolveVariableBlastRadius(fileKey, target, depth)
                            if (isJson) {
                                println(jsonPretty.encodeToString(impact))
                            } else {
                                val vars = impact.impactedVariables.map { it.name }
                                val bound = impact.boundNodes.map { it.name }
                                val text = "[Figma Query: impact] target=$target impacted_vars=${vars.size} bound_nodes=${bound.size} vars=[${vars.take(3).joinToString(", ")}]"
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
                                val targetNode = server.store.queryFigmaNodeById(fileKey, target)
                                    ?: server.store.queryFigmaNodesByName(fileKey, target).firstOrNull()
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
                            val path = server.store.findFigmaShortestPath(fileKey, target, secondaryTarget, depth = maxOf(depth, 10))
                            if (isJson) {
                                println(jsonPretty.encodeToString(path))
                            } else {
                                val names = path.map { it.name }
                                val text = "[Figma Query: shortest_path] fileKey=$fileKey from=$target to=$secondaryTarget hops=${maxOf(0, path.size - 1)} path=[${names.joinToString(" -> ")}]"
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
                    val seedsList = if (seeds.isNotEmpty()) seeds else (if (target.isNotEmpty()) listOf(target) else emptyList())
                    val auditor = FigmaDesignAuditor(server.store)
                    val scope = auditor.synthesizeFigmaScope(project, fileKey, seedsList)
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
            server: CodeGraphMcpServer,
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
            isJson: Boolean
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
                    val res = server.callTool("codegraph_query", toolArgs)
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
                    val res = server.callTool("codegraph_audit", toolArgs)
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
                    val res = server.callTool("codegraph_audit", toolArgs)
                    val data = loadSpilledData(res)
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
                    val res = server.callTool("codegraph_audit", toolArgs)
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

        private fun printHelp() {
            println(
                """
                Atlas: Sovereign Polyglot AST & Vault Knowledge Graph Engine (macOS Native)

                Usage: atlas <command> [options]
                       atlas figma <command> [options]
                       atlas compose <command> [options]

                Code Commands:
                  index              Incrementally index workspace AST symbols and markdown notes
                  query              Query AST symbols, callers, callees, blast radius, or status
                  synthesize-scope   Synthesize 3D Concurrency Scope (files, modules, architecture)
                  audit              Verify Clean Architecture layers and Single Declaration Principle
                  dead-code          Detect dead code unreferenced across indexed modules
                  record-memory      Record execution outcome annotation for a node into work memory
                  serve-mcp          Run JSON-RPC 2.0 MCP server over stdio
                  version            Print version information

                Compose Commands:
                  compose index              Index Kotlin @Composable functions, stability_config.conf, compiler reports, and traces
                  compose query              Query Compose stability (summary, cascade, blame)
                  compose audit              Run 12-rule Compose Gate 1 compliance & stability audit (exit code 0/1)
                  compose doctor             Synthesize prioritized write-site verified Compose remediation previews
                  compose synthesize-scope   Synthesize Compose-aware 3D Concurrency Scope (shared models, CompositionLocals)

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
                  --outcome <status>       Outcome status for memory recording (default: useful)
                  --note <text>            Descriptive note or rationale for memory recording

                Query & Compose Options:
                  --target <sym>           Symbol name, node name, or ID
                  --secondary-target <sym> Target for shortest_path
                  --action <action>        Query action (symbol, callers, callees, impact, summary, cascade, blame)
                  --depth <int>            Traversal depth (default: 2)
                  --limit <int>            Max prescriptions for compose doctor (default: 10)
                  --paths <p1> [p2...]     Subpaths to index or seed targets for scoping
                  --include-source         Include live disk source lines in query
                  --compose                Run Compose Gate 1 audit when passed to 'atlas audit --compose'
                  --stability-config <p>   Path to Compose stability_config.conf
                  --compiler-reports <d>   Path to Compose Compiler metrics directory or report file
                  --trace-log <p>          Path to @TraceRecomposition JSONL/Logcat telemetry log
                  --strong-skipping <bool> Evaluate under Kotlin 2.0+ Strong Skipping mode (default: true)

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
