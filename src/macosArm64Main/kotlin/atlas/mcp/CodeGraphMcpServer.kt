package atlas.mcp

import atlas.config.CacheProfileResolver
import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectureAuditor
import atlas.engine.AutonomousRemediationVerifier
import atlas.engine.ComposeStabilityEngine
import atlas.engine.FigmaDesignAuditor
import atlas.engine.ScopeSynthesizer
import atlas.engine.WorkMemoryStore
import atlas.engine.WorkspaceIndexer
import atlas.util.PlatformFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import platform.posix.fflush
import platform.posix.stdout
import platform.posix.time

/**
 * Standards-compliant 4-tool MCP Server and incremental workspace indexer with 32KB disk spillover.
 */
@OptIn(ExperimentalForeignApi::class)
class CodeGraphMcpServer(
    val workspaceRoot: String,
    val customDbPath: String? = null,
    val cacheProfile: String? = null
) {
    companion object {
        const val SPILLOVER_THRESHOLD_BYTES = 32768
        private var spilloverSeq = 0L
    }

    val dbPath: String
    val artifactsDir: String
    val store: SqliteGraphStore
    val synthesizer: ScopeSynthesizer
    val auditor: ArchitectureAuditor
    val memory: WorkMemoryStore
    val figmaAuditor: FigmaDesignAuditor
    val composeEngine: ComposeStabilityEngine
    val indexer: WorkspaceIndexer
    val remediationVerifier: AutonomousRemediationVerifier

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    init {
        val (resolvedDb, artifacts) = CacheProfileResolver.resolvePaths(
            workspaceRoot = workspaceRoot,
            customDb = customDbPath,
            profile = cacheProfile
        )
        dbPath = resolvedDb
        artifactsDir = artifacts
        store = SqliteGraphStore(dbPath)
        synthesizer = ScopeSynthesizer(store)
        auditor = ArchitectureAuditor(store)
        memory = WorkMemoryStore(store)
        figmaAuditor = FigmaDesignAuditor(store)
        composeEngine = ComposeStabilityEngine(store)
        indexer = WorkspaceIndexer(workspaceRoot, store)
        remediationVerifier = AutonomousRemediationVerifier(workspaceRoot, store)
    }

    fun toolDefinitions(): List<JsonObject> {
        val querySchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("search")
                        add("symbol")
                        add("callers")
                        add("callees")
                        add("impact")
                        add("god_nodes")
                        add("shortest_path")
                        add("status")
                        add("compose_summary")
                        add("compose_cascade")
                        add("compose_blame")
                    }
                    put("description", "Query operation to execute against the indexed polyglot code, Compose stability, and vault graph.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Target project slug (for example: organisation, relay, reflect, forma).")
                }
                putJsonObject("target") {
                    put("type", "string")
                    put("description", "Symbol name, qualified identifier, file path, or search query string.")
                }
                putJsonObject("secondary_target") {
                    put("type", "string")
                    put("description", "Destination symbol or node identifier when computing shortest_path.")
                }
                putJsonObject("depth") {
                    put("type", "integer")
                    put("description", "Maximum traversal hops for callers, callees, cascade, or blast-radius impact (default: 2).")
                }
                putJsonObject("include_source") {
                    put("type", "boolean")
                    put("description", "Include live SHA-256 verified source lines sliced from disk (default: false).")
                }
                putJsonObject("strong_skipping") {
                    put("type", "boolean")
                    put("description", "Evaluate Compose stability under Kotlin 2.0+ Strong Skipping rules (default: true).")
                }
            }
            putJsonArray("required") {
                add("action")
                add("project")
            }
        }

        val auditSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("verify_architecture")
                        add("synthesize_3d_scope")
                        add("record_memory")
                        add("sync_index")
                        add("dead_code")
                        add("figma_audit")
                        add("figma_scope")
                        add("audit_compose")
                        add("compose_doctor")
                        add("synthesize_compose_scope")
                        add("verify_remediation")
                    }
                    put("description", "Audit, synthesis, Compose compliance, learning, or indexing action to execute.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Target project slug.")
                }
                putJsonObject("file_key") {
                    put("type", "string")
                    put("description", "Figma file key or document slug for figma_audit or figma_scope.")
                }
                putJsonObject("targets") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Seed symbols, file paths, plan names, or Figma nodes to scope, audit, or record outcomes.")
                }
                putJsonObject("outcome") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("useful")
                        add("dead_end")
                        add("corrected")
                    }
                    put("description", "Agent outcome annotation to record into experiential work memory.")
                }
                putJsonObject("note") {
                    put("type", "string")
                    put("description", "Short rationale or learning note explaining why this path was useful or a dead end.")
                }
                putJsonObject("stability_config_path") {
                    put("type", "string")
                    put("description", "Optional path to a Compose stability_config.conf file for wildcard type matching.")
                }
                putJsonObject("compiler_reports_dir") {
                    put("type", "string")
                    put("description", "Optional directory or file path for Compose Compiler metrics reports.")
                }
                putJsonObject("trace_log_path") {
                    put("type", "string")
                    put("description", "Optional file path for bounded @TraceRecomposition JSONL or Logcat runtime telemetry.")
                }
                putJsonObject("strong_skipping") {
                    put("type", "boolean")
                    put("description", "Evaluate Compose audit rules under Kotlin 2.0+ Strong Skipping mode (default: true).")
                }
                putJsonObject("file_path") {
                    put("type", "string")
                    put("description", "Target file path for autonomous remediation verification.")
                }
                putJsonObject("before_snippet") {
                    put("type", "string")
                    put("description", "Original code snippet targeted for replacement.")
                }
                putJsonObject("after_snippet") {
                    put("type", "string")
                    put("description", "Proposed replacement code snippet.")
                }
                putJsonObject("rule_id") {
                    put("type", "string")
                    put("description", "Target rule identifier (e.g. COMPOSE-STAB-001, COMPOSE-API-001).")
                }
                putJsonObject("target_node_id") {
                    put("type", "string")
                    put("description", "Optional target composable/symbol identifier to scope rule clearance verification.")
                }
                putJsonObject("write_site_safe") {
                    put("type", "boolean")
                    put("description", "Write-site safety indicator (default: true).")
                }
                putJsonObject("dry_run") {
                    put("type", "boolean")
                    put("description", "Validate without committing changes to disk (default: true).")
                }
                putJsonObject("test_command") {
                    put("type", "string")
                    put("description", "Optional shell command to verify test suite preservation (e.g. './gradlew test').")
                }
            }
            putJsonArray("required") {
                add("action")
                add("project")
            }
        }

        val figmaAuditSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_key") {
                    put("type", "string")
                    put("description", "Figma document file key or identifier.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Target project slug (default: organisation).")
                }
            }
            putJsonArray("required") {
                add("file_key")
            }
        }

        val figmaScopeSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_key") {
                    put("type", "string")
                    put("description", "Figma document file key or identifier.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Target project slug (default: organisation).")
                }
                putJsonObject("seeds") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Seed node names, component names, or IDs to scope.")
                }
            }
            putJsonArray("required") {
                add("file_key")
            }
        }

        return listOf(
            buildJsonObject {
                put("name", "codegraph_query")
                put("description", "Query indexed polyglot AST symbols, callers, callees, blast radius, Compose stability, or live source slices.")
                put("inputSchema", querySchema)
            },
            buildJsonObject {
                put("name", "codegraph_audit")
                put("description", "Audit architectural layers, Compose Gate 1 compliance, Doctor fixes, or synthesize 3D scopes.")
                put("inputSchema", auditSchema)
            },
            buildJsonObject {
                put("name", "figma_audit")
                put("description", "Run 12 deterministic design invariant audits and evaluate Gate 1 maturity compliance.")
                put("inputSchema", figmaAuditSchema)
            },
            buildJsonObject {
                put("name", "figma_scope")
                put("description", "Synthesize Figma 3D Concurrency Scope (spatial canvas bounds, shared components, token layers).")
                put("inputSchema", figmaScopeSchema)
            }
        )
    }

    private val projectServers = mutableMapOf<String, CodeGraphMcpServer>()

    fun indexWorkspace(
        projectId: String,
        subpaths: List<String>? = null
    ): JsonObject = resolveServerForProject(projectId).indexer.indexWorkspace(projectId, subpaths)

    fun indexComposeArtifacts(
        projectId: String,
        subpaths: List<String>? = null,
        stabilityConfigPath: String? = null,
        compilerReportsDir: String? = null,
        traceLogPath: String? = null
    ): JsonObject = resolveServerForProject(projectId).indexer.indexComposeArtifacts(
        projectId = projectId,
        subpaths = subpaths,
        stabilityConfigPath = stabilityConfigPath,
        compilerReportsDir = compilerReportsDir,
        traceLogPath = traceLogPath
    )

    private fun resolveServerForProject(projectId: String): CodeGraphMcpServer {
        if (customDbPath != null || projectId.isEmpty()) return this
        val localStats = store.getStats(projectId)
        if (localStats.files > 0) return this

        val candidateWs = CacheProfileResolver.resolveProjectWorkspace(workspaceRoot, projectId)
        val canonCurrent = PlatformFiles.canonicalPath(workspaceRoot).replace('\\', '/').trimEnd('/')
        val canonCandidate = PlatformFiles.canonicalPath(candidateWs).replace('\\', '/').trimEnd('/')
        if (canonCandidate == canonCurrent) return this

        return projectServers.getOrPut(canonCandidate) {
            CodeGraphMcpServer(
                workspaceRoot = canonCandidate,
                customDbPath = null,
                cacheProfile = cacheProfile
            )
        }
    }

    fun close() {
        projectServers.values.forEach { it.store.close() }
        projectServers.clear()
        store.close()
    }

    fun callTool(name: String, arguments: JsonObject): JsonObject {
        val project = arguments["project"]?.jsonPrimitive?.content ?: "organisation"
        val activeServer = resolveServerForProject(project)
        if (activeServer !== this) {
            return activeServer.callToolDirect(name, arguments)
        }
        return callToolDirect(name, arguments)
    }

    private fun callToolDirect(name: String, arguments: JsonObject): JsonObject {
        val project = arguments["project"]?.jsonPrimitive?.content ?: "organisation"
        val payload = when (name) {
            "codegraph_query", "atlas_query" -> handleQuery(arguments)
            "codegraph_audit", "atlas_audit" -> handleAudit(arguments)
            "figma_audit" -> handleFigmaAudit(arguments)
            "figma_scope" -> handleFigmaScope(arguments)
            else -> throw IllegalArgumentException("Unknown MCP tool: $name")
        }
        return enforceSpillover(project, payload)
    }

    private fun handleQuery(args: JsonObject): JsonObject {
        val action = args["action"]?.jsonPrimitive?.content ?: "status"
        val project = args["project"]?.jsonPrimitive?.content ?: "organisation"
        val target = args["target"]?.jsonPrimitive?.content ?: ""
        val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 2
        val includeSource = args["include_source"]?.jsonPrimitive?.booleanOrNull ?: false
        val strongSkipping = args["strong_skipping"]?.jsonPrimitive?.booleanOrNull ?: true

        when (action) {
            "status" -> {
                val stats = store.getStats(project)
                return json.encodeToJsonElement(stats).jsonObject
            }
            "god_nodes" -> {
                val gods = store.getGodNodes(project, topN = 10)
                return buildJsonObject {
                    put("project", project)
                    put("god_nodes", json.encodeToJsonElement(gods))
                }
            }
            "search" -> {
                val matches = store.searchNodes(project, target, limit = 15)
                return buildJsonObject {
                    put("project", project)
                    put("query", target)
                    put("results", json.encodeToJsonElement(matches))
                }
            }
            "compose_summary", "compose_cascade", "compose_blame" -> {
                val mappedAction = action.removePrefix("compose_")
                val res = composeEngine.queryCompose(
                    projectSlug = project,
                    action = mappedAction,
                    target = target.ifEmpty { null },
                    maxDepth = if (args.containsKey("depth")) depth else 5,
                    strongSkipping = strongSkipping
                )
                return json.encodeToJsonElement(res).jsonObject
            }
        }

        val node = store.getNode(project, target)
        if (node == null) {
            val matches = store.searchNodes(project, target, limit = 5)
            return buildJsonObject {
                put("project", project)
                put("error", "Symbol '$target' not found directly.")
                put("candidates", json.encodeToJsonElement(matches))
            }
        }

        return when (action) {
            "symbol" -> {
                val annotations = memory.getActiveAnnotations(project, node.nodeId)
                buildJsonObject {
                    put("node", json.encodeToJsonElement(node))
                    put("annotations", json.encodeToJsonElement(annotations))
                    put("callers_count", node.inDegree)
                    put("callees_count", node.outDegree)
                    if (includeSource) {
                        val slice = store.sliceLiveSource(workspaceRoot, project, node)
                        put("live_source", json.encodeToJsonElement(slice))
                    }
                }
            }
            "callers" -> {
                val callers = store.getCallers(project, node.nodeId, depth = depth)
                buildJsonObject {
                    put("node_id", node.nodeId)
                    put("callers", json.encodeToJsonElement(callers))
                }
            }
            "callees" -> {
                val callees = store.getCallees(project, node.nodeId, depth = depth)
                buildJsonObject {
                    put("node_id", node.nodeId)
                    put("callees", json.encodeToJsonElement(callees))
                }
            }
            "impact" -> {
                val impact = store.getImpactRadius(project, node.nodeId, depth = depth)
                json.encodeToJsonElement(impact).jsonObject
            }
            "shortest_path" -> {
                val sec = args["secondary_target"]?.jsonPrimitive?.content ?: ""
                val tgtNode = store.getNode(project, sec)
                if (tgtNode == null) {
                    buildJsonObject { put("error", "Destination '$sec' not found.") }
                } else {
                    val path = store.getShortestPath(project, node.nodeId, tgtNode.nodeId)
                    buildJsonObject {
                        put("source", node.nodeId)
                        put("target", tgtNode.nodeId)
                        put("path", json.encodeToJsonElement(path))
                    }
                }
            }
            else -> throw IllegalArgumentException("Unsupported codegraph_query action: $action")
        }
    }

    private fun handleAudit(args: JsonObject): JsonObject {
        val action = args["action"]?.jsonPrimitive?.content ?: "verify_architecture"
        val project = args["project"]?.jsonPrimitive?.content ?: "organisation"
        val targets = args["targets"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val stabilityConfigPath = args["stability_config_path"]?.jsonPrimitive?.contentOrNull
        val compilerReportsDir = args["compiler_reports_dir"]?.jsonPrimitive?.contentOrNull
        val traceLogPath = args["trace_log_path"]?.jsonPrimitive?.contentOrNull
        val strongSkipping = args["strong_skipping"]?.jsonPrimitive?.booleanOrNull ?: true
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 10

        return when (action) {
            "sync_index" -> {
                if (stabilityConfigPath != null || compilerReportsDir != null || traceLogPath != null) {
                    indexComposeArtifacts(
                        projectId = project,
                        subpaths = if (targets.isNotEmpty()) targets else null,
                        stabilityConfigPath = stabilityConfigPath,
                        compilerReportsDir = compilerReportsDir,
                        traceLogPath = traceLogPath
                    )
                } else {
                    indexWorkspace(project, subpaths = if (targets.isNotEmpty()) targets else null)
                }
            }
            "synthesize_3d_scope" -> {
                val rec = synthesizer.synthesizeScope(project, targets)
                val baseObj = json.encodeToJsonElement(rec).jsonObject
                buildJsonObject {
                    baseObj.forEach { (k, v) -> put(k, v) }
                    put("yaml_block", rec.toYamlBlock())
                }
            }
            "verify_architecture" -> {
                val report = auditor.runFullAudit(project, pathPrefixes = if (targets.isNotEmpty()) targets else null)
                json.encodeToJsonElement(report).jsonObject
            }
            "audit_compose" -> {
                if (stabilityConfigPath != null || compilerReportsDir != null || traceLogPath != null) {
                    indexComposeArtifacts(
                        projectId = project,
                        subpaths = if (targets.isNotEmpty()) targets else null,
                        stabilityConfigPath = stabilityConfigPath,
                        compilerReportsDir = compilerReportsDir,
                        traceLogPath = traceLogPath
                    )
                }
                val report = auditor.runComposeAudit(project, strongSkipping = strongSkipping)
                json.encodeToJsonElement(report).jsonObject
            }
            "compose_doctor" -> {
                val targetSym = targets.firstOrNull() ?: args["target"]?.jsonPrimitive?.contentOrNull
                val report = composeEngine.runDoctor(project, target = targetSym, limit = limit)
                json.encodeToJsonElement(report).jsonObject
            }
            "synthesize_compose_scope" -> {
                val scope = synthesizer.synthesizeComposeScope(project, targets)
                json.encodeToJsonElement(scope).jsonObject
            }
            "verify_remediation" -> {
                val filePath = args["file_path"]?.jsonPrimitive?.contentOrNull
                    ?: (targets.firstOrNull() ?: (args["target"]?.jsonPrimitive?.contentOrNull ?: ""))
                val beforeSnippet = args["before_snippet"]?.jsonPrimitive?.content ?: ""
                val afterSnippet = args["after_snippet"]?.jsonPrimitive?.content ?: ""
                val ruleId = args["rule_id"]?.jsonPrimitive?.content ?: "GENERIC"
                val targetNodeId = args["target_node_id"]?.jsonPrimitive?.contentOrNull
                val writeSiteSafe = args["write_site_safe"]?.jsonPrimitive?.booleanOrNull ?: true
                val dryRun = args["dry_run"]?.jsonPrimitive?.booleanOrNull ?: true
                val testCmd = args["test_command"]?.jsonPrimitive?.contentOrNull
                val res = resolveServerForProject(project).remediationVerifier.verifyAndApply(
                    projectSlug = project,
                    relPath = filePath,
                    beforeSnippet = beforeSnippet,
                    afterSnippet = afterSnippet,
                    ruleId = ruleId,
                    targetNodeId = targetNodeId,
                    writeSiteSafe = writeSiteSafe,
                    dryRun = dryRun,
                    testCommand = testCmd
                )
                json.encodeToJsonElement(res).jsonObject
            }
            "dead_code" -> {
                val deadNodes = auditor.detectDeadCode(project, pathPrefixes = if (targets.isNotEmpty()) targets else null)
                buildJsonObject {
                    put("project", project)
                    put("dead_code_count", deadNodes.size)
                    put("dead_nodes", json.encodeToJsonElement(deadNodes))
                }
            }
            "record_memory" -> {
                val targetStr = if (targets.isNotEmpty()) targets[0] else ""
                val node = store.getNode(project, targetStr)
                val nodeId = node?.nodeId ?: targetStr
                val outcome = args["outcome"]?.jsonPrimitive?.content ?: "useful"
                val note = args["note"]?.jsonPrimitive?.content ?: ""
                val annId = memory.recordOutcome(project, nodeId, outcome, note)
                buildJsonObject {
                    put("annotation_id", annId)
                    put("node_id", nodeId)
                    put("outcome", outcome)
                }
            }
            "figma_audit" -> handleFigmaAudit(args)
            "figma_scope" -> handleFigmaScope(args)
            else -> throw IllegalArgumentException("Unsupported codegraph_audit action: $action")
        }
    }

    private fun handleFigmaAudit(args: JsonObject): JsonObject {
        val fileKey = args["file_key"]?.jsonPrimitive?.content
            ?: args["fileKey"]?.jsonPrimitive?.content
            ?: args["target"]?.jsonPrimitive?.content
            ?: (args["targets"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content)
            ?: ""
        if (fileKey.isEmpty()) {
            return buildJsonObject {
                put("error", "Missing required 'file_key' parameter for figma_audit")
            }
        }
        val result = figmaAuditor.audit(fileKey)
        return json.encodeToJsonElement(result).jsonObject
    }

    private fun handleFigmaScope(args: JsonObject): JsonObject {
        val project = args["project"]?.jsonPrimitive?.content ?: "organisation"
        val fileKey = args["file_key"]?.jsonPrimitive?.content
            ?: args["fileKey"]?.jsonPrimitive?.content
            ?: args["target"]?.jsonPrimitive?.content
            ?: (args["targets"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content)
            ?: ""
        if (fileKey.isEmpty()) {
            return buildJsonObject {
                put("error", "Missing required 'file_key' parameter for figma_scope")
            }
        }
        val seeds = args["seeds"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: args["targets"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: listOfNotNull(args["seed"]?.jsonPrimitive?.content ?: if (args.containsKey("file_key") || args.containsKey("fileKey")) args["target"]?.jsonPrimitive?.content else null)
        val scope = figmaAuditor.synthesizeFigmaScope(project, fileKey, seeds)
        return json.encodeToJsonElement(scope).jsonObject
    }

    private fun enforceSpillover(projectId: String, payload: JsonObject): JsonObject {
        val rawJson = json.encodeToString(payload)
        val byteLen = rawJson.encodeToByteArray().size
        if (byteLen <= SPILLOVER_THRESHOLD_BYTES) {
            return payload
        }

        val ts = (time(null) * 1000).toLong()
        val seq = ++spilloverSeq
        val spillFile = "$artifactsDir/spillover_${projectId}_${ts}_$seq.json"
        PlatformFiles.writeText(spillFile, rawJson)

        val gods = store.getGodNodes(projectId, topN = 5).map { it.name }
        return buildJsonObject {
            put("spillover", true)
            put("byte_length", byteLen)
            put("threshold_bytes", SPILLOVER_THRESHOLD_BYTES)
            put("artifact_uri", spillFile)
            put("top_central_nodes", json.encodeToJsonElement(gods))
            put("summary", "Payload ($byteLen bytes) exceeded 32KB ceiling and was written to $spillFile.")
        }
    }

    fun processJsonRpcMessage(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val req = try {
            json.parseToJsonElement(trimmed).jsonObject
        } catch (_: Throwable) {
            return null
        }

        val reqId = req["id"]
        val method = req["method"]?.jsonPrimitive?.content ?: ""
        val params = req["params"]?.jsonObject ?: buildJsonObject {}

        val resp = when (method) {
            "initialize" -> buildJsonObject {
                put("jsonrpc", "2.0")
                if (reqId != null) put("id", reqId)
                putJsonObject("result") {
                    put("protocolVersion", "2024-11-05")
                    putJsonObject("capabilities") {
                        putJsonObject("tools") {}
                    }
                    putJsonObject("serverInfo") {
                        put("name", "atlas")
                        put("version", "0.4.0")
                    }
                }
            }
            "tools/list" -> buildJsonObject {
                put("jsonrpc", "2.0")
                if (reqId != null) put("id", reqId)
                putJsonObject("result") {
                    put("tools", json.encodeToJsonElement(toolDefinitions()))
                }
            }
            "tools/call" -> {
                try {
                    val tName = params["name"]?.jsonPrimitive?.content ?: ""
                    val tArgs = params["arguments"]?.jsonObject ?: buildJsonObject {}
                    val out = callTool(tName, tArgs)
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        if (reqId != null) put("id", reqId)
                        putJsonObject("result") {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", json.encodeToString(out))
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        if (reqId != null) put("id", reqId)
                        putJsonObject("error") {
                            put("code", -32000)
                            put("message", e.message ?: "Unknown error")
                        }
                    }
                }
            }
            else -> {
                if (reqId == null) return null
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", reqId)
                    putJsonObject("error") {
                        put("code", -32601)
                        put("message", "Method not found: $method")
                    }
                }
            }
        }

        return json.encodeToString(resp)
    }

    fun serveStdio() {
        while (true) {
            val line = readlnOrNull() ?: break
            val resp = processJsonRpcMessage(line)
            if (resp != null) {
                println(resp)
                fflush(stdout)
            }
        }
    }
}
