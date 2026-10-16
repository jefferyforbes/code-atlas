package atlas

import atlas.cli.CodeGraphCli
import atlas.db.SqliteGraphStore
import atlas.engine.FigmaDesignAuditor
import atlas.model.*
import atlas.util.PlatformFiles
import kotlin.test.*

class FigmaDesignAuditorTest {

    private fun withTempStore(block: (SqliteGraphStore) -> Unit) {
        val dbPath = "/tmp/test_figma_auditor_${platform.posix.getpid()}_${platform.posix.rand()}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            block(store)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    private fun insertBasicDocument(store: SqliteGraphStore, fileKey: String) {
        store.insertFigmaDocument(
            FigmaDocument(
                fileKey = fileKey,
                name = "Test Figma Document",
                version = "1.0.0"
            )
        )
    }

    @Test
    fun testCircularFillInHug() = withTempStore { store ->
        val fileKey = "circular_test_file"
        insertBasicDocument(store, fileKey)

        val parentV = FigmaNode(
            nodeId = "p_v",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "ParentHugVertical",
            nodeType = "FRAME",
            layoutMode = "VERTICAL",
            sizingVertical = "HUG",
            width = 300.0,
            height = 200.0
        )
        val childV = FigmaNode(
            nodeId = "c_v",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "p_v",
            name = "ChildFillVertical",
            nodeType = "FRAME",
            layoutMode = "VERTICAL",
            sizingVertical = "FILL",
            width = 300.0,
            height = 150.0
        )

        val parentH = FigmaNode(
            nodeId = "p_h",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "ParentHugHorizontal",
            nodeType = "FRAME",
            layoutMode = "HORIZONTAL",
            sizingHorizontal = "HUG",
            width = 300.0,
            height = 100.0
        )
        val childH = FigmaNode(
            nodeId = "c_h",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "p_h",
            name = "ChildFillHorizontal",
            nodeType = "FRAME",
            layoutMode = "HORIZONTAL",
            sizingHorizontal = "FILL",
            width = 250.0,
            height = 100.0
        )

        val validParent = FigmaNode(
            nodeId = "p_valid",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "ParentFixed",
            nodeType = "FRAME",
            layoutMode = "VERTICAL",
            sizingVertical = "FIXED",
            width = 300.0,
            height = 200.0
        )
        val validChild = FigmaNode(
            nodeId = "c_valid",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "p_valid",
            name = "ChildValidFill",
            nodeType = "FRAME",
            layoutMode = "VERTICAL",
            sizingVertical = "FILL",
            width = 300.0,
            height = 100.0
        )

        store.insertFigmaNodes(fileKey, listOf(parentV, childV, parentH, childH, validParent, validChild))

        val auditor = FigmaDesignAuditor(store)
        val violations = auditor.checkCircularFillInHug(fileKey)

        assertEquals(2, violations.size)
        assertTrue(violations.any { it.rule == "CIRCULAR_V_FILL_IN_HUG" && it.nodeId == "c_v" })
        assertTrue(violations.any { it.rule == "CIRCULAR_H_FILL_IN_HUG" && it.nodeId == "c_h" })
    }

    @Test
    fun testStaticContainerFrames() = withTempStore { store ->
        val fileKey = "static_frame_test"
        insertBasicDocument(store, fileKey)

        val badButton = FigmaNode(
            nodeId = "btn_1",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "PrimaryButton",
            nodeType = "COMPONENT",
            layoutMode = "NONE",
            width = 120.0,
            height = 48.0,
            isInteractive = true
        )
        val goodButton = FigmaNode(
            nodeId = "btn_2",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "SecondaryButton",
            nodeType = "COMPONENT",
            layoutMode = "HORIZONTAL",
            width = 120.0,
            height = 48.0,
            isInteractive = true
        )
        val staticGraphic = FigmaNode(
            nodeId = "rect_1",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "BackgroundRect",
            nodeType = "RECTANGLE",
            layoutMode = "NONE",
            width = 200.0,
            height = 200.0
        )

        store.insertFigmaNodes(fileKey, listOf(badButton, goodButton, staticGraphic))

        val auditor = FigmaDesignAuditor(store)
        val violations = auditor.checkStaticContainerFrames(fileKey)

        assertEquals(1, violations.size)
        assertEquals("STATIC_CONTAINER_FRAME", violations[0].rule)
        assertEquals("btn_1", violations[0].nodeId)
    }

    @Test
    fun testDefault100PxFrameHeight() = withTempStore { store ->
        val fileKey = "default_height_test"
        insertBasicDocument(store, fileKey)

        val stuckFrame = FigmaNode(
            nodeId = "f_stuck",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "parent_screen",
            name = "InnerTagPill",
            nodeType = "FRAME",
            sizingVertical = "FIXED",
            height = 100.0,
            width = 200.0
        )
        val properPill = FigmaNode(
            nodeId = "f_proper",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "parent_screen",
            name = "ProperPill",
            nodeType = "FRAME",
            sizingVertical = "HUG",
            height = 100.0,
            width = 200.0
        )
        val normalFrame = FigmaNode(
            nodeId = "f_normal",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "parent_screen",
            name = "NormalFrame",
            nodeType = "FRAME",
            sizingVertical = "FIXED",
            height = 48.0,
            width = 200.0
        )

        store.insertFigmaNodes(fileKey, listOf(stuckFrame, properPill, normalFrame))

        val auditor = FigmaDesignAuditor(store)
        val violations = auditor.checkDefault100PxFrameHeight(fileKey)

        assertEquals(1, violations.size)
        assertEquals("DEFAULT_100PX_FRAME_HEIGHT", violations[0].rule)
        assertEquals("f_stuck", violations[0].nodeId)
    }

    @Test
    fun testMinTouchTarget() = withTempStore { store ->
        val fileKey = "touch_target_test"
        insertBasicDocument(store, fileKey)

        val smallBadIcon = FigmaNode(
            nodeId = "icon_bad",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "CloseIcon",
            nodeType = "FRAME",
            width = 24.0,
            height = 24.0,
            isInteractive = true
        )

        val targetParent = FigmaNode(
            nodeId = "touch_wrapper",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "TouchTargetFrame",
            nodeType = "FRAME",
            layoutMode = "HORIZONTAL",
            width = 48.0,
            height = 48.0
        )
        val wrappedIcon = FigmaNode(
            nodeId = "icon_wrapped",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "touch_wrapper",
            name = "WrappedCloseIcon",
            nodeType = "FRAME",
            width = 24.0,
            height = 24.0,
            isInteractive = true
        )

        val nonInteractiveIcon = FigmaNode(
            nodeId = "icon_static",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "DecorativeIcon",
            nodeType = "FRAME",
            width = 20.0,
            height = 20.0,
            isInteractive = false
        )

        store.insertFigmaNodes(fileKey, listOf(smallBadIcon, targetParent, wrappedIcon, nonInteractiveIcon))

        val auditor = FigmaDesignAuditor(store)
        val violations = auditor.checkMinTouchTargets(fileKey)

        assertEquals(1, violations.size)
        assertEquals("MIN_TOUCH_TARGET", violations[0].rule)
        assertEquals("icon_bad", violations[0].nodeId)
    }

    @Test
    fun testNegativeCoordinates() = withTempStore { store ->
        val fileKey = "neg_coords_test"
        insertBasicDocument(store, fileKey)

        val badChild = FigmaNode(
            nodeId = "c_neg",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "p_container",
            name = "ClippedChild",
            nodeType = "FRAME",
            localX = -12.0,
            localY = 0.0,
            width = 100.0,
            height = 50.0
        )
        val goodChild = FigmaNode(
            nodeId = "c_pos",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "p_container",
            name = "CleanChild",
            nodeType = "FRAME",
            localX = 0.0,
            localY = 8.0,
            width = 100.0,
            height = 50.0
        )

        store.insertFigmaNodes(fileKey, listOf(badChild, goodChild))

        val auditor = FigmaDesignAuditor(store)
        val violations = auditor.checkNegativeCoordinates(fileKey)

        assertEquals(1, violations.size)
        assertEquals("NEGATIVE_COORDINATES", violations[0].rule)
        assertEquals("c_neg", violations[0].nodeId)
    }

    @Test
    fun testChildAmputationWidth() = withTempStore { store ->
        val fileKey = "amputation_test"
        insertBasicDocument(store, fileKey)

        val paddedParent = FigmaNode(
            nodeId = "p_padded",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "PaddedCard",
            nodeType = "FRAME",
            layoutMode = "VERTICAL",
            paddingLeft = 16.0,
            paddingRight = 16.0,
            width = 375.0,
            height = 200.0
        )
        val amputatedChild = FigmaNode(
            nodeId = "c_amp",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "p_padded",
            name = "AmputatedRow",
            nodeType = "FRAME",
            sizingHorizontal = "FIXED",
            width = 375.0,
            height = 40.0
        )
        val flexibleChild = FigmaNode(
            nodeId = "c_fill",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "p_padded",
            name = "FillRow",
            nodeType = "FRAME",
            sizingHorizontal = "FILL",
            width = 343.0,
            height = 40.0
        )

        store.insertFigmaNodes(fileKey, listOf(paddedParent, amputatedChild, flexibleChild))

        val auditor = FigmaDesignAuditor(store)
        val violations = auditor.checkChildAmputationWidth(fileKey)

        assertEquals(1, violations.size)
        assertEquals("CHILD_AMPUTATION_WIDTH", violations[0].rule)
        assertEquals("c_amp", violations[0].nodeId)
    }

    @Test
    fun testNoEmoji() = withTempStore { store ->
        val fileKey = "emoji_test"
        insertBasicDocument(store, fileKey)

        val emojiNode = FigmaNode(
            nodeId = "node_emoji",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "Settings ⚙️",
            nodeType = "TEXT",
            hasEmoji = true
        )
        val cleanNode = FigmaNode(
            nodeId = "node_clean",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "Settings",
            nodeType = "TEXT",
            hasEmoji = false
        )

        store.insertFigmaNodes(fileKey, listOf(emojiNode, cleanNode))

        val auditor = FigmaDesignAuditor(store)
        val violations = auditor.checkNoEmoji(fileKey)

        assertEquals(1, violations.size)
        assertEquals("NO_EMOJI", violations[0].rule)
        assertEquals("node_emoji", violations[0].nodeId)
    }

    @Test
    fun testUnboundTokensRatioThreshold() = withTempStore { store ->
        val fileKey = "tokens_test"
        insertBasicDocument(store, fileKey)

        // 10 nodes total, 1 unbound = 10% (> 5% threshold)
        val nodesFail = (1..9).map { i ->
            FigmaNode(
                nodeId = "n_bound_$i",
                fileKey = fileKey,
                pageName = "Page 1",
                name = "BoundNode$i",
                nodeType = "FRAME",
                unboundFill = false,
                unboundStroke = false
            )
        }.toMutableList()
        nodesFail.add(
            FigmaNode(
                nodeId = "n_unbound_bad",
                fileKey = fileKey,
                pageName = "Page 1",
                name = "UnboundNode",
                nodeType = "FRAME",
                unboundFill = true
            )
        )
        store.insertFigmaNodes(fileKey, nodesFail)

        val auditor = FigmaDesignAuditor(store)
        val violationsFail = auditor.checkUnboundTokens(fileKey)
        assertEquals(1, violationsFail.size)
        assertEquals("UNBOUND_TOKENS", violationsFail[0].rule)
        assertEquals("n_unbound_bad", violationsFail[0].nodeId)

        // Add 15 more bound nodes -> 1 out of 25 unbound = 4% (<= 5% threshold)
        val fileKeyPass = "tokens_pass"
        insertBasicDocument(store, fileKeyPass)
        val nodesPass = (1..24).map { i ->
            FigmaNode(
                nodeId = "n_pass_$i",
                fileKey = fileKeyPass,
                pageName = "Page 1",
                name = "BoundNode$i",
                nodeType = "FRAME",
                unboundFill = false,
                unboundStroke = false
            )
        }.toMutableList()
        nodesPass.add(
            FigmaNode(
                nodeId = "n_pass_unbound",
                fileKey = fileKeyPass,
                pageName = "Page 1",
                name = "MinorUnboundNode",
                nodeType = "FRAME",
                unboundFill = true
            )
        )
        store.insertFigmaNodes(fileKeyPass, nodesPass)

        val violationsPass = auditor.checkUnboundTokens(fileKeyPass)
        assertEquals(0, violationsPass.size)
    }

    @Test
    fun testAdHocDetachedSpecimen() = withTempStore { store ->
        val fileKey = "gym_specimen_test"
        insertBasicDocument(store, fileKey)

        val gymArena = FigmaNode(
            nodeId = "gym_arena",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "Button Generative Gym Arena",
            nodeType = "FRAME",
            canvasX = 4000.0,
            canvasY = 5000.0,
            width = 1200.0,
            height = 800.0
        )
        val bay = FigmaNode(
            nodeId = "gym_bay_1",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "gym_arena",
            name = "Bay 1 - Pressed State",
            nodeType = "FRAME",
            canvasX = 4050.0,
            canvasY = 5050.0,
            width = 300.0,
            height = 100.0
        )
        val detachedSpecimen = FigmaNode(
            nodeId = "bad_specimen",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "gym_bay_1",
            name = "AdHocButtonBox",
            nodeType = "FRAME", // Detached static FRAME
            canvasX = 4060.0,
            canvasY = 5060.0,
            width = 120.0,
            height = 48.0
        )
        val liveInstance = FigmaNode(
            nodeId = "good_specimen",
            fileKey = fileKey,
            pageName = "Page 1",
            parentId = "gym_bay_1",
            name = "LiveMasterInstance",
            nodeType = "INSTANCE", // Valid live INSTANCE
            canvasX = 4200.0,
            canvasY = 5060.0,
            width = 120.0,
            height = 48.0
        )

        store.insertFigmaNodes(fileKey, listOf(gymArena, bay, detachedSpecimen, liveInstance))

        val auditor = FigmaDesignAuditor(store)
        val violations = auditor.checkAdHocDetachedSpecimens(fileKey)

        assertEquals(1, violations.size)
        assertEquals("AD_HOC_DETACHED_SPECIMEN", violations[0].rule)
        assertEquals("bad_specimen", violations[0].nodeId)
    }

    @Test
    fun testCanvasSpatialTopology() = withTempStore { store ->
        val fileKey = "spatial_grid_test"
        insertBasicDocument(store, fileKey)

        // Valid Row 1 user flow
        val flowSection = FigmaNode(
            nodeId = "sec_01",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "01 · Web/Landing",
            nodeType = "CANVAS_SECTION",
            canvasX = 0.0,
            canvasY = 0.0,
            width = 3600.0,
            height = 2400.0
        )
        // Valid Row 2 Col 1 Design System
        val dsSection = FigmaNode(
            nodeId = "sec_ds",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "Design System Components",
            nodeType = "CANVAS_SECTION",
            canvasX = 0.0,
            canvasY = 5000.0,
            width = 3600.0,
            height = 2400.0
        )
        // Valid Row 2 Col 2 Gym
        val gymSection = FigmaNode(
            nodeId = "sec_gym",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "Generative Gym Arena",
            nodeType = "CANVAS_SECTION",
            canvasX = 4000.0,
            canvasY = 5000.0,
            width = 3600.0,
            height = 2400.0
        )
        // Displaced invalid section
        val badSection = FigmaNode(
            nodeId = "sec_bad",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "Displaced Flow",
            nodeType = "CANVAS_SECTION",
            canvasX = 1000.0,
            canvasY = 2200.0, // Off-grid between row 1 and 2
            width = 1000.0,
            height = 800.0
        )

        store.insertFigmaNodes(fileKey, listOf(flowSection, dsSection, gymSection, badSection))

        val auditor = FigmaDesignAuditor(store)
        val violations = auditor.checkCanvasSpatialTopology(fileKey)

        assertEquals(1, violations.size)
        assertEquals("CANVAS_SPATIAL_TOPOLOGY", violations[0].rule)
        assertEquals("sec_bad", violations[0].nodeId)
    }

    @Test
    fun testWcagContrast() = withTempStore { store ->
        val fileKey = "wcag_test"
        insertBasicDocument(store, fileKey)

        // Normal text (< 24 height): needs >= 4.5
        val lowContrastNormal = FigmaNode(
            nodeId = "text_normal_low",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "BodyTextLow",
            nodeType = "TEXT",
            height = 16.0,
            wcagContrast = 3.2
        )
        val goodContrastNormal = FigmaNode(
            nodeId = "text_normal_good",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "BodyTextGood",
            nodeType = "TEXT",
            height = 16.0,
            wcagContrast = 5.5
        )

        // Large text (>= 24 height): needs >= 3.0
        val lowContrastLarge = FigmaNode(
            nodeId = "text_large_low",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "HeadlineLow",
            nodeType = "TEXT",
            height = 32.0,
            wcagContrast = 2.4
        )
        val goodContrastLarge = FigmaNode(
            nodeId = "text_large_good",
            fileKey = fileKey,
            pageName = "Page 1",
            name = "HeadlineGood",
            nodeType = "TEXT",
            height = 32.0,
            wcagContrast = 3.5 // >= 3.0 passes for large text!
        )

        store.insertFigmaNodes(fileKey, listOf(lowContrastNormal, goodContrastNormal, lowContrastLarge, goodContrastLarge))

        val auditor = FigmaDesignAuditor(store)
        val violations = auditor.checkWcagContrast(fileKey)

        assertEquals(2, violations.size)
        assertTrue(violations.any { it.nodeId == "text_normal_low" })
        assertTrue(violations.any { it.nodeId == "text_large_low" })
    }

    @Test
    fun testGate1MaturityScoreAndFullAudit() = withTempStore { store ->
        val cleanKey = "clean_file"
        insertBasicDocument(store, cleanKey)

        val cleanSection = FigmaNode(
            nodeId = "clean_sec",
            fileKey = cleanKey,
            pageName = "Page 1",
            name = "01 · Web/Landing",
            nodeType = "CANVAS_SECTION",
            canvasX = 0.0,
            canvasY = 0.0,
            width = 1200.0,
            height = 800.0
        )
        val cleanBtn = FigmaNode(
            nodeId = "clean_btn",
            fileKey = cleanKey,
            pageName = "Page 1",
            parentId = "clean_sec",
            name = "CleanButton",
            nodeType = "COMPONENT",
            layoutMode = "HORIZONTAL",
            sizingHorizontal = "HUG",
            sizingVertical = "HUG",
            width = 100.0,
            height = 48.0,
            isInteractive = true,
            hasEmoji = false,
            unboundFill = false,
            unboundStroke = false
        )
        store.insertFigmaNodes(cleanKey, listOf(cleanSection, cleanBtn))

        val auditor = FigmaDesignAuditor(store)
        val cleanResult = auditor.audit(cleanKey)

        assertTrue(cleanResult.passed)
        assertEquals(95.0, cleanResult.maturityScore) // Capped at 95.0
        assertEquals(0, cleanResult.violations.size)
        assertEquals(4, cleanResult.polishDelta.size)
        assertTrue(cleanResult.polishDelta.any { it.contains("Optical kerning") })

        // Failing file with violations
        val dirtyKey = "dirty_file"
        insertBasicDocument(store, dirtyKey)
        val dirtySection = FigmaNode(
            nodeId = "dirty_sec",
            fileKey = dirtyKey,
            pageName = "Page 1",
            name = "Dirty Section 01",
            nodeType = "CANVAS_SECTION",
            canvasX = 0.0,
            canvasY = 1500.0, // Topology error
            width = 1200.0,
            height = 800.0
        )
        val dirtyBtn = FigmaNode(
            nodeId = "dirty_btn",
            fileKey = dirtyKey,
            pageName = "Page 1",
            parentId = "dirty_sec",
            name = "Bad Button 🚫", // Emoji error
            nodeType = "COMPONENT",
            layoutMode = "NONE", // Static frame error
            sizingVertical = "FIXED",
            height = 100.0, // Default 100px error
            width = 30.0, // Min touch target error
            isInteractive = true,
            hasEmoji = true,
            unboundFill = true // Unbound tokens error
        )
        store.insertFigmaNodes(dirtyKey, listOf(dirtySection, dirtyBtn))

        val dirtyResult = auditor.audit(dirtyKey)
        assertFalse(dirtyResult.passed)
        assertTrue(dirtyResult.maturityScore < 80.0)
        assertTrue(dirtyResult.violations.isNotEmpty())
    }

    @Test
    fun testSynthesizeFigmaScope() = withTempStore { store ->
        val fileKey = "scope_synthesis_file"
        insertBasicDocument(store, fileKey)

        val secVoice = FigmaNode(
            nodeId = "sec_voice",
            fileKey = fileKey,
            pageName = "Voice Studio",
            name = "Section 03 · Voice Studio",
            nodeType = "CANVAS_SECTION",
            canvasX = 14400.0,
            canvasY = 0.0,
            width = 4000.0,
            height = 3000.0
        )
        val secTasks = FigmaNode(
            nodeId = "sec_tasks",
            fileKey = fileKey,
            pageName = "Tasks",
            name = "Section 04 · Task Center",
            nodeType = "CANVAS_SECTION",
            canvasX = 18800.0,
            canvasY = 0.0,
            width = 4000.0,
            height = 3000.0
        )

        // Master Component
        val masterBar = FigmaNode(
            nodeId = "master_bar",
            fileKey = fileKey,
            pageName = "Design System",
            name = "AppBottomBar",
            nodeType = "COMPONENT",
            canvasX = 0.0,
            canvasY = 5000.0,
            width = 375.0,
            height = 64.0
        )

        // Instances in Voice and Tasks
        val instVoice = FigmaNode(
            nodeId = "inst_voice_bar",
            fileKey = fileKey,
            pageName = "Voice Studio",
            parentId = "sec_voice",
            name = "AppBottomBarInstanceVoice",
            nodeType = "INSTANCE",
            canvasX = 14500.0,
            canvasY = 500.0,
            width = 375.0,
            height = 64.0
        )
        val instTasks = FigmaNode(
            nodeId = "inst_tasks_bar",
            fileKey = fileKey,
            pageName = "Tasks",
            parentId = "sec_tasks",
            name = "AppBottomBarInstanceTasks",
            nodeType = "INSTANCE",
            canvasX = 18900.0,
            canvasY = 500.0,
            width = 375.0,
            height = 64.0
        )

        // Tokens
        val varPrim = FigmaVariable(
            variableId = "var_prim_iris",
            fileKey = fileKey,
            collectionName = "Primitives",
            name = "iris/500",
            resolvedType = "COLOR",
            modesJson = "{\"Light\":\"#6C5CE7\"}"
        )
        val varSem = FigmaVariable(
            variableId = "var_sem_primary",
            fileKey = fileKey,
            collectionName = "Semantic",
            name = "color/primary",
            resolvedType = "COLOR",
            isAlias = true,
            aliasedVariableId = "var_prim_iris",
            modesJson = "{\"Light\":\"#6C5CE7\"}"
        )

        store.insertFigmaNodes(fileKey, listOf(secVoice, secTasks, masterBar, instVoice, instTasks))
        store.insertFigmaVariables(fileKey, listOf(varPrim, varSem))

        // Edges
        val edges = listOf(
            FigmaEdge("e1", fileKey, "inst_voice_bar", "master_bar", "INSTANTIATES"),
            FigmaEdge("e2", fileKey, "inst_tasks_bar", "master_bar", "INSTANTIATES"),
            FigmaEdge("e3", fileKey, "inst_voice_bar", "var_sem_primary", "BINDS_VARIABLE")
        )
        store.insertFigmaEdges(fileKey, edges)

        val auditor = FigmaDesignAuditor(store)
        val scope = auditor.synthesizeFigmaScope(
            project = "relay",
            fileKey = fileKey,
            seedNodeNames = listOf("Section 03 · Voice Studio")
        )

        assertEquals("relay", scope.project)
        assertEquals(fileKey, scope.fileKey)
        assertEquals(14400.0, scope.spatialBounds.minX)
        assertEquals(18400.0, scope.spatialBounds.maxX)
        assertEquals(0.0, scope.spatialBounds.minY)
        assertEquals(3000.0, scope.spatialBounds.maxY)

        // Scoped nodes should include secVoice and instVoice
        assertTrue(scope.scopedNodesCount >= 2)

        // Shared master component detection
        assertTrue(scope.sharedMasterComponents.contains("AppBottomBar"))
        assertEquals(1, scope.potentialCollisions.size)
        assertTrue(scope.potentialCollisions[0].contains("AppBottomBar"))

        // Token layers should include bound variable and its alias primitive
        assertTrue(scope.tokenLayers.contains("var_sem_primary") || scope.tokenLayers.contains("color/primary"))
        assertTrue(scope.tokenLayers.contains("iris/500"))
    }

    @Test
    fun testCliEndToEnd() {
        val dbPath = "/tmp/test_figma_cli_${platform.posix.getpid()}_${platform.posix.rand()}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        val fileKey = "cli_e2e_key"

        try {
            store.insertFigmaDocument(FigmaDocument(fileKey = fileKey, name = "CLI Test Doc"))
            val cleanNode = FigmaNode(
                nodeId = "cli_clean_btn",
                fileKey = fileKey,
                pageName = "Page 1",
                name = "CleanButton",
                nodeType = "COMPONENT",
                layoutMode = "HORIZONTAL",
                width = 120.0,
                height = 48.0,
                isInteractive = true
            )
            store.insertFigmaNodes(fileKey, listOf(cleanNode))
            store.close()

            // Run audit CLI subcommand
            val auditExit = CodeGraphCli.run(
                arrayOf("figma", "audit", "--project", "test_proj", "--file", fileKey, "--db", dbPath)
            )
            assertEquals(0, auditExit)

            // Run query CLI subcommand: symbol
            val queryExit = CodeGraphCli.run(
                arrayOf("figma", "query", "--project", "test_proj", "--file", fileKey, "--action", "symbol", "--target", "CleanButton", "--db", dbPath)
            )
            assertEquals(0, queryExit)

            // Run synthesize-scope CLI subcommand
            val scopeExit = CodeGraphCli.run(
                arrayOf("figma", "synthesize-scope", "--project", "test_proj", "--file", fileKey, "--seeds", "CleanButton", "--db", dbPath)
            )
            assertEquals(0, scopeExit)
        } finally {
            PlatformFiles.delete(dbPath)
        }
    }
}
