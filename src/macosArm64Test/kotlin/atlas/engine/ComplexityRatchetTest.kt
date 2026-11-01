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

    @Test
    fun testTwoTierRatchetPassAndFailViaMcpTool() {
        val tempDir = makeTempDir()
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            // node1: cognitive 12 -> WARNING
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
            // node2: cognitive 18 -> HIGH
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

            // Case 1: Total ratchet = 2, High ratchet = 1 -> Both pass
            val resBothPass = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
                put("ratchet", 2)
                put("high_ratchet", 1)
            })
            assertTrue(resBothPass["passed"]?.jsonPrimitive?.booleanOrNull ?: false)
            assertEquals(2, resBothPass["total_violations"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, resBothPass["high_severity_count"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, resBothPass["warning_count"]?.jsonPrimitive?.intOrNull)
            assertEquals(2, resBothPass["ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, resBothPass["high_ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, resBothPass["baseline_delta"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, resBothPass["high_delta"]?.jsonPrimitive?.intOrNull)

            // Case 2: Total ratchet = 5 (passes), but High ratchet = 0 (fails because 1 HIGH > 0)
            // Hardening verification: new HIGH violation cannot hide behind high total ratchet!
            val resHighFails = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
                put("ratchet", 5)
                put("high_ratchet", 0)
            })
            assertFalse(resHighFails["passed"]?.jsonPrimitive?.booleanOrNull ?: true, "Must fail when high violations exceed high_ratchet")
            assertEquals(5, resHighFails["ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, resHighFails["high_ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(-3, resHighFails["baseline_delta"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, resHighFails["high_delta"]?.jsonPrimitive?.intOrNull)

            // Case 3: Read both ratchets from complexity-baseline.json file
            val baselinePath = "$tempDir/complexity-baseline.json"
            PlatformFiles.writeText(
                baselinePath,
                """{"project": "proj", "ratchet": 2, "high_severity_count": 0, "total_violations": 2}"""
            )
            val resBaselineHighFail = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
            })
            assertFalse(resBaselineHighFail["passed"]?.jsonPrimitive?.booleanOrNull ?: true, "Must fail because high_severity_count is 0 in baseline")
            assertEquals(0, resBaselineHighFail["high_ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, resBaselineHighFail["high_delta"]?.jsonPrimitive?.intOrNull)

            server.close()
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            cleanDir(tempDir)
        }
    }

    @Test
    fun testCliHighRatchetFlag() {
        val tempDir = makeTempDir()
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val highNode = GraphNode(
                nodeId = "cli-proj:src/Main.kt#heavy",
                projectId = "cli-proj",
                fileId = "cli-proj:src/Main.kt",
                name = "heavy",
                qualifiedName = "heavy",
                kind = "function",
                startLine = 1,
                cognitiveComplexity = 25
            )
            store.upsertNodesAndEdges("cli-proj", listOf(highNode), emptyList())

            // 1. Total ratchet passes (5), but --high-ratchet is 0 -> exit code 1
            val exitFail = AtlasCli.run(
                arrayOf("audit", "--complexity", "--project", "cli-proj", "--db", dbPath, "--workspace", tempDir, "--ratchet", "5", "--high-ratchet", "0")
            )
            assertEquals(1, exitFail)

            // 2. Both pass (--ratchet 5, --high-ratchet 1) -> exit code 0
            val exitPass = AtlasCli.run(
                arrayOf("audit", "--complexity", "--project", "cli-proj", "--db", dbPath, "--workspace", tempDir, "--ratchet", "5", "--high-ratchet", "1")
            )
            assertEquals(0, exitPass)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            cleanDir(tempDir)
        }
    }

    @Test
    fun testAuditSchemaDeclaresHighRatchetAndMaxHighViolations() {
        val tempDir = makeTempDir()
        val server = AtlasMcpServer(workspaceRoot = tempDir)
        try {
            val tools = server.toolDefinitions()
            val auditTool = tools.firstOrNull { it["name"]?.jsonPrimitive?.content == "atlas_audit" }
            assertNotNull(auditTool, "atlas_audit tool must exist")
            val inputSchema = auditTool["inputSchema"]?.jsonObject
            assertNotNull(inputSchema, "inputSchema must exist")
            val schemaProps = inputSchema["properties"]?.jsonObject
            assertNotNull(schemaProps, "inputSchema properties must exist")
            assertTrue(schemaProps.containsKey("high_ratchet"), "auditSchema must declare high_ratchet property")
            assertTrue(schemaProps.containsKey("max_high_violations"), "auditSchema must declare max_high_violations property")
            assertEquals("integer", schemaProps["high_ratchet"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
            assertEquals("integer", schemaProps["max_high_violations"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        } finally {
            server.close()
            cleanDir(tempDir)
        }
    }

    @Test
    fun testStringEncodedRatchetArgumentsViaMcpTool() {
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
                cognitiveComplexity = 12, // WARNING
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
                cognitiveComplexity = 18, // HIGH
                cyclomaticComplexity = 8
            )
            store.upsertNodesAndEdges("proj", listOf(node1, node2), emptyList())

            val server = AtlasMcpServer(workspaceRoot = tempDir, customDbPath = dbPath)

            // String-encoded ratchets: ratchet = "2", high_ratchet = "1" -> passes
            val resPass = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
                put("ratchet", "2")
                put("high_ratchet", "1")
            })
            assertTrue(resPass["passed"]?.jsonPrimitive?.booleanOrNull ?: false)
            assertEquals(2, resPass["ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, resPass["high_ratchet"]?.jsonPrimitive?.intOrNull)

            // String-encoded ratchets: high_ratchet = "0" -> fails
            val resFail = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
                put("ratchet", "5")
                put("high_ratchet", "0")
            })
            assertFalse(resFail["passed"]?.jsonPrimitive?.booleanOrNull ?: true)
            assertEquals(0, resFail["high_ratchet"]?.jsonPrimitive?.intOrNull)

            server.close()
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            cleanDir(tempDir)
        }
    }

    @Test
    fun testCliSaveBaselineIncludesBothHighRatchetAndHighSeverityCount() {
        val tempDir = makeTempDir()
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val highNode = GraphNode(
                nodeId = "cli-proj:src/Main.kt#heavy",
                projectId = "cli-proj",
                fileId = "cli-proj:src/Main.kt",
                name = "heavy",
                qualifiedName = "heavy",
                kind = "function",
                startLine = 1,
                signature = "fun heavy(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int)",
                cognitiveComplexity = 25
            )
            store.upsertNodesAndEdges("cli-proj", listOf(highNode), emptyList())

            val baselinePath = "$tempDir/saved-baseline.json"
            val exitCode = AtlasCli.run(
                arrayOf(
                    "audit", "--complexity", "--project", "cli-proj",
                    "--db", dbPath, "--workspace", tempDir,
                    "--save-baseline", baselinePath
                )
            )
            assertEquals(0, exitCode)
            assertTrue(PlatformFiles.exists(baselinePath))

            val content = PlatformFiles.readText(baselinePath)
            val json = Json.parseToJsonElement(content).jsonObject
            assertEquals(1, json["total_violations"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, json["ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, json["high_severity_count"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, json["high_ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, json["param_high_ratchet"]?.jsonPrimitive?.intOrNull)

            // Verify CLI --param-high-ratchet flag enforcement
            val exitParamFail = AtlasCli.run(
                arrayOf(
                    "audit", "--complexity", "--project", "cli-proj",
                    "--db", dbPath, "--workspace", tempDir,
                    "--ratchet", "5", "--high-ratchet", "5", "--param-high-ratchet", "0"
                )
            )
            assertEquals(1, exitParamFail)

            val exitParamPass = AtlasCli.run(
                arrayOf(
                    "audit", "--complexity", "--project", "cli-proj",
                    "--db", dbPath, "--workspace", tempDir,
                    "--ratchet", "5", "--high-ratchet", "5", "--param-high-ratchet", "1"
                )
            )
            assertEquals(0, exitParamPass)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            cleanDir(tempDir)
        }
    }

    @Test
    fun testMcpAuditViolationCappingAndParamHighRatchet() {
        val tempDir = makeTempDir()
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val nodes = (1..65).map { idx ->
                GraphNode(
                    nodeId = "proj:src/File$idx.kt#fn$idx",
                    projectId = "proj",
                    fileId = "proj:src/File$idx.kt",
                    name = "fn$idx",
                    qualifiedName = "atlas.sample.fn$idx",
                    kind = "function",
                    startLine = 1,
                    endLine = 15,
                    signature = if (idx <= 3) {
                        "fun fn$idx(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int)"
                    } else {
                        "fun fn$idx(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int)"
                    },
                    cognitiveComplexity = 4,
                    cyclomaticComplexity = 3
                )
            }
            store.upsertNodesAndEdges("proj", nodes, emptyList())

            val server = AtlasMcpServer(workspaceRoot = tempDir, customDbPath = dbPath)

            // 1. Default limit=50 caps returned violations while preserving exact advisory_count and param_high_count
            val resDefault = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
            })
            assertFalse(resDefault["spillover"]?.jsonPrimitive?.booleanOrNull ?: false, "Capped payload must not trigger spillover")
            assertEquals(0, resDefault["total_violations"]?.jsonPrimitive?.intOrNull)
            assertEquals(65, resDefault["advisory_count"]?.jsonPrimitive?.intOrNull)
            assertEquals(3, resDefault["param_high_count"]?.jsonPrimitive?.intOrNull)
            assertEquals(50, resDefault["returned_violations"]?.jsonPrimitive?.intOrNull)

            // 2. param_high_ratchet = 3 -> passes
            val resParamPass = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
                put("param_high_ratchet", 3)
            })
            assertTrue(resParamPass["passed"]?.jsonPrimitive?.booleanOrNull ?: false)
            assertEquals(3, resParamPass["param_high_ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, resParamPass["param_high_delta"]?.jsonPrimitive?.intOrNull)

            // 3. param_high_ratchet = 2 -> fails because 3 HIGH parameter violations > 2
            val resParamFail = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
                put("param_high_ratchet", 2)
            })
            assertFalse(resParamFail["passed"]?.jsonPrimitive?.booleanOrNull ?: true)
            assertEquals(2, resParamFail["param_high_ratchet"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, resParamFail["param_high_delta"]?.jsonPrimitive?.intOrNull)

            // 4. verify_architecture also caps complexityViolations to limit and avoids spillover
            val resArch = server.callTool("atlas_audit", buildJsonObject {
                put("action", "verify_architecture")
                put("project", "proj")
            })
            assertFalse(resArch["spillover"]?.jsonPrimitive?.booleanOrNull ?: false)
            assertEquals(65, resArch["total_complexity_violations"]?.jsonPrimitive?.intOrNull)

            server.close()
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            cleanDir(tempDir)
        }
    }
}

