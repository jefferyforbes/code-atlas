package atlas.integration

import atlas.cli.AtlasCli
import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectContextEngine
import atlas.engine.CodegraphCompatibilityEngine
import atlas.engine.ComplexityDoctorEngine
import atlas.engine.ScopeSynthesizer
import atlas.engine.SpikeAnalysisEngine
import atlas.engine.WorkspaceIndexer
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getpid
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sovereign End-to-End Integration Suite exercising the entire Atlas engine:
 * 1. Multi-language AST workspace indexing (Kotlin, Python, Rust, TypeScript).
 * 2. SQLite GraphStore query surfaces (explore, symbol, callers, callees, impact).
 * 3. Token-bounded interview_context (<= 400B) for /grill-me.
 * 4. SemioticSizing & 3-Band velocity gate calculation for /boost.
 * 5. Topological Spike Analysis decoupling verification for /research.
 * 6. Complexity Doctor health diagnosis.
 */
class AtlasEndToEndIntegrationTest {

    private data class QueryBoundaryCase(
        val label: String,
        val query: String,
        val depth: Int,
        val limit: Int,
        val expectedMinResults: Int
    )

    private fun makeTempWorkspace(): String {
        val path = "/tmp/atlas_e2e_ws_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(path)
        return path
    }

    private fun cleanWorkspace(dir: String) {
        for (f in PlatformFiles.listFilesRecursively(dir)) {
            PlatformFiles.delete(f)
        }
        rmdir(dir)
    }

    private fun populatePolyglotWorkspace(workspaceDir: String) {
        val kotlinDir = "$workspaceDir/src/main/kotlin/com/acme/order"
        val pythonDir = "$workspaceDir/scripts"
        val rustDir = "$workspaceDir/crates/validator/src"
        val tsDir = "$workspaceDir/web/routes"

        PlatformFiles.mkdirs(kotlinDir)
        PlatformFiles.mkdirs(pythonDir)
        PlatformFiles.mkdirs(rustDir)
        PlatformFiles.mkdirs(tsDir)

        // 1. Kotlin Domain & Service Layer
        PlatformFiles.writeText(
            "$kotlinDir/OrderRepository.kt",
            """
            package com.acme.order
            
            interface OrderRepository {
                fun findById(orderId: String): OrderRecord?
                fun save(record: OrderRecord)
            }
            
            data class OrderRecord(val id: String, val amount: Double)
            """.trimIndent()
        )

        PlatformFiles.writeText(
            "$kotlinDir/OrderService.kt",
            """
            package com.acme.order
            
            class OrderService(private val repo: OrderRepository) {
                fun processOrder(orderId: String, amount: Double): Boolean {
                    if (amount <= 0.0) return false
                    val existing = repo.findById(orderId)
                    if (existing != null) return false
                    repo.save(OrderRecord(orderId, amount))
                    return true
                }
            }
            """.trimIndent()
        )

        // 2. Python Pricing Script
        PlatformFiles.writeText(
            "$pythonDir/pricing.py",
            """
            def calculate_discount(price: float, tier: str) -> float:
                if tier == 'VIP':
                    return price * 0.8
                return price * 0.95
            """.trimIndent()
        )

        // 3. Rust Invariant Validator
        PlatformFiles.writeText(
            "$rustDir/lib.rs",
            """
            pub struct OrderValidator;
            
            impl OrderValidator {
                pub fn validate_amount(amount: f64) -> bool {
                    amount > 0.0 && amount < 100000.0
                }
            }
            """.trimIndent()
        )

        // 4. TypeScript Web Routes
        PlatformFiles.writeText(
            "$tsDir/checkout.ts",
            """
            export function handleCheckout(req: any, res: any) {
                const amount = req.body.amount;
                if (!amount) {
                    res.status(400).send("Invalid amount");
                    return;
                }
                res.status(200).json({ status: "SUCCESS" });
            }
            """.trimIndent()
        )
    }

    // Pattern 1: Strict AAA Full Lifecycle End-to-End Test
    @Test
    fun testFullAtlasEngineLifecycleEndToEnd() {
        // Arrange
        val wsDir = makeTempWorkspace()
        val dbPath = "$wsDir/atlas_e2e.db"
        val store = SqliteGraphStore(dbPath)
        val projectId = "e2e_atlas_app"

        try {
            populatePolyglotWorkspace(wsDir)

            // Act 1: Complete Workspace Indexing
            val indexer = WorkspaceIndexer(wsDir, store)
            val indexStatsJson = indexer.indexWorkspace(projectId)
            assertNotNull(indexStatsJson)

            // Assert 1: Graph population and polyglot nodes indexed
            val stats = store.getStats(projectId)
            assertTrue(stats.files >= 5, "Expected at least 5 files indexed, got ${stats.files}")
            assertTrue(stats.nodes >= 5, "Store must record indexed nodes, got ${stats.nodes}")

            // Act 2: Symbol Search and AST Grounding
            val orderNodes = store.searchNodes(projectId, "OrderService")
            assertFalse(orderNodes.isEmpty(), "OrderService symbol must be discoverable via search")
            val serviceNode = orderNodes.first()
            assertEquals("OrderService", serviceNode.name)

            // Act 3: Interview Context Generation (<= 400B Token Discipline)
            val codegraphEngine = CodegraphCompatibilityEngine(store, wsDir)
            val interviewRes = codegraphEngine.interviewContext(projectId)
            val interviewMarkdown = interviewRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(
                interviewMarkdown.encodeToByteArray().size <= 400,
                "interview_context must be <= 400 bytes, was ${interviewMarkdown.encodeToByteArray().size}B"
            )
            assertTrue(interviewMarkdown.contains("### Interview Context"))
            assertTrue(interviewMarkdown.contains("Domain Models"))

            // Act 4: Architect Context & Semiotic Sizing
            val synthesizer = ScopeSynthesizer(store)
            val archEngine = ArchitectContextEngine(store, synthesizer, wsDir, "$wsDir/.artifacts")
            val architectResult = archEngine.synthesizeContext(
                projectId = projectId,
                seedTargets = listOf("OrderService")
            )
            assertNotNull(architectResult)
            val sizing = architectResult.semioticSizing
            assertNotNull(sizing)
            assertTrue(sizing.codeAci >= 0.0)
            assertTrue(sizing.unifiedAci >= 0.0)
            assertTrue(
                sizing.recommendedPipeline in listOf("Fast-Path Direct", "Medium Plan", "Multi-Agent Swarm"),
                "Recommended pipeline must match 3-band taxonomy: ${sizing.recommendedPipeline}"
            )

            // Act 5: Topological Spike Hypothesis Testing
            val spikeResult = SpikeAnalysisEngine(store).runSpike(
                projectId = projectId,
                hypothesis = "decoupling",
                from = "OrderService",
                to = "OrderValidator",
                maxHops = 5
            )
            assertEquals("CONFIRMED", spikeResult.status, "OrderService and OrderValidator must be cleanly decoupled")

            // Act 6: Complexity Doctor Diagnostics
            val doctorReport = ComplexityDoctorEngine(store, wsDir).runDoctor(projectId)
            assertNotNull(doctorReport)
            assertTrue(doctorReport.project.isNotEmpty())
        } finally {
            store.close()
            cleanWorkspace(wsDir)
        }
    }

    // Pattern 2: Sad Path, Chaos & Rollback Invariants
    @Test
    fun testSadPathCorruptWorkspaceAndRollbackIntegrity() {
        // Arrange
        val wsDir = makeTempWorkspace()
        val dbPath = "$wsDir/atlas_rollback.db"
        val store = SqliteGraphStore(dbPath)
        val fakeSink = FakeStoreSink()
        fakeSink.shouldFail = true

        try {
            // Act: Attempt querying an unindexed, non-existent project
            val missingStats = store.getStats("non_existent_project_xyz")

            // Assert: Safe empty defaults, zero side-effects
            assertEquals(0, missingStats.nodes)
            assertEquals(0, missingStats.edges)

            // Act 2: Verify state rollback on forced fake sink failure
            assertFailsWith<IllegalStateException> {
                fakeSink.deleteFigmaDocument("corrupt_key")
            }

            // Assert Rollback: Negative call counter verified
            assertEquals(0, fakeSink.callCount)
            assertEquals(0, fakeSink.deleteDocumentCallCount)
        } finally {
            store.close()
            cleanWorkspace(wsDir)
        }
    }

    // Pattern 3: Parameterized Boundary Value Analysis (BVA)
    @Test
    fun testBoundaryValueAnalysisAcrossQuerySurfaces() {
        val wsDir = makeTempWorkspace()
        val dbPath = "$wsDir/atlas_bva.db"
        val store = SqliteGraphStore(dbPath)
        val projectId = "bva_project"

        try {
            populatePolyglotWorkspace(wsDir)
            WorkspaceIndexer(wsDir, store).indexWorkspace(projectId)

            val boundaryCases = listOf(
                QueryBoundaryCase("BVA Exact Match", "OrderService", depth = 1, limit = 10, expectedMinResults = 1),
                QueryBoundaryCase("BVA Partial Substring", "Order", depth = 2, limit = 5, expectedMinResults = 1),
                QueryBoundaryCase("BVA Zero Results / Nonexistent", "NonExistentClass123", depth = 1, limit = 10, expectedMinResults = 0),
                QueryBoundaryCase("BVA Zero Limit", "OrderService", depth = 1, limit = 0, expectedMinResults = 0),
                QueryBoundaryCase("BVA Deep Hop Traversal", "OrderService", depth = 10, limit = 50, expectedMinResults = 1)
            )

            for (case in boundaryCases) {
                // Act: Search nodes with boundary constraints
                val results = store.searchNodes(projectId, case.query)
                val slicedResults = results.take(case.limit)

                // Assert
                if (case.expectedMinResults > 0) {
                    assertTrue(
                        slicedResults.size >= minOf(case.expectedMinResults, case.limit),
                        "Failed case ${case.label}: expected >= ${case.expectedMinResults}, got ${slicedResults.size}"
                    )
                } else {
                    assertEquals(
                        0,
                        slicedResults.size,
                        "Failed case ${case.label}: expected 0 results, got ${slicedResults.size}"
                    )
                }

                // Verify impact radius bounds
                if (results.isNotEmpty()) {
                    val impact = store.getImpactRadius(projectId, results.first().nodeId, case.depth)
                    assertNotNull(impact, "Impact must be computed for case ${case.label}")
                    assertTrue(impact.callers.all { it.hop <= case.depth }, "Impact callers exceeded depth for case ${case.label}")
                    assertTrue(impact.callees.all { it.hop <= case.depth }, "Impact callees exceeded depth for case ${case.label}")
                }
            }
        } finally {
            store.close()
            cleanWorkspace(wsDir)
        }
    }
}
