package atlas.mcp

import atlas.db.SqliteGraphStore
import atlas.engine.WorkspaceIndexer
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid

/**
 * Verification test suite for Tescode / CodeGraph drop-in compatibility.
 */
class CodegraphCompatibilityTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testToolDefinitionsAndWordCounts() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_cg_compat_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val server = AtlasMcpServer(workspaceRoot = tmpDir, customDbPath = dbPath, enableCodegraphTools = true)
        try {
            val tools = server.toolDefinitions()
            assertEquals(12, tools.size, "Must expose strictly 12 tools when enableCodegraphTools=true")

            val toolNames = tools.map { it["name"]!!.jsonPrimitive.content }.toSet()
            val expectedNames = setOf(
                "atlas_query", "atlas_audit", "figma_audit", "figma_scope",
                "codegraph_explore", "codegraph_node", "codegraph_search",
                "codegraph_callers", "codegraph_callees", "codegraph_impact",
                "codegraph_status", "codegraph_files"
            )
            assertEquals(expectedNames, toolNames)

            // Verify parameter docs <= 25 words
            for (tool in tools) {
                val tName = tool["name"]!!.jsonPrimitive.content
                val props = tool["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
                for ((paramName, propDef) in props) {
                    val desc = propDef.jsonObject["description"]?.jsonPrimitive?.content ?: ""
                    val words = desc.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
                    assertTrue(
                        words.size <= 25,
                        "Param '$tName.$paramName' exceeds 25 words: '$desc'"
                    )
                }
            }
        } finally {
            server.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testCodegraphToolsExecutionAndFormatting() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_cg_exec_test_$pid"
        val srcDir = "$tmpDir/features/basket"
        PlatformFiles.mkdirs(srcDir)
        val dbPath = "$tmpDir/atlas.db"

        val basketKt = """
            package features.basket

            class BasketPriceCalculator {
                fun compute(subtotal: Double): Double {
                    return subtotal * 1.2
                }
            }

            fun calculateBasketTotal(items: List<Double>): Double {
                val calc = BasketPriceCalculator()
                return calc.compute(items.sum())
            }

            class BasketViewModel {
                fun onRefresh() {
                    val total = calculateBasketTotal(listOf(10.0, 20.0))
                }
            }
        """.trimIndent()
        PlatformFiles.writeText("$srcDir/BasketScreen.kt", basketKt)

        val server = AtlasMcpServer(workspaceRoot = tmpDir, customDbPath = dbPath, enableCodegraphTools = true)
        try {
            val indexer = WorkspaceIndexer(tmpDir, server.store)
            indexer.indexWorkspace("shop")

            // 1. Test codegraph_explore
            val exploreRes = server.callTool("codegraph_explore", buildJsonObject {
                put("query", "calculateBasketTotal BasketViewModel")
                put("project", "shop")
            })
            val exploreText = exploreRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(exploreText.contains("BasketScreen.kt"), "Explore must include file header")
            assertTrue(exploreText.contains("calculateBasketTotal"), "Explore must include symbol source")
            assertTrue(exploreText.contains("Call Relationships") || exploreText.contains("calls"), "Explore should identify relationships")

            // 2. Test codegraph_node - symbol mode
            val nodeSymRes = server.callTool("codegraph_node", buildJsonObject {
                put("symbol", "calculateBasketTotal")
                put("includeCode", true)
                put("project", "shop")
            })
            val nodeSymText = nodeSymRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(nodeSymText.contains("calculateBasketTotal"), "Node symbol must show symbol name")
            assertTrue(nodeSymText.contains("features/basket/BasketScreen.kt"), "Node symbol must show location")

            // 3. Test codegraph_node - file reading mode (Read tool replacement)
            val nodeFileRes = server.callTool("codegraph_node", buildJsonObject {
                put("file", "BasketScreen.kt")
                put("offset", 1)
                put("limit", 15)
                put("project", "shop")
            })
            val nodeFileText = nodeFileRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(nodeFileText.contains("BasketPriceCalculator"), "Node file mode must read file source")
            assertTrue(nodeFileText.contains("\t"), "Node file mode must include tab separated line numbers")

            // 4. Test codegraph_search
            val searchRes = server.callTool("codegraph_search", buildJsonObject {
                put("query", "Basket")
                put("project", "shop")
            })
            val searchText = searchRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(searchText.contains("BasketPriceCalculator"), "Search must return matching symbols")

            // 5. Test codegraph_callers
            val callersRes = server.callTool("codegraph_callers", buildJsonObject {
                put("symbol", "calculateBasketTotal")
                put("project", "shop")
            })
            val callersText = callersRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(callersText.contains("Callers of `calculateBasketTotal`"), "Callers must have header")

            // 6. Test codegraph_callees
            val calleesRes = server.callTool("codegraph_callees", buildJsonObject {
                put("symbol", "calculateBasketTotal")
                put("project", "shop")
            })
            val calleesText = calleesRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(calleesText.contains("Callees of `calculateBasketTotal`"), "Callees must have header")

            // 7. Test codegraph_impact
            val impactRes = server.callTool("codegraph_impact", buildJsonObject {
                put("symbol", "calculateBasketTotal")
                put("project", "shop")
            })
            val impactText = impactRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(impactText.contains("Impact Blast Radius"), "Impact must have blast radius header")

            // 8. Test codegraph_status
            val statusRes = server.callTool("codegraph_status", buildJsonObject {
                put("project", "shop")
            })
            val statusText = statusRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(statusText.contains("Atlas Index Status"), "Status must report status")
            assertTrue(statusText.contains("Indexed Files:") && !statusText.contains("Indexed Files: 0"), "Status must report indexed files")

            // 9. Test codegraph_files
            val filesRes = server.callTool("codegraph_files", buildJsonObject {
                put("project", "shop")
            })
            val filesText = filesRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(filesText.contains("BasketScreen.kt"), "Files must report BasketScreen.kt")

            // 10. Test JSON-RPC 2.0 stdio handling returns clean text content
            val rpcReq = """{"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"codegraph_explore","arguments":{"query":"calculateBasketTotal","project":"shop"}}}"""
            val rpcResp = server.processJsonRpcMessage(rpcReq)
            assertNotNull(rpcResp)
            val parsedResp = json.parseToJsonElement(rpcResp).jsonObject
            val contentArr = parsedResp["result"]!!.jsonObject["content"]!!.jsonArray
            assertEquals(1, contentArr.size)
            val firstItem = contentArr[0].jsonObject
            assertEquals("text", firstItem["type"]!!.jsonPrimitive.content)
            val textContent = firstItem["text"]!!.jsonPrimitive.content
            assertTrue(textContent.startsWith("### CodeGraph Explore Context"), "JSON-RPC response text must be clean Markdown")
        } finally {
            server.close()
            PlatformFiles.delete(dbPath)
        }
    }
}
