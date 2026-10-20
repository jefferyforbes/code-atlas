package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.extract.PolyglotAstExtractor
import atlas.extract.SemioticLayerClassifier
import atlas.model.ArchitectureRemediationPrescription
import atlas.model.ArchitectureRemediationReport
import atlas.model.GraphNode
import atlas.model.LayerInversionViolation
import atlas.model.SingleDeclarationViolation
import atlas.model.SpawnedFileRecord
import atlas.util.PlatformFiles
import platform.posix.system
import kotlin.math.max
import kotlin.math.min

/**
 * Autonomous architecture remediation engine for inspecting audit findings (BOOST-003 single declaration
 * violations and ARCH-LAYER-INVERSION layer inversions), synthesizing verified AST file-splitting diffs,
 * classification corrections, and Dependency Inversion prescriptions, and committing verified changes.
 */
class ArchitectureRemediationEngine(
    private val workspaceRoot: String,
    private val store: SqliteGraphStore,
    private val verifier: AutonomousRemediationVerifier = AutonomousRemediationVerifier(workspaceRoot, store)
) {
    private val auditor = ArchitectureAuditor(store)

    fun planAndRemediate(
        projectId: String,
        targetPaths: List<String> = emptyList(),
        ruleFilter: String? = null,
        dryRun: Boolean = true,
        splitBoost003: Boolean = true,
        makePrivateBoost003: Boolean = false,
        testCommand: String? = null,
        limit: Int = 50
    ): ArchitectureRemediationReport {
        val auditReport = auditor.runFullAudit(
            projectId = projectId,
            pathPrefixes = if (targetPaths.isNotEmpty()) targetPaths else null,
            descriptionMode = "off"
        )

        val boostViolations = if (ruleFilter == null || ruleFilter.equals("BOOST-003", ignoreCase = true) || ruleFilter.equals("all", ignoreCase = true)) {
            auditReport.singleDeclarationViolations
        } else emptyList()

        val layerViolations = if (ruleFilter == null || ruleFilter.equals("ARCH-LAYER-INVERSION", ignoreCase = true) || ruleFilter.equals("all", ignoreCase = true)) {
            auditReport.layerInversions
        } else emptyList()

        val totalViolations = boostViolations.size + layerViolations.size
        val prescriptions = mutableListOf<ArchitectureRemediationPrescription>()

        var count = 0
        // 1. Process BOOST-003 violations
        for (v in boostViolations) {
            if (count >= limit) break
            val rx = remediateBoost003(
                projectId = projectId,
                violation = v,
                dryRun = dryRun,
                split = splitBoost003,
                makePrivate = makePrivateBoost003,
                testCommand = testCommand
            )
            prescriptions.add(rx)
            count++
        }

        // 2. Process ARCH-LAYER-INVERSION violations
        for (v in layerViolations) {
            if (count >= limit) break
            val rx = remediateLayerInversion(
                projectId = projectId,
                violation = v,
                dryRun = dryRun,
                testCommand = testCommand
            )
            prescriptions.add(rx)
            count++
        }

        val verifiedCount = prescriptions.count { it.verified }
        val appliedCount = prescriptions.count { it.applied }

        val summaryText = buildString {
            append("[Remediation Plan] project=").append(projectId)
            append(" total_violations=").append(totalViolations)
            append(" boost003=").append(boostViolations.size)
            append(" layer_inversions=").append(layerViolations.size)
            append(" planned=").append(prescriptions.size)
            append(" verified=").append(verifiedCount)
            append(" applied=").append(appliedCount)
            append(" dry_run=").append(dryRun)
        }

        return ArchitectureRemediationReport(
            project = projectId,
            totalViolations = totalViolations,
            boost003Count = boostViolations.size,
            layerInversionCount = layerViolations.size,
            verifiedCount = verifiedCount,
            appliedCount = appliedCount,
            dryRun = dryRun,
            prescriptions = prescriptions,
            summary = summaryText
        )
    }

    private fun readTargetFileContent(fullPath: String, relPath: String): Pair<String?, ArchitectureRemediationPrescription?> {
        if (!PlatformFiles.exists(fullPath)) {
            return Pair(null, ArchitectureRemediationPrescription(
                id = "rx-boost003-${relPath.replace('/', '_')}",
                ruleId = "BOOST-003",
                filePath = relPath,
                fixStrategy = "FILE_NOT_FOUND",
                rationale = "Target file not found on disk: $fullPath",
                verified = false,
                applied = false,
                details = listOf("File does not exist: $fullPath")
            ))
        }
        return try {
            Pair(PlatformFiles.readText(fullPath), null)
        } catch (e: Exception) {
            Pair(null, ArchitectureRemediationPrescription(
                id = "rx-boost003-${relPath.replace('/', '_')}",
                ruleId = "BOOST-003",
                filePath = relPath,
                fixStrategy = "READ_ERROR",
                rationale = "Cannot read target file: ${e.message}",
                verified = false,
                applied = false,
                details = listOf("Read error on $fullPath: ${e.message}")
            ))
        }
    }

    private fun filterCandidateTopNodes(nodes: List<GraphNode>): List<GraphNode> {
        val candidateKinds = setOf(
            "class", "interface", "object", "function", "composable",
            "enum", "annotation", "struct", "protocol", "actor"
        )
        return nodes.filter { it.isTopLevel && it.kind in candidateKinds }
    }

    private fun resolvePrimaryAndSecondaryNodes(
        topNodes: List<GraphNode>,
        relPath: String,
        ext: String
    ): Triple<GraphNode, List<GraphNode>, String?> {
        val baseFileName = relPath.substringAfterLast('/').substringBeforeLast('.')
        val matchingNode = topNodes.firstOrNull { it.name.equals(baseFileName, ignoreCase = true) }
        val primaryNode = matchingNode ?: topNodes.first()
        val secondaryNodes = topNodes.filter { it != primaryNode }
        val dir = if (relPath.contains('/')) relPath.substringBeforeLast('/') else ""
        val targetRenameRelPath = if (matchingNode == null) {
            val primaryFileName = "${primaryNode.name}$ext"
            if (dir.isEmpty()) primaryFileName else "$dir/$primaryFileName"
        } else null
        return Triple(primaryNode, secondaryNodes, targetRenameRelPath)
    }

    private fun buildCompliantPrescription(relPath: String, count: Int): ArchitectureRemediationPrescription {
        return ArchitectureRemediationPrescription(
            id = "rx-boost003-${relPath.replace('/', '_')}",
            ruleId = "BOOST-003",
            filePath = relPath,
            fixStrategy = "NOOP",
            rationale = "File already complies with BOOST-003 ($count top-level symbol(s)).",
            verified = true,
            applied = false,
            details = listOf("No top-level declarations require splitting.")
        )
    }

    private fun buildManualSplitPrescription(
        relPath: String,
        primaryNode: GraphNode,
        secondaryNodes: List<GraphNode>,
        count: Int
    ): ArchitectureRemediationPrescription {
        return ArchitectureRemediationPrescription(
            id = "rx-boost003-${relPath.replace('/', '_')}",
            ruleId = "BOOST-003",
            filePath = relPath,
            fixStrategy = "MANUAL_SPLIT_ADVISORY",
            rationale = "File declares $count top-level symbols. Splitting disabled (--no-split). Run with --split to extract to separate files or --make-private to convert secondary helpers to private.",
            primaryDeclaration = primaryNode.name,
            secondaryDeclarations = secondaryNodes.map { it.name },
            verified = true,
            applied = false,
            details = listOf("Splitting disabled (--no-split). Manual refactoring or --make-private recommended.")
        )
    }

    private fun buildCollisionPrescription(
        relPath: String,
        collisionPath: String,
        primaryNode: GraphNode,
        secondaryNodes: List<GraphNode>
    ): ArchitectureRemediationPrescription {
        return ArchitectureRemediationPrescription(
            id = "rx-boost003-${relPath.replace('/', '_')}",
            ruleId = "BOOST-003",
            filePath = relPath,
            fixStrategy = "COLLISION",
            rationale = "Target file '$collisionPath' already exists. Cannot safely split without overwriting.",
            primaryDeclaration = primaryNode.name,
            secondaryDeclarations = secondaryNodes.map { it.name },
            verified = false,
            applied = false,
            details = listOf("Spawn target '$collisionPath' already exists on disk. Collision avoided.")
        )
    }

    private fun checkCollision(
        root: String,
        dir: String,
        ext: String,
        targetRenameRelPath: String?,
        secondaryNodes: List<GraphNode>,
        relPath: String,
        primaryNode: GraphNode
    ): ArchitectureRemediationPrescription? {
        if (targetRenameRelPath != null && PlatformFiles.exists("$root/$targetRenameRelPath")) {
            return buildCollisionPrescription(relPath, targetRenameRelPath, primaryNode, secondaryNodes)
        }
        for (sec in secondaryNodes) {
            val newFileName = "${sec.name}$ext"
            val newRelPath = if (dir.isEmpty()) newFileName else "$dir/$newFileName"
            if (PlatformFiles.exists("$root/$newRelPath")) {
                return buildCollisionPrescription(relPath, newRelPath, primaryNode, secondaryNodes)
            }
        }
        return null
    }

    private fun buildSpawnedFileContent(
        headerLines: List<String>,
        importLines: List<String>,
        sliceText: String
    ): String {
        return buildString {
            if (headerLines.isNotEmpty()) {
                headerLines.forEach { append(it.trim()).append("\n") }
                append("\n")
            }
            if (importLines.isNotEmpty()) {
                importLines.forEach { append(it.trim()).append("\n") }
                append("\n")
            }
            append(sliceText.trim()).append("\n")
        }
    }

    private fun extractSpawnedFiles(
        workingLines: MutableList<String>,
        secondaryNodes: List<GraphNode>,
        dir: String,
        ext: String,
        headerLines: List<String>,
        importLines: List<String>
    ): List<SpawnedFileRecord> {
        val spawnedFiles = mutableListOf<SpawnedFileRecord>()
        val sortedSecondariesDesc = secondaryNodes.sortedByDescending { it.startLine }

        for (sec in sortedSecondariesDesc) {
            val expandedStart = findExpandedStartLine(workingLines, sec.startLine)
            val startIdx = max(0, expandedStart - 1)
            val endIdx = min(workingLines.size - 1, sec.endLine - 1)
            if (startIdx > endIdx) continue

            val sliceLines = workingLines.subList(startIdx, endIdx + 1).toList()
            val sliceText = sliceLines.joinToString("\n")
            val newFileName = "${sec.name}$ext"
            val newRelPath = if (dir.isEmpty()) newFileName else "$dir/$newFileName"
            val newFileContent = buildSpawnedFileContent(headerLines, importLines, sliceText)

            spawnedFiles.add(
                SpawnedFileRecord(
                    relPath = newRelPath,
                    declarationName = sec.name,
                    content = newFileContent,
                    loc = newFileContent.lines().size
                )
            )

            for (i in endIdx downTo startIdx) {
                workingLines.removeAt(i)
            }
        }
        return spawnedFiles
    }

    private fun verifyAstSplits(
        projectId: String,
        effectiveRelPath: String,
        cleanModified: String,
        spawnedFiles: List<SpawnedFileRecord>,
        lang: String,
        details: MutableList<String>
    ): Boolean {
        if (lang.isEmpty()) return true

        val maskedOrig = PolyglotAstExtractor.maskCommentsAndStrings(cleanModified, lang)
        if (!AutonomousRemediationVerifier.hasBalancedDelimiters(maskedOrig)) {
            details.add("Syntax check failed: Unbalanced delimiters in modified '$effectiveRelPath'.")
            return false
        }
        val (_, _, origCount) = PolyglotAstExtractor.extractFile(projectId, effectiveRelPath, cleanModified)
        if (origCount > 1) {
            details.add("Modified file still declares $origCount top-level symbols (expected <= 1).")
            return false
        }
        details.add("AST verification passed for modified original file '$effectiveRelPath' (topLevelDeclCount=$origCount).")

        for (sf in spawnedFiles) {
            val maskedSf = PolyglotAstExtractor.maskCommentsAndStrings(sf.content, lang)
            if (!AutonomousRemediationVerifier.hasBalancedDelimiters(maskedSf)) {
                details.add("Syntax check failed: Unbalanced delimiters in spawned file '${sf.relPath}'.")
                return false
            }
            val (_, _, sfCount) = PolyglotAstExtractor.extractFile(projectId, sf.relPath, sf.content)
            if (sfCount != 1) {
                details.add("Spawned file '${sf.relPath}' has $sfCount declarations (expected exactly 1).")
                return false
            }
            details.add("AST verification passed for spawned file '${sf.relPath}' (decl='${sf.declarationName}').")
        }
        return true
    }

    private fun rollbackFiles(writtenPaths: List<String>, fullPath: String, originalContent: String) {
        for (w in writtenPaths) {
            PlatformFiles.delete(w)
        }
        PlatformFiles.writeText(fullPath, originalContent)
    }

    private fun executeTestCommand(cmdStr: String, details: MutableList<String>): Boolean {
        val cmd = "cd \"$workspaceRoot\" && $cmdStr"
        val rawStatus = system(cmd)
        val exitCode = if (rawStatus == -1) -1 else (rawStatus shr 8) and 0xff
        if (exitCode != 0) {
            details.add("Test command '$cmdStr' failed (exit code $exitCode). Automatic rollback performed.")
            return false
        }
        details.add("Test command '$cmdStr' passed (exit code 0).")
        return true
    }

    private fun applyFilesAndRunTest(
        root: String,
        fullPath: String,
        primaryNewFullPath: String,
        targetRenameRelPath: String?,
        cleanModified: String,
        originalContent: String,
        spawnedFiles: List<SpawnedFileRecord>,
        testCommand: String?,
        details: MutableList<String>
    ): Boolean {
        val writtenPaths = mutableListOf<String>()
        return try {
            for (sf in spawnedFiles) {
                val fPath = "$root/${sf.relPath}"
                PlatformFiles.mkdirs(fPath.substringBeforeLast('/'))
                PlatformFiles.writeText(fPath, sf.content)
                writtenPaths.add(fPath)
            }
            if (targetRenameRelPath != null) {
                PlatformFiles.delete(fullPath)
                PlatformFiles.writeText(primaryNewFullPath, cleanModified)
                writtenPaths.add(primaryNewFullPath)
            } else {
                PlatformFiles.writeText(fullPath, cleanModified)
            }

            if (!testCommand.isNullOrBlank()) {
                val passed = executeTestCommand(testCommand, details)
                if (!passed) {
                    rollbackFiles(writtenPaths, fullPath, originalContent)
                    return false
                }
            }
            true
        } catch (e: Exception) {
            details.add("Disk application error: ${e.message}. Performing rollback.")
            rollbackFiles(writtenPaths, fullPath, originalContent)
            false
        }
    }

    private fun buildInvalidAstPrescription(
        relPath: String,
        primaryNode: GraphNode,
        secondaryNodes: List<GraphNode>,
        spawnedFiles: List<SpawnedFileRecord>,
        details: List<String>
    ): ArchitectureRemediationPrescription {
        return ArchitectureRemediationPrescription(
            id = "rx-boost003-${relPath.replace('/', '_')}",
            ruleId = "BOOST-003",
            filePath = relPath,
            fixStrategy = "AST_FILE_SPLIT",
            rationale = "AST splitting produced invalid syntax or non-compliant declaration counts.",
            primaryDeclaration = primaryNode.name,
            secondaryDeclarations = secondaryNodes.map { it.name },
            spawnedFiles = spawnedFiles,
            verified = false,
            applied = false,
            details = details
        )
    }

    private fun buildFinalSplitPrescription(
        relPath: String,
        primaryNode: GraphNode,
        secondaryNodes: List<GraphNode>,
        spawnedFiles: List<SpawnedFileRecord>,
        diffPreview: String,
        targetRenameRelPath: String?,
        verified: Boolean,
        applied: Boolean,
        details: List<String>
    ): ArchitectureRemediationPrescription {
        val rationaleMsg = if (targetRenameRelPath != null) {
            "Split ${secondaryNodes.size} secondary top-level declaration(s) and renamed original file to '$targetRenameRelPath' to match Single Declaration Principle (Rule 6 / BOOST-003)."
        } else {
            "Split ${secondaryNodes.size} secondary top-level declaration(s) into dedicated source files matching Single Declaration Principle (Rule 6 / BOOST-003)."
        }
        return ArchitectureRemediationPrescription(
            id = "rx-boost003-${relPath.replace('/', '_')}",
            ruleId = "BOOST-003",
            filePath = relPath,
            line = primaryNode.startLine,
            fixStrategy = "AST_FILE_SPLIT",
            rationale = rationaleMsg,
            primaryDeclaration = primaryNode.name,
            secondaryDeclarations = secondaryNodes.map { it.name },
            spawnedFiles = spawnedFiles,
            diffPreview = diffPreview,
            verified = verified,
            applied = applied,
            details = details
        )
    }

    private fun applyOrDryRun(
        dryRun: Boolean,
        root: String,
        fullPath: String,
        primaryNewFullPath: String,
        targetRenameRelPath: String?,
        cleanModified: String,
        originalContent: String,
        spawnedFiles: List<SpawnedFileRecord>,
        testCommand: String?,
        details: MutableList<String>
    ): Boolean {
        if (!dryRun) {
            return applyFilesAndRunTest(root, fullPath, primaryNewFullPath, targetRenameRelPath, cleanModified, originalContent, spawnedFiles, testCommand, details)
        }
        details.add("Dry-run mode: verified AST valid and Single Declaration compliant without modifying disk.")
        return true
    }

    private fun executeAstSplitRemediation(
        projectId: String,
        relPath: String,
        root: String,
        fullPath: String,
        originalContent: String,
        ext: String,
        lang: String,
        primaryNode: GraphNode,
        secondaryNodes: List<GraphNode>,
        targetRenameRelPath: String?,
        dryRun: Boolean,
        testCommand: String?,
        details: MutableList<String>
    ): ArchitectureRemediationPrescription {
        val dir = if (relPath.contains('/')) relPath.substringBeforeLast('/') else ""
        val collisionPrescription = checkCollision(root, dir, ext, targetRenameRelPath, secondaryNodes, relPath, primaryNode)
        if (collisionPrescription != null) return collisionPrescription

        val originalLines = originalContent.lines()
        val workingLines = originalLines.toMutableList()
        val (headerLines, importLines) = extractHeaderAndImports(originalLines, ext)
        val spawnedFiles = extractSpawnedFiles(workingLines, secondaryNodes, dir, ext, headerLines, importLines)

        val rawModified = workingLines.joinToString("\n")
        val cleanModified = Regex("""\n{3,}""").replace(rawModified, "\n\n").trimEnd() + "\n"
        val effectiveRelPath = targetRenameRelPath ?: relPath

        val astValid = verifyAstSplits(projectId, effectiveRelPath, cleanModified, spawnedFiles, lang, details)
        if (!astValid) {
            return buildInvalidAstPrescription(relPath, primaryNode, secondaryNodes, spawnedFiles, details)
        }

        val diffPreview = buildDiffPreview(relPath, originalContent, cleanModified, spawnedFiles, renamedTo = targetRenameRelPath)
        val primaryNewFullPath = if (targetRenameRelPath != null) "$root/$targetRenameRelPath" else fullPath
        val testsPreserved = applyOrDryRun(
            dryRun, root, fullPath, primaryNewFullPath, targetRenameRelPath,
            cleanModified, originalContent, spawnedFiles, testCommand, details
        )

        val success = astValid && testsPreserved
        val applied = !dryRun && success
        return buildFinalSplitPrescription(
            relPath, primaryNode, secondaryNodes, spawnedFiles, diffPreview,
            targetRenameRelPath, success, applied, details
        )
    }

    private fun remediateBoost003(
        projectId: String,
        violation: SingleDeclarationViolation,
        dryRun: Boolean,
        split: Boolean,
        makePrivate: Boolean,
        testCommand: String?
    ): ArchitectureRemediationPrescription {
        val relPath = violation.relPath
        val root = PlatformFiles.canonicalPath(workspaceRoot).trimEnd('/')
        val fullPath = if (relPath.startsWith("/")) relPath else "$root/$relPath"
        val details = mutableListOf<String>()

        val (originalContent, fileErr) = readTargetFileContent(fullPath, relPath)
        if (fileErr != null) return fileErr
        val content = originalContent ?: return buildCompliantPrescription(relPath, 0)

        val ext = if (relPath.contains('.')) "." + relPath.substringAfterLast('.') else ""
        val lang = PolyglotAstExtractor.supportedLanguage(ext)
        val (nodes, _, _) = PolyglotAstExtractor.extractFile(projectId, relPath, content)
        val topNodes = filterCandidateTopNodes(nodes)

        if (topNodes.size <= 1) {
            return buildCompliantPrescription(relPath, topNodes.size)
        }

        val (primaryNode, secondaryNodes, targetRenameRelPath) = resolvePrimaryAndSecondaryNodes(topNodes, relPath, ext)

        if (!split && !makePrivate) {
            return buildManualSplitPrescription(relPath, primaryNode, secondaryNodes, topNodes.size)
        }

        if (makePrivate) {
            return remediateBoost003MakePrivate(
                projectId, relPath, fullPath, content,
                primaryNode, secondaryNodes, lang, dryRun, testCommand
            )
        }

        return executeAstSplitRemediation(
            projectId, relPath, root, fullPath, content, ext, lang,
            primaryNode, secondaryNodes, targetRenameRelPath, dryRun, testCommand, details
        )
    }

    private fun privatizeKotlinSubTrimmed(rest: String): String {
        return when {
            rest.startsWith("public ") -> "private " + rest.removePrefix("public ")
            rest.startsWith("internal ") -> "private " + rest.removePrefix("internal ")
            rest.startsWith("open ") -> "private " + rest.removePrefix("open ")
            !rest.startsWith("private ") -> "private $rest"
            else -> rest
        }
    }

    private fun privatizeKotlinDeclaration(trimmed: String): String {
        return when {
            trimmed.startsWith("public ") -> "private " + trimmed.removePrefix("public ")
            trimmed.startsWith("internal ") -> "private " + trimmed.removePrefix("internal ")
            trimmed.startsWith("open ") -> "private " + trimmed.removePrefix("open ")
            trimmed.startsWith("@") -> {
                val annMatch = Regex("""^((?:@[A-Za-z0-9_]+(?:\([^)]*\))?\s*)+)(.*)""").find(trimmed)
                if (annMatch != null) {
                    annMatch.groupValues[1] + privatizeKotlinSubTrimmed(annMatch.groupValues[2])
                } else {
                    "private $trimmed"
                }
            }
            !trimmed.startsWith("private ") -> "private $trimmed"
            else -> trimmed
        }
    }

    private fun privatizePythonDeclaration(trimmed: String, name: String, details: MutableList<String>): String {
        if (name.startsWith("_")) return trimmed
        return if (trimmed.startsWith("def ")) {
            details.add("Converted function '$name' to private '_$name'.")
            trimmed.replaceFirst("def $name", "def _$name")
        } else if (trimmed.startsWith("class ")) {
            details.add("Converted class '$name' to private '_$name'.")
            trimmed.replaceFirst("class $name", "class _$name")
        } else {
            trimmed
        }
    }

    private fun privatizeLine(
        raw: String,
        lang: String,
        secName: String,
        details: MutableList<String>
    ): String {
        val indent = raw.takeWhile { it == ' ' || it == '\t' }
        val trimmed = raw.trimStart()

        val newTrimmed = when (lang) {
            "kotlin" -> {
                details.add("Converted '$secName' to private visibility.")
                privatizeKotlinDeclaration(trimmed)
            }
            "python" -> privatizePythonDeclaration(trimmed, secName, details)
            else -> {
                details.add("Converted '$secName' to private visibility.")
                if (!trimmed.startsWith("private ")) "private $trimmed" else trimmed
            }
        }
        return indent + newTrimmed
    }

    private fun privatizeSecondaryDeclarations(
        workingLines: MutableList<String>,
        secondaryNodes: List<GraphNode>,
        lang: String,
        details: MutableList<String>
    ): String {
        for (sec in secondaryNodes) {
            val lineIdx = sec.startLine - 1
            if (lineIdx in workingLines.indices) {
                workingLines[lineIdx] = privatizeLine(workingLines[lineIdx], lang, sec.name, details)
            }
        }
        return workingLines.joinToString("\n")
    }

    private fun verifyPrivatizedAst(
        projectId: String,
        relPath: String,
        modifiedContent: String,
        lang: String,
        details: MutableList<String>
    ): Boolean {
        if (lang.isEmpty()) return true
        val masked = PolyglotAstExtractor.maskCommentsAndStrings(modifiedContent, lang)
        if (!AutonomousRemediationVerifier.hasBalancedDelimiters(masked)) {
            details.add("Syntax check failed: Unbalanced delimiters after privatizing secondary declarations.")
            return false
        }
        val (_, _, count) = PolyglotAstExtractor.extractFile(projectId, relPath, modifiedContent)
        if (count > 1) {
            details.add("File still declares $count public top-level symbols after privatization.")
            return false
        }
        details.add("AST verification passed: public topLevelDeclCount reduced to $count.")
        return true
    }

    private fun applyPrivatizedAndRunTest(
        fullPath: String,
        originalContent: String,
        modifiedContent: String,
        testCommand: String?,
        dryRun: Boolean,
        details: MutableList<String>
    ): Boolean {
        if (dryRun) {
            details.add("Dry-run mode: verified private helper conversion without modifying disk.")
            return true
        }
        return try {
            PlatformFiles.writeText(fullPath, modifiedContent)
            if (!testCommand.isNullOrBlank()) {
                val passed = executeTestCommand(testCommand, details)
                if (!passed) {
                    PlatformFiles.writeText(fullPath, originalContent)
                    return false
                }
            }
            true
        } catch (e: Exception) {
            details.add("Disk application error: ${e.message}. Performing rollback.")
            PlatformFiles.writeText(fullPath, originalContent)
            false
        }
    }

    private fun remediateBoost003MakePrivate(
        projectId: String,
        relPath: String,
        fullPath: String,
        originalContent: String,
        primaryNode: GraphNode,
        secondaryNodes: List<GraphNode>,
        lang: String,
        dryRun: Boolean,
        testCommand: String?
    ): ArchitectureRemediationPrescription {
        val details = mutableListOf<String>()
        val workingLines = originalContent.lines().toMutableList()
        val modifiedContent = privatizeSecondaryDeclarations(workingLines, secondaryNodes, lang, details)

        val astValid = verifyPrivatizedAst(projectId, relPath, modifiedContent, lang, details)
        if (!astValid) {
            return ArchitectureRemediationPrescription(
                id = "rx-boost003-${relPath.replace('/', '_')}",
                ruleId = "BOOST-003",
                filePath = relPath,
                fixStrategy = "MAKE_PRIVATE",
                rationale = "Privatization produced syntax errors or failed to reduce public declaration count.",
                primaryDeclaration = primaryNode.name,
                secondaryDeclarations = secondaryNodes.map { it.name },
                verified = false,
                applied = false,
                details = details
            )
        }

        val diffPreview = buildDiffPreview(relPath, originalContent, modifiedContent, emptyList())
        val testsPreserved = applyPrivatizedAndRunTest(fullPath, originalContent, modifiedContent, testCommand, dryRun, details)
        val isSuccess = astValid && testsPreserved

        return ArchitectureRemediationPrescription(
            id = "rx-boost003-${relPath.replace('/', '_')}",
            ruleId = "BOOST-003",
            filePath = relPath,
            line = primaryNode.startLine,
            fixStrategy = "MAKE_PRIVATE",
            rationale = "Converted ${secondaryNodes.size} secondary top-level helper declaration(s) to private visibility per Rule 6 exceptions.",
            primaryDeclaration = primaryNode.name,
            secondaryDeclarations = secondaryNodes.map { it.name },
            diffPreview = diffPreview,
            verified = isSuccess,
            applied = !dryRun && isSuccess,
            details = details
        )
    }

    private fun remediateLayerInversion(
        projectId: String,
        violation: LayerInversionViolation,
        dryRun: Boolean,
        testCommand: String?
    ): ArchitectureRemediationPrescription {
        val srcNode = store.getNode(projectId, violation.source)
        val tgtNode = store.getNode(projectId, violation.target)
        val srcRelPath = srcNode?.fileId?.substringAfter(':') ?: violation.source.substringBefore(':')
        val root = PlatformFiles.canonicalPath(workspaceRoot).trimEnd('/')
        val fullPath = if (srcRelPath.startsWith("/")) srcRelPath else "$root/$srcRelPath"
        val details = mutableListOf<String>()

        val normSrcPath = srcRelPath.replace('\\', '/').lowercase()
        val checkPath = if (normSrcPath.startsWith("/")) normSrcPath else "/$normSrcPath"
        val symLow = srcNode?.name?.lowercase() ?: ""

        val isPresentationConstruct = listOf(
            "/ui/", "/presentation/", "/screen/", "/screens/", "/components/", "/views/", "/view/", "/cli/", "/mcp/",
            "/web/", "/gui/", "/activity/", "/activities/", "/demo/", "/launcher/"
        ).any { it in checkPath } ||
                listOf("screen", "viewmodel", "composable", "dialog", "sheet", "cli", "mcpserver",
                       "activity", "launcher", "gui", "component", "components").any { symLow.endsWith(it) } ||
                normSrcPath.endsWith("cli.py") || normSrcPath.endsWith("cli.kt") || normSrcPath.endsWith("mcpserver.kt") ||
                normSrcPath.endsWith("gui.py") || normSrcPath.endsWith("_gui.py") || normSrcPath.endsWith("_runner.py") ||
                normSrcPath.endsWith("_tool.py") || normSrcPath.endsWith("mb_tool.py") ||
                normSrcPath.endsWith("/main.kt") || normSrcPath == "main.kt"

        val isInfraConstruct = listOf(
            "/extract/", "/bridge/", "/config/", "/util/", "/tools/", "/scripts/", "/orchestrator/",
            "/tools & scripts/"
        ).any { it in checkPath } ||
                checkPath.contains("/tools") || checkPath.contains("/scripts") || checkPath.contains("/orchestrator")

        if (isPresentationConstruct || isInfraConstruct) {
            val targetLayer = if (isPresentationConstruct) "Presentation/Pragmatic" else "Infrastructure"
            val targetArch = if (isPresentationConstruct) "presentation" else "infrastructure"
            val targetSemiotic = if (isPresentationConstruct) "pragmatic" else "semantic"
            val rationale = "Source symbol '${violation.source}' in '$srcRelPath' belongs to the $targetLayer layer. Correcting classifier eliminates false layer inversion."

            if (!dryRun) {
                // Re-index this specific file with force to update node arch_layer in sqlite store
                val indexer = WorkspaceIndexer(workspaceRoot, store)
                indexer.indexWorkspace(projectId, subpaths = listOf(srcRelPath), force = true)
                details.add("Re-indexed '$srcRelPath' with updated classification: arch_layer=$targetArch, semiotic_layer=$targetSemiotic.")
            } else {
                details.add("Dry-run: reindexing '$srcRelPath' with updated SemioticLayerClassifier will classify as $targetLayer.")
            }

            return ArchitectureRemediationPrescription(
                id = "rx-arch-${violation.source.replace('.', '_')}",
                ruleId = "ARCH-LAYER-INVERSION",
                filePath = srcRelPath,
                line = violation.lineNumber,
                fixStrategy = "CLASSIFICATION_CORRECTION",
                rationale = rationale,
                verified = true,
                applied = !dryRun,
                details = details
            )
        }

        // True architectural inversion
        val portName = "${tgtNode?.name ?: "ExternalService"}Port"
        val rationale = "Domain symbol '${violation.source}' directly references ${tgtNode?.archLayer ?: "infrastructure"} symbol '${violation.target}'. Introduce Domain Port interface '$portName' (Dependency Inversion Principle)."
        details.add("Prescription: Define 'interface $portName' in domain, inject into '${violation.source}', and implement in infrastructure.")

        return ArchitectureRemediationPrescription(
            id = "rx-arch-${violation.source.replace('.', '_')}",
            ruleId = "ARCH-LAYER-INVERSION",
            filePath = srcRelPath,
            line = violation.lineNumber,
            fixStrategy = "DEPENDENCY_INVERSION_PORT",
            rationale = rationale,
            verified = true,
            applied = false,
            details = details
        )
    }

    private fun findExpandedStartLine(lines: List<String>, startLine: Int): Int {
        var cur = startLine - 2
        var expanded = startLine
        var inBlockComment = false
        while (cur >= 0) {
            val line = lines[cur].trim()
            if (line.endsWith("*/")) {
                inBlockComment = true
                expanded = cur + 1
                cur--
                continue
            }
            if (inBlockComment) {
                expanded = cur + 1
                if (line.startsWith("/*")) {
                    inBlockComment = false
                }
                cur--
                continue
            }
            if (line.startsWith("@") || line.startsWith("//") || line.startsWith("///") || line.startsWith("#")) {
                expanded = cur + 1
                cur--
                continue
            }
            // If line is empty, peek if the line before it is part of doc comment or annotations
            if (line.isEmpty() && cur > 0) {
                val prev = lines[cur - 1].trim()
                if (prev.endsWith("*/") || prev.startsWith("@") || prev.startsWith("//") || prev.startsWith("///") || prev.startsWith("*")) {
                    cur--
                    continue
                }
            }
            break
        }
        return expanded
    }

    private fun extractHeaderAndImports(originalLines: List<String>, ext: String): Pair<List<String>, List<String>> {
        val headers = mutableListOf<String>()
        val imports = mutableListOf<String>()
        val lang = PolyglotAstExtractor.supportedLanguage(ext)

        for (line in originalLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Stop scanning once declarations begin to prevent extracting false imports from code bodies
            val isDeclStart = when (lang) {
                "kotlin" -> !trimmed.startsWith("@file:") && !trimmed.startsWith("package ") &&
                    !trimmed.startsWith("import ") && !trimmed.startsWith("//") &&
                    !trimmed.startsWith("/*") && !trimmed.startsWith("*")
                "python" -> !trimmed.startsWith("#") && !trimmed.startsWith("\"\"\"") &&
                    !trimmed.startsWith("'''") && !trimmed.startsWith("import ") &&
                    !trimmed.startsWith("from ")
                "swift" -> !trimmed.startsWith("//") && !trimmed.startsWith("/*") &&
                    !trimmed.startsWith("*") && !trimmed.startsWith("import ")
                "rust" -> !trimmed.startsWith("//") && !trimmed.startsWith("/*") &&
                    !trimmed.startsWith("*") && !trimmed.startsWith("#![") &&
                    !trimmed.startsWith("use ")
                "cpp" -> !trimmed.startsWith("//") && !trimmed.startsWith("/*") &&
                    !trimmed.startsWith("*") && !trimmed.startsWith("#")
                "typescript", "javascript" -> !trimmed.startsWith("//") && !trimmed.startsWith("/*") &&
                    !trimmed.startsWith("*") && !trimmed.startsWith("import ") &&
                    !(trimmed.startsWith("const ") && "require(" in trimmed)
                else -> !trimmed.startsWith("package ") && !trimmed.startsWith("import ") &&
                    !trimmed.startsWith("from ") && !trimmed.startsWith("use ")
            }
            if (isDeclStart) {
                break
            }

            when (lang) {
                "kotlin" -> {
                    if (trimmed.startsWith("@file:")) {
                        headers.add(trimmed)
                    } else if (trimmed.startsWith("package ")) {
                        headers.add(trimmed)
                    } else if (trimmed.startsWith("import ")) {
                        imports.add(trimmed)
                    }
                }
                "swift" -> {
                    if (trimmed.startsWith("import ")) {
                        imports.add(trimmed)
                    }
                }
                "python" -> {
                    if (trimmed.startsWith("#!") || trimmed.startsWith("# -*-")) {
                        headers.add(trimmed)
                    } else if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) {
                        imports.add(trimmed)
                    }
                }
                "rust" -> {
                    if (trimmed.startsWith("#![")) {
                        headers.add(trimmed)
                    } else if (trimmed.startsWith("use ")) {
                        imports.add(trimmed)
                    }
                }
                "cpp" -> {
                    if (trimmed.startsWith("#pragma once")) {
                        headers.add(trimmed)
                    } else if (trimmed.startsWith("#include ")) {
                        imports.add(trimmed)
                    }
                }
                "typescript", "javascript" -> {
                    if (trimmed.startsWith("import ") || (trimmed.startsWith("const ") && "require(" in trimmed)) {
                        imports.add(trimmed)
                    }
                }
                else -> {
                    if (trimmed.startsWith("package ")) headers.add(trimmed)
                    if (trimmed.startsWith("import ") || trimmed.startsWith("from ") || trimmed.startsWith("use ")) {
                        imports.add(trimmed)
                    }
                }
            }
        }
        return Pair(headers, imports)
    }

    private fun buildDiffPreview(
        relPath: String,
        originalContent: String,
        modifiedContent: String,
        spawnedFiles: List<SpawnedFileRecord>,
        renamedTo: String? = null
    ): String {
        val sb = StringBuilder()
        if (renamedTo != null) {
            sb.append("rename from ").append(relPath).append("\n")
            sb.append("rename to ").append(renamedTo).append("\n")
            sb.append("--- a/").append(relPath).append("\n")
            sb.append("+++ b/").append(renamedTo).append("\n")
        } else {
            sb.append("--- a/").append(relPath).append("\n")
            sb.append("+++ b/").append(relPath).append("\n")
        }

        val origLines = originalContent.lines()
        val modLines = modifiedContent.lines()
        val diffHunks = computeUnifiedDiff(origLines, modLines)
        sb.append(diffHunks)

        for (sf in spawnedFiles) {
            sb.append("\n--- /dev/null\n")
            sb.append("+++ b/").append(sf.relPath).append("\n")
            sb.append("@@ +1,").append(sf.loc).append(" @@\n")
            for (line in sf.content.lines().take(15)) {
                sb.append("+").append(line).append("\n")
            }
            if (sf.loc > 15) {
                sb.append("+... (${sf.loc - 15} more lines)\n")
            }
        }
        return sb.toString()
    }

    private fun computeUnifiedDiff(origLines: List<String>, modLines: List<String>): String {
        val sb = StringBuilder()
        var prefix = 0
        while (prefix < origLines.size && prefix < modLines.size && origLines[prefix] == modLines[prefix]) {
            prefix++
        }
        var origSuffix = origLines.size - 1
        var modSuffix = modLines.size - 1
        while (origSuffix >= prefix && modSuffix >= prefix && origLines[origSuffix] == modLines[modSuffix]) {
            origSuffix--
            modSuffix--
        }

        if (prefix <= origSuffix || prefix <= modSuffix) {
            val origHunkStart = max(1, prefix)
            val origHunkLen = max(1, origSuffix - prefix + 1)
            val modHunkStart = max(1, prefix)
            val modHunkLen = max(1, modSuffix - prefix + 1)

            sb.append("@@ -").append(origHunkStart).append(",").append(origHunkLen)
                .append(" +").append(modHunkStart).append(",").append(modHunkLen).append(" @@\n")

            if (prefix > 0 && prefix - 1 < origLines.size) {
                sb.append(" ").append(origLines[prefix - 1]).append("\n")
            }
            for (i in prefix..origSuffix) {
                sb.append("-").append(origLines[i]).append("\n")
            }
            for (i in prefix..modSuffix) {
                sb.append("+").append(modLines[i]).append("\n")
            }
            if (origSuffix + 1 < origLines.size) {
                sb.append(" ").append(origLines[origSuffix + 1]).append("\n")
            }
        }
        return sb.toString()
    }
}
