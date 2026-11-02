package atlas.mcp

import atlas.util.PlatformFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import platform.posix.fflush
import platform.posix.stdout
import platform.posix.time

/**
 * JSON-RPC 2.0 stdio transport and 32KB artifact spillover writer for [AtlasMcpServer].
 */
@OptIn(ExperimentalForeignApi::class)
internal class McpJsonRpcTransport(
    private val server: AtlasMcpServer,
    private val json: Json
) {
    companion object {
        private var spilloverSeq = 0L
    }

    private class JsonRpcException(
        val code: Int,
        override val message: String,
        val id: JsonPrimitive? = null
    ) : Exception(message)

    private class JsonRpcRequest(
        val id: JsonPrimitive?,
        val hasId: Boolean,
        val method: String,
        val params: JsonObject
    )

    fun enforceSpillover(projectId: String, payload: JsonObject): JsonObject {
        val rawJson = json.encodeToString(payload)
        val byteLen = rawJson.encodeToByteArray().size
        if (byteLen <= AtlasMcpServer.SPILLOVER_THRESHOLD_BYTES) {
            return payload
        }

        val ts = time(null) * 1000L
        val seq = ++spilloverSeq
        val safeProject = PlatformFiles.sanitizeFileName(projectId)
        val spillFile = "${server.artifactsDir}/spillover_${safeProject}_${ts}_$seq.json"
        PlatformFiles.writeSecureText(spillFile, rawJson)

        val gods = server.store.getGodNodes(projectId, topN = 5).map { it.name }
        return buildJsonObject {
            put("spillover", true)
            put("byte_length", byteLen)
            put("threshold_bytes", AtlasMcpServer.SPILLOVER_THRESHOLD_BYTES)
            put("artifact_uri", spillFile)
            put("top_central_nodes", json.encodeToJsonElement(gods))
            put("summary", "Payload ($byteLen bytes) exceeded 32KB ceiling and was written to $spillFile.")
        }
    }

    fun processJsonRpcMessage(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        return try {
            val req = parseJsonRpcRequest(trimmed) ?: return null
            dispatchJsonRpcMethod(req)
        } catch (e: JsonRpcException) {
            jsonRpcError(e.id, e.code, e.message)
        } catch (e: Throwable) {
            jsonRpcError(null, -32603, "Internal error: ${e.message ?: "Unexpected error"}")
        }
    }

    fun serveStdio() {
        while (true) {
            val line = readNextStdioLine() ?: break
            handleStdioLine(line)
        }
    }

    private fun jsonRpcError(id: JsonPrimitive?, code: Int, message: String): String {
        return json.encodeToString(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        })
    }

    private fun jsonRpcResult(id: JsonPrimitive?, resultBuilder: JsonObjectBuilder.() -> Unit): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result", resultBuilder)
        }
    }

    private fun validateReqId(req: JsonObject): Pair<JsonPrimitive?, Boolean> {
        val rawId = req["id"]
        val hasId = req.containsKey("id")
        if (hasId && rawId !is JsonPrimitive) {
            throw JsonRpcException(-32600, "Invalid Request: 'id' must be a primitive string, number, or null")
        }
        return Pair(rawId as? JsonPrimitive, hasId)
    }

    private fun extractMethod(req: JsonObject, hasId: Boolean, reqId: JsonPrimitive?): String? {
        val methodPrimitive = req["method"] as? JsonPrimitive
        if (methodPrimitive != null && methodPrimitive.isString) {
            return methodPrimitive.content
        }
        if (!hasId) return null
        throw JsonRpcException(-32600, "Invalid Request: 'method' must be a string", reqId)
    }

    private fun extractParams(req: JsonObject, hasId: Boolean, reqId: JsonPrimitive?): JsonObject? {
        val params = req["params"] ?: return buildJsonObject {}
        if (params is JsonObject) return params
        if (!hasId) return null
        throw JsonRpcException(-32602, "Invalid params: 'params' must be an object", reqId)
    }

    private fun parseJsonRpcRequest(trimmed: String): JsonRpcRequest? {
        val reqElement = try {
            json.parseToJsonElement(trimmed)
        } catch (_: Throwable) {
            throw JsonRpcException(-32700, "Parse error")
        }
        val req = reqElement as? JsonObject
            ?: throw JsonRpcException(-32600, "Invalid Request: expected JSON object")

        val (reqId, hasId) = validateReqId(req)
        val method = extractMethod(req, hasId, reqId) ?: return null
        val params = extractParams(req, hasId, reqId) ?: return null

        return JsonRpcRequest(reqId, hasId, method, params)
    }

    private fun handleInitialize(reqId: JsonPrimitive?): JsonObject {
        return jsonRpcResult(reqId) {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {
                putJsonObject("tools") {}
            }
            putJsonObject("serverInfo") {
                put("name", "atlas")
                put("version", AtlasMcpServer.VERSION)
            }
        }
    }

    private fun handleToolsList(reqId: JsonPrimitive?): JsonObject {
        return jsonRpcResult(reqId) {
            put("tools", json.encodeToJsonElement(server.toolDefinitions()))
        }
    }

    private fun buildToolCallSuccess(reqId: JsonPrimitive?, out: JsonObject): JsonObject {
        val isToolErr = out.containsKey("error")
        return jsonRpcResult(reqId) {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    val rawText = out["text"]?.jsonPrimitive?.contentOrNull
                    put("text", rawText ?: json.encodeToString(out))
                }
            }
            if (isToolErr) {
                put("isError", true)
            }
        }
    }

    private fun buildToolCallFailure(reqId: JsonPrimitive?, e: Throwable): JsonObject {
        return jsonRpcResult(reqId) {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", json.encodeToString(buildJsonObject {
                        put("error", e.message ?: "Unknown error")
                    }))
                }
            }
            put("isError", true)
        }
    }

    private fun extractToolName(params: JsonObject): String? {
        val primitive = params["name"] as? JsonPrimitive
        return if (primitive != null && primitive.isString) primitive.content else null
    }

    private fun extractToolArgs(params: JsonObject): Pair<JsonObject?, Boolean> {
        val args = params["arguments"] ?: return Pair(buildJsonObject {}, true)
        val obj = args as? JsonObject
        return Pair(obj, obj != null)
    }

    private fun executeToolCall(tName: String, tArgs: JsonObject, reqId: JsonPrimitive?): String {
        return try {
            val out = server.callTool(tName, tArgs)
            json.encodeToString(buildToolCallSuccess(reqId, out))
        } catch (e: Throwable) {
            json.encodeToString(buildToolCallFailure(reqId, e))
        }
    }

    private fun handleToolsCall(reqId: JsonPrimitive?, params: JsonObject, hasId: Boolean): String? {
        val tName = extractToolName(params)
        if (tName == null) {
            if (!hasId) return null
            return jsonRpcError(reqId, -32602, "Invalid params: 'name' must be a string")
        }

        val (tArgs, isValidArgs) = extractToolArgs(params)
        if (!isValidArgs || tArgs == null) {
            if (!hasId) return null
            return jsonRpcError(reqId, -32602, "Invalid params: 'arguments' must be an object")
        }

        if (!hasId) {
            try { server.callTool(tName, tArgs) } catch (_: Throwable) {}
            return null
        }

        return executeToolCall(tName, tArgs, reqId)
    }

    private fun dispatchJsonRpcMethod(req: JsonRpcRequest): String? {
        if (!req.hasId) {
            if (req.method == "tools/call") {
                handleToolsCall(null, req.params, hasId = false)
            }
            return null
        }

        return when (req.method) {
            "initialize" -> json.encodeToString(handleInitialize(req.id))
            "ping" -> json.encodeToString(jsonRpcResult(req.id) {})
            "notifications/initialized", "notifications/cancelled" -> null
            "tools/list" -> json.encodeToString(handleToolsList(req.id))
            "tools/call" -> handleToolsCall(req.id, req.params, hasId = true)
            else -> jsonRpcError(req.id, -32601, "Method not found: ${req.method}")
        }
    }

    private fun readNextStdioLine(): String? = try {
        readlnOrNull()
    } catch (_: Throwable) {
        null
    }

    private fun handleStdioLine(line: String) {
        try {
            val resp = processJsonRpcMessage(line)
            if (resp != null) {
                println(resp)
                fflush(stdout)
            }
        } catch (e: Throwable) {
            emitStdioFallbackError(e)
        }
    }

    private fun emitStdioFallbackError(e: Throwable) {
        try {
            val err = json.encodeToString(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", JsonNull)
                putJsonObject("error") {
                    put("code", -32603)
                    put("message", "Internal error: ${e.message ?: "Unexpected error"}")
                }
            })
            println(err)
            fflush(stdout)
        } catch (_: Throwable) {
        }
    }
}
