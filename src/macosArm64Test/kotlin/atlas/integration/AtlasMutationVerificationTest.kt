package atlas.integration

import atlas.db.NativeSqliteDatabase
import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectContextEngine
import atlas.engine.ScopeSynthesizer
import atlas.engine.SpikeAnalysisEngine
import atlas.extract.PolyglotAstExtractor
import atlas.export.GraphVisualExporter
import atlas.export.SarifExporter
import atlas.model.AuditReport
import atlas.model.FileRecordUpsert
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.model.SemioticSizing
import atlas.model.SingleDeclarationViolation
import atlas.model.SpikeRequest
import atlas.query.GraphQueryEngine
import atlas.rule.ArchitectureRules
import atlas.testdoubles.FakeGraphStore
import atlas.util.PlatformFiles
import atlas.util.Sha256
import platform.posix.getpid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mutation Testing Verification Suite simulating Pitest mutant injections:
 * 1. CONDITIONALS_BOUNDARY (< vs <=, > vs >=) across 3-band velocity gates and graph traversals.
 * 2. MATH & INCREMENTS (+ vs -, * vs /) across unified ACI score computation.
 * 3. VOID_METHOD_CALLS (removal of side-effect mutations & rollback counters).
 * 4. RETURNS (null/empty replacement detection via deep equality and scoping).
 * 5. INVERT_NEGS & BOUNDARY conditions across polyglot AST extractors.
 */
class AtlasMutationVerificationTest {

    private data class SizingBoundaryMutantCase(
        val label: String,
        val filesCount: Int,
        val modulesCount: Int,
        val codeAci: Double,
        val vscScore: Double,
        val expectedBand: String,
        val expectedPipeline: String,
        val expectedUnifiedAci: Double
    )

    private fun withTempEngine(block: (ArchitectContextEngine, SqliteGraphStore, String) -> Unit) {
        val tmpDir = "/tmp/atlas_mut_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas_mut.db"
        val store = SqliteGraphStore(dbPath)
        val synthesizer = ScopeSynthesizer(store)
        val engine = ArchitectContextEngine(store, synthesizer, tmpDir, "$tmpDir/.artifacts")
        try {
            block(engine, store, tmpDir)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
            PlatformFiles.delete(tmpDir)
        }
    }

    // Pattern 1: Happy Path AAA Exact Math Calculation Test (Kills MATH / INCREMENT Mutants)
    @Test
    fun testUnifiedAciExactMathFormulaCalculation() = withTempEngine { engine, _, _ ->
        // Arrange: codeAci = 10.0, vscScore = 2.0
        // Expected formula: (codeAci * 0.7 + vscScore * 0.3 * 10.0) = 7.0 + 6.0 = 13.0
        val codeAci = 10.0
        val vscScore = 2.0

        // Act
        val sizing = engine.computeSemioticSizing(
            filesCount = 5,
            modulesCount = 1,
            codeAci = codeAci,
            vscScore = vscScore
        )

        // Assert: Kills mutator transforming * to / or + to - or dropping coerceAtLeast
        assertEquals(10.0, sizing.codeAci, "codeAci exact rounding")
        assertEquals(2.0, sizing.vscScore, "vscScore exact rounding")
        assertEquals(13.0, sizing.unifiedAci, "unifiedAci formula: 10.0*0.7 + 2.0*3.0 = 13.0")
        assertEquals("Band 2", sizing.velocityBand)
        assertEquals("Medium Plan", sizing.recommendedPipeline)

        // Act 2: VSC lower than code ACI -> coerceAtLeast kicks in
        val lowVscSizing = engine.computeSemioticSizing(
            filesCount = 2,
            modulesCount = 1,
            codeAci = 10.0,
            vscScore = 0.5
        )
        // 10.0 * 0.7 + 0.5 * 3.0 = 8.5 -> coerced to 10.0
        assertEquals(10.0, lowVscSizing.unifiedAci, "coerceAtLeast mutator kill: must not drop below codeAci")
    }

    // Pattern 2: Sad Path & Rollback (Kills VOID_METHOD_CALLS Mutants)
    @Test
    fun testVoidMethodCallsMutatorKillersOnSideEffectsAndRollback() {
        // Arrange
        val sink = FakeStoreSink()
        sink.shouldFail = true

        // Act & Assert: Exception check verifying state rollback invariant
        assertFailsWith<IllegalStateException> {
            sink.deleteFigmaDocument("corrupt_doc_key")
        }

        // Assert Rollback: Kills mutator that removes negative assertions or call counter
        assertEquals(0, sink.callCount, "VOID_CALLS mutator kill: callCount must remain 0 on rollback")
        assertEquals(0, sink.deleteDocumentCallCount)

        // Happy side-effect: Kills mutator removing state mutation inside delete
        sink.shouldFail = false
        sink.deleteFigmaDocument("test_key")
        assertEquals(1, sink.callCount, "VOID_CALLS mutator kill: delete must increment callCount")
        assertEquals(1, sink.deleteDocumentCallCount)
    }

    // Pattern 3: Parameterized Edge Cases (Kills CONDITIONALS_BOUNDARY Mutants < vs <=, > vs >=)
    @Test
    fun testConditionalsBoundaryMutatorKillersAcrossSizingBands() = withTempEngine { engine, _, _ ->
        val boundaryCases = listOf(
            // Band 1 Exact Upper Boundary: (files = 3, modules = 1, unified = 5.0) -> Band 1
            SizingBoundaryMutantCase(
                label = "Band 1 On Boundary (3, 1, 5.0)",
                filesCount = 3, modulesCount = 1, codeAci = 5.0, vscScore = 0.0,
                expectedBand = "Band 1", expectedPipeline = "Fast-Path Direct", expectedUnifiedAci = 5.0
            ),
            // Band 1 Upper Boundary Exceeded by 1 file: files = 4 -> Band 2 (Kills filesCount < 3 mutant)
            SizingBoundaryMutantCase(
                label = "Band 1 Files Exceeded (4, 1, 5.0)",
                filesCount = 4, modulesCount = 1, codeAci = 5.0, vscScore = 0.0,
                expectedBand = "Band 2", expectedPipeline = "Medium Plan", expectedUnifiedAci = 5.0
            ),
            // Band 1 Upper Boundary Exceeded by 1 module: modules = 2 -> Band 2 (Kills modulesCount < 1 mutant)
            SizingBoundaryMutantCase(
                label = "Band 1 Modules Exceeded (3, 2, 5.0)",
                filesCount = 3, modulesCount = 2, codeAci = 5.0, vscScore = 0.0,
                expectedBand = "Band 2", expectedPipeline = "Medium Plan", expectedUnifiedAci = 5.0
            ),
            // Band 1 Upper Boundary Exceeded by 0.1 ACI: ACI = 5.1 -> Band 2 (Kills unified <= 5.0 mutant)
            SizingBoundaryMutantCase(
                label = "Band 1 ACI Exceeded (3, 1, 5.1)",
                filesCount = 3, modulesCount = 1, codeAci = 5.1, vscScore = 0.0,
                expectedBand = "Band 2", expectedPipeline = "Medium Plan", expectedUnifiedAci = 5.1
            ),
            // Band 2 Exact Upper Boundary: (files = 8, modules = 2, unified = 15.0) -> Band 2
            SizingBoundaryMutantCase(
                label = "Band 2 On Boundary (8, 2, 15.0)",
                filesCount = 8, modulesCount = 2, codeAci = 15.0, vscScore = 0.0,
                expectedBand = "Band 2", expectedPipeline = "Medium Plan", expectedUnifiedAci = 15.0
            ),
            // Band 3 Boundary Triggered by 9 files (Kills files > 8 vs >= 8 mutant)
            SizingBoundaryMutantCase(
                label = "Band 3 Files Trigger (9, 2, 15.0)",
                filesCount = 9, modulesCount = 2, codeAci = 15.0, vscScore = 0.0,
                expectedBand = "Band 3", expectedPipeline = "Multi-Agent Swarm", expectedUnifiedAci = 15.0
            ),
            // Band 3 Boundary Triggered by 3 modules (Kills modules >= 3 vs > 3 mutant)
            SizingBoundaryMutantCase(
                label = "Band 3 Modules Trigger (8, 3, 15.0)",
                filesCount = 8, modulesCount = 3, codeAci = 15.0, vscScore = 0.0,
                expectedBand = "Band 3", expectedPipeline = "Multi-Agent Swarm", expectedUnifiedAci = 15.0
            ),
            // Band 3 Boundary Triggered by 15.1 ACI (Kills unified > 15.0 vs >= 15.0 mutant)
            SizingBoundaryMutantCase(
                label = "Band 3 ACI Trigger (8, 2, 15.1)",
                filesCount = 8, modulesCount = 2, codeAci = 15.1, vscScore = 0.0,
                expectedBand = "Band 3", expectedPipeline = "Multi-Agent Swarm", expectedUnifiedAci = 15.1
            )
        )

        for (case in boundaryCases) {
            val sizing = engine.computeSemioticSizing(
                filesCount = case.filesCount,
                modulesCount = case.modulesCount,
                codeAci = case.codeAci,
                vscScore = case.vscScore
            )

            assertEquals(
                case.expectedBand,
                sizing.velocityBand,
                "Case '${case.label}' velocity band mismatch (Kills condition boundary mutant)"
            )
            assertEquals(
                case.expectedPipeline,
                sizing.recommendedPipeline,
                "Case '${case.label}' recommended pipeline mismatch"
            )
            assertEquals(
                case.expectedUnifiedAci,
                sizing.unifiedAci,
                "Case '${case.label}' unified ACI mismatch"
            )
        }
    }

    // Pattern 4: Polyglot Extraction Boundaries & Negations (Kills INVERT_NEGS & BOUNDARY Mutants)
    @Test
    fun testPolyglotConditionalsAndBoundaryMutantKillers() {
        // Boundary 1: Kotlin backticks with spaces, symbols, and operators
        val ktSource = """
            package sample.test
            
            class `Boundary Verification Service <T>` {
                fun `given boundary A <= B when processed then assert 100% passed`(): Boolean = true
            }
        """.trimIndent()
        val (ktNodes, _, ktCount) = PolyglotAstExtractor.extractFile("p", "src/Test.kt", ktSource)
        assertEquals(1, ktCount, "Top-level declaration count")
        assertTrue(ktNodes.any { it.name == "Boundary Verification Service <T>" && it.kind == "class" }, "Backtick class")
        assertTrue(ktNodes.any { it.name == "given boundary A <= B when processed then assert 100% passed" && it.kind == "function" }, "Backtick function")

        // Boundary 2: Trailing lambda with and without argument lists
        val ktLambdaSource = """
            fun executeWork() {
                task.run {
                    perform()
                }
                items.filter(condition) { item ->
                    item.isActive
                }
            }
        """.trimIndent()
        val (_, ktEdges, _) = PolyglotAstExtractor.extractFile("p", "src/Lambda.kt", ktLambdaSource)
        assertTrue(ktEdges.any { it.edgeType == "CALLS" && (it.targetNodeId == "sym:run" || it.targetNodeId == "sym:task.run") })
        assertTrue(ktEdges.any { it.edgeType == "CALLS" && (it.targetNodeId == "sym:filter" || it.targetNodeId == "sym:items.filter") })

        // Boundary 3: TypeScript complex import boundaries
        val tsSource = """
            import DefaultView, { ItemList, type DetailProps, config as cfg } from './components';
            import * as Logger from './logger';

            export function renderView() {
                ItemList();
            }
        """.trimIndent()
        val (_, tsEdges, _) = PolyglotAstExtractor.extractFile("p", "src/View.ts", tsSource)
        val tsImports = tsEdges.filter { it.edgeType == "IMPORTS" }.map { it.targetNodeId }.toSet()
        assertTrue("sym:DefaultView" in tsImports)
        assertTrue("sym:ItemList" in tsImports)
        assertTrue("sym:DetailProps" in tsImports)
        assertTrue("sym:config" in tsImports)
        assertTrue("sym:Logger" in tsImports)

        // Boundary 4: Python multi-import and relative import boundaries
        val pySource = """
            from os.path import exists, join as pjoin, isdir
            from .submodule import LocalHelper
            import sys, os

            def verify_paths():
                return exists(sys.argv[0])
        """.trimIndent()
        val (_, pyEdges, _) = PolyglotAstExtractor.extractFile("p", "src/mod.py", pySource)
        val pyImports = pyEdges.filter { it.edgeType == "IMPORTS" }.map { it.targetNodeId }.toSet()
        assertTrue("sym:exists" in pyImports)
        assertTrue("sym:join" in pyImports)
        assertTrue("sym:isdir" in pyImports)
        assertTrue("sym:LocalHelper" in pyImports)
        assertTrue("sym:sys" in pyImports)
        assertTrue("sym:os" in pyImports)

        // Boundary 5: C++ constructor initializer list boundary
        val cppSource = """
            class DeviceChannel {
            public:
                DeviceChannel(int id, void* handle) : m_id(id), m_handle(handle) {
                    connectDevice();
                }
                void connectDevice() {}
            };
        """.trimIndent()
        val (cppNodes, cppEdges, _) = PolyglotAstExtractor.extractFile("p", "src/device.cpp", cppSource)
        assertTrue(cppNodes.any { it.name == "DeviceChannel" && it.kind == "function" })
        assertTrue(cppEdges.any { it.edgeType == "CALLS" && it.targetNodeId == "sym:connectDevice" })
        // Mutant kill: member inits m_id(id) must NOT be mis-extracted as function declarations
        assertFalse(cppNodes.any { it.name == "m_id" })
        assertFalse(cppNodes.any { it.name == "m_handle" })
    }

    // Pattern 5: Transaction Rollback on Abort (Kills VOID_METHOD_CALLS Mutants in Transaction Scopes)
    @Test
    fun testFakeGraphStoreRollbackMutantKillersOnAbortedTransactions() {
        val store = FakeGraphStore()
        val node1 = GraphNode(nodeId = "p:1", projectId = "p", fileId = "f1", name = "N1", qualifiedName = "N1", kind = "class")
        val node2 = GraphNode(nodeId = "p:2", projectId = "p", fileId = "f2", name = "N2", qualifiedName = "N2", kind = "class")
        val edge1 = GraphEdge(edgeId = "e1", projectId = "p", sourceNodeId = "p:1", targetNodeId = "p:2", edgeType = "CALLS")
        store.upsertNodesAndEdges("p", listOf(node1, node2), listOf(edge1))

        assertEquals(2, store.nodes.size)
        assertEquals(1, store.edges.size)

        // Sad path: Aborted transaction must restore state to exact pre-transaction snapshot
        assertFailsWith<IllegalStateException> {
            store.withImmediateTransaction {
                val node3 = GraphNode(nodeId = "p:3", projectId = "p", fileId = "f3", name = "N3", qualifiedName = "N3", kind = "class")
                val edge2 = GraphEdge(edgeId = "e2", projectId = "p", sourceNodeId = "p:1", targetNodeId = "p:3", edgeType = "CALLS")
                store.upsertNodesAndEdges("p", listOf(node3), listOf(edge2))
                store.upsertFileRecord(FileRecordUpsert(projectId = "p", relPath = "f3", language = "kotlin", sha256 = "h3", loc = 10))
                throw IllegalStateException("Simulated transaction abort")
            }
        }

        // Assert Rollback: Kills VOID_METHOD_CALLS mutants that drop rollback logic
        assertEquals(2, store.nodes.size, "Aborted transaction must not leak newly inserted nodes")
        assertEquals(1, store.edges.size, "Aborted transaction must not leak newly inserted edges")
        assertEquals(0, store.files.size, "Aborted transaction must not leak file records")

        // Happy path: Successful transaction persists modifications
        store.withImmediateTransaction {
            val node3 = GraphNode(nodeId = "p:3", projectId = "p", fileId = "f3", name = "N3", qualifiedName = "N3", kind = "class")
            val edge2 = GraphEdge(edgeId = "e2", projectId = "p", sourceNodeId = "p:1", targetNodeId = "p:3", edgeType = "CALLS")
            store.upsertNodesAndEdges("p", listOf(node3), listOf(edge2))
        }

        assertEquals(3, store.nodes.size, "Committed transaction must retain nodes")
        assertEquals(2, store.edges.size, "Committed transaction must retain edges")
    }

    // Pattern 6: Closed Database Guard (Kills VOID_METHOD_CALLS Mutants in Lifecycle Gates)
    @Test
    fun testClosedDatabaseGuardVoidCallMutants() {
        val db = NativeSqliteDatabase(":memory:")
        db.close()
        assertTrue(db.isClosed, "Database must be marked closed")

        assertFailsWith<IllegalStateException> {
            db.exec("CREATE TABLE test (id INT)")
        }
        assertFailsWith<IllegalStateException> {
            db.prepare("SELECT 1")
        }
        assertFailsWith<IllegalStateException> {
            db.withImmediateTransaction { /* no-op */ }
        }
    }

    // Pattern 7: Spike Hypothesis Shortest Path Boundary (Kills CONDITIONALS_BOUNDARY Mutants hops <= maxHops)
    @Test
    fun testSpikeHypothesisCalculationBoundaryMutants() {
        val store = FakeGraphStore()
        val projectId = "spike-mut"
        val engine = SpikeAnalysisEngine(store)

        // Graph chain: A -> B -> C -> D (3 hops)
        val nodeA = GraphNode(nodeId = "$projectId:A", projectId = projectId, fileId = "A.kt", name = "SubsystemA", qualifiedName = "SubsystemA", kind = "class")
        val nodeB = GraphNode(nodeId = "$projectId:B", projectId = projectId, fileId = "B.kt", name = "SubsystemB", qualifiedName = "SubsystemB", kind = "class")
        val nodeC = GraphNode(nodeId = "$projectId:C", projectId = projectId, fileId = "C.kt", name = "SubsystemC", qualifiedName = "SubsystemC", kind = "class")
        val nodeD = GraphNode(nodeId = "$projectId:D", projectId = projectId, fileId = "D.kt", name = "SubsystemD", qualifiedName = "SubsystemD", kind = "class")

        val edgeAB = GraphEdge(edgeId = "e1", projectId = projectId, sourceNodeId = nodeA.nodeId, targetNodeId = nodeB.nodeId, edgeType = "CALLS")
        val edgeBC = GraphEdge(edgeId = "e2", projectId = projectId, sourceNodeId = nodeB.nodeId, targetNodeId = nodeC.nodeId, edgeType = "CALLS")
        val edgeCD = GraphEdge(edgeId = "e3", projectId = projectId, sourceNodeId = nodeC.nodeId, targetNodeId = nodeD.nodeId, edgeType = "CALLS")

        store.upsertNodesAndEdges(projectId, listOf(nodeA, nodeB, nodeC, nodeD), listOf(edgeAB, edgeBC, edgeCD))

        // Boundary Test 1: maxHops = 2. Path has 3 hops. Since 3 > 2, decoupling is CONFIRMED.
        val resHops2 = engine.runSpike(
            SpikeRequest(
                projectId = projectId,
                hypothesis = "decoupling",
                from = "SubsystemA",
                to = "SubsystemD",
                maxHops = 2
            )
        )
        assertTrue(resHops2.passed, "Decoupling must be CONFIRMED when path length (3) > maxHops (2)")
        assertEquals("CONFIRMED", resHops2.status)

        // Boundary Test 2: maxHops = 3. Path has 3 hops. Since 3 <= 3, decoupling is FALSIFIED.
        // Kills mutant changing `hops <= maxHops` to `hops < maxHops`.
        val resHops3 = engine.runSpike(
            SpikeRequest(
                projectId = projectId,
                hypothesis = "decoupling",
                from = "SubsystemA",
                to = "SubsystemD",
                maxHops = 3
            )
        )
        assertFalse(resHops3.passed, "Decoupling must be FALSIFIED when path length (3) <= maxHops (3)")
        assertEquals("FALSIFIED", resHops3.status)
        assertEquals(2, resHops3.path.size, "Path must contain symbolChain and edgeChain")
        assertTrue(resHops3.path[0].contains("SubsystemA -> SubsystemB"))

        // Boundary Test 3: maxHops = 4. Path has 3 hops. Since 3 <= 4, decoupling is FALSIFIED.
        val resHops4 = engine.runSpike(
            SpikeRequest(
                projectId = projectId,
                hypothesis = "decoupling",
                from = "SubsystemA",
                to = "SubsystemD",
                maxHops = 4
            )
        )
        assertFalse(resHops4.passed, "Decoupling must be FALSIFIED when path length (3) <= maxHops (4)")
    }

    // Pattern 8: Project Scoping and Query Returns (Kills RETURNS & EMPTY_RETURNS Mutants)
    @Test
    fun testProjectIsolationAndReturnsMutantKillers() {
        val store = FakeGraphStore()
        val nodeA = GraphNode(nodeId = "alpha:A", projectId = "proj-alpha", fileId = "fA", name = "ServiceA", qualifiedName = "ServiceA", kind = "class")
        val nodeB = GraphNode(nodeId = "beta:B", projectId = "proj-beta", fileId = "fB", name = "ServiceB", qualifiedName = "ServiceB", kind = "class")
        store.upsertNodesAndEdges("proj-alpha", listOf(nodeA), emptyList())
        store.upsertNodesAndEdges("proj-beta", listOf(nodeB), emptyList())

        val alphaNodes = store.listNodes("proj-alpha")
        assertEquals(1, alphaNodes.size, "proj-alpha must contain exactly 1 node")
        assertEquals("alpha:A", alphaNodes[0].nodeId)

        val betaNodes = store.listNodes("proj-beta")
        assertEquals(1, betaNodes.size, "proj-beta must contain exactly 1 node")
        assertEquals("beta:B", betaNodes[0].nodeId)

        val alphaStats = store.getStats("proj-alpha")
        assertEquals(1, alphaStats.nodes)
        assertEquals(0, alphaStats.edges)

        val betaStats = store.getStats("proj-beta")
        assertEquals(1, betaStats.nodes)

        // Returns mutator kill: nonexistent node lookup must return null
        assertNull(store.getNode("proj-alpha", "nonexistent"), "Must return null for unknown node")
        assertNull(store.getNode("proj-alpha", "ServiceB"), "Must return null for node belonging to another project")
    }

    // Pattern 9: Cycle Detection and Topological Sort (Kills NEGATE_CONDITIONALS & BOUNDARY Mutants)
    @Test
    fun testCycleDetectionAndTopologicalSortMutantKillers() {
        val store = FakeGraphStore()
        val project = "mut-cycle"

        // Case 1: Self-loop A -> A
        val nodeA = GraphNode(nodeId = "SelfNode", projectId = project, fileId = "S.kt", name = "SelfNode", qualifiedName = "SelfNode", kind = "class")
        val loopEdge = GraphEdge(edgeId = "self-loop", projectId = project, sourceNodeId = "SelfNode", targetNodeId = "SelfNode", edgeType = "calls")
        store.upsertNodesAndEdges(project, listOf(nodeA), listOf(loopEdge))

        val engine = GraphQueryEngine(store, project)
        val selfCycles = engine.findCycles()
        assertEquals(1, selfCycles.size, "Must detect self-loop cycle")
        assertEquals(listOf("SelfNode", "SelfNode"), selfCycles.first())
        assertNull(engine.topologicalSort(), "Self-loop graph must return null for topologicalSort (not a DAG)")

        // Case 2: Clean DAG -> topological sort must be exact
        val dagStore = FakeGraphStore()
        val dagProject = "mut-dag"
        val n1 = GraphNode(nodeId = "N1", projectId = dagProject, fileId = "1.kt", name = "N1", qualifiedName = "N1", kind = "class")
        val n2 = GraphNode(nodeId = "N2", projectId = dagProject, fileId = "2.kt", name = "N2", qualifiedName = "N2", kind = "class")
        val dagEdge = GraphEdge(edgeId = "1->2", projectId = dagProject, sourceNodeId = "N1", targetNodeId = "N2", edgeType = "calls")
        dagStore.upsertNodesAndEdges(dagProject, listOf(n1, n2), listOf(dagEdge))

        val dagEngine = GraphQueryEngine(dagStore, dagProject)
        val dagCycles = dagEngine.findCycles()
        assertEquals(0, dagCycles.size, "DAG must have 0 cycles")
        val sorted = dagEngine.topologicalSort()
        assertNotNull(sorted, "DAG must produce valid topological ordering")
        assertEquals(listOf("N1", "N2"), sorted, "Topological order must preserve edge directions (Kills Kahn queue mutant)")
    }

    // Pattern 10: ArchitectureRules Whitelist and Severity Gating (Kills INVERT_NEGS & SEVERITY Mutants)
    @Test
    fun testArchitectureRulesSeverityAndWhitelistMutantKillers() {
        val store = FakeGraphStore()
        val project = "mut-rules"

        val uiNode = GraphNode(nodeId = "ui:View", projectId = project, fileId = "src/ui/View.kt", name = "View", qualifiedName = "ui.View", kind = "class")
        val domainNode = GraphNode(nodeId = "domain:Model", projectId = project, fileId = "src/domain/Model.kt", name = "Model", qualifiedName = "domain.Model", kind = "class")
        val dataNode = GraphNode(nodeId = "data:Db", projectId = project, fileId = "src/data/Db.kt", name = "Db", qualifiedName = "data.Db", kind = "class")

        val edge1 = GraphEdge(edgeId = "e1", projectId = project, sourceNodeId = "ui:View", targetNodeId = "domain:Model", edgeType = "calls")
        val edge2 = GraphEdge(edgeId = "e2", projectId = project, sourceNodeId = "ui:View", targetNodeId = "data:Db", edgeType = "calls")
        store.upsertNodesAndEdges(project, listOf(uiNode, domainNode, dataNode), listOf(edge1, edge2))

        // Test 1: Warning-only rules must NOT cause passed = false (Kills mutant failing on Warning)
        val warningRules = ArchitectureRules.define {
            nodeRule(
                name = "WARN-ALWAYS",
                severity = ArchitectureRules.Severity.Warning
            ) { false }
        }
        val warnReport = warningRules.evaluate(store, project)
        assertTrue(warnReport.passed, "Warning-only violations must not cause passed to be false")
        assertEquals(3, warnReport.violations.size, "Must report 3 warning violations for 3 nodes")

        // Test 2: Whitelist permits domain but forbids data
        val whitelistRules = ArchitectureRules.define {
            layer("ui", "/ui/")
            layer("domain", "/domain/")
            layer("data", "/data/")

            "ui" mayOnlyDependOn setOf("ui", "domain")
        }
        val wlReport = whitelistRules.evaluate(store, project)
        assertFalse(wlReport.passed, "Direct UI->Data dependency must fail whitelist rule")
        assertEquals(1, wlReport.violations.size, "Only the UI->Data edge must violate whitelist")
        assertEquals("ARCH-DISALLOWED-DEPENDENCY", wlReport.violations.first().ruleId)
    }

    // Pattern 11: Sub-Second File Hash Modifications (Kills MTIME_ONLY & EQUALITY Mutants)
    @Test
    fun testSubSecondFileHashModificationMutantKillers() {
        val originalContent = "package test\nclass A"
        val modifiedContent = "package test\nclass A;\n// modified same second"

        val originalHash = Sha256.digest(originalContent)
        val modifiedHash = Sha256.digest(modifiedContent)

        // Mutant kill: different content with identical mtime must produce distinct hashes
        assertTrue(originalHash != modifiedHash, "Sha256 must distinguish sub-second content modifications")

        val sameHash = Sha256.digest(originalContent)
        assertEquals(originalHash, sameHash, "Identical content must produce identical hash")
    }

    // Pattern 12: SARIF Exporter L-Prefix and Severity Mapping (Kills PARSING & REGEX Mutants)
    @Test
    fun testSarifLPrefixAndSeverityMappingMutantKillers() {
        val report = AuditReport(
            projectId = "sarif-mut",
            passed = false,
            singleDeclarationViolations = listOf(
                SingleDeclarationViolation(
                    rule = "BOOST-003",
                    relPath = "src/Foo.kt",
                    topLevelDeclCount = 2,
                    message = "Multiple declarations in Foo.kt"
                )
            ),
            layerInversions = emptyList(),
            plan3dCollisions = emptyList()
        )

        val json = SarifExporter.toSarifJson(report)
        // Mutant kill: ruleId must be BOOST-003, level must be error
        assertTrue(json.contains("\"ruleId\": \"BOOST-003\""))
        assertTrue(json.contains("\"level\": \"error\""))
        assertTrue(json.contains("\"uri\": \"src/Foo.kt\""))
        assertTrue(json.contains("\"startLine\": 1"))
    }

    // Pattern 13: GraphVisualExporter Blank Fallback & Mermaid Sanitization (Kills NULL_FALLBACK Mutants)
    @Test
    fun testGraphVisualExporterDotBlankFallbackAndMermaidSanitization() {
        val blankNode = GraphNode(
            nodeId = "com.pkg.BlankNode",
            projectId = "p",
            fileId = "Blank.kt",
            name = "", // Blank name!
            qualifiedName = "com.pkg.BlankNode",
            kind = "class",
            archLayer = "domain"
        )
        val dot = GraphVisualExporter.toGraphvizDot(listOf(blankNode), emptyList())
        // Mutant kill: Blank name must fall back to nodeId in DOT label
        assertTrue(dot.contains("\"com.pkg.BlankNode\" [label=\"com.pkg.BlankNode\\n(class, domain)\"];"), "DOT label must fall back to nodeId when name is blank")

        val specialNode = GraphNode(
            nodeId = "pkg.special-name#123",
            projectId = "p",
            fileId = "S.kt",
            name = "Special-Name#123",
            qualifiedName = "pkg.Special-Name#123",
            kind = "class"
        )
        val mermaid = GraphVisualExporter.toMermaidFlowchart(listOf(specialNode), emptyList(), clusterBy = null)
        // Mutant kill: Node identifier in Mermaid must be sanitized (no special chars like '#' or '-')
        assertTrue(mermaid.contains("n0_Special_Name_123[\"Special-Name#123\"]"), "Mermaid identifier must sanitize special characters")
    }
}
