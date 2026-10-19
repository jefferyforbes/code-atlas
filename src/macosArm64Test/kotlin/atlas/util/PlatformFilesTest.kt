package atlas.util

import atlas.mcp.AtlasMcpServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class PlatformFilesTest {

    @Test
    fun testSanitizeFileNameRejectsTraversal() {
        val dangerousInputs = listOf(
            "../../../../etc/passwd" to "etc_passwd",
            "../secret" to "secret",
            "foo/bar/baz" to "foo_bar_baz",
            "foo/. ./bar" to "foo_bar",
            ". . / . ." to "unnamed",
            "foo\\bar\\baz" to "foo_bar_baz",
            "..\\..\\windows\\system32" to "windows_system32",
            "..." to "unnamed",
            "" to "unnamed",
            "safe-project_1.0" to "safe-project_1.0"
        )

        for ((input, expected) in dangerousInputs) {
            val sanitized = PlatformFiles.sanitizeFileName(input)
            assertEquals(expected, sanitized, "Sanitization failed for input: $input")
            assertFalse(sanitized.contains('/'), "Sanitized name must not contain '/': $sanitized")
            assertFalse(sanitized.contains('\\'), "Sanitized name must not contain '\\': $sanitized")
            assertFalse(sanitized.contains(".."), "Sanitized name must not contain '..': $sanitized")
        }
    }

    @Test
    fun testWriteSecureTextEnforces0600Permissions() {
        val pid = getpid()
        val testDir = "/tmp/test_atlas_secure_files_$pid"
        val testFile = "$testDir/secret.json"
        PlatformFiles.deleteRecursively(testDir)

        try {
            val content = """{"secret": "token123"}"""
            val ok = PlatformFiles.writeSecureText(testFile, content, mode = 0b110000000u) // 0600
            assertTrue(ok, "writeSecureText should return true")
            assertTrue(PlatformFiles.exists(testFile), "Secure file must exist")

            val mode = PlatformFiles.fileMode(testFile)
            // 0600 octal = 384 decimal (rw-------)
            assertEquals(384u, mode, "File permission mode must be 0600 (384 decimal)")

            val readBack = PlatformFiles.readText(testFile)
            assertEquals(content, readBack)
        } finally {
            PlatformFiles.deleteRecursively(testDir)
        }
    }

    @Test
    fun testSpilloverSecurityEnforces0600AndPathSanitization() {
        val pid = getpid()
        val wsRoot = "/tmp/test_atlas_spill_sec_ws_$pid"
        val dbPath = "/tmp/test_atlas_spill_sec_$pid.db"
        PlatformFiles.delete(dbPath)
        PlatformFiles.mkdirs(wsRoot)

        val server = AtlasMcpServer(workspaceRoot = wsRoot, customDbPath = dbPath)
        try {
            val largeString = "x".repeat(35000)
            val traversalProjectId = "../../../../traversal_attempt"
            val safeProj = PlatformFiles.sanitizeFileName(traversalProjectId)
            assertEquals("traversal_attempt", safeProj)

            val spillPath = "${server.artifactsDir}/spillover_${safeProj}_${pid}_1.json"
            PlatformFiles.writeSecureText(spillPath, largeString)

            assertTrue(PlatformFiles.exists(spillPath))
            val mode = PlatformFiles.fileMode(spillPath)
            assertEquals(384u, mode, "Spillover artifact must have 0600 permissions")

            PlatformFiles.delete(spillPath)
        } finally {
            server.close()
            PlatformFiles.deleteRecursively(wsRoot)
            PlatformFiles.delete(dbPath)
        }
    }
}
