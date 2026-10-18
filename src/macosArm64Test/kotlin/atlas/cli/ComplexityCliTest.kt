package atlas.cli

import atlas.db.SqliteGraphStore
import atlas.mcp.CodeGraphMcpServer
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid
import platform.posix.rmdir

class ComplexityCliTest {

    private fun makeTempDir(): String {
        val path = "/tmp/atlas_complexity_cli_test_${getpid()}_${Random.nextInt(100000, 999999)}"
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
        val path = "/tmp/atlas_complexity_cli_db_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        PlatformFiles.delete(path)
        return path
    }

    @Test
    fun testComplexityQueryCliTabularAndJson() {
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val node = GraphNode(
                nodeId = "proj:src/Alg.kt#calc",
                projectId = "proj",
                fileId = "proj:src/Alg.kt",
                name = "calc",
                qualifiedName = "atlas.sample.Alg.calc",
                kind = "function",
                startLine = 12,
                endLine = 35,
                inDegree = 2,
                cognitiveComplexity = 14,
                cyclomaticComplexity = 6,
                maxNestingDepth = 3,
                complexityScore = 48.0
            )
            store.upsertNodesAndEdges("proj", listOf(node), emptyList())

            val exitJson = CodeGraphCli.run(
                arrayOf("query", "--action", "complexity", "--project", "proj", "--db", dbPath, "--json")
            )
            assertEquals(0, exitJson)

            val exitTable = CodeGraphCli.run(
                arrayOf("query", "--action", "complexity", "--project", "proj", "--db", dbPath)
            )
            assertEquals(0, exitTable)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testComplexityAuditCliGateExitCode() {
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val complexNode = GraphNode(
                nodeId = "proj:src/Heavy.kt#runBatch",
                projectId = "proj",
                fileId = "proj:src/Heavy.kt",
                name = "runBatch",
                qualifiedName = "atlas.sample.Heavy.runBatch",
                kind = "function",
                startLine = 10,
                endLine = 50,
                inDegree = 0,
                cognitiveComplexity = 16,
                cyclomaticComplexity = 7,
                maxNestingDepth = 4,
                complexityScore = 40.0
            )
            store.upsertNodesAndEdges("proj", listOf(complexNode), emptyList())

            // Default threshold is 10: node with cognitive 16 should fail (exit code 1)
            val exitFail = CodeGraphCli.run(
                arrayOf("audit", "--complexity", "--project", "proj", "--db", dbPath)
            )
            assertEquals(1, exitFail)

            // Relaxed threshold 20: node with cognitive 16 should pass (exit code 0)
            val exitPass = CodeGraphCli.run(
                arrayOf("audit", "--complexity", "--threshold", "20", "--project", "proj", "--db", dbPath)
            )
            assertEquals(0, exitPass)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testComplexityDoctorCliRemediation() {
        val tmpDir = makeTempDir()
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val relPath = "src/Processor.kt"
            val fullPath = "$tmpDir/$relPath"
            PlatformFiles.mkdirs("$tmpDir/src")
            val source = """
                package atlas.sample

                class Processor {
                    fun process(items: List<String>?, active: Boolean): Int {
                        if (active && items != null) {
                            var total = 0
                            for (item in items) {
                                if (item.isNotEmpty()) {
                                    if (item.startsWith("A")) {
                                        total += 1
                                    }
                                }
                            }
                            return total
                        }
                        return 0
                    }
                }
            """.trimIndent()
            PlatformFiles.writeText(fullPath, source)

            val node = GraphNode(
                nodeId = "proj:$relPath#process",
                projectId = "proj",
                fileId = "proj:$relPath",
                name = "process",
                qualifiedName = "atlas.sample.Processor.process",
                kind = "function",
                startLine = 4,
                endLine = 19,
                inDegree = 0,
                cognitiveComplexity = 11,
                cyclomaticComplexity = 5,
                maxNestingDepth = 3,
                complexityScore = 59.0
            )
            store.upsertNodesAndEdges("proj", listOf(node), emptyList())

            // Doctor dry run mode
            val exitDryRun = CodeGraphCli.run(
                arrayOf(
                    "doctor", "--complexity",
                    "--project", "proj",
                    "--workspace", tmpDir,
                    "--db", dbPath,
                    "--json"
                )
            )
            assertEquals(0, exitDryRun)

            // Doctor apply mode
            val exitApply = CodeGraphCli.run(
                arrayOf(
                    "doctor", "--complexity",
                    "--project", "proj",
                    "--workspace", tmpDir,
                    "--db", dbPath,
                    "--apply"
                )
            )
            assertEquals(0, exitApply)

            val diskContent = PlatformFiles.readText(fullPath)
            assertTrue(diskContent.contains("if (!active || items == null) return 0"))
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testMcpComplexityEndpoints() {
        val dbPath = makeTempDb()
        val server = CodeGraphMcpServer(workspaceRoot = ".", customDbPath = dbPath)
        try {
            val node = GraphNode(
                nodeId = "proj:src/Compute.kt#execute",
                projectId = "proj",
                fileId = "proj:src/Compute.kt",
                name = "execute",
                qualifiedName = "atlas.sample.Compute.execute",
                kind = "function",
                startLine = 5,
                endLine = 25,
                inDegree = 1,
                cognitiveComplexity = 12,
                cyclomaticComplexity = 4,
                maxNestingDepth = 2,
                complexityScore = 58.0
            )
            server.store.upsertNodesAndEdges("proj", listOf(node), emptyList())

            // 1. codegraph_query with action="complexity"
            val queryArgs = buildJsonObject {
                put("action", "complexity")
                put("project", "proj")
                put("threshold", 10)
            }
            val queryRes = server.callTool("codegraph_query", queryArgs)
            val queryResults = queryRes["results"]?.jsonArray
            assertEquals(1, queryResults?.size)
            val firstRecord = queryResults?.get(0)?.jsonObject
            assertEquals("execute", firstRecord?.get("name")?.jsonPrimitive?.content)
            assertEquals(12, firstRecord?.get("cognitive")?.jsonPrimitive?.intOrNull)

            // 2. codegraph_audit with action="audit_complexity"
            val auditArgs = buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
                put("threshold", 10)
            }
            val auditRes = server.callTool("codegraph_audit", auditArgs)
            assertFalse(auditRes["passed"]?.jsonPrimitive?.booleanOrNull ?: true)
            assertEquals(1, auditRes["total_violations"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, auditRes["warning_count"]?.jsonPrimitive?.intOrNull)
        } finally {
            server.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testComplexityQueryWithFlagAndSubsystem() {
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val classNode = GraphNode(
                nodeId = "proj:src/Service.kt#Service",
                projectId = "proj",
                fileId = "proj:src/Service.kt",
                name = "Service",
                qualifiedName = "atlas.sample.Service",
                kind = "class",
                startLine = 3,
                endLine = 40
            )
            val methodNode = GraphNode(
                nodeId = "proj:src/Service.kt#Service.doWork",
                projectId = "proj",
                fileId = "proj:src/Service.kt",
                name = "doWork",
                qualifiedName = "atlas.sample.Service.doWork",
                kind = "method",
                startLine = 5,
                endLine = 25,
                inDegree = 2,
                cognitiveComplexity = 13,
                cyclomaticComplexity = 5,
                maxNestingDepth = 2,
                complexityScore = 55.0
            )
            store.upsertNodesAndEdges("proj", listOf(classNode, methodNode), emptyList())

            // 1. atlas query --complexity --project proj
            val exitQueryFlag = CodeGraphCli.run(
                arrayOf("query", "--complexity", "--project", "proj", "--db", dbPath, "--json")
            )
            assertEquals(0, exitQueryFlag)

            // 2. atlas query --action complexity --target Service (resolves methods inside class Service)
            val server = CodeGraphMcpServer(workspaceRoot = ".", customDbPath = dbPath)
            val queryArgs = buildJsonObject {
                put("action", "complexity")
                put("project", "proj")
                put("target", "Service")
            }
            val res = server.callTool("codegraph_query", queryArgs)
            val results = res["results"]?.jsonArray
            assertEquals(1, results?.size)
            assertEquals("doWork", results?.get(0)?.jsonObject?.get("name")?.jsonPrimitive?.content)
            server.close()

            // 3. atlas complexity audit --project proj
            val exitSubsystemAudit = CodeGraphCli.run(
                arrayOf("complexity", "audit", "--project", "proj", "--db", dbPath)
            )
            assertEquals(1, exitSubsystemAudit) // cognitive 13 > 10

            // 4. atlas complexity query --project proj
            val exitSubsystemQuery = CodeGraphCli.run(
                arrayOf("complexity", "query", "--project", "proj", "--db", dbPath, "--json")
            )
            assertEquals(0, exitSubsystemQuery)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }
}
