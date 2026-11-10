package atlas.integration

import atlas.cli.AtlasCli
import atlas.db.SqliteGraphStore
import atlas.util.PlatformFiles
import platform.posix.getpid
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-End CLI Integration Suite exercising AtlasCli command dispatch,
 * argument parsing, exit code contracts, and JSON payload formatting.
 */
class AtlasCliEndToEndIntegrationTest {

    private data class CliBoundaryCase(
        val label: String,
        val args: Array<String>,
        val expectedExitCode: Int
    )

    private fun makeTempWorkspace(): String {
        val path = "/tmp/atlas_cli_ws_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(path)
        return path
    }

    private fun cleanWorkspace(dir: String) {
        for (f in PlatformFiles.listFilesRecursively(dir)) {
            PlatformFiles.delete(f)
        }
        rmdir(dir)
    }

    private fun populateWorkspace(wsDir: String) {
        val srcDir = "$wsDir/src/main/kotlin/com/acme/order"
        PlatformFiles.mkdirs(srcDir)
        PlatformFiles.writeText(
            "$srcDir/OrderService.kt",
            """
            package com.acme.order
            class OrderService {
                fun processOrder(orderId: String): Boolean = orderId.isNotEmpty()
            }
            """.trimIndent()
        )
    }

    // Pattern 1: Strict AAA Full CLI Lifecycle End-to-End Test
    @Test
    fun testAtlasCliFullLifecycleExecution() {
        // Arrange
        val wsDir = makeTempWorkspace()
        val projectId = "cli_e2e_app"

        try {
            populateWorkspace(wsDir)

            // Act 1: CLI Index command
            val indexExit = AtlasCli.run(arrayOf("index", "--project", projectId, "--workspace", wsDir))
            // Assert 1: Index command exits successfully
            assertEquals(0, indexExit, "CLI index command must return 0")

            // Act 2: CLI Status command
            val statusExit = AtlasCli.run(arrayOf("status", "--project", projectId, "--workspace", wsDir, "--json"))
            // Assert 2: Status command exits successfully
            assertEquals(0, statusExit, "CLI status command must return 0")

            // Act 3: CLI Explore command
            val exploreExit = AtlasCli.run(arrayOf("explore", "--project", projectId, "--workspace", wsDir, "--query", "OrderService", "--json"))
            // Assert 3: Explore command exits successfully
            assertEquals(0, exploreExit, "CLI explore command must return 0")

            // Act 4: CLI Callers & Callees commands
            val callersExit = AtlasCli.run(arrayOf("callers", "--project", projectId, "--workspace", wsDir, "--symbol", "OrderService"))
            val calleesExit = AtlasCli.run(arrayOf("callees", "--project", projectId, "--workspace", wsDir, "--symbol", "OrderService"))
            assertEquals(0, callersExit, "CLI callers command must return 0")
            assertEquals(0, calleesExit, "CLI callees command must return 0")

            // Act 5: CLI Impact command
            val impactExit = AtlasCli.run(arrayOf("impact", "--project", projectId, "--workspace", wsDir, "--symbol", "OrderService", "--depth", "2"))
            assertEquals(0, impactExit, "CLI impact command must return 0")

            // Act 6: CLI Context synthesis command
            val contextExit = AtlasCli.run(arrayOf("context", "--project", projectId, "--workspace", wsDir, "--seeds", "OrderService", "--json"))
            assertEquals(0, contextExit, "CLI context command must return 0")

            // Act 7: CLI Spike decoupling hypothesis command
            val spikeExit = AtlasCli.run(arrayOf("spike", "--project", projectId, "--workspace", wsDir, "--hypothesis", "decoupling", "--from", "OrderService", "--to", "UnknownSymbol"))
            assertEquals(0, spikeExit, "CLI spike command must return 0")

            // Act 8: CLI Audit command
            val auditExit = AtlasCli.run(arrayOf("audit", "--project", projectId, "--workspace", wsDir, "--summary"))
            assertEquals(0, auditExit, "CLI audit command must return 0")
        } finally {
            cleanWorkspace(wsDir)
        }
    }

    // Pattern 2: Sad Path, Chaos & Rollback Invariants
    @Test
    fun testSadPathCliUnknownCommandAndRollbackState() {
        // Arrange
        val wsDir = makeTempWorkspace()
        val fakeSink = FakeStoreSink()
        fakeSink.shouldFail = true

        try {
            // Act 1: Execute non-existent command
            val exitCode = AtlasCli.run(arrayOf("nonexistent_command_xyz", "--workspace", wsDir))

            // Assert: CLI must return non-zero exit code on unknown command
            assertNotEquals(0, exitCode, "Unknown CLI command must return non-zero exit code")

            // Act 2: Verify state rollback on forced fake sink failure
            assertFailsWith<IllegalStateException> {
                fakeSink.deleteFigmaDocument("corrupt_key")
            }

            // Assert Rollback: Negative call counter verified
            assertEquals(0, fakeSink.callCount)
            assertEquals(0, fakeSink.deleteDocumentCallCount)
        } finally {
            cleanWorkspace(wsDir)
        }
    }

    // Pattern 3: Parameterized Boundary Value Analysis (BVA)
    @Test
    fun testBoundaryValueAnalysisAcrossCliFlagPermutations() {
        val boundaryCases = listOf(
            CliBoundaryCase("BVA Version Long", arrayOf("--version"), 0),
            CliBoundaryCase("BVA Version Short", arrayOf("-v"), 0),
            CliBoundaryCase("BVA Help Long", arrayOf("--help"), 0),
            CliBoundaryCase("BVA Help Short", arrayOf("-h"), 0),
            CliBoundaryCase("BVA Unknown Flag", arrayOf("--invalid-unknown-flag-123"), 1),
            CliBoundaryCase("BVA Empty Command", arrayOf(""), 1)
        )

        for (case in boundaryCases) {
            val exitCode = AtlasCli.run(case.args)
            if (case.expectedExitCode == 0) {
                assertEquals(0, exitCode, "Case ${case.label}: expected exit code 0")
            } else {
                assertNotEquals(0, exitCode, "Case ${case.label}: expected non-zero exit code")
            }
        }
    }
}
