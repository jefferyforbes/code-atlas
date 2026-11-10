package atlas.model

import atlas.extract.FigmaSnapshotExtractor

/**
 * Read-write knowledge graph store interface supporting transactional indexing, mutations, and optimizations.
 */
interface GraphStore : GraphReader, FigmaSnapshotExtractor.StoreSink {
    fun setProjectMetadata(projectId: String, key: String, value: String)
    fun insertNodeAnnotation(
        annotationId: String,
        projectId: String,
        nodeId: String,
        outcome: String,
        note: String,
        createdAtEpoch: Double
    )

    fun upsertFileRecord(record: FileRecordUpsert): String
    fun deleteFileRecord(projectId: String, relPath: String, recomputeDegrees: Boolean = true)
    fun upsertNodesAndEdges(
        projectId: String,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>
    )

    fun replaceBatchRawSymbolEdges(projectId: String, fileEdgesMap: Map<String, List<GraphEdge>>)
    fun replaceFileRawSymbolEdges(projectId: String, fileId: String, edges: List<GraphEdge>)
    fun deleteFileRawSymbolEdges(projectId: String, fileId: String)
    fun deleteOutgoingEdgesForFile(projectId: String, relPath: String)
    fun filesWithRawSymbolEdges(projectId: String): Set<String>
    fun listRawSymbolEdges(projectId: String): List<GraphEdge>
    fun getDeclaredSymbolsForFile(projectId: String, fileId: String): List<String>
    fun listRawSymbolEdgesTargetingSymbols(projectId: String, symbols: Set<String>): List<GraphEdge>
    fun deleteEdgesForRawEdges(projectId: String, rawEdges: List<GraphEdge>)
    fun deleteAllResolvedCodeEdges(projectId: String)

    fun ensureVirtualFileRecord(projectId: String, virtualRelPath: String = "__endpoints__"): String
    fun recomputeDegrees(projectId: String)
    fun rebuildVocabulary(projectId: String)

    fun upsertComposeNodesAndParams(
        nodes: List<ComposeNodeRecord>,
        params: List<ComposeParamRecord>,
        preserveHigherProvenance: Boolean = true
    )
    fun deleteComposeNodesForFile(projectSlug: String, filePath: String)
    fun upsertComposeTraceMetric(
        nodeId: String,
        sessionId: String,
        recompositionCount: Int,
        skipCount: Int,
        dirtyParamsJson: String,
        recordedAt: Long
    )
    fun upsertNodeComplexity(
        nodeId: String,
        cyclomatic: Int,
        cognitive: Int,
        maxNesting: Int,
        score: Double
    )

    fun <T> withImmediateTransaction(block: () -> T): T
    fun <T> withSavepoint(name: String, block: () -> T): T
    fun close()
}
