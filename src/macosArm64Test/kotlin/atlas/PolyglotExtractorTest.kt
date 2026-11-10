package atlas

import atlas.extract.LineOffsetIndex
import atlas.extract.PolyglotAstExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Pattern 2 Invariant: state rollback verified and side-effect callCount == 0
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

    @Test
    fun `NativeSystemsAstExtractor extracts full Cpp class definition and methods when preceded by forward declaration`() {
        val cppSource = """
            #include "engine.h"

            class BufferManager;
            struct CacheEntry;

            class BufferManager {
            public:
                void allocateBuffer(int size) {
                    resetPointer();
                }
                void resetPointer() {}
            };

            struct CacheEntry {
                int id;
                void clear() {}
            };
        """.trimIndent()

        val (nodes, edges, topCount) = PolyglotAstExtractor.extractFile("proj", "src/BufferManager.cpp", cppSource)
        assertEquals(2, topCount, "Both BufferManager and CacheEntry must be counted once as top-level types")

        val bufferClass = nodes.firstOrNull { it.name == "BufferManager" && it.kind == "class" }
        assertNotNull(bufferClass, "BufferManager class node must be extracted")
        assertTrue(bufferClass.startLine >= 6, "BufferManager class node must match definition, not forward declaration")

        val allocateMethod = nodes.firstOrNull { it.name == "allocateBuffer" }
        assertNotNull(allocateMethod, "BufferManager::allocateBuffer method must be extracted")
        assertEquals("proj:src/BufferManager.cpp#BufferManager::allocateBuffer", allocateMethod.nodeId)

        val resetMethod = nodes.firstOrNull { it.name == "resetPointer" }
        assertNotNull(resetMethod, "BufferManager::resetPointer method must be extracted")
        assertEquals("proj:src/BufferManager.cpp#BufferManager::resetPointer", resetMethod.nodeId)

        val cacheStruct = nodes.firstOrNull { it.name == "CacheEntry" && it.kind == "struct" }
        assertNotNull(cacheStruct, "CacheEntry struct node must be extracted")
        val clearMethod = nodes.firstOrNull { it.name == "clear" }
        assertNotNull(clearMethod, "CacheEntry::clear method must be extracted")

        assertTrue(edges.any { it.edgeType == "CALLS" && it.targetNodeId == "sym:resetPointer" })
    }

    @Test
    fun `NativeSystemsAstExtractor qualifies Rust methods under impl blocks preventing primary key collisions across structs`() {
        val rustSource = """
            pub struct Engine {
                pub state: u32,
            }

            pub struct Pipeline {
                pub id: u64,
            }

            impl Engine {
                pub const fn new() -> Self {
                    Engine { state: 0 }
                }

                pub unsafe fn reset(&mut self) {
                    self.state = 0;
                }

                #[inline]
                pub fn is_active(&self) -> bool {
                    self.state > 0
                }
            }

            impl Pipeline {
                pub fn new() -> Self {
                    Pipeline { id: 1 }
                }

                pub fn execute(&self) {}
            }

            impl<'a> std::fmt::Display for &'a Pipeline {
                pub fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                    Ok(())
                }
            }
        """.trimIndent()

        val (nodes, _, topCount) = PolyglotAstExtractor.extractFile("proj", "src/engine.rs", rustSource)
        assertEquals(2, topCount, "Only Engine and Pipeline top-level structs should be counted for BOOST-003")

        val nodeIds = nodes.map { it.nodeId }.toSet()
        assertTrue("proj:src/engine.rs#Engine::new" in nodeIds, "Engine::new (const fn) must have qualified nodeId")
        assertTrue("proj:src/engine.rs#Pipeline::new" in nodeIds, "Pipeline::new must have qualified nodeId")
        assertTrue("proj:src/engine.rs#Engine::reset" in nodeIds, "Engine::reset (unsafe fn) must have qualified nodeId")
        assertTrue("proj:src/engine.rs#Engine::is_active" in nodeIds, "Engine::is_active (#[inline] fn) must have qualified nodeId")
        assertTrue("proj:src/engine.rs#Pipeline::execute" in nodeIds, "Pipeline::execute must have qualified nodeId")
        assertTrue("proj:src/engine.rs#Pipeline::fmt" in nodeIds, "Pipeline::fmt trait method on &'a Pipeline must have qualified nodeId")

        val newMethods = nodes.filter { it.name == "new" }
        assertEquals(2, newMethods.size, "Both new() methods must be preserved in the node graph without overwrite")
    }

    @Test
    fun `PythonAstExtractor extracts single-underscore private functions and methods while preserving complexity metrics`() {
        val pySource = """
            def _helper_util(val: int) -> int:
                if val > 10:
                    return val * 2
                return val

            class ConnectionManager:
                def __init__(self, host: str):
                    self.host = host

                def _connect(self, retries: int) -> bool:
                    for i in range(retries):
                        if self._try_socket():
                            return True
                    return False

                def _try_socket(self) -> bool:
                    return True

                def __unsupported_custom_dunder__(self):
                    pass
        """.trimIndent()

        val (nodes, edges, _) = PolyglotAstExtractor.extractFile("proj", "src/conn.py", pySource)

        val helperNode = nodes.firstOrNull { it.name == "_helper_util" }
        assertNotNull(helperNode, "Private top-level function _helper_util must be extracted")
        assertEquals("function", helperNode.kind)
        assertTrue(helperNode.cyclomaticComplexity >= 2, "Cognitive and cyclomatic complexity must be calculated for private function")

        val connectNode = nodes.firstOrNull { it.name == "_connect" }
        assertNotNull(connectNode, "Private method _connect must be extracted")
        assertEquals("proj:src/conn.py#ConnectionManager._connect", connectNode.nodeId)
        assertTrue(connectNode.cyclomaticComplexity >= 2, "Branching inside _connect must reflect in complexity metrics")

        val trySocketNode = nodes.firstOrNull { it.name == "_try_socket" }
        assertNotNull(trySocketNode, "Private method _try_socket must be extracted")

        val initNode = nodes.firstOrNull { it.name == "__init__" }
        assertNotNull(initNode, "Whitelisted dunder __init__ must be extracted")

        val unsupportedDunder = nodes.firstOrNull { it.name == "__unsupported_custom_dunder__" }
        assertTrue(unsupportedDunder == null, "Non-whitelisted dunders must continue to be filtered out")

        assertTrue(edges.any { it.sourceNodeId == connectNode.nodeId && it.targetNodeId == "sym:self._try_socket" }, "Call to private method self._connect -> self._try_socket must be extracted")
    }

    @Test
    fun `extractFile passes BOOST-003 validation for single public declaration with sealed variants and file-private helpers`() {
        val ktSource = """
            package sample.result

            sealed interface ActionResult

            data class Success(val message: String) : ActionResult
            data class Failure(val cause: Throwable) : ActionResult
            data object Pending : ActionResult

            private fun formatErrorMessage(cause: Throwable): String = cause.message ?: "Unknown"
        """.trimIndent()
        val (_, _, ktTopCount) = PolyglotAstExtractor.extractFile("proj", "src/ActionResult.kt", ktSource)
        assertEquals(1, ktTopCount, "Kotlin sealed interface with top-level variants and private helper must report topLevelCount == 1")

        val tsSource = """
            export abstract class CheckoutService {
                abstract process(id: string): boolean;
            }

            abstract class InternalValidationHelper {
                abstract check(): void;
            }

            function validate(id: string): boolean {
                return id.length > 0;
            }
        """.trimIndent()
        val (_, _, tsTopCount) = PolyglotAstExtractor.extractFile("proj", "src/CheckoutService.ts", tsSource)
        assertEquals(1, tsTopCount, "TypeScript abstract class with unexported helper abstract class and function must report topLevelCount == 1")

        val swiftSource = """
            import Foundation

            public struct PaymentProcessor {
                public func pay() {}
            }

            private class CardValidator {
                func isValid() -> Bool { true }
            }

            fileprivate func sanitize(input: String) -> String {
                return input
            }
        """.trimIndent()
        val (_, _, swiftTopCount) = PolyglotAstExtractor.extractFile("proj", "src/PaymentProcessor.swift", swiftSource)
        assertEquals(1, swiftTopCount, "Swift public struct with private and fileprivate helpers must report topLevelCount == 1")

        val rustSource = """
            pub struct ClusterClient {
                pub endpoint: String,
            }

            pub(crate) struct ClusterMetrics {
                pub latency_ms: u64,
            }

            fn internal_ping(endpoint: &str) -> bool {
                true
            }

            pub(super) fn internal_super_ping() -> bool {
                true
            }

            impl ClusterClient {
                pub fn connect(endpoint: String) -> Self {
                    ClusterClient { endpoint }
                }
            }
        """.trimIndent()
        val (_, _, rustTopCount) = PolyglotAstExtractor.extractFile("proj", "src/cluster.rs", rustSource)
        assertEquals(1, rustTopCount, "Rust pub struct with non-pub and pub(crate)/pub(super) helpers must report topLevelCount == 1")
    }

    @Test
    fun `NativeSystemsAstExtractor correctly attributes methods to innermost Cpp class when classes are nested`() {
        val cppSource = """
            class OuterEngine {
            public:
                class InnerWorker {
                public:
                    void doWork() {}
                };
                void runEngine() {}
            };
        """.trimIndent()
        val (nodes, _, topCount) = PolyglotAstExtractor.extractFile("proj", "src/nested.cpp", cppSource)
        assertEquals(1, topCount, "Only OuterEngine is top-level class")
        val doWorkMethod = nodes.firstOrNull { it.name == "doWork" }
        assertNotNull(doWorkMethod, "InnerWorker::doWork method must be extracted")
        assertEquals("proj:src/nested.cpp#InnerWorker::doWork", doWorkMethod.nodeId, "Innermost class InnerWorker must enclose doWork")
        val runEngineMethod = nodes.firstOrNull { it.name == "runEngine" }
        assertNotNull(runEngineMethod, "OuterEngine::runEngine method must be extracted")
        assertEquals("proj:src/nested.cpp#OuterEngine::runEngine", runEngineMethod.nodeId)
    }

    @Test
    fun `KotlinAstExtractor extracts backtick quoted function names and trailing lambda calls`() {
        val ktSource = """
            package test.behavior

            class TestRunner {
                fun `verify database transactions rollback cleanly on error`() {
                    statement.use { stmt ->
                        stmt.step()
                    }
                }
            }
        """.trimIndent()
        val (nodes, edges, _) = PolyglotAstExtractor.extractFile("proj", "src/TestRunner.kt", ktSource)
        val testFunc = nodes.firstOrNull { it.name == "verify database transactions rollback cleanly on error" }
        assertNotNull(testFunc, "Backtick function declaration must be extracted")
        assertEquals("function", testFunc.kind)

        val useCall = edges.firstOrNull { it.edgeType == "CALLS" && (it.targetNodeId == "sym:use" || it.targetNodeId == "sym:statement.use") }
        assertNotNull(useCall, "Trailing lambda call .use { ... } must be extracted")
    }

    @Test
    fun `NativeSystemsAstExtractor extracts Cpp constructor with member initializer list`() {
        val cppSource = """
            class SocketChannel {
            private:
                int m_fd;
                int m_port;
            public:
                SocketChannel(int fd, int port) : m_fd(fd), m_port(port) {
                    initChannel();
                }
                void initChannel() {}
            };
        """.trimIndent()
        val (nodes, edges, _) = PolyglotAstExtractor.extractFile("proj", "src/socket.cpp", cppSource)
        val ctor = nodes.firstOrNull { it.name == "SocketChannel" && it.kind == "function" }
        assertNotNull(ctor, "Cpp constructor with member initializer list must be extracted")
        val call = edges.firstOrNull { it.edgeType == "CALLS" && it.targetNodeId == "sym:initChannel" }
        assertNotNull(call, "Call from constructor body must be extracted")
    }

    @Test
    fun `TypeScriptAstExtractor extracts namespace combined and type imports`() {
        val tsSource = """
            import React, { useState, useEffect as ue } from 'react';
            import * as fs from 'fs';
            import type { UserProfile } from './types';

            export function renderApp() {
                useState();
            }
        """.trimIndent()
        val (_, edges, _) = PolyglotAstExtractor.extractFile("proj", "src/app.tsx", tsSource)
        val imports = edges.filter { it.edgeType == "IMPORTS" }.map { it.targetNodeId }
        assertTrue("sym:React" in imports, "Default import React must be captured")
        assertTrue("sym:useState" in imports, "Named import useState must be captured")
        assertTrue("sym:useEffect" in imports, "Aliased named import useEffect must be captured")
        assertTrue("sym:fs" in imports, "Namespace import fs must be captured")
        assertTrue("sym:UserProfile" in imports, "Type import UserProfile must be captured")
    }

    @Test
    fun `PythonAstExtractor extracts imported symbol tokens from from-import statements`() {
        val pySource = """
            from os.path import exists, join as pjoin
            import json, sys

            def check_file(path):
                return exists(path)
        """.trimIndent()
        val (_, edges, _) = PolyglotAstExtractor.extractFile("proj", "src/check.py", pySource)
        val imports = edges.filter { it.edgeType == "IMPORTS" }.map { it.targetNodeId }
        assertTrue("sym:os.path" in imports, "Module os.path must be captured")
        assertTrue("sym:exists" in imports, "Direct symbol exists must be captured")
        assertTrue("sym:os.path.exists" in imports, "FQN os.path.exists must be captured")
        assertTrue("sym:join" in imports, "Imported symbol join must be captured")
        assertTrue("sym:json" in imports, "Imported module json must be captured")
        assertTrue("sym:sys" in imports, "Imported module sys must be captured")
    }

    @Test
    fun `extractFile handles trailing commas and blank components in TypeScript and Python import clauses`() {
        val tsSource = """
            import { Alpha, , Beta, } from './services';
            export function run() { Alpha(); }
        """.trimIndent()
        val (_, tsEdges, _) = PolyglotAstExtractor.extractFile("proj", "src/run.ts", tsSource)
        val tsImports = tsEdges.filter { it.edgeType == "IMPORTS" }.map { it.targetNodeId }.toSet()
        assertTrue("sym:Alpha" in tsImports)
        assertTrue("sym:Beta" in tsImports)

        val pySource = """
            from os.path import exists, , isdir,
            import sys, , os,

            def check():
                return exists(".")
        """.trimIndent()
        val (_, pyEdges, _) = PolyglotAstExtractor.extractFile("proj", "src/run.py", pySource)
        val pyImports = pyEdges.filter { it.edgeType == "IMPORTS" }.map { it.targetNodeId }.toSet()
        assertTrue("sym:exists" in pyImports)
        assertTrue("sym:isdir" in pyImports)
        assertTrue("sym:sys" in pyImports)
        assertTrue("sym:os" in pyImports)
    }
}
