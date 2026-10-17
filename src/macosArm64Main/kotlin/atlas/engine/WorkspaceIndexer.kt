package atlas.engine

import atlas.bridge.VaultPlanBridge
import atlas.db.SqliteGraphStore
import atlas.extract.ModuleBoundaryDetector
import atlas.extract.PolyglotAstExtractor
import atlas.extract.SemioticLayerClassifier
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
 * and stale plan edge cleanup (`BUG-14`).
 */
class WorkspaceIndexer(
    private val workspaceRoot: String,
    private val store: SqliteGraphStore
) {
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

    fun indexWorkspace(
        projectId: String,
        subpaths: List<String>? = null
    ): JsonObject {
        val root = PlatformFiles.canonicalPath(workspaceRoot)
        val normalizedSubpaths = subpaths
            ?.map { normalizeSubpath(it, root) }
            ?.filter { it.isNotEmpty() }

        val (modNodes, modEdges, knownModules) = ModuleBoundaryDetector.extractModuleGraph(root, projectId)
        val cachedFiles = store.getFileRecordsMap(projectId)
        val filesWithRawEdges = store.filesWithRawSymbolEdges(projectId)

        val changedNodes = mutableListOf<GraphNode>()
        val markdownQueue = mutableListOf<Pair<String, String>>()
        val unchangedPlanQueue = mutableListOf<Pair<String, String>>()
        var codeFilesIndexed = 0
        var filesIndexed = 0
        var filesSkipped = 0

        val allFiles = PlatformFiles.listFilesRecursively(root)
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
            val isUnchanged = mtimeUnchanged || (cached != null && cached.first == sha256)

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
            store.replaceFileRawSymbolEdges(projectId, "$projectId:$relStr", fEdges)
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
                filesDeleted++
            }
        }

        // BUG-12: Short-circuit no-op runs when zero files were indexed or deleted and project already has nodes
        if (filesIndexed == 0 && filesDeleted == 0 && cachedFiles.isNotEmpty()) {
            val stats = store.getStats(projectId)
            return buildJsonObject {
                put("project_id", stats.projectId)
                put("files", stats.files)
                put("nodes", stats.nodes)
                put("edges", stats.edges)
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
            nodes = modNodes + changedNodes + docNodes,
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
}
