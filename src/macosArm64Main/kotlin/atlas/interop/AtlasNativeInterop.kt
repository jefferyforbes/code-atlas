package atlas.interop

import atlas.Atlas
import atlas.AtlasClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Foreign language and native interop gateway for Code Atlas.
 * Provides programmatic access and lifecycle management for Swift, Objective-C, and native C callers.
 */
object AtlasNativeInterop {

    const val VERSION = "0.8.0"

    private val json = Json { prettyPrint = false }

    /**
     * Return Code Atlas version string.
     */
    fun version(): String = VERSION

    /**
     * Open an in-memory AtlasClient instance.
     */
    fun openInMemory(projectSlug: String = "in-memory"): AtlasClient {
        return Atlas.inMemory(projectSlug = projectSlug)
    }

    /**
     * Open a persistent AtlasClient instance backed by SQLite.
     */
    fun openPersistent(
        projectSlug: String,
        workspacePath: String = ".",
        dbPath: String? = null
    ): AtlasClient {
        return Atlas.open(
            projectSlug = projectSlug,
            workspacePath = workspacePath,
            dbPath = dbPath
        )
    }

    /**
     * Open an ephemeral AtlasClient instance cleaned up on close.
     */
    fun openEphemeral(
        projectSlug: String = "ephemeral",
        workspacePath: String = "."
    ): AtlasClient {
        return Atlas.ephemeral(
            projectSlug = projectSlug,
            workspacePath = workspacePath
        )
    }

    /**
     * Close an active AtlasClient instance and release its resources.
     */
    fun closeClient(client: AtlasClient) {
        client.close()
    }

    /**
     * Query node metadata and return a compact JSON payload for foreign runtimes.
     */
    fun queryNodeJson(client: AtlasClient, target: String): String? {
        val node = client.getNode(target) ?: return null
        val obj = buildJsonObject {
            put("node_id", node.nodeId)
            put("name", node.name)
            put("kind", node.kind)
            put("file_id", node.fileId)
            put("module_id", node.moduleId)
            put("arch_layer", node.archLayer)
            put("in_degree", node.inDegree)
            put("out_degree", node.outDegree)
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }

    /**
     * Query stats as a JSON payload for foreign runtimes.
     */
    fun statsJson(client: AtlasClient): String {
        val stats = client.stats()
        val obj = buildJsonObject {
            put("project_id", stats.projectId)
            put("nodes", stats.nodes)
            put("edges", stats.edges)
            put("files", stats.files)
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }
}
