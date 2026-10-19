package atlas.config

import atlas.util.PlatformFiles
import atlas.util.Sha256

/**
 * Dual-profile cache path resolver for Atlas.
 * Resolves SQLite DB and artifacts directories using Enterprise or In-Repo cache profiles.
 */
object CacheProfileResolver {

    const val ENTERPRISE_PROFILE = "enterprise"
    const val IN_REPO_PROFILE = "in-repo"

    fun computeRepoHash(workspaceRoot: String): String {
        val canonical = PlatformFiles.canonicalPath(workspaceRoot).replace('\\', '/').trimEnd('/')
        return Sha256.digest(canonical).take(16)
    }

    fun getEnterpriseCacheDir(workspaceRoot: String): String {
        val repoHash = computeRepoHash(workspaceRoot)
        val home = PlatformFiles.userHome()
        // macOS default
        return "$home/Library/Caches/atlas/$repoHash"
    }

    fun resolvePaths(
        workspaceRoot: String,
        customDb: String? = null,
        profile: String? = null
    ): Pair<String, String> {
        val canonicalWorkspace = PlatformFiles.canonicalPath(workspaceRoot).replace('\\', '/').trimEnd('/')

        if (customDb != null) {
            val resolvedDb = PlatformFiles.canonicalPath(customDb).replace('\\', '/')
            val parent = PlatformFiles.parentOf(resolvedDb)
            val artifacts = "$parent/artifacts"
            PlatformFiles.mkdirs(artifacts)
            return Pair(resolvedDb, artifacts)
        }

        val envProfile = PlatformFiles.getenv("ATLAS_CACHE_PROFILE")?.lowercase()?.trim() ?: ""
        val selectedProfile = (profile ?: if (envProfile.isNotEmpty()) envProfile else ENTERPRISE_PROFILE).lowercase().trim()

        val inRepoDir = "$canonicalWorkspace/.atlas"
        val inRepoDb = "$inRepoDir/atlas.db"

        if (selectedProfile == IN_REPO_PROFILE || (PlatformFiles.exists(inRepoDb) && profile == null && envProfile.isEmpty())) {
            PlatformFiles.mkdirs(inRepoDir)
            val artifacts = "$inRepoDir/artifacts"
            PlatformFiles.mkdirs(artifacts)
            return Pair(inRepoDb, artifacts)
        }

        // Enterprise profile: keep working tree 100% clean by storing in user cache
        val cacheBase = getEnterpriseCacheDir(canonicalWorkspace)
        val success = PlatformFiles.mkdirs(cacheBase)
        if (success) {
            val artifacts = "$cacheBase/artifacts"
            PlatformFiles.mkdirs(artifacts)
            return Pair("$cacheBase/atlas.db", artifacts)
        }

        // Graceful fallback to in-repo cache if user cache directory is not writable
        PlatformFiles.mkdirs(inRepoDir)
        val fallbackArtifacts = "$inRepoDir/artifacts"
        PlatformFiles.mkdirs(fallbackArtifacts)
        return Pair(inRepoDb, fallbackArtifacts)
    }

    /**
     * Resolves an existing enterprise cache directory or Development workspace root for a given projectId
     * when the MCP server is started from an unindexed working directory (e.g., `/`).
     */
    fun resolveProjectWorkspace(defaultWorkspace: String, projectId: String): String {
        if (projectId.isEmpty()) return defaultWorkspace
        val home = PlatformFiles.userHome()
        val normalizedTarget = projectId.lowercase().replace("_", "-").trim()

        if (normalizedTarget == "agent-os" || normalizedTarget == "agent" || normalizedTarget == "agents") {
            val agentConfig = "$home/.gemini/config"
            if (PlatformFiles.isDirectory(agentConfig)) return agentConfig
        }

        if (normalizedTarget == "vault") {
            val candidates = listOf(
                "$home/vault",
                "$home/Documents/vault",
                "$home/Obsidian/vault",
                "$home/Documents/Obsidian Vault"
            )
            val found = candidates.firstOrNull { PlatformFiles.isDirectory(it) }
            if (found != null) return found
        }

        val searchRoots = listOf(
            "$home/StudioProjects",
            "$home/Development",
            "$home/Projects",
            "$home/code"
        )
        for (root in searchRoots) {
            if (!PlatformFiles.isDirectory(root)) continue
            val entries = PlatformFiles.listDirectory(root)
            val matchedEntry = entries.firstOrNull { entry ->
                entry.lowercase().replace("_", "-") == normalizedTarget
            }
            if (matchedEntry != null) {
                val candidateDir = "$root/$matchedEntry"
                if (PlatformFiles.isDirectory(candidateDir)) return candidateDir
            }
        }
        return defaultWorkspace
    }
}

