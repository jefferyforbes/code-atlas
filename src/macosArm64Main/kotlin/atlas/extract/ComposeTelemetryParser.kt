package atlas.extract

import atlas.extract.ComposeStabilityExtractor.Companion.BIT_STAB_001
import atlas.extract.ComposeStabilityExtractor.Companion.MAX_TELEMETRY_BYTES
import atlas.extract.ComposeStabilityExtractor.Companion.MAX_TELEMETRY_LINES
import atlas.extract.ComposeStabilityExtractor.TraceRecord
import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.time

/**
 * Compose Compiler metrics report (`*-composables.txt`) and `@TraceRecomposition` runtime telemetry parser.
 */
@OptIn(ExperimentalForeignApi::class)
internal object ComposeTelemetryParser {

    private val COMPILER_HEADER_RE = Regex("""^(.*?\bfun\s+)([A-Za-z0-9_.]+)\s*\(""")
    private val COMPILER_PARAM_RE = Regex("""^\s*(stable|unstable|runtime|unused|default)\s+(?:(stable|unstable|runtime)\s+)?([A-Za-z0-9_]+)\s*:\s*(.+)$""")
    private val LOGCAT_RE = Regex("""TraceRecomposition\s*:\s*composable=([A-Za-z0-9_.#:/-]+)\s+recompose=(\d+)\s+skip=(\d+)(?:\s+dirty=\[([^\]]*)\])?""")
    private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class CompilerNodeSpec(
        val projectSlug: String,
        val nodeId: String,
        val rawName: String,
        val flags: String,
        val matchedNode: ComposeNodeRecord?,
        val params: List<ComposeParamRecord>
    )

    fun parseCompilerComposablesReport(
        projectSlug: String,
        reportText: String,
        existingNodes: List<ComposeNodeRecord>
    ): Pair<List<ComposeNodeRecord>, List<ComposeParamRecord>> {
        val clipped = clipTelemetry(reportText)
        val byShortName = existingNodes.groupBy { it.fqName.substringAfterLast('.') }
        val byFqName = existingNodes.associateBy { it.fqName }

        val updatedNodes = mutableListOf<ComposeNodeRecord>()
        val updatedParams = mutableListOf<ComposeParamRecord>()

        val lines = clipped.lineSequence().take(MAX_TELEMETRY_LINES).toList()
        var i = 0
        while (i < lines.size) {
            val m = COMPILER_HEADER_RE.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val flags = m.groupValues[1]
            val rawName = m.groupValues[2].trim()
            val shortName = rawName.substringAfterLast('.')
            val matchedNode = byFqName[rawName] ?: byShortName[shortName]?.firstOrNull()
            val nodeId = matchedNode?.nodeId ?: "$projectSlug:compiler#$rawName"

            val (params, nextIndex) = parseCompilerParamsBlock(lines, i + 1, nodeId)
            val updatedNode = buildCompilerVerifiedNode(
                CompilerNodeSpec(projectSlug, nodeId, rawName, flags, matchedNode, params)
            )

            updatedNodes.add(updatedNode)
            updatedParams.addAll(params)
            i = nextIndex
        }

        return Pair(updatedNodes, updatedParams)
    }

    fun parseTraceTelemetry(
        rawLogText: String,
        defaultSessionId: String = "default_session"
    ): List<TraceRecord> {
        val clipped = clipTelemetry(rawLogText)
        val records = mutableListOf<TraceRecord>()

        var lineCount = 0
        for (rawLine in clipped.lineSequence()) {
            if (++lineCount > MAX_TELEMETRY_LINES) break
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val record = parseTelemetryLine(trimmed, defaultSessionId)
            if (record != null) {
                records.add(record)
            }
        }
        return records
    }

    private fun parseCompilerParamLine(
        pLine: String,
        nodeId: String,
        pIdx: Int
    ): ComposeParamRecord? {
        val pm = COMPILER_PARAM_RE.find(pLine) ?: return null
        val modifier1 = pm.groupValues[1]
        val modifier2 = pm.groupValues[2]
        val pName = pm.groupValues[3]
        val pType = pm.groupValues[4].trim()
        val isUnused = modifier1 == "unused"
        val effStability = when (modifier2.ifEmpty { modifier1 }) {
            "stable" -> "STABLE"
            "unstable" -> "UNSTABLE"
            "runtime" -> "RUNTIME"
            else -> "STABLE"
        }
        return ComposeParamRecord(
            nodeId = nodeId,
            paramIndex = pIdx,
            paramName = pName,
            typeSignature = pType,
            stability = effStability,
            stabilityReason = "COMPILER_REPORT_$effStability",
            hasDefault = modifier1 == "default",
            isUsedInBody = !isUnused,
            provenance = "COMPILER_VERIFIED"
        )
    }

    private fun parseCompilerParamsBlock(
        lines: List<String>,
        startIndex: Int,
        nodeId: String
    ): Pair<List<ComposeParamRecord>, Int> {
        val params = mutableListOf<ComposeParamRecord>()
        var i = startIndex
        while (i < lines.size) {
            val pLine = lines[i].trim()
            if (pLine.startsWith(")") || pLine.startsWith("fun ") || pLine.startsWith("restartable ")) {
                break
            }
            val record = parseCompilerParamLine(pLine, nodeId, params.size)
            if (record != null) {
                params.add(record)
            }
            i++
        }
        return Pair(params, i)
    }

    private fun buildCompilerVerifiedNode(spec: CompilerNodeSpec): ComposeNodeRecord {
        val isRestartable = spec.flags.contains("restartable")
        val isSkippable = spec.flags.contains("skippable")
        val isReadonly = spec.flags.contains("readonly")
        val isInline = spec.flags.contains("inline")
        val unstableCnt = spec.params.count { it.isUsedInBody && it.stability == "UNSTABLE" }
        val runtimeCnt = spec.params.count { it.isUsedInBody && it.stability == "RUNTIME" }
        val baseNode = spec.matchedNode ?: ComposeNodeRecord(
            nodeId = spec.nodeId,
            projectSlug = spec.projectSlug,
            fqName = spec.rawName,
            filePath = "compiler_report",
            startLine = 1,
            endLine = 1
        )
        var newMask = baseNode.violationMask
        if (isRestartable && !isSkippable && unstableCnt > 0) {
            newMask = newMask or BIT_STAB_001
        } else if (isSkippable) {
            newMask = newMask and BIT_STAB_001.inv()
        }
        return baseNode.copy(
            isRestartable = isRestartable,
            isSkippable = isSkippable,
            isReadonly = isReadonly,
            isInline = isInline,
            unstableParamCount = unstableCnt,
            runtimeParamCount = runtimeCnt,
            provenance = "COMPILER_VERIFIED",
            violationMask = newMask
        )
    }

    private fun formatDirtyParamsJson(dirtyList: List<String>): String {
        if (dirtyList.isEmpty()) return "[]"
        return dirtyList.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"") {
            it.replace("\\", "\\\\").replace("\"", "\\\"")
        }
    }

    private fun extractJsonTarget(obj: JsonObject): String? {
        val composable = obj["composable"]?.jsonPrimitive?.content
        if (composable != null) return composable
        val nodeId = obj["node_id"]?.jsonPrimitive?.content
        if (nodeId != null) return nodeId
        return obj["fqname"]?.jsonPrimitive?.content
    }

    private fun extractJsonCount(obj: JsonObject, primaryKey: String, secondaryKey: String): Int {
        val primary = obj[primaryKey]?.jsonPrimitive?.content?.toIntOrNull()
        if (primary != null) return primary.coerceAtLeast(0)
        val secondary = obj[secondaryKey]?.jsonPrimitive?.content?.toIntOrNull()
        if (secondary != null) return secondary.coerceAtLeast(0)
        return 0
    }

    private fun parseJsonTraceRecord(
        trimmed: String,
        defaultSessionId: String
    ): TraceRecord? {
        return try {
            val obj = LENIENT_JSON.parseToJsonElement(trimmed).jsonObject
            val target = extractJsonTarget(obj) ?: return null
            val session = obj["session_id"]?.jsonPrimitive?.content ?: defaultSessionId
            val recomp = extractJsonCount(obj, "recomposition_count", "recompose")
            val skip = extractJsonCount(obj, "skip_count", "skip")
            val dirtyArr = obj["dirty_params"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val dirtyJson = formatDirtyParamsJson(dirtyArr)
            val ts = obj["recorded_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: time(null)

            TraceRecord(
                fqNameOrNodeId = target,
                sessionId = session,
                recompositionCount = recomp,
                skipCount = skip,
                dirtyParamsJson = dirtyJson,
                recordedAt = ts
            )
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseLogcatTraceRecord(
        trimmed: String,
        defaultSessionId: String
    ): TraceRecord? {
        val m = LOGCAT_RE.find(trimmed) ?: return null
        val target = m.groupValues[1]
        val recomp = m.groupValues[2].toIntOrNull() ?: 0
        val skip = m.groupValues[3].toIntOrNull() ?: 0
        val dirtyRaw = m.groupValues[4].trim()
        val dirtyList = if (dirtyRaw.isEmpty()) emptyList() else dirtyRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val dirtyJson = formatDirtyParamsJson(dirtyList)

        return TraceRecord(
            fqNameOrNodeId = target,
            sessionId = defaultSessionId,
            recompositionCount = recomp,
            skipCount = skip,
            dirtyParamsJson = dirtyJson,
            recordedAt = time(null)
        )
    }

    private fun clipTelemetry(rawLogText: String): String {
        if (rawLogText.length > MAX_TELEMETRY_BYTES) {
            return rawLogText.substring(0, MAX_TELEMETRY_BYTES)
        }
        return rawLogText
    }

    private fun parseTelemetryLine(
        trimmed: String,
        defaultSessionId: String
    ): TraceRecord? {
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseJsonTraceRecord(trimmed, defaultSessionId)
        }
        return parseLogcatTraceRecord(trimmed, defaultSessionId)
    }
}
