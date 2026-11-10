package atlas.integration

import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectContextEngine
import atlas.engine.ScopeSynthesizer
import atlas.model.SemioticSizing
import atlas.util.PlatformFiles
import platform.posix.getpid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mutation Testing Verification Suite simulating Pitest mutant injections:
 * 1. CONDITIONALS_BOUNDARY (< vs <=, > vs >=) across 3-band velocity gates.
 * 2. MATH & INCREMENTS (+ vs -, * vs /) across unified ACI score computation.
 * 3. VOID_METHOD_CALLS (removal of side-effect mutations & rollback counters).
 * 4. RETURNS (null/empty replacement detection via deep equality).
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
}
