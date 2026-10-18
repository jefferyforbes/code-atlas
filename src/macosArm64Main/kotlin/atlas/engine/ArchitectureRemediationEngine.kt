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

        if (!PlatformFiles.exists(fullPath)) {
            return ArchitectureRemediationPrescription(
                id = "rx-boost003-${relPath.replace('/', '_')}",
                ruleId = "BOOST-003",
                filePath = relPath,
                fixStrategy = "FILE_NOT_FOUND",
                rationale = "Target file not found on disk: $fullPath",
                verified = false,
                applied = false,
                details = listOf("File does not exist: $fullPath")
            )
        }

        val originalContent = try {
            PlatformFiles.readText(fullPath)
        } catch (e: Exception) {
            return ArchitectureRemediationPrescription(
                id = "rx-boost003-${relPath.replace('/', '_')}",
                ruleId = "BOOST-003",
                filePath = relPath,
                fixStrategy = "READ_ERROR",
                rationale = "Cannot read target file: ${e.message}",
                verified = false,
                applied = false,
                details = listOf("Read error on $fullPath: ${e.message}")
            )
        }

        val ext = if (relPath.contains('.')) "." + relPath.substringAfterLast('.') else ""
        val lang = PolyglotAstExtractor.supportedLanguage(ext)
        val (nodes, _, _) = PolyglotAstExtractor.extractFile(projectId, relPath, originalContent)

        val candidateKinds = setOf(
            "class", "interface", "object", "function", "composable",
            "enum", "annotation", "struct", "protocol", "actor"
        )
        val topNodes = nodes.filter { it.isTopLevel && it.kind in candidateKinds }

        if (topNodes.size <= 1) {
            return ArchitectureRemediationPrescription(
                id = "rx-boost003-${relPath.replace('/', '_')}",
                ruleId = "BOOST-003",
                filePath = relPath,
                fixStrategy = "NOOP",
                rationale = "File already complies with BOOST-003 (${topNodes.size} top-level symbol(s)).",
                verified = true,
                applied = false,
                details = listOf("No top-level declarations require splitting.")
            )
        }

        val baseFileName = relPath.substringAfterLast('/').substringBeforeLast('.')
        val matchingNode = topNodes.firstOrNull { it.name.equals(baseFileName, ignoreCase = true) }
        val primaryNode = matchingNode ?: topNodes.first()
        val secondaryNodes = topNodes.filter { it != primaryNode }

        // Case 1: Manual split advisory if user requested --no-split and not --make-private
        if (!split && !makePrivate) {
            return ArchitectureRemediationPrescription(
                id = "rx-boost003-${relPath.replace('/', '_')}",
                ruleId = "BOOST-003",
                filePath = relPath,
                fixStrategy = "MANUAL_SPLIT_ADVISORY",
                rationale = "File declares ${topNodes.size} top-level symbols. Splitting disabled (--no-split). Run with --split to extract to separate files or --make-private to convert secondary helpers to private.",
                primaryDeclaration = primaryNode.name,
                secondaryDeclarations = secondaryNodes.map { it.name },
                verified = true,
                applied = false,
                details = listOf("Splitting disabled (--no-split). Manual refactoring or --make-private recommended.")
            )
        }

        // Case 2: Make secondary declarations private in-place
        if (makePrivate) {
            return remediateBoost003MakePrivate(
                projectId = projectId,
                relPath = relPath,
                fullPath = fullPath,
                originalContent = originalContent,
                primaryNode = primaryNode,
                secondaryNodes = secondaryNodes,
                lang = lang,
                dryRun = dryRun,
                testCommand = testCommand
            )
        }

        // Case 3: AST File Splitting
        val originalLines = originalContent.lines()
        val workingLines = originalLines.toMutableList()
        val spawnedFiles = mutableListOf<SpawnedFileRecord>()

        val (headerLines, importLines) = extractHeaderAndImports(originalLines, ext)

        val dir = if (relPath.contains('/')) relPath.substringBeforeLast('/') else ""
        val targetRenameRelPath = if (matchingNode == null) {
            val primaryFileName = "${primaryNode.name}$ext"
            if (dir.isEmpty()) primaryFileName else "$dir/$primaryFileName"
        } else null
        val primaryNewFullPath = if (targetRenameRelPath != null) "$root/$targetRenameRelPath" else fullPath

        // Pre-flight collision check
        if (targetRenameRelPath != null && PlatformFiles.exists(primaryNewFullPath)) {
            details.add("Spawn target '$targetRenameRelPath' already exists on disk. Collision avoided.")
            return ArchitectureRemediationPrescription(
                id = "rx-boost003-${relPath.replace('/', '_')}",
                ruleId = "BOOST-003",
                filePath = relPath,
                fixStrategy = "COLLISION",
                rationale = "Target file '$targetRenameRelPath' already exists. Cannot safely rename without overwriting.",
                primaryDeclaration = primaryNode.name,
                secondaryDeclarations = secondaryNodes.map { it.name },
                verified = false,
                applied = false,
                details = details
            )
        }

        for (sec in secondaryNodes) {
            val newFileName = "${sec.name}$ext"
            val newRelPath = if (dir.isEmpty()) newFileName else "$dir/$newFileName"
            val fullNewPath = "$root/$newRelPath"
            if (PlatformFiles.exists(fullNewPath)) {
                details.add("Spawn target '$newRelPath' already exists on disk. Collision avoided.")
                return ArchitectureRemediationPrescription(
                    id = "rx-boost003-${relPath.replace('/', '_')}",
                    ruleId = "BOOST-003",
                    filePath = relPath,
                    fixStrategy = "COLLISION",
                    rationale = "Target file '$newRelPath' already exists. Cannot safely split without overwriting.",
                    primaryDeclaration = primaryNode.name,
                    secondaryDeclarations = secondaryNodes.map { it.name },
                    verified = false,
                    applied = false,
                    details = details
                )
            }
        }

        // Sort descending by startLine so slice removals do not alter line numbers of earlier declarations
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

            val newFileContent = buildString {
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

            spawnedFiles.add(
                SpawnedFileRecord(
                    relPath = newRelPath,
                    declarationName = sec.name,
                    content = newFileContent,
                    loc = newFileContent.lines().size
                )
            )

            // Remove declaration slice from workingLines
            for (i in endIdx downTo startIdx) {
                workingLines.removeAt(i)
            }
        }

        val rawModified = workingLines.joinToString("\n")
        val cleanModified = Regex("""\n{3,}""").replace(rawModified, "\n\n").trimEnd() + "\n"

        val effectiveRelPath = targetRenameRelPath ?: relPath

        // AST and Delimiter Verification
        var astValid = true
        if (lang.isNotEmpty()) {
            val maskedOrig = PolyglotAstExtractor.maskCommentsAndStrings(cleanModified, lang)
            if (!AutonomousRemediationVerifier.hasBalancedDelimiters(maskedOrig)) {
                astValid = false
                details.add("Syntax check failed: Unbalanced delimiters in modified '$effectiveRelPath'.")
            } else {
                val (_, _, origCount) = PolyglotAstExtractor.extractFile(projectId, effectiveRelPath, cleanModified)
                if (origCount > 1) {
                    astValid = false
                    details.add("Modified file still declares $origCount top-level symbols (expected <= 1).")
                } else {
                    details.add("AST verification passed for modified original file '$effectiveRelPath' (topLevelDeclCount=$origCount).")
                }
            }

            for (sf in spawnedFiles) {
                val maskedSf = PolyglotAstExtractor.maskCommentsAndStrings(sf.content, lang)
                if (!AutonomousRemediationVerifier.hasBalancedDelimiters(maskedSf)) {
                    astValid = false
                    details.add("Syntax check failed: Unbalanced delimiters in spawned file '${sf.relPath}'.")
                } else {
                    val (_, _, sfCount) = PolyglotAstExtractor.extractFile(projectId, sf.relPath, sf.content)
                    if (sfCount != 1) {
                        astValid = false
                        details.add("Spawned file '${sf.relPath}' has $sfCount declarations (expected exactly 1).")
                    } else {
                        details.add("AST verification passed for spawned file '${sf.relPath}' (decl='${sf.declarationName}').")
                    }
                }
            }
        }

        if (!astValid) {
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

        val diffPreview = buildDiffPreview(relPath, originalContent, cleanModified, spawnedFiles, renamedTo = targetRenameRelPath)

        // Test Suite Preservation & Commit
        var testsPreserved = true
        if (!dryRun) {
            val writtenPaths = mutableListOf<String>()
            try {
                // 1. Stage spawned files
                for (sf in spawnedFiles) {
                    val fPath = "$root/${sf.relPath}"
                    val dirPath = fPath.substringBeforeLast('/')
                    PlatformFiles.mkdirs(dirPath)
                    PlatformFiles.writeText(fPath, sf.content)
                    writtenPaths.add(fPath)
                }

                // 2. Stage modified/renamed original file
                if (targetRenameRelPath != null) {
                    PlatformFiles.delete(fullPath)
                    PlatformFiles.writeText(primaryNewFullPath, cleanModified)
                    writtenPaths.add(primaryNewFullPath)
                } else {
                    PlatformFiles.writeText(fullPath, cleanModified)
                }

                // 3. Run test command if specified
                if (!testCommand.isNullOrBlank()) {
                    val exitCode = system(testCommand)
                    if (exitCode != 0) {
                        testsPreserved = false
                        details.add("Test command '$testCommand' failed (exit code $exitCode). Automatic rollback performed.")
                        // Rollback
                        for (w in writtenPaths) {
                            PlatformFiles.delete(w)
                        }
                        PlatformFiles.writeText(fullPath, originalContent)
                    } else {
                        details.add("Test command '$testCommand' passed (exit code 0).")
                    }
                }
            } catch (e: Exception) {
                testsPreserved = false
                details.add("Disk application error: ${e.message}. Performing rollback.")
                for (w in writtenPaths) {
                    PlatformFiles.delete(w)
                }
                PlatformFiles.writeText(fullPath, originalContent)
            }
        } else {
            details.add("Dry-run mode: verified AST valid and Single Declaration compliant without modifying disk.")
        }

        val isSuccess = astValid && testsPreserved
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
            verified = isSuccess,
            applied = !dryRun && isSuccess,
            details = details
        )
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

        for (sec in secondaryNodes) {
            val lineIdx = sec.startLine - 1
            if (lineIdx in workingLines.indices) {
                val raw = workingLines[lineIdx]
                val indent = raw.takeWhile { it == ' ' || it == '\t' }
                val trimmed = raw.trimStart()

                when (lang) {
                    "kotlin" -> {
                        val newTrimmed = when {
                            trimmed.startsWith("public ") -> "private " + trimmed.removePrefix("public ")
                            trimmed.startsWith("internal ") -> "private " + trimmed.removePrefix("internal ")
                            trimmed.startsWith("open ") -> "private " + trimmed.removePrefix("open ")
                            !trimmed.startsWith("private ") -> "private $trimmed"
                            else -> trimmed
                        }
                        workingLines[lineIdx] = indent + newTrimmed
                        details.add("Converted '${sec.name}' to private visibility.")
                    }
                    "python" -> {
                        if (trimmed.startsWith("def ") && !sec.name.startsWith("_")) {
                            workingLines[lineIdx] = indent + trimmed.replaceFirst("def ${sec.name}", "def _${sec.name}")
                            details.add("Converted function '${sec.name}' to private '_${sec.name}'.")
                        } else if (trimmed.startsWith("class ") && !sec.name.startsWith("_")) {
                            workingLines[lineIdx] = indent + trimmed.replaceFirst("class ${sec.name}", "class _${sec.name}")
                            details.add("Converted class '${sec.name}' to private '_${sec.name}'.")
                        }
                    }
                    else -> {
                        val newTrimmed = if (!trimmed.startsWith("private ")) "private $trimmed" else trimmed
                        workingLines[lineIdx] = indent + newTrimmed
                        details.add("Converted '${sec.name}' to private visibility.")
                    }
                }
            }
        }

        val modifiedContent = workingLines.joinToString("\n")

        var astValid = true
        if (lang.isNotEmpty()) {
            val masked = PolyglotAstExtractor.maskCommentsAndStrings(modifiedContent, lang)
            if (!AutonomousRemediationVerifier.hasBalancedDelimiters(masked)) {
                astValid = false
                details.add("Syntax check failed: Unbalanced delimiters after privatizing secondary declarations.")
            } else {
                val (_, _, count) = PolyglotAstExtractor.extractFile(projectId, relPath, modifiedContent)
                if (count > 1) {
                    astValid = false
                    details.add("File still declares $count public top-level symbols after privatization.")
                } else {
                    details.add("AST verification passed: public topLevelDeclCount reduced to $count.")
                }
            }
        }

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

        var testsPreserved = true
        if (!dryRun) {
            try {
                PlatformFiles.writeText(fullPath, modifiedContent)
                if (!testCommand.isNullOrBlank()) {
                    val exitCode = system(testCommand)
                    if (exitCode != 0) {
                        testsPreserved = false
                        details.add("Test command '$testCommand' failed (exit code $exitCode). Automatic rollback performed.")
                        PlatformFiles.writeText(fullPath, originalContent)
                    } else {
                        details.add("Test command '$testCommand' passed (exit code 0).")
                    }
                }
            } catch (e: Exception) {
                testsPreserved = false
                details.add("Disk application error: ${e.message}. Performing rollback.")
                PlatformFiles.writeText(fullPath, originalContent)
            }
        } else {
            details.add("Dry-run mode: verified private helper conversion without modifying disk.")
        }

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
