package atlas.engine

import atlas.util.PlatformFiles
import kotlinx.serialization.Serializable

/**
 * Autonomous scanner and migration engine for detecting and repairing deprecated
 * Atlas tool invocations (codegraph_query -> atlas_query, codegraph_audit -> atlas_audit).
 */
object ToolMigrationEngine {

    @Serializable
    data class MigrationMatch(
        val file: String,
        val line: Int,
        val legacyTool: String,
        val preferredTool: String,
        val snippet: String
    )

    @Serializable
    data class MigrationReport(
        val totalOccurrences: Int,
        val filesAffected: Int,
        val applied: Boolean,
        val matches: List<MigrationMatch>
    )

    private val SKIP_DIRS = setOf(
        ".git", ".gradle", ".idea", ".venv", "venv", "node_modules",
        "build", "dist", "__pycache__", ".pytest_cache", ".artifacts",
        ".atlas"
    )

    private val SKIP_FILES = setOf(
        "ToolMigrationEngine.kt"
    )

    private val TARGET_EXTS = setOf(
        "kt", "kts", "md", "json", "ts", "js", "py", "sh", "yml", "yaml", "txt", "conf", "toml"
    )

    /**
     * Scans the target path (file or directory) for legacy tool invocations and optionally rewrites them.
     */
    fun scanAndMigrate(targetPath: String, apply: Boolean = false): MigrationReport =
        scanAndMigrate(listOf(targetPath), apply)

    /**
     * Scans multiple target paths for legacy tool invocations and optionally rewrites them.
     */
    fun scanAndMigrate(targetPaths: List<String>, apply: Boolean = false): MigrationReport {
        val matches = mutableListOf<MigrationMatch>()
        val affectedFiles = mutableSetOf<String>()

        val filesToProcess = mutableListOf<String>()
        for (tp in targetPaths) {
            collectFiles(tp, filesToProcess)
        }

        for (filePath in filesToProcess) {
            val content = PlatformFiles.readText(filePath)
            if (!content.contains("codegraph_query") && !content.contains("codegraph_audit")) {
                continue
            }

            var fileHadMatch = false
            val lines = content.lines()
            for ((idx, line) in lines.withIndex()) {
                val lineNum = idx + 1
                if (line.contains("codegraph_query")) {
                    matches.add(
                        MigrationMatch(
                            file = filePath,
                            line = lineNum,
                            legacyTool = "codegraph_query",
                            preferredTool = "atlas_query",
                            snippet = line.trim()
                        )
                    )
                    fileHadMatch = true
                }
                if (line.contains("codegraph_audit")) {
                    matches.add(
                        MigrationMatch(
                            file = filePath,
                            line = lineNum,
                            legacyTool = "codegraph_audit",
                            preferredTool = "atlas_audit",
                            snippet = line.trim()
                        )
                    )
                    fileHadMatch = true
                }
            }

            if (fileHadMatch) {
                affectedFiles.add(filePath)
                if (apply) {
                    val updated = content
                        .replace("codegraph_query", "atlas_query")
                        .replace("codegraph_audit", "atlas_audit")
                    PlatformFiles.writeText(filePath, updated)
                }
            }
        }

        return MigrationReport(
            totalOccurrences = matches.size,
            filesAffected = affectedFiles.size,
            applied = apply,
            matches = matches
        )
    }

    private fun collectFiles(path: String, outList: MutableList<String>) {
        if (!PlatformFiles.exists(path)) return
        if (PlatformFiles.isRegularFile(path)) {
            val fileName = path.trimEnd('/').substringAfterLast('/')
            if (SKIP_FILES.contains(fileName)) return
            val ext = path.substringAfterLast('.', "")
            if (TARGET_EXTS.contains(ext)) {
                outList.add(path)
            }
            return
        }

        if (PlatformFiles.isDirectory(path)) {
            val dirName = path.trimEnd('/').substringAfterLast('/')
            if (SKIP_DIRS.contains(dirName)) return

            val entries = PlatformFiles.listDirectory(path)
            for (entry in entries) {
                if (SKIP_DIRS.contains(entry)) continue
                val full = if (path.endsWith("/")) "$path$entry" else "$path/$entry"
                collectFiles(full, outList)
            }
        }
    }
}
