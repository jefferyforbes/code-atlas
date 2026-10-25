package atlas.util

import com.orion.nativeio.env.PlatformEnvProvider
import com.orion.nativeio.fs.PlatformFileSystem
import kotlinx.cinterop.*
import platform.posix.*

/**
 * Pure POSIX file system utilities for Kotlin/Native on macOS,
 * backed by com.orion:orion-native-io:0.1.0.
 */
@OptIn(ExperimentalForeignApi::class)
object PlatformFiles {

    fun exists(path: String): Boolean = PlatformFileSystem.exists(path)

    fun delete(path: String): Boolean = PlatformFileSystem.delete(path)

    fun deleteRecursively(path: String): Boolean = PlatformFileSystem.deleteRecursively(path)

    fun isDirectory(path: String): Boolean = PlatformFileSystem.isDirectory(path)

    fun isRegularFile(path: String): Boolean = PlatformFileSystem.isRegularFile(path)

    fun mtime(path: String): Double = memScoped {
        val sb = alloc<stat>()
        if (stat(path, sb.ptr) == 0) {
            val sec = sb.st_mtimespec.tv_sec.toDouble()
            val nsec = sb.st_mtimespec.tv_nsec.toDouble()
            sec + (nsec / 1e9)
        } else 0.0
    }

    fun mkdirs(path: String): Boolean = PlatformFileSystem.mkdirs(path)

    fun readBytes(path: String): ByteArray = PlatformFileSystem.readBytes(path)

    fun readText(path: String): String = PlatformFileSystem.readText(path)

    fun writeText(path: String, content: String): Boolean = PlatformFileSystem.writeText(path, content)

    fun chmod(path: String, mode: UInt): Boolean = platform.posix.chmod(path, mode.toUShort()) == 0

    fun fileMode(path: String): UInt = memScoped {
        val sb = alloc<stat>()
        if (stat(path, sb.ptr) == 0) {
            sb.st_mode.toUInt() and 0b111111111u
        } else 0u
    }

    fun writeSecureText(path: String, content: String, mode: UInt = 0b110000000u): Boolean {
        val parent = parentOf(path)
        if (parent.isNotEmpty()) {
            mkdirs(parent)
            chmod(parent, 0b111000000u)
        }
        val ok = writeText(path, content)
        if (ok) {
            chmod(path, mode)
        }
        return ok
    }

    fun sanitizeFileName(name: String): String {
        var res = name
            .replace('/', '_')
            .replace('\\', '_')
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        while (res.contains("..")) {
            res = res.replace("..", "_")
        }
        while (res.contains("__")) {
            res = res.replace("__", "_")
        }
        return res.trim('.', '_').ifEmpty { "unnamed" }
    }

    fun logWarning(tag: String, message: String) {
        platform.posix.fputs("[$tag] Warning: $message\n", platform.posix.stderr)
        platform.posix.fflush(platform.posix.stderr)
    }

    fun listDirectory(dirPath: String): List<String> = PlatformFileSystem.listDirectory(dirPath)

    fun listFilesRecursively(
        dirPath: String,
        shouldSkipDir: ((dirName: String) -> Boolean)? = null
    ): List<String> {
        val result = mutableListOf<String>()
        val visitedDirs = mutableSetOf<String>()
        val rootCanon = canonicalPath(dirPath).trimEnd('/')
        scanDirectoryRecursively(dirPath, rootCanon, visitedDirs, result, shouldSkipDir)
        return result
    }

    private fun isPathInsideRoot(rootCanon: String, canonPath: String): Boolean {
        if (rootCanon.isEmpty()) return true
        if (canonPath == rootCanon) return true
        return canonPath.startsWith("$rootCanon/")
    }

    private fun scanDirectoryRecursively(
        curr: String,
        rootCanon: String,
        visitedDirs: MutableSet<String>,
        result: MutableList<String>,
        shouldSkipDir: ((dirName: String) -> Boolean)?
    ) {
        val canon = canonicalPath(curr)
        if (!isPathInsideRoot(rootCanon, canon)) return
        if (!visitedDirs.add(canon)) return
        for (entry in listDirectory(curr)) {
            processDirectoryEntry(curr, entry, rootCanon, visitedDirs, result, shouldSkipDir)
        }
    }

    private fun processDirectoryEntry(
        curr: String,
        entry: String,
        rootCanon: String,
        visitedDirs: MutableSet<String>,
        result: MutableList<String>,
        shouldSkipDir: ((dirName: String) -> Boolean)?
    ) {
        val full = if (curr.endsWith("/")) "$curr$entry" else "$curr/$entry"
        if (isDirectory(full)) {
            if (shouldSkipDir?.invoke(entry) != true) {
                scanDirectoryRecursively(full, rootCanon, visitedDirs, result, shouldSkipDir)
            }
            return
        }
        if (isRegularFile(full) && isPathInsideRoot(rootCanon, canonicalPath(full))) {
            result.add(full)
        }
    }

    fun parentOf(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        val idx = normalized.lastIndexOf('/')
        return if (idx >= 0) normalized.substring(0, idx) else ""
    }

    fun fileName(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        val idx = normalized.lastIndexOf('/')
        return if (idx >= 0) normalized.substring(idx + 1) else normalized
    }

    fun canonicalPath(path: String): String = memScoped {
        val resolved = realpath(path, null)
        if (resolved != null) {
            val result = resolved.toKString().replace('\\', '/')
            free(resolved)
            return result
        }
        val normalized = path.replace('\\', '/')
        val parent = parentOf(normalized)
        if (parent.isNotEmpty() && parent != normalized) {
            val canonParent = canonicalPath(parent)
            val name = fileName(normalized)
            return if (canonParent == "/") "/$name" else "$canonParent/$name"
        }
        return normalized
    }

    fun userHome(): String {
        val homeEnv = PlatformEnvProvider.get("HOME")
        return homeEnv ?: "/Users"
    }

    fun getenv(name: String): String? {
        return PlatformEnvProvider.get(name)
    }
}
