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
    }

    fun verifyPrescription(
        projectSlug: String,
        prescription: ComplexityDoctorReport.Prescription,
        dryRun: Boolean = true,
        testCommand: String? = null,
        testVerifier: ((filePath: String, modifiedContent: String) -> Boolean)? = null
    ): RemediationVerificationResult {
        return verifyAndApply(
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
    }

    fun verifyAndApply(
        projectSlug: String,
        relPath: String,
        beforeSnippet: String,
        afterSnippet: String,
        ruleId: String = "GENERIC",
        targetNodeId: String? = null,
        writeSiteSafe: Boolean = true,
        dryRun: Boolean = true,
        testCommand: String? = null,
        testVerifier: ((filePath: String, modifiedContent: String) -> Boolean)? = null,
        expectedReductionPercent: Double? = null
    ): RemediationVerificationResult {
        val root = PlatformFiles.canonicalPath(workspaceRoot).trimEnd('/')
        val fullPath = if (relPath.startsWith("/")) relPath else "$root/$relPath"
        val details = mutableListOf<String>()

        // 1. PRE-FLIGHT VALIDATION
        if (!writeSiteSafe) {
            val msg = "Pre-flight failed: Write-site mutations exist in call graph for $ruleId in $relPath."
            details.add("Write-site safety check failed (writeSiteSafe=false). Autonomous modification rejected.")
            return RemediationVerificationResult(
                valid = false,
                phase = "PRE_FLIGHT",
                ruleId = ruleId,
                filePath = relPath,
                preFlightPassed = false,
                astValid = false,
                checksPassed = false,
                testsPreserved = false,
                message = msg,
                details = details
            )
        }

        // Live graph store write-site safety verification (independent guard against unverified flags)
        if (store != null) {
            val varPropMatch = Regex("""\bvar\s+([A-Za-z0-9_]+)\s*:""").find(beforeSnippet)
            if (varPropMatch != null) {
                val propName = varPropMatch.groupValues[1]
                val mutations = store.countPropertyMutations(projectSlug, propName)
                if (mutations > 0) {
                    val msg = "Pre-flight failed: Write-site mutations exist in call graph ($mutations active mutation(s) for '$propName')."
                    details.add("Graph store check failed: Property '$propName' has $mutations MUTATES_PROPERTY edge(s). Refactor mutations before converting 'var' -> 'val'.")
                    return RemediationVerificationResult(
                        valid = false,
                        phase = "PRE_FLIGHT",
                        ruleId = ruleId,
                        filePath = relPath,
                        preFlightPassed = false,
                        astValid = false,
                        checksPassed = false,
                        testsPreserved = false,
                        message = msg,
                        details = details
                    )
                }
            }
        }

        if (!PlatformFiles.exists(fullPath)) {
            val msg = "Pre-flight failed: File does not exist at $relPath."
            details.add("File not found on disk: $fullPath")
            return RemediationVerificationResult(
                valid = false,
                phase = "PRE_FLIGHT",
                ruleId = ruleId,
                filePath = relPath,
                preFlightPassed = false,
                astValid = false,
                checksPassed = false,
                testsPreserved = false,
                message = msg,
                details = details
            )
        }

        val originalContent = try {
            PlatformFiles.readText(fullPath)
        } catch (e: Exception) {
            val msg = "Pre-flight failed: Cannot read $relPath: ${e.message}"
            details.add(msg)
            return RemediationVerificationResult(
                valid = false,
                phase = "PRE_FLIGHT",
                ruleId = ruleId,
                filePath = relPath,
                preFlightPassed = false,
                astValid = false,
                checksPassed = false,
                testsPreserved = false,
                message = msg,
                details = details
            )
        }

        if (beforeSnippet.isBlank()) {
            val msg = "Pre-flight failed: beforeSnippet is empty."
            details.add("Empty beforeSnippet provided for remediation of $relPath.")
            return RemediationVerificationResult(
                valid = false,
                phase = "PRE_FLIGHT",
                ruleId = ruleId,
                filePath = relPath,
                preFlightPassed = false,
                astValid = false,
                checksPassed = false,
                testsPreserved = false,
                message = msg,
                details = details
            )
        }

        if (!originalContent.contains(beforeSnippet)) {
            val msg = "Pre-flight failed: Target code snippet not found in $relPath."
            details.add("Expected beforeSnippet not present in source text.")
            return RemediationVerificationResult(
                valid = false,
                phase = "PRE_FLIGHT",
                ruleId = ruleId,
                filePath = relPath,
                preFlightPassed = false,
                astValid = false,
                checksPassed = false,
                testsPreserved = false,
                message = msg,
                details = details
            )
        }

        val occurrences = countOccurrences(originalContent, beforeSnippet)
        if (occurrences > 1) {
            val msg = "Pre-flight failed: Ambiguous replacement; target snippet occurs $occurrences times in $relPath."
            details.add("Target snippet is ambiguous ($occurrences occurrences).")
            return RemediationVerificationResult(
                valid = false,
                phase = "PRE_FLIGHT",
                ruleId = ruleId,
                filePath = relPath,
                preFlightPassed = false,
                astValid = false,
                checksPassed = false,
                testsPreserved = false,
                message = msg,
                details = details
            )
        }

        details.add("Pre-flight validation passed: File exists, writeSiteSafe=true, unique beforeSnippet located.")

        // 2. STAGED REMEDIATION APPLICATION
        val modifiedContent = originalContent.replace(beforeSnippet, afterSnippet)
        if (modifiedContent == originalContent) {
            val msg = "Application failed: Replacement produced identical content."
            details.add("No changes resulted from replacing beforeSnippet with afterSnippet.")
            return RemediationVerificationResult(
                valid = false,
                phase = "APPLICATION",
                ruleId = ruleId,
                filePath = relPath,
                preFlightPassed = true,
                astValid = false,
                checksPassed = false,
                testsPreserved = false,
                message = msg,
                details = details
            )
        }

        // 3. POST-REMEDIATION VERIFICATION
        // 3a. AST Validation via PolyglotAstExtractor
        val ext = if (relPath.contains('.')) "." + relPath.substringAfterLast('.') else ""
        val lang = PolyglotAstExtractor.supportedLanguage(ext)
        var astValid = true
        var topDeclCount = 1
        var origNodes: List<GraphNode> = emptyList()
        var nodes: List<GraphNode> = emptyList()

        if (lang.isNotEmpty()) {
            val masked = PolyglotAstExtractor.maskCommentsAndStrings(modifiedContent, lang)
            if (!hasBalancedDelimiters(masked)) {
                astValid = false
                details.add("Syntax check failed: Unbalanced or improperly nested delimiters ({}, (), or []) in modified content.")
            } else {
                try {
                    val extractedOrig = PolyglotAstExtractor.extractFile(
                        projectSlug, relPath, originalContent
                    )
                    origNodes = extractedOrig.first
                    val extractedMod = PolyglotAstExtractor.extractFile(
                        projectSlug, relPath, modifiedContent
                    )
                    nodes = extractedMod.first
                    topDeclCount = extractedMod.third
                    if (origNodes.isNotEmpty() && nodes.isEmpty() && modifiedContent.trim().isNotEmpty()) {
                        astValid = false
                        details.add("AST extraction produced 0 nodes for modified $lang source file (previously had ${origNodes.size} node(s)).")
                    } else {
                        details.add("Polyglot AST extraction succeeded (${nodes.size} nodes, topLevelDeclCount=$topDeclCount).")
                    }
                } catch (e: Exception) {
                    astValid = false
                    details.add("Polyglot AST extraction crashed: ${e.message}")
                }
            }
        }

        if (!astValid) {
            val msg = "Post-remediation verification failed: Modified source produces an invalid AST."
            return RemediationVerificationResult(
                valid = false,
                phase = "POST_VERIFICATION",
                ruleId = ruleId,
                filePath = relPath,
                preFlightPassed = true,
                astValid = false,
                checksPassed = false,
                testsPreserved = false,
                message = msg,
                details = details
            )
        }

        // 3b. Diagnostic / Check Verification (BOOST-003 Single Declaration & Compose stability checks)
        var checksPassed = true

        val (semiotic, _, _) = SemioticLayerClassifier.classify(relPath)
        val isTestFile = semiotic == "test" || relPath.contains("Test") || relPath.contains("test")
        if (!isTestFile && topDeclCount > 1 && !relPath.endsWith("__init__.py")) {
            checksPassed = false
            details.add("BOOST-003 violation: Modified file declares $topDeclCount top-level symbols (max 1 allowed).")
        }

        if (lang == "kotlin") {
            try {
                val origBundle = try {
                    composeExtractor.extractKotlinFile(
                        projectSlug = projectSlug,
                        relPath = relPath,
                        sourceText = originalContent
                    )
                } catch (e: Exception) { null }

                val bundle = composeExtractor.extractKotlinFile(
                    projectSlug = projectSlug,
                    relPath = relPath,
                    sourceText = modifiedContent
                )
                val targetRuleBit = ruleBitForId(ruleId)
                if (targetRuleBit != 0) {
                    val origViolations = origBundle?.nodes?.filter { (it.violationMask and targetRuleBit) != 0 } ?: emptyList()
                    val newViolations = bundle.nodes.filter { (it.violationMask and targetRuleBit) != 0 }

                    val cleared = if (!targetNodeId.isNullOrBlank()) {
                        val stillViolating = newViolations.any {
                            it.nodeId == targetNodeId || it.fqName == targetNodeId || it.nodeId.endsWith("#$targetNodeId")
                        }
                        !stillViolating
                    } else {
                        newViolations.isEmpty() || newViolations.size < origViolations.size
                    }

                    if (!cleared) {
                        checksPassed = false
                        details.add("Target rule $ruleId not cleared: ${newViolations.size} violation(s) remain post-remediation.")
                    } else {
                        details.add("Target rule $ruleId verified cleared post-remediation.")
                    }
                }
            } catch (e: Exception) {
                checksPassed = false
                details.add("Compose stability validation crashed: ${e.message}")
            }
        }

        if (ruleId.startsWith("COMPLEXITY") || expectedReductionPercent != null) {
            val origTargetNode = if (!targetNodeId.isNullOrBlank()) {
                origNodes.firstOrNull { it.nodeId == targetNodeId }
                    ?: origNodes.firstOrNull { it.qualifiedName == targetNodeId }
                    ?: origNodes.firstOrNull { it.name == targetNodeId }
            } else {
                origNodes.firstOrNull { it.kind in setOf("function", "method", "composable") }
            }
            val newTargetNode = if (!targetNodeId.isNullOrBlank()) {
                val matches = nodes.filter {
                    it.nodeId == targetNodeId || it.qualifiedName == targetNodeId || it.name == targetNodeId
                }
                if (origTargetNode != null && matches.size > 1) {
                    matches.minByOrNull { kotlin.math.abs(it.startLine - origTargetNode.startLine) }
                } else {
                    matches.firstOrNull()
                }
            } else {
                nodes.firstOrNull { it.kind in setOf("function", "method", "composable") }
            }

            val reductionPct = if (origTargetNode != null && newTargetNode != null && origTargetNode.cognitiveComplexity > 0) {
                val diff = origTargetNode.cognitiveComplexity - newTargetNode.cognitiveComplexity
                (diff.toDouble() / origTargetNode.cognitiveComplexity.toDouble()) * 100.0
            } else {
                expectedReductionPercent ?: 0.0
            }

            val roundedReduction = (reductionPct * 10.0).roundToInt() / 10.0
            if (reductionPct < 25.0) {
                checksPassed = false
                details.add("Gate 4 failed: Cognitive complexity reduction ($roundedReduction%) is below the required 25.0% threshold.")
            } else {
                details.add("Gate 4 passed: Cognitive complexity reduction verified ($roundedReduction% >= 25.0%).")
            }
        }

        if (!checksPassed) {
            val msg = "Post-remediation verification failed: Checks did not pass post-remediation."
            return RemediationVerificationResult(
                valid = false,
                phase = "POST_VERIFICATION",
                ruleId = ruleId,
                filePath = relPath,
                preFlightPassed = true,
                astValid = astValid,
                checksPassed = false,
                testsPreserved = false,
                message = msg,
                details = details
            )
        }

        // 3c. Test Suite Preservation Check
        var testsPreserved = true

        // 1. In-memory programmatic test verifier hook
        if (testVerifier != null) {
            try {
                testsPreserved = testVerifier(relPath, modifiedContent)
                if (!testsPreserved) {
                    details.add("Test suite preservation hook returned false.")
                } else {
                    details.add("Test suite preservation verified via hook.")
                }
            } catch (e: Exception) {
                testsPreserved = false
                details.add("Test suite preservation check failed: ${e.message}")
            }
        }

        // 2. Shell test command execution with atomic disk staging & automatic rollback
        if (testsPreserved && !testCommand.isNullOrBlank()) {
            val staged = try {
                PlatformFiles.writeText(fullPath, modifiedContent)
            } catch (e: Exception) {
                false
            }

            if (!staged) {
                testsPreserved = false
                details.add("Failed to stage modified content to disk for test execution: $fullPath")
            } else {
                try {
                    val exitCode = system(testCommand)
                    if (exitCode != 0) {
                        testsPreserved = false
                        details.add("Test command '$testCommand' failed (exit code $exitCode).")
                    } else {
                        details.add("Test command '$testCommand' passed (exit code 0).")
                    }
                } catch (e: Exception) {
                    testsPreserved = false
                    details.add("Test command '$testCommand' crashed: ${e.message}")
                } finally {
                    // Always rollback disk to originalContent if tests failed OR if dry run is requested
                    if (!testsPreserved || dryRun) {
                        PlatformFiles.writeText(fullPath, originalContent)
                    }
                }
            }
        }

        if (!testsPreserved) {
            val msg = "Post-remediation verification failed: Test suite preservation check failed."
            return RemediationVerificationResult(
                valid = false,
                phase = "POST_VERIFICATION",
                ruleId = ruleId,
                filePath = relPath,
                preFlightPassed = true,
                astValid = astValid,
                checksPassed = checksPassed,
                testsPreserved = false,
                message = msg,
                details = details
            )
        }

        // 4. COMMIT OR DRY-RUN
        val diffLines = mutableListOf<String>()
        diffLines.add("--- a/$relPath")
        diffLines.add("+++ b/$relPath")
        diffLines.add("@@ -1 +1 @@")
        for (line in beforeSnippet.lines()) {
            diffLines.add("-$line")
        }
        for (line in afterSnippet.lines()) {
            diffLines.add("+$line")
        }
        val diffSnippet = diffLines.joinToString("\n")

        if (!dryRun) {
            if (testCommand.isNullOrBlank()) {
                try {
                    PlatformFiles.writeText(fullPath, modifiedContent)
                    details.add("Remediation safely written to disk at $relPath.")
                } catch (e: Exception) {
                    val msg = "Application failed: Write error writing to $fullPath: ${e.message}"
                    details.add(msg)
                    return RemediationVerificationResult(
                        valid = false,
                        phase = "APPLICATION",
                        ruleId = ruleId,
                        filePath = relPath,
                        preFlightPassed = true,
                        astValid = astValid,
                        checksPassed = checksPassed,
                        testsPreserved = testsPreserved,
                        message = msg,
                        details = details
                    )
                }
            } else {
                details.add("Remediation safely verified and committed to disk at $relPath.")
            }
        } else {
            details.add("Dry run mode: changes validated and verified, disk untouched.")
        }

        return RemediationVerificationResult(
            valid = true,
            phase = "COMPLETED",
            ruleId = ruleId,
            filePath = relPath,
            preFlightPassed = true,
            astValid = true,
            checksPassed = true,
            testsPreserved = true,
            message = if (dryRun) "Remediation verified successfully (dry-run)." else "Remediation verified and applied successfully.",
            details = details,
            verifiedDiff = diffSnippet
        )
    }

    private fun hasBalancedDelimiters(maskedText: String): Boolean {
        val stack = mutableListOf<Char>()
        for (c in maskedText) {
            when (c) {
                '{', '(', '[' -> stack.add(c)
                '}' -> {
                    if (stack.isEmpty() || stack.removeAt(stack.size - 1) != '{') return false
                }
                ')' -> {
                    if (stack.isEmpty() || stack.removeAt(stack.size - 1) != '(') return false
                }
                ']' -> {
                    if (stack.isEmpty() || stack.removeAt(stack.size - 1) != '[') return false
                }
            }
        }
        return stack.isEmpty()
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
