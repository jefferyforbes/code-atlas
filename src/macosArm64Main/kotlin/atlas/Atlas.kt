package atlas

import atlas.config.CacheProfileResolver
import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectureAuditor
import atlas.engine.SpikeAnalysisEngine
import atlas.engine.WorkspaceIndexer
import atlas.model.AuditReport
import atlas.model.GraphHop
import atlas.model.GraphNode
import atlas.model.GraphStats
import atlas.model.GraphStore
import atlas.model.ImpactRadiusResult
import atlas.model.SpikeAnalysisResult
import atlas.model.SpikeRequest
import atlas.util.PlatformFiles
import kotlinx.serialization.json.JsonObject
import platform.posix.getpid
import kotlin.random.Random

/**
 * Primary library factory and entry point for Code Atlas.
 * Provides fluent instantiation of in-memory, persistent, and ephemeral [AtlasClient] instances.
 */
object Atlas {

    /**
     * Open an in-memory Atlas instance backed by SQLite in-memory mode.
     * Fastest startup, zero disk footprint, ideal for unit tests and stateless AST analysis.
     */
    fun inMemory(
        projectSlug: String = "in-memory",
        workspacePath: String = "."
    ): AtlasClient {
        val store = SqliteGraphStore(":memory:")
        return DefaultAtlasClient(
            projectSlug = projectSlug,
            workspacePath = workspacePath,
            store = store
        )
    }

    /**
     * Open a persistent Atlas instance backed by a disk SQLite database.
     * If [dbPath] is omitted, uses the standard CacheProfileResolver paths.
     */
    fun open(
        projectSlug: String,
        workspacePath: String = ".",
        dbPath: String? = null,
        cacheProfile: String? = null
    ): AtlasClient {
        val resolvedDb = if (!dbPath.isNullOrBlank()) {
            dbPath
        } else {
            val (resolved, _) = CacheProfileResolver.resolvePaths(
                workspaceRoot = workspacePath,
                customDb = null,
                profile = cacheProfile
            )
            resolved
        }
        val store = SqliteGraphStore(resolvedDb)
        return DefaultAtlasClient(
            projectSlug = projectSlug,
            workspacePath = workspacePath,
            store = store
        )
    }

    /**
     * Open an ephemeral Atlas instance backed by a temporary SQLite database on disk.
     * The database file and any associated WAL/SHM files are automatically deleted upon [AtlasClient.close].
     */
    fun ephemeral(
        projectSlug: String = "ephemeral",
        workspacePath: String = "."
    ): AtlasClient {
        val pid = getpid()
        val rnd = Random.nextInt(100000, 999999)
        val tempDbPath = "/tmp/atlas_ephemeral_${pid}_${rnd}.db"
        val store = SqliteGraphStore(tempDbPath)
        return DefaultAtlasClient(
            projectSlug = projectSlug,
            workspacePath = workspacePath,
            store = store,
            cleanup = {
                try {
                    store.close()
                } catch (_: Throwable) {}
                if (!PlatformFiles.delete(tempDbPath) && PlatformFiles.exists(tempDbPath)) {
                    PlatformFiles.logWarning("Atlas", "Failed to delete ephemeral database: $tempDbPath")
                }
                val wal = "$tempDbPath-wal"
                if (!PlatformFiles.delete(wal) && PlatformFiles.exists(wal)) {
                    PlatformFiles.logWarning("Atlas", "Failed to delete ephemeral WAL: $wal")
                }
                val shm = "$tempDbPath-shm"
                if (!PlatformFiles.delete(shm) && PlatformFiles.exists(shm)) {
                    PlatformFiles.logWarning("Atlas", "Failed to delete ephemeral SHM: $shm")
                }
            }
        )
    }

    /**
     * Create an AtlasClient wrapping an existing [GraphStore] instance.
     */
    fun fromStore(
        projectSlug: String,
        workspacePath: String = ".",
        store: GraphStore,
        cleanup: (() -> Unit)? = null
    ): AtlasClient = DefaultAtlasClient(
        projectSlug = projectSlug,
        workspacePath = workspacePath,
        store = store,
        cleanup = cleanup
    )
}

private class DefaultAtlasClient(
    override val projectSlug: String,
    override val workspacePath: String,
    override val store: GraphStore,
    private val cleanup: (() -> Unit)? = null
) : AtlasClient {

    private var closed = false

    private fun checkNotClosed() {
        check(!closed) { "AtlasClient for '$projectSlug' has been closed" }
    }

    override fun index(incremental: Boolean): JsonObject {
        checkNotClosed()
        val indexer = WorkspaceIndexer(workspacePath, store)
        return indexer.indexWorkspace(projectId = projectSlug, force = !incremental)
    }

    override fun getNode(target: String): GraphNode? {
        checkNotClosed()
        return store.getNode(projectSlug, target)
    }

    override fun callers(target: String, depth: Int): List<GraphHop> {
        checkNotClosed()
        val resolvedId = store.getNode(projectSlug, target)?.nodeId ?: target
        return store.getCallers(projectSlug, resolvedId, depth)
    }

    override fun callees(target: String, depth: Int): List<GraphHop> {
        checkNotClosed()
        val resolvedId = store.getNode(projectSlug, target)?.nodeId ?: target
        return store.getCallees(projectSlug, resolvedId, depth)
    }

    override fun impactRadius(target: String, depth: Int): ImpactRadiusResult {
        checkNotClosed()
        val resolvedId = store.getNode(projectSlug, target)?.nodeId ?: target
        return store.getImpactRadius(projectSlug, resolvedId, depth)
    }

    override fun shortestPath(sourceNodeId: String, targetNodeId: String, maxHops: Int): List<String> {
        checkNotClosed()
        val src = store.getNode(projectSlug, sourceNodeId)?.nodeId ?: sourceNodeId
        val tgt = store.getNode(projectSlug, targetNodeId)?.nodeId ?: targetNodeId
        return store.getShortestPath(projectSlug, src, tgt, maxHops)
    }

    override fun searchNodes(query: String, limit: Int, kind: String?): List<GraphNode> {
        checkNotClosed()
        return store.searchNodes(projectSlug, query, limit, kind)
    }

    override fun stats(): GraphStats {
        checkNotClosed()
        return store.getStats(projectSlug)
    }

    override fun auditArchitecture(): AuditReport {
        checkNotClosed()
        val auditor = ArchitectureAuditor(store, workspacePath)
        return auditor.runFullAudit(projectSlug)
    }

    override fun runSpike(request: SpikeRequest): SpikeAnalysisResult {
        checkNotClosed()
        val engine = SpikeAnalysisEngine(store)
        return engine.runSpike(request)
    }

    override fun close() {
        if (!closed) {
            closed = true
            try {
                cleanup?.invoke() ?: store.close()
            } catch (t: Throwable) {
                PlatformFiles.logWarning("DefaultAtlasClient", "Error during close: ${t.message}")
            }
        }
    }
}
