package atlas

import atlas.extract.ModuleBoundaryDetector
import atlas.extract.PolyglotAstExtractor
import atlas.extract.SemioticLayerClassifier
import atlas.model.GraphEdge
import atlas.model.GraphNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolyglotExtractorTest {

    @Test
    fun testKotlinMultilineAnnotationsAndSupertypes() {
        val ktCode = """
            package atlas.model

            import kotlinx.serialization.Serializable

            interface Auditable

            open class BaseReport(val id: String)

            @OptIn(ExperimentalStdlibApi::class)
            @Serializable
            data class SampleReport(
                val projectId: String,
                val passed: Boolean
            ) : BaseReport(projectId), Auditable {
                companion object {
                    const val DEFAULT_ID = "none"
                }

                @Suppress("UNUSED_PARAMETER")
                fun computeScore(
                    weight: Int = 10,
                    bonus: Int = 5
                ): Int = weight + bonus

                fun invokeCompute(): Int {
                    return computeScore(20, 5)
                }
            }
        """.trimIndent()

        val (nodes, edges, topLevelCount) = PolyglotAstExtractor.extractFile(
            projectId = "proj",
            relPath = "src/macosArm64Main/kotlin/atlas/model/SampleReport.kt",
            sourceText = ktCode
        )

        // Auditable, BaseReport, SampleReport (companion object should not inflate topLevelCount)
        assertEquals(3, topLevelCount)

        val byName = nodes.associateBy { it.name }
        val reportNode = byName["SampleReport"]
        assertNotNull(reportNode)
        // Line 11 is `data class SampleReport(`, NOT line 9 (`@OptIn(...)`)
        assertEquals(11, reportNode.startLine)
        assertEquals("data class SampleReport(", reportNode.signature)
        assertEquals("macosArm64Main", reportNode.sourceSet)

        // Check INHERITS and IMPLEMENTS edges from SampleReport
        val superEdges = edges.filter { it.sourceNodeId == reportNode.nodeId && it.edgeType in setOf("INHERITS", "IMPLEMENTS") }
        assertTrue(superEdges.any { it.targetNodeId == "sym:BaseReport" && it.edgeType == "INHERITS" })
        assertTrue(superEdges.any { it.targetNodeId == "sym:Auditable" && it.edgeType == "IMPLEMENTS" })
        // Ensure @OptIn(ExperimentalStdlibApi::class) did NOT produce a bogus supertype
        assertTrue(superEdges.none { it.targetNodeId.contains("class") || it.targetNodeId.contains("Experimental") })

        // Check multiline default parameter single-expression function endLine
        val computeNode = byName["computeScore"]
        assertNotNull(computeNode)
        assertEquals(20, computeNode.startLine)
        assertEquals(23, computeNode.endLine)

        // Check that IMPORTS edges are attached only to top-level declarations, not member functions
        val importEdges = edges.filter { it.edgeType == "IMPORTS" }
        assertEquals(3, importEdges.size)
        assertTrue(importEdges.none { it.sourceNodeId == computeNode.nodeId })

        // Check that CALLS to computeScore is attributed to invokeCompute at exact call line (26), not duplicated on SampleReport
        val invokeNode = byName["invokeCompute"]
        assertNotNull(invokeNode)
        val callEdgesFromInvoke = edges.filter { it.sourceNodeId == invokeNode.nodeId && it.edgeType == "CALLS" }
        assertEquals(1, callEdgesFromInvoke.size)
        assertEquals("sym:computeScore", callEdgesFromInvoke[0].targetNodeId)
        assertEquals(26, callEdgesFromInvoke[0].lineNumber)

        val callEdgesFromClass = edges.filter { it.sourceNodeId == reportNode.nodeId && it.edgeType == "CALLS" && it.targetNodeId == "sym:computeScore" }
        assertTrue(callEdgesFromClass.isEmpty(), "Enclosing class should not duplicate CALLS made inside member functions")
    }

    @Test
    fun testPythonIndentationBlockBeyond30Lines() {
        val bodyLines = (1..40).joinToString("\n") { "    step_$it = $it" }
        val pyCode = """
import os
from pathlib import Path

def long_pipeline(
    input_path: str,
    retries: int = 3
):
$bodyLines
    return execute_final_step(input_path)

def second_func():
    return long_pipeline("a")
        """.trimIndent()

        val (nodes, edges, topLevelCount) = PolyglotAstExtractor.extractFile(
            projectId = "py_proj",
            relPath = "pipeline/runner.py",
            sourceText = pyCode
        )

        assertEquals(2, topLevelCount)
        val byName = nodes.associateBy { it.name }
        val longNode = byName["long_pipeline"]
        assertNotNull(longNode)
        assertEquals(4, longNode.startLine)
        // 47 lines total up to `return execute_final_step(input_path)` (> 30 lines!)
        assertEquals(48, longNode.endLine)

        val callFromLong = edges.filter { it.sourceNodeId == longNode.nodeId && it.edgeType == "CALLS" }
        assertTrue(callFromLong.any { it.targetNodeId == "sym:execute_final_step" && it.lineNumber == 48 })
    }

    @Test
    fun testRustAndCppExtraction() {
        val rsCode = """
            use std::collections::HashMap;

            pub struct GraphEngine {
                pub count: usize,
            }

            pub async fn build_graph() -> GraphEngine {
                let map = HashMap::new();
                helper_init();
                GraphEngine { count: 1 }
            }

            fn helper_init() {}
        """.trimIndent()

        val (rsNodes, rsEdges, rsTop) = PolyglotAstExtractor.extractFile(
            projectId = "rs_proj",
            relPath = "src/engine.rs",
            sourceText = rsCode
        )
        assertEquals(3, rsTop)
        val rsByName = rsNodes.associateBy { it.name }
        assertNotNull(rsByName["GraphEngine"])
        val buildFn = rsByName["build_graph"]
        assertNotNull(buildFn)
        assertEquals(7, buildFn.startLine)
        assertEquals(11, buildFn.endLine)
        assertTrue(rsEdges.any { it.sourceNodeId == buildFn.nodeId && it.targetNodeId == "sym:HashMap" && it.edgeType == "CALLS" })
        assertTrue(rsEdges.any { it.sourceNodeId == buildFn.nodeId && it.targetNodeId == "sym:helper_init" && it.edgeType == "CALLS" })

        val cppCode = """
            #include <vector>
            #include "spatial/rtree_index.hpp"

            namespace spatial {
            template <typename T>
            class RTreeNode {
            public:
                void insertItem() {
                    rebalanceTree();
                }
            };
            }
        """.trimIndent()

        val (cppNodes, cppEdges, cppTop) = PolyglotAstExtractor.extractFile(
            projectId = "cpp_proj",
            relPath = "src/rtree.cpp",
            sourceText = cppCode
        )
        assertTrue(cppTop >= 1)
        assertTrue(cppNodes.any { it.name == "RTreeNode" })
        assertTrue(cppEdges.any { it.edgeType == "IMPORTS" && it.targetNodeId == "sym:rtree_index.hpp" })
    }

    @Test
    fun testCrossFileEdgeResolutionAndAmbiguityConfidence() {
        val nodeCaller = GraphNode(
            nodeId = "p:Caller.kt#Caller", projectId = "p", fileId = "p:Caller.kt",
            name = "Caller", qualifiedName = "pkg.Caller", kind = "class"
        )
        val nodeTarget1 = GraphNode(
            nodeId = "p:A.kt#processData", projectId = "p", fileId = "p:A.kt",
            name = "processData", qualifiedName = "pkg.a.processData", kind = "function"
        )
        val nodeTarget2 = GraphNode(
            nodeId = "p:B.kt#processData", projectId = "p", fileId = "p:B.kt",
            name = "processData", qualifiedName = "pkg.b.processData", kind = "function"
        )
        val rawCall = GraphEdge(
            edgeId = "p:Caller.kt#Caller:call:processData:10",
            projectId = "p",
            sourceNodeId = nodeCaller.nodeId,
            targetNodeId = "sym:processData",
            edgeType = "CALLS",
            provenance = "INFERRED_CALL",
            confidence = 0.85,
            lineNumber = 10
        )

        val resolved = PolyglotAstExtractor.resolveCrossFileEdges(
            projectId = "p",
            nodes = listOf(nodeCaller, nodeTarget1, nodeTarget2),
            rawEdges = listOf(rawCall)
        )
        assertEquals(1, resolved.size)
        assertEquals(0.75, resolved[0].confidence, "Ambiguous inferred call should downgrade confidence to 0.75")
    }

    @Test
    fun testSemioticLayerClassifierKmpNativeSourceSets() {
        assertEquals("macosArm64Main", SemioticLayerClassifier.detectSourceSet("src/macosArm64Main/kotlin/atlas/Main.kt"))
        assertEquals("macosArm64Test", SemioticLayerClassifier.detectSourceSet("src/macosArm64Test/kotlin/atlas/Helper.kt"))
        assertEquals("iosTest", SemioticLayerClassifier.detectSourceSet("shared/src/iosTest/kotlin/Sample.kt"))

        val (semTest, archTest, _) = SemioticLayerClassifier.classify("src/macosArm64Test/kotlin/atlas/TestFixtureHelper.kt", "TestFixtureHelper")
        assertEquals("test", semTest)
        assertEquals("test", archTest)

        val (semEngine, archEngine, _) = SemioticLayerClassifier.classify("src/macosArm64Main/kotlin/atlas/engine/ArchitectureAuditor.kt", "ArchitectureAuditor")
        assertEquals("semantic", semEngine)
        assertEquals("application", archEngine)

        val (semExtract, archExtract, _) = SemioticLayerClassifier.classify("src/macosArm64Main/kotlin/atlas/extract/PolyglotAstExtractor.kt", "PolyglotAstExtractor")
        assertEquals("semantic", semExtract)
        assertEquals("infrastructure", archExtract)
    }

    @Test
    fun testNestedAnnotationsAndPythonMultilineTripleQuote() {
        val ktCode = """
            package atlas.model

            @OptIn(ExperimentalStdlibApi::class)
            @Suppress(names = arrayOf("UNUSED_PARAMETER", "DEPRECATION"))
            class AnnotatedWithNestedParens(val id: String) {
                fun compute(): Int = 42
            }
        """.trimIndent()

        val (ktNodes, _, ktTop) = PolyglotAstExtractor.extractFile(
            projectId = "proj",
            relPath = "src/AnnotatedWithNestedParens.kt",
            sourceText = ktCode
        )
        assertEquals(1, ktTop)
        val clsNode = ktNodes.firstOrNull { it.name == "AnnotatedWithNestedParens" }
        assertNotNull(clsNode)
        assertEquals(5, clsNode.startLine)
        assertEquals("class AnnotatedWithNestedParens(val id: String) {", clsNode.signature)

        val tq = "\"\"\""
        val pyCode = """
def query_with_outdented_sql():
    sql = $tq
SELECT *
FROM nodes
WHERE project_id = 'test'
$tq
    return execute_sql_query(sql)
        """.trimIndent()

        val (pyNodes, pyEdges, pyTop) = PolyglotAstExtractor.extractFile(
            projectId = "py_proj",
            relPath = "src/db_helper.py",
            sourceText = pyCode
        )
        assertEquals(1, pyTop)
        val fnNode = pyNodes.firstOrNull { it.name == "query_with_outdented_sql" }
        assertNotNull(fnNode)
        assertEquals(1, fnNode.startLine)
        assertEquals(7, fnNode.endLine, "Python function with outdented triple-quoted string should not terminate early")
        assertTrue(pyEdges.any { it.sourceNodeId == fnNode.nodeId && it.targetNodeId == "sym:execute_sql_query" && it.lineNumber == 7 })
    }

    @Test
    fun testCommentAndStringMaskingPreventsPhantomDeclarationsAndCalls() {
        val tq = "\"\"\""
        val ktCode = """
            package atlas.sample

            /*
            class PhantomInBlockComment {
                fun ghostMethod() { fakeCallInComment() }
            }
            */
            // fun commentedOutTopLevelFun() {}
            class RealService {
                val sql = $tq
            class PhantomInRawString {
                fun rawStringGhost() {}
            }
            $tq
                fun runRealWork(): String {
                    val msg = "fakeCallInStringLiteral(123)"
                    // fakeCallInLineComment()
                    return helperAction(msg)
                }
            }
        """.trimIndent()

        val (nodes, edges, topLevelCount) = PolyglotAstExtractor.extractFile(
            projectId = "mask_proj",
            relPath = "src/RealService.kt",
            sourceText = ktCode
        )
        assertEquals(1, topLevelCount, "Commented-out and raw-string declarations must not inflate topLevelCount")
        val names = nodes.map { it.name }.toSet()
        assertTrue("RealService" in names)
        assertTrue("runRealWork" in names)
        assertFalse("PhantomInBlockComment" in names)
        assertFalse("commentedOutTopLevelFun" in names)
        assertFalse("PhantomInRawString" in names)

        val callTargets = edges.filter { it.edgeType == "CALLS" }.map { it.targetNodeId }.toSet()
        assertTrue("sym:helperAction" in callTargets)
        assertFalse("sym:fakeCallInStringLiteral" in callTargets)
        assertFalse("sym:fakeCallInLineComment" in callTargets)
        assertFalse("sym:fakeCallInComment" in callTargets)
    }

    @Test
    fun testContextAwareCrossFileEdgeResolution() {
        val callerNode = GraphNode(
            nodeId = "p:feature/OrderVm.kt#OrderVm",
            projectId = "p",
            fileId = "p:feature/OrderVm.kt",
            name = "OrderVm",
            qualifiedName = "app.feature.OrderVm",
            kind = "class",
            moduleId = ":feature:order"
        )
        val unrelatedTarget = GraphNode(
            nodeId = "p:legacy/Validator.kt#validate",
            projectId = "p",
            fileId = "p:legacy/Validator.kt",
            name = "validate",
            qualifiedName = "app.legacy.validate",
            kind = "function",
            moduleId = ":legacy"
        )
        val importedTarget = GraphNode(
            nodeId = "p:domain/OrderValidator.kt#validate",
            projectId = "p",
            fileId = "p:domain/OrderValidator.kt",
            name = "validate",
            qualifiedName = "app.domain.validate",
            kind = "function",
            moduleId = ":domain"
        )
        val sameModuleTarget = GraphNode(
            nodeId = "p:feature/OrderHelper.kt#formatOrder",
            projectId = "p",
            fileId = "p:feature/OrderHelper.kt",
            name = "formatOrder",
            qualifiedName = "app.feature.formatOrder",
            kind = "function",
            moduleId = ":feature:order"
        )
        val otherModuleTarget = GraphNode(
            nodeId = "p:other/OtherHelper.kt#formatOrder",
            projectId = "p",
            fileId = "p:other/OtherHelper.kt",
            name = "formatOrder",
            qualifiedName = "app.other.formatOrder",
            kind = "function",
            moduleId = ":other"
        )

        val rawEdges = listOf(
            GraphEdge(
                edgeId = "${callerNode.nodeId}:imp:app.domain.validate:2",
                projectId = "p",
                sourceNodeId = callerNode.nodeId,
                targetNodeId = "sym:app.domain.validate",
                edgeType = "IMPORTS",
                lineNumber = 2
            ),
            GraphEdge(
                edgeId = "${callerNode.nodeId}:call:validate:15",
                projectId = "p",
                sourceNodeId = callerNode.nodeId,
                targetNodeId = "sym:validate",
                edgeType = "CALLS",
                provenance = "INFERRED_CALL",
                confidence = 0.85,
                lineNumber = 15
            ),
            GraphEdge(
                edgeId = "${callerNode.nodeId}:call:formatOrder:16",
                projectId = "p",
                sourceNodeId = callerNode.nodeId,
                targetNodeId = "sym:formatOrder",
                edgeType = "CALLS",
                provenance = "INFERRED_CALL",
                confidence = 0.85,
                lineNumber = 16
            )
        )

        // Note: unrelatedTarget and otherModuleTarget are placed FIRST in the list to prove we don't blindly pick candidates[0]
        val resolved = PolyglotAstExtractor.resolveCrossFileEdges(
            projectId = "p",
            nodes = listOf(callerNode, unrelatedTarget, importedTarget, otherModuleTarget, sameModuleTarget),
            rawEdges = rawEdges
        )
        val validateCall = resolved.firstOrNull { it.edgeType == "CALLS" && it.edgeId.contains(":call:validate:") }
        assertNotNull(validateCall)
        assertEquals(importedTarget.nodeId, validateCall.targetNodeId, "Explicitly imported symbol must win over first candidate")

        val formatCall = resolved.firstOrNull { it.edgeType == "CALLS" && it.edgeId.contains(":call:formatOrder:") }
        assertNotNull(formatCall)
        assertEquals(sameModuleTarget.nodeId, formatCall.targetNodeId, "Same-module symbol must win over other-module candidate")
    }

    @Test
    fun testTypeScriptRustCppKindsAndPythonMultilineSignature() {
        val tsCode = """
            // import { FakeImport } from './fake';
            export interface UserDto {
                id: string;
            }
            export type UserId = string;
            export enum UserRole { Admin, Guest }
            export const MAX_USERS = 100;
            export let mutableCounter = 0;
            export const fetchUser = async (id: UserId): Promise<UserDto> => {
                return loadFromApi(id);
            };
        """.trimIndent()

        val (tsNodes, tsEdges, tsTop) = PolyglotAstExtractor.extractFile("ts_p", "src/user.ts", tsCode)
        val tsByName = tsNodes.associateBy { it.name }
        assertEquals("interface", tsByName["UserDto"]?.kind)
        assertEquals("typealias", tsByName["UserId"]?.kind)
        assertEquals("enum", tsByName["UserRole"]?.kind)
        assertEquals("variable", tsByName["MAX_USERS"]?.kind)
        assertEquals("variable", tsByName["mutableCounter"]?.kind)
        assertEquals("function", tsByName["fetchUser"]?.kind)
        assertTrue(tsEdges.none { it.edgeType == "IMPORTS" && it.targetNodeId == "sym:FakeImport" }, "Commented-out TS import must be ignored")
        // UserDto, UserRole, fetchUser count toward topLevelCount; typealias and const/let scalar variables do not
        assertEquals(3, tsTop)

        val rsCode = """
            pub mod submod;
            pub type NodeId = String;
            pub trait GraphStore {
                fn query(&self);
            }
            pub enum Direction { Incoming, Outgoing }
        """.trimIndent()
        val (rsNodes, _, rsTop) = PolyglotAstExtractor.extractFile("rs_p", "src/lib.rs", rsCode)
        val rsByName = rsNodes.associateBy { it.name }
        assertEquals("module", rsByName["submod"]?.kind)
        assertEquals("typealias", rsByName["NodeId"]?.kind)
        assertEquals("interface", rsByName["GraphStore"]?.kind)
        assertEquals("enum", rsByName["Direction"]?.kind)
        assertEquals(2, rsTop)

        val cppCode = """
            // #include "commented/phantom_header.hpp"
            namespace engine {
            struct Point { int x; int y; };
            enum class Color { Red, Blue };
            int computeDistance(int a, int b) {
                return helperMath(a, b);
            }
            }
        """.trimIndent()
        val (cppNodes, cppEdges, cppTop) = PolyglotAstExtractor.extractFile("cpp_p", "src/math.cpp", cppCode)
        val cppByName = cppNodes.associateBy { it.name }
        assertEquals("namespace", cppByName["engine"]?.kind)
        assertEquals("struct", cppByName["Point"]?.kind)
        assertEquals("enum", cppByName["Color"]?.kind)
        assertEquals("function", cppByName["computeDistance"]?.kind)
        assertEquals(3, cppTop, "namespace wrapper should not inflate topLevelCount")
        assertTrue(cppEdges.any { it.sourceNodeId == cppByName["computeDistance"]?.nodeId && it.targetNodeId == "sym:helperMath" })
        assertTrue(cppEdges.none { it.edgeType == "IMPORTS" && it.targetNodeId == "sym:phantom_header.hpp" })

        val pyCode = """
            def transform_records(
                items: list[str],
                 delimiter: str = ":("
            ) -> list[str]:
                return normalize_items(items)
        """.trimIndent()
        val (pyNodes, pyEdges, _) = PolyglotAstExtractor.extractFile("py_p", "src/transform.py", pyCode)
        val pyFn = pyNodes.firstOrNull { it.name == "transform_records" }
        assertNotNull(pyFn)
        assertTrue(pyFn.signature.contains("items: list[str]") && pyFn.signature.contains("-> list[str]:"))
        assertEquals(5, pyFn.endLine)
        val pyCalls = pyEdges.filter { it.sourceNodeId == pyFn.nodeId && it.edgeType == "CALLS" }
        assertTrue(pyCalls.any { it.targetNodeId == "sym:normalize_items" && it.lineNumber == 5 })
    }

    @Test
    fun testGradleTypesafeAccessorWithKnownModulesAndDeterministicEdgeIds() {
        val buildKts = """
            dependencies {
                implementation(projects.feature.OrderCheckout)
                implementation(project(":core:network"))
            }
        """.trimIndent()
        val knownModules = listOf(":feature:order-checkout", ":core:network")
        val (_, edges) = ModuleBoundaryDetector.extractFromBuildContent(
            projectId = "gradle_p",
            relPath = "app/build.gradle.kts",
            sourceText = buildKts,
            parentModule = ":app",
            knownModules = knownModules
        )
        assertEquals(2, edges.size)
        val edgeIds = edges.map { it.edgeId }.toSet()
        assertTrue("gradle_p:mod_dep::app->:feature:order-checkout" in edgeIds)
        assertTrue("gradle_p:mod_dep::app->:core:network" in edgeIds)
    }

    @Test
    fun testKotlinDeclarationModifiers() {
        val ktCode = """
            package test.modifiers

            final class FinalService
            inner class InnerHelper
            enum class Status { ACTIVE, INACTIVE }
            annotation class Marker(val id: String)

            class MathOps {
                operator fun plus(other: MathOps): MathOps = this
                infix fun to(other: MathOps): Pair<MathOps, MathOps> = Pair(this, other)
                tailrec fun loop(n: Int): Int = if (n <= 0) 0 else loop(n - 1)
                external fun nativeFastCompute(): Long
            }
        """.trimIndent()

        val (nodes, edges, topLevelCount) = PolyglotAstExtractor.extractFile(
            projectId = "p",
            relPath = "src/main/kotlin/Modifiers.kt",
            sourceText = ktCode
        )

        val byName = nodes.associateBy { it.name }
        assertNotNull(byName["FinalService"])
        assertEquals("class", byName["FinalService"]?.kind)

        assertNotNull(byName["InnerHelper"])
        assertEquals("class", byName["InnerHelper"]?.kind)

        assertNotNull(byName["Status"])
        assertEquals("enum", byName["Status"]?.kind)

        assertNotNull(byName["Marker"])
        assertEquals("annotation", byName["Marker"]?.kind)

        assertNotNull(byName["plus"])
        assertEquals("function", byName["plus"]?.kind)

        assertNotNull(byName["to"])
        assertEquals("function", byName["to"]?.kind)

        assertNotNull(byName["loop"])
        assertEquals("function", byName["loop"]?.kind)

        assertNotNull(byName["nativeFastCompute"])
        assertEquals("function", byName["nativeFastCompute"]?.kind)
    }

    @Test
    fun testTypeScriptConstEnumAndKindResolution() {
        val tsCode = """
            export const enum Direction {
                Up = "UP",
                Down = "DOWN"
            }

            export enum RegularEnum {
                First,
                Second
            }

            export const regularConst = 42;
        """.trimIndent()

        val (nodes, _, topCount) = PolyglotAstExtractor.extractFile(
            projectId = "p",
            relPath = "src/enums.ts",
            sourceText = tsCode
        )

        val byName = nodes.associateBy { it.name }
        val constEnum = byName["Direction"]
        assertNotNull(constEnum)
        assertEquals("enum", constEnum.kind)
        assertEquals("Direction", constEnum.name)

        val regularEnum = byName["RegularEnum"]
        assertNotNull(regularEnum)
        assertEquals("enum", regularEnum.kind)

        val regularConst = byName["regularConst"]
        assertNotNull(regularConst)
        assertEquals("variable", regularConst.kind)
    }

    @Test
    fun testStringInterpolationExpressionExtraction() {
        val q3 = "\"\"\""
        val ktCode = "package test.interp\n\n" +
            "fun formatGreeting(name: String): String {\n" +
            "    val single = \"Hello \${lookupUser(name)}!\"\n" +
            "    val multi = $q3 Multiline: \${fetchMetadata()} $q3\n" +
            "    return single + multi\n" +
            "}\n" +
            "fun lookupUser(n: String): String = n\n" +
            "fun fetchMetadata(): String = \"\"\n"

        val (ktNodes, ktEdges, _) = PolyglotAstExtractor.extractFile(
            projectId = "p",
            relPath = "src/Interp.kt",
            sourceText = ktCode
        )
        val greetingNode = ktNodes.first { it.name == "formatGreeting" }
        val callTargets = ktEdges.filter { it.sourceNodeId == greetingNode.nodeId && it.edgeType == "CALLS" }
            .map { it.targetNodeId }

        assertTrue(callTargets.contains("sym:lookupUser"), "Function call inside single-quote interpolation must be captured")
        assertTrue(callTargets.contains("sym:fetchMetadata"), "Function call inside triple-quote interpolation must be captured")

        // TypeScript template literals
        val tsCode = """
            function renderCard(id: string) {
                const message = `Card item: ${'$'}{calcItemTotal(id)} has status`;
                return message;
            }
            function calcItemTotal(x: string) { return 100; }
        """.trimIndent()
        val (tsNodes, tsEdges, _) = PolyglotAstExtractor.extractFile(
            projectId = "p",
            relPath = "src/render.ts",
            sourceText = tsCode
        )
        val renderNode = tsNodes.first { it.name == "renderCard" }
        val tsCallTargets = tsEdges.filter { it.sourceNodeId == renderNode.nodeId && it.edgeType == "CALLS" }
            .map { it.targetNodeId }
        assertTrue(tsCallTargets.contains("sym:calcItemTotal"), "TS call inside backtick interpolation must be captured")

        // Swift string interpolation
        val swiftCode = """
            func buildSwiftString() -> String {
                return "Value is \(swiftHelper())!"
            }
            func swiftHelper() -> Int { return 1 }
        """.trimIndent()
        val (swiftNodes, swiftEdges, _) = PolyglotAstExtractor.extractFile(
            projectId = "p",
            relPath = "Sources/App.swift",
            sourceText = swiftCode
        )
        val swiftFn = swiftNodes.first { it.name == "buildSwiftString" }
        val swiftCallTargets = swiftEdges.filter { it.sourceNodeId == swiftFn.nodeId && it.edgeType == "CALLS" }
            .map { it.targetNodeId }
        assertTrue(swiftCallTargets.contains("sym:swiftHelper"), "Swift call inside \\(...) must be captured")
    }

    @Test
    fun testCrossFileReceiverMatching() {
        val file1 = """
            package com.orders

            class OrderService {
                fun processOrder() {}
            }
        """.trimIndent()

        val file2 = """
            package com.cart

            class CartService {
                fun processOrder() {}
            }
        """.trimIndent()

        val callerFile = """
            package com.app

            class AppController {
                fun run() {
                    val orderService = getOrderService()
                    orderService.processOrder()
                }
            }
        """.trimIndent()

        val (nodes1, raw1, _) = PolyglotAstExtractor.extractFile("p", "src/OrderService.kt", file1)
        val (nodes2, raw2, _) = PolyglotAstExtractor.extractFile("p", "src/CartService.kt", file2)
        val (nodes3, raw3, _) = PolyglotAstExtractor.extractFile("p", "src/AppController.kt", callerFile)

        val allNodes = nodes1 + nodes2 + nodes3
        val allRaw = raw1 + raw2 + raw3
        val resolved = PolyglotAstExtractor.resolveCrossFileEdges("p", allNodes, allRaw)

        val runNode = nodes3.first { it.name == "run" }
        val resolvedCalls = resolved.filter { it.sourceNodeId == runNode.nodeId && it.edgeType == "CALLS" }

        val orderMethod = nodes1.first { it.name == "processOrder" }
        val cartMethod = nodes2.first { it.name == "processOrder" }

        assertTrue(resolvedCalls.any { it.targetNodeId == orderMethod.nodeId }, "Receiver 'orderService' must prefer OrderService.processOrder")
        assertFalse(resolvedCalls.any { it.targetNodeId == cartMethod.nodeId }, "Receiver 'orderService' must NOT match CartService.processOrder")
    }

    @Test
    fun testHierarchicalFqnDisambiguationAndDistinctNodeIdsInSameFile() {
        val src = """
            package com.example.repo

            class UserRepository {
                fun get(id: String): String = "user"
            }

            class OrderRepository {
                fun get(id: String): String = "order"
            }
        """.trimIndent()

        val (nodes, _, topCount) = PolyglotAstExtractor.extractFile("proj", "src/Repositories.kt", src)
        assertEquals(2, topCount)

        val userGet = nodes.firstOrNull { it.qualifiedName == "com.example.repo.UserRepository.get" }
        val orderGet = nodes.firstOrNull { it.qualifiedName == "com.example.repo.OrderRepository.get" }

        assertNotNull(userGet, "UserRepository.get must have hierarchical qualifiedName")
        assertNotNull(orderGet, "OrderRepository.get must have hierarchical qualifiedName")
        assertEquals("proj:src/Repositories.kt#UserRepository.get", userGet.nodeId)
        assertEquals("proj:src/Repositories.kt#OrderRepository.get", orderGet.nodeId)
        assertFalse(userGet.nodeId == orderGet.nodeId, "Node IDs in same file with same method name must be distinct")
    }

    @Test
    fun testInfixFunctionCallRecognition() {
        val src = """
            package com.example.crypto

            class CryptoHelper {
                infix fun rotate(bits: Int): Int = bits

                fun runHash(w: IntArray, idx: Int): Int {
                    val a = (w[idx] rotate 7)
                    val b = (a rotate 18)
                    return a + b
                }
            }
        """.trimIndent()

        val (nodes, edges, _) = PolyglotAstExtractor.extractFile("proj", "src/CryptoHelper.kt", src)
        val runHashNode = nodes.firstOrNull { it.name == "runHash" }
        assertNotNull(runHashNode)

        val infixCalls = edges.filter { it.sourceNodeId == runHashNode.nodeId && it.edgeType == "CALLS" }
        assertTrue(
            infixCalls.any { it.targetNodeId == "sym:rotate" && it.provenance == "INFERRED_INFIX_CALL" },
            "Infix call 'rotate' must be recognized as an inferred infix call edge"
        )
    }

    @Test
    fun testKotlinDocDescriptionExtraction() {
        val src = """
            package com.example.service

            /**
             * Core user account management service.
             * Handles registration, authentication, and credentials.
             *
             * @param db Database handle
             */
            @Serializable
            class UserService(val db: String) {
                class NestedInner
            }

            /** Process payment transactions securely. */
            fun processPayment(amount: Double): Boolean = true

            // Regular single-line comment
            class UndocumentedService
        """.trimIndent()

        val (nodes, _, _) = PolyglotAstExtractor.extractFile("proj", "src/UserService.kt", src)
        val userNode = nodes.firstOrNull { it.name == "UserService" }
        assertNotNull(userNode)
        assertTrue(userNode.isTopLevel)
        assertEquals("Core user account management service. Handles registration, authentication, and credentials.", userNode.description)

        val procNode = nodes.firstOrNull { it.name == "processPayment" }
        assertNotNull(procNode)
        assertTrue(procNode.isTopLevel)
        assertEquals("Process payment transactions securely.", procNode.description)

        val innerNode = nodes.firstOrNull { it.name == "NestedInner" }
        assertNotNull(innerNode)
        assertFalse(innerNode.isTopLevel)
        assertEquals(null, innerNode.description)

        val undocNode = nodes.firstOrNull { it.name == "UndocumentedService" }
        assertNotNull(undocNode)
        assertTrue(undocNode.isTopLevel)
        assertEquals("Regular single-line comment", undocNode.description)
    }

    @Test
    fun testSwiftDocDescriptionExtraction() {
        val src = """
            import Foundation

            /// Manages customer sessions and tokens.
            /// Synchronizes token refreshes across threads.
            public struct SessionManager {
                var token: String
            }

            /**
             * Computes cryptographic signature for the payload.
             * - Parameter payload: The byte payload
             */
            func signPayload(payload: Data) -> String {
                return "sig"
            }
        """.trimIndent()

        val (nodes, _, _) = PolyglotAstExtractor.extractFile("proj", "Sources/SessionManager.swift", src)
        val mgrNode = nodes.firstOrNull { it.name == "SessionManager" }
        assertNotNull(mgrNode)
        assertTrue(mgrNode.isTopLevel)
        assertEquals("Manages customer sessions and tokens. Synchronizes token refreshes across threads.", mgrNode.description)

        val signNode = nodes.firstOrNull { it.name == "signPayload" }
        assertNotNull(signNode)
        assertTrue(signNode.isTopLevel)
        assertEquals("Computes cryptographic signature for the payload.", signNode.description)
    }

    @Test
    fun testTypeScriptDocDescriptionExtraction() {
        val src = """
            /**
             * Represents a verified user identity entity.
             */
            export interface UserEntity {
                id: string;
                email: string;
            }

            /**
             * Dispatches analytic telemetry events.
             * @param event The event payload
             */
            export function dispatchEvent(event: any): void {
                console.log(event);
            }
        """.trimIndent()

        val (nodes, _, _) = PolyglotAstExtractor.extractFile("proj", "src/models/user.ts", src)
        val entityNode = nodes.firstOrNull { it.name == "UserEntity" }
        assertNotNull(entityNode)
        assertTrue(entityNode.isTopLevel)
        assertEquals("Represents a verified user identity entity.", entityNode.description)

        val dispatchNode = nodes.firstOrNull { it.name == "dispatchEvent" }
        assertNotNull(dispatchNode)
        assertTrue(dispatchNode.isTopLevel)
        assertEquals("Dispatches analytic telemetry events.", dispatchNode.description)
    }

    @Test
    fun testRustDocDescriptionExtraction() {
        val src = """
            /// High-performance token bucket rate limiter.
            /// Guarantees lock-free atomic token refills.
            #[derive(Debug, Clone)]
            pub struct RateLimiter {
                capacity: usize,
            }

            /// Creates a newly initialized rate limiter.
            pub fn new_limiter(cap: usize) -> RateLimiter {
                RateLimiter { capacity: cap }
            }
        """.trimIndent()

        val (nodes, _, _) = PolyglotAstExtractor.extractFile("proj", "src/limiter.rs", src)
        val limiterNode = nodes.firstOrNull { it.name == "RateLimiter" }
        assertNotNull(limiterNode)
        assertTrue(limiterNode.isTopLevel)
        assertEquals("High-performance token bucket rate limiter. Guarantees lock-free atomic token refills.", limiterNode.description)

        val fnNode = nodes.firstOrNull { it.name == "new_limiter" }
        assertNotNull(fnNode)
        assertTrue(fnNode.isTopLevel)
        assertEquals("Creates a newly initialized rate limiter.", fnNode.description)
    }

    @Test
    fun testCppDocDescriptionExtraction() {
        val src = """
            #include <string>

            /**
             * Thread-safe memory allocation arena.
             */
            class MemoryArena {
            public:
                int capacity;
            };

            /// Allocates a contiguous block of memory.
            void* allocateBlock(size_t size) {
                return nullptr;
            }
        """.trimIndent()

        val (nodes, _, _) = PolyglotAstExtractor.extractFile("proj", "src/arena.cpp", src)
        val arenaNode = nodes.firstOrNull { it.name == "MemoryArena" }
        assertNotNull(arenaNode)
        assertTrue(arenaNode.isTopLevel)
        assertEquals("Thread-safe memory allocation arena.", arenaNode.description)

        val allocNode = nodes.firstOrNull { it.name == "allocateBlock" }
        assertNotNull(allocNode)
        assertTrue(allocNode.isTopLevel)
        assertEquals("Allocates a contiguous block of memory.", allocNode.description)
    }

    @Test
    fun testPythonDocDescriptionExtraction() {
        val src = """
            class CustomerRepository:
                ${"\"\"\""}Persistent customer repository backed by Postgres.
                Provides transactional atomic mutations.${"\"\"\""}
                def __init__(self):
                    pass

            def validate_email(email: str) -> bool:
                '''Checks RFC 5322 compliance for recipient email.'''
                return True

            # Mathematical utility helper for fast inverse square root
            def fast_inv_sqrt(number: float) -> float:
                return 1.0 / number

            def undocumented_function():
                pass
        """.trimIndent()

        val (nodes, _, _) = PolyglotAstExtractor.extractFile("proj", "src/repo.py", src)
        val repoNode = nodes.firstOrNull { it.name == "CustomerRepository" }
        assertNotNull(repoNode)
        assertTrue(repoNode.isTopLevel)
        assertEquals("Persistent customer repository backed by Postgres. Provides transactional atomic mutations.", repoNode.description)

        val valNode = nodes.firstOrNull { it.name == "validate_email" }
        assertNotNull(valNode)
        assertTrue(valNode.isTopLevel)
        assertEquals("Checks RFC 5322 compliance for recipient email.", valNode.description)

        val invNode = nodes.firstOrNull { it.name == "fast_inv_sqrt" }
        assertNotNull(invNode)
        assertTrue(invNode.isTopLevel)
        assertEquals("Mathematical utility helper for fast inverse square root", invNode.description)

        val undocNode = nodes.firstOrNull { it.name == "undocumented_function" }
        assertNotNull(undocNode)
        assertTrue(undocNode.isTopLevel)
        assertEquals(null, undocNode.description)
    }

    @Test
    fun testDocDescriptionWithMultilineAnnotation() {
        val src = """
            package demo

            /**
             * Handles refunds and chargebacks securely.
             */
            @Deprecated(
                message = "Migrate to RefundService",
                replaceWith = ReplaceWith("RefundService")
            )
            class OldRefundService
        """.trimIndent()
        val (nodes, _, _) = PolyglotAstExtractor.extractFile("proj", "src/Refund.kt", src)
        val node = nodes.firstOrNull { it.name == "OldRefundService" }
        assertNotNull(node)
        assertTrue(node.isTopLevel)
        assertEquals("Handles refunds and chargebacks securely.", node.description)
    }

    @Test
    fun testRustInnerModuleCommentNotAttachedToStruct() {
        val src = """
            //! This crate provides serialization tools.
            //! Copyright (C) 2026.

            pub struct Serializer {
                buffer: Vec<u8>,
            }
        """.trimIndent()
        val (nodes, _, _) = PolyglotAstExtractor.extractFile("proj", "src/serializer.rs", src)
        val node = nodes.firstOrNull { it.name == "Serializer" }
        assertNotNull(node)
        assertTrue(node.isTopLevel)
        assertEquals(null, node.description)
    }

    @Test
    fun testPythonInlineDocstring() {
        val src = """
            def compute_hash(payload: bytes) -> str: ${"\"\"\""}Computes SHA-256 digest.${"\"\"\""}
                pass
        """.trimIndent()
        val (nodes, _, _) = PolyglotAstExtractor.extractFile("proj", "src/hasher.py", src)
        val node = nodes.firstOrNull { it.name == "compute_hash" }
        assertNotNull(node)
        assertTrue(node.isTopLevel)
        assertEquals("Computes SHA-256 digest.", node.description)
    }

    @Test
    fun testNonDocCommentsIgnored() {
        val src = """
            package demo

            // Copyright 2026 Acme Corp. All rights reserved.
            // Licensed under Apache 2.0.
            class LicenseTestService

            // ============================================
            // Section Divider
            // ============================================
            class SectionTestService

            // MARK: - Navigation Handlers
            class MarkTestService
        """.trimIndent()
        val (nodes, _, _) = PolyglotAstExtractor.extractFile("proj", "src/Demo.kt", src)
        val licenseNode = nodes.firstOrNull { it.name == "LicenseTestService" }
        assertNotNull(licenseNode)
        assertEquals(null, licenseNode.description)

        val sectionNode = nodes.firstOrNull { it.name == "SectionTestService" }
        assertNotNull(sectionNode)
        assertEquals(null, sectionNode.description)

        val markNode = nodes.firstOrNull { it.name == "MarkTestService" }
        assertNotNull(markNode)
        assertEquals(null, markNode.description)
    }

    @Test
    fun testReceiverDisambiguationTokenOverlapAndExactMatch() {
        val callerNode = GraphNode(
            nodeId = "p:src/Caller.kt#execute",
            projectId = "p",
            fileId = "p:src/Caller.kt",
            name = "execute",
            qualifiedName = "app.Caller.execute",
            kind = "function",
            moduleId = ":app"
        )
        val complexityDoctorNode = GraphNode(
            nodeId = "p:src/ComplexityDoctorEngine.kt#runDoctor",
            projectId = "p",
            fileId = "p:src/ComplexityDoctorEngine.kt",
            name = "runDoctor",
            qualifiedName = "atlas.engine.ComplexityDoctorEngine.runDoctor",
            kind = "function",
            moduleId = ":engine"
        )
        val composeEngineNode = GraphNode(
            nodeId = "p:src/ComposeStabilityEngine.kt#runDoctor",
            projectId = "p",
            fileId = "p:src/ComposeStabilityEngine.kt",
            name = "runDoctor",
            qualifiedName = "atlas.engine.ComposeStabilityEngine.runDoctor",
            kind = "function",
            moduleId = ":engine"
        )

        val rawEdges = listOf(
            GraphEdge(
                edgeId = "call1",
                projectId = "p",
                sourceNodeId = callerNode.nodeId,
                targetNodeId = "sym:composeEngine.runDoctor",
                edgeType = "CALLS",
                provenance = "CALL_RECEIVER:composeEngine",
                lineNumber = 10
            ),
            GraphEdge(
                edgeId = "call2",
                projectId = "p",
                sourceNodeId = callerNode.nodeId,
                targetNodeId = "sym:complexityDoctor.runDoctor",
                edgeType = "CALLS",
                provenance = "CALL_RECEIVER:complexityDoctor",
                lineNumber = 11
            )
        )

        val resolved = PolyglotAstExtractor.resolveCrossFileEdges(
            projectId = "p",
            nodes = listOf(callerNode, complexityDoctorNode, composeEngineNode),
            rawEdges = rawEdges
        )

        val composeCall = resolved.firstOrNull { it.edgeId.startsWith("call1->") }
        assertNotNull(composeCall, "Edge call1 should be resolved")
        assertEquals(composeEngineNode.nodeId, composeCall.targetNodeId, "composeEngine.runDoctor must resolve to ComposeStabilityEngine.runDoctor")

        val complexityCall = resolved.firstOrNull { it.edgeId.startsWith("call2->") }
        assertNotNull(complexityCall, "Edge call2 should be resolved")
        assertEquals(complexityDoctorNode.nodeId, complexityCall.targetNodeId, "complexityDoctor.runDoctor must resolve to ComplexityDoctorEngine.runDoctor")
    }

    @Test
    fun testKotlinLocalVariableTypeReceiverResolution() {
        val src = """
            package app

            class AuditorClient {
                val composeEngine: ComposeStabilityEngine = ComposeStabilityEngine()

                fun runAudit() {
                    composeEngine.runDoctor()
                    val doctor = ComplexityDoctorEngine()
                    doctor.runDoctor()
                }
            }
        """.trimIndent()

        val (nodes, rawEdges, _) = PolyglotAstExtractor.extractFile("p", "src/AuditorClient.kt", src)
        val auditNode = nodes.firstOrNull { it.name == "runAudit" }
        assertNotNull(auditNode)

        val composeEdge = rawEdges.firstOrNull { it.targetNodeId.contains("ComposeStabilityEngine.runDoctor") }
        assertNotNull(composeEdge, "Property type inference must map composeEngine.runDoctor to ComposeStabilityEngine.runDoctor")

        val doctorEdge = rawEdges.firstOrNull { it.targetNodeId.contains("ComplexityDoctorEngine.runDoctor") }
        assertNotNull(doctorEdge, "Local variable assignment must map doctor.runDoctor to ComplexityDoctorEngine.runDoctor")
    }
}
