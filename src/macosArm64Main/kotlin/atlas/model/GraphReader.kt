package atlas.model

import kotlinx.serialization.json.JsonObject

/**
 * Read-only interface for querying knowledge graph nodes, edges, files, and architectural layers.
 */
interface GraphReader {
    fun getNode(projectId: String, target: String): GraphNode?
    fun queryByUrl(projectId: String, target: String): GraphNode?
    fun queryNodesAtLocation(projectId: String, filePath: String, line: Int): List<GraphNode>
    fun getNodesForFile(projectId: String, relPath: String): List<GraphNode>
    fun getNodesForFileIds(projectId: String, fileIds: List<String>): List<GraphNode>
    fun searchNodes(
        projectId: String,
        query: String,
        limit: Int = 15,
        kind: String? = null
    ): List<GraphNode>

    fun getCallers(projectId: String, nodeId: String, depth: Int = 2): List<GraphHop>
    fun getCallees(projectId: String, nodeId: String, depth: Int = 2): List<GraphHop>
    fun getImpactRadius(projectId: String, nodeId: String, depth: Int = 2): ImpactRadiusResult
    fun getGodNodes(projectId: String, topN: Int = 10): List<GraphNode>
    fun resolveNodesByUrlOrRoute(projectId: String, rawUrlOrRoute: String, limit: Int = 25): List<GraphNode>
    fun searchByUrl(projectId: String, rawUrlOrRoute: String, limit: Int = 20): JsonObject
    fun getShortestPath(
        projectId: String,
        sourceNodeId: String,
        targetNodeId: String,
        maxHops: Int = 6
    ): List<String>

    fun sliceLiveSource(
        projectRoot: String,
        projectId: String,
        node: GraphNode
    ): LiveSourceSlice

    fun listFiles(projectId: String): List<FileRecord>
    fun listNodes(projectId: String): List<GraphNode>
    fun listEdges(projectId: String, edgeTypes: List<String>? = null): List<GraphEdge>
    fun getStats(projectId: String): GraphStats

    fun getProjectMetadata(projectId: String, key: String): String?
    fun getNodeAnnotations(projectId: String, nodeId: String): List<NodeAnnotation>
    fun listIndexedFiles(projectId: String): List<String>
    fun getFileRecordsMap(projectId: String): Map<String, Pair<String, Double>>

    fun listComposeNodes(projectSlug: String, onlyViolating: Boolean = false): List<ComposeNodeRecord>
    fun getComposeNode(projectSlug: String, target: String): ComposeNodeRecord?
    fun listComposeParams(nodeId: String): List<ComposeParamRecord>
    fun listComposeParamsForProject(projectSlug: String): Map<String, List<ComposeParamRecord>>
    fun listComposeTraceMetrics(projectSlug: String): Map<String, Triple<Int, Int, String>>
    fun queryComposeCascade(
        projectSlug: String,
        rootNodeId: String,
        maxDepth: Int = 5
    ): List<ComposeQueryResult.CascadeHop>
    fun queryComposeBlame(
        projectSlug: String,
        composableId: String
    ): List<ComposeQueryResult.BlameHop>
    fun countPropertyMutations(
        projectSlug: String,
        targetSymbolOrNodeId: String,
        ownerClassName: String? = null
    ): Int

    fun queryComplexNodes(
        projectId: String,
        minCognitive: Int = 10,
        limit: Int = 50
    ): List<ComplexityScoreRecord>
    fun queryArchitecturalRiskNodes(
        projectId: String,
        minRisk: Double = 0.0,
        limit: Int = 50
    ): List<ComplexityScoreRecord>

    fun getFigmaDocument(fileKey: String): FigmaDocument?
    fun queryFigmaSpatialIntersection(fileKey: String, bounds: FigmaSpatialBounds): List<FigmaNode>
    fun queryFigmaSpatialIntersection(fileKey: String, minX: Double, maxX: Double, minY: Double, maxY: Double): List<FigmaNode>
    fun queryFigmaNodeById(fileKey: String, nodeId: String): FigmaNode?
    fun queryFigmaNodesByName(fileKey: String, name: String): List<FigmaNode>
    fun queryFigmaNodesByType(fileKey: String, nodeType: String): List<FigmaNode>
    fun queryFigmaVariableById(fileKey: String, variableId: String): FigmaVariable?
    fun queryFigmaVariableByName(fileKey: String, name: String): FigmaVariable?
    fun queryFigmaEdgesBySource(fileKey: String, sourceNodeId: String): List<FigmaEdge>
    fun queryFigmaEdgesByTarget(fileKey: String, targetNodeId: String): List<FigmaEdge>
    fun queryFigmaEdgesByType(fileKey: String, edgeType: String): List<FigmaEdge>
    fun resolveVariableAliasImpact(fileKey: String, variableIdOrName: String, depth: Int = 10): List<FigmaVariable>
    fun queryNodesBoundToVariable(fileKey: String, variableId: String): List<FigmaNode>
    fun resolveVariableBlastRadius(fileKey: String, variableIdOrName: String, depth: Int = 10): FigmaVariableImpact
    fun queryFigmaCallers(fileKey: String, componentNameOrId: String, depth: Int = 2): List<FigmaNode>
    fun getLatestFigmaFileKey(): String?
    fun queryFigmaGodNodes(fileKey: String, limit: Int = 10): List<Pair<FigmaNode, Int>>
    fun queryFigmaNodesByIds(fileKey: String, nodeIds: List<String>): List<FigmaNode>
    fun findFigmaShortestPath(fileKey: String, sourceTarget: String, destTarget: String, depth: Int = 10): List<FigmaNode>
    fun countFigmaNodes(fileKey: String): Int
    fun queryFigmaNodesByNameLike(fileKey: String, substring: String): List<FigmaNode>
    fun queryCircularFillInHugViolations(fileKey: String): List<FigmaViolation>
    fun queryStaticContainerFrameViolations(fileKey: String): List<FigmaViolation>
    fun queryDefault100PxFrameHeightViolations(fileKey: String): List<FigmaViolation>
    fun queryMinTouchTargetViolations(fileKey: String): List<FigmaViolation>
    fun queryNegativeCoordinateViolations(fileKey: String): List<FigmaViolation>
    fun queryChildAmputationWidthViolations(fileKey: String): List<FigmaViolation>
    fun queryNoEmojiViolations(fileKey: String): List<FigmaViolation>
    fun queryUnboundTokenViolations(fileKey: String): List<FigmaViolation>
    fun queryAdHocDetachedSpecimenViolations(fileKey: String): List<FigmaViolation>
    fun queryCanvasSpatialTopologyViolations(fileKey: String): List<FigmaViolation>
    fun queryWcagContrastViolations(fileKey: String): List<FigmaViolation>
}
