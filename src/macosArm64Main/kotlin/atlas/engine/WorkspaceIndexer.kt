package atlas.engine

import atlas.bridge.VaultPlanBridge
import atlas.db.SqliteGraphStore
import atlas.extract.ComposeStabilityExtractor
import atlas.extract.ComposeTypeStabilityAnalyzer
import atlas.extract.ModuleBoundaryDetector
import atlas.extract.PolyglotAstExtractor
import atlas.extract.SemioticLayerClassifier
import atlas.model.FileRecordUpsert
import atlas.model.GraphStore
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
    private val store: GraphStore
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
        val filePath: String,
        val baseEdges: List<GraphEdge>
    )

    private class ScanCollector {
        val validDiskFiles = mutableSetOf<String>()
        val changedNodes = mutableListOf<GraphNode>()
        val purgedSymbolNames = mutableSetOf<String>()
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
                FileRecordUpsert(
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

        scan.purgedSymbolNames.addAll(store.getDeclaredSymbolsForFile(ctx.projectId, fileId))

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
            FileRecordUpsert(
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
        if (meta.lang == "kotlin") {
            val mightContainComposeOrClass = sourceText.contains("@Composable") ||
                sourceText.contains("class ") ||
                sourceText.contains("interface ")
            if (mightContainComposeOrClass) {
                scan.pendingKotlinComposeFiles.add(
                    PendingKotlinComposeFile(meta.relStr, meta.filePath, fEdges)
                )
            } else {
                scan.pendingRawEdges[fileId] = fEdges
            }
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
        normalizedSubpaths: List<String>?,
        purgedSymbolsCollector: MutableSet<String>? = null
    ): Int {
        var filesDeleted = 0
        for (indexedRel in cachedFiles.keys) {
            if (indexedRel == "__endpoints__") continue
            if (!matchesSubpaths(indexedRel, normalizedSubpaths) && PlatformFiles.exists("$root/$indexedRel")) {
                continue
            }
            if (indexedRel !in validDiskFiles) {
                val fileId = "$projectId:$indexedRel"
                if (purgedSymbolsCollector != null) {
                    purgedSymbolsCollector.addAll(store.getDeclaredSymbolsForFile(projectId, fileId))
                }
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

    private fun analyzeFileClasses(
        item: PendingKotlinComposeFile,
        stabilityPatterns: List<Regex>
    ): Map<String, Pair<String, String>> {
        val sourceText = PlatformFiles.readText(item.filePath)
        val maskedText = PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "kotlin")
        return composeExtractor.analyzeClassesInSource(sourceText, maskedText, stabilityPatterns)
    }

    private class ComposeProcessingContext(
        val ctx: WorkspaceScanContext,
        val stabilityPatterns: List<Regex>,
        val precomputedStability: Map<String, Pair<String, String>>,
        val fileClassStability: Map<String, Map<String, Pair<String, String>>>,
        val scan: ScanCollector,
        val mergedNodeMap: MutableMap<String, GraphNode>
    )

    private fun processSingleComposeFile(
        item: PendingKotlinComposeFile,
        pctx: ComposeProcessingContext,
        allNewComposeNodes: MutableList<ComposeNodeRecord>,
        allNewComposeParams: MutableList<ComposeParamRecord>
    ) {
        val relStr = item.relStr
        val fileId = "${pctx.ctx.projectId}:$relStr"
        store.deleteComposeNodesForFile(pctx.ctx.projectId, relStr)
        val sourceText = PlatformFiles.readText(item.filePath)
        if (!sourceText.contains("@Composable")) {
            pctx.scan.pendingRawEdges[fileId] = item.baseEdges
            return
        }
        val maskedText = PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "kotlin")
        val bundle = composeExtractor.extractWithPrecomputedStability(
            ComposeStabilityExtractor.PrecomputedComposeInput(
                projectSlug = pctx.ctx.projectId,
                relPath = relStr,
                sourceText = sourceText,
                masked = maskedText,
                stabilityPatterns = pctx.stabilityPatterns,
                localClassStability = resolveFileLocalClassStability(pctx.precomputedStability, pctx.fileClassStability[relStr])
            )
        )
        if (isComposeSourceFile(bundle, sourceText)) {
            allNewComposeNodes.addAll(bundle.nodes)
            allNewComposeParams.addAll(bundle.params)

            val (semLayer, archLayer, _) = SemioticLayerClassifier.classify(relStr)
            val modId = ModuleBoundaryDetector.resolveModuleId(relStr, pctx.ctx.knownModules)
            val syncCtx = ComposeFileSyncContext(pctx.ctx.projectId, fileId, relStr, modId, semLayer, archLayer)

            syncComposeNodeRecords(bundle.nodes, syncCtx, pctx.mergedNodeMap, pctx.scan.changedNodes)
            syncComposeEdgeEntities(bundle.edges, syncCtx, pctx.mergedNodeMap, pctx.scan.changedNodes)

            pctx.scan.pendingRawEdges[fileId] = item.baseEdges + bundle.edges
        } else {
            pctx.scan.pendingRawEdges[fileId] = item.baseEdges
        }
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
            val classesInFile = analyzeFileClasses(item, stabilityPatterns)
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
        val pctx = ComposeProcessingContext(ctx, stabilityPatterns, precomputedStability, fileClassStability, scan, mergedNodeMap)
        for (item in scan.pendingKotlinComposeFiles) {
            processSingleComposeFile(item, pctx, allNewComposeNodes, allNewComposeParams)
        }
        return Pair(allNewComposeNodes, allNewComposeParams)
    }

    private data class EdgeResolutionContext(
        val projectId: String,
        val combinedNodes: List<GraphNode>,
        val pendingRawEdges: Map<String, List<GraphEdge>>,
        val allNewComposeNodes: List<ComposeNodeRecord>,
        val allNewComposeParams: List<ComposeParamRecord>,
        val isFullReindex: Boolean,
        val declaredSymbols: Set<String>,
        val changedNodes: List<GraphNode>
    )

    private fun resolveWorkspaceEdges(
        ctx: EdgeResolutionContext
    ): Pair<List<GraphEdge>, List<GraphEdge>> {
        if (ctx.pendingRawEdges.isNotEmpty()) {
            store.replaceBatchRawSymbolEdges(ctx.projectId, ctx.pendingRawEdges)
        }
        if (ctx.allNewComposeNodes.isNotEmpty() || ctx.allNewComposeParams.isNotEmpty()) {
            store.upsertComposeNodesAndParams(ctx.allNewComposeNodes, ctx.allNewComposeParams)
        }

        if (ctx.isFullReindex) {
            store.deleteAllResolvedCodeEdges(ctx.projectId)
            val allPersistedRawEdges = store.listRawSymbolEdges(ctx.projectId)
            val resolvedEdges = PolyglotAstExtractor.resolveCrossFileEdges(ctx.projectId, ctx.combinedNodes, allPersistedRawEdges)
            val kmpEdges = ModuleBoundaryDetector.synthesizeKmpExpectActualEdges(ctx.projectId, ctx.combinedNodes)
            return Pair(resolvedEdges, kmpEdges)
        }

        val touchedRawEdges = ctx.pendingRawEdges.values.flatten()
        val crossFileRawEdges = if (ctx.declaredSymbols.isNotEmpty()) {
            store.listRawSymbolEdgesTargetingSymbols(ctx.projectId, ctx.declaredSymbols)
        } else {
            emptyList()
        }
        val touchedEdgeIds = touchedRawEdges.mapTo(mutableSetOf()) { it.edgeId }
        val otherFileRawEdges = crossFileRawEdges.filter { it.edgeId !in touchedEdgeIds }

        store.deleteEdgesForRawEdges(ctx.projectId, otherFileRawEdges)

        val rawEdgesToResolve = (touchedRawEdges + otherFileRawEdges).distinctBy { it.edgeId }
        val resolvedEdges = PolyglotAstExtractor.resolveCrossFileEdges(ctx.projectId, ctx.combinedNodes, rawEdgesToResolve)

        val hasKmpDecls = ctx.changedNodes.any { it.kind == "expect_decl" || it.kind == "actual_decl" }
        val kmpEdges = if (hasKmpDecls) {
            ModuleBoundaryDetector.synthesizeKmpExpectActualEdges(ctx.projectId, ctx.combinedNodes)
        } else {
            emptyList()
        }
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
        val isFullReindex = (effectiveForce && normalizedSubpaths.isNullOrEmpty()) || cachedFiles.isEmpty()

        val scan = store.withSavepoint("sp_scan_workspace") {
            val s = scanWorkspaceFiles(scanCtx)
            val deleted = purgeDeletedFiles(projectId, root, cachedFiles, s.validDiskFiles, normalizedSubpaths, s.purgedSymbolNames)
            Pair(s, deleted)
        }
        val scanResult = scan.first
        val filesDeleted = scan.second

        if (!effectiveForce && scanResult.filesIndexed == 0 && filesDeleted == 0 && cachedFiles.isNotEmpty()) {
            return buildStatsResponse(projectId, 0, scanResult.filesSkipped)
        }

        return store.withSavepoint("sp_finalize_index") {
            val mergedNodeMap = initialNodeMap(projectId, modNodes, scanResult.changedNodes)

            val (allNewComposeNodes, allNewComposeParams) = processComposeFiles(
                ctx = scanCtx,
                stabilityPatterns = stabilityPatterns,
                scan = scanResult,
                mergedNodeMap = mergedNodeMap
            )

            val declaredSymbols = collectDeclaredSymbols(scanResult)
            val combinedNodes = mergedNodeMap.values.toList()

            val (resolvedEdges, kmpEdges) = resolveWorkspaceEdges(
                EdgeResolutionContext(
                    projectId = projectId,
                    combinedNodes = combinedNodes,
                    pendingRawEdges = scanResult.pendingRawEdges,
                    allNewComposeNodes = allNewComposeNodes,
                    allNewComposeParams = allNewComposeParams,
                    isFullReindex = isFullReindex,
                    declaredSymbols = declaredSymbols,
                    changedNodes = scanResult.changedNodes
                )
            )

            reevaluateMarkdownFiles(
                ctx = scanCtx,
                scan = scanResult,
                reEvaluate = scanResult.codeFilesIndexed > 0 || filesDeleted > 0
            )

            val (docNodes, docEdges) = extractDocumentationNodes(projectId, scanResult, combinedNodes)
            val endpointNodes = extractEndpoints(projectId, resolvedEdges, docEdges)

            val allUpsertNodes = modNodes + scanResult.changedNodes.distinctBy { it.nodeId } + docNodes.distinctBy { it.nodeId } + endpointNodes
            val allUpsertEdges = modEdges + resolvedEdges + kmpEdges + docEdges

            persistFinalizedGraph(projectId, allUpsertNodes, allUpsertEdges, filesDeleted)
            buildStatsResponse(projectId, scanResult.filesIndexed, scanResult.filesSkipped)
        }
    }

    private fun initialNodeMap(
        projectId: String,
        modNodes: List<GraphNode>,
        changedNodes: List<GraphNode>
    ): MutableMap<String, GraphNode> {
        val merged = store.listNodes(projectId).associateBy { it.nodeId }.toMutableMap()
        for (n in modNodes) merged[n.nodeId] = n
        for (n in changedNodes) merged[n.nodeId] = n
        return merged
    }

    private fun collectDeclaredSymbols(scanResult: ScanCollector): Set<String> {
        val declared = scanResult.purgedSymbolNames.toMutableSet()
        for (node in scanResult.changedNodes) {
            if (node.name.isNotBlank()) declared.add(node.name)
            if (node.qualifiedName.isNotBlank()) declared.add(node.qualifiedName)
        }
        return declared
    }

    private fun extractDocumentationNodes(
        projectId: String,
        scanResult: ScanCollector,
        combinedNodes: List<GraphNode>
    ): Pair<List<GraphNode>, List<GraphEdge>> {
        val docNodes = mutableListOf<GraphNode>()
        val docEdges = mutableListOf<GraphEdge>()
        for ((relMd, mdText) in scanResult.markdownQueue) {
            val (dNodes, dEdges) = VaultPlanBridge.extractMarkdownAndPlans(projectId, relMd, mdText, combinedNodes)
            docNodes.addAll(dNodes)
            docEdges.addAll(dEdges)
        }
        return Pair(docNodes, docEdges)
    }

    private fun persistFinalizedGraph(
        projectId: String,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        filesDeleted: Int
    ) {
        store.upsertNodesAndEdges(projectId = projectId, nodes = nodes, edges = edges)
        if (filesDeleted > 0 && nodes.isEmpty() && edges.isEmpty()) {
            store.recomputeDegrees(projectId)
        }
        store.setProjectMetadata(projectId, "extractor_version", PolyglotAstExtractor.EXTRACTOR_VERSION)
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

