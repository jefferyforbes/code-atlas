package atlas.mcp

import atlas.bridge.VaultPlanBridge
import atlas.config.CacheProfileResolver
import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectureAuditor
import atlas.engine.ScopeSynthesizer
import atlas.engine.WorkMemoryStore
import atlas.extract.ModuleBoundaryDetector
import atlas.extract.PolyglotAstExtractor
import atlas.extract.SemioticLayerClassifier
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import atlas.util.Sha256
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import platform.posix.fflush
import platform.posix.stdout
import platform.posix.time

/**
 * Standards-compliant 2-tool MCP Server and incremental workspace indexer with 32KB disk spillover.
 */
@OptIn(ExperimentalForeignApi::class)
class CodeGraphMcpServer(
    val workspaceRoot: String,
    val customDbPath: String? = null,
    val cacheProfile: String? = null
) {
    companion object {
        const val SPILLOVER_THRESHOLD_BYTES = 32768

        private val SKIP_DIRS = setOf(
            ".git", ".gradle", ".idea", ".venv", "venv", "node_modules",
            "build", "dist", "__pycache__", ".pytest_cache", ".artifacts",
            ".agents", ".agent", ".atlas"
        )
    }

    val dbPath: String
    val artifactsDir: String
    val store: SqliteGraphStore
    val synthesizer: ScopeSynthesizer
    val auditor: ArchitectureAuditor
    val memory: WorkMemoryStore

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
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
                    }
                    put("description", "Query operation to execute against the indexed polyglot code and vault graph.")
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
                    put("description", "Maximum traversal hops for callers, callees, or blast-radius impact (default: 2).")
                }
                putJsonObject("include_source") {
                    put("type", "boolean")
                    put("description", "Include live SHA-256 verified source lines sliced from disk (default: false).")
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
                    }
                    put("description", "Audit, synthesis, learning, or indexing action to execute.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Target project slug.")
                }
                putJsonObject("targets") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Seed symbols, file paths, or plan names to scope, audit, or record outcomes against.")
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
            }
            putJsonArray("required") {
                add("action")
                add("project")
            }
        }

        return listOf(
            buildJsonObject {
                put("name", "codegraph_query")
                put("description", "Query indexed polyglot AST symbols, callers, callees, blast radius, God nodes, or live source slices.")
                put("inputSchema", querySchema)
            },
            buildJsonObject {
                put("name", "codegraph_audit")
                put("description", "Audit architectural layers, verify single-declaration rules, record outcomes, or synthesize 3D scopes.")
                put("inputSchema", auditSchema)
            }
        )
    }

    fun indexWorkspace(
        projectId: String,
        subpaths: List<String>? = null
    ): JsonObject {
        val root = PlatformFiles.canonicalPath(workspaceRoot)
        val (modNodes, modEdges, knownModules) = ModuleBoundaryDetector.extractModuleGraph(root, projectId)

        val allNodes = modNodes.toMutableList()
        val rawEdges = modEdges.toMutableList()
        val markdownQueue = mutableListOf<Pair<String, String>>()
        var filesIndexed = 0
        var filesSkipped = 0

        val allFiles = PlatformFiles.listFilesRecursively(root)
        val validDiskFiles = mutableSetOf<String>()
        for (filePath in allFiles) {
            val relStr = filePath.removePrefix(root).removePrefix("/").replace('\\', '/')
            val parts = relStr.split('/')
            if (parts.any { it in SKIP_DIRS || it.startsWith(".venv") }) continue

            if (subpaths != null && subpaths.isNotEmpty()) {
                val matchesSubpath = subpaths.any { sp ->
                    val cleanSp = sp.trimEnd('/')
                    relStr == cleanSp || relStr.startsWith("$cleanSp/")
                }
                if (!matchesSubpath) continue
            }

            val ext = if (relStr.contains('.')) "." + relStr.substringAfterLast('.') else ""
            val lang = PolyglotAstExtractor.supportedLanguage(ext)

            if (lang.isEmpty() && ext != ".md") continue

            validDiskFiles.add(relStr)

            val rawBytes = PlatformFiles.readBytes(filePath)
            val sha256 = Sha256.digest(rawBytes)
            val mtime = PlatformFiles.mtime(filePath)
            val sourceText = rawBytes.decodeToString()

            if (ext == ".md") {
                if (store.needsReindex(projectId, relStr, sha256, mtime)) {
                    markdownQueue.add(Pair(relStr, sourceText))
                    store.upsertFileRecord(
                        projectId = projectId,
                        relPath = relStr,
                        language = "markdown",
                        moduleId = if (relStr.startsWith("plans/")) "plans" else "vault",
                        sourceSet = "docs",
                        semioticLayer = if (relStr.startsWith("plans/")) "syntactic" else "semantic",
                        archLayer = "infrastructure",
                        sha256 = sha256,
                        mtime = mtime,
                        loc = sourceText.lines().size,
                        topLevelDeclCount = 1
                    )
                    filesIndexed++
                } else {
                    filesSkipped++
                }
                continue
            }

            if (!store.needsReindex(projectId, relStr, sha256, mtime)) {
                filesSkipped++
                continue
            }

            val (fNodes, fEdges, topDeclCount) = PolyglotAstExtractor.extractFile(
                projectId, relStr, sourceText, knownModules
            )
            val (semLayer, archLayer, _) = SemioticLayerClassifier.classify(relStr)
            val modId = ModuleBoundaryDetector.resolveModuleId(relStr, knownModules)
            val srcSet = SemioticLayerClassifier.detectSourceSet(relStr)

            store.upsertFileRecord(
                projectId = projectId,
                relPath = relStr,
                language = lang,
                moduleId = modId,
                sourceSet = srcSet,
                semioticLayer = semLayer,
                archLayer = archLayer,
                sha256 = sha256,
                mtime = mtime,
                loc = sourceText.lines().size,
                topLevelDeclCount = topDeclCount
            )
            allNodes.addAll(fNodes)
            rawEdges.addAll(fEdges)
            filesIndexed++
        }

        val indexedFiles = store.listIndexedFiles(projectId)
        for (indexedRel in indexedFiles) {
            if (subpaths != null && subpaths.isNotEmpty()) {
                val matchesSubpath = subpaths.any { sp ->
                    val cleanSp = sp.trimEnd('/')
                    indexedRel == cleanSp || indexedRel.startsWith("$cleanSp/")
                }
                if (!matchesSubpath) continue
            }
            if (indexedRel !in validDiskFiles) {
                store.deleteFileRecord(projectId, indexedRel)
            }
        }

        val existingNodes = store.listNodes(projectId)
        val mergedNodeMap = existingNodes.associateBy { it.nodeId }.toMutableMap()
        for (n in allNodes) {
            mergedNodeMap[n.nodeId] = n
        }
        val combinedNodes = mergedNodeMap.values.toList()

        val resolvedEdges = PolyglotAstExtractor.resolveCrossFileEdges(
            projectId, combinedNodes, rawEdges
        )
        val kmpEdges = ModuleBoundaryDetector.synthesizeKmpExpectActualEdges(
            projectId, combinedNodes
        )

        val docNodes = mutableListOf<GraphNode>()
        val docEdges = mutableListOf<GraphEdge>()
        for ((relMd, mdText) in markdownQueue) {
            val (dNodes, dEdges) = VaultPlanBridge.extractMarkdownAndPlans(
                projectId, relMd, mdText, combinedNodes
            )
            docNodes.addAll(dNodes)
            docEdges.addAll(dEdges)
        }

        store.upsertNodesAndEdges(
            projectId = projectId,
            nodes = allNodes + docNodes,
            edges = modEdges + resolvedEdges + kmpEdges + docEdges
        )

        val stats = store.getStats(projectId)
        return buildJsonObject {
            put("project_id", stats.projectId)
            put("files", stats.files)
            put("nodes", stats.nodes)
            put("edges", stats.edges)
            put("files_indexed", filesIndexed)
            put("files_skipped", filesSkipped)
            putJsonObject("provenance_breakdown") {
                stats.provenanceBreakdown.forEach { (k, v) -> put(k, v) }
            }
        }
    }

    fun callTool(name: String, arguments: JsonObject): JsonObject {
        val project = arguments["project"]?.jsonPrimitive?.content ?: "organisation"
        val payload = when (name) {
            "codegraph_query", "atlas_query" -> handleQuery(arguments)
            "codegraph_audit", "atlas_audit" -> handleAudit(arguments)
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

        return when (action) {
            "sync_index" -> indexWorkspace(project, subpaths = if (targets.isNotEmpty()) targets else null)
            "synthesize_3d_scope" -> {
                val rec = synthesizer.synthesizeScope(project, targets)
                val baseObj = json.encodeToJsonElement(rec).jsonObject
                buildJsonObject {
                    baseObj.forEach { (k, v) -> put(k, v) }
                    put("yaml_block", rec.toYamlBlock())
                }
            }
            "verify_architecture" -> {
                val report = auditor.runFullAudit(project)
                json.encodeToJsonElement(report).jsonObject
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
            else -> throw IllegalArgumentException("Unsupported codegraph_audit action: $action")
        }
    }

    private fun enforceSpillover(projectId: String, payload: JsonObject): JsonObject {
        val rawJson = json.encodeToString(payload)
        val byteLen = rawJson.encodeToByteArray().size
        if (byteLen <= SPILLOVER_THRESHOLD_BYTES) {
            return payload
        }

        val ts = (time(null) * 1000).toLong()
        val spillFile = "$artifactsDir/spillover_${projectId}_$ts.json"
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

    fun serveStdio() {
        while (true) {
            val line = readlnOrNull() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val req = try {
                json.parseToJsonElement(trimmed).jsonObject
            } catch (_: Throwable) {
                continue
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
                            put("version", "0.2.0")
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
                    if (reqId == null) continue
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", reqId)
                        putJsonObject("result") {}
                    }
                }
            }

            println(json.encodeToString(resp))
            fflush(stdout)
        }
    }
}
