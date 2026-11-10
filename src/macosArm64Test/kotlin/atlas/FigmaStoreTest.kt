package atlas

import atlas.db.SqliteGraphStore
import atlas.model.*
import atlas.util.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Mock fixture data for hermetic offline testing without live network
class FigmaStoreTest {

    private fun withTempStore(block: (SqliteGraphStore) -> Unit) {
        val dbPath = "/tmp/test_figma_store_${platform.posix.getpid()}_${platform.posix.rand()}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            block(store)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testTableCreationAndDocumentCrud() = withTempStore { store ->
        val fileKey = "LVhI2ueu8A6igT14N8m1MM"
        val doc = FigmaDocument(
            fileKey = fileKey,
            name = "Relay Mobile Design System",
            version = "1.0.0",
            lastModified = "2026-10-16T12:00:00Z",
            thumbnailUrl = "https://figma.com/thumb/relay.png"
        )

        store.insertFigmaDocument(doc)

        val retrieved = store.getFigmaDocument(fileKey)
        assertNotNull(retrieved)
        assertEquals(fileKey, retrieved.fileKey)
        assertEquals("Relay Mobile Design System", retrieved.name)
        assertEquals("1.0.0", retrieved.version)
        assertEquals("2026-10-16T12:00:00Z", retrieved.lastModified)
        assertEquals("https://figma.com/thumb/relay.png", retrieved.thumbnailUrl)

        // Test update
        val updatedDoc = doc.copy(version = "1.1.0", lastModified = "2026-10-16T14:00:00Z")
        store.insertFigmaDocument(updatedDoc)
        val afterUpdate = store.getFigmaDocument(fileKey)
        assertNotNull(afterUpdate)
        assertEquals("1.1.0", afterUpdate.version)
        assertEquals("2026-10-16T14:00:00Z", afterUpdate.lastModified)

        // Non-existent document
        assertNull(store.getFigmaDocument("unknown_key"))
    }

    @Test
    fun testNodeAndSpatialIndexInsertion() = withTempStore { store ->
        val fileKey = "test_file_001"
        val node1 = FigmaNode(
            nodeId = "1:100",
            fileKey = fileKey,
            pageName = "Page 1",
            sectionId = "0:1",
            parentId = "0:1",
            name = "AppHeader",
            nodeType = "FRAME",
            layoutMode = "HORIZONTAL",
            sizingHorizontal = "FILL",
            sizingVertical = "HUG",
            paddingLeft = 16.0,
            paddingRight = 16.0,
            paddingTop = 12.0,
            paddingBottom = 12.0,
            itemSpacing = 8.0,
            localX = 0.0,
            localY = 0.0,
            canvasX = 100.0,
            canvasY = 200.0,
            width = 375.0,
            height = 56.0,
            isInteractive = true,
            hasEmoji = false,
            wcagContrast = 4.8,
            semioticLayer = "pragmatic"
        )
        val node2 = FigmaNode(
            nodeId = "1:101",
            fileKey = fileKey,
            pageName = "Page 1",
            sectionId = "0:1",
            parentId = "1:100",
            name = "TitleText",
            nodeType = "TEXT",
            canvasX = 116.0,
            canvasY = 214.0,
            width = 200.0,
            height = 24.0,
            textAutoResize = "HEIGHT",
            semioticLayer = "syntactic"
        )

        store.insertFigmaNodes(fileKey, listOf(node1, node2))

        val retrieved1 = store.queryFigmaNodeById(fileKey, "1:100")
        assertNotNull(retrieved1)
        assertTrue(retrieved1.rowidPk > 0L)
        assertEquals("AppHeader", retrieved1.name)
        assertEquals("FRAME", retrieved1.nodeType)
        assertEquals("HORIZONTAL", retrieved1.layoutMode)
        assertEquals("FILL", retrieved1.sizingHorizontal)
        assertEquals("HUG", retrieved1.sizingVertical)
        assertEquals(16.0, retrieved1.paddingLeft)
        assertEquals(4.8, retrieved1.wcagContrast)
        assertEquals(100.0, retrieved1.canvasX)
        assertEquals(200.0, retrieved1.canvasY)
        assertEquals(375.0, retrieved1.width)
        assertEquals(56.0, retrieved1.height)
        assertTrue(retrieved1.isInteractive)
        assertEquals("pragmatic", retrieved1.semioticLayer)

        val retrieved2 = store.queryFigmaNodeById(fileKey, "1:101")
        assertNotNull(retrieved2)
        assertEquals("TitleText", retrieved2.name)
        assertEquals("TEXT", retrieved2.nodeType)
        assertEquals("HEIGHT", retrieved2.textAutoResize)
        assertNull(retrieved2.wcagContrast)

        // Query by name and type
        val byName = store.queryFigmaNodesByName(fileKey, "AppHeader")
        assertEquals(1, byName.size)
        assertEquals("1:100", byName[0].nodeId)

        val frames = store.queryFigmaNodesByType(fileKey, "FRAME")
        assertEquals(1, frames.size)
        assertEquals("AppHeader", frames[0].name)

        val texts = store.queryFigmaNodesByType(fileKey, "TEXT")
        assertEquals(1, texts.size)
        assertEquals("TitleText", texts[0].name)
    }

    @Test
    fun testRTreeSpatialIntersectionQueries() = withTempStore { store ->
        val fileKey = "spatial_file"
        // Setup 4 nodes on canvas:
        // Node A: (0, 0) to (400, 800) [Screen 1]
        // Node B: (50, 100) to (350, 150) [Button inside Screen 1]
        // Node C: (600, 0) to (1000, 800) [Screen 2]
        // Node D: (5000, 5000) to (5200, 5200) [Far isolated component]
        val nodeA = FigmaNode(
            nodeId = "s1",
            fileKey = fileKey,
            pageName = "Flows",
            name = "Screen 1 · Login",
            nodeType = "FRAME",
            canvasX = 0.0,
            canvasY = 0.0,
            width = 400.0,
            height = 800.0
        )
        val nodeB = FigmaNode(
            nodeId = "b1",
            fileKey = fileKey,
            pageName = "Flows",
            name = "LoginButton",
            nodeType = "INSTANCE",
            canvasX = 50.0,
            canvasY = 100.0,
            width = 300.0,
            height = 50.0
        )
        val nodeC = FigmaNode(
            nodeId = "s2",
            fileKey = fileKey,
            pageName = "Flows",
            name = "Screen 2 · Home",
            nodeType = "FRAME",
            canvasX = 600.0,
            canvasY = 0.0,
            width = 400.0,
            height = 800.0
        )
        val nodeD = FigmaNode(
            nodeId = "d1",
            fileKey = fileKey,
            pageName = "Design System",
            name = "AvatarComponent",
            nodeType = "COMPONENT",
            canvasX = 5000.0,
            canvasY = 5000.0,
            width = 200.0,
            height = 200.0
        )

        store.insertFigmaNodes(fileKey, listOf(nodeA, nodeB, nodeC, nodeD))

        // Query 1: Bounding box covering Screen 1 and Button only
        val q1 = store.queryFigmaSpatialIntersection(fileKey, minX = 0.0, maxX = 450.0, minY = 0.0, maxY = 850.0)
        assertEquals(2, q1.size)
        val q1Ids = q1.map { it.nodeId }.toSet()
        assertTrue(q1Ids.contains("s1"))
        assertTrue(q1Ids.contains("b1"))

        // Query 2: Bounding box covering Screen 2 only
        val q2 = store.queryFigmaSpatialIntersection(fileKey, minX = 550.0, maxX = 1050.0, minY = 0.0, maxY = 850.0)
        assertEquals(1, q2.size)
        assertEquals("s2", q2[0].nodeId)

        // Query 3: Bounding box covering Screen 1, Button, and Screen 2
        val q3 = store.queryFigmaSpatialIntersection(fileKey, minX = 0.0, maxX = 1100.0, minY = 0.0, maxY = 900.0)
        assertEquals(3, q3.size)
        val q3Ids = q3.map { it.nodeId }.toSet()
        assertTrue(q3Ids.contains("s1"))
        assertTrue(q3Ids.contains("b1"))
        assertTrue(q3Ids.contains("s2"))

        // Query 4: Empty canvas quadrant
        val q4 = store.queryFigmaSpatialIntersection(fileKey, minX = 2000.0, maxX = 3000.0, minY = 2000.0, maxY = 3000.0)
        assertTrue(q4.isEmpty())

        // Query 5: Query with inverted coordinates (minX > maxX, minY > maxY) should be normalized
        val q5 = store.queryFigmaSpatialIntersection(fileKey, minX = 1050.0, maxX = 550.0, minY = 850.0, maxY = 0.0)
        assertEquals(1, q5.size)
        assertEquals("s2", q5[0].nodeId)

        // Query 6: Far component query
        val q6 = store.queryFigmaSpatialIntersection(fileKey, minX = 4900.0, maxX = 5300.0, minY = 4900.0, maxY = 5300.0)
        assertEquals(1, q6.size)
        assertEquals("d1", q6[0].nodeId)
    }

    @Test
    fun testVariableAliasDagImpactResolution() = withTempStore { store ->
        val fileKey = "tokens_file"

        // Primitive tokens (Tier 0)
        val varIris500 = FigmaVariable(
            variableId = "var:iris_500",
            fileKey = fileKey,
            collectionName = "Primitives",
            name = "iris/500",
            resolvedType = "COLOR",
            isAlias = false,
            aliasedVariableId = null,
            modesJson = "{\"Value\": \"#6C5CE7\"}"
        )
        val varNeutral100 = FigmaVariable(
            variableId = "var:neutral_100",
            fileKey = fileKey,
            collectionName = "Primitives",
            name = "neutral/100",
            resolvedType = "COLOR",
            isAlias = false,
            aliasedVariableId = null,
            modesJson = "{\"Value\": \"#F4F5F7\"}"
        )

        // Semantic tokens (Tier 1)
        val varPrimary = FigmaVariable(
            variableId = "var:primary",
            fileKey = fileKey,
            collectionName = "Semantic",
            name = "color/primary",
            resolvedType = "COLOR",
            isAlias = true,
            aliasedVariableId = "var:iris_500",
            modesJson = "{\"Light\": \"#6C5CE7\", \"Dark\": \"#8172F0\"}",
            codeSyntaxCompose = "RelayTheme.colors.primary"
        )
        val varSurface = FigmaVariable(
            variableId = "var:surface",
            fileKey = fileKey,
            collectionName = "Semantic",
            name = "color/surface",
            resolvedType = "COLOR",
            isAlias = true,
            aliasedVariableId = "var:neutral_100",
            modesJson = "{\"Light\": \"#FFFFFF\", \"Dark\": \"#1A1A1A\"}"
        )

        // Component tokens (Tier 2) aliasing semantic tokens
        val varBtnBg = FigmaVariable(
            variableId = "var:btn_bg",
            fileKey = fileKey,
            collectionName = "Component",
            name = "button/background/primary",
            resolvedType = "COLOR",
            isAlias = true,
            aliasedVariableId = "var:primary",
            modesJson = "{}"
        )
        val varBadgeBg = FigmaVariable(
            variableId = "var:badge_bg",
            fileKey = fileKey,
            collectionName = "Component",
            name = "badge/background/primary",
            resolvedType = "COLOR",
            isAlias = true,
            aliasedVariableId = "var:primary",
            modesJson = "{}"
        )

        // Micro-variant token (Tier 3) aliasing component token
        val varBtnHover = FigmaVariable(
            variableId = "var:btn_hover",
            fileKey = fileKey,
            collectionName = "Component",
            name = "button/hover/primary",
            resolvedType = "COLOR",
            isAlias = true,
            aliasedVariableId = "var:btn_bg",
            modesJson = "{}"
        )

        store.insertFigmaVariables(
            fileKey,
            listOf(varIris500, varNeutral100, varPrimary, varSurface, varBtnBg, varBadgeBg, varBtnHover)
        )

        // Insert nodes binding to tokens
        val buttonNode = FigmaNode(
            nodeId = "n:btn",
            fileKey = fileKey,
            pageName = "Components",
            name = "PrimaryButton",
            nodeType = "COMPONENT"
        )
        val badgeNode = FigmaNode(
            nodeId = "n:badge",
            fileKey = fileKey,
            pageName = "Components",
            name = "PrimaryBadge",
            nodeType = "COMPONENT"
        )
        val cardNode = FigmaNode(
            nodeId = "n:card",
            fileKey = fileKey,
            pageName = "Components",
            name = "SurfaceCard",
            nodeType = "COMPONENT"
        )
        store.insertFigmaNodes(fileKey, listOf(buttonNode, badgeNode, cardNode))

        // Insert edges representing token bindings
        val edge1 = FigmaEdge("e1", fileKey, "n:btn", "var:btn_bg", "BINDS_VARIABLE", "fill")
        val edge2 = FigmaEdge("e2", fileKey, "n:badge", "var:badge_bg", "BINDS_VARIABLE", "fill")
        val edge3 = FigmaEdge("e3", fileKey, "n:card", "var:surface", "BINDS_VARIABLE", "fill")
        store.insertFigmaEdges(fileKey, listOf(edge1, edge2, edge3))

        // 1. Resolve alias DAG from root primitive token "iris/500"
        val irisImpactByName = store.resolveVariableAliasImpact(fileKey, "iris/500")
        assertEquals(5, irisImpactByName.size)
        val irisVarNames = irisImpactByName.map { it.name }.toSet()
        assertTrue(irisVarNames.contains("iris/500"))
        assertTrue(irisVarNames.contains("color/primary"))
        assertTrue(irisVarNames.contains("button/background/primary"))
        assertTrue(irisVarNames.contains("badge/background/primary"))
        assertTrue(irisVarNames.contains("button/hover/primary"))
        // Unrelated branch should NOT be present
        assertTrue(!irisVarNames.contains("neutral/100"))
        assertTrue(!irisVarNames.contains("color/surface"))

        // 2. Resolve alias DAG using variable ID
        val irisImpactById = store.resolveVariableAliasImpact(fileKey, "var:iris_500")
        assertEquals(5, irisImpactById.size)

        // 3. Resolve alias DAG from middle node "color/primary"
        val primaryImpact = store.resolveVariableAliasImpact(fileKey, "color/primary")
        assertEquals(4, primaryImpact.size)
        val primaryVarNames = primaryImpact.map { it.name }.toSet()
        assertTrue(primaryVarNames.contains("color/primary"))
        assertTrue(primaryVarNames.contains("button/background/primary"))
        assertTrue(primaryVarNames.contains("badge/background/primary"))
        assertTrue(primaryVarNames.contains("button/hover/primary"))
        // Upstream root should NOT be included in downstream alias DAG
        assertTrue(!primaryVarNames.contains("iris/500"))

        // 4. Test depth limiting
        val depth1Impact = store.resolveVariableAliasImpact(fileKey, "var:iris_500", depth = 1)
        assertEquals(2, depth1Impact.size) // iris/500 + color/primary

        // 5. Test complete blast radius (variables + bound nodes)
        val blastRadius = store.resolveVariableBlastRadius(fileKey, "iris/500")
        assertEquals(5, blastRadius.impactedVariables.size)
        assertEquals(2, blastRadius.boundNodes.size)
        val boundNodeIds = blastRadius.boundNodes.map { it.nodeId }.toSet()
        assertTrue(boundNodeIds.contains("n:btn"))
        assertTrue(boundNodeIds.contains("n:badge"))
        assertTrue(!boundNodeIds.contains("n:card"))
    }

    @Test
    fun testComponentCallersAndEdges() = withTempStore { store ->
        val fileKey = "component_file"

        val masterComponent = FigmaNode(
            nodeId = "cmp:button",
            fileKey = fileKey,
            pageName = "Design System",
            name = "Button",
            nodeType = "COMPONENT"
        )
        val instanceLogin = FigmaNode(
            nodeId = "inst:login_btn",
            fileKey = fileKey,
            pageName = "Flows",
            name = "LoginSubmitButton",
            nodeType = "INSTANCE"
        )
        val instanceCheckout = FigmaNode(
            nodeId = "inst:checkout_btn",
            fileKey = fileKey,
            pageName = "Flows",
            name = "CheckoutConfirmButton",
            nodeType = "INSTANCE"
        )

        store.insertFigmaNodes(fileKey, listOf(masterComponent, instanceLogin, instanceCheckout))

        val edge1 = FigmaEdge("e_inst_1", fileKey, "inst:login_btn", "cmp:button", "INSTANTIATES")
        val edge2 = FigmaEdge("e_inst_2", fileKey, "inst:checkout_btn", "cmp:button", "INSTANTIATES")
        store.insertFigmaEdges(fileKey, listOf(edge1, edge2))

        // Query callers by component name
        val callersByName = store.queryFigmaCallers(fileKey, "Button")
        assertEquals(2, callersByName.size)
        val callerNames = callersByName.map { it.name }.toSet()
        assertTrue(callerNames.contains("LoginSubmitButton"))
        assertTrue(callerNames.contains("CheckoutConfirmButton"))

        // Query callers by component nodeId
        val callersById = store.queryFigmaCallers(fileKey, "cmp:button")
        assertEquals(2, callersById.size)

        // Query edges
        val outgoing = store.queryFigmaEdgesBySource(fileKey, "inst:login_btn")
        assertEquals(1, outgoing.size)
        assertEquals("cmp:button", outgoing[0].targetNodeId)
        assertEquals("INSTANTIATES", outgoing[0].edgeType)

        val incoming = store.queryFigmaEdgesByTarget(fileKey, "cmp:button")
        assertEquals(2, incoming.size)
    }

    @Test
    fun testCascadeDeletion() = withTempStore { store ->
        val fileKey = "delete_test_file"

        store.insertFigmaDocument(FigmaDocument(fileKey, "To Delete"))
        val node = FigmaNode(
            nodeId = "del_node_1",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "Card",
            nodeType = "FRAME",
            canvasX = 10.0,
            canvasY = 20.0,
            width = 100.0,
            height = 100.0
        )
        store.insertFigmaNodes(fileKey, listOf(node))
        val variable = FigmaVariable("del_var_1", fileKey, "Col", "var1", "COLOR")
        store.insertFigmaVariables(fileKey, listOf(variable))
        val edge = FigmaEdge("del_edge_1", fileKey, "del_node_1", "del_var_1", "BINDS_VARIABLE")
        store.insertFigmaEdges(fileKey, listOf(edge))

        // Verify inserted
        assertNotNull(store.getFigmaDocument(fileKey))
        assertNotNull(store.queryFigmaNodeById(fileKey, "del_node_1"))
        assertNotNull(store.queryFigmaVariableById(fileKey, "del_var_1"))
        assertEquals(1, store.queryFigmaSpatialIntersection(fileKey, 0.0, 200.0, 0.0, 200.0).size)

        // Delete
        store.deleteFigmaDocument(fileKey)

        // Verify everything purged
        assertNull(store.getFigmaDocument(fileKey))
        assertNull(store.queryFigmaNodeById(fileKey, "del_node_1"))
        assertNull(store.queryFigmaVariableById(fileKey, "del_var_1"))
        assertTrue(store.queryFigmaSpatialIntersection(fileKey, 0.0, 200.0, 0.0, 200.0).isEmpty())
        assertTrue(store.queryFigmaEdgesBySource(fileKey, "del_node_1").isEmpty())
    }

    @Test
    fun testSpatialUpdateAndNormalizationEdgeCases() = withTempStore { store ->
        val fileKey = "edge_cases_file"

        // 1. Negative width and height (e.g. flipped vector/frame)
        val invertedNode = FigmaNode(
            nodeId = "inv_node",
            fileKey = fileKey,
            pageName = "Canvas",
            name = "InvertedVector",
            nodeType = "VECTOR",
            canvasX = 200.0,
            canvasY = 300.0,
            width = -50.0,
            height = -100.0
        )
        store.insertFigmaNodes(fileKey, listOf(invertedNode))

        val hitInverted = store.queryFigmaSpatialIntersection(fileKey, 140.0, 210.0, 190.0, 310.0)
        assertEquals(1, hitInverted.size)
        assertEquals("inv_node", hitInverted[0].nodeId)

        // 2. Spatial update: node moves from (100, 100) to (800, 800)
        val movingNode = FigmaNode(
            nodeId = "moving_node",
            fileKey = fileKey,
            pageName = "Canvas",
            name = "MovableCard",
            nodeType = "FRAME",
            canvasX = 100.0,
            canvasY = 100.0,
            width = 50.0,
            height = 50.0
        )
        store.insertFigmaNodes(fileKey, listOf(movingNode))

        // Found at initial position
        val initialQuery = store.queryFigmaSpatialIntersection(fileKey, 90.0, 160.0, 90.0, 160.0)
        assertEquals(1, initialQuery.size)
        assertEquals("moving_node", initialQuery[0].nodeId)

        // Update position to (800, 800)
        val updatedNode = movingNode.copy(canvasX = 800.0, canvasY = 800.0)
        store.insertFigmaNodes(fileKey, listOf(updatedNode))

        // Old position is now empty
        val oldPosQuery = store.queryFigmaSpatialIntersection(fileKey, 90.0, 160.0, 90.0, 160.0)
        assertTrue(oldPosQuery.isEmpty())

        // New position contains the updated node
        val newPosQuery = store.queryFigmaSpatialIntersection(fileKey, 790.0, 860.0, 790.0, 860.0)
        assertEquals(1, newPosQuery.size)
        assertEquals("moving_node", newPosQuery[0].nodeId)
        assertEquals(800.0, newPosQuery[0].canvasX)
    }

    @Test
    fun testEmptyAndMissingInputs() = withTempStore { store ->
        val fileKey = "empty_file"

        // Empty insertions should not throw
        store.insertFigmaNodes(fileKey, emptyList())
        store.insertFigmaVariables(fileKey, emptyList())
        store.insertFigmaEdges(fileKey, emptyList())

        // Non-existent variable impact
        val impact = store.resolveVariableAliasImpact(fileKey, "non_existent_var")
        assertTrue(impact.isEmpty())

        val blastRadius = store.resolveVariableBlastRadius(fileKey, "non_existent_var")
        assertTrue(blastRadius.impactedVariables.isEmpty())
        assertTrue(blastRadius.boundNodes.isEmpty())

        // Non-existent spatial intersection
        val spatial = store.queryFigmaSpatialIntersection(fileKey, 0.0, 100.0, 0.0, 100.0)
        assertTrue(spatial.isEmpty())

        // Non-existent callers
        val callers = store.queryFigmaCallers(fileKey, "MissingComponent")
        assertTrue(callers.isEmpty())
    }

    @Test
    fun testRecursiveCallersWithDepth() = withTempStore { store ->
        val fileKey = "callers_recursive_file"

        val cmpButton = FigmaNode(
            nodeId = "cmp:btn",
            fileKey = fileKey,
            pageName = "DS",
            name = "Button",
            nodeType = "COMPONENT"
        )
        val instHeaderBtn = FigmaNode(
            nodeId = "inst:hdr_btn",
            fileKey = fileKey,
            pageName = "DS",
            name = "HeaderButton",
            nodeType = "INSTANCE",
            parentId = "cmp:header"
        )
        val cmpHeader = FigmaNode(
            nodeId = "cmp:header",
            fileKey = fileKey,
            pageName = "DS",
            name = "HeaderComponent",
            nodeType = "COMPONENT"
        )
        val instScreenHeader = FigmaNode(
            nodeId = "inst:screen_hdr",
            fileKey = fileKey,
            pageName = "Flows",
            name = "ScreenHeader",
            nodeType = "INSTANCE",
            parentId = "screen_1"
        )
        val frameScreen1 = FigmaNode(
            nodeId = "screen_1",
            fileKey = fileKey,
            pageName = "Flows",
            name = "HomeScreen",
            nodeType = "FRAME"
        )

        store.insertFigmaNodes(fileKey, listOf(cmpButton, instHeaderBtn, cmpHeader, instScreenHeader, frameScreen1))

        val edge1 = FigmaEdge("e_call_1", fileKey, "inst:hdr_btn", "cmp:btn", "INSTANTIATES")
        val edge2 = FigmaEdge("e_call_2", fileKey, "inst:screen_hdr", "cmp:header", "INSTANTIATES")
        store.insertFigmaEdges(fileKey, listOf(edge1, edge2))

        // Depth 1: only direct caller HeaderButton
        val depth1Callers = store.queryFigmaCallers(fileKey, "Button", depth = 1)
        assertEquals(1, depth1Callers.size)
        assertEquals("inst:hdr_btn", depth1Callers[0].nodeId)

        // Depth 2: both HeaderButton (hop 1) and ScreenHeader (hop 2)
        val depth2Callers = store.queryFigmaCallers(fileKey, "Button", depth = 2)
        assertEquals(2, depth2Callers.size)
        val depth2Ids = depth2Callers.map { it.nodeId }
        assertEquals("inst:hdr_btn", depth2Ids[0])
        assertEquals("inst:screen_hdr", depth2Ids[1])

        // Query by nodeId
        val depth2ById = store.queryFigmaCallers(fileKey, "cmp:btn", depth = 2)
        assertEquals(2, depth2ById.size)

        // Cycle test: Add edge making Button instantiate ScreenHeader
        val cycleEdge = FigmaEdge("e_cycle", fileKey, "cmp:btn", "inst:screen_hdr", "INSTANTIATES")
        store.insertFigmaEdges(fileKey, listOf(cycleEdge))

        // Must terminate cleanly with cycle protection
        val callersWithCycle = store.queryFigmaCallers(fileKey, "Button", depth = 5)
        assertTrue(callersWithCycle.isNotEmpty())
        val callerIdsSet = callersWithCycle.map { it.nodeId }.toSet()
        assertEquals(callersWithCycle.size, callerIdsSet.size)
    }

    @Test
    fun testCircularVariableAliasDetection() = withTempStore { store ->
        val fileKey = "circular_tokens_file"

        // Circular alias: A -> B -> A
        val varA = FigmaVariable(
            variableId = "var:circ_a",
            fileKey = fileKey,
            collectionName = "Tokens",
            name = "token/a",
            resolvedType = "COLOR",
            isAlias = true,
            aliasedVariableId = "var:circ_b"
        )
        val varB = FigmaVariable(
            variableId = "var:circ_b",
            fileKey = fileKey,
            collectionName = "Tokens",
            name = "token/b",
            resolvedType = "COLOR",
            isAlias = true,
            aliasedVariableId = "var:circ_a"
        )

        store.insertFigmaVariables(fileKey, listOf(varA, varB))

        // Must terminate cleanly without infinite loop and return each variable once
        val impact = store.resolveVariableAliasImpact(fileKey, "var:circ_a", depth = 10)
        assertEquals(2, impact.size)
        val impactIds = impact.map { it.variableId }.toSet()
        assertTrue(impactIds.contains("var:circ_a"))
        assertTrue(impactIds.contains("var:circ_b"))

        // Diamond alias graph: root -> left, root -> right, left -> bottom
        val varRoot = FigmaVariable("v:root", fileKey, "Col", "root", "FLOAT")
        val varLeft = FigmaVariable("v:left", fileKey, "Col", "left", "FLOAT", isAlias = true, aliasedVariableId = "v:root")
        val varRight = FigmaVariable("v:right", fileKey, "Col", "right", "FLOAT", isAlias = true, aliasedVariableId = "v:root")
        val varBottom = FigmaVariable("v:bottom", fileKey, "Col", "bottom", "FLOAT", isAlias = true, aliasedVariableId = "v:left")
        store.insertFigmaVariables(fileKey, listOf(varRoot, varLeft, varRight, varBottom))

        val diamondImpact = store.resolveVariableAliasImpact(fileKey, "v:root")
        assertEquals(4, diamondImpact.size)
        val uniqueIds = diamondImpact.map { it.variableId }.toSet()
        assertEquals(4, uniqueIds.size)
    }

    @Test
    fun testVariableAliasByNameAndBinding() = withTempStore { store ->
        val fileKey = "name_alias_file"

        // Primitive token referenced by NAME in alias
        val varPrimitive = FigmaVariable(
            variableId = "var:prim_1",
            fileKey = fileKey,
            collectionName = "Primitives",
            name = "neutral/300",
            resolvedType = "COLOR",
            modesJson = "{\"Value\": \"#CCCCCC\"}"
        )
        val varSemantic = FigmaVariable(
            variableId = "var:sem_1",
            fileKey = fileKey,
            collectionName = "Semantic",
            name = "border/default",
            resolvedType = "COLOR",
            isAlias = true,
            aliasedVariableId = "neutral/300"
        )

        store.insertFigmaVariables(fileKey, listOf(varPrimitive, varSemantic))

        val impact = store.resolveVariableAliasImpact(fileKey, "neutral/300")
        assertEquals(2, impact.size)
        val names = impact.map { it.name }
        assertTrue(names.contains("neutral/300"))
        assertTrue(names.contains("border/default"))

        // Node bound by name
        val cardNode = FigmaNode(
            nodeId = "card_1",
            fileKey = fileKey,
            pageName = "P1",
            name = "Card",
            nodeType = "FRAME"
        )
        store.insertFigmaNodes(fileKey, listOf(cardNode))

        val edgeByName = FigmaEdge("e_bound_name", fileKey, "card_1", "border/default", "BINDS_VARIABLE")
        store.insertFigmaEdges(fileKey, listOf(edgeByName))

        val boundNodes = store.queryNodesBoundToVariable(fileKey, "var:sem_1")
        assertEquals(1, boundNodes.size)
        assertEquals("card_1", boundNodes[0].nodeId)
    }

    @Test
    fun testCrossFileIsolation() = withTempStore { store ->
        val fileA = "file_alpha"
        val fileB = "file_beta"

        store.insertFigmaDocument(FigmaDocument(fileA, "Alpha Doc"))
        store.insertFigmaDocument(FigmaDocument(fileB, "Beta Doc"))

        // Identical local nodeId in both files
        val nodeA = FigmaNode(
            nodeId = "0:1",
            fileKey = fileA,
            pageName = "Page Alpha",
            name = "AlphaRoot",
            nodeType = "CANVAS",
            canvasX = 10.0,
            canvasY = 10.0,
            width = 100.0,
            height = 100.0
        )
        val nodeB = FigmaNode(
            nodeId = "0:1",
            fileKey = fileB,
            pageName = "Page Beta",
            name = "BetaRoot",
            nodeType = "CANVAS",
            canvasX = 500.0,
            canvasY = 500.0,
            width = 200.0,
            height = 200.0
        )

        store.insertFigmaNodes(fileA, listOf(nodeA))
        store.insertFigmaNodes(fileB, listOf(nodeB))

        // Verify neither overwrote the other
        val queryA = store.queryFigmaNodeById(fileA, "0:1")
        assertNotNull(queryA)
        assertEquals("AlphaRoot", queryA.name)
        assertEquals(10.0, queryA.canvasX)

        val queryB = store.queryFigmaNodeById(fileB, "0:1")
        assertNotNull(queryB)
        assertEquals("BetaRoot", queryB.name)
        assertEquals(500.0, queryB.canvasX)

        // Identical variableId in both files
        val varA = FigmaVariable("var:primary", fileA, "Primitives", "color/alpha", "COLOR")
        val varB = FigmaVariable("var:primary", fileB, "Primitives", "color/beta", "COLOR")
        store.insertFigmaVariables(fileA, listOf(varA))
        store.insertFigmaVariables(fileB, listOf(varB))

        assertEquals("color/alpha", store.queryFigmaVariableById(fileA, "var:primary")?.name)
        assertEquals("color/beta", store.queryFigmaVariableById(fileB, "var:primary")?.name)

        // Deleting fileA purges fileA but leaves fileB intact
        store.deleteFigmaDocument(fileA)
        assertNull(store.getFigmaDocument(fileA))
        assertNull(store.queryFigmaNodeById(fileA, "0:1"))
        assertNull(store.queryFigmaVariableById(fileA, "var:primary"))

        assertNotNull(store.getFigmaDocument(fileB))
        assertNotNull(store.queryFigmaNodeById(fileB, "0:1"))
        assertNotNull(store.queryFigmaVariableById(fileB, "var:primary"))
    }

    @Test
    fun testNaNAndInfinityCoordinateSanitization() = withTempStore { store ->
        val fileKey = "nan_test_file"

        val malformedNode = FigmaNode(
            nodeId = "nan_node",
            fileKey = fileKey,
            pageName = "Canvas",
            name = "MalformedGeometryNode",
            nodeType = "FRAME",
            canvasX = Double.NaN,
            canvasY = Double.POSITIVE_INFINITY,
            width = Double.NEGATIVE_INFINITY,
            height = Double.NaN
        )

        store.insertFigmaNodes(fileKey, listOf(malformedNode))

        val retrieved = store.queryFigmaNodeById(fileKey, "nan_node")
        assertNotNull(retrieved)
        assertEquals("nan_node", retrieved.nodeId)

        val results = store.queryFigmaSpatialIntersection(fileKey, Double.NaN, 100.0, 0.0, Double.POSITIVE_INFINITY)
        assertNotNull(results)
    }

    @Test
    fun testLargeBatchInsertionPerformance() = withTempStore { store ->
        val fileKey = "batch_file"
        val batchSize = 2000
        val nodes = (1..batchSize).map { i ->
            FigmaNode(
                nodeId = "batch_node_$i",
                fileKey = fileKey,
                pageName = "BatchPage",
                name = "Item_$i",
                nodeType = "FRAME",
                canvasX = (i % 50) * 100.0,
                canvasY = (i / 50) * 100.0,
                width = 80.0,
                height = 80.0
            )
        }

        store.insertFigmaNodes(fileKey, nodes)

        val quadrantHits = store.queryFigmaSpatialIntersection(
            fileKey,
            minX = 0.0,
            maxX = 200.0,
            minY = 0.0,
            maxY = 200.0
        )
        assertTrue(quadrantHits.isNotEmpty())

        val byType = store.queryFigmaNodesByType(fileKey, "FRAME")
        assertEquals(batchSize, byType.size)
    }
}
