package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.extract.ComposeStabilityExtractor
import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeStabilityEngineTest {

    @Test
    fun testGate1ScoringCeilingRealityCheckAndPolishDelta() {
        val dbPath = "/tmp/atlas_compose_engine_test_${kotlin.random.Random.nextInt(100000, 999999)}.db"
        val store = SqliteGraphStore(dbPath)
        val engine = ComposeStabilityEngine(store)
        try {
            // 1. Seed a clean composable -> raw score is 100.0 (100-Point World-Class Calibration)
            val cleanNode = ComposeNodeRecord(
                nodeId = "proj:ui/CleanScreen.kt#CleanScreen",
                projectSlug = "proj",
                fqName = "com.example.ui.CleanScreen",
                filePath = "ui/CleanScreen.kt",
                startLine = 10,
                endLine = 30,
                isSkippable = true,
                unstableParamCount = 0,
                provenance = "STATIC_INFERRED",
                violationMask = 0
            )
            val silentWasteNode = ComposeNodeRecord(
                nodeId = "proj:ui/SilentRow.kt#SilentRow",
                projectSlug = "proj",
                fqName = "com.example.ui.SilentRow",
                filePath = "ui/SilentRow.kt",
                startLine = 35,
                endLine = 55,
                isSkippable = true,
                unstableParamCount = 0,
                provenance = "STATIC_INFERRED",
                violationMask = 0
            )
            val hotspotNode = ComposeNodeRecord(
                nodeId = "proj:ui/HotCard.kt#HotCard",
                projectSlug = "proj",
                fqName = "com.example.ui.HotCard",
                filePath = "ui/HotCard.kt",
                startLine = 60,
                endLine = 90,
                isSkippable = false,
                unstableParamCount = 1,
                provenance = "COMPILER_VERIFIED",
                violationMask = ComposeStabilityExtractor.BIT_STAB_001
            )
            store.upsertComposeNodesAndParams(
                listOf(cleanNode, silentWasteNode, hotspotNode),
                listOf(
                    ComposeParamRecord(
                        nodeId = hotspotNode.nodeId,
                        paramIndex = 0,
                        paramName = "state",
                        typeSignature = "com.example.ui.HotUiState",
                        stability = "UNSTABLE",
                        stabilityReason = "MUTABLE_VAR_PROPERTY:count",
                        provenance = "COMPILER_VERIFIED"
                    )
                )
            )

            // Seed trace metrics: SilentRow has 25 recompositions & 0 skips despite being statically skippable -> SILENT_WASTE
            store.upsertComposeTraceMetric(
                nodeId = silentWasteNode.nodeId,
                sessionId = "s1",
                recompositionCount = 25,
                skipCount = 0,
                dirtyParamsJson = "[\"items\"]",
                recordedAt = 1000L
            )
            // HotCard has 40 recompositions & 2 skips -> CONFIRMED
            store.upsertComposeTraceMetric(
                nodeId = hotspotNode.nodeId,
                sessionId = "s1",
                recompositionCount = 40,
                skipCount = 2,
                dirtyParamsJson = "[\"state\"]",
                recordedAt = 1000L
            )

            val audit = engine.auditCompose("proj", strongSkipping = true)
            assertEquals("PASS", audit.status)
            assertEquals(95.0, audit.overallScore, "Overall score must reflect 100.0 minus 5.0 deduction")
            assertEquals(95.0, audit.rawUnclampedScore)
            assertEquals(4, audit.remainingPolishDelta.size, "Must populate 4 Remaining 5% Polish Delta items")

            val realityByNode = audit.realityCheck.associateBy { it.nodeId }
            assertEquals("SILENT_WASTE", realityByNode[silentWasteNode.nodeId]?.classification)
            assertEquals("CONFIRMED", realityByNode[hotspotNode.nodeId]?.classification)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testDoctorWriteSiteSafetyProofAndCompose3DScopeCollision() {
        val dbPath = "/tmp/atlas_compose_doctor_test_${kotlin.random.Random.nextInt(100000, 999999)}.db"
        val store = SqliteGraphStore(dbPath)
        val engine = ComposeStabilityEngine(store)
        val synthesizer = ScopeSynthesizer(store)
        try {
            val screenA = "proj:ui/ScreenA.kt#ScreenA"
            val screenB = "proj:ui/ScreenB.kt#ScreenB"
            val propNodeId = "proj:ui/SharedUiState.kt#mutatedTitle"

            store.upsertNodesAndEdges(
                projectId = "proj",
                nodes = listOf(
                    GraphNode(
                        nodeId = screenA,
                        projectId = "proj",
                        fileId = "proj:ui/ScreenA.kt",
                        name = "ScreenA",
                        qualifiedName = "com.example.ui.ScreenA",
                        kind = "composable"
                    ),
                    GraphNode(
                        nodeId = screenB,
                        projectId = "proj",
                        fileId = "proj:ui/ScreenB.kt",
                        name = "ScreenB",
                        qualifiedName = "com.example.ui.ScreenB",
                        kind = "composable"
                    ),
                    GraphNode(
                        nodeId = propNodeId,
                        projectId = "proj",
                        fileId = "proj:ui/SharedUiState.kt",
                        name = "mutatedTitle",
                        qualifiedName = "com.example.ui.SharedUiState.mutatedTitle",
                        kind = "property"
                    )
                ),
                edges = listOf(
                    GraphEdge(
                        edgeId = "mut1",
                        projectId = "proj",
                        sourceNodeId = screenA,
                        targetNodeId = propNodeId,
                        edgeType = "MUTATES_PROPERTY"
                    )
                )
            )

            store.upsertComposeNodesAndParams(
                nodes = listOf(
                    ComposeNodeRecord(
                        nodeId = screenA,
                        projectSlug = "proj",
                        fqName = "com.example.ui.ScreenA",
                        filePath = "ui/ScreenA.kt",
                        startLine = 10,
                        endLine = 40,
                        isSkippable = false,
                        unstableParamCount = 2,
                        violationMask = ComposeStabilityExtractor.BIT_STAB_001
                    ),
                    ComposeNodeRecord(
                        nodeId = screenB,
                        projectSlug = "proj",
                        fqName = "com.example.ui.ScreenB",
                        filePath = "ui/ScreenB.kt",
                        startLine = 10,
                        endLine = 40,
                        isSkippable = false,
                        unstableParamCount = 1,
                        violationMask = ComposeStabilityExtractor.BIT_STAB_001
                    )
                ),
                params = listOf(
                    ComposeParamRecord(
                        nodeId = screenA,
                        paramIndex = 0,
                        paramName = "sharedState",
                        typeSignature = "com.example.ui.SharedUiState",
                        stability = "UNSTABLE",
                        stabilityReason = "MUTABLE_VAR_PROPERTY:mutatedTitle"
                    ),
                    ComposeParamRecord(
                        nodeId = screenA,
                        paramIndex = 1,
                        paramName = "safeState",
                        typeSignature = "com.example.ui.SafeUiState",
                        stability = "UNSTABLE",
                        stabilityReason = "MUTABLE_VAR_PROPERTY:unmutatedFlag"
                    ),
                    ComposeParamRecord(
                        nodeId = screenB,
                        paramIndex = 0,
                        paramName = "sharedState",
                        typeSignature = "com.example.ui.SharedUiState",
                        stability = "UNSTABLE",
                        stabilityReason = "MUTABLE_VAR_PROPERTY:mutatedTitle"
                    )
                )
            )

            val doctor = engine.runDoctor("proj", target = "ScreenA")
            assertEquals("ESTIMATED", doctor.scoringMode)
            assertEquals(2, doctor.totalPrescriptions)

            val mutatedPresc = doctor.prescriptions.first { it.rationale.contains("mutatedTitle") }
            val safePresc = doctor.prescriptions.first { it.rationale.contains("unmutatedFlag") }
            assertFalse(mutatedPresc.writeSiteSafe, "mutatedTitle has 1 MUTATES_PROPERTY edge so writeSiteSafe must be false")
            assertTrue(mutatedPresc.afterSnippet.contains(".copy("), "Unsafe var conversion must prescribe .copy(...) refactoring")
            assertTrue(safePresc.writeSiteSafe, "unmutatedFlag has 0 MUTATES_PROPERTY edges so writeSiteSafe must be true")
            assertTrue(safePresc.afterSnippet.contains("val unmutatedFlag"), "Safe var conversion must prescribe val")

            // Verify Compose 3D Scope detects collision on SharedUiState across ScreenA.kt and ScreenB.kt
            val scope = synthesizer.synthesizeComposeScope("proj", listOf("ui/ScreenA.kt"))
            assertTrue(scope.sharedStableModels.contains("SharedUiState"))
            assertFalse(scope.safeForParallelDispatch, "Sharing SharedUiState with outside file ScreenB.kt must flag collision risk")
            assertTrue(scope.collisionRisks.any { it.sharedSymbolId == "SharedUiState" && it.riskKind == "SHARED_STABLE_MODEL" })
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }
}
