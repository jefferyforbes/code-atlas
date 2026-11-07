package atlas.extract

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
     * Storage sink interface decoupling Figma extraction from concrete SQLite persistence.
     */
    interface StoreSink {
        fun deleteFigmaDocument(fileKey: String)
        fun insertFigmaDocument(document: FigmaDocument)
        fun insertFigmaNodes(fileKey: String, nodes: List<FigmaNode>)
        fun insertFigmaVariables(fileKey: String, variables: List<FigmaVariable>)
        fun insertFigmaEdges(fileKey: String, edges: List<FigmaEdge>)
    }

    /**
     * Ingests Figma document AST JSON and optional DTCG tokens into a storage sink.
     */
    fun ingest(
        store: StoreSink,
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

    private data class FigmaTraversalCollector(
        val fileKey: String,
        val nodes: MutableList<FigmaNode>,
        val edges: MutableMap<String, FigmaEdge>
    )

    private fun traverseNodesMap(
        nodesMap: JsonObject,
        collector: FigmaTraversalCollector
    ) {
        val defaultParentScope = FigmaParentScope(null, "Page 1", null)
        for ((_, nodeVal) in nodesMap) {
            val targetNode = (nodeVal as? JsonObject)?.let { it["document"]?.jsonObject ?: it }
            if (targetNode != null) {
                traverseNodeTree(collector, targetNode, defaultParentScope)
            }
        }
    }

    private fun traverseRootArray(
        rootElement: JsonArray,
        collector: FigmaTraversalCollector
    ) {
        val defaultParentScope = FigmaParentScope(null, "Page 1", null)
        for (item in rootElement.filterIsInstance<JsonObject>()) {
            traverseNodeTree(collector, item, defaultParentScope)
        }
    }

    private fun traverseDocumentRoots(
        collector: FigmaTraversalCollector,
        rootElement: JsonElement,
        rootObj: JsonObject?
    ) {
        val docObj = rootObj?.get("document")?.jsonObject
        if (docObj != null) {
            traverseNodeTree(collector, docObj, FigmaParentScope(null, "Document", null))
            return
        }

        val nodesMap = rootObj?.get("nodes")?.jsonObject
        if (nodesMap != null) {
            traverseNodesMap(nodesMap, collector)
            return
        }

        if (rootObj != null && (rootObj.containsKey("id") || rootObj.containsKey("type"))) {
            traverseNodeTree(collector, rootObj, FigmaParentScope(null, "Page 1", null))
            return
        }

        if (rootElement is JsonArray) {
            traverseRootArray(rootElement, collector)
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
            FigmaTokenExtractor.extractVariablesFromAstRoot(fileKey, rootObj, extractedVars, extractedEdges)
        }

        traverseDocumentRoots(
            FigmaTraversalCollector(fileKey, extractedNodes, extractedEdges),
            rootElement,
            rootObj
        )

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
            FigmaTokenExtractor.parseFigmaVariablesFormat(fileKey, rootObj, variables, edges)
        } else {
            FigmaTokenExtractor.parseDtcgTokensFormat(fileKey, rootObj, variables, edges)
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

    private data class FigmaNodeIdentity(
        val nodeId: String,
        val fileKey: String,
        val pageName: String,
        val sectionId: String?,
        val parentId: String?,
        val name: String,
        val rawType: String
    )

    private data class FigmaNodeFlags(
        val isInteractive: Boolean,
        val hasEmoji: Boolean,
        val hasReaction: Boolean
    )

    private data class FigmaParentScope(
        val parentId: String?,
        val pageName: String,
        val sectionId: String?
    )

    private data class LayoutSizingKeys(
        val primaryKey: String,
        val aliasKey: String,
        val targetMode: String,
        val counterMode: String
    )

    private fun buildFigmaNode(
        identity: FigmaNodeIdentity,
        nodeObj: JsonObject,
        flags: FigmaNodeFlags
    ): FigmaNode {
        val isInteractive = flags.isInteractive
        val hasEmoji = flags.hasEmoji
        val hasReaction = flags.hasReaction
        val autoLayout = extractAutoLayout(nodeObj)
        val bbox = extractBoundingBox(nodeObj)
        val fills = nodeObj["fills"]?.jsonArray
        val strokes = nodeObj["strokes"]?.jsonArray
        val unboundFill = extractUnboundFill(nodeObj, fills)
        val unboundStroke = extractUnboundStroke(nodeObj, strokes)
        val wcagContrast = nodeObj["wcagContrast"].asDouble() ?: nodeObj["wcag_contrast"].asDouble()
        val semioticLayer = nodeObj["semioticLayer"].asString()
            ?: nodeObj["semiotic_layer"].asString()
            ?: classifySemioticLayer(identity.rawType, identity.name, isInteractive, hasReaction)

        return FigmaNode(
            nodeId = identity.nodeId,
            fileKey = identity.fileKey,
            pageName = identity.pageName,
            sectionId = identity.sectionId,
            parentId = identity.parentId,
            name = identity.name,
            nodeType = identity.rawType,
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
        collector: FigmaTraversalCollector,
        children: JsonArray?,
        parentScope: FigmaParentScope
    ) {
        if (children == null) return
        for (child in children.filterIsInstance<JsonObject>()) {
            traverseNodeTree(collector, child, parentScope)
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
        collector: FigmaTraversalCollector,
        nodeObj: JsonObject,
        parentScope: FigmaParentScope
    ) {
        val nodeId = nodeObj["id"].asString() ?: nodeObj["nodeId"].asString() ?: return
        val name = nodeObj["name"].asString() ?: ""
        val rawType = nodeObj["type"].asString()?.uppercase() ?: "FRAME"

        val effectivePageName = if (rawType == "CANVAS" && name.isNotBlank()) name else parentScope.pageName
        val (currentSectionId, nextSectionId) = resolveNodeSectionScope(nodeObj, nodeId, rawType, parentScope.sectionId)

        val hasEmoji = detectEmoji(nodeObj, name)
        val hasReaction = extractReactions(collector.fileKey, nodeId, nodeObj, collector.edges)
        val isInteractive = detectInteractive(nodeObj, rawType, name, hasReaction)

        extractBoundVariableEdges(collector.fileKey, nodeId, nodeObj, collector.edges)
        if (rawType == "INSTANCE") {
            extractInstantiates(collector.fileKey, nodeId, nodeObj, collector.edges)
        }
        recordContainsEdge(collector.fileKey, parentScope.parentId, nodeId, collector.edges)

        val figmaNode = buildFigmaNode(
            identity = FigmaNodeIdentity(
                nodeId = nodeId,
                fileKey = collector.fileKey,
                pageName = effectivePageName,
                sectionId = currentSectionId,
                parentId = parentScope.parentId,
                name = name,
                rawType = rawType
            ),
            nodeObj = nodeObj,
            flags = FigmaNodeFlags(isInteractive, hasEmoji, hasReaction)
        )
        collector.nodes.add(figmaNode)

        traverseChildren(
            collector,
            nodeObj["children"]?.jsonArray,
            FigmaParentScope(nodeId, effectivePageName, nextSectionId)
        )
    }

    private fun resolveFallbackSizing(
        nodeObj: JsonObject,
        layoutMode: String,
        keys: LayoutSizingKeys
    ): String {
        val align = nodeObj["layoutAlign"].asString()?.uppercase()
        val primarySizing = nodeObj["primaryAxisSizingMode"].asString()?.uppercase()
        val counterSizing = nodeObj["counterAxisSizingMode"].asString()?.uppercase()
        return when {
            align == "STRETCH" && layoutMode == keys.counterMode -> "FILL"
            layoutMode == keys.targetMode && primarySizing == "AUTO" -> "HUG"
            layoutMode == keys.counterMode && counterSizing == "AUTO" -> "HUG"
            else -> "FIXED"
        }
    }

    private fun extractLayoutSizing(
        nodeObj: JsonObject,
        layoutMode: String,
        keys: LayoutSizingKeys
    ): String {
        val explicit = nodeObj[keys.primaryKey].asString()?.uppercase()
            ?: nodeObj[keys.aliasKey].asString()?.uppercase()
        if (explicit != null) {
            return if (explicit in VALID_SIZINGS) explicit else "FIXED"
        }
        val fallback = resolveFallbackSizing(nodeObj, layoutMode, keys)
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
            nodeObj,
            layoutMode,
            LayoutSizingKeys("layoutSizingHorizontal", "sizingHorizontal", "HORIZONTAL", "VERTICAL")
        )
        val sizingV = extractLayoutSizing(
            nodeObj,
            layoutMode,
            LayoutSizingKeys("layoutSizingVertical", "sizingVertical", "VERTICAL", "HORIZONTAL")
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

    private data class UnboundPaintSpec(
        val camelKey: String,
        val snakeKey: String,
        val boundKey: String,
        val styleKey: String
    )

    private fun extractUnboundFill(nodeObj: JsonObject, fills: JsonArray?): Boolean =
        extractUnboundPaint(nodeObj, fills, UNBOUND_FILL_SPEC)

    private fun extractUnboundStroke(nodeObj: JsonObject, strokes: JsonArray?): Boolean =
        extractUnboundPaint(nodeObj, strokes, UNBOUND_STROKE_SPEC)

    private fun extractUnboundPaint(
        nodeObj: JsonObject,
        paints: JsonArray?,
        spec: UnboundPaintSpec
    ): Boolean {
        val explicit = nodeObj[spec.camelKey].asBoolean() ?: nodeObj[spec.snakeKey].asBoolean()
        if (explicit != null) return explicit

        if (paints == null || paints.isEmpty()) return false
        val visiblePaints = paints.filterIsInstance<JsonObject>().filter {
            it["visible"].asBoolean() != false
        }
        if (visiblePaints.isEmpty()) return false

        val boundVars = nodeObj["boundVariables"]?.jsonObject
        val styles = nodeObj["styles"]?.jsonObject
        if (boundVars?.containsKey(spec.boundKey) == true || styles?.containsKey(spec.styleKey) == true) {
            return false
        }

        return visiblePaints.any { it["boundVariables"]?.jsonObject?.isNotEmpty() != true }
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
        private val UNBOUND_FILL_SPEC = UnboundPaintSpec("unboundFill", "unbound_fill", "fills", "fill")
        private val UNBOUND_STROKE_SPEC = UnboundPaintSpec("unboundStroke", "unbound_stroke", "strokes", "stroke")
    }
}
