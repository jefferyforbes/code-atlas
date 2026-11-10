package atlas.integration

import atlas.extract.FigmaSnapshotExtractor
import atlas.model.FigmaDocument
import atlas.model.FigmaEdge
import atlas.model.FigmaNode
import atlas.model.FigmaVariable

/**
 * Stateful in-memory fake implementing StoreSink for hermetic test seams.
 * Tracks calls and mutations to verify negative side effects and rollback invariants.
 */
class FakeStoreSink : FigmaSnapshotExtractor.StoreSink {

    val documents = mutableMapOf<String, FigmaDocument>()
    val nodes = mutableMapOf<String, MutableList<FigmaNode>>()
    val variables = mutableMapOf<String, MutableList<FigmaVariable>>()
    val edges = mutableMapOf<String, MutableList<FigmaEdge>>()

    var callCount: Int = 0
        private set

    var deleteDocumentCallCount: Int = 0
        private set

    var insertDocumentCallCount: Int = 0
        private set

    var insertNodesCallCount: Int = 0
        private set

    var insertVariablesCallCount: Int = 0
        private set

    var insertEdgesCallCount: Int = 0
        private set

    var shouldFail: Boolean = false

    override fun deleteFigmaDocument(fileKey: String) {
        checkRollbackFailure()
        callCount++
        deleteDocumentCallCount++
        documents.remove(fileKey)
        nodes.remove(fileKey)
        variables.remove(fileKey)
        edges.remove(fileKey)
    }

    override fun insertFigmaDocument(document: FigmaDocument) {
        checkRollbackFailure()
        callCount++
        insertDocumentCallCount++
        documents[document.fileKey] = document
    }

    override fun insertFigmaNodes(fileKey: String, nodes: List<FigmaNode>) {
        checkRollbackFailure()
        callCount++
        insertNodesCallCount++
        this.nodes.getOrPut(fileKey) { mutableListOf() }.addAll(nodes)
    }

    override fun insertFigmaVariables(fileKey: String, variables: List<FigmaVariable>) {
        checkRollbackFailure()
        callCount++
        insertVariablesCallCount++
        this.variables.getOrPut(fileKey) { mutableListOf() }.addAll(variables)
    }

    override fun insertFigmaEdges(fileKey: String, edges: List<FigmaEdge>) {
        checkRollbackFailure()
        callCount++
        insertEdgesCallCount++
        this.edges.getOrPut(fileKey) { mutableListOf() }.addAll(edges)
    }

    fun reset() {
        documents.clear()
        nodes.clear()
        variables.clear()
        edges.clear()
        callCount = 0
        deleteDocumentCallCount = 0
        insertDocumentCallCount = 0
        insertNodesCallCount = 0
        insertVariablesCallCount = 0
        insertEdgesCallCount = 0
        shouldFail = false
    }

    private fun checkRollbackFailure() {
        if (shouldFail) {
            throw IllegalStateException("FakeStoreSink forced failure for state rollback testing")
        }
    }
}
