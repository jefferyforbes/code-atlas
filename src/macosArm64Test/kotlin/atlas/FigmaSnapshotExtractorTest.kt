package atlas

import atlas.db.SqliteGraphStore
import atlas.extract.FigmaSnapshotExtractor
import atlas.model.FigmaSpatialBounds
import atlas.util.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Mock fixture data for hermetic offline testing without live network
class FigmaSnapshotExtractorTest {

    private fun withTempStore(block: (SqliteGraphStore) -> Unit) {
        val dbPath = "/tmp/test_figma_extractor_${platform.posix.getpid()}_${platform.posix.rand()}.db"
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
    fun testExtractRealisticFigmaAstJsonAndVectorBloatStripping() {
        val fileKey = "relay_design_file"
        val extractor = FigmaSnapshotExtractor()

        val astJson = """
        {
          "name": "Relay Mobile Design System",
          "version": "2.4.0",
          "lastModified": "2026-10-16T12:30:00Z",
          "thumbnailUrl": "https://figma.com/thumb/relay_ds.png",
          "document": {
            "id": "0:0",
            "name": "Document",
            "type": "DOCUMENT",
            "children": [
              {
                "id": "0:1",
                "name": "01 · Components",
                "type": "CANVAS",
                "children": [
                  {
                    "id": "1:10",
                    "name": "Section 01 · Primitives",
                    "type": "SECTION",
                    "absoluteBoundingBox": { "x": 0.0, "y": 0.0, "width": 4000.0, "height": 3000.0 },
                    "children": [
                      {
                        "id": "1:50",
                        "name": "Button",
                        "type": "COMPONENT_SET",
                        "layoutMode": "HORIZONTAL",
                        "layoutSizingHorizontal": "FIXED",
                        "layoutSizingVertical": "HUG",
                        "absoluteBoundingBox": { "x": 100.0, "y": 200.0, "width": 800.0, "height": 200.0 },
                        "children": [
                          {
                            "id": "1:51",
                            "name": "Button/Primary",
                            "type": "COMPONENT",
                            "layoutMode": "HORIZONTAL",
                            "layoutSizingHorizontal": "HUG",
                            "layoutSizingVertical": "FIXED",
                            "paddingLeft": 16.0,
                            "paddingRight": 16.0,
                            "paddingTop": 12.0,
                            "paddingBottom": 12.0,
                            "itemSpacing": 8.0,
                            "absoluteBoundingBox": { "x": 120.0, "y": 220.0, "width": 140.0, "height": 48.0 },
                            "boundVariables": {
                              "fills": [ { "type": "VARIABLE_ALIAS", "id": "color/primary" } ]
                            },
                            "fills": [
                              {
                                "type": "SOLID",
                                "visible": true,
                                "boundVariables": {
                                  "color": { "type": "VARIABLE_ALIAS", "id": "color/primary" }
                                }
                              }
                            ],
                            "children": [
                              {
                                "id": "1:52",
                                "name": "LabelText",
                                "type": "TEXT",
                                "characters": "Submit",
                                "textAutoResize": "WIDTH_AND_HEIGHT",
                                "absoluteBoundingBox": { "x": 136.0, "y": 234.0, "width": 80.0, "height": 20.0 }
                              }
                            ]
                          }
                        ]
                      },
                      {
                        "id": "2:100",
                        "name": "SubmitButtonInstance",
                        "type": "INSTANCE",
                        "componentId": "1:51",
                        "componentSetId": "1:50",
                        "layoutMode": "HORIZONTAL",
                        "layoutSizingHorizontal": "FILL",
                        "layoutSizingVertical": "HUG",
                        "absoluteBoundingBox": { "x": 500.0, "y": 220.0, "width": 200.0, "height": 48.0 },
                        "reactions": [
                          {
                            "trigger": { "type": "ON_CLICK" },
                            "action": {
                              "type": "NODE",
                              "destinationId": "screen:dashboard",
                              "navigation": "NAVIGATE"
                            }
                          }
                        ]
                      },
                      {
                        "id": "3:200",
                        "name": "VectorIconNode",
                        "type": "VECTOR",
                        "absoluteBoundingBox": { "x": 800.0, "y": 200.0, "width": 24.0, "height": 24.0 },
                        "vectorPaths": [
                          { "windingRule": "NONZERO", "data": "M 0 0 L 24 0 L 24 24 L 0 24 Z M 5 5 L 19 19" }
                        ],
                        "fillGeometry": [
                          { "path": "M 0 0 L 24 0 L 24 24 Z" }
                        ],
                        "strokeGeometry": [
                          { "path": "M 5 5 L 19 19" }
                        ],
                        "svg": "<svg xmlns='http://www.w3.org/2000/svg' width='24' height='24'><path d='M0 0h24v24H0z'/></svg>",
                        "fills": [
                          {
                            "type": "SOLID",
                            "visible": true,
                            "color": { "r": 0.8, "g": 0.2, "b": 0.2, "a": 1.0 }
                          }
                        ]
                      },
                      {
                        "id": "4:300",
                        "name": "EmojiHeader \uD83D\uDE80",
                        "type": "FRAME",
                        "absoluteBoundingBox": { "x": 900.0, "y": 200.0, "width": 300.0, "height": 60.0 },
                        "children": [
                          {
                            "id": "4:301",
                            "name": "SubText",
                            "type": "TEXT",
                            "characters": "Welcome to Relay \uD83D\uDC4B",
                            "absoluteBoundingBox": { "x": 910.0, "y": 210.0, "width": 200.0, "height": 24.0 }
                          }
                        ]
                      }
                    ]
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

        val snapshot = extractor.extractDocument(fileKey, astJson)

        // 1. Verify Document metadata
        assertEquals(fileKey, snapshot.document.fileKey)
        assertEquals("Relay Mobile Design System", snapshot.document.name)
        assertEquals("2.4.0", snapshot.document.version)
        assertEquals("2026-10-16T12:30:00Z", snapshot.document.lastModified)
        assertEquals("https://figma.com/thumb/relay_ds.png", snapshot.document.thumbnailUrl)

        // 2. Verify Nodes extraction
        val nodesById = snapshot.nodes.associateBy { it.nodeId }
        assertTrue(nodesById.containsKey("1:10"))
        assertTrue(nodesById.containsKey("1:50"))
        assertTrue(nodesById.containsKey("1:51"))
        assertTrue(nodesById.containsKey("1:52"))
        assertTrue(nodesById.containsKey("2:100"))
        assertTrue(nodesById.containsKey("3:200"))
        assertTrue(nodesById.containsKey("4:300"))
        assertTrue(nodesById.containsKey("4:301"))

        // 3. Verify AutoLayout parameters
        val btnPrimary = nodesById["1:51"]!!
        assertEquals("01 · Components", btnPrimary.pageName)
        assertEquals("1:10", btnPrimary.sectionId)
        assertEquals("HORIZONTAL", btnPrimary.layoutMode)
        assertEquals("HUG", btnPrimary.sizingHorizontal)
        assertEquals("FIXED", btnPrimary.sizingVertical)
        assertEquals(16.0, btnPrimary.paddingLeft)
        assertEquals(16.0, btnPrimary.paddingRight)
        assertEquals(12.0, btnPrimary.paddingTop)
        assertEquals(12.0, btnPrimary.paddingBottom)
        assertEquals(8.0, btnPrimary.itemSpacing)
        assertEquals(120.0, btnPrimary.canvasX)
        assertEquals(220.0, btnPrimary.canvasY)
        assertEquals(140.0, btnPrimary.width)
        assertEquals(48.0, btnPrimary.height)
        assertFalse(btnPrimary.unboundFill) // Fill is bound to color/primary!

        // 4. Verify Text node auto-resize
        val label = nodesById["1:52"]!!
        assertEquals("WIDTH_AND_HEIGHT", label.textAutoResize)
        assertEquals("1:51", label.parentId)

        // 5. Verify Instance & Interaction
        val instance = nodesById["2:100"]!!
        assertEquals("INSTANCE", instance.nodeType)
        assertEquals("FILL", instance.sizingHorizontal)
        assertEquals("HUG", instance.sizingVertical)
        assertTrue(instance.isInteractive) // Has prototype reaction!

        // 6. Verify Vector Bloat Stripped
        val vectorNode = nodesById["3:200"]!!
        assertEquals(800.0, vectorNode.canvasX)
        assertEquals(200.0, vectorNode.canvasY)
        assertEquals(24.0, vectorNode.width)
        assertEquals(24.0, vectorNode.height)
        assertTrue(vectorNode.unboundFill) // Has raw un-aliased fill!

        // 7. Verify Emoji Detection
        val emojiHeader = nodesById["4:300"]!!
        assertTrue(emojiHeader.hasEmoji)
        val emojiSubtext = nodesById["4:301"]!!
        assertTrue(emojiSubtext.hasEmoji)
        assertFalse(btnPrimary.hasEmoji)

        // 8. Verify Directed Edges
        val edgesBySlot = snapshot.edges.groupBy { it.edgeType }

        // CONTAINS edges
        val containsEdges = edgesBySlot["CONTAINS"] ?: emptyList()
        val containsPairs = containsEdges.map { it.sourceNodeId to it.targetNodeId }.toSet()
        assertTrue(containsPairs.contains("0:1" to "1:10"))
        assertTrue(containsPairs.contains("1:10" to "1:50"))
        assertTrue(containsPairs.contains("1:50" to "1:51"))
        assertTrue(containsPairs.contains("1:51" to "1:52"))
        assertTrue(containsPairs.contains("4:300" to "4:301"))

        // INSTANTIATES edges
        val instEdges = edgesBySlot["INSTANTIATES"] ?: emptyList()
        val instPairs = instEdges.map { it.sourceNodeId to it.targetNodeId }.toSet()
        assertTrue(instPairs.contains("2:100" to "1:51"))
        assertTrue(instPairs.contains("2:100" to "1:50"))

        // BINDS_VARIABLE edges
        val bindEdges = edgesBySlot["BINDS_VARIABLE"] ?: emptyList()
        assertTrue(bindEdges.any { it.sourceNodeId == "1:51" && it.targetNodeId == "color/primary" })

        // TRIGGERS_ACTION edges
        val actionEdges = edgesBySlot["TRIGGERS_ACTION"] ?: emptyList()
        assertTrue(actionEdges.any { it.sourceNodeId == "2:100" && it.targetNodeId == "screen:dashboard" && it.propertySlot == "ON_CLICK" })
    }

    @Test
    fun testExtractW3cDtcgTokenBundleAndAliasDag() {
        val fileKey = "dtcg_tokens_file"
        val extractor = FigmaSnapshotExtractor()

        val tokensJson = """
        {
          "color": {
            "neutral": {
              "950": {
                "${'$'}value": "#0c0d11",
                "${'$'}type": "color",
                "${'$'}description": "Darkest canvas background"
              },
              "300": {
                "${'$'}value": "#d1d5db",
                "${'$'}type": "color"
              }
            },
            "iris": {
              "500": {
                "${'$'}value": "#6c5ce7",
                "${'$'}type": "color"
              }
            },
            "text": {
              "primary": {
                "${'$'}value": "{color.neutral.950}",
                "${'$'}type": "color"
              },
              "muted": {
                "${'$'}value": "{color.neutral.300}",
                "${'$'}type": "color"
              }
            },
            "brand": {
              "cta": {
                "${'$'}value": "{color.text.primary}",
                "${'$'}type": "color"
              }
            }
          },
          "dimension": {
            "spacing": {
              "small": {
                "${'$'}value": "8px",
                "${'$'}type": "dimension"
              },
              "medium": {
                "${'$'}value": "16px",
                "${'$'}type": "dimension"
              }
            }
          }
        }
        """.trimIndent()

        val (variables, edges) = extractor.extractTokens(fileKey, tokensJson)
        assertEquals(8, variables.size)

        val varsByName = variables.associateBy { it.name }
        assertNotNull(varsByName["color/neutral/950"])
        assertNotNull(varsByName["color/neutral/300"])
        assertNotNull(varsByName["color/iris/500"])
        assertNotNull(varsByName["color/text/primary"])
        assertNotNull(varsByName["color/text/muted"])
        assertNotNull(varsByName["color/brand/cta"])
        assertNotNull(varsByName["dimension/spacing/small"])
        assertNotNull(varsByName["dimension/spacing/medium"])

        // Primitive variables
        val neutral950 = varsByName["color/neutral/950"]!!
        assertFalse(neutral950.isAlias)
        assertEquals("COLOR", neutral950.resolvedType)
        assertEquals("color", neutral950.collectionName)

        val spacingSmall = varsByName["dimension/spacing/small"]!!
        assertFalse(spacingSmall.isAlias)
        assertEquals("FLOAT", spacingSmall.resolvedType)

        // Aliased variables
        val textPrimary = varsByName["color/text/primary"]!!
        assertTrue(textPrimary.isAlias)
        assertEquals("color/neutral/950", textPrimary.aliasedVariableId)

        val brandCta = varsByName["color/brand/cta"]!!
        assertTrue(brandCta.isAlias)
        assertEquals("color/text/primary", brandCta.aliasedVariableId)

        // VARIABLE_ALIAS_OF edges
        val aliasEdges = edges.filter { it.edgeType == "VARIABLE_ALIAS_OF" }
        assertEquals(3, aliasEdges.size)
        val aliasPairs = aliasEdges.map { it.sourceNodeId to it.targetNodeId }.toSet()
        assertTrue(aliasPairs.contains("color/text/primary" to "color/neutral/950"))
        assertTrue(aliasPairs.contains("color/text/muted" to "color/neutral/300"))
        assertTrue(aliasPairs.contains("color/brand/cta" to "color/text/primary"))
    }

    @Test
    fun testExtractFigmaVariablesFormat() {
        val fileKey = "figma_variables_file"
        val extractor = FigmaSnapshotExtractor()

        val variablesJson = """
        {
          "collections": [
            { "id": "col:primitives", "name": "Primitives" },
            { "id": "col:semantic", "name": "Semantic" }
          ],
          "variables": [
            {
              "id": "var:neutral_950",
              "variableCollectionId": "col:primitives",
              "name": "neutral/950",
              "resolvedType": "COLOR",
              "valuesByMode": {
                "m1": { "r": 0.05, "g": 0.05, "b": 0.07, "a": 1.0 }
              },
              "codeSyntax": {
                "WEB": "var(--neutral-950)",
                "COMPOSE": "RelayTheme.colors.neutral950"
              }
            },
            {
              "id": "var:text_primary",
              "variableCollectionId": "col:semantic",
              "name": "text/primary",
              "resolvedType": "COLOR",
              "valuesByMode": {
                "m2": { "type": "VARIABLE_ALIAS", "id": "var:neutral_950" }
              },
              "codeSyntax": {
                "WEB": "var(--text-primary)",
                "COMPOSE": "RelayTheme.colors.textPrimary"
              }
            }
          ]
        }
        """.trimIndent()

        val (variables, edges) = extractor.extractTokens(fileKey, variablesJson)
        assertEquals(2, variables.size)

        val n950 = variables.first { it.variableId == "var:neutral_950" }
        assertEquals("Primitives", n950.collectionName)
        assertEquals("neutral/950", n950.name)
        assertFalse(n950.isAlias)
        assertEquals("RelayTheme.colors.neutral950", n950.codeSyntaxCompose)

        val tPrimary = variables.first { it.variableId == "var:text_primary" }
        assertEquals("Semantic", tPrimary.collectionName)
        assertEquals("text/primary", tPrimary.name)
        assertTrue(tPrimary.isAlias)
        assertEquals("var:neutral_950", tPrimary.aliasedVariableId)

        assertEquals(1, edges.size)
        assertEquals("var:text_primary", edges[0].sourceNodeId)
        assertEquals("var:neutral_950", edges[0].targetNodeId)
        assertEquals("VARIABLE_ALIAS_OF", edges[0].edgeType)
    }

    @Test
    fun testIngestAndCallersQuery() = withTempStore { store ->
        val fileKey = "callers_integration_test"
        val extractor = FigmaSnapshotExtractor()

        val astJson = """
        {
          "name": "Component Callers Test",
          "document": {
            "id": "0:0",
            "name": "Document",
            "type": "DOCUMENT",
            "children": [
              {
                "id": "0:1",
                "name": "Page 1",
                "type": "CANVAS",
                "children": [
                  {
                    "id": "cmp:action_button",
                    "name": "ActionButton",
                    "type": "COMPONENT"
                  },
                  {
                    "id": "inst:login_flow",
                    "name": "LoginSubmitBtn",
                    "type": "INSTANCE",
                    "componentId": "cmp:action_button"
                  },
                  {
                    "id": "inst:checkout_flow",
                    "name": "CheckoutConfirmBtn",
                    "type": "INSTANCE",
                    "componentId": "cmp:action_button"
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

        extractor.ingest(store, fileKey, astJson)

        // Query callers by component name
        val callersByName = store.queryFigmaCallers(fileKey, "ActionButton")
        assertEquals(2, callersByName.size)
        val callerNames = callersByName.map { it.name }.toSet()
        assertTrue(callerNames.contains("LoginSubmitBtn"))
        assertTrue(callerNames.contains("CheckoutConfirmBtn"))

        // Query callers by component ID
        val callersById = store.queryFigmaCallers(fileKey, "cmp:action_button")
        assertEquals(2, callersById.size)
    }

    @Test
    fun testIngestAndSpatialIntersectionQuery() = withTempStore { store ->
        val fileKey = "spatial_integration_test"
        val extractor = FigmaSnapshotExtractor()

        val astJson = """
        {
          "name": "Spatial Test",
          "document": {
            "id": "0:0",
            "name": "Document",
            "type": "DOCUMENT",
            "children": [
              {
                "id": "0:1",
                "name": "Page 1",
                "type": "CANVAS",
                "children": [
                  {
                    "id": "node:box_inside_1",
                    "name": "BoxInside1",
                    "type": "FRAME",
                    "absoluteBoundingBox": { "x": 100.0, "y": 100.0, "width": 150.0, "height": 100.0 }
                  },
                  {
                    "id": "node:box_inside_2",
                    "name": "BoxInside2",
                    "type": "FRAME",
                    "absoluteBoundingBox": { "x": 200.0, "y": 200.0, "width": 100.0, "height": 100.0 }
                  },
                  {
                    "id": "node:box_outside",
                    "name": "BoxOutside",
                    "type": "FRAME",
                    "absoluteBoundingBox": { "x": 1000.0, "y": 1000.0, "width": 200.0, "height": 200.0 }
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

        extractor.ingest(store, fileKey, astJson)

        // Query region x=50..350, y=50..350
        val queryBounds = FigmaSpatialBounds(50.0, 350.0, 50.0, 350.0)
        val intersectingNodes = store.queryFigmaSpatialIntersection(fileKey, queryBounds)

        val intersectingIds = intersectingNodes.map { it.nodeId }.toSet()
        assertEquals(2, intersectingIds.size)
        assertTrue(intersectingIds.contains("node:box_inside_1"))
        assertTrue(intersectingIds.contains("node:box_inside_2"))
        assertFalse(intersectingIds.contains("node:box_outside"))
    }

    @Test
    fun testIngestAndVariableImpactBlastRadius() = withTempStore { store ->
        val fileKey = "tokens_blast_radius_test"
        val extractor = FigmaSnapshotExtractor()

        val tokensJson = """
        {
          "color": {
            "neutral": {
              "950": {
                "${'$'}value": "#0c0d11",
                "${'$'}type": "color"
              }
            },
            "text": {
              "primary": {
                "${'$'}value": "{color.neutral.950}",
                "${'$'}type": "color"
              }
            },
            "header": {
              "headline": {
                "${'$'}value": "{color.text.primary}",
                "${'$'}type": "color"
              }
            }
          },
          "dimension": {
            "spacing": {
              "small": {
                "${'$'}value": "8px",
                "${'$'}type": "dimension"
              }
            }
          }
        }
        """.trimIndent()

        val astJson = """
        {
          "name": "Variable Binding Document",
          "document": {
            "id": "0:0",
            "name": "Document",
            "type": "DOCUMENT",
            "children": [
              {
                "id": "0:1",
                "name": "Page 1",
                "type": "CANVAS",
                "children": [
                  {
                    "id": "node:heading_text",
                    "name": "HeaderTitleText",
                    "type": "TEXT",
                    "boundVariables": {
                      "fills": [ { "id": "color/header/headline" } ]
                    }
                  },
                  {
                    "id": "node:body_text",
                    "name": "BodyContentText",
                    "type": "TEXT",
                    "boundVariables": {
                      "fills": [ { "id": "color/text/primary" } ]
                    }
                  },
                  {
                    "id": "node:spacer_box",
                    "name": "DividerSpacer",
                    "type": "FRAME",
                    "boundVariables": {
                      "itemSpacing": { "id": "dimension/spacing/small" }
                    }
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

        val snapshot = extractor.ingest(store, fileKey, astJson, tokensJson)
        assertEquals(5, snapshot.nodes.size)
        assertEquals(4, snapshot.variables.size)

        // Trace blast radius of updating primitive token "color/neutral/950"
        val blastRadius = store.resolveVariableBlastRadius(fileKey, "color/neutral/950", depth = 5)

        assertEquals("color/neutral/950", blastRadius.seedVariableIdOrName)
        assertEquals(3, blastRadius.impactedVariables.size)
        val impactedNames = blastRadius.impactedVariables.map { it.name }.toSet()
        assertTrue(impactedNames.contains("color/neutral/950"))
        assertTrue(impactedNames.contains("color/text/primary"))
        assertTrue(impactedNames.contains("color/header/headline"))
        assertFalse(impactedNames.contains("dimension/spacing/small"))

        assertEquals(2, blastRadius.boundNodes.size)
        val boundNodeIds = blastRadius.boundNodes.map { it.nodeId }.toSet()
        assertTrue(boundNodeIds.contains("node:heading_text"))
        assertTrue(boundNodeIds.contains("node:body_text"))
        assertFalse(boundNodeIds.contains("node:spacer_box"))
    }

    @Test
    fun testEmptyInputsAndDefaults() {
        val fileKey = "empty_test"
        val extractor = FigmaSnapshotExtractor()

        val snapEmptyStr = extractor.extractDocument(fileKey, "")
        assertEquals(fileKey, snapEmptyStr.document.fileKey)
        assertTrue(snapEmptyStr.nodes.isEmpty())
        assertTrue(snapEmptyStr.variables.isEmpty())
        assertTrue(snapEmptyStr.edges.isEmpty())

        val (vars, edges) = extractor.extractTokens(fileKey, "")
        assertTrue(vars.isEmpty())
        assertTrue(edges.isEmpty())

        val snapEmptyObj = extractor.extractDocument(fileKey, "{}")
        assertEquals(fileKey, snapEmptyObj.document.fileKey)
        assertTrue(snapEmptyObj.nodes.isEmpty())
    }

    @Test
    fun testDesktopBridgeSingleNodePayload() {
        val fileKey = "bridge_test"
        val extractor = FigmaSnapshotExtractor()

        val bridgeJson = """
        {
          "id": "bridge:root",
          "name": "FloatingModal",
          "type": "FRAME",
          "layoutMode": "VERTICAL",
          "sizingHorizontal": "FIXED",
          "sizingVertical": "HUG",
          "horizontalPadding": 24.0,
          "verticalPadding": 16.0,
          "itemSpacing": 12.0,
          "relativeTransform": [
            [1.0, 0.0, 45.0],
            [0.0, 1.0, 90.0]
          ],
          "size": { "x": 320.0, "y": 240.0 }
        }
        """.trimIndent()

        val snapshot = extractor.extractDocument(fileKey, bridgeJson)
        assertEquals(1, snapshot.nodes.size)
        val node = snapshot.nodes[0]
        assertEquals("bridge:root", node.nodeId)
        assertEquals("FloatingModal", node.name)
        assertEquals("VERTICAL", node.layoutMode)
        assertEquals("FIXED", node.sizingHorizontal)
        assertEquals("HUG", node.sizingVertical)
        assertEquals(24.0, node.paddingLeft)
        assertEquals(24.0, node.paddingRight)
        assertEquals(16.0, node.paddingTop)
        assertEquals(16.0, node.paddingBottom)
        assertEquals(12.0, node.itemSpacing)
        assertEquals(45.0, node.localX)
        assertEquals(90.0, node.localY)
        assertEquals(320.0, node.width)
        assertEquals(240.0, node.height)
        assertEquals("pragmatic", node.semioticLayer) // "FloatingModal" matches "Modal" pattern!
    }

    @Test
    fun testUnboundStrokeDetection() {
        val fileKey = "stroke_test"
        val extractor = FigmaSnapshotExtractor()

        val astJson = """
        {
          "name": "Stroke Test",
          "document": {
            "id": "0:0",
            "name": "Document",
            "type": "DOCUMENT",
            "children": [
              {
                "id": "node:unbound_stroke",
                "name": "UnboundStrokeFrame",
                "type": "FRAME",
                "strokes": [
                  {
                    "type": "SOLID",
                    "visible": true,
                    "color": { "r": 0.5, "g": 0.5, "b": 0.5, "a": 1.0 }
                  }
                ]
              },
              {
                "id": "node:bound_stroke",
                "name": "BoundStrokeFrame",
                "type": "FRAME",
                "strokes": [
                  {
                    "type": "SOLID",
                    "visible": true,
                    "boundVariables": {
                      "color": { "type": "VARIABLE_ALIAS", "id": "color/border" }
                    }
                  }
                ]
              },
              {
                "id": "node:no_stroke",
                "name": "NoStrokeFrame",
                "type": "FRAME",
                "strokes": []
              }
            ]
          }
        }
        """.trimIndent()

        val snapshot = extractor.extractDocument(fileKey, astJson)
        val nodesById = snapshot.nodes.associateBy { it.nodeId }

        assertTrue(nodesById["node:unbound_stroke"]!!.unboundStroke)
        assertFalse(nodesById["node:bound_stroke"]!!.unboundStroke)
        assertFalse(nodesById["node:no_stroke"]!!.unboundStroke)
    }

    @Test
    fun testDeepAliasDagChain() = withTempStore { store ->
        val fileKey = "deep_dag_test"
        val extractor = FigmaSnapshotExtractor()

        // 4-level token chain: base -> primitive -> semantic -> component
        val tokensJson = """
        {
          "palette": {
            "blue": {
              "900": {
                "${'$'}value": "#001144",
                "${'$'}type": "color"
              }
            }
          },
          "color": {
            "brand": {
              "navy": {
                "${'$'}value": "{palette.blue.900}",
                "${'$'}type": "color"
              }
            },
            "surface": {
              "primary": {
                "${'$'}value": "{color.brand.navy}",
                "${'$'}type": "color"
              }
            }
          },
          "component": {
            "card": {
              "bg": {
                "${'$'}value": "{color.surface.primary}",
                "${'$'}type": "color"
              }
            }
          }
        }
        """.trimIndent()

        val astJson = """
        {
          "name": "Deep DAG Card Screen",
          "document": {
            "id": "0:0",
            "name": "Document",
            "type": "DOCUMENT",
            "children": [
              {
                "id": "node:card_bg",
                "name": "CardBackground",
                "type": "RECTANGLE",
                "boundVariables": {
                  "fills": [ { "id": "component/card/bg" } ]
                }
              }
            ]
          }
        }
        """.trimIndent()

        extractor.ingest(store, fileKey, astJson, tokensJson)

        val blastRadius = store.resolveVariableBlastRadius(fileKey, "palette/blue/900", depth = 10)
        assertEquals("palette/blue/900", blastRadius.seedVariableIdOrName)
        assertEquals(4, blastRadius.impactedVariables.size)

        val impactedNames = blastRadius.impactedVariables.map { it.name }.toSet()
        assertTrue(impactedNames.contains("palette/blue/900"))
        assertTrue(impactedNames.contains("color/brand/navy"))
        assertTrue(impactedNames.contains("color/surface/primary"))
        assertTrue(impactedNames.contains("component/card/bg"))

        assertEquals(1, blastRadius.boundNodes.size)
        assertEquals("node:card_bg", blastRadius.boundNodes[0].nodeId)
    }

    @Test
    fun `test StoreSink interface decouples extraction from concrete SQLite store`() {
        class InMemoryStoreSink : FigmaSnapshotExtractor.StoreSink {
            var deletedKey: String? = null
            var insertedDoc: atlas.model.FigmaDocument? = null
            val insertedNodes = mutableListOf<atlas.model.FigmaNode>()
            val insertedVariables = mutableListOf<atlas.model.FigmaVariable>()
            val insertedEdges = mutableListOf<atlas.model.FigmaEdge>()

            override fun deleteFigmaDocument(fileKey: String) { deletedKey = fileKey }
            override fun insertFigmaDocument(document: atlas.model.FigmaDocument) { insertedDoc = document }
            override fun insertFigmaNodes(fileKey: String, nodes: List<atlas.model.FigmaNode>) { insertedNodes.addAll(nodes) }
            override fun insertFigmaVariables(fileKey: String, variables: List<atlas.model.FigmaVariable>) { insertedVariables.addAll(variables) }
            override fun insertFigmaEdges(fileKey: String, edges: List<atlas.model.FigmaEdge>) { insertedEdges.addAll(edges) }
        }

        val sink = InMemoryStoreSink()
        val extractor = FigmaSnapshotExtractor()
        val ast = """
        {
          "name": "Decoupled Test",
          "document": {
            "id": "0:0",
            "type": "DOCUMENT",
            "children": [
              {
                "id": "1:1",
                "name": "Frame A",
                "type": "FRAME"
              }
            ]
          }
        }
        """.trimIndent()

        val snap = extractor.ingest(sink, "decoupled_key", ast)
        assertEquals("decoupled_key", sink.deletedKey)
        assertNotNull(sink.insertedDoc)
        assertEquals("Decoupled Test", sink.insertedDoc?.name)
        assertEquals(2, sink.insertedNodes.size)
        assertEquals("0:0", sink.insertedNodes[0].nodeId)
        assertEquals("1:1", sink.insertedNodes[1].nodeId)
        assertEquals("Frame A", sink.insertedNodes[1].name)
        assertEquals(2, snap.nodes.size)
    }
}
