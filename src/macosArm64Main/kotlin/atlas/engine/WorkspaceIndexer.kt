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
        val versionMismatch = storedVersion != PolyglotAstExtractor.EXTRACTOR_VERSION
        val effectiveForce = force || forceComposeReindex || versionMismatch

        val changedNodes = mutableListOf<GraphNode>()
        val markdownQueue = mutableListOf<Pair<String, String>>()
        val unchangedPlanQueue = mutableListOf<Pair<String, String>>()
        val pendingKotlinComposeFiles = mutableListOf<Triple<String, String, List<GraphEdge>>>()
        val pendingRawEdges = mutableMapOf<String, List<GraphEdge>>()
        var codeFilesIndexed = 0
        var filesIndexed = 0
        var filesSkipped = 0

        val allFiles = PlatformFiles.listFilesRecursively(root) { dirName ->
            dirName in SKIP_DIRS || dirName.startsWith(".venv")
        }
        val validDiskFiles = mutableSetOf<String>()
        for (filePath in allFiles) {
            val relStr = filePath.removePrefix(root).removePrefix("/").replace('\\', '/')
            val parts = relStr.split('/')
            if (parts.any { it in SKIP_DIRS || it.startsWith(".venv") }) continue

            if (normalizedSubpaths != null && normalizedSubpaths.isNotEmpty()) {
                val matchesSubpath = normalizedSubpaths.any { cleanSp ->
                    relStr == cleanSp || relStr.startsWith("$cleanSp/")
                }
                if (!matchesSubpath) continue
            }

            val ext = if (relStr.contains('.')) "." + relStr.substringAfterLast('.') else ""
            val lang = PolyglotAstExtractor.supportedLanguage(ext)

            if (lang.isEmpty() && ext != ".md") continue

            validDiskFiles.add(relStr)

            val mtime = PlatformFiles.mtime(filePath)
            val cached = cachedFiles[relStr]
            val mtimeUnchanged = cached != null && abs(cached.second - mtime) < 1e-6
            val rawBytes: ByteArray by lazy { PlatformFiles.readBytes(filePath) }
            val sha256 = if (mtimeUnchanged) cached!!.first else Sha256.digest(rawBytes)
            val isUnchanged = !effectiveForce && (mtimeUnchanged || (cached != null && cached.first == sha256))

            if (ext == ".md") {
                if (!isUnchanged) {
                    val sourceText = rawBytes.decodeToString()
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
                    if (relStr.startsWith("plans/") || relStr.contains("/plans/")) {
                        unchangedPlanQueue.add(Pair(relStr, rawBytes.decodeToString()))
                    }
                    filesSkipped++
                }
                continue
            }

            val fileId = "$projectId:$relStr"
            if (isUnchanged && fileId in filesWithRawEdges) {
                filesSkipped++
                continue
            }

            val sourceText = rawBytes.decodeToString()
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
            if (lang == "kotlin") {
                pendingKotlinComposeFiles.add(Triple(relStr, sourceText, fEdges))
            } else {
                pendingRawEdges[fileId] = fEdges
            }
            changedNodes.addAll(fNodes)
            codeFilesIndexed++
            filesIndexed++
        }

        var filesDeleted = 0
        for (indexedRel in cachedFiles.keys) {
            if (normalizedSubpaths != null && normalizedSubpaths.isNotEmpty()) {
                val matchesSubpath = normalizedSubpaths.any { cleanSp ->
                    indexedRel == cleanSp || indexedRel.startsWith("$cleanSp/")
                }
                if (!matchesSubpath) continue
            }
            if (indexedRel !in validDiskFiles) {
                store.deleteFileRecord(projectId, indexedRel, recomputeDegrees = false)
                store.deleteComposeNodesForFile(projectId, indexedRel)
                filesDeleted++
            }
        }

        // BUG-12: Short-circuit no-op runs when zero files were indexed or deleted and project already has nodes
        if (!effectiveForce && filesIndexed == 0 && filesDeleted == 0 && cachedFiles.isNotEmpty()) {
            val stats = store.getStats(projectId)
            val composeNodes = store.listComposeNodes(projectId)
            return buildJsonObject {
                put("project_id", stats.projectId)
                put("files", stats.files)
                put("nodes", stats.nodes)
                put("edges", stats.edges)
                put("compose_nodes", composeNodes.size)
                put("files_indexed", 0)
                put("files_skipped", filesSkipped)
                putJsonObject("provenance_breakdown") {
                    stats.provenanceBreakdown.forEach { (k, v) -> put(k, v) }
                }
            }
        }

        val existingNodes = store.listNodes(projectId)
        val mergedNodeMap = existingNodes.associateBy { it.nodeId }.toMutableMap()
        for (n in modNodes) {
            mergedNodeMap[n.nodeId] = n
        }
        for (n in changedNodes) {
            mergedNodeMap[n.nodeId] = n
        }

        // Execute Dual-Mode Compose extraction across changed Kotlin files using the full workspace node table
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
            val isComposeFile = bundle.nodes.isNotEmpty() ||
                bundle.edges.any { it.edgeType in setOf("PROVIDES_COMPOSITION_LOCAL", "CONSUMES_COMPOSITION_LOCAL", "OBSERVES_STATE") } ||
                sourceText.lineSequence().any { line ->
                    val t = line.trim()
                    (t.startsWith("import androidx.compose") || t.startsWith("import org.jetbrains.compose")) ||
                    t.startsWith("@Composable") || t.contains(" @Composable ")
                }

            if (isComposeFile) {
                allNewComposeNodes.addAll(bundle.nodes)
                allNewComposeParams.addAll(bundle.params)

                val (semLayer, archLayer, _) = SemioticLayerClassifier.classify(relStr)
                val modId = ModuleBoundaryDetector.resolveModuleId(relStr, knownModules)

                // Ensure every ComposeNodeRecord and mutated property/CompositionLocal has a GraphNode in `nodes`
                // so `sweepOrphanEdges` preserves all semantic Compose edges.
                for (cn in bundle.nodes) {
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

                for (ce in bundle.edges) {
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
                        val anyExisting = mergedNodeMap.values.any { it.name == symName }
                        if (!anyExisting) {
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

                pendingRawEdges[fileId] = baseEdges + bundle.edges
            } else {
                pendingRawEdges[fileId] = baseEdges
            }
        }

        if (pendingRawEdges.isNotEmpty()) {
            store.replaceBatchRawSymbolEdges(projectId, pendingRawEdges)
        }

        if (allNewComposeNodes.isNotEmpty() || allNewComposeParams.isNotEmpty()) {
            store.upsertComposeNodesAndParams(allNewComposeNodes, allNewComposeParams)
        }

        val combinedNodes = mergedNodeMap.values.toList()

        // When code files were incrementally re-indexed or deleted, purge all previous resolved AST edges
        // (and MODULE_DEPENDS_ON / EXPECT_ACTUAL edges) before re-inserting freshly resolved edges from
        // `raw_symbol_edges`, so deleted callees or changed imports never leave stale resolved edges behind.
        store.deleteAllResolvedCodeEdges(projectId)

        // BUG-01: Resolve all persisted raw symbol edges across the entire project so unchanged callers
        // link to newly added or modified callees.
        val allPersistedRawEdges = store.listRawSymbolEdges(projectId)
        val resolvedEdges = PolyglotAstExtractor.resolveCrossFileEdges(
            projectId, combinedNodes, allPersistedRawEdges
        )
        val kmpEdges = ModuleBoundaryDetector.synthesizeKmpExpectActualEdges(
            projectId, combinedNodes
        )

        // When code files were incrementally re-indexed or deleted, also re-evaluate plans/*.md
        // so PLAN_SCOPES_FILE edges link against newly added or updated code symbols.
        if (codeFilesIndexed > 0 || filesDeleted > 0) {
            val queuedRels = markdownQueue.map { it.first }.toSet()
            for ((relPlan, planText) in unchangedPlanQueue) {
                if (relPlan !in queuedRels) {
                    store.deleteOutgoingEdgesForFile(projectId, "$projectId:$relPlan")
                    markdownQueue.add(Pair(relPlan, planText))
                }
            }
            if (normalizedSubpaths != null && normalizedSubpaths.isNotEmpty()) {
                for (indexedRel in cachedFiles.keys) {
                    if ((indexedRel.startsWith("plans/") || indexedRel.contains("/plans/")) && indexedRel.endsWith(".md") && indexedRel !in markdownQueue.map { it.first }) {
                        val fullPlanPath = "$root/$indexedRel"
                        if (PlatformFiles.exists(fullPlanPath)) {
                            store.deleteOutgoingEdgesForFile(projectId, "$projectId:$indexedRel")
                            markdownQueue.add(Pair(indexedRel, PlatformFiles.readText(fullPlanPath)))
                        }
                    }
                }
            }
        }

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
            nodes = modNodes + changedNodes.distinctBy { it.nodeId } + docNodes,
            edges = modEdges + resolvedEdges + kmpEdges + docEdges
        )
        store.setProjectMetadata(projectId, "extractor_version", PolyglotAstExtractor.EXTRACTOR_VERSION)

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

    /**
     * Runs Dual-Mode Compose indexing: indexes workspace `.kt` files with optional `stability_config.conf`,
     * ingests optional Compose Compiler metrics reports (`*-composables.txt`), and ingests optional
     * `@TraceRecomposition` runtime telemetry (`traceLogPath`).
     */
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

        var compilerUpgraded = 0
        if (!compilerReportsDir.isNullOrBlank()) {
            val resolvedDir = if (compilerReportsDir.startsWith("/")) compilerReportsDir else "$root/$compilerReportsDir"
            if (PlatformFiles.exists(resolvedDir)) {
                val reportFiles = if (resolvedDir.endsWith(".txt") || resolvedDir.endsWith(".csv")) {
                    listOf(resolvedDir)
                } else {
                    PlatformFiles.listFilesRecursively(resolvedDir).filter {
                        it.endsWith("-composables.txt") || it.endsWith("composables.txt")
                    }
                }
                val existing = store.listComposeNodes(projectId)
                for (rf in reportFiles) {
                    val text = PlatformFiles.readText(rf)
                    val (updNodes, updParams) = composeExtractor.parseCompilerComposablesReport(projectId, text, existing)
                    if (updNodes.isNotEmpty() || updParams.isNotEmpty()) {
                        store.upsertComposeNodesAndParams(updNodes, updParams)
                        compilerUpgraded += updNodes.size
                    }
                }
            }
        }

        var traceRecordsIngested = 0
        if (!traceLogPath.isNullOrBlank()) {
            val resolvedLog = if (traceLogPath.startsWith("/")) traceLogPath else "$root/$traceLogPath"
            if (PlatformFiles.exists(resolvedLog)) {
                val rawLog = PlatformFiles.readText(resolvedLog)
                val traces = composeExtractor.parseTraceTelemetry(rawLog)
                val existingNodes = store.listComposeNodes(projectId)
                for (tr in traces) {
                    val matched = existingNodes.firstOrNull {
                        it.nodeId == tr.fqNameOrNodeId ||
                            it.fqName == tr.fqNameOrNodeId ||
                            it.fqName.endsWith(".${tr.fqNameOrNodeId}")
                    }
                    val targetNodeId = matched?.nodeId ?: tr.fqNameOrNodeId
                    store.upsertComposeTraceMetric(
                        nodeId = targetNodeId,
                        sessionId = tr.sessionId,
                        recompositionCount = tr.recompositionCount,
                        skipCount = tr.skipCount,
                        dirtyParamsJson = tr.dirtyParamsJson,
                        recordedAt = tr.recordedAt
                    )
                    traceRecordsIngested++
                }
            }
        }

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
}

