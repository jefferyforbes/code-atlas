package atlas.cli

import atlas.mcp.CodeGraphMcpServer
import atlas.util.PlatformFiles
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * Command-line entrypoint for indexing, querying, synthesizing 3D scopes, and auditing code graphs.
 */
class CodeGraphCli {
    companion object {
        const val SUMMARY_CEILING_BYTES = 400

        private val jsonPretty = Json { prettyPrint = true }
        private val jsonCompact = Json { prettyPrint = false }

        fun run(argv: Array<String>): Int {
            if (argv.isEmpty() || argv.contains("--help") || argv.contains("-h")) {
                printHelp()
                return 0
            }

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

            var idx = 0
            while (idx < argv.size) {
                val arg = argv[idx]
                when {
                    arg == "index" || arg == "query" || arg == "synthesize-scope" || arg == "audit" || arg == "serve-mcp" -> {
                        command = arg
                    }
                    arg == "--project" && idx + 1 < argv.size -> project = argv[++idx]
                    arg == "--workspace" && idx + 1 < argv.size -> workspace = argv[++idx]
                    arg == "--db" && idx + 1 < argv.size -> customDb = argv[++idx]
                    arg == "--cache-profile" && idx + 1 < argv.size -> cacheProfile = argv[++idx]
                    arg == "--target" && idx + 1 < argv.size -> target = argv[++idx]
                    arg == "--action" && idx + 1 < argv.size -> action = argv[++idx]
                    arg == "--secondary-target" && idx + 1 < argv.size -> secondaryTarget = argv[++idx]
                    arg == "--depth" && idx + 1 < argv.size -> depth = argv[++idx].toIntOrNull() ?: 2
                    arg == "--json" -> isJson = true
                    arg == "--include-source" -> includeSource = true
                    arg == "--paths" -> {
                        idx++
                        while (idx < argv.size && !argv[idx].startsWith("--")) {
                            paths.add(argv[idx])
                            idx++
                        }
                        continue
                    }
                    !arg.startsWith("--") && command.isEmpty() -> command = arg
                    !arg.startsWith("--") && target.isEmpty() -> target = arg
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
                        val seeds = if (paths.isNotEmpty()) paths else (if (target.isNotEmpty()) listOf(target) else emptyList())
                        val toolArgs = buildJsonObject {
                            put("action", "synthesize_3d_scope")
                            put("project", project)
                            putJsonArray("targets") { seeds.forEach { add(it) } }
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
                            "[3D Scope] project=$project seeds=$seeds files=$fCount modules=$mods arch=$arch collisions=$collisions"
                        }
                        println(truncateSummary(text, isJson))
                        return 0
                    }
                    "audit" -> {
                        val toolArgs = buildJsonObject {
                            put("action", "verify_architecture")
                            put("project", project)
                        }
                        val res = server.callTool("codegraph_audit", toolArgs)
                        val data = loadSpilledData(res)
                        val text = if (isJson) {
                            jsonPretty.encodeToString(data)
                        } else {
                            val bViolations = (data["single_declaration_violations"] ?: data["singleDeclarationViolations"])?.jsonArray?.size ?: 0
                            val lInversions = (data["layer_inversions"] ?: data["layerInversions"])?.jsonArray?.size ?: 0
                            val pCollisions = (data["plan_3d_collisions"] ?: data["plan3dCollisions"])?.jsonArray?.size ?: 0
                            val passed = data["passed"]?.jsonPrimitive?.booleanOrNull ?: (bViolations == 0 && lInversions == 0 && pCollisions == 0)
                            "[Audit] project=$project passed=$passed boost003_violations=$bViolations layer_inversions=$lInversions plan_3d_collisions=$pCollisions"
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
                    else -> {
                        println("Unknown command: $command")
                        printHelp()
                        return 1
                    }
                }
            } finally {
                server.store.close()
            }
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

        private fun truncateSummary(text: String, isJson: Boolean): String {
            if (isJson) return text
            val bytes = text.encodeToByteArray()
            if (bytes.size <= SUMMARY_CEILING_BYTES) return text
            val truncatedBytes = bytes.copyOfRange(0, SUMMARY_CEILING_BYTES - 3)
            return truncatedBytes.decodeToString() + "..."
        }

        private fun printHelp() {
            println(
                """
                Atlas: Sovereign Polyglot AST & Vault Knowledge Graph Engine (macOS Native)

                Usage: atlas <command> [options]

                Commands:
                  index              Incrementally index workspace AST symbols and markdown notes
                  query              Query AST symbols, callers, callees, blast radius, or status
                  synthesize-scope   Synthesize 3D Concurrency Scope (files, modules, architecture)
                  audit              Verify Clean Architecture layers and Single Declaration Principle
                  serve-mcp          Run JSON-RPC 2.0 MCP server over stdio

                Options:
                  --project <name>         Target project slug (default: organisation)
                  --workspace <dir>        Workspace root directory (default: .)
                  --db <path>              Custom SQLite database path
                  --cache-profile <p>      'enterprise' (user cache) or 'in-repo' (.atlas/)
                  --target <sym>           Symbol name, path, or query
                  --secondary-target <sym> Target for shortest_path
                  --action <action>        Query action (search, symbol, callers, callees, impact, god_nodes, status)
                  --depth <int>            Traversal depth (default: 2)
                  --paths <p1> [p2...]     Subpaths to index or seed targets for scoping
                  --include-source         Include live disk source lines in query
                  --json                   Emit full formatted JSON output
                  --summary                Emit compact summary <= 400 bytes (default)
                """.trimIndent()
            )
        }
    }
}
