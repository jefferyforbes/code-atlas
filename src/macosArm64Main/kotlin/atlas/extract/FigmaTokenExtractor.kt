package atlas.extract

import atlas.model.FigmaEdge
import atlas.model.FigmaVariable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Extracts Figma Variable collections, variable aliases, and W3C DTCG design token hierarchies
 * into normalized [FigmaVariable] and [FigmaEdge] domain models.
 */
internal object FigmaTokenExtractor {

    private val DTCG_METADATA_KEYS = setOf(
        "description", "\$description", "extensions", "\$extensions"
    )

    private data class DtcgRawToken(
        val name: String,
        val collectionName: String,
        val resolvedType: String,
        val value: JsonElement,
        val codeSyntaxWeb: String?,
        val codeSyntaxCompose: String?
    )

    fun extractVariablesFromAstRoot(
        fileKey: String,
        rootObj: JsonObject,
        variables: MutableMap<String, FigmaVariable>,
        edges: MutableMap<String, FigmaEdge>
    ) {
        if (rootObj.containsKey("variables") || rootObj.containsKey("variableCollections")) {
            parseFigmaVariablesFormat(fileKey, rootObj, variables, edges)
        }
    }

    fun parseFigmaVariablesFormat(
        fileKey: String,
        rootObj: JsonObject,
        variables: MutableMap<String, FigmaVariable>,
        edges: MutableMap<String, FigmaEdge>
    ) {
        val collectionsMap = resolveFigmaCollectionsMap(rootObj)
        val varItems = extractFigmaVariableItems(rootObj)
        val ctx = VariableParseContext(fileKey, collectionsMap, variables, edges)
        for (varObj in varItems) {
            processFigmaVariableItem(varObj, ctx)
        }
    }

    private data class VariableParseContext(
        val fileKey: String,
        val collectionsMap: Map<String, String>,
        val variables: MutableMap<String, FigmaVariable>,
        val edges: MutableMap<String, FigmaEdge>
    )

    fun parseDtcgTokensFormat(
        fileKey: String,
        rootObj: JsonObject,
        variables: MutableMap<String, FigmaVariable>,
        edges: MutableMap<String, FigmaEdge>
    ) {
        val collectedTokens = mutableListOf<DtcgRawToken>()
        traverseDtcgGroup(rootObj, emptyList(), null, collectedTokens)
        val knownTokens = buildDtcgKnownTokensMap(collectedTokens)

        for (t in collectedTokens) {
            val targetAlias = extractDtcgAlias(t.value, knownTokens)
            if (targetAlias != null) {
                val edgeId = "$fileKey:alias:${t.name}->$targetAlias"
                edges[edgeId] = FigmaEdge(edgeId, fileKey, t.name, targetAlias, "VARIABLE_ALIAS_OF", "alias")
            }
            variables[t.name] = buildDtcgVariable(fileKey, t, targetAlias)
        }
    }

    private fun putCollectionEntriesFromArray(collectionsArray: JsonArray, collectionsMap: MutableMap<String, String>) {
        for (col in collectionsArray.filterIsInstance<JsonObject>()) {
            val colId = col["id"].asString() ?: continue
            val colName = col["name"].asString() ?: "Default"
            collectionsMap[colId] = colName
        }
    }

    private fun putCollectionEntriesFromObject(collectionsObj: JsonObject, collectionsMap: MutableMap<String, String>) {
        for ((colId, colVal) in collectionsObj) {
            val colName = (colVal as? JsonObject)?.get("name").asString() ?: "Default"
            collectionsMap[colId] = colName
        }
    }

    private fun resolveFigmaCollectionsMap(rootObj: JsonObject): Map<String, String> {
        val collectionsMap = mutableMapOf<String, String>()
        val collectionsArray = rootObj["collections"]?.jsonArray
            ?: rootObj["variableCollections"]?.jsonArray
            ?: rootObj["meta"]?.jsonObject?.get("variableCollections")?.jsonArray
        if (collectionsArray != null) {
            putCollectionEntriesFromArray(collectionsArray, collectionsMap)
        }
        val collectionsObj = rootObj["meta"]?.jsonObject?.get("variableCollections")?.jsonObject
        if (collectionsObj != null) {
            putCollectionEntriesFromObject(collectionsObj, collectionsMap)
        }
        return collectionsMap
    }

    private fun extractFigmaVariableItems(rootObj: JsonObject): List<JsonObject> {
        val varsElement = rootObj["variables"]
            ?: rootObj["meta"]?.jsonObject?.get("variables")
            ?: return emptyList()
        return when (varsElement) {
            is JsonArray -> varsElement.filterIsInstance<JsonObject>()
            is JsonObject -> varsElement.values.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
    }

    private fun extractVariableAliasTarget(valuesByMode: JsonObject): String? {
        for ((_, modeVal) in valuesByMode) {
            if (modeVal is JsonObject && modeVal["type"].asString() == "VARIABLE_ALIAS") {
                val targetId = modeVal["id"].asString()
                if (!targetId.isNullOrBlank()) {
                    return targetId
                }
            }
        }
        return null
    }

    private fun processFigmaVariableItem(
        varObj: JsonObject,
        ctx: VariableParseContext
    ) {
        val varId = varObj["id"].asString()
            ?: varObj["variableId"].asString()
            ?: return
        val name = varObj["name"].asString() ?: varId
        val colId = varObj["variableCollectionId"].asString()
        val colName = if (colId != null) ctx.collectionsMap[colId] ?: "Default" else "Default"
        val resolvedType = varObj["resolvedType"].asString()?.uppercase() ?: "COLOR"

        val valuesByMode = varObj["valuesByMode"]?.jsonObject ?: JsonObject(emptyMap())
        val targetAlias = extractVariableAliasTarget(valuesByMode)
        if (targetAlias != null) {
            val edgeId = "${ctx.fileKey}:alias:$varId->$targetAlias"
            ctx.edges[edgeId] = FigmaEdge(edgeId, ctx.fileKey, varId, targetAlias, "VARIABLE_ALIAS_OF", "alias")
        }

        val codeSyntax = varObj["codeSyntax"]?.jsonObject
        val codeCompose = codeSyntax?.get("COMPOSE").asString()
        val codeWeb = codeSyntax?.get("WEB").asString()

        ctx.variables[varId] = FigmaVariable(
            variableId = varId,
            fileKey = ctx.fileKey,
            collectionName = colName,
            name = name,
            resolvedType = resolvedType,
            isAlias = targetAlias != null,
            aliasedVariableId = targetAlias,
            modesJson = valuesByMode.toString(),
            codeSyntaxCompose = codeCompose,
            codeSyntaxWeb = codeWeb
        )
    }

    private fun buildDtcgKnownTokensMap(collectedTokens: List<DtcgRawToken>): Map<String, String> {
        val knownTokens = mutableMapOf<String, String>()
        for (t in collectedTokens) {
            knownTokens[t.name] = t.name
            knownTokens[t.name.replace('/', '.')] = t.name
            val leaf = t.name.substringAfterLast('/')
            if (!knownTokens.containsKey(leaf)) {
                knownTokens[leaf] = t.name
            }
        }
        return knownTokens
    }

    private fun resolveDtcgAliasTarget(rawRef: String, knownTokens: Map<String, String>): String {
        return knownTokens[rawRef]
            ?: knownTokens[rawRef.replace('.', '/')]
            ?: rawRef.replace('.', '/')
    }

    private fun parseBraceWrappedAlias(str: String?, knownTokens: Map<String, String>): String? {
        if (str == null || !str.startsWith("{") || !str.endsWith("}")) return null
        val rawRef = str.removePrefix("{").removeSuffix("}").trim()
        return resolveDtcgAliasTarget(rawRef, knownTokens)
    }

    private fun extractDtcgAlias(value: JsonElement, knownTokens: Map<String, String>): String? {
        if (value is JsonPrimitive) {
            return parseBraceWrappedAlias(value.content, knownTokens)
        }
        if (value is JsonObject) {
            for ((_, modeVal) in value) {
                val resolved = parseBraceWrappedAlias((modeVal as? JsonPrimitive)?.content, knownTokens)
                if (resolved != null) return resolved
            }
        }
        return null
    }

    private fun formatDtcgModesJson(value: JsonElement): String {
        if (value is JsonObject) return value.toString()
        val contentStr = (value as? JsonPrimitive)?.content ?: ""
        return JsonObject(mapOf("default" to JsonPrimitive(contentStr))).toString()
    }

    private fun buildDtcgVariable(
        fileKey: String,
        t: DtcgRawToken,
        targetAlias: String?
    ): FigmaVariable {
        val isAlias = targetAlias != null
        val modesJson = formatDtcgModesJson(t.value)
        val codeWeb = t.codeSyntaxWeb ?: "var(--${t.name.replace('/', '-').replace('.', '-')})"
        val codeCompose = t.codeSyntaxCompose ?: "Theme.tokens.${toCamelCase(t.name.substringAfterLast('/'))}"

        return FigmaVariable(
            variableId = t.name,
            fileKey = fileKey,
            collectionName = t.collectionName,
            name = t.name,
            resolvedType = t.resolvedType,
            isAlias = isAlias,
            aliasedVariableId = targetAlias,
            modesJson = modesJson,
            codeSyntaxCompose = codeCompose,
            codeSyntaxWeb = codeWeb
        )
    }

    private fun resolveDtcgType(obj: JsonObject, inheritedType: String?): String? {
        val direct = obj["\$type"].asString()
        if (direct != null) return direct
        val type = obj["type"].asString()
        if (type != null) return type
        return inheritedType
    }

    private fun extractDtcgToken(obj: JsonObject, path: List<String>, currentType: String?): DtcgRawToken {
        val tokenName = path.joinToString("/")
        val collectionName = if (path.size > 1) path.first() else "Tokens"
        val value = obj["\$value"] ?: obj["value"] ?: JsonPrimitive("")
        val resolvedType = normalizeDtcgType(currentType)

        val ext = obj["\$extensions"]?.jsonObject ?: obj["extensions"]?.jsonObject
        val codeWeb = ext?.get("web").asString() ?: ext?.get("css").asString()
        val codeCompose = ext?.get("compose").asString()

        return DtcgRawToken(
            name = tokenName,
            collectionName = collectionName,
            resolvedType = resolvedType,
            value = value,
            codeSyntaxWeb = codeWeb,
            codeSyntaxCompose = codeCompose
        )
    }

    private fun traverseDtcgChildren(
        obj: JsonObject,
        path: List<String>,
        currentType: String?,
        results: MutableList<DtcgRawToken>
    ) {
        for ((key, child) in obj) {
            if (key.startsWith("\$") || key in DTCG_METADATA_KEYS) continue
            if (child is JsonObject) {
                traverseDtcgGroup(child, path + key, currentType, results)
            }
        }
    }

    private fun traverseDtcgGroup(
        obj: JsonObject,
        path: List<String>,
        inheritedType: String?,
        results: MutableList<DtcgRawToken>
    ) {
        val currentType = resolveDtcgType(obj, inheritedType)
        if (obj.containsKey("\$value") || obj.containsKey("value")) {
            results.add(extractDtcgToken(obj, path, currentType))
            return
        }
        traverseDtcgChildren(obj, path, currentType, results)
    }

    private fun normalizeDtcgType(typeStr: String?): String {
        return when (typeStr?.lowercase()) {
            "color" -> "COLOR"
            "dimension", "number", "duration", "fontsize", "spacing", "lineheight" -> "FLOAT"
            "boolean" -> "BOOLEAN"
            else -> "STRING"
        }
    }

    private fun toCamelCase(str: String): String {
        val parts = str.split('-', '_', '.', ' ').filter { it.isNotBlank() }
        if (parts.isEmpty()) return str
        return parts.first().lowercase() + parts.drop(1).joinToString("") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.content
}
