package atlas.util

import kotlinx.cinterop.*
import platform.posix.*

/**
 * Pure POSIX file system utilities for Kotlin/Native on macOS.
 * Zero external library dependencies.
 */
@OptIn(ExperimentalForeignApi::class)
object PlatformFiles {

    fun exists(path: String): Boolean = access(path, F_OK) == 0

    fun delete(path: String): Boolean = unlink(path) == 0

    fun deleteRecursively(path: String): Boolean {
        if (!exists(path)) return true
        if (isDirectory(path)) {
            val entries = listDirectory(path)
            for (entry in entries) {
                val full = if (path.endsWith("/")) "$path$entry" else "$path/$entry"
                deleteRecursively(full)
            }
            return rmdir(path) == 0
        }
        return unlink(path) == 0
    }

    fun isDirectory(path: String): Boolean = memScoped {
        val sb = alloc<stat>()
        if (stat(path, sb.ptr) == 0) {
            (sb.st_mode.toInt() and S_IFMT) == S_IFDIR
        } else false
    }

    fun isRegularFile(path: String): Boolean = memScoped {
        val sb = alloc<stat>()
        if (stat(path, sb.ptr) == 0) {
            (sb.st_mode.toInt() and S_IFMT) == S_IFREG
        } else false
    }

    fun mtime(path: String): Double = memScoped {
        val sb = alloc<stat>()
        if (stat(path, sb.ptr) == 0) {
            val sec = sb.st_mtimespec.tv_sec.toDouble()
            val nsec = sb.st_mtimespec.tv_nsec.toDouble()
            sec + (nsec / 1e9)
        } else 0.0
    }

    fun mkdirs(path: String): Boolean {
        if (path.isEmpty() || exists(path)) return true
        val normalized = path.replace('\\', '/').trimEnd('/')
        var current = if (normalized.startsWith("/")) "/" else ""
        for (part in normalized.split('/')) {
            if (part.isEmpty()) continue
            current = joinPathSegment(current, part)
            if (!ensureDirectoryExists(current)) {
                return false
            }
        }
        return true
    }

    private fun joinPathSegment(base: String, part: String): String {
        return if (base == "/" || base.isEmpty()) "$base$part" else "$base/$part"
    }

    private fun ensureDirectoryExists(dirPath: String): Boolean {
        if (exists(dirPath)) return true
        val mode = 0b111101101.toUShort()
        return mkdir(dirPath, mode) == 0 || errno == EEXIST
    }

    fun readBytes(path: String): ByteArray {
        val file = fopen(path, "rb") ?: return ByteArray(0)
        try {
            fseek(file, 0, SEEK_END)
            val len = ftell(file)
            fseek(file, 0, SEEK_SET)
            if (len <= 0) return ByteArray(0)
            val bytes = ByteArray(len.toInt())
            bytes.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, len.toULong(), file)
            }
            return bytes
        } finally {
            fclose(file)
        }
    }

    fun readText(path: String): String = readBytes(path).decodeToString()

    fun writeText(path: String, content: String): Boolean {
        val parent = parentOf(path)
        if (parent.isNotEmpty()) {
            mkdirs(parent)
        }
        val file = fopen(path, "wb") ?: return false
        var success = false
        try {
            val bytes = content.encodeToByteArray()
            if (bytes.isNotEmpty()) {
                val written = bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
                }
                success = (written == bytes.size.toULong())
            } else {
                success = true
            }
        } finally {
            val closeRc = fclose(file)
            if (closeRc != 0) {
                success = false
            }
        }
        return success
    }

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

    fun sanitizePathSegment(segment: String): String = sanitizeFileName(segment)

    fun logWarning(tag: String, message: String) {
        platform.posix.fputs("[$tag] Warning: $message\n", platform.posix.stderr)
        platform.posix.fflush(platform.posix.stderr)
    }

    fun listDirectory(dirPath: String): List<String> {
        val result = mutableListOf<String>()
        val dir = opendir(dirPath) ?: return result
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                result.add(name)
            }
        } finally {
            closedir(dir)
        }
        return result
    }

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
        val homeEnv = platform.posix.getenv("HOME")?.toKString()
        return homeEnv ?: "/Users"
    }

    fun getenv(name: String): String? {
        return platform.posix.getenv(name)?.toKString()
    }
}
