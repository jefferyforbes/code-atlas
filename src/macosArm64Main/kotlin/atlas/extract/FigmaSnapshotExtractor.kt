package atlas.extract

import atlas.db.SqliteGraphStore
import atlas.model.FigmaDocument
import atlas.model.FigmaEdge
import atlas.model.FigmaExtractedSnapshot
import atlas.model.FigmaNode
import atlas.model.FigmaVariable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Sovereign AST extractor ingesting Figma document AST JSON and W3C DTCG design tokens.
 *
 * Strips vector render paths, vertex coordinates, and SVG bloat to cut footprint by ~85%,
 * while extracting AutoLayout flex parameters, 2D bounding boxes, interactive flags,
 * unbound fills/strokes, unicode emojis, and directed graph edges.
 */
@OptIn(ExperimentalSerializationApi::class)
class FigmaSnapshotExtractor {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
        coerceInputValues = true
    }

    /**
     * Extracts a full snapshot from Figma AST JSON and optional W3C DTCG design tokens JSON.
     */
    fun extract(
        fileKey: String,
        astJson: String,
        tokensJson: String? = null
    ): FigmaExtractedSnapshot {
        val docSnapshot = extractDocument(fileKey, astJson)
        if (tokensJson.isNullOrBlank()) {
            return docSnapshot
        }

        val (tokenVars, tokenEdges) = extractTokens(fileKey, tokensJson)

        val mergedVariables = LinkedHashMap<String, FigmaVariable>()
        for (v in docSnapshot.variables) mergedVariables[v.variableId] = v
        for (v in tokenVars) mergedVariables[v.variableId] = v

        val mergedEdges = LinkedHashMap<String, FigmaEdge>()
        for (e in docSnapshot.edges) mergedEdges[e.edgeId] = e
        for (e in tokenEdges) mergedEdges[e.edgeId] = e

        return docSnapshot.copy(
            variables = mergedVariables.values.toList(),
            edges = mergedEdges.values.toList()
        )
    }

    /**
     * Ingests Figma document AST JSON and optional DTCG tokens into SQLite graph store.
     */
    fun ingest(
        store: SqliteGraphStore,
        fileKey: String,
        astJson: String,
        tokensJson: String? = null
    ): FigmaExtractedSnapshot {
        val snapshot = extract(fileKey, astJson, tokensJson)
        store.deleteFigmaDocument(fileKey)
        store.insertFigmaDocument(snapshot.document)
        if (snapshot.nodes.isNotEmpty()) {
            store.insertFigmaNodes(fileKey, snapshot.nodes)
        }
        if (snapshot.variables.isNotEmpty()) {
            store.insertFigmaVariables(fileKey, snapshot.variables)
        }
        if (snapshot.edges.isNotEmpty()) {
            store.insertFigmaEdges(fileKey, snapshot.edges)
        }
        return snapshot
    }

    private fun parseDocumentMetadata(fileKey: String, rootObj: JsonObject?): FigmaDocument {
        val docName = rootObj?.get("name").asString()
            ?: rootObj?.get("document")?.jsonObject?.get("name").asString()
            ?: fileKey
        val docVersion = rootObj?.get("version").asString() ?: "1.0.0"
        val docLastModified = rootObj?.get("lastModified").asString() ?: ""
        val docThumbnailUrl = rootObj?.get("thumbnailUrl").asString() ?: ""
        return FigmaDocument(
            fileKey = fileKey,
            name = docName,
            version = docVersion,
            lastModified = docLastModified,
            thumbnailUrl = docThumbnailUrl
        )
    }

    private fun traverseNodesMap(
        nodesMap: JsonObject,
        fileKey: String,
        nodes: MutableList<FigmaNode>,
        edges: MutableMap<String, FigmaEdge>
    ) {
        for ((_, nodeVal) in nodesMap) {
            val targetNode = (nodeVal as? JsonObject)?.let { it["document"]?.jsonObject ?: it }
            if (targetNode != null) {
                traverseNodeTree(fileKey, targetNode, null, "Page 1", null, nodes, edges)
            }
        }
    }

    private fun traverseRootArray(
        rootElement: JsonArray,
        fileKey: String,
        nodes: MutableList<FigmaNode>,
        edges: MutableMap<String, FigmaEdge>
    ) {
        for (item in rootElement.filterIsInstance<JsonObject>()) {
            traverseNodeTree(fileKey, item, null, "Page 1", null, nodes, edges)
        }
    }

    private fun traverseDocumentRoots(
        fileKey: String,
        rootElement: JsonElement,
        rootObj: JsonObject?,
        nodes: MutableList<FigmaNode>,
        edges: MutableMap<String, FigmaEdge>
    ) {
        val docObj = rootObj?.get("document")?.jsonObject
        if (docObj != null) {
            traverseNodeTree(fileKey, docObj, null, "Document", null, nodes, edges)
            return
        }

        val nodesMap = rootObj?.get("nodes")?.jsonObject
        if (nodesMap != null) {
            traverseNodesMap(nodesMap, fileKey, nodes, edges)
            return
        }

        if (rootObj != null && (rootObj.containsKey("id") || rootObj.containsKey("type"))) {
            traverseNodeTree(fileKey, rootObj, null, "Page 1", null, nodes, edges)
            return
        }

        if (rootElement is JsonArray) {
            traverseRootArray(rootElement, fileKey, nodes, edges)
        }
    }

    /**
     * Extracts Figma document AST JSON into document, nodes, variables, and edges.
     */
    fun extractDocument(fileKey: String, astJson: String): FigmaExtractedSnapshot {
        if (astJson.isBlank()) {
            return FigmaExtractedSnapshot(document = FigmaDocument(fileKey = fileKey, name = fileKey))
        }

        val rootElement = json.parseToJsonElement(astJson)
        val rootObj = rootElement as? JsonObject
        val document = parseDocumentMetadata(fileKey, rootObj)

        val extractedNodes = mutableListOf<FigmaNode>()
        val extractedEdges = LinkedHashMap<String, FigmaEdge>()
        val extractedVars = LinkedHashMap<String, FigmaVariable>()

        if (rootObj != null) {
            extractVariablesFromAstRoot(fileKey, rootObj, extractedVars, extractedEdges)
        }

        traverseDocumentRoots(fileKey, rootElement, rootObj, extractedNodes, extractedEdges)

        return FigmaExtractedSnapshot(
            document = document,
            nodes = extractedNodes,
            variables = extractedVars.values.toList(),
            edges = extractedEdges.values.toList()
        )
    }

    /**
     * Extracts W3C DTCG design token bundles or Figma Variables exports into domain models.
     */
    fun extractTokens(
        fileKey: String,
        tokensJson: String
    ): Pair<List<FigmaVariable>, List<FigmaEdge>> {
        if (tokensJson.isBlank()) {
            return Pair(emptyList(), emptyList())
        }

        val rootElement = json.parseToJsonElement(tokensJson)
        val rootObj = rootElement as? JsonObject ?: return Pair(emptyList(), emptyList())

        val variables = LinkedHashMap<String, FigmaVariable>()
        val edges = LinkedHashMap<String, FigmaEdge>()

        val isFigmaVariablesExport = rootObj.containsKey("variables") ||
            (rootObj.containsKey("meta") && rootObj["meta"]?.jsonObject?.containsKey("variables") == true)

        if (isFigmaVariablesExport) {
            parseFigmaVariablesFormat(fileKey, rootObj, variables, edges)
        } else {
            parseDtcgTokensFormat(fileKey, rootObj, variables, edges)
        }

        return Pair(variables.values.toList(), edges.values.toList())
    }

    private fun detectEmoji(nodeObj: JsonObject, name: String): Boolean {
        val explicit = nodeObj["hasEmoji"].asBoolean() ?: nodeObj["has_emoji"].asBoolean()
        if (explicit != null) return explicit
        val characters = nodeObj["characters"].asString() ?: ""
        return containsEmoji(name) || containsEmoji(characters)
    }

    private fun detectInteractive(
        nodeObj: JsonObject,
        rawType: String,
        name: String,
        hasReaction: Boolean
    ): Boolean {
        val explicit = nodeObj["isInteractive"].asBoolean() ?: nodeObj["is_interactive"].asBoolean()
        if (explicit != null) return explicit
        return hasReaction || rawType == "TOUCH_TARGET_FRAME" || isInteractiveByName(name)
    }

    private fun recordContainsEdge(
        fileKey: String,
        parentId: String?,
        nodeId: String,
        edges: MutableMap<String, FigmaEdge>
    ) {
        if (parentId == null) return
        val containsEdgeId = "$fileKey:contains:$parentId->$nodeId"
        edges[containsEdgeId] = FigmaEdge(
            edgeId = containsEdgeId,
            fileKey = fileKey,
            sourceNodeId = parentId,
            targetNodeId = nodeId,
            edgeType = "CONTAINS",
            propertySlot = "children"
        )
    }

    private fun buildFigmaNode(
        nodeId: String,
        fileKey: String,
        pageName: String,
        sectionId: String?,
        parentId: String?,
        name: String,
        rawType: String,
        nodeObj: JsonObject,
        isInteractive: Boolean,
        hasEmoji: Boolean,
        hasReaction: Boolean
    ): FigmaNode {
        val autoLayout = extractAutoLayout(nodeObj)
        val bbox = extractBoundingBox(nodeObj)
        val fills = nodeObj["fills"]?.jsonArray
        val strokes = nodeObj["strokes"]?.jsonArray
        val unboundFill = extractUnboundFill(nodeObj, fills)
        val unboundStroke = extractUnboundStroke(nodeObj, strokes)
        val wcagContrast = nodeObj["wcagContrast"].asDouble() ?: nodeObj["wcag_contrast"].asDouble()
        val semioticLayer = nodeObj["semioticLayer"].asString()
            ?: nodeObj["semiotic_layer"].asString()
            ?: classifySemioticLayer(rawType, name, isInteractive, hasReaction)

        return FigmaNode(
            nodeId = nodeId,
            fileKey = fileKey,
            pageName = pageName,
            sectionId = sectionId,
            parentId = parentId,
            name = name,
            nodeType = rawType,
            layoutMode = autoLayout.layoutMode,
            sizingHorizontal = autoLayout.sizingHorizontal,
            sizingVertical = autoLayout.sizingVertical,
            textAutoResize = autoLayout.textAutoResize,
            paddingLeft = autoLayout.paddingLeft,
            paddingRight = autoLayout.paddingRight,
            paddingTop = autoLayout.paddingTop,
            paddingBottom = autoLayout.paddingBottom,
            itemSpacing = autoLayout.itemSpacing,
            localX = bbox.localX,
            localY = bbox.localY,
            canvasX = bbox.canvasX,
            canvasY = bbox.canvasY,
            width = bbox.width,
            height = bbox.height,
            isInteractive = isInteractive,
            hasEmoji = hasEmoji,
            unboundFill = unboundFill,
            unboundStroke = unboundStroke,
            wcagContrast = wcagContrast,
            semioticLayer = semioticLayer
        )
    }

    private fun traverseChildren(
        children: JsonArray?,
        fileKey: String,
        parentId: String,
        pageName: String,
        sectionId: String?,
        nodes: MutableList<FigmaNode>,
        edges: MutableMap<String, FigmaEdge>
    ) {
        if (children == null) return
        for (child in children.filterIsInstance<JsonObject>()) {
            traverseNodeTree(fileKey, child, parentId, pageName, sectionId, nodes, edges)
        }
    }

    private fun resolveNodeSectionScope(
        nodeObj: JsonObject,
        nodeId: String,
        rawType: String,
        sectionId: String?
    ): Pair<String?, String?> {
        val isSection = rawType == "SECTION" || rawType == "CANVAS_SECTION"
        val currentSectionId = if (isSection) sectionId else (sectionId ?: nodeObj["sectionId"].asString())
        val nextSectionId = if (isSection) nodeId else currentSectionId
        return Pair(currentSectionId, nextSectionId)
    }

    private fun traverseNodeTree(
        fileKey: String,
        nodeObj: JsonObject,
        parentId: String?,
        pageName: String,
        sectionId: String?,
        nodes: MutableList<FigmaNode>,
        edges: MutableMap<String, FigmaEdge>
    ) {
        val nodeId = nodeObj["id"].asString() ?: nodeObj["nodeId"].asString() ?: return
        val name = nodeObj["name"].asString() ?: ""
        val rawType = nodeObj["type"].asString()?.uppercase() ?: "FRAME"

        val effectivePageName = if (rawType == "CANVAS" && name.isNotBlank()) name else pageName
        val (currentSectionId, nextSectionId) = resolveNodeSectionScope(nodeObj, nodeId, rawType, sectionId)

        val hasEmoji = detectEmoji(nodeObj, name)
        val hasReaction = extractReactions(fileKey, nodeId, nodeObj, edges)
        val isInteractive = detectInteractive(nodeObj, rawType, name, hasReaction)

        extractBoundVariableEdges(fileKey, nodeId, nodeObj, edges)
        if (rawType == "INSTANCE") {
            extractInstantiates(fileKey, nodeId, nodeObj, edges)
        }
        recordContainsEdge(fileKey, parentId, nodeId, edges)

        val figmaNode = buildFigmaNode(
            nodeId = nodeId,
            fileKey = fileKey,
            pageName = effectivePageName,
            sectionId = currentSectionId,
            parentId = parentId,
            name = name,
            rawType = rawType,
            nodeObj = nodeObj,
            isInteractive = isInteractive,
            hasEmoji = hasEmoji,
            hasReaction = hasReaction
        )
        nodes.add(figmaNode)

        traverseChildren(nodeObj["children"]?.jsonArray, fileKey, nodeId, effectivePageName, nextSectionId, nodes, edges)
    }

    private fun resolveFallbackSizing(
        align: String?,
        primarySizing: String?,
        counterSizing: String?,
        layoutMode: String,
        targetMode: String,
        counterMode: String
    ): String = when {
        align == "STRETCH" && layoutMode == counterMode -> "FILL"
        layoutMode == targetMode && primarySizing == "AUTO" -> "HUG"
        layoutMode == counterMode && counterSizing == "AUTO" -> "HUG"
        else -> "FIXED"
    }

    private fun extractLayoutSizing(
        nodeObj: JsonObject,
        primaryKey: String,
        aliasKey: String,
        layoutMode: String,
        targetMode: String,
        counterMode: String
    ): String {
        val explicit = nodeObj[primaryKey].asString()?.uppercase()
            ?: nodeObj[aliasKey].asString()?.uppercase()
        if (explicit != null) {
            return if (explicit in VALID_SIZINGS) explicit else "FIXED"
        }
        val align = nodeObj["layoutAlign"].asString()?.uppercase()
        val primarySizing = nodeObj["primaryAxisSizingMode"].asString()?.uppercase()
        val counterSizing = nodeObj["counterAxisSizingMode"].asString()?.uppercase()
        val fallback = resolveFallbackSizing(align, primarySizing, counterSizing, layoutMode, targetMode, counterMode)
        return if (fallback in VALID_SIZINGS) fallback else "FIXED"
    }

    private fun extractSidePadding(
        nodeObj: JsonObject,
        sideKey: String,
        axisFallback: Double?,
        allFallback: Double
    ): Double = nodeObj[sideKey].asDouble() ?: axisFallback ?: allFallback

    private fun extractAutoLayout(nodeObj: JsonObject): AutoLayoutFlex {
        val layoutMode = nodeObj["layoutMode"].asString()?.uppercase() ?: "NONE"
        val sizingH = extractLayoutSizing(
            nodeObj, "layoutSizingHorizontal", "sizingHorizontal", layoutMode, "HORIZONTAL", "VERTICAL"
        )
        val sizingV = extractLayoutSizing(
            nodeObj, "layoutSizingVertical", "sizingVertical", layoutMode, "VERTICAL", "HORIZONTAL"
        )
        val textAutoResize = nodeObj["textAutoResize"].asString()?.uppercase() ?: "NONE"

        val padH = nodeObj["horizontalPadding"].asDouble() ?: nodeObj["paddingHorizontal"].asDouble()
        val padV = nodeObj["verticalPadding"].asDouble() ?: nodeObj["paddingVertical"].asDouble()
        val padAll = nodeObj["padding"].asDouble() ?: 0.0

        return AutoLayoutFlex(
            layoutMode = layoutMode,
            sizingHorizontal = sizingH,
            sizingVertical = sizingV,
            textAutoResize = textAutoResize,
            paddingLeft = extractSidePadding(nodeObj, "paddingLeft", padH, padAll),
            paddingRight = extractSidePadding(nodeObj, "paddingRight", padH, padAll),
            paddingTop = extractSidePadding(nodeObj, "paddingTop", padV, padAll),
            paddingBottom = extractSidePadding(nodeObj, "paddingBottom", padV, padAll),
            itemSpacing = nodeObj["itemSpacing"].asDouble() ?: 0.0
        )
    }

    private fun extractDimension(
        bbox: JsonObject?,
        nodeObj: JsonObject,
        bboxKey: String,
        sizeKey: String,
        fallbackKey: String
    ): Double {
        val bVal = bbox?.get(bboxKey).asDouble()
        if (bVal != null) return bVal
        val sizeVal = nodeObj["size"]?.jsonObject?.get(sizeKey).asDouble()
        if (sizeVal != null) return sizeVal
        val fbVal = nodeObj[fallbackKey].asDouble()
        if (fbVal != null) return fbVal
        return 0.0
    }

    private fun extractCanvasCoord(
        bbox: JsonObject?,
        nodeObj: JsonObject,
        axis: String,
        canvasAxis: String
    ): Double {
        val bVal = bbox?.get(axis).asDouble()
        if (bVal != null) return bVal
        val cVal = nodeObj[canvasAxis].asDouble()
        if (cVal != null) return cVal
        val fbVal = nodeObj[axis].asDouble()
        if (fbVal != null) return fbVal
        return 0.0
    }

    private fun extractLocalCoord(
        nodeObj: JsonObject,
        localKey: String,
        axis: String,
        transformRow: Int
    ): Double {
        val lVal = nodeObj[localKey].asDouble()
        if (lVal != null) return lVal
        val tVal = extractTransformCoord(nodeObj["relativeTransform"], transformRow, 2)
        if (tVal != null) return tVal
        val fbVal = nodeObj[axis].asDouble()
        if (fbVal != null) return fbVal
        return 0.0
    }

    private fun extractBoundingBox(nodeObj: JsonObject): BoundingBox {
        val bbox = nodeObj["absoluteBoundingBox"]?.jsonObject ?: nodeObj["absoluteRenderBounds"]?.jsonObject
        val canvasX = extractCanvasCoord(bbox, nodeObj, "x", "canvasX")
        val canvasY = extractCanvasCoord(bbox, nodeObj, "y", "canvasY")
        val width = extractDimension(bbox, nodeObj, "width", "x", "width")
        val height = extractDimension(bbox, nodeObj, "height", "y", "height")
        val localX = extractLocalCoord(nodeObj, "localX", "x", 0)
        val localY = extractLocalCoord(nodeObj, "localY", "y", 1)

        return BoundingBox(canvasX, canvasY, width, height, localX, localY)
    }

    private fun extractTransformCoord(transformEl: JsonElement?, row: Int, col: Int): Double? {
        val arr = transformEl?.jsonArray ?: return null
        if (row < arr.size) {
            val rowArr = arr[row].jsonArray
            if (col < rowArr.size) {
                return rowArr[col].asDouble()
            }
        }
        return null
    }

    private fun isInteractiveByName(name: String): Boolean {
        val low = name.lowercase()
        return INTERACTIVE_KEYWORDS.any { low.contains(it) }
    }

    private fun extractActionDestinationId(action: JsonObject): String? {
        val dest = action["destinationId"].asString()
        if (dest != null) return dest
        return action["destinationNodeId"].asString()
    }

    private fun recordTriggerEdge(
        fileKey: String,
        nodeId: String,
        targetId: String,
        triggerType: String,
        edges: MutableMap<String, FigmaEdge>
    ) {
        val edgeId = "$fileKey:triggers_action:$nodeId->$targetId:$triggerType"
        edges[edgeId] = FigmaEdge(edgeId, fileKey, nodeId, targetId, "TRIGGERS_ACTION", triggerType)
    }

    private fun processReactionAction(
        action: JsonObject,
        fileKey: String,
        nodeId: String,
        triggerType: String,
        edges: MutableMap<String, FigmaEdge>
    ): Boolean {
        var recorded = false
        val destId = extractActionDestinationId(action)
        if (!destId.isNullOrBlank()) {
            recordTriggerEdge(fileKey, nodeId, destId, triggerType, edges)
            recorded = true
        }

        val subActions = action["actions"]?.jsonArray?.filterIsInstance<JsonObject>()
        if (subActions != null) {
            for (subAction in subActions) {
                val subDestId = extractActionDestinationId(subAction)
                if (!subDestId.isNullOrBlank()) {
                    recordTriggerEdge(fileKey, nodeId, subDestId, triggerType, edges)
                    recorded = true
                }
            }
        }
        return recorded
    }

    private fun processReactionsArray(
        reactions: List<JsonObject>,
        fileKey: String,
        nodeId: String,
        edges: MutableMap<String, FigmaEdge>
    ): Boolean {
        var hasReaction = false
        for (reaction in reactions) {
            val trigger = reaction["trigger"]?.jsonObject
            val triggerType = trigger?.get("type").asString() ?: "ON_CLICK"
            val action = reaction["action"]?.jsonObject
            if (action != null && processReactionAction(action, fileKey, nodeId, triggerType, edges)) {
                hasReaction = true
            }
        }
        return hasReaction
    }

    private fun extractReactions(
        fileKey: String,
        nodeId: String,
        nodeObj: JsonObject,
        edges: MutableMap<String, FigmaEdge>
    ): Boolean {
        val reactions = nodeObj["reactions"]?.jsonArray?.filterIsInstance<JsonObject>()
        val hasReactions = if (reactions != null) processReactionsArray(reactions, fileKey, nodeId, edges) else false

        val transitionId = nodeObj["transitionNodeID"].asString()
        val hasTransition = if (!transitionId.isNullOrBlank()) {
            recordTriggerEdge(fileKey, nodeId, transitionId, "NAVIGATE", edges)
            true
        } else false

        return hasReactions || hasTransition
    }

    private fun recordBoundVariableEdge(
        fileKey: String,
        nodeId: String,
        varId: String,
        slot: String,
        edges: MutableMap<String, FigmaEdge>
    ) {
        val edgeId = "$fileKey:binds_variable:$nodeId->$varId:$slot"
        edges[edgeId] = FigmaEdge(edgeId, fileKey, nodeId, varId, "BINDS_VARIABLE", slot)
    }

    private fun processBoundElement(
        element: JsonElement,
        fileKey: String,
        nodeId: String,
        slot: String,
        edges: MutableMap<String, FigmaEdge>
    ) {
        when (element) {
            is JsonArray -> {
                for (item in element) {
                    val varId = extractVariableId(item)
                    if (varId != null) {
                        recordBoundVariableEdge(fileKey, nodeId, varId, slot, edges)
                    }
                }
            }
            is JsonObject -> {
                val varId = extractVariableId(element)
                if (varId != null) {
                    recordBoundVariableEdge(fileKey, nodeId, varId, slot, edges)
                }
            }
            is JsonPrimitive -> {
                val varId = element.asString()
                if (!varId.isNullOrBlank()) {
                    recordBoundVariableEdge(fileKey, nodeId, varId, slot, edges)
                }
            }
        }
    }

    private fun processPaintBoundVariables(
        paintBound: JsonObject,
        slot: String,
        fileKey: String,
        nodeId: String,
        edges: MutableMap<String, FigmaEdge>
    ) {
        for ((propName, propVal) in paintBound) {
            val varId = extractVariableId(propVal)
            if (varId != null) {
                val effectiveSlot = if (propName == "color") slot else "$slot.$propName"
                recordBoundVariableEdge(fileKey, nodeId, varId, effectiveSlot, edges)
            }
        }
    }

    private fun extractPaintSlotVariableEdges(
        nodeObj: JsonObject,
        slot: String,
        fileKey: String,
        nodeId: String,
        edges: MutableMap<String, FigmaEdge>
    ) {
        val paints = nodeObj[slot]?.jsonArray ?: return
        for (paint in paints.filterIsInstance<JsonObject>()) {
            val paintBound = paint["boundVariables"]?.jsonObject ?: continue
            processPaintBoundVariables(paintBound, slot, fileKey, nodeId, edges)
        }
    }

    private fun extractBoundVariableEdges(
        fileKey: String,
        nodeId: String,
        nodeObj: JsonObject,
        edges: MutableMap<String, FigmaEdge>
    ) {
        val boundVars = nodeObj["boundVariables"]?.jsonObject
        if (boundVars != null) {
            for ((slot, element) in boundVars) {
                processBoundElement(element, fileKey, nodeId, slot, edges)
            }
        }

        for (slot in listOf("fills", "strokes")) {
            extractPaintSlotVariableEdges(nodeObj, slot, fileKey, nodeId, edges)
        }
    }

    private fun extractVariableId(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> element.asString()
            is JsonObject -> {
                element["id"].asString()
                    ?: element["variableId"].asString()
                    ?: element["name"].asString()
            }
            else -> null
        }
    }

    private fun extractInstantiates(
        fileKey: String,
        nodeId: String,
        nodeObj: JsonObject,
        edges: MutableMap<String, FigmaEdge>
    ) {
        val compId = nodeObj["componentId"].asString()
            ?: nodeObj["mainComponentId"].asString()
        if (!compId.isNullOrBlank()) {
            val edgeId = "$fileKey:instantiates:$nodeId->$compId"
            edges[edgeId] = FigmaEdge(edgeId, fileKey, nodeId, compId, "INSTANTIATES", "component")
        }
        val compSetId = nodeObj["componentSetId"].asString()
        if (!compSetId.isNullOrBlank() && compSetId != compId) {
            val edgeId = "$fileKey:instantiates:$nodeId->$compSetId"
            edges[edgeId] = FigmaEdge(edgeId, fileKey, nodeId, compSetId, "INSTANTIATES", "component_set")
        }
    }

    private fun extractUnboundFill(nodeObj: JsonObject, fills: JsonArray?): Boolean {
        val explicit = nodeObj["unboundFill"].asBoolean()
            ?: nodeObj["unbound_fill"].asBoolean()
        if (explicit != null) return explicit

        if (fills == null || fills.isEmpty()) return false
        val visibleFills = fills.filterIsInstance<JsonObject>().filter {
            it["visible"].asBoolean() != false
        }
        if (visibleFills.isEmpty()) return false

        val boundVars = nodeObj["boundVariables"]?.jsonObject
        val styles = nodeObj["styles"]?.jsonObject

        if (boundVars?.containsKey("fills") == true || styles?.containsKey("fill") == true) {
            return false
        }

        for (fill in visibleFills) {
            val fillBoundVars = fill["boundVariables"]?.jsonObject
            val hasBinding = fillBoundVars?.isNotEmpty() == true
            if (!hasBinding) return true
        }
        return false
    }

    private fun extractUnboundStroke(nodeObj: JsonObject, strokes: JsonArray?): Boolean {
        val explicit = nodeObj["unboundStroke"].asBoolean()
            ?: nodeObj["unbound_stroke"].asBoolean()
        if (explicit != null) return explicit

        if (strokes == null || strokes.isEmpty()) return false
        val visibleStrokes = strokes.filterIsInstance<JsonObject>().filter {
            it["visible"].asBoolean() != false
        }
        if (visibleStrokes.isEmpty()) return false

        val boundVars = nodeObj["boundVariables"]?.jsonObject
        val styles = nodeObj["styles"]?.jsonObject

        if (boundVars?.containsKey("strokes") == true || styles?.containsKey("stroke") == true) {
            return false
        }

        for (stroke in visibleStrokes) {
            val strokeBoundVars = stroke["boundVariables"]?.jsonObject
            val hasBinding = strokeBoundVars?.isNotEmpty() == true
            if (!hasBinding) return true
        }
        return false
    }

    private fun classifySemioticLayer(
        nodeType: String,
        name: String,
        isInteractive: Boolean,
        hasReaction: Boolean
    ): String {
        return when {
            nodeType in SEMANTIC_NODE_TYPES -> "semantic"
            nodeType in PRAGMATIC_NODE_TYPES || isInteractive || hasReaction -> "pragmatic"
            PRAGMATIC_NAME_PATTERNS.any { name.contains(it, ignoreCase = true) } -> "pragmatic"
            else -> "syntactic"
        }
    }

    private fun extractVariablesFromAstRoot(
        fileKey: String,
        rootObj: JsonObject,
        variables: MutableMap<String, FigmaVariable>,
        edges: MutableMap<String, FigmaEdge>
    ) {
        if (rootObj.containsKey("variables") || rootObj.containsKey("variableCollections")) {
            parseFigmaVariablesFormat(fileKey, rootObj, variables, edges)
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
        fileKey: String,
        collectionsMap: Map<String, String>,
        variables: MutableMap<String, FigmaVariable>,
        edges: MutableMap<String, FigmaEdge>
    ) {
        val varId = varObj["id"].asString()
            ?: varObj["variableId"].asString()
            ?: return
        val name = varObj["name"].asString() ?: varId
        val colId = varObj["variableCollectionId"].asString()
        val colName = if (colId != null) collectionsMap[colId] ?: "Default" else "Default"
        val resolvedType = varObj["resolvedType"].asString()?.uppercase() ?: "COLOR"

        val valuesByMode = varObj["valuesByMode"]?.jsonObject ?: JsonObject(emptyMap())
        val targetAlias = extractVariableAliasTarget(valuesByMode)
        if (targetAlias != null) {
            val edgeId = "$fileKey:alias:$varId->$targetAlias"
            edges[edgeId] = FigmaEdge(edgeId, fileKey, varId, targetAlias, "VARIABLE_ALIAS_OF", "alias")
        }

        val codeSyntax = varObj["codeSyntax"]?.jsonObject
        val codeCompose = codeSyntax?.get("COMPOSE").asString()
        val codeWeb = codeSyntax?.get("WEB").asString()

        variables[varId] = FigmaVariable(
            variableId = varId,
            fileKey = fileKey,
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

    private fun parseFigmaVariablesFormat(
        fileKey: String,
        rootObj: JsonObject,
        variables: MutableMap<String, FigmaVariable>,
        edges: MutableMap<String, FigmaEdge>
    ) {
        val collectionsMap = resolveFigmaCollectionsMap(rootObj)
        val varItems = extractFigmaVariableItems(rootObj)
        for (varObj in varItems) {
            processFigmaVariableItem(varObj, fileKey, collectionsMap, variables, edges)
        }
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

    private fun parseDtcgTokensFormat(
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

    private fun containsEmoji(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val codePoint: Int
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                val high = c.code
                val low = text[i + 1].code
                codePoint = 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
                i += 2
            } else {
                codePoint = c.code
                i += 1
            }
            if (isEmojiCodePoint(codePoint)) return true
        }
        return false
    }

    private fun isEmojiCodePoint(cp: Int): Boolean {
        return EMOJI_CODE_POINT_RANGES.any { cp in it }
    }

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.content
    private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    private fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    private data class AutoLayoutFlex(
        val layoutMode: String,
        val sizingHorizontal: String,
        val sizingVertical: String,
        val textAutoResize: String,
        val paddingLeft: Double,
        val paddingRight: Double,
        val paddingTop: Double,
        val paddingBottom: Double,
        val itemSpacing: Double
    )

    private data class BoundingBox(
        val canvasX: Double,
        val canvasY: Double,
        val width: Double,
        val height: Double,
        val localX: Double,
        val localY: Double
    )

    private data class DtcgRawToken(
        val name: String,
        val collectionName: String,
        val resolvedType: String,
        val value: JsonElement,
        val codeSyntaxWeb: String?,
        val codeSyntaxCompose: String?
    )

    companion object {
        private val EMOJI_CODE_POINT_RANGES = arrayOf(
            0x1F300..0x1F5FF, // Misc Symbols and Pictographs
            0x1F600..0x1F64F, // Emoticons
            0x1F680..0x1F6FF, // Transport and Map
            0x1F700..0x1F77F, // Alchemical Symbols
            0x1F780..0x1F7FF, // Geometric Shapes Extended
            0x1F800..0x1F8FF, // Supplemental Arrows-C
            0x1F900..0x1F9FF, // Supplemental Symbols and Pictographs
            0x1FA00..0x1FA6F, // Chess Symbols
            0x1FA70..0x1FAFF, // Symbols and Pictographs Extended-A
            0x2600..0x26FF, // Misc Symbols
            0x2700..0x27BF, // Dingbats
            0x2300..0x23FF, // Misc Technical
            0x2B50..0x2B55, // Stars
            0x1F1E6..0x1F1FF // Flags
        )
        private val VALID_SIZINGS = setOf("FIXED", "HUG", "FILL")
        private val SEMANTIC_NODE_TYPES = setOf("COMPONENT_SET", "COMPONENT")
        private val PRAGMATIC_NODE_TYPES = setOf("SECTION", "CANVAS_SECTION", "TOUCH_TARGET_FRAME")
        private val PRAGMATIC_NAME_PATTERNS = listOf(
            "Screen", "Flow", "Studio", "Center", "Modal", "Dialog", "Onboarding", "Auth"
        )
        private val INTERACTIVE_KEYWORDS = setOf(
            "button", "btn", "cta", "toggle", "switch", "checkbox", "radio",
            "chip", "tab", "clickable", "touch_target", "touch target"
        )
        private val DTCG_METADATA_KEYS = setOf(
            "description", "\$description", "extensions", "\$extensions"
        )
    }
}
