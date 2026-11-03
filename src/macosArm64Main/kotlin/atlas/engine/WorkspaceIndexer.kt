package atlas.engine

import atlas.bridge.VaultPlanBridge
import atlas.db.SqliteGraphStore
import atlas.extract.ComposeStabilityExtractor
import atlas.extract.ComposeTypeStabilityAnalyzer
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

    private data class PendingKotlinComposeFile(
        val relStr: String,
        val sourceText: String,
        val maskedText: String,
        val baseEdges: List<GraphEdge>
    )

    private class ScanCollector {
        val validDiskFiles = mutableSetOf<String>()
        val changedNodes = mutableListOf<GraphNode>()
        val markdownQueue = mutableListOf<Pair<String, String>>()
        val unchangedMarkdownQueue = mutableListOf<Pair<String, String>>()
        val pendingKotlinComposeFiles = mutableListOf<PendingKotlinComposeFile>()
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

    private data class DiscoveredFileMeta(
        val filePath: String,
        val relStr: String,
        val ext: String,
        val lang: String,
        val isUnchanged: Boolean,
        val sha256: String,
        val mtime: Double
    )

    private fun processMarkdownFile(
        meta: DiscoveredFileMeta,
        rawBytes: ByteArray,
        projectId: String,
        scan: ScanCollector
    ) {
        if (!meta.isUnchanged) {
            val sourceText = rawBytes.decodeToString()
            scan.markdownQueue.add(Pair(meta.relStr, sourceText))
            store.upsertFileRecord(
                SqliteGraphStore.FileRecordUpsert(
                    projectId = projectId,
                    relPath = meta.relStr,
                    language = "markdown",
                    moduleId = if (meta.relStr.startsWith("plans/")) "plans" else "vault",
                    sourceSet = "docs",
                    semioticLayer = if (meta.relStr.startsWith("plans/")) "syntactic" else "semantic",
                    archLayer = "infrastructure",
                    sha256 = meta.sha256,
                    mtime = meta.mtime,
                    loc = sourceText.lines().size,
                    topLevelDeclCount = 1
                )
            )
            scan.filesIndexed++
        } else {
            scan.unchangedMarkdownQueue.add(Pair(meta.relStr, meta.filePath))
            scan.filesSkipped++
        }
    }

    private fun processCodeFile(
        meta: DiscoveredFileMeta,
        rawBytes: ByteArray,
        ctx: WorkspaceScanContext,
        scan: ScanCollector
    ) {
        val fileId = "${ctx.projectId}:${meta.relStr}"
        if (meta.isUnchanged && fileId in ctx.filesWithRawEdges) {
            scan.filesSkipped++
            return
        }

        val sourceText = rawBytes.decodeToString()
        val maskedText = if (meta.lang == "kotlin") {
            PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "kotlin")
        } else {
            null
        }
        val (fNodes, fEdges, topDeclCount) = PolyglotAstExtractor.extractFile(
            ctx.projectId, meta.relStr, sourceText, ctx.knownModules, maskedText
        )
        val (semLayer, archLayer, _) = SemioticLayerClassifier.classify(meta.relStr)
        val modId = ModuleBoundaryDetector.resolveModuleId(meta.relStr, ctx.knownModules)
        val srcSet = SemioticLayerClassifier.detectSourceSet(meta.relStr)

        store.upsertFileRecord(
            SqliteGraphStore.FileRecordUpsert(
                projectId = ctx.projectId,
                relPath = meta.relStr,
                language = meta.lang,
                moduleId = modId,
                sourceSet = srcSet,
                semioticLayer = semLayer,
                archLayer = archLayer,
                sha256 = meta.sha256,
                mtime = meta.mtime,
                loc = sourceText.lines().size,
                topLevelDeclCount = topDeclCount
            )
        )
        if (meta.lang == "kotlin" && maskedText != null) {
            scan.pendingKotlinComposeFiles.add(
                PendingKotlinComposeFile(meta.relStr, sourceText, maskedText, fEdges)
            )
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
        val meta = DiscoveredFileMeta(filePath, relStr, ext, lang, isUnchanged, sha256, mtime)

        if (ext == ".md") {
            processMarkdownFile(meta, rawBytes, ctx.projectId, scan)
        } else {
            processCodeFile(meta, rawBytes, ctx, scan)
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

    private data class ComposeFileSyncContext(
        val projectId: String,
        val fileId: String,
        val relStr: String,
        val modId: String,
        val semLayer: String,
        val archLayer: String
    )

    private fun syncComposeNodeRecords(
        bundleNodes: List<ComposeNodeRecord>,
        syncCtx: ComposeFileSyncContext,
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
                    projectId = syncCtx.projectId,
                    fileId = syncCtx.fileId,
                    name = shortName,
                    qualifiedName = cn.fqName,
                    kind = "composable",
                    signature = "@Composable fun $shortName",
                    startLine = cn.startLine,
                    endLine = cn.endLine,
                    moduleId = syncCtx.modId,
                    sourceSet = cn.sourceSet,
                    semioticLayer = syncCtx.semLayer,
                    archLayer = syncCtx.archLayer,
                    isTestDouble = false
                )
            }
            mergedNodeMap[cn.nodeId] = compGraphNode
            changedNodes.add(compGraphNode)
        }
    }

    private fun syncComposeEdgeEntities(
        bundleEdges: List<GraphEdge>,
        syncCtx: ComposeFileSyncContext,
        mergedNodeMap: MutableMap<String, GraphNode>,
        changedNodes: MutableList<GraphNode>
    ) {
        for (ce in bundleEdges) {
            if (ce.sourceNodeId.endsWith("#file") && ce.sourceNodeId !in mergedNodeMap) {
                val fileNode = GraphNode(
                    nodeId = ce.sourceNodeId,
                    projectId = syncCtx.projectId,
                    fileId = syncCtx.fileId,
                    name = syncCtx.relStr.substringAfterLast('/'),
                    qualifiedName = syncCtx.relStr,
                    kind = "file_scope",
                    signature = syncCtx.relStr,
                    startLine = 1,
                    endLine = 1,
                    moduleId = syncCtx.modId,
                    sourceSet = SemioticLayerClassifier.detectSourceSet(syncCtx.relStr),
                    semioticLayer = syncCtx.semLayer,
                    archLayer = syncCtx.archLayer
                )
                mergedNodeMap[fileNode.nodeId] = fileNode
                changedNodes.add(fileNode)
            }
            if (ce.edgeType in setOf("MUTATES_PROPERTY", "PROVIDES_COMPOSITION_LOCAL", "CONSUMES_COMPOSITION_LOCAL", "OBSERVES_STATE")) {
                val symName = ce.targetNodeId.removePrefix("sym:")
                if (mergedNodeMap.values.none { it.name == symName }) {
                    val syntheticId = "${syncCtx.fileId}#$symName"
                    val synNode = GraphNode(
                        nodeId = syntheticId,
                        projectId = syncCtx.projectId,
                        fileId = syncCtx.fileId,
                        name = symName,
                        qualifiedName = symName,
                        kind = "variable",
                        signature = symName,
                        startLine = ce.lineNumber,
                        endLine = ce.lineNumber,
                        moduleId = syncCtx.modId,
                        sourceSet = SemioticLayerClassifier.detectSourceSet(syncCtx.relStr),
                        semioticLayer = syncCtx.semLayer,
                        archLayer = syncCtx.archLayer
                    )
                    mergedNodeMap[syntheticId] = synNode
                    changedNodes.add(synNode)
                }
            }
        }
    }

    private fun resolveFileLocalClassStability(
        baseStability: Map<String, Pair<String, String>>,
        fileClasses: Map<String, Pair<String, String>>?
    ): Map<String, Pair<String, String>> {
        if (fileClasses.isNullOrEmpty()) return baseStability
        val merged = HashMap<String, Pair<String, String>>(baseStability.size + fileClasses.size)
        merged.putAll(baseStability)
        merged.putAll(fileClasses)
        return merged
    }

    private fun processComposeFiles(
        ctx: WorkspaceScanContext,
        stabilityPatterns: List<Regex>,
        scan: ScanCollector,
        mergedNodeMap: MutableMap<String, GraphNode>
    ): Pair<List<ComposeNodeRecord>, List<ComposeParamRecord>> {
        if (scan.pendingKotlinComposeFiles.isEmpty()) return Pair(emptyList(), emptyList())
        val workspaceNodesSnapshot = mergedNodeMap.values.toList()
        val rawStability = mutableMapOf<String, Pair<String, String>>()
        val fileClassStability = HashMap<String, Map<String, Pair<String, String>>>(scan.pendingKotlinComposeFiles.size)
        for (item in scan.pendingKotlinComposeFiles) {
            val classesInFile = composeExtractor.analyzeClassesInSource(item.sourceText, item.maskedText, stabilityPatterns)
            if (classesInFile.isNotEmpty()) {
                fileClassStability[item.relStr] = classesInFile
                rawStability.putAll(classesInFile)
            }
        }
        val precomputedStability = ComposeTypeStabilityAnalyzer.buildWorkspaceClassStability(
            workspaceNodesSnapshot,
            rawStability,
            stabilityPatterns
        )

        val allNewComposeNodes = mutableListOf<ComposeNodeRecord>()
        val allNewComposeParams = mutableListOf<ComposeParamRecord>()
        for (item in scan.pendingKotlinComposeFiles) {
            val relStr = item.relStr
            val sourceText = item.sourceText
            val fileId = "${ctx.projectId}:$relStr"
            store.deleteComposeNodesForFile(ctx.projectId, relStr)
            val bundle = composeExtractor.extractWithPrecomputedStability(
                ComposeStabilityExtractor.PrecomputedComposeInput(
                    projectSlug = ctx.projectId,
                    relPath = relStr,
                    sourceText = sourceText,
                    masked = item.maskedText,
                    stabilityPatterns = stabilityPatterns,
                    localClassStability = resolveFileLocalClassStability(precomputedStability, fileClassStability[relStr])
                )
            )
            if (isComposeSourceFile(bundle, sourceText)) {
                allNewComposeNodes.addAll(bundle.nodes)
                allNewComposeParams.addAll(bundle.params)

                val (semLayer, archLayer, _) = SemioticLayerClassifier.classify(relStr)
                val modId = ModuleBoundaryDetector.resolveModuleId(relStr, ctx.knownModules)
                val syncCtx = ComposeFileSyncContext(ctx.projectId, fileId, relStr, modId, semLayer, archLayer)

                syncComposeNodeRecords(bundle.nodes, syncCtx, mergedNodeMap, scan.changedNodes)
                syncComposeEdgeEntities(bundle.edges, syncCtx, mergedNodeMap, scan.changedNodes)

                scan.pendingRawEdges[fileId] = item.baseEdges + bundle.edges
            } else {
                scan.pendingRawEdges[fileId] = item.baseEdges
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
        ctx: WorkspaceScanContext,
        scan: ScanCollector,
        reEvaluate: Boolean
    ) {
        if (!reEvaluate) return
        val queuedRels = scan.markdownQueue.mapTo(mutableSetOf()) { it.first }
        for ((relMd, filePath) in scan.unchangedMarkdownQueue) {
            queueMarkdownIfReadable(ctx.projectId, relMd, filePath, queuedRels, scan.markdownQueue)
        }
        if (ctx.normalizedSubpaths != null && ctx.normalizedSubpaths.isNotEmpty()) {
            for (indexedRel in ctx.cachedFiles.keys) {
                if (indexedRel.endsWith(".md")) {
                    queueMarkdownIfReadable(ctx.projectId, indexedRel, "${ctx.root}/$indexedRel", queuedRels, scan.markdownQueue)
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
        val scan = store.withSavepoint("sp_scan_workspace") {
            val s = scanWorkspaceFiles(scanCtx)
            val deleted = purgeDeletedFiles(projectId, root, cachedFiles, s.validDiskFiles, normalizedSubpaths)
            Pair(s, deleted)
        }
        val scanResult = scan.first
        val filesDeleted = scan.second

        if (!effectiveForce && scanResult.filesIndexed == 0 && filesDeleted == 0 && cachedFiles.isNotEmpty()) {
            return buildStatsResponse(projectId, 0, scanResult.filesSkipped)
        }

        return store.withSavepoint("sp_finalize_index") {
            val mergedNodeMap = store.listNodes(projectId).associateBy { it.nodeId }.toMutableMap()
            for (n in modNodes) mergedNodeMap[n.nodeId] = n
            for (n in scanResult.changedNodes) mergedNodeMap[n.nodeId] = n

            val (allNewComposeNodes, allNewComposeParams) = processComposeFiles(
                ctx = scanCtx,
                stabilityPatterns = stabilityPatterns,
                scan = scanResult,
                mergedNodeMap = mergedNodeMap
            )

            val combinedNodes = mergedNodeMap.values.toList()
            val (resolvedEdges, kmpEdges) = resolveWorkspaceEdges(
                projectId = projectId,
                combinedNodes = combinedNodes,
                pendingRawEdges = scanResult.pendingRawEdges,
                allNewComposeNodes = allNewComposeNodes,
                allNewComposeParams = allNewComposeParams
            )

            reevaluateMarkdownFiles(
                ctx = scanCtx,
                scan = scanResult,
                reEvaluate = scanResult.codeFilesIndexed > 0 || filesDeleted > 0
            )

            val docNodes = mutableListOf<GraphNode>()
            val docEdges = mutableListOf<GraphEdge>()
            for ((relMd, mdText) in scanResult.markdownQueue) {
                val (dNodes, dEdges) = VaultPlanBridge.extractMarkdownAndPlans(projectId, relMd, mdText, combinedNodes)
                docNodes.addAll(dNodes)
                docEdges.addAll(dEdges)
            }

            val endpointNodes = extractEndpoints(projectId, resolvedEdges, docEdges)

            store.upsertNodesAndEdges(
                projectId = projectId,
                nodes = modNodes + scanResult.changedNodes.distinctBy { it.nodeId } + docNodes.distinctBy { it.nodeId } + endpointNodes,
                edges = modEdges + resolvedEdges + kmpEdges + docEdges
            )
            store.setProjectMetadata(projectId, "extractor_version", PolyglotAstExtractor.EXTRACTOR_VERSION)

            buildStatsResponse(projectId, scanResult.filesIndexed, scanResult.filesSkipped)
        }
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

