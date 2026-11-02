package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.extract.ComposeStabilityExtractor
import atlas.extract.PolyglotAstExtractor
import atlas.extract.SemioticLayerClassifier
import atlas.model.ComplexityDoctorReport
import atlas.model.ComposeDoctorReport
import atlas.model.GraphNode
import atlas.model.RemediationVerificationResult
import atlas.util.PlatformFiles
import kotlin.math.roundToInt
import platform.posix.system

/**
 * Autonomous remediation verification engine enforcing pre-flight validation,
 * staged transformation, and post-remediation verification (AST validity, rule resolution,
 * BOOST-003 single declaration invariant, graph-level write-site safety, and test suite preservation).
 */
class AutonomousRemediationVerifier(
    private val workspaceRoot: String = ".",
    private val store: SqliteGraphStore? = null
) {
    private val composeExtractor = ComposeStabilityExtractor()

    fun verifyPrescription(
        projectSlug: String,
        prescription: ComposeDoctorReport.Prescription,
        dryRun: Boolean = true,
        testCommand: String? = null,
        testVerifier: ((filePath: String, modifiedContent: String) -> Boolean)? = null
    ): RemediationVerificationResult {
        return verifyAndApply(
            VerificationRequest(
                projectSlug = projectSlug,
                relPath = prescription.filePath,
                beforeSnippet = prescription.beforeSnippet,
                afterSnippet = prescription.afterSnippet,
                ruleId = prescription.ruleId,
                targetNodeId = prescription.targetNodeId,
                writeSiteSafe = prescription.writeSiteSafe,
                dryRun = dryRun,
                testCommand = testCommand,
                testVerifier = testVerifier
            )
        )
    }

    fun verifyPrescription(
        projectSlug: String,
        prescription: ComplexityDoctorReport.Prescription,
        dryRun: Boolean = true,
        testCommand: String? = null,
        testVerifier: ((filePath: String, modifiedContent: String) -> Boolean)? = null
    ): RemediationVerificationResult {
        return verifyAndApply(
            VerificationRequest(
                projectSlug = projectSlug,
                relPath = prescription.filePath,
                beforeSnippet = prescription.beforeSnippet,
                afterSnippet = prescription.afterSnippet,
                ruleId = prescription.ruleId,
                targetNodeId = prescription.targetNodeId,
                writeSiteSafe = prescription.writeSiteSafe,
                dryRun = dryRun,
                testCommand = testCommand,
                testVerifier = testVerifier,
                expectedReductionPercent = prescription.reductionPercent
            )
        )
    }

    private enum class PassedStage {
        None,
        PreFlight,
        AstValid,
        ChecksPassed,
        TestsPreserved
    }

    private fun failResult(
        phase: String,
        req: VerificationRequest,
        message: String,
        details: List<String>,
        passedStage: PassedStage = PassedStage.None
    ): RemediationVerificationResult {
        return RemediationVerificationResult(
            valid = false,
            phase = phase,
            ruleId = req.ruleId,
            filePath = req.relPath,
            preFlightPassed = passedStage >= PassedStage.PreFlight,
            astValid = passedStage >= PassedStage.AstValid,
            checksPassed = passedStage >= PassedStage.ChecksPassed,
            testsPreserved = passedStage >= PassedStage.TestsPreserved,
            message = message,
            details = details
        )
    }

    private fun checkGraphStoreSafety(
        req: VerificationRequest,
        details: MutableList<String>
    ): RemediationVerificationResult? {
        if (store == null) return null
        val varPropMatch = Regex("""\bvar\s+([A-Za-z0-9_]+)\s*:""").find(req.beforeSnippet) ?: return null
        val propName = varPropMatch.groupValues[1]
        val className = Regex("""\b(?:data\s+)?class\s+([A-Za-z0-9_]+)""").find(req.beforeSnippet)?.groupValues?.get(1)
        val mutations = store.countPropertyMutations(req.projectSlug, propName, className)
        if (mutations > 0) {
            val msg = "Pre-flight failed: Write-site mutations exist in call graph ($mutations active mutation(s) for '$propName')."
            details.add("Graph store check failed: Property '$propName' has $mutations MUTATES_PROPERTY edge(s). Refactor mutations before converting 'var' -> 'val'.")
            return failResult("PRE_FLIGHT", req, msg, details)
        }
        return null
    }

    private fun validatePreFlight(
        req: VerificationRequest,
        fullPath: String,
        details: MutableList<String>
    ): Pair<String, RemediationVerificationResult?> {
        if (!req.writeSiteSafe) {
            val msg = "Pre-flight failed: Write-site mutations exist in call graph for ${req.ruleId} in ${req.relPath}."
            details.add("Write-site safety check failed (writeSiteSafe=false). Autonomous modification rejected.")
            return Pair("", failResult("PRE_FLIGHT", req, msg, details))
        }

        val storeErr = checkGraphStoreSafety(req, details)
        if (storeErr != null) return Pair("", storeErr)

        if (!PlatformFiles.exists(fullPath)) {
            val msg = "Pre-flight failed: File does not exist at ${req.relPath}."
            details.add("File not found on disk: $fullPath")
            return Pair("", failResult("PRE_FLIGHT", req, msg, details))
        }

        val originalContent = try {
            PlatformFiles.readText(fullPath)
        } catch (e: Exception) {
            val msg = "Pre-flight failed: Cannot read ${req.relPath}: ${e.message}"
            details.add(msg)
            return Pair("", failResult("PRE_FLIGHT", req, msg, details))
        }

        if (req.beforeSnippet.isBlank()) {
            val msg = "Pre-flight failed: beforeSnippet is empty."
            details.add("Empty beforeSnippet provided for remediation of ${req.relPath}.")
            return Pair("", failResult("PRE_FLIGHT", req, msg, details))
        }

        if (!originalContent.contains(req.beforeSnippet)) {
            val msg = "Pre-flight failed: Target code snippet not found in ${req.relPath}."
            details.add("Expected beforeSnippet not present in source text.")
            return Pair("", failResult("PRE_FLIGHT", req, msg, details))
        }

        val occurrences = countOccurrences(originalContent, req.beforeSnippet)
        if (occurrences > 1) {
            val msg = "Pre-flight failed: Ambiguous replacement; target snippet occurs $occurrences times in ${req.relPath}."
            details.add("Target snippet is ambiguous ($occurrences occurrences).")
            return Pair("", failResult("PRE_FLIGHT", req, msg, details))
        }

        details.add("Pre-flight validation passed: File exists, writeSiteSafe=true, unique beforeSnippet located.")
        return Pair(originalContent, null)
    }

    private fun stageReplacement(
        originalContent: String,
        req: VerificationRequest,
        details: MutableList<String>
    ): Pair<String, RemediationVerificationResult?> {
        val modifiedContent = originalContent.replace(req.beforeSnippet, req.afterSnippet)
        if (modifiedContent == originalContent) {
            val msg = "Application failed: Replacement produced identical content."
            details.add("No changes resulted from replacing beforeSnippet with afterSnippet.")
            return Pair("", failResult("APPLICATION", req, msg, details, PassedStage.PreFlight))
        }
        return Pair(modifiedContent, null)
    }

    private data class AstExtractionResult(
        val astValid: Boolean,
        val topDeclCount: Int,
        val origNodes: List<GraphNode>,
        val nodes: List<GraphNode>,
        val lang: String
    )

    private fun validateAstIntegrity(
        projectSlug: String,
        relPath: String,
        originalContent: String,
        modifiedContent: String,
        details: MutableList<String>
    ): AstExtractionResult {
        val ext = if (relPath.contains('.')) "." + relPath.substringAfterLast('.') else ""
        val lang = PolyglotAstExtractor.supportedLanguage(ext)
        if (lang.isEmpty()) {
            return AstExtractionResult(true, 1, emptyList(), emptyList(), "")
        }

        val masked = PolyglotAstExtractor.maskCommentsAndStrings(modifiedContent, lang)
        if (!hasBalancedDelimiters(masked)) {
            details.add("Syntax check failed: Unbalanced or improperly nested delimiters ({}, (), or []) in modified content.")
            return AstExtractionResult(false, 1, emptyList(), emptyList(), lang)
        }

        return try {
            val extractedOrig = PolyglotAstExtractor.extractFile(projectSlug, relPath, originalContent)
            val extractedMod = PolyglotAstExtractor.extractFile(projectSlug, relPath, modifiedContent)
            val origNodes = extractedOrig.first
            val nodes = extractedMod.first
            val topDeclCount = extractedMod.third
            val valid = !(origNodes.isNotEmpty() && nodes.isEmpty() && modifiedContent.trim().isNotEmpty())
            if (!valid) {
                details.add("AST extraction produced 0 nodes for modified $lang source file (previously had ${origNodes.size} node(s)).")
            } else {
                details.add("Polyglot AST extraction succeeded (${nodes.size} nodes, topLevelDeclCount=$topDeclCount).")
            }
            AstExtractionResult(valid, topDeclCount, origNodes, nodes, lang)
        } catch (e: Exception) {
            details.add("Polyglot AST extraction crashed: ${e.message}")
            AstExtractionResult(false, 1, emptyList(), emptyList(), lang)
        }
    }

    private fun checkSingleDeclaration(relPath: String, topDeclCount: Int, details: MutableList<String>): Boolean {
        val (semiotic, _, _) = SemioticLayerClassifier.classify(relPath)
        val isTestFile = semiotic == "test" || relPath.contains("Test") || relPath.contains("test")
        if (!isTestFile && topDeclCount > 1 && !relPath.endsWith("__init__.py")) {
            details.add("BOOST-003 violation: Modified file declares $topDeclCount top-level symbols (max 1 allowed).")
            return false
        }
        return true
    }

    private fun checkComposeStabilityRemediation(
        req: VerificationRequest,
        originalContent: String,
        modifiedContent: String,
        details: MutableList<String>
    ): Boolean {
        val targetRuleBit = ruleBitForId(req.ruleId)
        if (targetRuleBit == 0) return true
        return try {
            val origBundle = try {
                composeExtractor.extractKotlinFile(projectSlug = req.projectSlug, relPath = req.relPath, sourceText = originalContent)
            } catch (_: Exception) { null }
            val bundle = composeExtractor.extractKotlinFile(projectSlug = req.projectSlug, relPath = req.relPath, sourceText = modifiedContent)
            val origViolations = origBundle?.nodes?.filter { (it.violationMask and targetRuleBit) != 0 } ?: emptyList()
            val newViolations = bundle.nodes.filter { (it.violationMask and targetRuleBit) != 0 }
            val targetNodeId = req.targetNodeId
            val cleared = if (!targetNodeId.isNullOrBlank()) {
                newViolations.none {
                    it.nodeId == targetNodeId || it.fqName == targetNodeId || it.nodeId.endsWith("#$targetNodeId")
                }
            } else {
                newViolations.isEmpty() || newViolations.size < origViolations.size
            }
            if (!cleared) {
                details.add("Target rule ${req.ruleId} not cleared: ${newViolations.size} violation(s) remain post-remediation.")
                false
            } else {
                details.add("Target rule ${req.ruleId} verified cleared post-remediation.")
                true
            }
        } catch (e: Exception) {
            details.add("Compose stability validation crashed: ${e.message}")
            false
        }
    }

    private fun nodeMatchesTarget(node: GraphNode, targetNodeId: String, sym: String, simpleSym: String): Boolean {
        if (node.nodeId == targetNodeId || node.qualifiedName == targetNodeId || node.name == targetNodeId) return true
        if (sym.isNotEmpty() && (node.nodeId.substringAfter('#') == sym || node.name == sym)) return true
        if (simpleSym.isNotEmpty() && node.name == simpleSym) return true
        return false
    }

    private fun findTargetGraphNode(
        candidateNodes: List<GraphNode>,
        targetNodeId: String?,
        sym: String,
        simpleSym: String,
        referenceNode: GraphNode? = null
    ): GraphNode? {
        if (targetNodeId.isNullOrBlank()) {
            return candidateNodes.firstOrNull { it.kind in setOf("function", "method", "composable") }
        }
        val matches = candidateNodes.filter { nodeMatchesTarget(it, targetNodeId, sym, simpleSym) }
        if (referenceNode != null && matches.size > 1) {
            return matches.minByOrNull { kotlin.math.abs(it.startLine - referenceNode.startLine) }
        }
        return matches.firstOrNull()
    }

    private fun evaluateReduction(
        origNode: GraphNode?,
        newNode: GraphNode?,
        expectedReductionPercent: Double?
    ): Triple<Boolean, Double, Int> {
        if (origNode != null && newNode != null && origNode.cognitiveComplexity > 0) {
            val diff = origNode.cognitiveComplexity - newNode.cognitiveComplexity
            val pct = (diff.toDouble() / origNode.cognitiveComplexity.toDouble()) * 100.0
            val rounded = (pct * 10.0).roundToInt() / 10.0
            return Triple(diff >= 1, rounded, diff)
        }
        val rounded = ((expectedReductionPercent ?: 0.0) * 10.0).roundToInt() / 10.0
        return Triple(rounded >= 25.0, rounded, 0)
    }

    private fun checkComplexityReduction(
        req: VerificationRequest,
        astResult: AstExtractionResult,
        details: MutableList<String>
    ): Boolean {
        if (!req.ruleId.startsWith("COMPLEXITY") && req.expectedReductionPercent == null) return true
        val targetNodeId = req.targetNodeId
        val sym = if (!targetNodeId.isNullOrBlank() && targetNodeId.contains('#')) targetNodeId.substringAfter('#') else (targetNodeId ?: "")
        val simpleSym = sym.substringAfterLast('.')

        val origTargetNode = findTargetGraphNode(astResult.origNodes, targetNodeId, sym, simpleSym)
        val newTargetNode = findTargetGraphNode(astResult.nodes, targetNodeId, sym, simpleSym, origTargetNode)

        val (floorSatisfied, roundedReduction, diff) = evaluateReduction(origTargetNode, newTargetNode, req.expectedReductionPercent)
        if (!floorSatisfied) {
            details.add("Gate 4 failed: Cognitive complexity reduction ($roundedReduction%) is below the required threshold.")
            return false
        }
        details.add("Gate 4 passed: Cognitive complexity reduction verified ($roundedReduction% >= threshold, delta=$diff).")
        return true
    }

    private fun executeTestCommandWithRollback(
        fullPath: String,
        originalContent: String,
        modifiedContent: String,
        req: VerificationRequest,
        details: MutableList<String>
    ): Boolean {
        val testCommand = req.testCommand ?: return true
        val staged = try {
            PlatformFiles.writeText(fullPath, modifiedContent)
        } catch (_: Exception) { false }

        if (!staged) {
            details.add("Failed to stage modified content to disk for test execution: $fullPath")
            return false
        }

        var passed = false
        try {
            val escapedRoot = "'" + workspaceRoot.replace("'", "'\\''") + "'"
            val cmd = "cd $escapedRoot && $testCommand"
            val rawStatus = system(cmd)
            val exitCode = if (rawStatus == -1) -1 else (rawStatus shr 8) and 0xff
            if (exitCode != 0) {
                details.add("Test command '$testCommand' failed (exit code $exitCode).")
            } else {
                details.add("Test command '$testCommand' passed (exit code 0).")
                passed = true
            }
        } catch (e: Exception) {
            details.add("Test command '$testCommand' crashed: ${e.message}")
        } finally {
            if (!passed || req.dryRun) {
                PlatformFiles.writeText(fullPath, originalContent)
            }
        }
        return passed
    }

    private fun verifyTestSuitePreservation(
        fullPath: String,
        originalContent: String,
        modifiedContent: String,
        req: VerificationRequest,
        details: MutableList<String>
    ): Boolean {
        val verifierHook = req.testVerifier
        if (verifierHook != null) {
            try {
                if (!verifierHook(req.relPath, modifiedContent)) {
                    details.add("Test suite preservation hook returned false.")
                    return false
                }
                details.add("Test suite preservation verified via hook.")
            } catch (e: Exception) {
                details.add("Test suite preservation check failed: ${e.message}")
                return false
            }
        }

        if (!req.testCommand.isNullOrBlank()) {
            return executeTestCommandWithRollback(fullPath, originalContent, modifiedContent, req, details)
        }
        return true
    }

    private fun formatUnifiedDiff(relPath: String, beforeSnippet: String, afterSnippet: String): String {
        val diffLines = mutableListOf<String>()
        diffLines.add("--- a/$relPath")
        diffLines.add("+++ b/$relPath")
        diffLines.add("@@ -1 +1 @@")
        for (line in beforeSnippet.lines()) diffLines.add("-$line")
        for (line in afterSnippet.lines()) diffLines.add("+$line")
        return diffLines.joinToString("\n")
    }

    private fun commitRemediation(
        fullPath: String,
        modifiedContent: String,
        req: VerificationRequest,
        details: MutableList<String>
    ): String? {
        if (!req.dryRun) {
            if (req.testCommand.isNullOrBlank()) {
                try {
                    PlatformFiles.writeText(fullPath, modifiedContent)
                    details.add("Remediation safely written to disk at ${req.relPath}.")
                } catch (e: Exception) {
                    val msg = "Application failed: Write error writing to $fullPath: ${e.message}"
                    details.add(msg)
                    return msg
                }
            } else {
                details.add("Remediation safely verified and committed to disk at ${req.relPath}.")
            }
        } else {
            details.add("Dry run mode: changes validated and verified, disk untouched.")
        }
        return null
    }

    private fun verifyRulesAndInvariants(
        req: VerificationRequest,
        originalContent: String,
        modifiedContent: String,
        astResult: AstExtractionResult,
        details: MutableList<String>
    ): Boolean {
        if (!checkSingleDeclaration(req.relPath, astResult.topDeclCount, details)) return false
        if (astResult.lang == "kotlin") {
            if (!checkComposeStabilityRemediation(req, originalContent, modifiedContent, details)) {
                return false
            }
        }
        return checkComplexityReduction(req, astResult, details)
    }

    data class VerificationRequest(
        val projectSlug: String,
        val relPath: String,
        val beforeSnippet: String,
        val afterSnippet: String,
        val ruleId: String = "GENERIC",
        val targetNodeId: String? = null,
        val writeSiteSafe: Boolean = true,
        val dryRun: Boolean = true,
        val testCommand: String? = null,
        val testVerifier: ((filePath: String, modifiedContent: String) -> Boolean)? = null,
        val expectedReductionPercent: Double? = null
    )

    fun verifyAndApply(req: VerificationRequest): RemediationVerificationResult {
        val root = PlatformFiles.canonicalPath(workspaceRoot).trimEnd('/')
        val fullPath = if (req.relPath.startsWith("/")) req.relPath else "$root/${req.relPath}"
        val details = mutableListOf<String>()

        val (originalContent, preFlightErr) = validatePreFlight(req, fullPath, details)
        if (preFlightErr != null) return preFlightErr

        val (modifiedContent, stageErr) = stageReplacement(originalContent, req, details)
        if (stageErr != null) return stageErr

        val astResult = validateAstIntegrity(req.projectSlug, req.relPath, originalContent, modifiedContent, details)
        if (!astResult.astValid) {
            val msg = "Post-remediation verification failed: Modified source produces an invalid AST."
            return failResult("POST_VERIFICATION", req, msg, details, PassedStage.PreFlight)
        }

        val checksPassed = verifyRulesAndInvariants(req, originalContent, modifiedContent, astResult, details)
        if (!checksPassed) {
            val msg = "Post-remediation verification failed: Checks did not pass post-remediation."
            return failResult("POST_VERIFICATION", req, msg, details, PassedStage.AstValid)
        }

        val testsPreserved = verifyTestSuitePreservation(fullPath, originalContent, modifiedContent, req, details)
        if (!testsPreserved) {
            val msg = "Post-remediation verification failed: Test suite preservation check failed."
            return failResult("POST_VERIFICATION", req, msg, details, PassedStage.ChecksPassed)
        }

        val writeErr = commitRemediation(fullPath, modifiedContent, req, details)
        if (writeErr != null) {
            return failResult("APPLICATION", req, writeErr, details, PassedStage.TestsPreserved)
        }

        val diffSnippet = formatUnifiedDiff(req.relPath, req.beforeSnippet, req.afterSnippet)
        val successMsg = if (req.dryRun) "Remediation verified successfully (dry-run)." else "Remediation verified and applied successfully."
        return RemediationVerificationResult(
            valid = true,
            phase = "COMPLETED",
            ruleId = req.ruleId,
            filePath = req.relPath,
            preFlightPassed = true,
            astValid = true,
            checksPassed = true,
            testsPreserved = true,
            message = successMsg,
            details = details,
            verifiedDiff = diffSnippet
        )
    }

    companion object {
        fun hasBalancedDelimiters(maskedText: String): Boolean {
            val stack = mutableListOf<Char>()
            for (c in maskedText) {
                when (c) {
                    '{', '(', '[' -> stack.add(c)
                    '}', ')', ']' -> {
                        val expected = when (c) {
                            '}' -> '{'
                            ')' -> '('
                            else -> '['
                        }
                        if (stack.isEmpty() || stack.removeAt(stack.size - 1) != expected) return false
                    }
                }
            }
            return stack.isEmpty()
        }
    }

    private fun countOccurrences(source: String, target: String): Int {
        if (target.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = source.indexOf(target, idx)
            if (found < 0) break
            count++
            idx = found + target.length
        }
        return count
    }

    private fun ruleBitForId(ruleId: String): Int {
        return when (ruleId) {
            "COMPOSE-STAB-001" -> ComposeStabilityExtractor.BIT_STAB_001
            "COMPOSE-STAB-002" -> ComposeStabilityExtractor.BIT_STAB_002
            "COMPOSE-STAB-003" -> ComposeStabilityExtractor.BIT_STAB_003
            "COMPOSE-STATE-001" -> ComposeStabilityExtractor.BIT_STATE_001
            "COMPOSE-STATE-002" -> ComposeStabilityExtractor.BIT_STATE_002
            "COMPOSE-STATE-003" -> ComposeStabilityExtractor.BIT_STATE_003
            "COMPOSE-API-001" -> ComposeStabilityExtractor.BIT_API_001
            "COMPOSE-API-002" -> ComposeStabilityExtractor.BIT_API_002
            "COMPOSE-PERF-001" -> ComposeStabilityExtractor.BIT_PERF_001
            "COMPOSE-PERF-002" -> ComposeStabilityExtractor.BIT_PERF_002
            "COMPOSE-I18N-001" -> ComposeStabilityExtractor.BIT_I18N_001
            "COMPOSE-ARCH-001" -> ComposeStabilityExtractor.BIT_ARCH_001
            else -> 0
        }
    }
}
