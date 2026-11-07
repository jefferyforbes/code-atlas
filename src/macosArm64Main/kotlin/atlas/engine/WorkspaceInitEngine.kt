package atlas.engine

import atlas.util.PlatformFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Installs and manages Git pre-commit and pre-push quality gate hooks for Atlas workspaces.
 */
class WorkspaceInitEngine(val workspaceRoot: String) {

    data class InitResult(
        val success: Boolean,
        val hookPath: String,
        val hookType: String,
        val message: String,
        val overwritten: Boolean = false,
        val dryRun: Boolean = false,
        val scriptContent: String? = null
    )

    private fun readProjectFromBaseline(path: String): String? {
        if (!PlatformFiles.exists(path)) return null
        return try {
            val content = PlatformFiles.readText(path)
            val json = Json { ignoreUnknownKeys = true }
            val parsed = json.parseToJsonElement(content).jsonObject
            parsed["project"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun readBaselineProject(root: String): String? {
        val f1 = "$root/complexity-baseline.json"
        val f2 = "$root/.atlas/complexity-baseline.json"
        return readProjectFromBaseline(f1) ?: readProjectFromBaseline(f2)
    }

    private fun deriveDirectorySlug(root: String): String {
        val dirName = root.substringAfterLast('/')
        return if (dirName.isNotBlank() && dirName != ".") dirName else "organisation"
    }

    fun resolveProjectSlug(explicitProject: String? = null): String {
        if (!explicitProject.isNullOrBlank() && explicitProject != "organisation") {
            return explicitProject
        }
        val canonical = PlatformFiles.canonicalPath(workspaceRoot).trimEnd('/')
        return readBaselineProject(canonical) ?: deriveDirectorySlug(canonical)
    }

    fun initHook(
        project: String = "organisation",
        hookType: String = "pre-commit",
        force: Boolean = false,
        threshold: Int = 15,
        dryRun: Boolean = false
    ): InitResult {
        val root = workspaceRoot.trimEnd('/')
        val gitDir = "$root/.git"
        if (!PlatformFiles.exists(gitDir) || !PlatformFiles.isDirectory(gitDir)) {
            return InitResult(
                success = false,
                hookPath = "",
                hookType = hookType,
                message = "Workspace root '$root' is not a git repository (missing .git directory)."
            )
        }

        val validHookTypes = setOf("pre-commit", "pre-push")
        if (hookType !in validHookTypes) {
            return InitResult(
                success = false,
                hookPath = "",
                hookType = hookType,
                message = "Unsupported hook type '$hookType'. Supported hook types: ${validHookTypes.joinToString(", ")}"
            )
        }

        val hooksDir = "$gitDir/hooks"
        val hookPath = "$hooksDir/$hookType"
        val effectiveProject = resolveProjectSlug(project)
        val scriptContent = generateHookScript(project = effectiveProject, hookType = hookType, threshold = threshold)

        if (dryRun) {
            return InitResult(
                success = true,
                hookPath = hookPath,
                hookType = hookType,
                message = "Dry-run: Hook script preview for $hookType at $hookPath.",
                overwritten = false,
                dryRun = true,
                scriptContent = scriptContent
            )
        }

        val exists = PlatformFiles.exists(hookPath)
        if (exists && !force) {
            return InitResult(
                success = false,
                hookPath = hookPath,
                hookType = hookType,
                message = "Git hook already exists at '$hookPath'. Use --force to overwrite."
            )
        }

        val dirCreated = PlatformFiles.mkdirs(hooksDir)
        if (!dirCreated) {
            PlatformFiles.logWarning("WorkspaceInitEngine", "Failed to create hooks directory: $hooksDir")
        }
        val written = PlatformFiles.writeText(hookPath, scriptContent)
        if (!written) {
            return InitResult(
                success = false,
                hookPath = hookPath,
                hookType = hookType,
                message = "Failed to write hook script to '$hookPath'."
            )
        }

        // 0755 permissions: S_IRWXU | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH = 493u
        val chmodOk = PlatformFiles.chmod(hookPath, 493u)
        if (!chmodOk) {
            return InitResult(
                success = false,
                hookPath = hookPath,
                hookType = hookType,
                message = "Failed to set executable permissions on '$hookPath'."
            )
        }

        return InitResult(
            success = true,
            hookPath = hookPath,
            hookType = hookType,
            message = if (exists) "Overwrote and installed $hookType hook at $hookPath." else "Installed $hookType hook at $hookPath.",
            overwritten = exists,
            dryRun = false,
            scriptContent = scriptContent
        )
    }

    fun generateHookScript(project: String, hookType: String, threshold: Int): String {
        require(threshold > 0) { "Threshold must be positive: $threshold" }
        val quotedProject = "'${escapeSingleQuotes(project)}'"
        val triggerCondition = if (hookType == "pre-push") {
            """
            |# In pre-push, verify all modified tracked files in the repo against origin
            |STAGED_FILES=${'$'}(git diff --name-only "@{push}..HEAD" 2>/dev/null | grep -E '\.(kt|kts|ts|tsx|js|jsx|swift|py|rs|cpp|c|h|hpp)$' || true)
            |if [ -z "${'$'}STAGED_FILES" ]; then
            |    STAGED_FILES=${'$'}(git diff --name-only "origin/main..HEAD" 2>/dev/null | grep -E '\.(kt|kts|ts|tsx|js|jsx|swift|py|rs|cpp|c|h|hpp)$' || true)
            |fi
            |if [ -z "${'$'}STAGED_FILES" ]; then
            |    exit 0
            |fi
            """.trimMargin("|")
        } else {
            """
            |# Check for staged code files
            |STAGED_FILES=${'$'}(git diff --cached --name-only --diff-filter=ACMR | grep -E '\.(kt|kts|ts|tsx|js|jsx|swift|py|rs|cpp|c|h|hpp)$' || true)
            |if [ -z "${'$'}STAGED_FILES" ]; then
            |    exit 0
            |fi
            """.trimMargin("|")
        }

        return """
            |#!/bin/sh
            |# Atlas $hookType Hook: Automated Architectural, Single Declaration & Complexity Guard
            |# Generated by atlas init
            |
            |set -e
            |
            |# 1. Resolve atlas binary (PATH, ./dist/atlas, ~/.local/bin/atlas, /opt/homebrew/bin/atlas, /usr/local/bin/atlas)
            |ATLAS_BIN=""
            |if command -v atlas >/dev/null 2>&1; then
            |    ATLAS_BIN="atlas"
            |elif [ -x "./dist/atlas" ]; then
            |    ATLAS_BIN="./dist/atlas"
            |elif [ -x "${'$'}HOME/.local/bin/atlas" ]; then
            |    ATLAS_BIN="${'$'}HOME/.local/bin/atlas"
            |elif [ -x "/opt/homebrew/bin/atlas" ]; then
            |    ATLAS_BIN="/opt/homebrew/bin/atlas"
            |elif [ -x "/usr/local/bin/atlas" ]; then
            |    ATLAS_BIN="/usr/local/bin/atlas"
            |fi
            |
            |if [ -z "${'$'}ATLAS_BIN" ]; then
            |    echo "[Atlas Hook] Warning: 'atlas' binary not found in PATH or standard install locations."
            |    echo "[Atlas Hook] Please install atlas or run './gradlew installToDist' to enable automated checks."
            |    exit 0
            |fi
            |
            |# 2. Check for relevant files
            |$triggerCondition
            |
            |echo "[Atlas Hook] Verifying architectural invariants & complexity on changed files..."
            |
            |# 3. Incrementally index workspace
            |"${'$'}ATLAS_BIN" index --workspace . --project $quotedProject >/dev/null 2>&1
            |
            |# 4. Run architectural & complexity audit
            |set +e
            |AUDIT_OUTPUT=${'$'}("${'$'}ATLAS_BIN" audit --workspace . --project $quotedProject 2>&1)
            |AUDIT_EXIT=${'$'}?
            |if [ ${'$'}AUDIT_EXIT -eq 0 ]; then
            |    COMPLEXITY_OUTPUT=${'$'}("${'$'}ATLAS_BIN" audit --workspace . --project $quotedProject --complexity --threshold $threshold 2>&1)
            |    AUDIT_EXIT=${'$'}?
            |    if [ ${'$'}AUDIT_EXIT -ne 0 ]; then
            |        AUDIT_OUTPUT="${'$'}COMPLEXITY_OUTPUT"
            |    fi
            |fi
            |set -e
            |
            |if [ ${'$'}AUDIT_EXIT -ne 0 ]; then
            |    echo ""
            |    echo "================================================================================"
            |    echo "[Atlas Hook] Commit rejected: Architectural or complexity invariants violated!"
            |    echo "================================================================================"
            |    echo "${'$'}AUDIT_OUTPUT"
            |    echo ""
            |    echo "Run '${'$'}ATLAS_BIN audit --project $quotedProject' for detailed diagnostics."
            |    echo "Run '${'$'}ATLAS_BIN fix' to remediate BOOST-003 or layer inversions."
            |    echo "Run '${'$'}ATLAS_BIN doctor --complexity' to synthesize complexity reduction guards."
            |    echo "================================================================================"
            |    exit 1
            |fi
            |
            |echo "[Atlas Hook] Invariants verified. Check passed."
            |exit 0
        """.trimMargin("|") + "\n"
    }

    companion object {
        fun escapeSingleQuotes(value: String): String = value.replace("'", "'\\''")
    }
}
