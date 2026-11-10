package atlas.watch

import atlas.AtlasClient
import atlas.model.GraphNode
import atlas.model.GraphStats
import atlas.util.PlatformFiles

/**
 * Reactive file watching and incremental delta notification session for Code Atlas.
 * Tracks workspace mutations, triggers incremental reindexing, and emits structured [GraphDelta] events.
 */
class AtlasWatchSession(
    val client: AtlasClient
) {

    /**
     * Represents a discrete set of changes applied to the knowledge graph during reindexing.
     */
    data class GraphDelta(
        val changedFiles: List<String>,
        val addedNodes: List<GraphNode>,
        val removedNodeIds: List<String>,
        val updatedStats: GraphStats,
        val hasChanges: Boolean
    )

    private val listeners = mutableListOf<(GraphDelta) -> Unit>()

    companion object {
        private val SKIP_DIRS = setOf(
            ".git", ".gradle", ".idea", ".venv", "venv", "node_modules",
            "build", "dist", "__pycache__", ".pytest_cache", ".artifacts",
            ".agents", ".agent", ".atlas"
        )
        private val SUPPORTED_EXTS = setOf(
            "kt", "kts", "swift", "py", "ts", "tsx", "js", "jsx",
            "rs", "cpp", "cc", "cxx", "hpp", "h", "c", "m", "mm", "md"
        )
    }

    /**
     * Register a callback listener to receive [GraphDelta] events whenever graph mutations occur.
     * Returns an unregister closure for clean resource lifecycle management.
     */
    fun registerListener(listener: (GraphDelta) -> Unit): () -> Unit {
        listeners.add(listener)
        return {
            listeners.remove(listener)
        }
    }

    /**
     * Inspect the workspace filesystem against cached graph file records.
     * If changes are detected, triggers incremental indexing, computes the delta,
     * dispatches to listeners, and returns the [GraphDelta]. Returns null if no changes occurred.
     */
    fun triggerCheck(): GraphDelta? {
        val delta = pollOnce()
        if (delta != null) {
            for (listener in listeners) {
                try {
                    listener(delta)
                } catch (t: Throwable) {
                    PlatformFiles.logWarning("AtlasWatchSession", "Listener error: ${t.message}")
                }
            }
        }
        return delta
    }

    /**
     * Perform a single inspection cycle without dispatching to registered listeners.
     */
    fun pollOnce(): GraphDelta? {
        val root = PlatformFiles.canonicalPath(client.workspacePath)
        val cachedFiles = client.store.getFileRecordsMap(client.projectSlug)

        val diskFiles = discoverSourceFiles(root)
        val changedFiles = mutableListOf<String>()

        // 1. Check for modified or new files
        for (diskFile in diskFiles) {
            val relPath = diskFile.removePrefix(root).removePrefix("/").replace('\\', '/')
            val cachedMeta = cachedFiles[relPath]
            if (cachedMeta == null) {
                changedFiles.add(relPath)
            } else {
                val currentMtime = PlatformFiles.mtime(diskFile)
                if (currentMtime > cachedMeta.second) {
                    changedFiles.add(relPath)
                }
            }
        }

        // 2. Check for deleted files
        for (relPath in cachedFiles.keys) {
            if (relPath == "__endpoints__" || relPath.startsWith("__")) continue
            val fullPath = "$root/$relPath"
            if (!PlatformFiles.exists(fullPath)) {
                changedFiles.add(relPath)
            }
        }

        if (changedFiles.isEmpty()) {
            return null
        }

        // 3. Compute delta between pre-index and post-index graph state
        val beforeNodes = client.store.listNodes(client.projectSlug).associateBy { it.nodeId }

        client.index(incremental = true)

        val afterNodes = client.store.listNodes(client.projectSlug)
        val afterNodeMap = afterNodes.associateBy { it.nodeId }

        val addedNodes = afterNodes.filter { it.nodeId !in beforeNodes }
        val removedNodeIds = beforeNodes.keys.filter { it !in afterNodeMap }
        val updatedStats = client.stats()

        return GraphDelta(
            changedFiles = changedFiles.distinct(),
            addedNodes = addedNodes,
            removedNodeIds = removedNodeIds,
            updatedStats = updatedStats,
            hasChanges = true
        )
    }

    private fun discoverSourceFiles(root: String): List<String> {
        val allFiles = PlatformFiles.listFilesRecursively(root) { dirName ->
            dirName in SKIP_DIRS || dirName.startsWith(".venv")
        }
        return allFiles.filter { filePath ->
            val ext = filePath.substringAfterLast('.', "")
            ext in SUPPORTED_EXTS
        }
    }
}
