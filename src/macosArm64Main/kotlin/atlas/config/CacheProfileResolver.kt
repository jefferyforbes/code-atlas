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

    private fun ensureCacheDir(path: String): Boolean {
        val created = PlatformFiles.mkdirs(path)
        if (!created) {
            PlatformFiles.logWarning("CacheProfileResolver", "Failed to create directory: $path")
        }
        return created
    }

    private fun resolveInRepoPaths(inRepoDir: String, inRepoDb: String): Pair<String, String> {
        ensureCacheDir(inRepoDir)
        val artifacts = "$inRepoDir/artifacts"
        ensureCacheDir(artifacts)
        return Pair(inRepoDb, artifacts)
    }

    private fun shouldUseInRepoCache(selectedProfile: String, inRepoDb: String, profile: String?, envProfile: String): Boolean {
        if (selectedProfile == IN_REPO_PROFILE) return true
        return PlatformFiles.exists(inRepoDb) && profile == null && envProfile.isEmpty()
    }

    fun resolvePaths(
        workspaceRoot: String,
        customDb: String? = null,
        profile: String? = null
    ): Pair<String, String> {
        val canonicalWorkspace = PlatformFiles.canonicalPath(workspaceRoot).replace('\\', '/').trimEnd('/')

        if (customDb != null) {
            val resolvedDb = PlatformFiles.canonicalPath(customDb).replace('\\', '/')
            val artifacts = "${PlatformFiles.parentOf(resolvedDb)}/artifacts"
            ensureCacheDir(artifacts)
            return Pair(resolvedDb, artifacts)
        }

        val envProfile = PlatformFiles.getenv("ATLAS_CACHE_PROFILE")?.lowercase()?.trim() ?: ""
        val selectedProfile = (profile ?: envProfile.ifEmpty { ENTERPRISE_PROFILE }).lowercase().trim()
        val inRepoDir = "$canonicalWorkspace/.atlas"
        val inRepoDb = "$inRepoDir/atlas.db"

        if (shouldUseInRepoCache(selectedProfile, inRepoDb, profile, envProfile)) {
            return resolveInRepoPaths(inRepoDir, inRepoDb)
        }

        val cacheBase = getEnterpriseCacheDir(canonicalWorkspace)
        if (PlatformFiles.mkdirs(cacheBase)) {
            val artifacts = "$cacheBase/artifacts"
            ensureCacheDir(artifacts)
            return Pair("$cacheBase/atlas.db", artifacts)
        }

        return resolveInRepoPaths(inRepoDir, inRepoDb)
    }

    /**
     * Resolves an existing enterprise cache directory or Development workspace root for a given projectId
     * when the MCP server is started from an unindexed working directory (e.g., `/`).
     */
    fun resolveProjectWorkspace(defaultWorkspace: String, projectId: String): String {
        if (projectId.isEmpty()) return defaultWorkspace
        val home = PlatformFiles.userHome()
        val normalizedTarget = projectId.lowercase().replace("_", "-").trim()

        val special = resolveSpecialWorkspace(normalizedTarget, home)
        if (special != null) return special

        return findWorkspaceInSearchRoots(normalizedTarget, home) ?: defaultWorkspace
    }

    private fun resolveSpecialWorkspace(target: String, home: String): String? {
        if (target == "agent-os" || target == "agent" || target == "agents") {
            val agentConfig = "$home/.gemini/config"
            if (PlatformFiles.isDirectory(agentConfig)) return agentConfig
        }
        if (target == "vault") {
            val candidates = listOf(
                "$home/vault",
                "$home/Documents/vault",
                "$home/Obsidian/vault",
                "$home/Documents/Obsidian Vault"
            )
            return candidates.firstOrNull { PlatformFiles.isDirectory(it) }
        }
        return null
    }

    private fun findWorkspaceInSearchRoots(target: String, home: String): String? {
        val searchRoots = listOf(
            "$home/StudioProjects",
            "$home/Development",
            "$home/Projects",
            "$home/code"
        )
        for (root in searchRoots) {
            val found = matchProjectInDirectory(root, target)
            if (found != null) return found
        }
        return null
    }

    private fun matchProjectInDirectory(root: String, target: String): String? {
        if (!PlatformFiles.isDirectory(root)) return null
        val entries = PlatformFiles.listDirectory(root)
        val matchedEntry = entries.firstOrNull { it.lowercase().replace("_", "-") == target } ?: return null
        val candidateDir = "$root/$matchedEntry"
        return if (PlatformFiles.isDirectory(candidateDir)) candidateDir else null
    }
}

