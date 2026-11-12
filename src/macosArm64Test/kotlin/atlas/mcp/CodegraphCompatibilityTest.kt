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
import kotlin.random.Random
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
            assertEquals(13, tools.size, "Must expose strictly 13 tools when enableCodegraphTools=true")

            val toolNames = tools.map { it["name"]!!.jsonPrimitive.content }.toSet()
            val expectedNames = setOf(
                "atlas_query", "atlas_audit", "figma_audit", "figma_scope", "atlas_outline",
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

    private fun withShopWorkspace(block: (AtlasMcpServer) -> Unit) {
        val pid = getpid()
        val rnd = Random.nextInt(100000, 999999)
        val tmpDir = "/tmp/atlas_cg_exec_test_${pid}_$rnd"
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
            block(server)
        } finally {
            server.close()
            PlatformFiles.delete(dbPath)
            PlatformFiles.delete("$srcDir/BasketScreen.kt")
        }
    }

    @Test
    fun testCodegraphExploreExecution() = withShopWorkspace { server ->
        val exploreRes = server.callTool("codegraph_explore", buildJsonObject {
            put("query", "calculateBasketTotal BasketViewModel")
            put("project", "shop")
        })
        val exploreText = exploreRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(exploreText.contains("BasketScreen.kt"), "Explore must include file header")
        assertTrue(exploreText.contains("calculateBasketTotal"), "Explore must include symbol source")
        val hasRel = exploreText.contains("Call Relationships") || exploreText.contains("calls")
        assertTrue(hasRel, "Explore should identify relationships")
    }

    @Test
    fun testCodegraphNodeSymbolMode() = withShopWorkspace { server ->
        val nodeSymRes = server.callTool("codegraph_node", buildJsonObject {
            put("symbol", "calculateBasketTotal")
            put("includeCode", true)
            put("project", "shop")
        })
        val nodeSymText = nodeSymRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(nodeSymText.contains("calculateBasketTotal"), "Node symbol must show symbol name")
        assertTrue(nodeSymText.contains("features/basket/BasketScreen.kt"), "Node symbol must show location")
    }

    @Test
    fun testCodegraphNodeFileReadingMode() = withShopWorkspace { server ->
        val nodeFileRes = server.callTool("codegraph_node", buildJsonObject {
            put("file", "BasketScreen.kt")
            put("offset", 1)
            put("limit", 15)
            put("project", "shop")
        })
        val nodeFileText = nodeFileRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(nodeFileText.contains("BasketPriceCalculator"), "Node file mode must read file source")
        assertTrue(nodeFileText.contains("\t"), "Node file mode must include tab separated line numbers")
    }

    @Test
    fun testCodegraphSearchExecution() = withShopWorkspace { server ->
        val searchRes = server.callTool("codegraph_search", buildJsonObject {
            put("query", "Basket")
            put("project", "shop")
        })
        val searchText = searchRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(searchText.contains("BasketPriceCalculator"), "Search must return matching symbols")
    }

    @Test
    fun testCodegraphCallersExecution() = withShopWorkspace { server ->
        val callersRes = server.callTool("codegraph_callers", buildJsonObject {
            put("symbol", "calculateBasketTotal")
            put("project", "shop")
        })
        val callersText = callersRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(callersText.contains("Callers of `calculateBasketTotal`"), "Callers must have header")
    }

    @Test
    fun testCodegraphCalleesExecution() = withShopWorkspace { server ->
        val calleesRes = server.callTool("codegraph_callees", buildJsonObject {
            put("symbol", "calculateBasketTotal")
            put("project", "shop")
        })
        val calleesText = calleesRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(calleesText.contains("Callees of `calculateBasketTotal`"), "Callees must have header")
    }

    @Test
    fun testCodegraphImpactExecution() = withShopWorkspace { server ->
        val impactRes = server.callTool("codegraph_impact", buildJsonObject {
            put("symbol", "calculateBasketTotal")
            put("project", "shop")
        })
        val impactText = impactRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(impactText.contains("Impact Blast Radius"), "Impact must have blast radius header")
    }

    @Test
    fun testCodegraphStatusExecution() = withShopWorkspace { server ->
        val statusRes = server.callTool("codegraph_status", buildJsonObject {
            put("project", "shop")
        })
        val statusText = statusRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(statusText.contains("Atlas Index Status"), "Status must report status")
        assertTrue(statusText.contains("Indexed Files:"), "Status must report indexed files")
        assertTrue(!statusText.contains("Indexed Files: 0"), "Status must report non-zero indexed files")
    }

    @Test
    fun testCodegraphFilesExecution() = withShopWorkspace { server ->
        val filesRes = server.callTool("codegraph_files", buildJsonObject {
            put("project", "shop")
        })
        val filesText = filesRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(filesText.contains("BasketScreen.kt"), "Files must report BasketScreen.kt")
    }

    @Test
    fun testCodegraphExploreJsonRpcReturnsCleanMarkdown() = withShopWorkspace { server ->
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
    }

    @Test
    fun testSymbolNotFoundSuggestsNearestCandidates() = withShopWorkspace { server ->
        val nodeRes = server.callTool("codegraph_node", buildJsonObject {
            put("symbol", "BasketCalculatorUnknown")
            put("project", "shop")
        })
        val nodeText = nodeRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(nodeText.contains("Symbol \"BasketCalculatorUnknown\" not found in project 'shop'."), "Must report not found")
        assertTrue(nodeText.contains("Did you mean:"), "Must suggest nearest candidates")
        assertTrue(nodeText.contains("BasketPriceCalculator"), "Must include candidate BasketPriceCalculator")

        val callersRes = server.callTool("codegraph_callers", buildJsonObject {
            put("symbol", "NonExistentFunction")
            put("project", "shop")
        })
        val callersText = callersRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(callersText.contains("Symbol \"NonExistentFunction\" not found in project 'shop'."), "Callers must report 404 on unindexed symbol")

        val impactRes = server.callTool("codegraph_impact", buildJsonObject {
            put("symbol", "NonExistentFunction")
            put("project", "shop")
        })
        val impactText = impactRes["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(impactText.contains("Symbol \"NonExistentFunction\" not found in project 'shop'."), "Impact must report 404 on unindexed symbol")
    }

    @Test
    fun testDumpMcpSchemasIntegration() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_dump_schemas_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        try {
            val args = atlas.cli.CliArgs.parse(arrayOf("dump-mcp-schemas", "--out", tmpDir))
            assertNotNull(args)
            assertEquals("dump-mcp-schemas", args.command)
            assertEquals(tmpDir, args.outPath)

            val server = AtlasMcpServer(workspaceRoot = tmpDir, enableCodegraphTools = true)
            try {
                // Call dump-mcp-schemas logic
                val exitCode = atlas.cli.AtlasCli.run(arrayOf("dump-mcp-schemas", "--out", tmpDir))
                assertEquals(0, exitCode)

                for (name in listOf("atlas_query", "atlas_audit", "atlas_outline", "figma_audit", "figma_scope", "codegraph_files")) {
                    assertTrue(PlatformFiles.exists("$tmpDir/$name.json"), "Tool schema $name.json must exist in dumped directory")
                    val content = PlatformFiles.readText("$tmpDir/$name.json")
                    assertTrue(content.contains("\"name\": \"$name\""), "$name.json must define tool name")
                    assertTrue(content.contains("\"parameters\""), "$name.json must define parameters")
                }
            } finally {
                server.close()
            }
        } finally {
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }
}
