package atlas

import atlas.extract.PolyglotAstExtractor
import atlas.extract.SemioticLayerClassifier
import atlas.model.GraphEdge
import atlas.model.GraphNode
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
