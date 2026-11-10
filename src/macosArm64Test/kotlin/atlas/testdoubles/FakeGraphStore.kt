package atlas.testdoubles

import atlas.model.ComplexityScoreRecord
import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.ComposeQueryResult
import atlas.model.FigmaDocument
import atlas.model.FigmaEdge
import atlas.model.FigmaNode
import atlas.model.FigmaSpatialBounds
import atlas.model.FigmaVariable
import atlas.model.FigmaVariableImpact
import atlas.model.FigmaViolation
import atlas.model.FileRecord
import atlas.model.FileRecordUpsert
import atlas.model.GraphEdge
import atlas.model.GraphHop
import atlas.model.GraphNode
import atlas.model.GraphStats
import atlas.model.GraphStore
import atlas.model.ImpactRadiusResult
import atlas.model.LiveSourceSlice
import atlas.model.NodeAnnotation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * In-memory test double implementing GraphStore for fast, zero-disk unit testing.
 */
class FakeGraphStore : GraphStore {
    val files = mutableMapOf<String, FileRecord>()
    val nodes = mutableMapOf<String, GraphNode>()
    val edges = mutableListOf<GraphEdge>()
    val metadata = mutableMapOf<Pair<String, String>, String>()
    val annotations = mutableListOf<NodeAnnotation>()
    val rawEdges = mutableMapOf<String, MutableList<GraphEdge>>()
    val composeNodes = mutableMapOf<String, MutableList<ComposeNodeRecord>>()
    val composeParams = mutableMapOf<String, MutableList<ComposeParamRecord>>()
    val composeTraceMetrics = mutableMapOf<String, Triple<Int, Int, String>>()
    val complexityScores = mutableMapOf<String, ComplexityScoreRecord>()
    val figmaDocuments = mutableMapOf<String, FigmaDocument>()
    val figmaNodes = mutableMapOf<String, MutableList<FigmaNode>>()
    val figmaVariables = mutableMapOf<String, MutableList<FigmaVariable>>()
    val figmaEdges = mutableMapOf<String, MutableList<FigmaEdge>>()

    override fun getProjectMetadata(projectId: String, key: String): String? =
        metadata[Pair(projectId, key)]

    override fun setProjectMetadata(projectId: String, key: String, value: String) {
        metadata[Pair(projectId, key)] = value
    }

    override fun insertNodeAnnotation(
        annotationId: String,
        projectId: String,
        nodeId: String,
        outcome: String,
        note: String,
        createdAtEpoch: Double
    ) {
        annotations.add(
            NodeAnnotation(
                annotationId = annotationId,
                projectId = projectId,
                nodeId = nodeId,
                outcome = outcome,
                note = note,
                createdAtEpoch = createdAtEpoch
            )
        )
    }

    override fun getNodeAnnotations(projectId: String, nodeId: String): List<NodeAnnotation> =
        annotations.filter { it.projectId == projectId && it.nodeId == nodeId }

    override fun getFileRecordsMap(projectId: String): Map<String, Pair<String, Double>> =
        files.values.filter { it.projectId == projectId && it.relPath != "__endpoints__" }
            .associate { it.relPath to Pair(it.sha256, it.mtime) }

    override fun upsertFileRecord(record: FileRecordUpsert): String {
        val fileId = "${record.projectId}:${record.relPath}"
        files[fileId] = FileRecord(
            fileId = fileId,
            projectId = record.projectId,
            relPath = record.relPath,
            language = record.language,
            moduleId = record.moduleId,
            sourceSet = record.sourceSet,
            semioticLayer = record.semioticLayer,
            archLayer = record.archLayer,
            sha256 = record.sha256,
            mtime = record.mtime,
            loc = record.loc,
            topLevelDeclCount = record.topLevelDeclCount
        )
        return fileId
    }

    override fun deleteFileRecord(projectId: String, relPath: String, recomputeDegrees: Boolean) {
        val fileId = "$projectId:$relPath"
        files.remove(fileId)
        val nodeIds = nodes.values.filter { it.fileId == fileId }.map { it.nodeId }.toSet()
        nodeIds.forEach { nodes.remove(it) }
        edges.removeAll { it.sourceNodeId in nodeIds || it.targetNodeId in nodeIds }
        rawEdges.remove(fileId)
    }

    override fun upsertNodesAndEdges(
        projectId: String,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>
    ) {
        nodes.forEach { this.nodes[it.nodeId] = it }
        this.edges.addAll(edges)
    }

    override fun replaceBatchRawSymbolEdges(projectId: String, fileEdgesMap: Map<String, List<GraphEdge>>) {
        for ((fileId, edgeList) in fileEdgesMap) {
            rawEdges[fileId] = edgeList.toMutableList()
        }
    }

    override fun replaceFileRawSymbolEdges(projectId: String, fileId: String, edges: List<GraphEdge>) {
        replaceBatchRawSymbolEdges(projectId, mapOf(fileId to edges))
    }

    override fun listRawSymbolEdges(projectId: String): List<GraphEdge> =
        rawEdges.values.flatten().filter { it.projectId == projectId }

    override fun filesWithRawSymbolEdges(projectId: String): Set<String> =
        rawEdges.keys.toSet()

    override fun deleteFileRawSymbolEdges(projectId: String, fileId: String) {
        rawEdges.remove(fileId)
    }

    override fun deleteOutgoingEdgesForFile(projectId: String, relPath: String) {
        val fileId = "$projectId:$relPath"
        val nodeIds = nodes.values.filter { it.fileId == fileId }.map { it.nodeId }.toSet()
        edges.removeAll { it.projectId == projectId && it.sourceNodeId in nodeIds }
    }

    override fun getDeclaredSymbolsForFile(projectId: String, fileId: String): List<String> =
        nodes.values.filter { it.projectId == projectId && it.fileId == fileId }.map { it.name }

    override fun listRawSymbolEdgesTargetingSymbols(projectId: String, symbols: Set<String>): List<GraphEdge> =
        listRawSymbolEdges(projectId).filter { edge ->
            val sym = edge.targetNodeId.removePrefix("sym:")
            sym in symbols
        }

    override fun deleteEdgesForRawEdges(projectId: String, rawEdges: List<GraphEdge>) {
        val edgeIds = rawEdges.map { it.edgeId }.toSet()
        edges.removeAll { it.projectId == projectId && it.edgeId in edgeIds }
    }

    override fun deleteAllResolvedCodeEdges(projectId: String) {
        edges.removeAll { it.projectId == projectId }
    }

    override fun ensureVirtualFileRecord(projectId: String, virtualRelPath: String): String {
        val fileId = "$projectId:$virtualRelPath"
        if (fileId !in files) {
            files[fileId] = FileRecord(
                fileId = fileId,
                projectId = projectId,
                relPath = virtualRelPath,
                language = "virtual",
                moduleId = "endpoints",
                sourceSet = "main",
                semioticLayer = "syntactic",
                archLayer = "interface",
                sha256 = "virtual",
                mtime = 0.0,
                loc = 0,
                topLevelDeclCount = 0
            )
        }
        return fileId
    }

    override fun recomputeDegrees(projectId: String) {
        for ((nodeId, node) in nodes) {
            if (node.projectId != projectId) continue
            val inDeg = edges.count { it.projectId == projectId && it.targetNodeId == nodeId }
            val outDeg = edges.count { it.projectId == projectId && it.sourceNodeId == nodeId }
            nodes[nodeId] = node.copy(inDegree = inDeg, outDegree = outDeg)
        }
    }

    override fun rebuildVocabulary(projectId: String) {}

    override fun getNode(projectId: String, target: String): GraphNode? =
        nodes.values.firstOrNull {
            it.projectId == projectId && (it.nodeId == target || it.name == target || it.qualifiedName == target)
        }

    override fun queryByUrl(projectId: String, target: String): GraphNode? =
        getNode(projectId, target)

    override fun queryNodesAtLocation(projectId: String, filePath: String, line: Int): List<GraphNode> =
        nodes.values.filter {
            it.projectId == projectId && (it.fileId.endsWith(filePath) || it.qualifiedName.startsWith(filePath)) &&
                it.startLine <= line && it.endLine >= line
        }

    override fun getNodesForFile(projectId: String, relPath: String): List<GraphNode> =
        nodes.values.filter { it.projectId == projectId && (it.fileId == "$projectId:$relPath" || it.fileId == relPath) }

    override fun getNodesForFileIds(projectId: String, fileIds: List<String>): List<GraphNode> {
        val set = fileIds.toSet()
        return nodes.values.filter { it.projectId == projectId && it.fileId in set }
    }

    override fun searchNodes(projectId: String, query: String, limit: Int, kind: String?): List<GraphNode> =
        nodes.values.filter {
            it.projectId == projectId &&
                (kind == null || it.kind == kind) &&
                (it.name.contains(query, ignoreCase = true) || it.qualifiedName.contains(query, ignoreCase = true))
        }.take(limit)

    override fun getCallers(projectId: String, nodeId: String, depth: Int): List<GraphHop> {
        val callerEdges = edges.filter { it.projectId == projectId && it.targetNodeId == nodeId }
        return callerEdges.mapNotNull { edge ->
            val srcNode = nodes[edge.sourceNodeId] ?: return@mapNotNull null
            GraphHop(
                sourceId = srcNode.nodeId,
                targetId = nodeId,
                edgeType = edge.edgeType,
                provenance = edge.provenance,
                hop = 1,
                name = srcNode.name,
                fileId = srcNode.fileId,
                startLine = srcNode.startLine,
                semioticLayer = srcNode.semioticLayer,
                archLayer = srcNode.archLayer
            )
        }
    }

    override fun getCallees(projectId: String, nodeId: String, depth: Int): List<GraphHop> {
        val calleeEdges = edges.filter { it.projectId == projectId && it.sourceNodeId == nodeId }
        return calleeEdges.mapNotNull { edge ->
            val tgtNode = nodes[edge.targetNodeId] ?: return@mapNotNull null
            GraphHop(
                sourceId = nodeId,
                targetId = tgtNode.nodeId,
                edgeType = edge.edgeType,
                provenance = edge.provenance,
                hop = 1,
                name = tgtNode.name,
                fileId = tgtNode.fileId,
                startLine = tgtNode.startLine,
                semioticLayer = tgtNode.semioticLayer,
                archLayer = tgtNode.archLayer
            )
        }
    }

    override fun getImpactRadius(projectId: String, nodeId: String, depth: Int): ImpactRadiusResult {
        val callers = getCallers(projectId, nodeId, depth)
        val callees = getCallees(projectId, nodeId, depth)
        val impactedNodeIds = (callers.map { it.sourceId } + callees.map { it.targetId }).distinct()
        val impactedNodeList = impactedNodeIds.mapNotNull { nodes[it] }
        val impactedFilesList = impactedNodeList.map { it.fileId }.distinct()
        val impactedModulesList = impactedNodeList.map { it.moduleId }.distinct()
        val impactedLayersList = impactedNodeList.map { it.archLayer }.distinct()
        return ImpactRadiusResult(
            seedNodeId = nodeId,
            depth = depth,
            impactedNodes = impactedNodeList,
            impactedFiles = impactedFilesList,
            impactedModules = impactedModulesList,
            impactedLayers = impactedLayersList,
            callers = callers,
            callees = callees
        )
    }

    override fun getGodNodes(projectId: String, topN: Int): List<GraphNode> =
        nodes.values.filter { it.projectId == projectId }
            .sortedByDescending { it.inDegree + it.outDegree }
            .take(topN)

    override fun resolveNodesByUrlOrRoute(projectId: String, rawUrlOrRoute: String, limit: Int): List<GraphNode> =
        searchNodes(projectId, rawUrlOrRoute, limit)

    override fun searchByUrl(projectId: String, rawUrlOrRoute: String, limit: Int): JsonObject =
        buildJsonObject {}

    override fun getShortestPath(projectId: String, sourceNodeId: String, targetNodeId: String, maxHops: Int): List<String> =
        listOf(sourceNodeId, targetNodeId)

    override fun sliceLiveSource(projectRoot: String, projectId: String, node: GraphNode): LiveSourceSlice =
        LiveSourceSlice(
            fresh = true,
            relPath = node.fileId.removePrefix("$projectId:"),
            startLine = node.startLine,
            endLine = node.endLine,
            indexedSha256 = "",
            liveSha256 = "",
            source = ""
        )

    override fun listFiles(projectId: String): List<FileRecord> =
        files.values.filter { it.projectId == projectId && it.relPath != "__endpoints__" }

    override fun listNodes(projectId: String): List<GraphNode> =
        nodes.values.filter { it.projectId == projectId }

    override fun listEdges(projectId: String, edgeTypes: List<String>?): List<GraphEdge> =
        edges.filter { it.projectId == projectId && (edgeTypes == null || it.edgeType in edgeTypes) }

    override fun getStats(projectId: String): GraphStats =
        GraphStats(
            projectId = projectId,
            files = files.values.count { it.projectId == projectId && it.relPath != "__endpoints__" },
            nodes = nodes.values.count { it.projectId == projectId },
            edges = edges.count { it.projectId == projectId },
            provenanceBreakdown = edges.filter { it.projectId == projectId }
                .groupingBy { it.provenance }
                .eachCount()
        )

    override fun listIndexedFiles(projectId: String): List<String> =
        files.values.filter { it.projectId == projectId && it.relPath != "__endpoints__" }.map { it.relPath }

    override fun listComposeNodes(projectSlug: String, onlyViolating: Boolean): List<ComposeNodeRecord> =
        composeNodes[projectSlug].orEmpty().filter { !onlyViolating || it.isRestartable }

    override fun getComposeNode(projectSlug: String, target: String): ComposeNodeRecord? =
        composeNodes[projectSlug].orEmpty().firstOrNull { it.nodeId == target || it.fqName == target }

    override fun listComposeParams(nodeId: String): List<ComposeParamRecord> =
        composeParams.values.flatten().filter { it.nodeId == nodeId }

    override fun listComposeParamsForProject(projectSlug: String): Map<String, List<ComposeParamRecord>> =
        composeParams

    override fun listComposeTraceMetrics(projectSlug: String): Map<String, Triple<Int, Int, String>> =
        composeTraceMetrics

    override fun queryComposeCascade(
        projectSlug: String,
        rootNodeId: String,
        maxDepth: Int
    ): List<ComposeQueryResult.CascadeHop> = emptyList()

    override fun queryComposeBlame(
        projectSlug: String,
        composableId: String
    ): List<ComposeQueryResult.BlameHop> = emptyList()

    override fun countPropertyMutations(projectSlug: String, targetSymbolOrNodeId: String, ownerClassName: String?): Int = 0

    override fun queryComplexNodes(projectId: String, minCognitive: Int, limit: Int): List<ComplexityScoreRecord> =
        complexityScores.values.filter { it.cognitive >= minCognitive }.take(limit)

    override fun queryArchitecturalRiskNodes(projectId: String, minRisk: Double, limit: Int): List<ComplexityScoreRecord> =
        complexityScores.values.take(limit)

    override fun getFigmaDocument(fileKey: String): FigmaDocument? = figmaDocuments[fileKey]
    override fun queryFigmaSpatialIntersection(fileKey: String, bounds: FigmaSpatialBounds): List<FigmaNode> = emptyList()
    override fun queryFigmaSpatialIntersection(fileKey: String, minX: Double, maxX: Double, minY: Double, maxY: Double): List<FigmaNode> = emptyList()
    override fun queryFigmaNodeById(fileKey: String, nodeId: String): FigmaNode? =
        figmaNodes[fileKey].orEmpty().firstOrNull { it.nodeId == nodeId }
    override fun queryFigmaNodesByName(fileKey: String, name: String): List<FigmaNode> =
        figmaNodes[fileKey].orEmpty().filter { it.name == name }
    override fun queryFigmaNodesByType(fileKey: String, nodeType: String): List<FigmaNode> =
        figmaNodes[fileKey].orEmpty().filter { it.nodeType == nodeType }
    override fun queryFigmaVariableById(fileKey: String, variableId: String): FigmaVariable? =
        figmaVariables[fileKey].orEmpty().firstOrNull { it.variableId == variableId }
    override fun queryFigmaVariableByName(fileKey: String, name: String): FigmaVariable? =
        figmaVariables[fileKey].orEmpty().firstOrNull { it.name == name }
    override fun queryFigmaEdgesBySource(fileKey: String, sourceNodeId: String): List<FigmaEdge> =
        figmaEdges[fileKey].orEmpty().filter { it.sourceNodeId == sourceNodeId }
    override fun queryFigmaEdgesByTarget(fileKey: String, targetNodeId: String): List<FigmaEdge> =
        figmaEdges[fileKey].orEmpty().filter { it.targetNodeId == targetNodeId }
    override fun queryFigmaEdgesByType(fileKey: String, edgeType: String): List<FigmaEdge> =
        figmaEdges[fileKey].orEmpty().filter { it.edgeType == edgeType }
    override fun resolveVariableAliasImpact(fileKey: String, variableIdOrName: String, depth: Int): List<FigmaVariable> = emptyList()
    override fun queryNodesBoundToVariable(fileKey: String, variableId: String): List<FigmaNode> = emptyList()
    override fun resolveVariableBlastRadius(fileKey: String, variableIdOrName: String, depth: Int): FigmaVariableImpact =
        FigmaVariableImpact(
            seedVariableIdOrName = variableIdOrName,
            depth = depth,
            impactedVariables = emptyList(),
            boundNodes = emptyList()
        )
    override fun queryFigmaCallers(fileKey: String, componentNameOrId: String, depth: Int): List<FigmaNode> = emptyList()
    override fun getLatestFigmaFileKey(): String? = figmaDocuments.keys.firstOrNull()
    override fun queryFigmaGodNodes(fileKey: String, limit: Int): List<Pair<FigmaNode, Int>> = emptyList()
    override fun queryFigmaNodesByIds(fileKey: String, nodeIds: List<String>): List<FigmaNode> =
        figmaNodes[fileKey].orEmpty().filter { it.nodeId in nodeIds }
    override fun findFigmaShortestPath(fileKey: String, sourceTarget: String, destTarget: String, depth: Int): List<FigmaNode> = emptyList()
    override fun countFigmaNodes(fileKey: String): Int = figmaNodes[fileKey].orEmpty().size
    override fun queryFigmaNodesByNameLike(fileKey: String, substring: String): List<FigmaNode> =
        figmaNodes[fileKey].orEmpty().filter { it.name.contains(substring, ignoreCase = true) }
    override fun queryCircularFillInHugViolations(fileKey: String): List<FigmaViolation> = emptyList()
    override fun queryStaticContainerFrameViolations(fileKey: String): List<FigmaViolation> = emptyList()
    override fun queryDefault100PxFrameHeightViolations(fileKey: String): List<FigmaViolation> = emptyList()
    override fun queryMinTouchTargetViolations(fileKey: String): List<FigmaViolation> = emptyList()
    override fun queryNegativeCoordinateViolations(fileKey: String): List<FigmaViolation> = emptyList()
    override fun queryChildAmputationWidthViolations(fileKey: String): List<FigmaViolation> = emptyList()
    override fun queryNoEmojiViolations(fileKey: String): List<FigmaViolation> = emptyList()
    override fun queryUnboundTokenViolations(fileKey: String): List<FigmaViolation> = emptyList()
    override fun queryAdHocDetachedSpecimenViolations(fileKey: String): List<FigmaViolation> = emptyList()
    override fun queryCanvasSpatialTopologyViolations(fileKey: String): List<FigmaViolation> = emptyList()
    override fun queryWcagContrastViolations(fileKey: String): List<FigmaViolation> = emptyList()

    override fun insertFigmaDocument(document: FigmaDocument) {
        figmaDocuments[document.fileKey] = document
    }

    override fun deleteFigmaDocument(fileKey: String) {
        figmaDocuments.remove(fileKey)
        figmaNodes.remove(fileKey)
        figmaVariables.remove(fileKey)
        figmaEdges.remove(fileKey)
    }

    override fun insertFigmaNodes(fileKey: String, nodes: List<FigmaNode>) {
        figmaNodes.getOrPut(fileKey) { mutableListOf() }.addAll(nodes)
    }

    override fun insertFigmaVariables(fileKey: String, variables: List<FigmaVariable>) {
        figmaVariables.getOrPut(fileKey) { mutableListOf() }.addAll(variables)
    }

    override fun insertFigmaEdges(fileKey: String, edges: List<FigmaEdge>) {
        figmaEdges.getOrPut(fileKey) { mutableListOf() }.addAll(edges)
    }

    override fun upsertComposeNodesAndParams(
        nodes: List<ComposeNodeRecord>,
        params: List<ComposeParamRecord>,
        preserveHigherProvenance: Boolean
    ) {
        nodes.forEach { node ->
            composeNodes.getOrPut(node.projectSlug) { mutableListOf() }.add(node)
        }
        params.forEach { param ->
            composeParams.getOrPut(param.nodeId) { mutableListOf() }.add(param)
        }
    }

    override fun deleteComposeNodesForFile(projectSlug: String, filePath: String) {
        composeNodes[projectSlug]?.removeAll { it.filePath == filePath }
    }

    override fun upsertComposeTraceMetric(
        nodeId: String,
        sessionId: String,
        recompositionCount: Int,
        skipCount: Int,
        dirtyParamsJson: String,
        recordedAt: Long
    ) {
        composeTraceMetrics[nodeId] = Triple(recompositionCount, skipCount, dirtyParamsJson)
    }

    override fun upsertNodeComplexity(
        nodeId: String,
        cyclomatic: Int,
        cognitive: Int,
        maxNesting: Int,
        score: Double
    ) {
        val node = nodes[nodeId]
        complexityScores[nodeId] = ComplexityScoreRecord(
            nodeId = nodeId,
            projectId = node?.projectId ?: "",
            name = node?.name ?: nodeId,
            qualifiedName = node?.qualifiedName ?: "",
            filePath = node?.fileId ?: "",
            line = node?.startLine ?: 1,
            cyclomatic = cyclomatic,
            cognitive = cognitive,
            maxNesting = maxNesting,
            overallScore = score
        )
    }

    private data class StoreSnapshot(
        val files: Map<String, FileRecord>,
        val nodes: Map<String, GraphNode>,
        val edges: List<GraphEdge>,
        val metadata: Map<Pair<String, String>, String>,
        val annotations: List<NodeAnnotation>,
        val rawEdges: Map<String, List<GraphEdge>>,
        val composeNodes: Map<String, List<ComposeNodeRecord>>,
        val composeParams: Map<String, List<ComposeParamRecord>>,
        val composeTraceMetrics: Map<String, Triple<Int, Int, String>>,
        val complexityScores: Map<String, ComplexityScoreRecord>,
        val figmaDocuments: Map<String, FigmaDocument>,
        val figmaNodes: Map<String, List<FigmaNode>>,
        val figmaVariables: Map<String, List<FigmaVariable>>,
        val figmaEdges: Map<String, List<FigmaEdge>>
    )

    private fun captureSnapshot(): StoreSnapshot = StoreSnapshot(
        files = HashMap(files),
        nodes = HashMap(nodes),
        edges = ArrayList(edges),
        metadata = HashMap(metadata),
        annotations = ArrayList(annotations),
        rawEdges = rawEdges.mapValues { ArrayList(it.value) },
        composeNodes = composeNodes.mapValues { ArrayList(it.value) },
        composeParams = composeParams.mapValues { ArrayList(it.value) },
        composeTraceMetrics = HashMap(composeTraceMetrics),
        complexityScores = HashMap(complexityScores),
        figmaDocuments = HashMap(figmaDocuments),
        figmaNodes = figmaNodes.mapValues { ArrayList(it.value) },
        figmaVariables = figmaVariables.mapValues { ArrayList(it.value) },
        figmaEdges = figmaEdges.mapValues { ArrayList(it.value) }
    )

    private fun restoreSnapshot(s: StoreSnapshot) {
        files.clear(); files.putAll(s.files)
        nodes.clear(); nodes.putAll(s.nodes)
        edges.clear(); edges.addAll(s.edges)
        metadata.clear(); metadata.putAll(s.metadata)
        annotations.clear(); annotations.addAll(s.annotations)
        rawEdges.clear(); s.rawEdges.forEach { (k, v) -> rawEdges[k] = ArrayList(v) }
        composeNodes.clear(); s.composeNodes.forEach { (k, v) -> composeNodes[k] = ArrayList(v) }
        composeParams.clear(); s.composeParams.forEach { (k, v) -> composeParams[k] = ArrayList(v) }
        composeTraceMetrics.clear(); composeTraceMetrics.putAll(s.composeTraceMetrics)
        complexityScores.clear(); complexityScores.putAll(s.complexityScores)
        figmaDocuments.clear(); figmaDocuments.putAll(s.figmaDocuments)
        figmaNodes.clear(); s.figmaNodes.forEach { (k, v) -> figmaNodes[k] = ArrayList(v) }
        figmaVariables.clear(); s.figmaVariables.forEach { (k, v) -> figmaVariables[k] = ArrayList(v) }
        figmaEdges.clear(); s.figmaEdges.forEach { (k, v) -> figmaEdges[k] = ArrayList(v) }
    }

    override fun <T> withImmediateTransaction(block: () -> T): T {
        val snapshot = captureSnapshot()
        return try {
            block()
        } catch (t: Throwable) {
            restoreSnapshot(snapshot)
            throw t
        }
    }

    override fun <T> withSavepoint(name: String, block: () -> T): T {
        val snapshot = captureSnapshot()
        return try {
            block()
        } catch (t: Throwable) {
            restoreSnapshot(snapshot)
            throw t
        }
    }

    override fun close() {}
}
