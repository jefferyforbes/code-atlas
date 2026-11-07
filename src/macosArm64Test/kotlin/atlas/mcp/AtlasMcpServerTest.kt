package atlas.mcp

import atlas.util.PlatformFiles
import kotlin.random.Random
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.posix.getpid

@OptIn(ExperimentalForeignApi::class)
class AtlasMcpServerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testJsonParseErrorReturns32700() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            val invalidJsonStrings = listOf(
                """{"jsonrpc": "2.0", "id": 1, "method":""",
                """{""",
                """not a json payload""",
                """{"unclosed: 1"""
            )

            for (invalid in invalidJsonStrings) {
                val resp = server.processJsonRpcMessage(invalid)
                assertNotNull(resp, "Expected error response for invalid JSON: $invalid")
                val parsed = json.parseToJsonElement(resp).jsonObject
                assertEquals("2.0", parsed["jsonrpc"]?.jsonPrimitive?.content)
                assertEquals(JsonNull, parsed["id"])
                val error = parsed["error"]?.jsonObject
                assertNotNull(error)
                assertEquals(-32700, error["code"]?.jsonPrimitive?.int)
                assertEquals("Parse error", error["message"]?.jsonPrimitive?.content)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun testNonObjectRequestReturns32600() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            val nonObjects = listOf(
                """[1, 2, 3]""",
                """"just a string"""",
                """12345""",
                """true"""
            )

            for (payload in nonObjects) {
                val resp = server.processJsonRpcMessage(payload)
                assertNotNull(resp, "Expected error response for non-object JSON: $payload")
                val parsed = json.parseToJsonElement(resp).jsonObject
                assertEquals("2.0", parsed["jsonrpc"]?.jsonPrimitive?.content)
                assertEquals(JsonNull, parsed["id"])
                val error = parsed["error"]?.jsonObject
                assertNotNull(error)
                assertEquals(-32600, error["code"]?.jsonPrimitive?.int)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun testNonPrimitiveMethodReturns32600() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            // Non-primitive method with ID -> -32600 error
            val objMethod = server.processJsonRpcMessage("""{"jsonrpc": "2.0", "id": 10, "method": {}}""")
            assertNotNull(objMethod)
            val parsedObj = json.parseToJsonElement(objMethod).jsonObject
            assertEquals(10, parsedObj["id"]?.jsonPrimitive?.int)
            assertEquals(-32600, parsedObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)

            val arrayMethod = server.processJsonRpcMessage("""{"jsonrpc": "2.0", "id": 11, "method": ["ping"]}""")
            assertNotNull(arrayMethod)
            val parsedArr = json.parseToJsonElement(arrayMethod).jsonObject
            assertEquals(11, parsedArr["id"]?.jsonPrimitive?.int)
            assertEquals(-32600, parsedArr["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)

            // Non-primitive method notification without ID -> null
            val notif = server.processJsonRpcMessage("""{"jsonrpc": "2.0", "method": {}}""")
            assertNull(notif, "Malformed notification without id must return null")
        } finally {
            server.close()
        }
    }

    @Test
    fun testNonObjectParamsReturns32602() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            // Params as array
            val arrayParams = server.processJsonRpcMessage("""{"jsonrpc": "2.0", "id": 20, "method": "ping", "params": [1, 2]}""")
            assertNotNull(arrayParams)
            val parsedArr = json.parseToJsonElement(arrayParams).jsonObject
            assertEquals(20, parsedArr["id"]?.jsonPrimitive?.int)
            assertEquals(-32602, parsedArr["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)

            // Params as primitive string
            val strParams = server.processJsonRpcMessage("""{"jsonrpc": "2.0", "id": 21, "method": "ping", "params": "foo"}""")
            assertNotNull(strParams)
            val parsedStr = json.parseToJsonElement(strParams).jsonObject
            assertEquals(21, parsedStr["id"]?.jsonPrimitive?.int)
            assertEquals(-32602, parsedStr["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)

            // Notification with malformed params -> null
            val notif = server.processJsonRpcMessage("""{"jsonrpc": "2.0", "method": "ping", "params": [1]}""")
            assertNull(notif, "Notification with malformed params must return null")
        } finally {
            server.close()
        }
    }

    @Test
    fun testToolsCallInvalidParameterShapesReturns32602() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            // Tool name is an object
            val objName = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 30, "method": "tools/call", "params": {"name": {}, "arguments": {}}}"""
            )
            assertNotNull(objName)
            val parsedObjName = json.parseToJsonElement(objName).jsonObject
            assertEquals(30, parsedObjName["id"]?.jsonPrimitive?.int)
            assertEquals(-32602, parsedObjName["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)

            // Tool name is missing
            val missingName = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 31, "method": "tools/call", "params": {"arguments": {}}}"""
            )
            assertNotNull(missingName)
            val parsedMissingName = json.parseToJsonElement(missingName).jsonObject
            assertEquals(31, parsedMissingName["id"]?.jsonPrimitive?.int)
            assertEquals(-32602, parsedMissingName["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)

            // Tool arguments is a primitive string
            val strArgs = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 32, "method": "tools/call", "params": {"name": "atlas_query", "arguments": "invalid"}}"""
            )
            assertNotNull(strArgs)
            val parsedStrArgs = json.parseToJsonElement(strArgs).jsonObject
            assertEquals(32, parsedStrArgs["id"]?.jsonPrimitive?.int)
            assertEquals(-32602, parsedStrArgs["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)

            // Tool arguments is an array
            val arrArgs = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 33, "method": "tools/call", "params": {"name": "atlas_query", "arguments": [1, 2]}}"""
            )
            assertNotNull(arrArgs)
            val parsedArrArgs = json.parseToJsonElement(arrArgs).jsonObject
            assertEquals(33, parsedArrArgs["id"]?.jsonPrimitive?.int)
            assertEquals(-32602, parsedArrArgs["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)
        } finally {
            server.close()
        }
    }

    @Test
    fun testEmptyOrWhitespaceLinesReturnNull() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            assertNull(server.processJsonRpcMessage(""))
            assertNull(server.processJsonRpcMessage("   "))
            assertNull(server.processJsonRpcMessage("\t\n\r"))
        } finally {
            server.close()
        }
    }

    @Test
    fun testNotificationsNeverReturnResponse() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            // ping as notification -> null
            assertNull(server.processJsonRpcMessage("""{"jsonrpc": "2.0", "method": "ping"}"""))

            // initialize as notification -> null
            assertNull(server.processJsonRpcMessage("""{"jsonrpc": "2.0", "method": "initialize"}"""))

            // tools/list as notification -> null
            assertNull(server.processJsonRpcMessage("""{"jsonrpc": "2.0", "method": "tools/list"}"""))

            // tools/call as notification -> executes side effects and returns null
            assertNull(
                server.processJsonRpcMessage(
                    """{"jsonrpc": "2.0", "method": "tools/call", "params": {"name": "atlas_query", "arguments": {"action": "symbol", "target": "AtlasMcpServer"}}}"""
                )
            )

            // tools/call with invalid params as notification -> null
            assertNull(
                server.processJsonRpcMessage(
                    """{"jsonrpc": "2.0", "method": "tools/call", "params": {"name": 123}}"""
                )
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun testNonPrimitiveIdReturns32600() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            // ID as array -> -32600 with id: null
            val arrayIdResp = server.processJsonRpcMessage("""{"jsonrpc": "2.0", "id": [1, 2], "method": "ping"}""")
            assertNotNull(arrayIdResp)
            val parsedArr = json.parseToJsonElement(arrayIdResp).jsonObject
            assertEquals(JsonNull, parsedArr["id"])
            assertEquals(-32600, parsedArr["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)

            // ID as object -> -32600 with id: null
            val objIdResp = server.processJsonRpcMessage("""{"jsonrpc": "2.0", "id": {}, "method": "ping"}""")
            assertNotNull(objIdResp)
            val parsedObj = json.parseToJsonElement(objIdResp).jsonObject
            assertEquals(JsonNull, parsedObj["id"])
            assertEquals(-32600, parsedObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int)
        } finally {
            server.close()
        }
    }

    @Test
    fun testToolDefinitionsMatchStandardsAndExposeV061Capabilities() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            val listResp = server.processJsonRpcMessage("""{"jsonrpc": "2.0", "id": 40, "method": "tools/list"}""")
            assertNotNull(listResp)
            val parsed = json.parseToJsonElement(listResp).jsonObject
            val tools = parsed["result"]?.jsonObject?.get("tools")?.jsonArray
            assertNotNull(tools)

            val toolMap = tools.associateBy { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }
            assertTrue("atlas_query" in toolMap)
            assertTrue("atlas_audit" in toolMap)
            assertTrue("figma_audit" in toolMap)
            assertTrue("figma_scope" in toolMap)

            // Verify descriptions are <= 25 words
            for (toolElem in tools) {
                val tObj = toolElem.jsonObject
                val name = tObj["name"]?.jsonPrimitive?.content ?: ""
                val desc = tObj["description"]?.jsonPrimitive?.content ?: ""
                val wordCount = desc.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                assertTrue(wordCount in 1..25, "Tool '$name' description has $wordCount words (max 25)")

                // Verify parameter descriptions are <= 25 words
                val props = tObj["inputSchema"]?.jsonObject?.get("properties")?.jsonObject ?: continue
                for ((paramName, paramSchema) in props) {
                    val pDesc = paramSchema.jsonObject["description"]?.jsonPrimitive?.content ?: ""
                    val pWords = pDesc.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                    assertTrue(pWords in 1..25, "Param '$paramName' in '$name' has $pWords words (max 25)")
                }
            }

            // Verify atlas_query action enum includes "explore" and properties include "query"
            val queryProps = toolMap["atlas_query"]!!.jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
            val queryActions = queryProps["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("explore" in queryActions, "atlas_query action enum must include 'explore'")
            assertTrue("query" in queryProps, "atlas_query must declare 'query' parameter")

            // Verify atlas_audit action enum includes "architect_context" and properties include "depth" and "seeds"
            val auditProps = toolMap["atlas_audit"]!!.jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
            val auditActions = auditProps["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("architect_context" in auditActions, "atlas_audit action enum must include 'architect_context'")
            assertTrue("depth" in auditProps, "atlas_audit must declare 'depth' parameter")
            assertTrue("seeds" in auditProps, "atlas_audit must declare 'seeds' parameter")
        } finally {
            server.close()
        }
    }

    @Test
    fun testAtlasQueryExploreAndArchitectContextExecution() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            // 1. Test atlas_query action: explore with target
            val exploreResp1 = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 50, "method": "tools/call", "params": {"name": "atlas_query", "arguments": {"project": "test", "action": "explore", "target": "NonExistent"}}}"""
            )
            assertNotNull(exploreResp1)
            val parsedExplore1 = json.parseToJsonElement(exploreResp1).jsonObject
            assertEquals(50, parsedExplore1["id"]?.jsonPrimitive?.int)
            val exploreErr1 = parsedExplore1["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
            assertFalse(exploreErr1, "atlas_query explore with target must not return error")
            val exploreContent1 = parsedExplore1["result"]?.jsonObject?.get("content")?.jsonArray
            assertNotNull(exploreContent1)
            assertTrue(exploreContent1.size > 0)

            // 2. Test atlas_query action: explore with query alias
            val exploreResp2 = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 51, "method": "tools/call", "params": {"name": "atlas_query", "arguments": {"project": "test", "action": "explore", "query": "NonExistent"}}}"""
            )
            assertNotNull(exploreResp2)
            val parsedExplore2 = json.parseToJsonElement(exploreResp2).jsonObject
            assertEquals(51, parsedExplore2["id"]?.jsonPrimitive?.int)
            val exploreErr2 = parsedExplore2["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
            assertFalse(exploreErr2, "atlas_query explore with query alias must not return error")
            val exploreContent2 = parsedExplore2["result"]?.jsonObject?.get("content")?.jsonArray
            assertNotNull(exploreContent2)
            assertTrue(exploreContent2.size > 0)

            // 3. Test atlas_audit action: architect_context with targets array
            val contextResp1 = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 52, "method": "tools/call", "params": {"name": "atlas_audit", "arguments": {"project": "test", "action": "architect_context", "targets": ["NonExistent"]}}}"""
            )
            assertNotNull(contextResp1)
            val parsedContext1 = json.parseToJsonElement(contextResp1).jsonObject
            assertEquals(52, parsedContext1["id"]?.jsonPrimitive?.int)
            val contextErr1 = parsedContext1["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
            assertFalse(contextErr1, "atlas_audit architect_context with targets must not return error")
            val contextContent1 = parsedContext1["result"]?.jsonObject?.get("content")?.jsonArray
            assertNotNull(contextContent1)
            assertTrue(contextContent1.size > 0)

            // 4. Test atlas_audit action: architect_context with singular target
            val contextResp2 = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 53, "method": "tools/call", "params": {"name": "atlas_audit", "arguments": {"project": "test", "action": "architect_context", "target": "NonExistent"}}}"""
            )
            assertNotNull(contextResp2)
            val parsedContext2 = json.parseToJsonElement(contextResp2).jsonObject
            assertEquals(53, parsedContext2["id"]?.jsonPrimitive?.int)
            val contextErr2 = parsedContext2["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
            assertFalse(contextErr2, "atlas_audit architect_context with singular target must not return error")

            // 5. Test atlas_audit action: architect_context with seeds alias array
            val contextResp3 = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 54, "method": "tools/call", "params": {"name": "atlas_audit", "arguments": {"project": "test", "action": "architect_context", "seeds": ["NonExistent"]}}}"""
            )
            assertNotNull(contextResp3)
            val parsedContext3 = json.parseToJsonElement(contextResp3).jsonObject
            assertEquals(54, parsedContext3["id"]?.jsonPrimitive?.int)
            val contextErr3 = parsedContext3["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
            assertFalse(contextErr3, "atlas_audit architect_context with seeds alias must not return error")

            // 6. Test atlas_audit action: architect_context with empty seeds (fast safe return)
            val contextResp4 = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 55, "method": "tools/call", "params": {"name": "atlas_audit", "arguments": {"project": "test", "action": "architect_context"}}}"""
            )
            assertNotNull(contextResp4)
            val parsedContext4 = json.parseToJsonElement(contextResp4).jsonObject
            assertEquals(55, parsedContext4["id"]?.jsonPrimitive?.int)
            val contextErr4 = parsedContext4["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
            assertFalse(contextErr4, "atlas_audit architect_context with empty seeds must not return error")
        } finally {
            server.close()
        }
    }

    @Test
    fun `partial ratchet arguments still populate remaining ratchets from complexity baseline`() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            val resp = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 60, "method": "tools/call", "params": {"name": "atlas_audit", "arguments": {"project": "code-atlas", "action": "audit_complexity", "ratchet": 5}}}"""
            )
            assertNotNull(resp)
            val parsed = json.parseToJsonElement(resp).jsonObject
            val text = parsed["result"]?.jsonObject?.get("content")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
            assertNotNull(text)
            val payload = json.parseToJsonElement(text).jsonObject
            assertEquals(5, payload["ratchet"]?.jsonPrimitive?.int)
            assertEquals(0, payload["high_ratchet"]?.jsonPrimitive?.int)
            assertEquals(0, payload["param_high_ratchet"]?.jsonPrimitive?.int)
        } finally {
            server.close()
        }
    }

    @Test
    fun testLruProjectServerEvictionClosesStoreAndRetains16Servers() {
        val pid = getpid()
        val rnd = Random.nextInt(100000, 999999)
        val rootDir = "/tmp/atlas_mcp_lru_root_${pid}_$rnd"
        PlatformFiles.mkdirs(rootDir)
        val server = AtlasMcpServer(workspaceRoot = rootDir)

        try {
            val projectDirs = (0..16).map { i ->
                val p = "$rootDir/project_$i"
                PlatformFiles.mkdirs(p)
                p
            }

            for (i in 0..15) {
                server.resolveServerForProject("proj_$i", explicitWorkspace = projectDirs[i])
            }
            assertEquals(16, server.projectServers.size, "Should hold 16 servers")

            val firstNormalized = PlatformFiles.canonicalPath(projectDirs[0]).replace('\\', '/').trimEnd('/')
            val firstServer = server.projectServers[firstNormalized]
            assertNotNull(firstServer, "Server 0 should be present in cache")

            server.resolveServerForProject("proj_16", explicitWorkspace = projectDirs[16])
            assertEquals(16, server.projectServers.size, "Cache should remain capped at 16")
            assertFalse(server.projectServers.containsKey(firstNormalized), "Server 0 must be evicted")

            val queryResult = runCatching { firstServer.store.getStats("proj_0") }
            assertTrue(queryResult.isFailure, "Evicted server's store must be closed")

            val resolvedRoot = server.resolveServerForProject("root_proj", explicitWorkspace = rootDir)
            assertEquals(server, resolvedRoot, "Resolving root server must return root instance")
            assertFalse(server.projectServers.containsKey(PlatformFiles.canonicalPath(rootDir)), "Root server is never stored in projectServers")
        } finally {
            server.close()
            PlatformFiles.delete(rootDir)
        }
    }

    @Test
    fun testHandleQueryGodNodesAndSearchHonorLimit() {
        val pid = getpid()
        val rnd = Random.nextInt(100000, 999999)
        val tmpDir = "/tmp/atlas_mcp_query_limit_${pid}_$rnd"
        PlatformFiles.mkdirs(tmpDir)
        val server = AtlasMcpServer(workspaceRoot = tmpDir)

        try {
            val ktFile = """
                package sample
                class NodeA { fun a() {} }
                class NodeB { fun b() {} }
                class NodeC { fun c() {} }
                class NodeD { fun d() {} }
                class NodeE { fun e() {} }
            """.trimIndent()
            PlatformFiles.writeText("$tmpDir/Nodes.kt", ktFile)
            server.indexer.indexWorkspace("limit_proj")

            val searchRes = server.callTool("atlas_query", buildJsonObject {
                put("project", "limit_proj")
                put("action", "search")
                put("query", "Node")
                put("limit", 2)
            })
            val searchMatches = (searchRes["results"] ?: searchRes["matches"])?.jsonArray
            assertNotNull(searchMatches, "search result must contain results array")
            assertTrue(searchMatches.size in 1..2, "search must honor limit=2 (actual: ${searchMatches.size})")

            val godRes = server.callTool("atlas_query", buildJsonObject {
                put("project", "limit_proj")
                put("action", "god_nodes")
                put("limit", 3)
            })
            val godNodes = godRes["god_nodes"]?.jsonArray
            assertNotNull(godNodes, "god_nodes result must contain god_nodes array")
            assertTrue(godNodes.size <= 3, "god_nodes must honor limit=3 (actual: ${godNodes.size})")
        } finally {
            server.close()
            PlatformFiles.delete(tmpDir)
        }
    }

    @Test
    fun testAtlasAuditComposeScopeAndSynthesizeComposeScopeBothSucceed() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            val res1 = server.callTool("atlas_audit", buildJsonObject {
                put("project", "code-atlas")
                put("action", "synthesize_compose_scope")
            })
            assertFalse(res1.containsKey("error"), "synthesize_compose_scope must succeed")
            val hasKeys1 = res1.containsKey("target_files") || res1.containsKey("collision_risks") || res1.containsKey("safe_for_parallel_dispatch")
            assertTrue(hasKeys1, "synthesize_compose_scope must return compose payload")

            val res2 = server.callTool("atlas_audit", buildJsonObject {
                put("project", "code-atlas")
                put("action", "compose_scope")
            })
            assertFalse(res2.containsKey("error"), "compose_scope must succeed")
            val hasKeys2 = res2.containsKey("target_files") || res2.containsKey("collision_risks") || res2.containsKey("safe_for_parallel_dispatch")
            assertTrue(hasKeys2, "compose_scope must return compose payload")
        } finally {
            server.close()
        }
    }

    @Test
    fun testSpilloverPreservesErrorClassification() {
        val pid = getpid()
        val rnd = Random.nextInt(100000, 999999)
        val tmpDir = "/tmp/atlas_mcp_spill_err_${pid}_$rnd"
        PlatformFiles.mkdirs(tmpDir)
        val server = AtlasMcpServer(workspaceRoot = tmpDir)

        try {
            val largeErrorMessage = "Custom error: " + "E".repeat(35000)
            val errorPayload = buildJsonObject {
                put("error", largeErrorMessage)
                put("detail", "High blast radius failure")
            }

            val spilled = server.transport.enforceSpillover("err_proj", errorPayload)
            val isSpillover = spilled["spillover"]?.jsonPrimitive?.booleanOrNull ?: false
            assertTrue(isSpillover, "Payload >32KB must trigger spillover")
            assertTrue(spilled.containsKey("error"), "Spillover envelope must retain 'error' key")
            val preservedErr = spilled["error"]?.jsonPrimitive?.content ?: ""
            assertTrue(preservedErr.isNotEmpty(), "Error message must be preserved")

            val toolResp = server.transport.buildToolCallSuccess(JsonPrimitive(101), spilled)
            val isError = toolResp["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
            assertTrue(isError, "Tool response envelope must retain isError=true for spilled error")
        } finally {
            server.close()
            PlatformFiles.delete(tmpDir)
        }
    }

    @Test
    fun testJsonToolResponseWithTextKeyIsNotStripped() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            val normalJsonWithText = buildJsonObject {
                put("text", "This is human readable advice")
                put("score", 95)
                put("recommendation", "Refactor module")
            }
            val wrappedNormal = server.transport.buildToolCallSuccess(JsonPrimitive(201), normalJsonWithText)
            val normalContent = wrappedNormal["result"]?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
            assertNotNull(normalContent)
            val textContent1 = normalContent["text"]?.jsonPrimitive?.content ?: ""
            val hasFullJson = textContent1.contains("\"score\": 95") || textContent1.contains("\"score\":95")
            assertTrue(hasFullJson, "Normal JSON output must retain entire JSON structure and not be unwrapped to raw text")
            assertTrue(textContent1.contains("This is human readable advice"), "JSON string must include text field")

            val rawMarkdownOutput = buildJsonObject {
                put("text", "### CodeGraph Explore Context\n- File: BasketScreen.kt")
                put("_mcp_raw_text", true)
            }
            val wrappedRaw = server.transport.buildToolCallSuccess(JsonPrimitive(202), rawMarkdownOutput)
            val rawContent = wrappedRaw["result"]?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
            assertNotNull(rawContent)
            val textContent2 = rawContent["text"]?.jsonPrimitive?.content ?: ""
            assertEquals("### CodeGraph Explore Context\n- File: BasketScreen.kt", textContent2, "Raw text marker must unwrap text content directly without JSON encoding")
        } finally {
            server.close()
        }
    }
}
