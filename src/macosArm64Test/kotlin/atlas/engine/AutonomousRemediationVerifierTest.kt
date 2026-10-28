package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.mcp.AtlasMcpServer
import atlas.model.ComposeDoctorReport
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutonomousRemediationVerifierTest {

    private fun makeTempDir(): String {
        val path = "/tmp/atlas_remediation_test_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(path)
        return path
    }

    private fun cleanDir(dir: String) {
        for (f in PlatformFiles.listFilesRecursively(dir)) {
            PlatformFiles.delete(f)
        }
        rmdir(dir)
    }

    @Test
    fun testPreFlightFailsWhenWriteSiteUnsafe() {
        val tmpDir = makeTempDir()
        try {
            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)
            val res = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "src/State.kt",
                    beforeSnippet = "var title: String",
                    afterSnippet = "val title: String",
                    ruleId = "COMPOSE-STAB-001",
                    writeSiteSafe = false
                )
            )

            assertFalse(res.valid)
            assertEquals("PRE_FLIGHT", res.phase)
            assertFalse(res.preFlightPassed)
            assertTrue(res.message.contains("Write-site mutations exist"))
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testPreFlightFailsWhenSnippetNotFoundOrAmbiguous() {
        val tmpDir = makeTempDir()
        try {
            val filePath = "$tmpDir/Widget.kt"
            PlatformFiles.writeText(
                filePath,
                """
                package com.example

                class Widget {
                    val duplicate = 1
                    val duplicate = 2
                }
                """.trimIndent()
            )

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)

            // 1. Snippet not found
            val resNotFound = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "Widget.kt",
                    beforeSnippet = "val missing = 0",
                    afterSnippet = "val replaced = 0"
                )
            )
            assertFalse(resNotFound.valid)
            assertEquals("PRE_FLIGHT", resNotFound.phase)
            assertFalse(resNotFound.preFlightPassed)
            assertTrue(resNotFound.message.contains("not found"))

            // 2. Ambiguous snippet (multiple occurrences)
            val resAmbiguous = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "Widget.kt",
                    beforeSnippet = "val duplicate =",
                    afterSnippet = "val unique ="
                )
            )
            assertFalse(resAmbiguous.valid)
            assertEquals("PRE_FLIGHT", resAmbiguous.phase)
            assertFalse(resAmbiguous.preFlightPassed)
            assertTrue(resAmbiguous.message.contains("Ambiguous replacement"))
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testPostVerificationFailsWhenAstInvalidOrBoost003Violated() {
        val tmpDir = makeTempDir()
        try {
            val filePath = "$tmpDir/OrderModel.kt"
            PlatformFiles.writeText(
                filePath,
                """
                package com.example

                class OrderModel {
                    var count: Int = 0
                }
                """.trimIndent()
            )

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)

            // 1. Candidate introduces invalid syntax (broken AST)
            val resBrokenAst = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "OrderModel.kt",
                    beforeSnippet = "var count: Int = 0",
                    afterSnippet = "var count: Int = {{{ INVALID SYNTAX"
                )
            )
            assertFalse(resBrokenAst.valid)
            assertEquals("POST_VERIFICATION", resBrokenAst.phase)
            assertFalse(resBrokenAst.astValid)

            // 2. Candidate introduces second top-level class (BOOST-003 violation)
            val resBoost003 = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "OrderModel.kt",
                    beforeSnippet = "class OrderModel {\n    var count: Int = 0\n}",
                    afterSnippet = "class OrderModel {\n    val count: Int = 0\n}\n\nclass IllegalSecondClass"
                )
            )
            assertFalse(resBoost003.valid)
            assertEquals("POST_VERIFICATION", resBoost003.phase)
            assertTrue(resBoost003.astValid)
            assertFalse(resBoost003.checksPassed)
            assertTrue(resBoost003.details.any { it.contains("BOOST-003 violation") })
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testPostVerificationFailsWhenTestSuitePreservationCheckFails() {
        val tmpDir = makeTempDir()
        try {
            val filePath = "$tmpDir/OrderModel.kt"
            PlatformFiles.writeText(
                filePath,
                """
                package com.example

                class OrderModel {
                    val count: Int = 0
                }
                """.trimIndent()
            )

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)
            val res = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "OrderModel.kt",
                    beforeSnippet = "val count: Int = 0",
                    afterSnippet = "val count: Int = 1",
                    testVerifier = { _, _ -> false } // Simulate test failure
                )
            )

            assertFalse(res.valid)
            assertEquals("POST_VERIFICATION", res.phase)
            assertFalse(res.testsPreserved)
            assertTrue(res.message.contains("Test suite preservation check failed"))
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testSuccessfulDryRunAndVerifiedApplication() {
        val tmpDir = makeTempDir()
        try {
            val filePath = "$tmpDir/CounterWidget.kt"
            val initialContent = """
                package com.example.ui

                import androidx.compose.runtime.Composable

                @Composable
                fun CounterWidget(modifier: Modifier = Modifier) {
                    val state = 1
                }
            """.trimIndent()
            PlatformFiles.writeText(filePath, initialContent)

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)

            // 1. Dry run: Validates completely without writing to disk
            val dryRes = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "CounterWidget.kt",
                    beforeSnippet = "val state = 1",
                    afterSnippet = "val state = 2",
                    dryRun = true,
                    testVerifier = { _, _ -> true }
                )
            )
            assertTrue(dryRes.valid)
            assertEquals("COMPLETED", dryRes.phase)
            assertTrue(dryRes.preFlightPassed)
            assertTrue(dryRes.astValid)
            assertTrue(dryRes.checksPassed)
            assertTrue(dryRes.testsPreserved)
            assertNotNull(dryRes.verifiedDiff)
            assertEquals(initialContent, PlatformFiles.readText(filePath), "Dry run must NOT alter disk content")

            // 2. Verified Apply: Writes safely to disk
            val applyRes = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "CounterWidget.kt",
                    beforeSnippet = "val state = 1",
                    afterSnippet = "val state = 2",
                    dryRun = false,
                    testVerifier = { _, _ -> true }
                )
            )
            assertTrue(applyRes.valid)
            assertEquals("COMPLETED", applyRes.phase)
            assertTrue(PlatformFiles.readText(filePath).contains("val state = 2"))

            // 3. Verify via Doctor Prescription object
            val prescription = ComposeDoctorReport.Prescription(
                rank = 1,
                priorityScore = 10.0,
                scoringMode = "ESTIMATED",
                ruleId = "COMPOSE-API-001",
                fixStrategy = "PARAM_UPDATE",
                writeSiteSafe = true,
                targetNodeId = "proj:CounterWidget.kt#CounterWidget",
                filePath = "CounterWidget.kt",
                line = 5,
                downstreamCascadeCount = 0,
                rationale = "Safe update",
                beforeSnippet = "val state = 2",
                afterSnippet = "val state = 3"
            )
            val prescRes = verifier.verifyPrescription("proj", prescription, dryRun = false)
            assertTrue(prescRes.valid)
            assertEquals("COMPLETED", prescRes.phase)
            assertTrue(PlatformFiles.readText(filePath).contains("val state = 3"))
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testMcpAuditToolVerifyRemediation() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        try {
            val filePath = "$tmpDir/Sample.kt"
            PlatformFiles.writeText(
                filePath,
                """
                package com.example

                class Sample {
                    val version: Int = 1
                }
                """.trimIndent()
            )

            val server = AtlasMcpServer(workspaceRoot = tmpDir, customDbPath = dbPath)

            val toolArgs = buildJsonObject {
                put("action", "verify_remediation")
                put("project", "test_proj")
                put("file_path", "Sample.kt")
                put("before_snippet", "val version: Int = 1")
                put("after_snippet", "val version: Int = 2")
                put("write_site_safe", true)
                put("dry_run", true)
            }

            val res = server.callTool("atlas_audit", toolArgs)
            assertTrue(res["valid"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals("COMPLETED", res["phase"]?.jsonPrimitive?.contentOrNull)
            server.close()
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testInterleavedMismatchedDelimitersRejected() {
        val tmpDir = makeTempDir()
        try {
            val filePath = "$tmpDir/SyntaxTest.kt"
            PlatformFiles.writeText(
                filePath,
                """
                package com.example

                class SyntaxTest {
                    fun calculate(): Int = 42
                }
                """.trimIndent()
            )

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)

            // Interleaved mismatched delimiters: ({)} or ( [ ) ]
            val resInterleaved = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "SyntaxTest.kt",
                    beforeSnippet = "fun calculate(): Int = 42",
                    afterSnippet = "fun calculate(): Int { val x = listOf( { ) } ; return 42 }"
                )
            )
            assertFalse(resInterleaved.valid, "Interleaved mismatched delimiters must be rejected")
            assertEquals("POST_VERIFICATION", resInterleaved.phase)
            assertFalse(resInterleaved.astValid)
            assertTrue(resInterleaved.details.any { it.contains("improperly nested delimiters") })
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testTopLevelConstantsFileAstValidationSucceeds() {
        val tmpDir = makeTempDir()
        try {
            val filePath = "$tmpDir/Constants.kt"
            PlatformFiles.writeText(
                filePath,
                """
                package com.example.config

                val APP_TIMEOUT_MS = 5000L
                """.trimIndent()
            )

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)

            val res = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "Constants.kt",
                    beforeSnippet = "val APP_TIMEOUT_MS = 5000L",
                    afterSnippet = "val APP_TIMEOUT_MS = 10000L",
                    dryRun = false
                )
            )
            assertTrue(res.valid, "File with only top-level properties must pass AST validation")
            assertEquals("COMPLETED", res.phase)
            assertTrue(res.astValid)
            assertTrue(PlatformFiles.readText(filePath).contains("10000L"))
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testMultiComposableFileTargetNodeClearing() {
        val tmpDir = makeTempDir()
        try {
            val filePath = "$tmpDir/ScreenComponents.kt"
            PlatformFiles.writeText(
                filePath,
                """
                package com.example.ui

                import androidx.compose.runtime.Composable

                class ScreenComponents {
                    @Composable
                    fun SearchBar(query: String, modifier: Modifier = Modifier) {
                        Text(query)
                    }

                    @Composable
                    fun Footer(copyright: String, modifier: Modifier = Modifier) {
                        Text(copyright)
                    }
                }
                """.trimIndent()
            )

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)

            // Fix SearchBar by moving modifier to first optional parameter position
            val res = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "ScreenComponents.kt",
                    beforeSnippet = "fun SearchBar(query: String, modifier: Modifier = Modifier)",
                    afterSnippet = "fun SearchBar(modifier: Modifier = Modifier, query: String)",
                    ruleId = "COMPOSE-API-001",
                    targetNodeId = "proj:ScreenComponents.kt#SearchBar",
                    dryRun = true
                )
            )
            assertTrue(res.valid, "Remediating target composable must succeed even if another composable still has the rule. Details: ${res.details}")
            assertEquals("COMPLETED", res.phase)
            assertTrue(res.checksPassed)
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testLiveStoreWriteSiteMutationDetectedAndRejected() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val filePath = "$tmpDir/UserModel.kt"
            PlatformFiles.writeText(
                filePath,
                """
                package com.example.model

                data class UserModel(
                    var title: String
                )
                """.trimIndent()
            )

            // Seed store with an active MUTATES_PROPERTY edge on 'title'
            val modelNode = GraphNode(
                nodeId = "proj:UserModel.kt#UserModel",
                projectId = "proj",
                fileId = "proj:UserModel.kt",
                name = "UserModel",
                qualifiedName = "com.example.model.UserModel",
                kind = "class",
                signature = "data class UserModel",
                startLine = 3,
                endLine = 5,
                moduleId = "model",
                sourceSet = "commonMain",
                semioticLayer = "semantic",
                archLayer = "domain"
            )
            val callerNode = GraphNode(
                nodeId = "proj:Caller.kt#updateUser",
                projectId = "proj",
                fileId = "proj:Caller.kt",
                name = "updateUser",
                qualifiedName = "com.example.Caller.updateUser",
                kind = "function",
                signature = "fun updateUser()",
                startLine = 1,
                endLine = 5,
                moduleId = "caller",
                sourceSet = "commonMain",
                semioticLayer = "pragmatic",
                archLayer = "presentation"
            )
            val titleNode = GraphNode(
                nodeId = "sym:title",
                projectId = "proj",
                fileId = "proj:UserModel.kt",
                name = "title",
                qualifiedName = "title",
                kind = "variable",
                signature = "var title: String",
                startLine = 4,
                endLine = 4,
                moduleId = "model",
                sourceSet = "commonMain",
                semioticLayer = "semantic",
                archLayer = "domain"
            )

            store.upsertNodesAndEdges(
                projectId = "proj",
                nodes = listOf(modelNode, callerNode, titleNode),
                edges = listOf(
                    GraphEdge(
                        edgeId = "edge_mut_title",
                        projectId = "proj",
                        sourceNodeId = "proj:Caller.kt#updateUser",
                        targetNodeId = "sym:title",
                        edgeType = "MUTATES_PROPERTY",
                        provenance = "EXTRACTED_AST",
                        confidence = 1.0,
                        lineNumber = 10
                    )
                )
            )

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir, store = store)

            // Attempt var -> val conversion with writeSiteSafe passed as true
            val res = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "UserModel.kt",
                    beforeSnippet = "var title: String",
                    afterSnippet = "val title: String",
                    ruleId = "COMPOSE-STAB-001",
                    writeSiteSafe = true // Caller thought it was safe, but store knows better!
                )
            )

            assertFalse(res.valid, "Store-level write-site mutations must reject remediation in pre-flight")
            assertEquals("PRE_FLIGHT", res.phase)
            assertFalse(res.preFlightPassed)
            assertTrue(res.message.contains("Write-site mutations exist"))
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testShellTestCommandPreservationAndAutomaticRollback() {
        val tmpDir = makeTempDir()
        try {
            val filePath = "$tmpDir/Config.kt"
            val initialContent = """
                package com.example

                class Config {
                    val port = 8080
                }
            """.trimIndent()
            PlatformFiles.writeText(filePath, initialContent)

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)

            // 1. Failing test command: must fail verification and restore disk even with dryRun=false
            val resFail = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "Config.kt",
                    beforeSnippet = "val port = 8080",
                    afterSnippet = "val port = 9090",
                    dryRun = false,
                    testCommand = "exit 1" // Simulated test failure
                )
            )
            assertFalse(resFail.valid)
            assertEquals("POST_VERIFICATION", resFail.phase)
            assertFalse(resFail.testsPreserved)
            assertEquals(initialContent, PlatformFiles.readText(filePath), "Failing test command must rollback disk changes")

            // 2. Passing test command with dryRun=true: validates and rolls back
            val resPassDry = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "Config.kt",
                    beforeSnippet = "val port = 8080",
                    afterSnippet = "val port = 9090",
                    dryRun = true,
                    testCommand = "exit 0" // Passing test command
                )
            )
            assertTrue(resPassDry.valid)
            assertEquals("COMPLETED", resPassDry.phase)
            assertTrue(resPassDry.testsPreserved)
            assertEquals(initialContent, PlatformFiles.readText(filePath), "Dry run must restore disk changes")

            // 3. Passing test command with dryRun=false: commits to disk
            val resPassApply = verifier.verifyAndApply(
                AutonomousRemediationVerifier.VerificationRequest(
                    projectSlug = "proj",
                    relPath = "Config.kt",
                    beforeSnippet = "val port = 8080",
                    afterSnippet = "val port = 9090",
                    dryRun = false,
                    testCommand = "exit 0"
                )
            )
            assertTrue(resPassApply.valid)
            assertEquals("COMPLETED", resPassApply.phase)
            assertTrue(resPassApply.testsPreserved)
            assertTrue(PlatformFiles.readText(filePath).contains("val port = 9090"))
        } finally {
            cleanDir(tmpDir)
        }
    }
}

