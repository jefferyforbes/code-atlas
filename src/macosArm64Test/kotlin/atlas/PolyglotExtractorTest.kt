package atlas

import atlas.extract.LineOffsetIndex
import atlas.extract.PolyglotAstExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `extractFile extracts chained Kotlin infix call operators without false positive RHS operands`() {
        val ktSource = """
            package sample.infix

            fun evaluateRules(lhs: Int, mid: Int, rhs: Int): Boolean {
                val combined = lhs combineWith mid mergeInto rhs
                val multiline = combined
                    verifyAgainst rhs
                return multiline
            }
        """.trimIndent()

        val (_, edges, _) = PolyglotAstExtractor.extractFile("proj", "src/InfixSample.kt", ktSource)
        val infixEdges = edges.filter { it.provenance == "INFERRED_INFIX_CALL" }
        val infixTargets = infixEdges.map { it.targetNodeId }.toSet()

        assertTrue("sym:combineWith" in infixTargets, "First chained infix operator must be extracted")
        assertTrue("sym:mergeInto" in infixTargets, "Second chained infix operator must be extracted")
        assertTrue("sym:verifyAgainst" in infixTargets, "Multiline infix operator must be extracted")
        assertFalse("sym:mid" in infixTargets, "Intermediate RHS operand 'mid' must NOT be extracted as an infix operator")
        assertFalse("sym:rhs" in infixTargets, "Terminal RHS operand 'rhs' must NOT be extracted as an infix operator")

        val multilineEdge = infixEdges.first { it.targetNodeId == "sym:verifyAgainst" }
        assertEquals(5, multilineEdge.lineNumber, "Line cursor must track multiline infix call position")
    }

    @Test
    fun `LineOffsetIndex resolves exact 1-based line numbers matching linear scan across boundary conditions`() {
        val emptyIndex = LineOffsetIndex.from("")
        assertEquals(1, emptyIndex.lineNumberAt(0))
        assertEquals(1, emptyIndex.lineNumberAt(-10))
        assertEquals(1, emptyIndex.lineNumberAt(100))

        val singleLineIndex = LineOffsetIndex.from("val singleLine = 42")
        assertEquals(1, singleLineIndex.lineNumberAt(0))
        assertEquals(1, singleLineIndex.lineNumberAt(10))
        assertEquals(1, singleLineIndex.lineNumberAt(200))

        val multilineBuilder = StringBuilder()
        for (i in 1..2500) {
            multilineBuilder.append("line_$i = $i\n")
        }
        val multilineText = multilineBuilder.toString()
        val index = LineOffsetIndex.from(multilineText)
        assertEquals(2501, index.lineCount)

        val sampleOffsets = listOf(0, 1, 10, 50, 120, 1000, 5000, 12345, multilineText.length - 1, multilineText.length)
        for (offset in sampleOffsets) {
            val expectedLine = multilineText.take(offset.coerceIn(0, multilineText.length)).count { it == '\n' } + 1
            assertEquals(expectedLine, index.lineNumberAt(offset), "Mismatch at offset $offset")
        }
    }

    @Test
    fun `SourceLexerMasker ignores Cpp digit separators in numeric literals and preserves subsequent declarations`() {
        val cppSource = """
            #include <cstdint>

            const int64_t MAX_SIZE = 1'000'000;
            const uint32_t HEX_VAL = 0x12'ab'cd;
            const uint8_t BIN_VAL = 0b1010'0101;
            const char CHAR_VAL = 'a';

            class MemoryPool {
            public:
                void allocateBlock() {
                    initBuffer();
                }
                void initBuffer() {}
            };
        """.trimIndent()

        val (nodes, edges, topCount) = PolyglotAstExtractor.extractFile("proj", "src/pool.cpp", cppSource)
        assertEquals(1, topCount, "MemoryPool must be counted as top-level class")
        assertTrue(nodes.any { it.name == "MemoryPool" && it.kind == "class" })
        assertTrue(nodes.any { it.name == "allocateBlock" && it.kind == "function" })
        assertTrue(nodes.any { it.name == "initBuffer" && it.kind == "function" })
        assertTrue(edges.any { it.edgeType == "CALLS" && it.targetNodeId == "sym:initBuffer" })
    }

    @Test
    fun `Python extractor handles escaped triple quotes in docstrings and inline comments without block corruption`() {
        val pySource = """
            def process_text(content: str) -> str:
                \"\"\"Processes markdown with escaped quotes: \"\"\" and continues.
                
                Additional details at column 0 in docstring.
                \"\"\"
                result = content.strip() # inline comment with \"\"\" triple quote
                return result

            def next_function() -> int:
                return 100
        """.trimIndent()

        val (nodes, _, topCount) = PolyglotAstExtractor.extractFile("proj", "src/text_proc.py", pySource)
        assertEquals(2, topCount, "Both process_text and next_function must be extracted as top-level functions")
        val procFunc = nodes.firstOrNull { it.name == "process_text" }
        val nextFunc = nodes.firstOrNull { it.name == "next_function" }
        assertTrue(procFunc != null, "process_text must be extracted")
        assertTrue(nextFunc != null, "next_function must be extracted")
        assertTrue(procFunc.endLine < nextFunc.startLine, "process_text must terminate before next_function begins")
    }

    @Test
    fun `Kotlin and TypeScript extractors support nested generic type parameters`() {
        val ktSource = """
            package sample.generics

            class EntityStore<K, V : Comparable<V>> {
                fun <T : Comparable<T>> sortEntries(items: List<T>): List<T> {
                    val map = HashMap<String, List<Int>>()
                    return items
                }
            }
        """.trimIndent()

        val (ktNodes, _, ktTopCount) = PolyglotAstExtractor.extractFile("proj", "src/EntityStore.kt", ktSource)
        assertEquals(1, ktTopCount)
        assertTrue(ktNodes.any { it.name == "EntityStore" && it.kind == "class" })
        assertTrue(ktNodes.any { it.name == "sortEntries" && it.kind == "function" })

        val tsSource = """
            export class QueryHandler {
                async handleQuery<T extends Record<string, unknown>>(query: T): Promise<T> {
                    return query;
                }
            }
        """.trimIndent()

        val (tsNodes, _, tsTopCount) = PolyglotAstExtractor.extractFile("proj", "src/QueryHandler.ts", tsSource)
        assertEquals(1, tsTopCount)
        assertTrue(tsNodes.any { it.name == "QueryHandler" && it.kind == "class" })
        assertTrue(tsNodes.any { it.name == "handleQuery" && it.kind == "method" })
    }

    @Test
    fun `KotlinAstExtractor excludes internal top-level declarations from BOOST-003 public counts`() {
        val ktSource = """
            package sample.visibility

            class PublicService {
                fun doPublic(): Unit {}
            }

            internal class InternalHelper {
                fun doInternal(): Unit {}
            }

            internal fun internalUtility(): Int = 42

            private fun privateHelper(): Boolean = true
        """.trimIndent()

        val (nodes, _, topCount) = PolyglotAstExtractor.extractFile("proj", "src/PublicService.kt", ktSource)
        assertEquals(1, topCount, "Only PublicService should be counted as public top-level declaration for BOOST-003")
        assertTrue(nodes.any { it.name == "PublicService" && it.isTopLevel })
        assertTrue(nodes.any { it.name == "InternalHelper" })
        assertTrue(nodes.any { it.name == "internalUtility" })
        assertTrue(nodes.any { it.name == "privateHelper" })
    }
}
