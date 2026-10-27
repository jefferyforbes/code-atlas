package atlas

import atlas.extract.PolyglotAstExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolyglotExtractorTest {

    @Test
    fun `extractFile delegates across Kotlin TypeScript Python Swift Rust and Cpp`() {
        val ktSource = """
            package sample.domain

            /** Sample service KDoc. */
            class OrderService {
                fun processOrder(id: String): Boolean {
                    return validateOrder(id)
                }

                fun validateOrder(id: String): Boolean = id.isNotEmpty()
            }
        """.trimIndent()
        val (ktNodes, ktEdges, ktTopCount) = PolyglotAstExtractor.extractFile("proj", "src/OrderService.kt", ktSource)
        assertEquals(1, ktTopCount)
        assertTrue(ktNodes.any { it.name == "OrderService" && it.kind == "class" })
        assertTrue(ktNodes.any { it.name == "processOrder" && it.kind == "function" })
        assertTrue(ktEdges.any { it.edgeType == "CALLS" && it.targetNodeId == "sym:validateOrder" })

        val tsSource = """
            import { Helper } from './helper';

            export class CartController {
                checkout(cartId: string): void {
                    Helper.submit(cartId);
                }
            }
        """.trimIndent()
        val (tsNodes, tsEdges, tsTopCount) = PolyglotAstExtractor.extractFile("proj", "src/CartController.ts", tsSource)
        assertEquals(1, tsTopCount)
        assertTrue(tsNodes.any { it.name == "CartController" && it.kind == "class" })
        assertTrue(tsNodes.any { it.name == "checkout" && it.kind == "method" })
        assertTrue(tsEdges.any { it.edgeType == "IMPORTS" && it.targetNodeId == "sym:Helper" })

        val pySource = """
            import json

            class PayloadParser:
                '''Parses raw JSON payloads.'''
                def parse_item(self, raw: str) -> dict:
                    return json.loads(raw)
        """.trimIndent()
        val (pyNodes, pyEdges, pyTopCount) = PolyglotAstExtractor.extractFile("proj", "src/parser.py", pySource)
        assertEquals(1, pyTopCount)
        val pyClass = pyNodes.first { it.name == "PayloadParser" }
        assertEquals("Parses raw JSON payloads.", pyClass.description)
        assertTrue(pyEdges.any { it.edgeType == "CALLS" && it.targetNodeId == "sym:json.loads" })

        val swiftSource = """
            import Foundation

            struct SessionManager {
                func refresh() {
                    performSync()
                }
                func performSync() {}
            }
        """.trimIndent()
        val (swiftNodes, _, swiftTopCount) = PolyglotAstExtractor.extractFile("proj", "src/SessionManager.swift", swiftSource)
        assertEquals(1, swiftTopCount)
        assertTrue(swiftNodes.any { it.name == "SessionManager" && it.kind == "struct" })

        val rustSource = """
            use std::collections::HashMap;

            pub struct GraphIndex {
                pub count: usize,
            }

            pub fn build_index() -> GraphIndex {
                GraphIndex::new()
            }
        """.trimIndent()
        val (rustNodes, rustEdges, rustTopCount) = PolyglotAstExtractor.extractFile("proj", "src/lib.rs", rustSource)
        assertEquals(2, rustTopCount)
        assertTrue(rustNodes.any { it.name == "GraphIndex" && it.kind == "class" })
        assertTrue(rustEdges.any { it.edgeType == "CALLS" && it.targetNodeId == "sym:GraphIndex" })

        val cppSource = """
            #include "engine.h"

            class QueryPlanner {
            public:
                void executePlan() {
                    optimizeTree();
                }
                void optimizeTree() {}
            };
        """.trimIndent()
        val (cppNodes, cppEdges, cppTopCount) = PolyglotAstExtractor.extractFile("proj", "src/planner.cpp", cppSource)
        assertEquals(1, cppTopCount)
        assertTrue(cppNodes.any { it.name == "QueryPlanner" && it.kind == "class" })
        assertTrue(cppEdges.any { it.edgeType == "IMPORTS" && it.targetNodeId == "sym:engine.h" })
    }
}
