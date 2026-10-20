package atlas.extract

import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectureAuditor
import atlas.mcp.AtlasMcpServer
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid

class ParamComplexityAuditorTest {

    private fun makeTempDb(): String {
        val path = "/tmp/atlas_param_test_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        PlatformFiles.delete(path)
        return path
    }

    @Test
    fun testAuditorParamViolationsAndThresholds() {
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            // Function with 4 parameters: below warning threshold 5
            val node4 = GraphNode(
                nodeId = "proj:src/A.kt#fn4",
                projectId = "proj",
                fileId = "proj:src/A.kt",
                name = "fn4",
                qualifiedName = "pkg.fn4",
                kind = "function",
                startLine = 1,
                endLine = 5,
                signature = "fun fn4(a: Int, b: Int, c: Int, d: Int)",
                cognitiveComplexity = 2,
                cyclomaticComplexity = 2
            )

            // Function with 5 parameters: warning violation (warnParams = 5)
            val node5 = GraphNode(
                nodeId = "proj:src/A.kt#fn5",
                projectId = "proj",
                fileId = "proj:src/A.kt",
                name = "fn5",
                qualifiedName = "pkg.fn5",
                kind = "function",
                startLine = 6,
                endLine = 10,
                signature = "fun fn5(a: Int, b: Int, c: Int, d: Int, e: Int)",
                cognitiveComplexity = 2,
                cyclomaticComplexity = 2
            )

            // Function with 7 parameters: HIGH severity violation (maxParams = 7)
            val node7 = GraphNode(
                nodeId = "proj:src/A.kt#fn7",
                projectId = "proj",
                fileId = "proj:src/A.kt",
                name = "fn7",
                qualifiedName = "pkg.fn7",
                kind = "function",
                startLine = 11,
                endLine = 15,
                signature = "fun fn7(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int)",
                cognitiveComplexity = 2,
                cyclomaticComplexity = 2
            )

            // Composable with 8 parameters: below composeWarnParams (10)
            val comp8 = GraphNode(
                nodeId = "proj:src/A.kt#comp8",
                projectId = "proj",
                fileId = "proj:src/A.kt",
                name = "comp8",
                qualifiedName = "pkg.comp8",
                kind = "composable",
                startLine = 16,
                endLine = 20,
                signature = "@Composable fun comp8(p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int, p8: Int)",
                cognitiveComplexity = 1,
                cyclomaticComplexity = 1
            )

            // Composable with 11 parameters: warning violation (composeWarnParams = 10)
            val comp11 = GraphNode(
                nodeId = "proj:src/A.kt#comp11",
                projectId = "proj",
                fileId = "proj:src/A.kt",
                name = "comp11",
                qualifiedName = "pkg.comp11",
                kind = "composable",
                startLine = 21,
                endLine = 25,
                signature = "@Composable fun comp11(p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int, p8: Int, p9: Int, p10: Int, p11: Int)",
                cognitiveComplexity = 1,
                cyclomaticComplexity = 1
            )

            // Data class: excluded from parameter checks even with 8 parameters
            val dataClass = GraphNode(
                nodeId = "proj:src/A.kt#UserData",
                projectId = "proj",
                fileId = "proj:src/A.kt",
                name = "UserData",
                qualifiedName = "pkg.UserData",
                kind = "class",
                startLine = 26,
                endLine = 30,
                signature = "data class UserData(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int, val f: Int, val g: Int, val h: Int)",
                cognitiveComplexity = 0,
                cyclomaticComplexity = 1
            )

            store.upsertNodesAndEdges("proj", listOf(node4, node5, node7, comp8, comp11, dataClass), emptyList())

            val auditor = ArchitectureAuditor(store)
            val paramViolations = auditor.checkParamCountViolations("proj")

            // Expect fn5 (WARNING), fn7 (HIGH), comp11 (WARNING) -> 3 violations
            assertEquals(3, paramViolations.size)

            val vFn5 = paramViolations.find { it.nodeId.endsWith("#fn5") }
            assertNotNull(vFn5)
            assertEquals("COMPLEXITY-002-PARAMS", vFn5.ruleId)
            assertEquals("WARNING", vFn5.severity)
            assertEquals(5, vFn5.paramCount)

            val vFn7 = paramViolations.find { it.nodeId.endsWith("#fn7") }
            assertNotNull(vFn7)
            assertEquals("HIGH", vFn7.severity)
            assertEquals(7, vFn7.paramCount)

            val vComp11 = paramViolations.find { it.nodeId.endsWith("#comp11") }
            assertNotNull(vComp11)
            assertEquals("WARNING", vComp11.severity)
            assertEquals(11, vComp11.paramCount)

            // Verify Data class and fn4 and comp8 are not present
            assertFalse(paramViolations.any { it.nodeId.endsWith("#UserData") })
            assertFalse(paramViolations.any { it.nodeId.endsWith("#fn4") })
            assertFalse(paramViolations.any { it.nodeId.endsWith("#comp8") })

            // Test MCP server integration and advisory isolation
            val server = AtlasMcpServer(workspaceRoot = ".", customDbPath = dbPath)
            val res = server.callTool("atlas_audit", buildJsonObject {
                put("action", "audit_complexity")
                put("project", "proj")
            })

            // All cognitive complexities are <= 2, so COMPLEXITY-001 has 0 violations
            assertEquals(0, res["total_violations"]?.jsonPrimitive?.intOrNull)
            // Advisory count reflects COMPLEXITY-002-PARAMS violations
            assertEquals(3, res["advisory_count"]?.jsonPrimitive?.intOrNull)
            // Passed is true because advisory violations do not block
            assertTrue(res["passed"]?.jsonPrimitive?.booleanOrNull ?: false)
        } finally {
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testSecondaryConstructorAndClassExclusion() {
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            // abstract class with 8 parameters -> excluded as class
            val abstractClass = GraphNode(
                nodeId = "proj:src/Base.kt#BaseService",
                projectId = "proj",
                fileId = "proj:src/Base.kt",
                name = "BaseService",
                qualifiedName = "pkg.BaseService",
                kind = "class",
                startLine = 1,
                endLine = 5,
                signature = "abstract class BaseService(p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int, p8: Int)",
                cognitiveComplexity = 0,
                cyclomaticComplexity = 1
            )
            // secondary constructor with 6 parameters -> warning violation
            val ctorNode = GraphNode(
                nodeId = "proj:src/Base.kt#Service.constructor",
                projectId = "proj",
                fileId = "proj:src/Base.kt",
                name = "constructor",
                qualifiedName = "pkg.Service.constructor",
                kind = "method",
                startLine = 10,
                endLine = 12,
                signature = "constructor(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int)",
                cognitiveComplexity = 1,
                cyclomaticComplexity = 1
            )
            store.upsertNodesAndEdges("proj", listOf(abstractClass, ctorNode), emptyList())

            val auditor = ArchitectureAuditor(store)
            val violations = auditor.checkParamCountViolations("proj")

            assertEquals(1, violations.size)
            assertEquals("constructor", violations.first().nodeId.substringAfterLast('.'))
            assertEquals(6, violations.first().paramCount)
            assertEquals("WARNING", violations.first().severity)
        } finally {
            PlatformFiles.delete(dbPath)
        }
    }
}
