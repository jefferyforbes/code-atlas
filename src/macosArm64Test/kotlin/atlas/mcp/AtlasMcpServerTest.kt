package atlas.mcp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

            // Verify atlas_query action enum includes "explore"
            val queryProps = toolMap["atlas_query"]!!.jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
            val queryActions = queryProps["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("explore" in queryActions, "atlas_query action enum must include 'explore'")

            // Verify atlas_audit action enum includes "architect_context" and properties include "depth"
            val auditProps = toolMap["atlas_audit"]!!.jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
            val auditActions = auditProps["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("architect_context" in auditActions, "atlas_audit action enum must include 'architect_context'")
            assertTrue("depth" in auditProps, "atlas_audit must declare 'depth' parameter")
        } finally {
            server.close()
        }
    }

    @Test
    fun testAtlasQueryExploreAndArchitectContextExecution() {
        val server = AtlasMcpServer(workspaceRoot = ".")
        try {
            // Test atlas_query action: explore
            val exploreResp = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 50, "method": "tools/call", "params": {"name": "atlas_query", "arguments": {"project": "test", "action": "explore", "target": "NonExistent"}}}"""
            )
            assertNotNull(exploreResp)
            val parsedExplore = json.parseToJsonElement(exploreResp).jsonObject
            assertEquals(50, parsedExplore["id"]?.jsonPrimitive?.int)
            val exploreErr = parsedExplore["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
            assertFalse(exploreErr, "atlas_query explore must not return error")
            val exploreContent = parsedExplore["result"]?.jsonObject?.get("content")?.jsonArray
            assertNotNull(exploreContent)
            assertTrue(exploreContent.size > 0)

            // Test atlas_audit action: architect_context
            val contextResp = server.processJsonRpcMessage(
                """{"jsonrpc": "2.0", "id": 51, "method": "tools/call", "params": {"name": "atlas_audit", "arguments": {"project": "test", "action": "architect_context", "targets": ["NonExistent"]}}}"""
            )
            assertNotNull(contextResp)
            val parsedContext = json.parseToJsonElement(contextResp).jsonObject
            assertEquals(51, parsedContext["id"]?.jsonPrimitive?.int)
            val contextErr = parsedContext["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
            assertFalse(contextErr, "atlas_audit architect_context must not return error")
            val contextContent = parsedContext["result"]?.jsonObject?.get("content")?.jsonArray
            assertNotNull(contextContent)
            assertTrue(contextContent.size > 0)
        } finally {
            server.close()
        }
    }
}
