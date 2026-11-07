package atlas.mcp

import atlas.config.CacheProfileResolver
import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectureAuditor
import atlas.engine.ArchitectureRemediationEngine
import atlas.engine.AutonomousRemediationVerifier
import atlas.engine.CodegraphCompatibilityEngine
import atlas.engine.ComplexityDoctorEngine
import atlas.engine.ComposeStabilityEngine
import atlas.engine.FigmaDesignAuditor
import atlas.engine.ScopeSynthesizer
import atlas.engine.ToolMigrationEngine
import atlas.engine.WorkMemoryStore
import atlas.engine.WorkspaceIndexer
import atlas.engine.ArchitectContextEngine
import atlas.extract.ComplexityExtractor
import atlas.extract.UrlTargetResolver
import atlas.model.ArchitectContextResult
import atlas.model.ComplexityScoreRecord
import atlas.model.ComplexityViolation
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.math.roundToInt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Standards-compliant 4-tool MCP Server and incremental workspace indexer with 32KB disk spillover.
 */
class AtlasMcpServer(
    val workspaceRoot: String,
    val customDbPath: String? = null,
    val cacheProfile: String? = null,
    val enableCodegraphTools: Boolean = (PlatformFiles.getenv("ATLAS_CODEGRAPH_COMPAT") == "1")
) {
    companion object {
        const val VERSION = "0.8.0"
        const val SPILLOVER_THRESHOLD_BYTES = 32768
        const val MAX_CACHED_PROJECT_SERVERS = 16
        private val COMPOSE_QUERY_ACTIONS = setOf("summary", "compose_summary", "cascade", "compose_cascade", "blame", "compose_blame")
    }

    val dbPath: String
    val artifactsDir: String
    val store: SqliteGraphStore
    val synthesizer: ScopeSynthesizer
    val auditor: ArchitectureAuditor
    val memory: WorkMemoryStore
    val figmaAuditor: FigmaDesignAuditor
    val composeEngine: ComposeStabilityEngine
    val complexityDoctor: ComplexityDoctorEngine
    val indexer: WorkspaceIndexer
    val remediationVerifier: AutonomousRemediationVerifier
    val remediationEngine: ArchitectureRemediationEngine
    val codegraphEngine: CodegraphCompatibilityEngine
    val architectContextEngine: ArchitectContextEngine

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    internal val transport = McpJsonRpcTransport(this, json)

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
        auditor = ArchitectureAuditor(store, workspaceRoot)
        memory = WorkMemoryStore(store)
        figmaAuditor = FigmaDesignAuditor(store)
        composeEngine = ComposeStabilityEngine(store)
        complexityDoctor = ComplexityDoctorEngine(store, workspaceRoot)
        indexer = WorkspaceIndexer(workspaceRoot, store)
        remediationVerifier = AutonomousRemediationVerifier(workspaceRoot, store)
        remediationEngine = ArchitectureRemediationEngine(workspaceRoot, store, remediationVerifier)
        codegraphEngine = CodegraphCompatibilityEngine(store, workspaceRoot)
        architectContextEngine = ArchitectContextEngine(store, synthesizer, workspaceRoot, artifactsDir)
    }

    fun toolDefinitions(): List<JsonObject> = McpToolSchemas.toolDefinitions(enableCodegraphTools)

    internal val projectServers = mutableMapOf<String, AtlasMcpServer>()
    private val projectAccessOrder = mutableListOf<String>()

    private fun recordAccess(path: String) {
        projectAccessOrder.remove(path)
        projectAccessOrder.add(path)
    }

    private fun evictLeastRecentlyUsed() {
        if (projectAccessOrder.isEmpty()) return
        val lruKey = projectAccessOrder.removeAt(0)
        val evicted = projectServers.remove(lruKey)
        evicted?.close()
    }

    fun indexWorkspace(
        projectId: String,
        subpaths: List<String>? = null,
        force: Boolean = false
    ): JsonObject = resolveServerForProject(projectId).indexer.indexWorkspace(projectId, subpaths, force = force)

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

    private fun resolveCandidateWorkspacePath(projectId: String, explicitWorkspace: String?): String {
        if (!explicitWorkspace.isNullOrEmpty() && explicitWorkspace != "." && explicitWorkspace != workspaceRoot) {
            val path = if (PlatformFiles.isDirectory(explicitWorkspace)) explicitWorkspace
                       else PlatformFiles.parentOf(explicitWorkspace)
            return PlatformFiles.canonicalPath(path)
        }
        return CacheProfileResolver.resolveProjectWorkspace(workspaceRoot, projectId)
    }

    private fun normalizeWorkspacePath(path: String): String =
        PlatformFiles.canonicalPath(path).replace('\\', '/').trimEnd('/')

    fun resolveServerForProject(projectId: String, explicitWorkspace: String? = null): AtlasMcpServer {
        if (customDbPath != null || (projectId.isEmpty() && explicitWorkspace.isNullOrEmpty())) return this
        if (store.getStats(projectId).files > 0) return this

        val canonCurrent = normalizeWorkspacePath(workspaceRoot)
        val canonCandidate = normalizeWorkspacePath(resolveCandidateWorkspacePath(projectId, explicitWorkspace))
        if (canonCandidate == canonCurrent) return this

        val existing = projectServers[canonCandidate]
        if (existing != null) {
            recordAccess(canonCandidate)
            return existing
        }

        while (projectServers.size >= MAX_CACHED_PROJECT_SERVERS) {
            evictLeastRecentlyUsed()
        }

        val newServer = AtlasMcpServer(
            workspaceRoot = canonCandidate,
            customDbPath = null,
            cacheProfile = cacheProfile,
            enableCodegraphTools = enableCodegraphTools
        )
        projectServers[canonCandidate] = newServer
        recordAccess(canonCandidate)
        return newServer
    }

    fun close() {
        projectServers.values.forEach { it.close() }
        projectServers.clear()
        projectAccessOrder.clear()
        store.close()
    }

    fun callTool(name: String, arguments: JsonObject): JsonObject {
        val explicitPath = arguments["projectPath"]?.jsonPrimitive?.contentOrNull
            ?: arguments["project-path"]?.jsonPrimitive?.contentOrNull
        val explicitProject = arguments["project"]?.jsonPrimitive?.contentOrNull
        val project = when {
            !explicitProject.isNullOrBlank() -> explicitProject
            !explicitPath.isNullOrBlank() -> {
                val clean = explicitPath.trimEnd('/', '\\')
                if (clean.contains('/')) clean.substringAfterLast('/') else clean
            }
            else -> "organisation"
        }
        val activeServer = resolveServerForProject(project, explicitPath)
        if (activeServer !== this) {
            return activeServer.callToolDirect(name, arguments)
        }
        return callToolDirect(name, arguments)
    }

    private fun callToolDirect(name: String, arguments: JsonObject): JsonObject {
        val project = resolveProjectFromArgs(arguments)
        val rawPayload = when (name) {
            "atlas_query" -> handleQuery(arguments)
            "atlas_audit" -> handleAudit(arguments)
            "figma_audit" -> handleFigmaAudit(arguments)
            "figma_scope" -> handleFigmaScope(arguments)
            "codegraph_explore" -> handleCodegraphExplore(arguments)
            "codegraph_node" -> handleCodegraphNode(arguments)
            "codegraph_search" -> handleCodegraphSearch(arguments)
            "codegraph_callers" -> handleCodegraphCallers(arguments)
            "codegraph_callees" -> handleCodegraphCallees(arguments)
            "codegraph_impact" -> handleCodegraphImpact(arguments)
            "codegraph_status" -> handleCodegraphStatus(arguments)
            "codegraph_files" -> handleCodegraphFiles(arguments)
            else -> throw IllegalArgumentException("Unknown MCP tool: $name")
        }
        val payload = if (name.startsWith("codegraph_") && rawPayload.containsKey("text")) {
            buildJsonObject {
                rawPayload.forEach { (k, v) -> put(k, v) }
                put("_mcp_raw_text", true)
            }
        } else rawPayload
        return enforceSpillover(project, payload)
    }

    private fun extractQueryTarget(args: JsonObject): String {
        val t = args["target"]?.jsonPrimitive?.content
        if (!t.isNullOrEmpty()) return t
        return args["query"]?.jsonPrimitive?.content.orEmpty()
    }

    private fun handleQuery(args: JsonObject): JsonObject {
        val action = args["action"]?.jsonPrimitive?.content ?: "status"
        val project = args["project"]?.jsonPrimitive?.content ?: "organisation"
        val target = extractQueryTarget(args)
        val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 2
        val includeSource = args["include_source"]?.jsonPrimitive?.booleanOrNull ?: false
        val strongSkipping = args["strong_skipping"]?.jsonPrimitive?.booleanOrNull ?: true

        if (action in COMPOSE_QUERY_ACTIONS) {
            return handleQueryCompose(action, project, target, args, depth, strongSkipping)
        }
        return when (action) {
            "status" -> json.encodeToJsonElement(store.getStats(project)).jsonObject
            "god_nodes" -> {
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 10
                buildJsonObject {
                    put("project", project)
                    put("god_nodes", json.encodeToJsonElement(store.getGodNodes(project, topN = limit)))
                }
            }
            "search" -> {
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 15
                buildJsonObject {
                    put("project", project)
                    put("query", target)
                    put("results", json.encodeToJsonElement(store.searchNodes(project, target, limit = limit)))
                }
            }
            "url" -> store.searchByUrl(project, target)
            "complexity" -> handleQueryComplexity(project, target, args)
            "explore" -> {
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 12
                val res = codegraphEngine.explore(project, target, maxFiles = limit)
                buildJsonObject {
                    res.forEach { (k, v) -> put(k, v) }
                    put("_mcp_raw_text", true)
                }
            }
            else -> handleQueryNodeAction(action, project, target, args, depth, includeSource)
        }
    }

    private fun handleQueryCompose(
        action: String,
        project: String,
        target: String,
        args: JsonObject,
        depth: Int,
        strongSkipping: Boolean
    ): JsonObject {
        val mappedAction = action.removePrefix("compose_")
        val maxDepth = if (args.containsKey("depth")) depth else 5
        val res = composeEngine.queryCompose(
            projectSlug = project,
            action = mappedAction,
            target = target.ifEmpty { null },
            maxDepth = maxDepth,
            strongSkipping = strongSkipping
        )
        return json.encodeToJsonElement(res).jsonObject
    }

    private fun handleQueryComplexity(project: String, target: String, args: JsonObject): JsonObject {
        val minCognitive = args["threshold"]?.jsonPrimitive?.intOrNull ?: 10
        val queryLimit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
        val results = if (target.isNotEmpty()) {
            resolveTargetComplexityRecords(project, target, queryLimit)
        } else {
            store.queryComplexNodes(project, minCognitive = minCognitive, limit = queryLimit)
        }
        return buildJsonObject {
            put("project", project)
            put("total_results", results.size)
            put("threshold", minCognitive)
            put("results", json.encodeToJsonElement(results))
        }
    }

    private fun resolveTargetComplexityRecords(project: String, target: String, queryLimit: Int): List<ComplexityScoreRecord> {
        val matching = store.queryComplexNodes(project, minCognitive = 0, limit = queryLimit * 4)
            .filter { isComplexityTargetMatch(it, target) }
        if (matching.isNotEmpty()) {
            return matching.take(queryLimit)
        }
        return createDirectNodeComplexityRecord(project, target)
    }

    private fun isComplexityTargetMatch(node: ComplexityScoreRecord, target: String): Boolean {
        return node.nodeId == target ||
            node.name == target ||
            node.qualifiedName == target ||
            node.qualifiedName.endsWith(".$target") ||
            node.nodeId.contains("#$target.") ||
            node.nodeId.endsWith("#$target") ||
            node.filePath.contains(target)
    }

    private fun createDirectNodeComplexityRecord(project: String, target: String): List<ComplexityScoreRecord> {
        val directNode = store.getNode(project, target) ?: return emptyList()
        if (directNode.kind !in setOf("function", "method", "composable")) {
            return emptyList()
        }
        val score = directNode.complexityScore
        val grade = ComplexityExtractor.computeGrade(score)
        val risk = ComplexityExtractor.computeRiskIndex(directNode.cognitiveComplexity, directNode.inDegree)
        val roundedRisk = (risk * 10.0).roundToInt() / 10.0
        val filePath = if (directNode.fileId.contains(':')) directNode.fileId.substringAfter(':') else directNode.fileId
        return listOf(
            ComplexityScoreRecord(
                nodeId = directNode.nodeId,
                projectId = project,
                name = directNode.name,
                qualifiedName = directNode.qualifiedName,
                filePath = filePath,
                line = directNode.startLine,
                cyclomatic = directNode.cyclomaticComplexity,
                cognitive = directNode.cognitiveComplexity,
                maxNesting = directNode.maxNestingDepth,
                inDegree = directNode.inDegree,
                overallScore = score,
                grade = grade,
                riskIndex = roundedRisk
            )
        )
    }

    private fun handleQueryNodeAction(
        action: String,
        project: String,
        target: String,
        args: JsonObject,
        depth: Int,
        includeSource: Boolean
    ): JsonObject {
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
            "symbol" -> handleQuerySymbol(project, node, includeSource)
            "callers" -> handleQueryCallers(project, node, depth)
            "callees" -> handleQueryCallees(project, node, depth)
            "impact" -> handleQueryImpact(project, node, depth)
            "shortest_path" -> handleQueryShortestPath(project, node, args)
            else -> throw IllegalArgumentException("Unsupported atlas_query action: $action")
        }
    }

    private fun handleQuerySymbol(project: String, node: GraphNode, includeSource: Boolean): JsonObject {
        val annotations = memory.getActiveAnnotations(project, node.nodeId)
        return buildJsonObject {
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

    private fun handleQueryCallers(project: String, node: GraphNode, depth: Int): JsonObject {
        val callers = store.getCallers(project, node.nodeId, depth = depth)
        return buildJsonObject {
            put("node_id", node.nodeId)
            put("callers", json.encodeToJsonElement(callers))
        }
    }

    private fun handleQueryCallees(project: String, node: GraphNode, depth: Int): JsonObject {
        val callees = store.getCallees(project, node.nodeId, depth = depth)
        return buildJsonObject {
            put("node_id", node.nodeId)
            put("callees", json.encodeToJsonElement(callees))
        }
    }

    private fun handleQueryImpact(project: String, node: GraphNode, depth: Int): JsonObject {
        val impact = store.getImpactRadius(project, node.nodeId, depth = depth)
        val baseObj = json.encodeToJsonElement(impact).jsonObject
        val annotations = memory.getActiveAnnotations(project, node.nodeId)
        return buildJsonObject {
            baseObj.forEach { (k, v) -> put(k, v) }
            put("annotations", json.encodeToJsonElement(annotations))
        }
    }

    private fun handleQueryShortestPath(project: String, node: GraphNode, args: JsonObject): JsonObject {
        val sec = args["secondary_target"]?.jsonPrimitive?.content ?: ""
        val tgtNode = store.getNode(project, sec)
            ?: return buildJsonObject { put("error", "Destination '$sec' not found.") }
        val path = store.getShortestPath(project, node.nodeId, tgtNode.nodeId)
        return buildJsonObject {
            put("source", node.nodeId)
            put("target", tgtNode.nodeId)
            put("path", json.encodeToJsonElement(path))
        }
    }

    private fun extractAuditTargets(args: JsonObject): List<String> {
        val fromArray = args["targets"]?.jsonArray ?: args["seeds"]?.jsonArray
        if (fromArray != null) {
            return fromArray.map { it.jsonPrimitive.content }.filter { it.isNotBlank() }
        }
        val single = args["target"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        return if (single != null) listOf(single) else emptyList()
    }

    private fun handleAudit(args: JsonObject): JsonObject {
        val action = args["action"]?.jsonPrimitive?.content ?: "verify_architecture"
        val project = args["project"]?.jsonPrimitive?.content ?: "organisation"
        val targets = extractAuditTargets(args)

        return when {
            action.startsWith("figma_") -> handleAuditFigmaAction(action, args)
            action.startsWith("compose_") || action == "audit_compose" -> handleAuditComposeAction(action, args, project, targets)
            action.startsWith("complexity_") || action == "audit_complexity" -> handleAuditComplexityAction(action, args, project, targets)
            else -> handleAuditCoreAction(action, args, project, targets)
        }
    }

    private fun handleAuditFigmaAction(action: String, args: JsonObject): JsonObject {
        return when (action) {
            "figma_audit" -> handleFigmaAudit(args)
            "figma_scope" -> handleFigmaScope(args)
            else -> throw IllegalArgumentException("Unsupported figma action: $action")
        }
    }

    private fun handleAuditComposeAction(
        action: String,
        args: JsonObject,
        project: String,
        targets: List<String>
    ): JsonObject {
        return when (action) {
            "audit_compose" -> {
                val stabilityConfigPath = args["stability_config_path"]?.jsonPrimitive?.contentOrNull
                val compilerReportsDir = args["compiler_reports_dir"]?.jsonPrimitive?.contentOrNull
                val traceLogPath = args["trace_log_path"]?.jsonPrimitive?.contentOrNull
                val strongSkipping = args["strong_skipping"]?.jsonPrimitive?.booleanOrNull ?: true
                handleAuditCompose(project, targets, stabilityConfigPath, compilerReportsDir, traceLogPath, strongSkipping)
            }
            "compose_doctor" -> {
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 10
                handleAuditComposeDoctor(args, project, targets, limit)
            }
            "compose_scope" -> handleAuditSynthesizeComposeScope(project, targets)
            else -> throw IllegalArgumentException("Unsupported compose action: $action")
        }
    }

    private fun handleAuditComplexityAction(
        action: String,
        args: JsonObject,
        project: String,
        targets: List<String>
    ): JsonObject {
        return when (action) {
            "audit_complexity" -> handleAuditComplexity(args, project, targets)
            "complexity_doctor" -> {
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 10
                handleAuditComplexityDoctor(args, project, targets, limit)
            }
            else -> throw IllegalArgumentException("Unsupported complexity action: $action")
        }
    }

    private fun handleAuditCoreAction(
        action: String,
        args: JsonObject,
        project: String,
        targets: List<String>
    ): JsonObject {
        return when (action) {
            "sync_index" -> {
                val stabilityConfigPath = args["stability_config_path"]?.jsonPrimitive?.contentOrNull
                val compilerReportsDir = args["compiler_reports_dir"]?.jsonPrimitive?.contentOrNull
                val traceLogPath = args["trace_log_path"]?.jsonPrimitive?.contentOrNull
                handleAuditSyncIndex(project, targets, stabilityConfigPath, compilerReportsDir, traceLogPath)
            }
            "synthesize_3d_scope" -> handleAuditSynthesize3dScope(project, targets)
            "synthesize_compose_scope", "compose_scope" -> handleAuditSynthesizeComposeScope(project, targets)
            "architect_context" -> handleAuditArchitectContext(args, project, targets)
            "verify_architecture" -> handleAuditVerifyArchitecture(args, project, targets)
            "check_descriptions" -> handleAuditCheckDescriptions(args, project, targets)
            "verify_remediation" -> handleAuditVerifyRemediation(args, project, targets)
            "dead_code" -> handleAuditDeadCode(project, targets)
            "record_memory" -> handleAuditRecordMemory(args, project, targets)
            "remedy_architecture", "fix_architecture", "fix", "remedy" -> handleAuditRemedyArchitecture(args, project, targets)
            "migrate_tools", "check_legacy_tools" -> handleAuditMigrateTools(args, targets)
            "audit_skills" -> handleAuditSkills(project, targets)
            else -> throw IllegalArgumentException("Unsupported atlas_audit action: $action")
        }
    }

    private fun handleAuditSyncIndex(
        project: String,
        targets: List<String>,
        stabilityConfigPath: String?,
        compilerReportsDir: String?,
        traceLogPath: String?
    ): JsonObject {
        val hasComposeArtifacts = stabilityConfigPath != null || compilerReportsDir != null || traceLogPath != null
        val subpaths = if (targets.isNotEmpty()) targets else null
        return if (hasComposeArtifacts) {
            indexComposeArtifacts(
                projectId = project,
                subpaths = subpaths,
                stabilityConfigPath = stabilityConfigPath,
                compilerReportsDir = compilerReportsDir,
                traceLogPath = traceLogPath
            )
        } else {
            indexWorkspace(project, subpaths = subpaths)
        }
    }

    private fun handleAuditSynthesize3dScope(project: String, targets: List<String>): JsonObject {
        val rec = synthesizer.synthesizeScope(project, targets)
        val baseObj = json.encodeToJsonElement(rec).jsonObject
        val scopeAnnotations = rec.seedTargets.flatMap { target ->
            val node = store.getNode(project, target)
            if (node != null) memory.getActiveAnnotations(project, node.nodeId)
            else emptyList()
        }
        return buildJsonObject {
            baseObj.forEach { (k, v) -> put(k, v) }
            put("yaml_block", rec.toYamlBlock())
            put("annotations", json.encodeToJsonElement(scopeAnnotations))
        }
    }

    private fun handleAuditSynthesizeComposeScope(project: String, targets: List<String>): JsonObject {
        val scope = synthesizer.synthesizeComposeScope(project, targets)
        val baseObj = json.encodeToJsonElement(scope).jsonObject
        val scopeAnnotations = scope.sharedStableModels.flatMap { modelId ->
            memory.getActiveAnnotations(project, modelId)
        }
        return buildJsonObject {
            baseObj.forEach { (k, v) -> put(k, v) }
            put("annotations", json.encodeToJsonElement(scopeAnnotations))
        }
    }

    private fun handleAuditArchitectContext(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 2
        val result = architectContextEngine.synthesizeContext(
            projectId = project,
            seedTargets = targets,
            depth = depth
        )
        return json.encodeToJsonElement(result).jsonObject
    }

    private fun handleAuditVerifyArchitecture(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val descMode = args["description_mode"]?.jsonPrimitive?.contentOrNull ?: "domain-only"
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
        val report = auditor.runFullAudit(
            projectId = project,
            pathPrefixes = if (targets.isNotEmpty()) targets else null,
            descriptionMode = descMode
        )
        val totalComp = report.complexityViolations.size
        val totalAdvisories = report.parameterAdvisories.size
        val cappedReport = if (report.complexityViolations.size > limit || report.parameterAdvisories.size > limit) {
            report.copy(
                complexityViolations = if (report.complexityViolations.size > limit) prioritizeAndCapViolations(report.complexityViolations, limit) else report.complexityViolations,
                parameterAdvisories = if (report.parameterAdvisories.size > limit) prioritizeAndCapViolations(report.parameterAdvisories, limit) else report.parameterAdvisories
            )
        } else {
            report
        }
        val baseObj = json.encodeToJsonElement(cappedReport).jsonObject
        return buildJsonObject {
            baseObj.forEach { (k, v) -> put(k, v) }
            put("total_complexity_violations", totalComp)
            put("total_parameter_advisories", totalAdvisories)
        }
    }

    private fun prioritizeAndCapViolations(violations: List<ComplexityViolation>, limit: Int): List<ComplexityViolation> {
        val sorted = violations.sortedWith(
            compareBy<ComplexityViolation> { if (it.ruleId == "COMPLEXITY-001") 0 else 1 }
                .thenBy { if (it.severity == "HIGH") 0 else 1 }
                .thenByDescending { it.riskIndex }
        )
        return sorted.take(limit.coerceAtLeast(1))
    }

    private fun handleAuditCheckDescriptions(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val descMode = args["description_mode"]?.jsonPrimitive?.contentOrNull ?: "domain-only"
        val suggestions = auditor.checkTopLevelDescriptions(
            projectId = project,
            pathPrefixes = targets,
            mode = descMode
        )
        return buildJsonObject {
            put("project", project)
            put("mode", descMode)
            put("total_suggestions", suggestions.size)
            put("suggestions", json.encodeToJsonElement(suggestions))
        }
    }

    private fun handleAuditSkills(project: String, targets: List<String>): JsonObject {
        val violations = auditor.checkSkillViolations(project, if (targets.isNotEmpty()) targets else null)
        val passed = violations.isEmpty()
        return buildJsonObject {
            put("project", project)
            put("passed", passed)
            put("violations_count", violations.size)
            put("violations", json.encodeToJsonElement(violations))
        }
    }

    private fun handleAuditCompose(
        project: String,
        targets: List<String>,
        stabilityConfigPath: String?,
        compilerReportsDir: String?,
        traceLogPath: String?,
        strongSkipping: Boolean
    ): JsonObject {
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
        return json.encodeToJsonElement(report).jsonObject
    }

    private fun handleAuditComposeDoctor(args: JsonObject, project: String, targets: List<String>, limit: Int): JsonObject {
        val targetSym = targets.firstOrNull() ?: args["target"]?.jsonPrimitive?.contentOrNull
        val report = composeEngine.runDoctor(project, target = targetSym, limit = limit, workspaceRoot = workspaceRoot)
        return json.encodeToJsonElement(report).jsonObject
    }

    private fun handleAuditVerifyRemediation(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val filePath = args["file_path"]?.jsonPrimitive?.contentOrNull
            ?: targets.firstOrNull()
            ?: args["target"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val beforeSnippet = args["before_snippet"]?.jsonPrimitive?.content ?: ""
        val afterSnippet = args["after_snippet"]?.jsonPrimitive?.content ?: ""
        val ruleId = args["rule_id"]?.jsonPrimitive?.content ?: "GENERIC"
        val targetNodeId = args["target_node_id"]?.jsonPrimitive?.contentOrNull
        val writeSiteSafe = args["write_site_safe"]?.jsonPrimitive?.booleanOrNull ?: true
        val dryRun = args["dry_run"]?.jsonPrimitive?.booleanOrNull ?: true
        val testCmd = args["test_command"]?.jsonPrimitive?.contentOrNull
        val res = resolveServerForProject(project).remediationVerifier.verifyAndApply(
            AutonomousRemediationVerifier.VerificationRequest(
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
        )
        return json.encodeToJsonElement(res).jsonObject
    }

    private fun handleAuditDeadCode(project: String, targets: List<String>): JsonObject {
        val deadNodes = auditor.detectDeadCode(project, pathPrefixes = if (targets.isNotEmpty()) targets else null)
        return buildJsonObject {
            put("project", project)
            put("dead_code_count", deadNodes.size)
            put("dead_nodes", json.encodeToJsonElement(deadNodes))
        }
    }

    private fun handleAuditRecordMemory(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val targetStr = if (targets.isNotEmpty()) targets[0] else ""
        val node = store.getNode(project, targetStr)
        val nodeId = node?.nodeId ?: targetStr
        val outcome = args["outcome"]?.jsonPrimitive?.content ?: "useful"
        val note = args["note"]?.jsonPrimitive?.content ?: ""
        val annId = memory.recordOutcome(project, nodeId, outcome, note)
        return buildJsonObject {
            put("annotation_id", annId)
            put("node_id", nodeId)
            put("outcome", outcome)
        }
    }

    private fun handleAuditRemedyArchitecture(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val targetList = if (targets.isNotEmpty()) {
            targets
        } else {
            args["path"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) } ?: emptyList()
        }
        val ruleFilter = args["rule"]?.jsonPrimitive?.contentOrNull ?: args["rule_id"]?.jsonPrimitive?.contentOrNull
        val dryRun = args["dry_run"]?.jsonPrimitive?.booleanOrNull ?: (!args["apply"]?.jsonPrimitive?.booleanOrNull.let { it == true })
        val split = args["split"]?.jsonPrimitive?.booleanOrNull ?: true
        val makePrivate = args["make_private"]?.jsonPrimitive?.booleanOrNull ?: false
        val testCmd = args["test_command"]?.jsonPrimitive?.contentOrNull
        val limitArg = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
        val active = resolveServerForProject(project)
        val report = active.remediationEngine.planAndRemediate(
            ArchitectureRemediationEngine.RemediationPlanRequest(
                projectId = project,
                targetPaths = targetList,
                ruleFilter = ruleFilter,
                dryRun = dryRun,
                splitBoost003 = split,
                makePrivateBoost003 = makePrivate,
                testCommand = testCmd,
                limit = limitArg
            )
        )
        if (!dryRun) {
            active.indexer.indexWorkspace(project, force = true)
        }
        return json.encodeToJsonElement(report).jsonObject
    }

    private fun handleAuditMigrateTools(args: JsonObject, targets: List<String>): JsonObject {
        val apply = args["apply"]?.jsonPrimitive?.booleanOrNull ?: false
        val scanPaths = if (targets.isNotEmpty()) {
            targets
        } else {
            val p = args["path"]?.jsonPrimitive?.contentOrNull
                ?: args["file_path"]?.jsonPrimitive?.contentOrNull
                ?: workspaceRoot
            listOf(p)
        }
        val report = ToolMigrationEngine.scanAndMigrate(scanPaths, apply = apply)
        return json.encodeToJsonElement(report).jsonObject
    }

    private fun handleAuditComplexity(
        args: JsonObject,
        project: String,
        targets: List<String>
    ): JsonObject {
        val threshold = args["threshold"]?.jsonPrimitive?.intOrNull ?: 10
        val maxCyclomatic = args["max_cyclomatic"]?.jsonPrimitive?.intOrNull ?: 15
        val warnParams = extractIntOrParsed(args, "warn_params", "max_parameters") ?: 5
        val maxParams = extractIntOrParsed(args, "max_params") ?: 7
        val composeWarnParams = extractIntOrParsed(args, "compose_warn_params") ?: 10
        val composeMaxParams = extractIntOrParsed(args, "compose_max_params") ?: 14
        val checkParams = args["check_params"]?.jsonPrimitive?.booleanOrNull ?: true
        val thresholds = ArchitectureAuditor.ComplexityThresholds(
            maxCognitive = threshold,
            maxCyclomatic = maxCyclomatic,
            warnParams = warnParams,
            maxParams = maxParams,
            composeWarnParams = composeWarnParams,
            composeMaxParams = composeMaxParams,
            checkParams = checkParams
        )
        val violations = auditor.checkComplexityViolations(
            projectId = project,
            pathPrefixes = if (targets.isNotEmpty()) targets else null,
            thresholds = thresholds
        )
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
        val ratchets = resolveComplexityRatchets(args, project)
        return buildComplexityAuditResponse(project, threshold, maxCyclomatic, violations, ratchets, limit)
    }

    private data class ComplexityRatchets(
        val ratchet: Int?,
        val highRatchet: Int?,
        val paramHighRatchet: Int? = null
    )

    private fun extractIntOrParsed(args: JsonObject, vararg keys: String): Int? {
        for (key in keys) {
            val prim = args[key]?.jsonPrimitive ?: continue
            val direct = prim.intOrNull
            if (direct != null) return direct
            val parsed = prim.contentOrNull?.toIntOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun resolveExplicitBaselineFile(args: JsonObject): String? {
        val prim = args["baseline"]?.jsonPrimitive ?: return null
        if (prim.intOrNull != null) return null
        if (prim.contentOrNull?.toIntOrNull() != null) return null
        return prim.contentOrNull
    }

    private fun resolveComplexityRatchets(args: JsonObject, project: String): ComplexityRatchets {
        val rRatchet = extractIntOrParsed(args, "ratchet", "max_violations", "baseline")
        val rHigh = extractIntOrParsed(args, "high_ratchet", "max_high_violations")
        val rParamHigh = extractIntOrParsed(args, "param_high_ratchet")
        if (rRatchet != null && rHigh != null && rParamHigh != null) {
            return ComplexityRatchets(rRatchet, rHigh, rParamHigh)
        }

        val explicitFile = resolveExplicitBaselineFile(args)
        return findBaselineInPaths(project, explicitFile, ComplexityRatchets(rRatchet, rHigh, rParamHigh))
    }

    private fun candidateBaselinePaths(explicitFile: String?): List<String> {
        return listOfNotNull(
            explicitFile,
            "$workspaceRoot/.atlas/complexity-baseline.json",
            "$workspaceRoot/complexity-baseline.json",
            ".atlas/complexity-baseline.json",
            "complexity-baseline.json"
        ).distinct()
    }

    private fun isAllResolved(ratchets: ComplexityRatchets): Boolean {
        return ratchets.ratchet != null && ratchets.highRatchet != null && ratchets.paramHighRatchet != null
    }

    private fun tryReadBaseline(
        cPath: String,
        project: String,
        current: ComplexityRatchets
    ): ComplexityRatchets {
        val resolvedPath = resolveExistingPath(cPath) ?: return current
        val parsed = parseBaselineJson(resolvedPath, project) ?: return current
        return ComplexityRatchets(
            ratchet = current.ratchet ?: parsed.ratchet,
            highRatchet = current.highRatchet ?: parsed.highRatchet,
            paramHighRatchet = current.paramHighRatchet ?: parsed.paramHighRatchet
        )
    }

    private fun findBaselineInPaths(
        project: String,
        explicitFile: String?,
        initial: ComplexityRatchets
    ): ComplexityRatchets {
        var current = initial
        val candidatePaths = candidateBaselinePaths(explicitFile)

        for (cPath in candidatePaths) {
            current = tryReadBaseline(cPath, project, current)
            if (isAllResolved(current)) break
        }
        return current
    }

    private fun resolveExistingPath(cPath: String): String? {
        if (PlatformFiles.exists(cPath)) return cPath
        if (!cPath.startsWith("/") && PlatformFiles.exists("$workspaceRoot/$cPath")) {
            return "$workspaceRoot/$cPath"
        }
        return null
    }

    private fun extractIntFromObject(obj: JsonObject, vararg keys: String): Int? {
        for (key in keys) {
            val v = obj[key]?.jsonPrimitive?.intOrNull
            if (v != null) return v
        }
        return null
    }

    private fun parseBaselineJson(resolvedPath: String, project: String): ComplexityRatchets? {
        return try {
            val text = PlatformFiles.readText(resolvedPath)
            val parsed = json.parseToJsonElement(text).jsonObject
            val fileProject = parsed["project"]?.jsonPrimitive?.contentOrNull
            if (fileProject != null && fileProject != project) return null
            val r = extractIntFromObject(parsed, "ratchet", "total_violations", "max_violations")
            val h = extractIntFromObject(parsed, "high_ratchet", "high_severity_count", "max_high_violations")
            val ph = extractIntFromObject(parsed, "param_high_ratchet")
            ComplexityRatchets(r, h, ph)
        } catch (e: Exception) {
            PlatformFiles.logWarning("AtlasMcpServer", "Failed to parse complexity baseline from $resolvedPath: ${e.message}")
            null
        }
    }

    private fun buildComplexityAuditResponse(
        project: String,
        threshold: Int,
        maxCyclomatic: Int,
        violations: List<ComplexityViolation>,
        ratchets: ComplexityRatchets,
        limit: Int = 50
    ): JsonObject {
        val blockingViolations = violations.filter { it.ruleId == "COMPLEXITY-001" }
        val advisoryViolations = violations.filter { it.ruleId != "COMPLEXITY-001" }
        val highCount = blockingViolations.count { it.severity == "HIGH" }
        val warningCount = blockingViolations.count { it.severity == "WARNING" }
        val paramHighCount = advisoryViolations.count { it.severity == "HIGH" }
        val resolvedRatchet = ratchets.ratchet
        val resolvedHighRatchet = ratchets.highRatchet
        val resolvedParamHighRatchet = ratchets.paramHighRatchet
        val totalPassed = if (resolvedRatchet != null) blockingViolations.size <= resolvedRatchet else blockingViolations.isEmpty()
        val highPassed = if (resolvedHighRatchet != null) highCount <= resolvedHighRatchet else true
        val paramHighPassed = if (resolvedParamHighRatchet != null) paramHighCount <= resolvedParamHighRatchet else true
        val passed = totalPassed && highPassed && paramHighPassed
        val cappedViolations = prioritizeAndCapViolations(violations, limit)

        return buildJsonObject {
            put("project", project)
            put("passed", passed)
            put("threshold", threshold)
            put("max_cyclomatic", maxCyclomatic)
            put("total_violations", blockingViolations.size)
            put("high_severity_count", highCount)
            put("warning_count", warningCount)
            put("advisory_count", advisoryViolations.size)
            put("param_high_count", paramHighCount)
            put("returned_violations", cappedViolations.size)
            if (resolvedRatchet != null) {
                put("ratchet", resolvedRatchet)
                put("baseline_delta", blockingViolations.size - resolvedRatchet)
            }
            if (resolvedHighRatchet != null) {
                put("high_ratchet", resolvedHighRatchet)
                put("high_delta", highCount - resolvedHighRatchet)
            }
            if (resolvedParamHighRatchet != null) {
                put("param_high_ratchet", resolvedParamHighRatchet)
                put("param_high_delta", paramHighCount - resolvedParamHighRatchet)
            }
            put("violations", json.encodeToJsonElement(cappedViolations))
        }
    }

    private fun handleAuditComplexityDoctor(
        args: JsonObject,
        project: String,
        targets: List<String>,
        limit: Int
    ): JsonObject {
        val targetSym = targets.firstOrNull() ?: args["target"]?.jsonPrimitive?.contentOrNull
        val apply = args["apply"]?.jsonPrimitive?.booleanOrNull ?: false
        val testCmd = args["test_command"]?.jsonPrimitive?.contentOrNull
        val minCognitive = args["threshold"]?.jsonPrimitive?.intOrNull ?: 10
        val report = complexityDoctor.runDoctor(
            projectId = project,
            target = targetSym,
            limit = limit,
            minCognitive = minCognitive
        )
        val finalReport = if (apply) {
            complexityDoctor.applyRemediations(report, dryRun = false, testCommand = testCmd)
        } else {
            report
        }
        return json.encodeToJsonElement(finalReport).jsonObject
    }

    private fun handleFigmaAudit(args: JsonObject): JsonObject {
        val rawFileKey = args["file_key"]?.jsonPrimitive?.content
            ?: args["fileKey"]?.jsonPrimitive?.content
            ?: args["target"]?.jsonPrimitive?.content
            ?: (args["targets"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content)
            ?: ""
        if (rawFileKey.isEmpty()) {
            return buildJsonObject {
                put("error", "Missing required 'file_key' parameter for figma_audit")
            }
        }
        val fileKey = UrlTargetResolver.parseFigmaUrl(rawFileKey)?.fileKey ?: rawFileKey
        val result = figmaAuditor.audit(fileKey)
        return json.encodeToJsonElement(result).jsonObject
    }

    private fun extractRawFileKey(args: JsonObject): String {
        val direct = args["file_key"]?.jsonPrimitive?.content
        if (direct != null) return direct
        val camel = args["fileKey"]?.jsonPrimitive?.content
        if (camel != null) return camel
        val target = args["target"]?.jsonPrimitive?.content
        if (target != null) return target
        val firstTarget = args["targets"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
        if (firstTarget != null) return firstTarget
        return ""
    }

    private fun extractRawSeeds(args: JsonObject): List<String> {
        val seedsArray = args["seeds"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (seedsArray != null) return seedsArray
        val targetsArray = args["targets"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (targetsArray != null) return targetsArray
        val singleSeed = args["seed"]?.jsonPrimitive?.content
        if (singleSeed != null) return listOf(singleSeed)
        val hasFileKey = args.containsKey("file_key") || args.containsKey("fileKey")
        if (hasFileKey) {
            val target = args["target"]?.jsonPrimitive?.content
            if (target != null) return listOf(target)
        }
        return emptyList()
    }

    private fun normalizeFigmaSeeds(rawSeeds: List<String>, fallbackNodeId: String?): List<String> {
        if (rawSeeds.isEmpty() && fallbackNodeId != null) {
            return listOf(fallbackNodeId)
        }
        return rawSeeds.map { seed ->
            val parsedUrl = UrlTargetResolver.parseFigmaUrl(seed)
            if (parsedUrl?.nodeId != null) parsedUrl.nodeId else UrlTargetResolver.normalizeFigmaNodeId(seed)
        }
    }

    private fun handleFigmaScope(args: JsonObject): JsonObject {
        val project = args["project"]?.jsonPrimitive?.content ?: "organisation"
        val rawFileKey = extractRawFileKey(args)
        if (rawFileKey.isEmpty()) {
            return buildJsonObject {
                put("error", "Missing required 'file_key' parameter for figma_scope")
            }
        }
        val parsedFileUrl = UrlTargetResolver.parseFigmaUrl(rawFileKey)
        val fileKey = if (parsedFileUrl?.fileKey != null) parsedFileUrl.fileKey else rawFileKey
        val rawSeeds = extractRawSeeds(args)
        val seeds = normalizeFigmaSeeds(rawSeeds, parsedFileUrl?.nodeId)
        val scope = figmaAuditor.synthesizeFigmaScope(project, fileKey, seeds)
        return json.encodeToJsonElement(scope).jsonObject
    }

    private fun resolveProjectFromArgs(args: JsonObject): String {
        val explicitProject = args["project"]?.jsonPrimitive?.contentOrNull
        if (!explicitProject.isNullOrBlank()) return explicitProject
        val explicitPath = args["projectPath"]?.jsonPrimitive?.contentOrNull
            ?: args["project-path"]?.jsonPrimitive?.contentOrNull
        if (!explicitPath.isNullOrBlank()) {
            val clean = explicitPath.trimEnd('/', '\\')
            return if (clean.contains('/')) clean.substringAfterLast('/') else clean
        }
        return "organisation"
    }

    private fun handleCodegraphExplore(args: JsonObject): JsonObject {
        val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val maxFiles = args["maxFiles"]?.jsonPrimitive?.intOrNull ?: 12
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.explore(project, query, maxFiles)
    }

    private fun handleCodegraphNode(args: JsonObject): JsonObject {
        val symbol = args["symbol"]?.jsonPrimitive?.contentOrNull
        val file = args["file"]?.jsonPrimitive?.contentOrNull
        val includeCode = args["includeCode"]?.jsonPrimitive?.booleanOrNull ?: false
        val offset = args["offset"]?.jsonPrimitive?.intOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull
        val symbolsOnly = args["symbolsOnly"]?.jsonPrimitive?.booleanOrNull ?: false
        val line = args["line"]?.jsonPrimitive?.intOrNull
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.node(
            CodegraphCompatibilityEngine.NodeQueryRequest(
                projectId = project,
                symbol = symbol,
                file = file,
                includeCode = includeCode,
                offset = offset,
                limit = limit,
                symbolsOnly = symbolsOnly,
                line = line
            )
        )
    }

    private fun handleCodegraphSearch(args: JsonObject): JsonObject {
        val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val kind = args["kind"]?.jsonPrimitive?.contentOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 10
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.search(project, query, kind, limit)
    }

    private fun handleCodegraphCallers(args: JsonObject): JsonObject {
        val symbol = args["symbol"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val file = args["file"]?.jsonPrimitive?.contentOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.callers(project, symbol, file, limit)
    }

    private fun handleCodegraphCallees(args: JsonObject): JsonObject {
        val symbol = args["symbol"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val file = args["file"]?.jsonPrimitive?.contentOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.callees(project, symbol, file, limit)
    }

    private fun handleCodegraphImpact(args: JsonObject): JsonObject {
        val symbol = args["symbol"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val file = args["file"]?.jsonPrimitive?.contentOrNull
        val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 2
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.impact(project, symbol, file, depth)
    }

    private fun handleCodegraphStatus(args: JsonObject): JsonObject {
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.status(project)
    }

    private fun handleCodegraphFiles(args: JsonObject): JsonObject {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
        val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull
        val format = args["format"]?.jsonPrimitive?.contentOrNull ?: "tree"
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.files(project, path, pattern, format)
    }

    private fun enforceSpillover(projectId: String, payload: JsonObject): JsonObject =
        transport.enforceSpillover(projectId, payload)

    fun processJsonRpcMessage(line: String): String? =
        transport.processJsonRpcMessage(line)

    fun serveStdio() =
        transport.serveStdio()
}
