package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.model.ComplexityDoctorReport
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.posix.getpid
import platform.posix.rmdir

class ComplexityDoctorTest {

    private fun makeTempDir(): String {
        val path = "/tmp/atlas_complexity_doc_test_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(path)
        return path
    }

    private fun cleanDir(dir: String) {
        for (f in PlatformFiles.listFilesRecursively(dir)) {
            PlatformFiles.delete(f)
        }
        rmdir(dir)
    }

    private fun makeTempDb(): String {
        val path = "/tmp/atlas_complexity_test_db_${getpid()}_${Random.nextInt(100000, 999999)}.db"
        PlatformFiles.delete(path)
        return path
    }

    @Test
    fun testAuditorDetectsHighCognitiveViolation() {
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val highNode = GraphNode(
                nodeId = "proj:src/Heavy.kt#heavyMethod",
                projectId = "proj",
                fileId = "proj:src/Heavy.kt",
                name = "heavyMethod",
                qualifiedName = "atlas.sample.Heavy.heavyMethod",
                kind = "function",
                startLine = 10,
                endLine = 45,
                inDegree = 3,
                cognitiveComplexity = 18,
                cyclomaticComplexity = 8,
                maxNestingDepth = 4,
                complexityScore = 35.0
            )
            val c1 = GraphNode(nodeId = "proj:src/C.kt#c1", projectId = "proj", fileId = "proj:src/C.kt", name = "c1", qualifiedName = "c1", kind = "function", startLine = 1)
            val c2 = GraphNode(nodeId = "proj:src/C.kt#c2", projectId = "proj", fileId = "proj:src/C.kt", name = "c2", qualifiedName = "c2", kind = "function", startLine = 5)
            val c3 = GraphNode(nodeId = "proj:src/C.kt#c3", projectId = "proj", fileId = "proj:src/C.kt", name = "c3", qualifiedName = "c3", kind = "function", startLine = 10)
            val edges = listOf(
                GraphEdge(edgeId = "e1", projectId = "proj", sourceNodeId = c1.nodeId, targetNodeId = highNode.nodeId, edgeType = "CALLS"),
                GraphEdge(edgeId = "e2", projectId = "proj", sourceNodeId = c2.nodeId, targetNodeId = highNode.nodeId, edgeType = "CALLS"),
                GraphEdge(edgeId = "e3", projectId = "proj", sourceNodeId = c3.nodeId, targetNodeId = highNode.nodeId, edgeType = "CALLS")
            )
            store.upsertNodesAndEdges("proj", listOf(highNode, c1, c2, c3), edges)

            val auditor = ArchitectureAuditor(store)
            val violations = auditor.checkComplexityViolations("proj", maxCognitive = 10, maxCyclomatic = 15)

            assertEquals(1, violations.size)
            val v = violations[0]
            assertEquals("COMPLEXITY-001", v.ruleId)
            assertEquals("HIGH", v.severity)
            assertEquals("proj:src/Heavy.kt#heavyMethod", v.nodeId)
            assertEquals("L10-L45", v.lineSpan)
            assertEquals(18, v.cognitiveComplexity)
            assertEquals(8, v.cyclomaticComplexity)
            // 18 * (1 + ln(4)) = 18 * (1 + 1.38629) = 18 * 2.38629 = 42.953 -> rounded 43.0
            assertTrue(v.riskIndex in 42.8..43.2)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testAuditorDetectsWarningForCognitive11to15() {
        val dbPath = makeTempDb()
        val store = SqliteGraphStore(dbPath)
        try {
            val warnNode = GraphNode(
                nodeId = "proj:src/Medium.kt#mediumMethod",
                projectId = "proj",
                fileId = "proj:src/Medium.kt",
                name = "mediumMethod",
                qualifiedName = "atlas.sample.Medium.mediumMethod",
                kind = "function",
                startLine = 5,
                endLine = 20,
                inDegree = 0,
                cognitiveComplexity = 12,
                cyclomaticComplexity = 6,
                maxNestingDepth = 2,
                complexityScore = 54.0
            )
            store.upsertNodesAndEdges("proj", listOf(warnNode), emptyList())

            val auditor = ArchitectureAuditor(store)
            val violations = auditor.checkComplexityViolations("proj", maxCognitive = 10, maxCyclomatic = 15)

            assertEquals(1, violations.size)
            val v = violations[0]
            assertEquals("WARNING", v.severity)
            assertEquals(12, v.cognitiveComplexity)
            assertEquals(12.0, v.riskIndex)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testDoctorSynthesizesGuardInversionPrescription() {
        val source = """
            package atlas.sample

            class SampleProcessor {
                fun processItems(items: List<String>?, active: Boolean): Int {
                    if (active && items != null) {
                        var total = 0
                        for (item in items) {
                            if (item.isNotEmpty()) {
                                if (item.startsWith("X")) {
                                    total += 1
                                }
                            }
                        }
                        return total
                    }
                    return 0
                }
            }
        """.trimIndent()

        val engine = ComplexityDoctorEngine()
        val prescriptions = engine.analyzeSource("src/SampleProcessor.kt", source)

        assertEquals(1, prescriptions.size)
        val p = prescriptions[0]
        assertEquals("COMPLEXITY-GUARD-INVERT", p.ruleId)
        assertEquals("GUARD_INVERSION", p.fixStrategy)
        assertTrue(p.writeSiteSafe)
        assertTrue(p.cognitiveBefore >= 10, "Expected cognitiveBefore >= 10, but got ${p.cognitiveBefore}")
        assertTrue(p.cognitiveAfter < p.cognitiveBefore, "Expected cognitiveAfter < cognitiveBefore")
        assertTrue(p.reductionPercent >= 25.0, "Expected reductionPercent >= 25.0, but got ${p.reductionPercent}")
        assertTrue(p.afterSnippet.contains("if (!active || items == null) return 0"))
    }

    @Test
    fun testDoctorSynthesizesElvisGuardPrescription() {
        val source = """
            package atlas.sample

            class BatchProcessor {
                fun processBatch(ids: List<String>): List<String> {
                    val results = mutableListOf<String>()
                    for (id in ids) {
                        val item = fetchItem(id)
                        if (item == null) {
                            return emptyList()
                        }
                        results.add(item)
                    }
                    return results
                }

                fun fetchItem(id: String): String? = id
            }
        """.trimIndent()

        val engine = ComplexityDoctorEngine()
        val prescriptions = engine.analyzeSource("src/BatchProcessor.kt", source)

        val elvisPrescription = prescriptions.firstOrNull { it.ruleId == "COMPLEXITY-ELVIS-GUARD" }
        assertNotNull(elvisPrescription, "Expected an ELVIS_GUARD prescription")
        assertEquals("ELVIS_GUARD", elvisPrescription.fixStrategy)
        assertTrue(elvisPrescription.writeSiteSafe)
        assertTrue(elvisPrescription.reductionPercent >= 25.0)
        assertTrue(elvisPrescription.afterSnippet.contains("val item = fetchItem(id) ?: return emptyList()"))
    }

    @Test
    fun testVerifierApprovesPrescriptionWithAtLeast25PercentReduction() {
        val tmpDir = makeTempDir()
        try {
            val relPath = "src/SampleProcessor.kt"
            val fullPath = "$tmpDir/$relPath"
            PlatformFiles.mkdirs("$tmpDir/src")
            val source = """
                package atlas.sample

                class SampleProcessor {
                    fun processItems(items: List<String>?, active: Boolean): Int {
                        if (active && items != null) {
                            var total = 0
                            for (item in items) {
                                if (item.isNotEmpty()) {
                                    if (item.startsWith("X")) {
                                        total += 1
                                    }
                                }
                            }
                            return total
                        }
                        return 0
                    }
                }
            """.trimIndent()
            PlatformFiles.writeText(fullPath, source)

            val engine = ComplexityDoctorEngine(workspaceRoot = tmpDir)
            val prescriptions = engine.analyzeSource(relPath, source)
            assertEquals(1, prescriptions.size)
            val prescription = prescriptions[0]

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)
            val result = verifier.verifyPrescription("test-proj", prescription, dryRun = true)

            assertTrue(result.valid)
            assertEquals("COMPLETED", result.phase)
            assertTrue(result.preFlightPassed)
            assertTrue(result.astValid)
            assertTrue(result.checksPassed)
            assertTrue(result.testsPreserved)
            assertTrue(result.details.any { it.contains("Gate 4 passed") })
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testVerifierRejectsPrescriptionWithInsufficientReduction() {
        val tmpDir = makeTempDir()
        try {
            val relPath = "src/SampleProcessor.kt"
            val fullPath = "$tmpDir/$relPath"
            PlatformFiles.mkdirs("$tmpDir/src")
            val source = """
                package atlas.sample

                class SampleProcessor {
                    fun compute(): Int = 42
                }
            """.trimIndent()
            PlatformFiles.writeText(fullPath, source)

            val prescription = ComplexityDoctorReport.Prescription(
                rank = 1,
                priorityScore = 10.0,
                ruleId = "COMPLEXITY-GUARD-INVERT",
                fixStrategy = "GUARD_INVERSION",
                writeSiteSafe = true,
                targetNodeId = "compute",
                filePath = relPath,
                line = 4,
                cognitiveBefore = 10,
                cognitiveAfter = 9,
                reductionPercent = 10.0,
                rationale = "Minor change",
                beforeSnippet = "fun compute(): Int = 42",
                afterSnippet = "fun compute(): Int = 40 + 2"
            )

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)
            val result = verifier.verifyPrescription("test-proj", prescription, dryRun = true)

            assertFalse(result.valid)
            assertEquals("POST_VERIFICATION", result.phase)
            assertFalse(result.checksPassed)
            assertTrue(result.details.any { it.contains("Gate 4 failed") })
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testVerifierExecutesTestCommandAndRollsBackOnFailure() {
        val tmpDir = makeTempDir()
        try {
            val relPath = "src/SampleProcessor.kt"
            val fullPath = "$tmpDir/$relPath"
            PlatformFiles.mkdirs("$tmpDir/src")
            val source = """
                package atlas.sample

                class SampleProcessor {
                    fun processItems(items: List<String>?, active: Boolean): Int {
                        if (active && items != null) {
                            var total = 0
                            for (item in items) {
                                if (item.isNotEmpty()) {
                                    if (item.startsWith("X")) {
                                        total += 1
                                    }
                                }
                            }
                            return total
                        }
                        return 0
                    }
                }
            """.trimIndent()
            PlatformFiles.writeText(fullPath, source)

            val engine = ComplexityDoctorEngine(workspaceRoot = tmpDir)
            val prescriptions = engine.analyzeSource(relPath, source)
            assertEquals(1, prescriptions.size)
            val prescription = prescriptions[0]

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)
            // Test command fails (exit code != 0)
            val result = verifier.verifyPrescription(
                projectSlug = "test-proj",
                prescription = prescription,
                dryRun = false,
                testCommand = "sh -c 'exit 1'"
            )

            assertFalse(result.valid)
            assertEquals("POST_VERIFICATION", result.phase)
            assertFalse(result.testsPreserved)

            // Disk file must be rolled back atomically to original
            val currentOnDisk = PlatformFiles.readText(fullPath)
            assertEquals(source, currentOnDisk)
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testVerifierAppliesRemediationWhenTestCommandPasses() {
        val tmpDir = makeTempDir()
        try {
            val relPath = "src/SampleProcessor.kt"
            val fullPath = "$tmpDir/$relPath"
            PlatformFiles.mkdirs("$tmpDir/src")
            val source = """
                package atlas.sample

                class SampleProcessor {
                    fun processItems(items: List<String>?, active: Boolean): Int {
                        if (active && items != null) {
                            var total = 0
                            for (item in items) {
                                if (item.isNotEmpty()) {
                                    if (item.startsWith("X")) {
                                        total += 1
                                    }
                                }
                            }
                            return total
                        }
                        return 0
                    }
                }
            """.trimIndent()
            PlatformFiles.writeText(fullPath, source)

            val engine = ComplexityDoctorEngine(workspaceRoot = tmpDir)
            val prescriptions = engine.analyzeSource(relPath, source)
            assertEquals(1, prescriptions.size)
            val prescription = prescriptions[0]

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)
            val result = verifier.verifyPrescription(
                projectSlug = "test-proj",
                prescription = prescription,
                dryRun = false,
                testCommand = "echo 'Remediation passed test suite'"
            )

            assertTrue(result.valid)
            assertEquals("COMPLETED", result.phase)
            assertTrue(result.testsPreserved)

            // Disk file must have the applied remediation
            val currentOnDisk = PlatformFiles.readText(fullPath)
            assertTrue(currentOnDisk.contains("if (!active || items == null) return 0"))
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testRemediationWithOverloadedFunctions() {
        val tmpDir = makeTempDir()
        try {
            val relPath = "src/OverloadedService.kt"
            val fullPath = "$tmpDir/$relPath"
            PlatformFiles.mkdirs("$tmpDir/src")
            val source = """
                package atlas.sample

                class OverloadedService {
                    fun process(id: String): Int = 42

                    fun process(id: String, items: List<String>?, active: Boolean): Int {
                        if (active && items != null) {
                            var total = 0
                            for (item in items) {
                                if (item.isNotEmpty()) {
                                    if (item.startsWith("X")) {
                                        total += 1
                                    }
                                }
                            }
                            return total
                        }
                        return 0
                    }
                }
            """.trimIndent()
            PlatformFiles.writeText(fullPath, source)

            val engine = ComplexityDoctorEngine(workspaceRoot = tmpDir)
            val prescriptions = engine.analyzeSource(relPath, source)
            assertEquals(1, prescriptions.size)
            val prescription = prescriptions[0]
            assertEquals(7, prescription.line)

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)
            val result = verifier.verifyPrescription("test-proj", prescription, dryRun = true)

            assertTrue(result.valid)
            assertEquals("COMPLETED", result.phase)
            assertTrue(result.checksPassed)
            assertTrue(result.details.any { it.contains("Gate 4 passed") })
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testGuardInversionWithParenthesizedCondition() {
        val tmpDir = makeTempDir()
        try {
            val relPath = "src/ParenFilter.kt"
            val fullPath = "$tmpDir/$relPath"
            PlatformFiles.mkdirs("$tmpDir/src")
            val source = """
                package atlas.sample

                class ParenFilter {
                    fun filter(flag: Boolean, items: List<String>?): Int {
                        if ((flag && items != null)) {
                            var count = 0
                            for (x in items) {
                                if (x.length > 2) {
                                    if (x.startsWith("A")) {
                                        count++
                                    }
                                }
                            }
                            return count
                        }
                        return 0
                    }
                }
            """.trimIndent()
            PlatformFiles.writeText(fullPath, source)

            val engine = ComplexityDoctorEngine(workspaceRoot = tmpDir)
            val prescriptions = engine.analyzeSource(relPath, source)
            assertEquals(1, prescriptions.size)
            val p = prescriptions[0]
            assertTrue(p.afterSnippet.contains("if (!flag || items == null) return 0"))

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)
            val result = verifier.verifyPrescription("test-proj", p, dryRun = true)
            assertTrue(result.valid)
            assertEquals("COMPLETED", result.phase)
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testDoctorSynthesizesElvisGuardOnHighCognitiveFunctionWithLowPercentage() {
        val tmpDir = makeTempDir()
        try {
            val relPath = "src/HighBatchProcessor.kt"
            val fullPath = "$tmpDir/$relPath"
            PlatformFiles.mkdirs("$tmpDir/src")
            val source = """
                package atlas.sample

                class HighBatchProcessor {
                    fun processBatch(ids: List<String>): List<String> {
                        val results = mutableListOf<String>()
                        for (id in ids) {
                            val item = fetchItem(id)
                            if (item == null) {
                                return emptyList()
                            }
                            if (id.length > 5) {
                                if (id.startsWith("A")) {
                                    if (id.endsWith("Z")) {
                                        for (ch in id) {
                                            if (ch == '1') {
                                                results.add(item)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        return results
                    }

                    fun fetchItem(id: String): String? = id
                }
            """.trimIndent()
            PlatformFiles.writeText(fullPath, source)

            val engine = ComplexityDoctorEngine(workspaceRoot = tmpDir)
            val prescriptions = engine.analyzeSource(relPath, source)
            val elvisPrescription = prescriptions.firstOrNull { it.ruleId == "COMPLEXITY-ELVIS-GUARD" }
            assertNotNull(elvisPrescription, "Expected ELVIS_GUARD prescription on high complexity function")
            assertTrue(elvisPrescription.cognitiveBefore >= 10, "Expected cognitiveBefore >= 10, got ${elvisPrescription.cognitiveBefore}")
            assertEquals(1, elvisPrescription.cognitiveBefore - elvisPrescription.cognitiveAfter, "Expected reduction of 1 point")
            assertTrue(elvisPrescription.reductionPercent < 25.0, "Expected reductionPercent < 25.0%, got ${elvisPrescription.reductionPercent}")
            assertTrue(elvisPrescription.afterSnippet.contains("val item = fetchItem(id) ?: return emptyList()"))

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)
            val result = verifier.verifyPrescription("test-proj", elvisPrescription, dryRun = true)
            assertTrue(result.valid, "Verifier must approve Elvis prescription with delta >= 1")
            assertTrue(result.checksPassed, "Gate 4 must pass with delta >= 1")
            assertTrue(result.details.any { it.contains("Gate 4 passed") })
        } finally {
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testDoctorSynthesizesGuardInversionWithPrecedingVariableDeclarations() {
        val tmpDir = makeTempDir()
        try {
            val relPath = "src/PrecedingVarsProcessor.kt"
            val fullPath = "$tmpDir/$relPath"
            PlatformFiles.mkdirs("$tmpDir/src")
            val source = """
                package atlas.sample

                class PrecedingVarsProcessor {
                    fun process(flag: Boolean, items: List<String>?): Int {
                        val prefix = "item_"
                        val limit = 100
                        if (flag && items != null) {
                            var count = 0
                            for (x in items) {
                                if (x.startsWith(prefix)) {
                                    if (x.length > limit) {
                                        count++
                                    }
                                }
                            }
                            return count
                        }
                        return 0
                    }
                }
            """.trimIndent()
            PlatformFiles.writeText(fullPath, source)

            val engine = ComplexityDoctorEngine(workspaceRoot = tmpDir)
            val prescriptions = engine.analyzeSource(relPath, source)
            val guardPrescription = prescriptions.firstOrNull { it.ruleId == "COMPLEXITY-GUARD-INVERT" }
            assertNotNull(guardPrescription, "Expected GUARD_INVERT prescription despite preceding val declarations")
            assertTrue(guardPrescription.afterSnippet.contains("if (!flag || items == null) return 0"))

            val verifier = AutonomousRemediationVerifier(workspaceRoot = tmpDir)
            val result = verifier.verifyPrescription("test-proj", guardPrescription, dryRun = true)
            assertTrue(result.valid)
            assertTrue(result.checksPassed)
            assertTrue(result.details.any { it.contains("Gate 4 passed") })
        } finally {
            cleanDir(tmpDir)
        }
    }
}
