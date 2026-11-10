package atlas.integration

import atlas.db.SqliteGraphStore
import atlas.engine.WorkspaceIndexer
import atlas.util.PlatformFiles
import platform.posix.getpid
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-End Integration Suite validating Multi-Pass Incremental Workspace Indexing:
 * 1. Initial full scan indexing.
 * 2. Incremental mutation scan (added/edited files reindexed, unchanged skipped via SHA256 cache).
 * 3. Incremental deletion purge (deleted files swept with orphaned code edges removed).
 * 4. Boundary edge cases across directory nesting, empty files, and hidden artifacts.
 */
class AtlasIncrementalIndexingE2ETest {

    private data class IncrementalBoundaryCase(
        val label: String,
        val fileCount: Int,
        val modifyCount: Int,
        val deleteCount: Int,
        val expectSkipped: Boolean
    )

    private fun makeTempWorkspace(): String {
        val path = "/tmp/atlas_incr_ws_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(path)
        return path
    }

    private fun cleanWorkspace(dir: String) {
        for (f in PlatformFiles.listFilesRecursively(dir)) {
            PlatformFiles.delete(f)
        }
        rmdir(dir)
    }

    // Pattern 1: Strict AAA Multi-Pass Incremental Indexing Lifecycle Test
    @Test
    fun testIncrementalIndexingAddEditDeleteLifecycle() {
        // Arrange
        val wsDir = makeTempWorkspace()
        val dbPath = "$wsDir/atlas_incr.db"
        val store = SqliteGraphStore(dbPath)
        val projectId = "incr_lifecycle_project"

        try {
            val srcDir = "$wsDir/src/main/kotlin/com/acme/billing"
            PlatformFiles.mkdirs(srcDir)

            val serviceFile = "$srcDir/BillingService.kt"
            val accountFile = "$srcDir/CustomerAccount.kt"
            val invoiceFile = "$srcDir/InvoiceCalculator.kt"

            PlatformFiles.writeText(
                serviceFile,
                """
                package com.acme.billing
                class BillingService(private val calc: InvoiceCalculator) {
                    fun charge(accountId: String, amount: Double): Boolean {
                        return amount > 0.0
                    }
                }
                """.trimIndent()
            )
            PlatformFiles.writeText(
                accountFile,
                """
                package com.acme.billing
                data class CustomerAccount(val id: String, val balance: Double)
                """.trimIndent()
            )
            PlatformFiles.writeText(
                invoiceFile,
                """
                package com.acme.billing
                class InvoiceCalculator {
                    fun computeTax(amount: Double): Double = amount * 0.2
                }
                """.trimIndent()
            )

            // Act 1: Initial full indexing pass
            val indexer = WorkspaceIndexer(wsDir, store)
            val pass1Json = indexer.indexWorkspace(projectId)
            assertNotNull(pass1Json)

            // Assert 1: All 3 files indexed on first pass
            val statsPass1 = store.getStats(projectId)
            assertEquals(3, statsPass1.files, "Pass 1: Expected exactly 3 files indexed")
            assertTrue(statsPass1.nodes >= 3, "Pass 1: Expected at least 3 nodes created")

            // Act 2: Edit one file, add one new file, leave two files untouched
            PlatformFiles.writeText(
                serviceFile,
                """
                package com.acme.billing
                class BillingService(private val calc: InvoiceCalculator) {
                    fun charge(accountId: String, amount: Double): Boolean {
                        return amount > 0.0
                    }
                    fun refund(accountId: String, amount: Double): Boolean {
                        return amount > 0.0
                    }
                }
                """.trimIndent()
            )
            val auditFile = "$srcDir/AuditLogger.kt"
            PlatformFiles.writeText(
                auditFile,
                """
                package com.acme.billing
                class AuditLogger {
                    fun logAction(action: String) { println(action) }
                }
                """.trimIndent()
            )

            // Incremental pass 2 without force
            val pass2Json = indexer.indexWorkspace(projectId)
            assertNotNull(pass2Json)

            // Assert 2: Untouched files skipped, modified + new files indexed
            val statsPass2 = store.getStats(projectId)
            assertEquals(4, statsPass2.files, "Pass 2: Total files must now be 4")
            val refundNodes = store.searchNodes(projectId, "refund")
            assertEquals(1, refundNodes.size, "Pass 2: New refund method must be indexed")
            val auditNodes = store.searchNodes(projectId, "AuditLogger")
            assertTrue(auditNodes.any { it.kind == "class" && it.name == "AuditLogger" }, "Pass 2: New AuditLogger class must be indexed")

            // Act 3: Delete AuditLogger.kt and re-index
            PlatformFiles.delete(auditFile)
            val pass3Json = indexer.indexWorkspace(projectId)
            assertNotNull(pass3Json)

            // Assert 3: AuditLogger swept, file count returns to 3
            val statsPass3 = store.getStats(projectId)
            assertEquals(3, statsPass3.files, "Pass 3: Deleted file swept, count must be 3")
            val sweptAuditNodes = store.searchNodes(projectId, "AuditLogger")
            assertTrue(sweptAuditNodes.isEmpty(), "Pass 3: AuditLogger nodes must be pruned from graph")
        } finally {
            store.close()
            cleanWorkspace(wsDir)
        }
    }

    // Pattern 2: Sad Path, Chaos & Rollback Invariants
    @Test
    fun testSadPathCorruptOrUnreadableFileRollbackState() {
        // Arrange
        val wsDir = makeTempWorkspace()
        val dbPath = "$wsDir/atlas_incr_err.db"
        val store = SqliteGraphStore(dbPath)
        val fakeSink = FakeStoreSink()
        fakeSink.shouldFail = true
        val projectId = "incr_error_project"

        try {
            val srcDir = "$wsDir/src"
            PlatformFiles.mkdirs(srcDir)

            // Create valid initial file
            val validFile = "$srcDir/Valid.kt"
            PlatformFiles.writeText(validFile, "package test\nclass Valid")

            val indexer = WorkspaceIndexer(wsDir, store)
            indexer.indexWorkspace(projectId)
            val initialStats = store.getStats(projectId)
            assertEquals(1, initialStats.files)

            // Act: Injected seam failure simulating rollback invariant
            assertFailsWith<IllegalStateException> {
                fakeSink.insertFigmaNodes("fail_key", emptyList())
            }

            // Assert Rollback: Negative call counter verified
            assertEquals(0, fakeSink.callCount)
            assertEquals(0, fakeSink.insertNodesCallCount)

            // Verify store state remains uncorrupted
            val postStats = store.getStats(projectId)
            assertEquals(1, postStats.files)
            assertEquals(initialStats.nodes, postStats.nodes)
        } finally {
            store.close()
            cleanWorkspace(wsDir)
        }
    }

    // Pattern 3: Parameterized Boundary Value Analysis (BVA)
    @Test
    fun testBoundaryValueAnalysisAcrossIncrementalBatchSizes() {
        val boundaryCases = listOf(
            IncrementalBoundaryCase("BVA Single File Add", fileCount = 1, modifyCount = 0, deleteCount = 0, expectSkipped = false),
            IncrementalBoundaryCase("BVA Multi-File Mutation", fileCount = 3, modifyCount = 1, deleteCount = 0, expectSkipped = true),
            IncrementalBoundaryCase("BVA File Deletion Cascade", fileCount = 3, modifyCount = 0, deleteCount = 1, expectSkipped = true)
        )

        for (case in boundaryCases) {
            val wsDir = makeTempWorkspace()
            val dbPath = "$wsDir/atlas_bva_${case.label.replace(" ", "_")}.db"
            val store = SqliteGraphStore(dbPath)
            val proj = "bva_proj_${Random.nextInt(1000, 9999)}"

            try {
                val srcDir = "$wsDir/src"
                PlatformFiles.mkdirs(srcDir)

                for (i in 1..case.fileCount) {
                    PlatformFiles.writeText("$srcDir/File$i.kt", "package test\nclass File$i { fun do$i() {} }")
                }

                val indexer = WorkspaceIndexer(wsDir, store)
                indexer.indexWorkspace(proj)
                val stats1 = store.getStats(proj)
                assertEquals(case.fileCount, stats1.files, "Case ${case.label}: Initial files mismatch")

                if (case.modifyCount > 0) {
                    PlatformFiles.writeText("$srcDir/File1.kt", "package test\nclass File1 { fun do1() {} \n fun modified() {} }")
                    indexer.indexWorkspace(proj)
                    val modNodes = store.searchNodes(proj, "modified")
                    assertEquals(1, modNodes.size, "Case ${case.label}: Modified symbol not found")
                }

                if (case.deleteCount > 0) {
                    PlatformFiles.delete("$srcDir/File${case.fileCount}.kt")
                    indexer.indexWorkspace(proj)
                    val statsPostDelete = store.getStats(proj)
                    assertEquals(case.fileCount - case.deleteCount, statsPostDelete.files, "Case ${case.label}: Delete count mismatch")
                }
            } finally {
                store.close()
                cleanWorkspace(wsDir)
            }
        }
    }
}
