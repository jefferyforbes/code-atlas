package atlas.engine

import atlas.cli.AtlasCli
import atlas.db.SqliteGraphStore
import atlas.mcp.AtlasMcpServer
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid
import platform.posix.rmdir

class ComplexityRatchetTest {

    private fun makeTempDir(): String {
        val path = "/tmp/atlas_ratchet_test_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(path)
        return path
    }

    private fun cleanDir(dir: String) {
        for (f in PlatformFiles.listFilesRecursively(dir)) {
            PlatformFiles.delete(f)
        }
        rmdir(dir)
    }

    private fun makeTempDb(): String {
        val path = "/tmp/atlas_ratchet_db_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        PlatformFiles.delete(path)
        return path
    }

    @Test
    fun testRatchetPassAndFailViaMcpTool() {
        val tempDir = makeTempDir()
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val node1 = GraphNode(
                nodeId = "proj:src/A.kt#fnA",
                projectId = "proj",
                fileId = "proj:src/A.kt",
                name = "fnA",
                qualifiedName = "atlas.sample.fnA",
                kind = "function",
                startLine = 1,
                endLine = 20,
                cognitiveComplexity = 12,
                cyclomaticComplexity = 5
            )
            val node2 = GraphNode(
                nodeId = "proj:src/B.kt#fnB",
                projectId = "proj",
                fileId = "proj:src/B.kt",
                name = "fnB",
                qualifiedName = "atlas.sample.fnB",
                kind = "function",
                startLine = 1,
                endLine = 30,
                cognitiveComplexity = 18,
                cyclomaticComplexity = 8
            )
            store.upsertNodesAndEdges("proj", listOf(node1, node2), emptyList())

            val server = AtlasMcpServer(workspaceRoot = tempDir, customDbPath = dbPath)

            // Case 1: No ratchet and no baseline file -> violations.isEmpty() -> fails because 2 violations
            val resNoRatchet = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
            })
            assertFalse(resNoRatchet["passed"]?.jsonPrimitive?.booleanOrNull ?: true)
            assertEquals(2, resNoRatchet["total_violations"]?.jsonPrimitive?.intOrNull)
            assertEquals(null, resNoRatchet["ratchet"])

            // Case 2: Explicit ratchet = 2 (equal to violations) -> passes, delta = 0
            val resRatchet2 = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
                put("ratchet", 2)
            })
            assertTrue(resRatchet2["passed"]?.jsonPrimitive?.booleanOrNull ?: false)
            assertEquals(2, resRatchet2["ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, resRatchet2["baseline_delta"]?.jsonPrimitive?.intOrNull)

            // Case 3: Explicit ratchet = 5 (higher than violations) -> passes, delta = -3
            val resRatchet5 = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
                put("ratchet", 5)
            })
            assertTrue(resRatchet5["passed"]?.jsonPrimitive?.booleanOrNull ?: false)
            assertEquals(5, resRatchet5["ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(-3, resRatchet5["baseline_delta"]?.jsonPrimitive?.intOrNull)

            // Case 4: Explicit ratchet = 1 (lower than violations) -> fails, delta = +1
            val resRatchet1 = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
                put("ratchet", 1)
            })
            assertFalse(resRatchet1["passed"]?.jsonPrimitive?.booleanOrNull ?: true)
            assertEquals(1, resRatchet1["ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, resRatchet1["baseline_delta"]?.jsonPrimitive?.intOrNull)

            server.close()
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            cleanDir(tempDir)
        }
    }

    @Test
    fun testBaselineFileLoadingFromWorkspaceRoot() {
        val tempDir = makeTempDir()
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val node1 = GraphNode(
                nodeId = "proj:src/A.kt#fnA",
                projectId = "proj",
                fileId = "proj:src/A.kt",
                name = "fnA",
                qualifiedName = "atlas.sample.fnA",
                kind = "function",
                startLine = 1,
                endLine = 20,
                cognitiveComplexity = 14,
                cyclomaticComplexity = 4
            )
            store.upsertNodesAndEdges("proj", listOf(node1), emptyList())

            val baselinePath = "$tempDir/complexity-baseline.json"
            PlatformFiles.writeText(
                baselinePath,
                """{"project": "proj", "ratchet": 1, "threshold": 10, "max_cyclomatic": 15, "total_violations": 1}"""
            )

            val server = AtlasMcpServer(workspaceRoot = tempDir, customDbPath = dbPath)

            // Auto-load from workspaceRoot: ratchet = 1, violations = 1 -> passes, delta = 0
            val resAutoLoad = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
            })
            assertTrue(resAutoLoad["passed"]?.jsonPrimitive?.booleanOrNull ?: false)
            assertEquals(1, resAutoLoad["ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, resAutoLoad["baseline_delta"]?.jsonPrimitive?.intOrNull)

            // Update baseline in workspace to ratchet = 0 -> fails, delta = +1
            PlatformFiles.writeText(
                baselinePath,
                """{"project": "proj", "ratchet": 0, "threshold": 10, "max_cyclomatic": 15, "total_violations": 0}"""
            )
            val resFail = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
            })
            assertFalse(resFail["passed"]?.jsonPrimitive?.booleanOrNull ?: true)
            assertEquals(0, resFail["ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, resFail["baseline_delta"]?.jsonPrimitive?.intOrNull)

            server.close()
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            cleanDir(tempDir)
        }
    }

    @Test
    fun testExplicitBaselineFileAndDeltaCalculation() {
        val tempDir = makeTempDir()
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val node1 = GraphNode(
                nodeId = "my-app:src/X.kt#fnX",
                projectId = "my-app",
                fileId = "my-app:src/X.kt",
                name = "fnX",
                qualifiedName = "fnX",
                kind = "function",
                startLine = 1,
                cognitiveComplexity = 15
            )
            val node2 = GraphNode(
                nodeId = "my-app:src/Y.kt#fnY",
                projectId = "my-app",
                fileId = "my-app:src/Y.kt",
                name = "fnY",
                qualifiedName = "fnY",
                kind = "function",
                startLine = 1,
                cognitiveComplexity = 20
            )
            store.upsertNodesAndEdges("my-app", listOf(node1, node2), emptyList())

            val customBaselinePath = "$tempDir/custom-baseline.json"
            PlatformFiles.writeText(
                customBaselinePath,
                """{"project": "my-app", "ratchet": 3, "total_violations": 3}"""
            )

            val server = AtlasMcpServer(workspaceRoot = tempDir, customDbPath = dbPath)

            // Explicit baseline file path passed via tool argument
            val res = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "my-app")
                put("baseline", customBaselinePath)
            })
            assertTrue(res["passed"]?.jsonPrimitive?.booleanOrNull ?: false)
            assertEquals(3, res["ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(2, res["total_violations"]?.jsonPrimitive?.intOrNull)
            assertEquals(-1, res["baseline_delta"]?.jsonPrimitive?.intOrNull)

            server.close()
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            cleanDir(tempDir)
        }
    }

    @Test
    fun testCliRatchetFlagAndSaveBaseline() {
        val tempDir = makeTempDir()
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val node1 = GraphNode(
                nodeId = "cli-proj:src/Main.kt#heavy",
                projectId = "cli-proj",
                fileId = "cli-proj:src/Main.kt",
                name = "heavy",
                qualifiedName = "heavy",
                kind = "function",
                startLine = 1,
                cognitiveComplexity = 25
            )
            store.upsertNodesAndEdges("cli-proj", listOf(node1), emptyList())

            // 1. Without ratchet -> exit code 1
            val exitNoRatchet = AtlasCli.run(
                arrayOf("audit", "--complexity", "--project", "cli-proj", "--db", dbPath, "--workspace", tempDir)
            )
            assertEquals(1, exitNoRatchet)

            // 2. With --ratchet 1 -> exit code 0
            val exitPass = AtlasCli.run(
                arrayOf("audit", "--complexity", "--project", "cli-proj", "--db", dbPath, "--workspace", tempDir, "--ratchet", "1")
            )
            assertEquals(0, exitPass)

            // 3. With --ratchet 0 -> exit code 1
            val exitFail = AtlasCli.run(
                arrayOf("audit", "--complexity", "--project", "cli-proj", "--db", dbPath, "--workspace", tempDir, "--ratchet", "0")
            )
            assertEquals(1, exitFail)

            // 4. With --save-baseline
            val savedBaselinePath = "$tempDir/saved-baseline.json"
            val exitSave = AtlasCli.run(
                arrayOf("audit", "--complexity", "--project", "cli-proj", "--db", dbPath, "--workspace", tempDir, "--save-baseline", savedBaselinePath)
            )
            assertEquals(0, exitSave)
            assertTrue(PlatformFiles.exists(savedBaselinePath))

            val savedContent = PlatformFiles.readText(savedBaselinePath)
            val parsed = Json.parseToJsonElement(savedContent).jsonObject
            assertEquals("cli-proj", parsed["project"]?.jsonPrimitive?.content)
            assertEquals(1, parsed["total_violations"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, parsed["ratchet"]?.jsonPrimitive?.intOrNull)

            // 5. Run using the saved baseline file via --baseline
            val exitWithBaselineFile = AtlasCli.run(
                arrayOf("audit", "--complexity", "--project", "cli-proj", "--db", dbPath, "--workspace", tempDir, "--baseline", savedBaselinePath)
            )
            assertEquals(0, exitWithBaselineFile)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            cleanDir(tempDir)
        }
    }
}
