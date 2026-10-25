package atlas.engine

import atlas.bridge.VaultPlanBridge
import atlas.db.SqliteGraphStore
import atlas.extract.ComposeStabilityExtractor
import atlas.extract.ModuleBoundaryDetector
import atlas.extract.PolyglotAstExtractor
import atlas.extract.SemioticLayerClassifier
import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import atlas.util.Sha256
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs

/**
 * Incremental workspace indexer with persisted raw symbol edge re-resolution (`BUG-01`),
 * pre-loaded file record cache (`PERF-03`), no-op short-circuiting (`BUG-12`),
 * stale plan edge cleanup (`BUG-14`), and Dual-Mode Jetpack Compose & KMP stability extraction.
 */
class WorkspaceIndexer(
    private val workspaceRoot: String,
    private val store: SqliteGraphStore
) {
    private val composeExtractor = ComposeStabilityExtractor()

    companion object {
        private val SKIP_DIRS = setOf(
            ".git", ".gradle", ".idea", ".venv", "venv", "node_modules",
            "build", "dist", "__pycache__", ".pytest_cache", ".artifacts",
            ".agents", ".agent", ".atlas"
        )
    }

    private fun normalizeSubpath(raw: String, canonicalRoot: String): String {
        var s = raw.trim().replace('\\', '/')
        if (s.startsWith("$canonicalRoot/")) {
            s = s.removePrefix("$canonicalRoot/")
        } else if (s == canonicalRoot) {
            s = ""
        }
        while (s.startsWith("./")) {
            s = s.removePrefix("./")
        }
        return s.trim('/')
    }

    private fun discoverStabilityPatterns(root: String, explicitConfigPath: String? = null): List<Regex> {
        val patterns = mutableListOf<Regex>()
        if (!explicitConfigPath.isNullOrBlank()) {
            val resolved = if (explicitConfigPath.startsWith("/")) explicitConfigPath else "$root/$explicitConfigPath"
            if (PlatformFiles.exists(resolved)) {
                patterns.addAll(ComposeStabilityExtractor.parseStabilityConfigPatterns(PlatformFiles.readText(resolved)))
            }
        }
        val defaultCandidates = listOf(
            "$root/stability_config.conf",
            "$root/compose_stability.conf",
            "$root/config/stability_config.conf"
        )
        for (cand in defaultCandidates) {
            if (PlatformFiles.exists(cand)) {
                patterns.addAll(ComposeStabilityExtractor.parseStabilityConfigPatterns(PlatformFiles.readText(cand)))
            }
        }
        return patterns
    }

    private class ScanCollector {
        val validDiskFiles = mutableSetOf<String>()
        val changedNodes = mutableListOf<GraphNode>()
        val markdownQueue = mutableListOf<Pair<String, String>>()
        val unchangedMarkdownQueue = mutableListOf<Pair<String, String>>()
        val pendingKotlinComposeFiles = mutableListOf<Triple<String, String, List<GraphEdge>>>()
        val pendingRawEdges = mutableMapOf<String, List<GraphEdge>>()
        var codeFilesIndexed = 0
        var filesIndexed = 0
        var filesSkipped = 0
    }

    private data class WorkspaceScanContext(
        val root: String,
        val projectId: String,
        val normalizedSubpaths: List<String>?,
        val effectiveForce: Boolean,
        val knownModules: List<String>,
        val cachedFiles: Map<String, Pair<String, Double>>,
        val filesWithRawEdges: Set<String>
    )

    private fun isPathSkipped(relStr: String): Boolean {
        val parts = relStr.split('/').filter { it.isNotBlank() }
        return parts.any { it in SKIP_DIRS || it.startsWith(".venv") }
    }

    private fun matchesSubpaths(relStr: String, subpaths: List<String>?): Boolean {
        if (subpaths.isNullOrEmpty()) return true
        return subpaths.any { cleanSp -> relStr == cleanSp || relStr.startsWith("$cleanSp/") }
    }

    private fun processMarkdownFile(
        relStr: String,
        filePath: String,
        isUnchanged: Boolean,
        sha256: String,
        mtime: Double,
        rawBytes: ByteArray,
        projectId: String,
        scan: ScanCollector
    ) {
        if (!isUnchanged) {
            val sourceText = rawBytes.decodeToString()
            scan.markdownQueue.add(Pair(relStr, sourceText))
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
            scan.filesIndexed++
        } else {
            scan.unchangedMarkdownQueue.add(Pair(relStr, filePath))
            scan.filesSkipped++
        }
    }

    private fun processCodeFile(
        relStr: String,
        lang: String,
        isUnchanged: Boolean,
        sha256: String,
        mtime: Double,
        rawBytes: ByteArray,
        ctx: WorkspaceScanContext,
        scan: ScanCollector
    ) {
        val fileId = "${ctx.projectId}:$relStr"
        if (isUnchanged && fileId in ctx.filesWithRawEdges) {
            scan.filesSkipped++
            return
        }

        val sourceText = rawBytes.decodeToString()
        val (fNodes, fEdges, topDeclCount) = PolyglotAstExtractor.extractFile(
            ctx.projectId, relStr, sourceText, ctx.knownModules
        )
        val (semLayer, archLayer, _) = SemioticLayerClassifier.classify(relStr)
        val modId = ModuleBoundaryDetector.resolveModuleId(relStr, ctx.knownModules)
        val srcSet = SemioticLayerClassifier.detectSourceSet(relStr)

        store.upsertFileRecord(
            projectId = ctx.projectId,
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
        if (lang == "kotlin") {
            scan.pendingKotlinComposeFiles.add(Triple(relStr, sourceText, fEdges))
        } else {
            scan.pendingRawEdges[fileId] = fEdges
        }
        scan.changedNodes.addAll(fNodes)
        scan.codeFilesIndexed++
        scan.filesIndexed++
    }

    private fun processDiscoveredFile(
        filePath: String,
        relStr: String,
        ext: String,
        lang: String,
        ctx: WorkspaceScanContext,
        scan: ScanCollector
    ) {
        val mtime = PlatformFiles.mtime(filePath)
        val cached = ctx.cachedFiles[relStr]
        val mtimeUnchanged = cached != null && abs(cached.second - mtime) < 1e-6
        val rawBytes: ByteArray by lazy { PlatformFiles.readBytes(filePath) }
        val sha256 = if (mtimeUnchanged) cached.first else Sha256.digest(rawBytes)
        val isUnchanged = !ctx.effectiveForce && (mtimeUnchanged || (cached != null && cached.first == sha256))

        if (ext == ".md") {
            processMarkdownFile(relStr, filePath, isUnchanged, sha256, mtime, rawBytes, ctx.projectId, scan)
        } else {
            processCodeFile(relStr, lang, isUnchanged, sha256, mtime, rawBytes, ctx, scan)
        }
    }

    private fun resolveFileLangOrNull(relStr: String): Pair<String, String>? {
        val ext = if (relStr.contains('.')) "." + relStr.substringAfterLast('.') else ""
        val lang = PolyglotAstExtractor.supportedLanguage(ext)
        if (lang.isEmpty() && ext != ".md") return null
        return Pair(ext, lang)
    }

    private fun isCandidateFile(relStr: String, subpaths: List<String>?): Boolean {
        return !isPathSkipped(relStr) && matchesSubpaths(relStr, subpaths)
    }

    private fun scanWorkspaceFiles(ctx: WorkspaceScanContext): ScanCollector {
        val scan = ScanCollector()
        val allFiles = PlatformFiles.listFilesRecursively(ctx.root) { dirName ->
            dirName in SKIP_DIRS || dirName.startsWith(".venv")
        }
        for (filePath in allFiles) {
            val relStr = filePath.removePrefix(ctx.root).removePrefix("/").replace('\\', '/')
            if (!isCandidateFile(relStr, ctx.normalizedSubpaths)) continue
            val (ext, lang) = resolveFileLangOrNull(relStr) ?: continue
            scan.validDiskFiles.add(relStr)
            processDiscoveredFile(filePath, relStr, ext, lang, ctx, scan)
        }
        return scan
    }

    private fun purgeDeletedFiles(
        projectId: String,
        root: String,
        cachedFiles: Map<String, Pair<String, Double>>,
        validDiskFiles: Set<String>,
        normalizedSubpaths: List<String>?
    ): Int {
        var filesDeleted = 0
        for (indexedRel in cachedFiles.keys) {
            if (indexedRel == "__endpoints__") continue
            if (!matchesSubpaths(indexedRel, normalizedSubpaths) && PlatformFiles.exists("$root/$indexedRel")) {
                continue
            }
            if (indexedRel !in validDiskFiles) {
                store.deleteFileRecord(projectId, indexedRel, recomputeDegrees = false)
                store.deleteComposeNodesForFile(projectId, indexedRel)
                filesDeleted++
            }
        }
        return filesDeleted
    }

    private fun buildStatsResponse(projectId: String, filesIndexed: Int, filesSkipped: Int): JsonObject {
        val stats = store.getStats(projectId)
        val composeNodes = store.listComposeNodes(projectId)
        return buildJsonObject {
            put("project_id", stats.projectId)
            put("files", stats.files)
            put("nodes", stats.nodes)
            put("edges", stats.edges)
            put("compose_nodes", composeNodes.size)
            put("files_indexed", filesIndexed)
            put("files_skipped", filesSkipped)
            putJsonObject("provenance_breakdown") {
                stats.provenanceBreakdown.forEach { (k, v) -> put(k, v) }
            }
        }
    }

    private fun isComposeSourceFile(
        bundle: ComposeStabilityExtractor.ExtractionBundle,
        sourceText: String
    ): Boolean {
        if (bundle.nodes.isNotEmpty()) return true
        if (bundle.edges.any { it.edgeType in setOf("PROVIDES_COMPOSITION_LOCAL", "CONSUMES_COMPOSITION_LOCAL", "OBSERVES_STATE") }) return true
        return sourceText.lineSequence().any { line ->
            val t = line.trim()
            t.startsWith("import androidx.compose") ||
            t.startsWith("import org.jetbrains.compose") ||
            t.startsWith("@Composable") ||
            t.contains(" @Composable ")
        }
    }

    private fun syncComposeNodeRecords(
        bundleNodes: List<ComposeNodeRecord>,
        projectId: String,
        fileId: String,
        modId: String,
        semLayer: String,
        archLayer: String,
        mergedNodeMap: MutableMap<String, GraphNode>,
        changedNodes: MutableList<GraphNode>
    ) {
        for (cn in bundleNodes) {
            val shortName = cn.fqName.substringAfterLast('.')
            val existing = mergedNodeMap[cn.nodeId]
            val compGraphNode = if (existing != null) {
                existing.copy(kind = "composable", sourceSet = cn.sourceSet)
            } else {
                GraphNode(
                    nodeId = cn.nodeId,
                    projectId = projectId,
                    fileId = fileId,
                    name = shortName,
                    qualifiedName = cn.fqName,
                    kind = "composable",
                    signature = "@Composable fun $shortName",
                    startLine = cn.startLine,
                    endLine = cn.endLine,
                    moduleId = modId,
                    sourceSet = cn.sourceSet,
                    semioticLayer = semLayer,
                    archLayer = archLayer,
                    isTestDouble = false
                )
            }
            mergedNodeMap[cn.nodeId] = compGraphNode
            changedNodes.add(compGraphNode)
        }
    }

    private fun syncComposeEdgeEntities(
        bundleEdges: List<GraphEdge>,
        projectId: String,
        fileId: String,
        relStr: String,
        modId: String,
        semLayer: String,
        archLayer: String,
        mergedNodeMap: MutableMap<String, GraphNode>,
        changedNodes: MutableList<GraphNode>
    ) {
        for (ce in bundleEdges) {
            if (ce.sourceNodeId.endsWith("#file") && ce.sourceNodeId !in mergedNodeMap) {
                val fileNode = GraphNode(
                    nodeId = ce.sourceNodeId,
                    projectId = projectId,
                    fileId = fileId,
                    name = relStr.substringAfterLast('/'),
                    qualifiedName = relStr,
                    kind = "file_scope",
                    signature = relStr,
                    startLine = 1,
                    endLine = 1,
                    moduleId = modId,
                    sourceSet = SemioticLayerClassifier.detectSourceSet(relStr),
                    semioticLayer = semLayer,
                    archLayer = archLayer
                )
                mergedNodeMap[fileNode.nodeId] = fileNode
                changedNodes.add(fileNode)
            }
            if (ce.edgeType in setOf("MUTATES_PROPERTY", "PROVIDES_COMPOSITION_LOCAL", "CONSUMES_COMPOSITION_LOCAL", "OBSERVES_STATE")) {
                val symName = ce.targetNodeId.removePrefix("sym:")
                if (mergedNodeMap.values.none { it.name == symName }) {
                    val syntheticId = "$fileId#$symName"
                    val synNode = GraphNode(
                        nodeId = syntheticId,
                        projectId = projectId,
                        fileId = fileId,
                        name = symName,
                        qualifiedName = symName,
                        kind = "variable",
                        signature = symName,
                        startLine = ce.lineNumber,
                        endLine = ce.lineNumber,
                        moduleId = modId,
                        sourceSet = SemioticLayerClassifier.detectSourceSet(relStr),
                        semioticLayer = semLayer,
                        archLayer = archLayer
                    )
                    mergedNodeMap[syntheticId] = synNode
                    changedNodes.add(synNode)
                }
            }
        }
    }

    private fun processComposeFiles(
        projectId: String,
        stabilityPatterns: List<Regex>,
        pendingKotlinComposeFiles: List<Triple<String, String, List<GraphEdge>>>,
        mergedNodeMap: MutableMap<String, GraphNode>,
        changedNodes: MutableList<GraphNode>,
        knownModules: List<String>,
        pendingRawEdges: MutableMap<String, List<GraphEdge>>
    ): Pair<List<ComposeNodeRecord>, List<ComposeParamRecord>> {
        val workspaceNodesSnapshot = mergedNodeMap.values.toList()
        val precomputedStability = mutableMapOf<String, Pair<String, String>>()
        for ((_, sourceText, _) in pendingKotlinComposeFiles) {
            val masked = PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "kotlin")
            precomputedStability.putAll(composeExtractor.analyzeClassesInSource(sourceText, masked, stabilityPatterns))
        }

        val allNewComposeNodes = mutableListOf<ComposeNodeRecord>()
        val allNewComposeParams = mutableListOf<ComposeParamRecord>()
        for ((relStr, sourceText, baseEdges) in pendingKotlinComposeFiles) {
            val fileId = "$projectId:$relStr"
            store.deleteComposeNodesForFile(projectId, relStr)
            val bundle = composeExtractor.extractKotlinFile(
                projectSlug = projectId,
                relPath = relStr,
                sourceText = sourceText,
                stabilityPatterns = stabilityPatterns,
                workspaceNodes = workspaceNodesSnapshot,
                precomputedClassStability = precomputedStability
            )
            if (isComposeSourceFile(bundle, sourceText)) {
                allNewComposeNodes.addAll(bundle.nodes)
                allNewComposeParams.addAll(bundle.params)

                val (semLayer, archLayer, _) = SemioticLayerClassifier.classify(relStr)
                val modId = ModuleBoundaryDetector.resolveModuleId(relStr, knownModules)

                syncComposeNodeRecords(bundle.nodes, projectId, fileId, modId, semLayer, archLayer, mergedNodeMap, changedNodes)
                syncComposeEdgeEntities(bundle.edges, projectId, fileId, relStr, modId, semLayer, archLayer, mergedNodeMap, changedNodes)

                pendingRawEdges[fileId] = baseEdges + bundle.edges
            } else {
                pendingRawEdges[fileId] = baseEdges
            }
        }
        return Pair(allNewComposeNodes, allNewComposeParams)
    }

    private fun resolveWorkspaceEdges(
        projectId: String,
        combinedNodes: List<GraphNode>,
        pendingRawEdges: Map<String, List<GraphEdge>>,
        allNewComposeNodes: List<ComposeNodeRecord>,
        allNewComposeParams: List<ComposeParamRecord>
    ): Pair<List<GraphEdge>, List<GraphEdge>> {
        if (pendingRawEdges.isNotEmpty()) {
            store.replaceBatchRawSymbolEdges(projectId, pendingRawEdges)
        }
        if (allNewComposeNodes.isNotEmpty() || allNewComposeParams.isNotEmpty()) {
            store.upsertComposeNodesAndParams(allNewComposeNodes, allNewComposeParams)
        }
        store.deleteAllResolvedCodeEdges(projectId)
        val allPersistedRawEdges = store.listRawSymbolEdges(projectId)
        val resolvedEdges = PolyglotAstExtractor.resolveCrossFileEdges(projectId, combinedNodes, allPersistedRawEdges)
        val kmpEdges = ModuleBoundaryDetector.synthesizeKmpExpectActualEdges(projectId, combinedNodes)
        return Pair(resolvedEdges, kmpEdges)
    }

    private fun reevaluateMarkdownFiles(
        projectId: String,
        root: String,
        markdownQueue: MutableList<Pair<String, String>>,
        unchangedMarkdownQueue: List<Pair<String, String>>,
        cachedFiles: Map<String, Pair<String, Double>>,
        normalizedSubpaths: List<String>?,
        reEvaluate: Boolean
    ) {
        if (!reEvaluate) return
        val queuedRels = markdownQueue.mapTo(mutableSetOf()) { it.first }
        for ((relMd, filePath) in unchangedMarkdownQueue) {
            queueMarkdownIfReadable(projectId, relMd, filePath, queuedRels, markdownQueue)
        }
        if (normalizedSubpaths != null && normalizedSubpaths.isNotEmpty()) {
            for (indexedRel in cachedFiles.keys) {
                if (indexedRel.endsWith(".md")) {
                    queueMarkdownIfReadable(projectId, indexedRel, "$root/$indexedRel", queuedRels, markdownQueue)
                }
            }
        }
    }

    private fun collectActiveEndpointTargets(
        endpointPrefix: String,
        projectId: String,
        resolvedEdges: List<GraphEdge>,
        docEdges: List<GraphEdge>
    ): Set<String> {
        val targets = LinkedHashSet<String>()
        val allCandidateEdges = resolvedEdges + docEdges + store.listEdges(projectId, listOf("URL_REFS"))
        for (edge in allCandidateEdges) {
            if (edge.targetNodeId.startsWith(endpointPrefix)) {
                targets.add(edge.targetNodeId)
            }
        }
        return targets
    }

    private fun extractEndpoints(
        projectId: String,
        resolvedEdges: List<GraphEdge>,
        docEdges: List<GraphEdge>
    ): List<GraphNode> {
        val endpointPrefix = "$projectId:endpoint#"
        val activeEndpointTargets = collectActiveEndpointTargets(endpointPrefix, projectId, resolvedEdges, docEdges)
        if (activeEndpointTargets.isEmpty()) return emptyList()

        store.ensureVirtualFileRecord(projectId, "__endpoints__")
        val virtualFileId = "$projectId:__endpoints__"
        val endpointNodes = mutableListOf<GraphNode>()
        for (epNodeId in activeEndpointTargets) {
            val canonicalRoute = epNodeId.removePrefix(endpointPrefix)
            if (canonicalRoute.isEmpty()) continue
            endpointNodes.add(
                GraphNode(
                    nodeId = epNodeId,
                    projectId = projectId,
                    fileId = virtualFileId,
                    name = canonicalRoute,
                    qualifiedName = canonicalRoute,
                    kind = "endpoint",
                    signature = "endpoint $canonicalRoute",
                    startLine = 1,
                    endLine = 1,
                    moduleId = "endpoints",
                    sourceSet = "main",
                    semioticLayer = "syntactic",
                    archLayer = "interface",
                    isTestDouble = false,
                    isTopLevel = false
                )
            )
        }
        return endpointNodes
    }

    fun indexWorkspace(
        projectId: String,
        subpaths: List<String>? = null,
        stabilityConfigPath: String? = null,
        forceComposeReindex: Boolean = false,
        force: Boolean = false
    ): JsonObject {
        val root = PlatformFiles.canonicalPath(workspaceRoot)
        val normalizedSubpaths = subpaths
            ?.map { normalizeSubpath(it, root) }
            ?.filter { it.isNotEmpty() }

        val stabilityPatterns = discoverStabilityPatterns(root, stabilityConfigPath)
        val (modNodes, modEdges, knownModules) = ModuleBoundaryDetector.extractModuleGraph(root, projectId)
        val cachedFiles = store.getFileRecordsMap(projectId)
        val filesWithRawEdges = store.filesWithRawSymbolEdges(projectId)
        val storedVersion = store.getProjectMetadata(projectId, "extractor_version")
        val effectiveForce = force || forceComposeReindex || (storedVersion != PolyglotAstExtractor.EXTRACTOR_VERSION)

        val scanCtx = WorkspaceScanContext(
            root = root,
            projectId = projectId,
            normalizedSubpaths = normalizedSubpaths,
            effectiveForce = effectiveForce,
            knownModules = knownModules,
            cachedFiles = cachedFiles,
            filesWithRawEdges = filesWithRawEdges
        )
        val scan = scanWorkspaceFiles(scanCtx)
        val filesDeleted = purgeDeletedFiles(projectId, root, cachedFiles, scan.validDiskFiles, normalizedSubpaths)

        if (!effectiveForce && scan.filesIndexed == 0 && filesDeleted == 0 && cachedFiles.isNotEmpty()) {
            return buildStatsResponse(projectId, 0, scan.filesSkipped)
        }

        val mergedNodeMap = store.listNodes(projectId).associateBy { it.nodeId }.toMutableMap()
        for (n in modNodes) mergedNodeMap[n.nodeId] = n
        for (n in scan.changedNodes) mergedNodeMap[n.nodeId] = n

        val (allNewComposeNodes, allNewComposeParams) = processComposeFiles(
            projectId = projectId,
            stabilityPatterns = stabilityPatterns,
            pendingKotlinComposeFiles = scan.pendingKotlinComposeFiles,
            mergedNodeMap = mergedNodeMap,
            changedNodes = scan.changedNodes,
            knownModules = knownModules,
            pendingRawEdges = scan.pendingRawEdges
        )

        val combinedNodes = mergedNodeMap.values.toList()
        val (resolvedEdges, kmpEdges) = resolveWorkspaceEdges(
            projectId = projectId,
            combinedNodes = combinedNodes,
            pendingRawEdges = scan.pendingRawEdges,
            allNewComposeNodes = allNewComposeNodes,
            allNewComposeParams = allNewComposeParams
        )

        reevaluateMarkdownFiles(
            projectId = projectId,
            root = root,
            markdownQueue = scan.markdownQueue,
            unchangedMarkdownQueue = scan.unchangedMarkdownQueue,
            cachedFiles = cachedFiles,
            normalizedSubpaths = normalizedSubpaths,
            reEvaluate = scan.codeFilesIndexed > 0 || filesDeleted > 0
        )

        val docNodes = mutableListOf<GraphNode>()
        val docEdges = mutableListOf<GraphEdge>()
        for ((relMd, mdText) in scan.markdownQueue) {
            val (dNodes, dEdges) = VaultPlanBridge.extractMarkdownAndPlans(projectId, relMd, mdText, combinedNodes)
            docNodes.addAll(dNodes)
            docEdges.addAll(dEdges)
        }

        val endpointNodes = extractEndpoints(projectId, resolvedEdges, docEdges)

        store.upsertNodesAndEdges(
            projectId = projectId,
            nodes = modNodes + scan.changedNodes.distinctBy { it.nodeId } + docNodes.distinctBy { it.nodeId } + endpointNodes,
            edges = modEdges + resolvedEdges + kmpEdges + docEdges
        )
        store.setProjectMetadata(projectId, "extractor_version", PolyglotAstExtractor.EXTRACTOR_VERSION)

        return buildStatsResponse(projectId, scan.filesIndexed, scan.filesSkipped)
    }

    /**
     * Runs Dual-Mode Compose indexing: indexes workspace `.kt` files with optional `stability_config.conf`,
     * ingests optional Compose Compiler metrics reports (`*-composables.txt`), and ingests optional
     * `@TraceRecomposition` runtime telemetry (`traceLogPath`).
     */
    private fun resolveCompilerReportFiles(resolvedDir: String): List<String> {
        if (resolvedDir.endsWith(".txt") || resolvedDir.endsWith(".csv")) {
            return listOf(resolvedDir)
        }
        return PlatformFiles.listFilesRecursively(resolvedDir).filter {
            it.endsWith("-composables.txt") || it.endsWith("composables.txt")
        }
    }

    private fun ingestCompilerReports(
        projectId: String,
        root: String,
        compilerReportsDir: String?
    ): Int {
        if (compilerReportsDir.isNullOrBlank()) return 0
        val resolvedDir = if (compilerReportsDir.startsWith("/")) compilerReportsDir else "$root/$compilerReportsDir"
        if (!PlatformFiles.exists(resolvedDir)) return 0

        val reportFiles = resolveCompilerReportFiles(resolvedDir)
        val existing = store.listComposeNodes(projectId)
        var compilerUpgraded = 0
        for (rf in reportFiles) {
            val text = PlatformFiles.readText(rf)
            val (updNodes, updParams) = composeExtractor.parseCompilerComposablesReport(projectId, text, existing)
            if (updNodes.isNotEmpty() || updParams.isNotEmpty()) {
                store.upsertComposeNodesAndParams(updNodes, updParams)
                compilerUpgraded += updNodes.size
            }
        }
        return compilerUpgraded
    }

    private fun matchTraceTargetNodeId(fqNameOrNodeId: String, existingNodes: List<ComposeNodeRecord>): String {
        val matched = existingNodes.firstOrNull {
            it.nodeId == fqNameOrNodeId ||
                it.fqName == fqNameOrNodeId ||
                it.fqName.endsWith(".${fqNameOrNodeId}")
        }
        return matched?.nodeId ?: fqNameOrNodeId
    }

    private fun ingestTraceTelemetry(
        projectId: String,
        root: String,
        traceLogPath: String?
    ): Int {
        if (traceLogPath.isNullOrBlank()) return 0
        val resolvedLog = if (traceLogPath.startsWith("/")) traceLogPath else "$root/$traceLogPath"
        if (!PlatformFiles.exists(resolvedLog)) return 0

        val rawLog = PlatformFiles.readText(resolvedLog)
        val traces = composeExtractor.parseTraceTelemetry(rawLog)
        val existingNodes = store.listComposeNodes(projectId)
        for (tr in traces) {
            val targetNodeId = matchTraceTargetNodeId(tr.fqNameOrNodeId, existingNodes)
            store.upsertComposeTraceMetric(
                nodeId = targetNodeId,
                sessionId = tr.sessionId,
                recompositionCount = tr.recompositionCount,
                skipCount = tr.skipCount,
                dirtyParamsJson = tr.dirtyParamsJson,
                recordedAt = tr.recordedAt
            )
        }
        return traces.size
    }

    fun indexComposeArtifacts(
        projectId: String,
        subpaths: List<String>? = null,
        stabilityConfigPath: String? = null,
        compilerReportsDir: String? = null,
        traceLogPath: String? = null
    ): JsonObject {
        val root = PlatformFiles.canonicalPath(workspaceRoot)
        val baseRes = indexWorkspace(
            projectId = projectId,
            subpaths = subpaths,
            stabilityConfigPath = stabilityConfigPath,
            forceComposeReindex = !stabilityConfigPath.isNullOrBlank()
        )

        val compilerUpgraded = ingestCompilerReports(projectId, root, compilerReportsDir)
        val traceRecordsIngested = ingestTraceTelemetry(projectId, root, traceLogPath)

        val finalComposeNodes = store.listComposeNodes(projectId)
        val skippableCount = finalComposeNodes.count { it.isSkippable }
        return buildJsonObject {
            baseRes.forEach { (k, v) -> put(k, v) }
            put("compose_nodes", finalComposeNodes.size)
            put("skippable_composables", skippableCount)
            put("compiler_upgraded", compilerUpgraded)
            put("trace_records_ingested", traceRecordsIngested)
        }
    }

    private fun queueMarkdownIfReadable(
        projectId: String,
        relMd: String,
        filePath: String,
        queuedRels: MutableSet<String>,
        queue: MutableList<Pair<String, String>>
    ) {
        if (!queuedRels.add(relMd)) return
        val text = try {
            if (PlatformFiles.exists(filePath)) PlatformFiles.readText(filePath) else null
        } catch (_: Exception) {
            null
        } ?: return
        store.deleteOutgoingEdgesForFile(projectId, "$projectId:$relMd")
        queue.add(Pair(relMd, text))
    }
}

