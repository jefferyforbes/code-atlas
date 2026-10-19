package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.extract.PolyglotAstExtractor
import atlas.model.ComplexityDoctorReport
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Autonomous Complexity Doctor Engine synthesizing deterministic complexity remediation
 * prescriptions (guard clause inversion and elvis guard simplification) with cognitive
 * complexity reduction measurement and multi-gate verification.
 */
class ComplexityDoctorEngine(
    private val store: SqliteGraphStore? = null,
    private val workspaceRoot: String = "."
) {

    fun runDoctor(
        projectId: String,
        target: String? = null,
        limit: Int = 10,
        minCognitive: Int = 10
    ): ComplexityDoctorReport {
        val candidatePrescriptions = mutableListOf<ComplexityDoctorReport.Prescription>()
        if (store == null) {
            return ComplexityDoctorReport(
                project = projectId,
                totalPrescriptions = 0,
                prescriptions = emptyList()
            )
        }

        val complexNodes = store.queryComplexNodes(projectId, minCognitive = minCognitive, limit = limit * 4)
            .let { list ->
                if (!target.isNullOrBlank()) {
                    list.filter {
                        it.nodeId == target || it.name == target || it.qualifiedName == target || it.filePath.contains(target)
                    }
                } else {
                    list
                }
            }

        val nodesByFile = complexNodes.groupBy { it.filePath }
        for ((filePath, nodes) in nodesByFile) {
            val root = PlatformFiles.canonicalPath(workspaceRoot).trimEnd('/')
            val fullPath = if (filePath.startsWith("/")) filePath else "$root/$filePath"
            if (!PlatformFiles.exists(fullPath)) continue
            val sourceText = try {
                PlatformFiles.readText(fullPath)
            } catch (_: Exception) {
                continue
            }

            val ext = if (filePath.contains('.')) "." + filePath.substringAfterLast('.') else ""
            val lang = PolyglotAstExtractor.supportedLanguage(ext)
            val preExtractedNodes = if (lang.isNotEmpty()) {
                try {
                    PolyglotAstExtractor.extractFile(projectId, filePath, sourceText).first
                } catch (_: Exception) {
                    null
                }
            } else null

            for (node in nodes) {
                val prescriptions = analyzeSource(
                    filePath = filePath,
                    sourceText = sourceText,
                    nodeId = node.nodeId,
                    inDegree = node.inDegree,
                    projectId = projectId,
                    preExtractedNodes = preExtractedNodes
                )
                candidatePrescriptions.addAll(prescriptions)
            }
        }

        val sorted = candidatePrescriptions
            .sortedWith(
                compareByDescending<ComplexityDoctorReport.Prescription> { it.priorityScore }
                    .thenBy { it.filePath }
                    .thenBy { it.line }
            )
            .take(limit.coerceAtLeast(1))
            .mapIndexed { index, p ->
                p.copy(rank = index + 1)
            }

        return ComplexityDoctorReport(
            project = projectId,
            totalPrescriptions = sorted.size,
            prescriptions = sorted
        )
    }

    fun analyzeSource(
        filePath: String,
        sourceText: String,
        nodeId: String = "",
        inDegree: Int = 0,
        projectId: String = "default",
        preExtractedNodes: List<GraphNode>? = null
    ): List<ComplexityDoctorReport.Prescription> {
        val ext = if (filePath.contains('.')) "." + filePath.substringAfterLast('.') else ""
        val lang = PolyglotAstExtractor.supportedLanguage(ext)
        if (lang.isEmpty()) return emptyList()

        val nodes = preExtractedNodes ?: try {
            PolyglotAstExtractor.extractFile(projectId, filePath, sourceText).first
        } catch (_: Exception) {
            return emptyList()
        }

        val candidatePrescriptions = mutableListOf<ComplexityDoctorReport.Prescription>()

        val funcNodes = nodes.filter { it.kind in setOf("function", "method", "composable") }
        val sym = if (nodeId.contains('#')) nodeId.substringAfter('#') else nodeId
        val simpleSym = sym.substringAfterLast('.')
        for (func in funcNodes) {
            val matches = nodeId.isEmpty() ||
                func.nodeId == nodeId ||
                func.name == nodeId ||
                func.qualifiedName == nodeId ||
                (sym.isNotEmpty() && (func.nodeId.substringAfter('#') == sym || func.name == sym || func.name == simpleSym || func.qualifiedName.endsWith(sym)))
            if (!matches) {
                continue
            }

            // 1. Guard Clause Inversion
            val guardPrescription = synthesizeGuardInversion(
                filePath = filePath,
                sourceText = sourceText,
                node = func,
                inDegree = inDegree,
                lang = lang,
                projectId = projectId
            )
            if (guardPrescription != null) {
                candidatePrescriptions.add(guardPrescription)
            }

            // 2. Elvis Guard Simplification
            val elvisPrescription = synthesizeElvisGuard(
                filePath = filePath,
                sourceText = sourceText,
                node = func,
                inDegree = inDegree,
                lang = lang,
                projectId = projectId
            )
            if (elvisPrescription != null) {
                candidatePrescriptions.add(elvisPrescription)
            }
        }

        return candidatePrescriptions
    }

    fun applyRemediations(
        report: ComplexityDoctorReport,
        dryRun: Boolean = true,
        testCommand: String? = null
    ): ComplexityDoctorReport {
        val verifier = AutonomousRemediationVerifier(workspaceRoot = workspaceRoot, store = store)
        var verified = 0
        var rolledBack = 0

        val updatedPrescriptions = mutableListOf<ComplexityDoctorReport.Prescription>()
        for (p in report.prescriptions) {
            val result = verifier.verifyPrescription(
                projectSlug = report.project,
                prescription = p,
                dryRun = dryRun,
                testCommand = testCommand
            )
            if (result.valid) {
                verified++
                updatedPrescriptions.add(p)
            } else {
                rolledBack++
            }
        }

        return report.copy(
            verifiedCount = verified,
            rolledBackCount = rolledBack,
            prescriptions = updatedPrescriptions
        )
    }

    private fun synthesizeGuardInversion(
        filePath: String,
        sourceText: String,
        node: GraphNode,
        inDegree: Int,
        lang: String,
        projectId: String
    ): ComplexityDoctorReport.Prescription? {
        val lines = sourceText.lines()
        val startIdx = (node.startLine - 1).coerceAtLeast(0)
        val endIdx = (node.endLine - 1).coerceAtMost(lines.size - 1)
        if (startIdx >= endIdx || startIdx >= lines.size) return null

        var bodyStart = startIdx
        while (bodyStart <= endIdx && !lines[bodyStart].contains('{')) {
            bodyStart++
        }
        if (bodyStart > endIdx) return null

        var ifLineIdx = -1
        var ifIndent = ""
        var rawCondition = ""
        val ifRegex = Regex("""^(\s*)if\s*\((.+)\)\s*\{(?:\s*//.*)?$""")

        for (i in (bodyStart + 1)..endIdx) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                continue
            }
            val m = ifRegex.find(lines[i])
            if (m != null) {
                ifLineIdx = i
                ifIndent = m.groupValues[1]
                rawCondition = m.groupValues[2].trim()
                break
            }
            // Allow skipping variable definitions/declarations before the outer if condition
            if (trimmed.startsWith("val ") || trimmed.startsWith("var ") ||
                trimmed.startsWith("let ") || trimmed.startsWith("const ") ||
                trimmed.startsWith("final ") || trimmed.startsWith("auto ")
            ) {
                continue
            }
            break
        }

        if (ifLineIdx < 0 || rawCondition.isEmpty()) return null

        var depth = 0
        var closeIfIdx = -1
        for (i in ifLineIdx..endIdx) {
            val masked = PolyglotAstExtractor.maskCommentsAndStrings(lines[i], lang)
            depth += masked.count { it == '{' } - masked.count { it == '}' }
            if (depth == 0) {
                closeIfIdx = i
                break
            }
        }
        if (closeIfIdx < 0 || closeIfIdx <= ifLineIdx) return null

        var trailingReturnIdx = -1
        var defaultReturn = "return"
        val retRegex = Regex("""^\s*return(?:\s+([^;}\n]+))?;?\s*$""")

        // Check if there is an else block immediately after the if block
        var elseEndIdx = -1
        val elseRegex = Regex("""^\s*\}\s*else\s*\{(?:\s*return(?:\s+([^;}\n]+))?;?\s*\})?\s*$""")
        val elseStartRegex = Regex("""^\s*\}\s*else\s*\{""")

        val closeIfLine = lines[closeIfIdx]
        val elseMatch = elseRegex.find(closeIfLine)
        if (elseMatch != null) {
            val retExpr = elseMatch.groupValues.getOrNull(1)?.trim() ?: ""
            defaultReturn = if (retExpr.isNotEmpty()) "return $retExpr" else "return"
            elseEndIdx = closeIfIdx
        } else if (elseStartRegex.find(closeIfLine) != null) {
            var elseDepth = 1
            for (i in (closeIfIdx + 1)..endIdx) {
                val rMatch = retRegex.find(lines[i])
                if (rMatch != null) {
                    val retExpr = rMatch.groupValues.getOrNull(1)?.trim() ?: ""
                    defaultReturn = if (retExpr.isNotEmpty()) "return $retExpr" else "return"
                }
                val masked = PolyglotAstExtractor.maskCommentsAndStrings(lines[i], lang)
                elseDepth += masked.count { it == '{' } - masked.count { it == '}' }
                if (elseDepth == 0) {
                    elseEndIdx = i
                    break
                }
            }
        }

        val spanEndIdx = if (elseEndIdx > 0) {
            elseEndIdx
        } else {
            var candidateRetIdx = -1
            var hasTrailingStatements = false
            for (i in (closeIfIdx + 1)..endIdx) {
                val trimmed = lines[i].trim()
                if (trimmed.isEmpty() || trimmed.startsWith("//")) continue
                val rMatch = retRegex.find(lines[i])
                if (rMatch != null) {
                    candidateRetIdx = i
                    val retExpr = rMatch.groupValues.getOrNull(1)?.trim() ?: ""
                    defaultReturn = if (retExpr.isNotEmpty()) "return $retExpr" else "return"
                } else if (trimmed == "}") {
                    break
                } else {
                    hasTrailingStatements = true
                    break
                }
            }
            if (hasTrailingStatements) return null
            if (candidateRetIdx > 0) candidateRetIdx else closeIfIdx
        }

        val invertedCond = invertCondition(rawCondition)
        val innerLines = lines.subList(ifLineIdx + 1, closeIfIdx)
        if (innerLines.isEmpty()) return null

        val firstNonBlankInner = innerLines.firstOrNull { it.isNotBlank() } ?: ""
        val innerIndent = firstNonBlankInner.takeWhile { it.isWhitespace() }
        val indentToRemove = if (innerIndent.startsWith(ifIndent) && innerIndent.length > ifIndent.length) {
            innerIndent.substring(ifIndent.length)
        } else {
            "    "
        }

        val unindentedInner = innerLines.map { l ->
            if (l.startsWith(ifIndent + indentToRemove)) {
                ifIndent + l.substring(ifIndent.length + indentToRemove.length)
            } else if (l.startsWith(indentToRemove)) {
                l.substring(indentToRemove.length)
            } else {
                l.trimStart()
            }
        }

        val beforeSnippet = lines.subList(ifLineIdx, spanEndIdx + 1).joinToString("\n")
        val afterLines = mutableListOf<String>()
        afterLines.add("${ifIndent}if ($invertedCond) $defaultReturn")
        afterLines.addAll(unindentedInner)
        val afterSnippet = afterLines.joinToString("\n")

        if (countOccurrences(sourceText, beforeSnippet) != 1) return null

        val candidateText = sourceText.replace(beforeSnippet, afterSnippet)
        val maskedCandidate = PolyglotAstExtractor.maskCommentsAndStrings(candidateText, lang)
        if (!hasBalancedDelimiters(maskedCandidate)) return null

        val modExtracted = try {
            PolyglotAstExtractor.extractFile(projectId, filePath, candidateText)
        } catch (_: Exception) {
            return null
        }

        val modNode = modExtracted.first
            .filter { it.kind in setOf("function", "method", "composable") && it.name == node.name }
            .minByOrNull { kotlin.math.abs(it.startLine - node.startLine) }
            ?: return null
        val cogBefore = node.cognitiveComplexity
        val cogAfter = modNode.cognitiveComplexity
        val diff = cogBefore - cogAfter
        val reductionPct = if (cogBefore > 0) (diff.toDouble() / cogBefore.toDouble()) * 100.0 else 0.0
        val roundedPct = (reductionPct * 10.0).roundToInt() / 10.0

        if (diff < 1) return null

        val inDeg = inDegree.coerceAtLeast(node.inDegree).toDouble()
        val riskIndex = cogBefore.toDouble() * (1.0 + ln(1.0 + inDeg))
        val priorityScore = (riskIndex * (roundedPct / 100.0) * 10.0).roundToInt() / 10.0

        return ComplexityDoctorReport.Prescription(
            rank = 0,
            priorityScore = priorityScore,
            ruleId = "COMPLEXITY-GUARD-INVERT",
            fixStrategy = "GUARD_INVERSION",
            writeSiteSafe = true,
            targetNodeId = node.nodeId,
            filePath = filePath,
            line = ifLineIdx + 1,
            cognitiveBefore = cogBefore,
            cognitiveAfter = cogAfter,
            reductionPercent = roundedPct,
            rationale = "Inverting outer conditional guard flattens function body nesting, reducing cognitive complexity from $cogBefore to $cogAfter ($roundedPct% reduction).",
            beforeSnippet = beforeSnippet,
            afterSnippet = afterSnippet
        )
    }

    private fun synthesizeElvisGuard(
        filePath: String,
        sourceText: String,
        node: GraphNode,
        inDegree: Int,
        lang: String,
        projectId: String
    ): ComplexityDoctorReport.Prescription? {
        val lines = sourceText.lines()
        val startIdx = (node.startLine - 1).coerceAtLeast(0)
        val endIdx = (node.endLine - 1).coerceAtMost(lines.size - 1)
        if (startIdx >= endIdx || startIdx >= lines.size) return null

        val declRegex = Regex("""^(\s*)(?:val|var)\s+([A-Za-z0-9_]+)\s*=\s*(.+)$""")

        for (i in startIdx until endIdx) {
            val dMatch = declRegex.find(lines[i]) ?: continue
            val indent = dMatch.groupValues[1]
            val varName = dMatch.groupValues[2]
            val initExpr = dMatch.groupValues[3].trim()

            // Find next non-blank line
            var nextIdx = -1
            for (j in (i + 1)..endIdx) {
                if (lines[j].isNotBlank()) {
                    nextIdx = j
                    break
                }
            }
            if (nextIdx < 0) continue

            val singleLineIf = Regex("""^\s*if\s*\(\s*${Regex.escape(varName)}\s*==\s*null\s*\)\s*(?:\{\s*return(?:\s+([^;}\n]+))?;?\s*\}|return(?:\s+([^;}\n]+))?;?)\s*$""")
            val sm = singleLineIf.find(lines[nextIdx])

            var spanEnd = nextIdx
            var retExpr = ""

            if (sm != null) {
                retExpr = (sm.groupValues.getOrNull(1)?.ifEmpty { null }
                    ?: sm.groupValues.getOrNull(2))?.trim() ?: ""
            } else {
                val multiLineIfStart = Regex("""^\s*if\s*\(\s*${Regex.escape(varName)}\s*==\s*null\s*\)\s*\{$""")
                if (multiLineIfStart.find(lines[nextIdx]) != null && nextIdx + 2 <= endIdx) {
                    val retRegex = Regex("""^\s*return(?:\s+([^;}\n]+))?;?\s*$""")
                    val rm = retRegex.find(lines[nextIdx + 1])
                    if (rm != null && lines[nextIdx + 2].trim() == "}") {
                        retExpr = rm.groupValues.getOrNull(1)?.trim() ?: ""
                        spanEnd = nextIdx + 2
                    } else {
                        continue
                    }
                } else {
                    continue
                }
            }

            val elvisRet = if (retExpr.isNotEmpty()) " ?: return $retExpr" else " ?: return"
            val beforeSnippet = lines.subList(i, spanEnd + 1).joinToString("\n")
            val afterSnippet = "${indent}val $varName = $initExpr$elvisRet"

            if (countOccurrences(sourceText, beforeSnippet) != 1) continue

            val candidateText = sourceText.replace(beforeSnippet, afterSnippet)
            val maskedCandidate = PolyglotAstExtractor.maskCommentsAndStrings(candidateText, lang)
            if (!hasBalancedDelimiters(maskedCandidate)) continue

            val modExtracted = try {
                PolyglotAstExtractor.extractFile(projectId, filePath, candidateText)
            } catch (_: Exception) {
                continue
            }

            val modNode = modExtracted.first
                .filter { it.kind in setOf("function", "method", "composable") && it.name == node.name }
                .minByOrNull { kotlin.math.abs(it.startLine - node.startLine) }
                ?: continue
            val cogBefore = node.cognitiveComplexity
            val cogAfter = modNode.cognitiveComplexity
            val diff = cogBefore - cogAfter
            val reductionPct = if (cogBefore > 0) (diff.toDouble() / cogBefore.toDouble()) * 100.0 else 0.0
            val roundedPct = (reductionPct * 10.0).roundToInt() / 10.0

            if (diff < 1) continue

            val inDeg = inDegree.coerceAtLeast(node.inDegree).toDouble()
            val riskIndex = cogBefore.toDouble() * (1.0 + ln(1.0 + inDeg))
            val priorityScore = (riskIndex * (roundedPct / 100.0) * 10.0).roundToInt() / 10.0

            return ComplexityDoctorReport.Prescription(
                rank = 0,
                priorityScore = priorityScore,
                ruleId = "COMPLEXITY-ELVIS-GUARD",
                fixStrategy = "ELVIS_GUARD",
                writeSiteSafe = true,
                targetNodeId = node.nodeId,
                filePath = filePath,
                line = i + 1,
                cognitiveBefore = cogBefore,
                cognitiveAfter = cogAfter,
                reductionPercent = roundedPct,
                rationale = "Replacing explicit null check with Elvis return operator flattens decision branching, reducing cognitive complexity from $cogBefore to $cogAfter ($roundedPct% reduction).",
                beforeSnippet = beforeSnippet,
                afterSnippet = afterSnippet
            )
        }

        return null
    }

    private fun invertCondition(cond: String): String {
        var trimmed = cond.trim()
        while (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            var depth = 0
            var balancedMatch = false
            for (idx in trimmed.indices) {
                if (trimmed[idx] == '(') depth++
                else if (trimmed[idx] == ')') {
                    depth--
                    if (depth == 0) {
                        balancedMatch = (idx == trimmed.length - 1)
                        break
                    }
                }
            }
            if (balancedMatch) {
                trimmed = trimmed.substring(1, trimmed.length - 1).trim()
            } else {
                break
            }
        }

        if (trimmed.startsWith("!(") && trimmed.endsWith(")")) {
            var depth = 0
            var balancedMatch = false
            for (idx in 1 until trimmed.length) {
                if (trimmed[idx] == '(') depth++
                else if (trimmed[idx] == ')') {
                    depth--
                    if (depth == 0) {
                        balancedMatch = (idx == trimmed.length - 1)
                        break
                    }
                }
            }
            if (balancedMatch) {
                return trimmed.substring(2, trimmed.length - 1).trim()
            }
        }
        if (trimmed.startsWith("!")) {
            val rest = trimmed.substring(1).trim()
            if (rest.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
                return rest
            }
        }

        val topOrs = findTopLevelOperatorPositions(trimmed, "||")
        if (topOrs.isNotEmpty()) {
            val parts = splitAtIndices(trimmed, topOrs, 2)
            return parts.joinToString(" && ") { part ->
                val inv = invertCondition(part)
                if (findTopLevelOperatorPositions(inv, "||").isNotEmpty()) "($inv)" else inv
            }
        }

        val topAnds = findTopLevelOperatorPositions(trimmed, "&&")
        if (topAnds.isNotEmpty()) {
            val parts = splitAtIndices(trimmed, topAnds, 2)
            return parts.joinToString(" || ") { invertCondition(it) }
        }

        val eq3Pos = findTopLevelOperatorPositions(trimmed, " === ")
        if (eq3Pos.size == 1) {
            val left = trimmed.substring(0, eq3Pos[0]).trim()
            val right = trimmed.substring(eq3Pos[0] + 5).trim()
            return "$left !== $right"
        }
        val neq3Pos = findTopLevelOperatorPositions(trimmed, " !== ")
        if (neq3Pos.size == 1) {
            val left = trimmed.substring(0, neq3Pos[0]).trim()
            val right = trimmed.substring(neq3Pos[0] + 5).trim()
            return "$left === $right"
        }

        val eqPos = findTopLevelOperatorPositions(trimmed, " == ")
        if (eqPos.size == 1) {
            val left = trimmed.substring(0, eqPos[0]).trim()
            val right = trimmed.substring(eqPos[0] + 4).trim()
            return "$left != $right"
        }
        val neqPos = findTopLevelOperatorPositions(trimmed, " != ")
        if (neqPos.size == 1) {
            val left = trimmed.substring(0, neqPos[0]).trim()
            val right = trimmed.substring(neqPos[0] + 4).trim()
            return "$left == $right"
        }

        val ltePos = findTopLevelOperatorPositions(trimmed, " <= ")
        if (ltePos.size == 1) {
            val left = trimmed.substring(0, ltePos[0]).trim()
            val right = trimmed.substring(ltePos[0] + 4).trim()
            return "$left > $right"
        }
        val gtePos = findTopLevelOperatorPositions(trimmed, " >= ")
        if (gtePos.size == 1) {
            val left = trimmed.substring(0, gtePos[0]).trim()
            val right = trimmed.substring(gtePos[0] + 4).trim()
            return "$left < $right"
        }
        val ltPos = findTopLevelOperatorPositions(trimmed, " < ")
        if (ltPos.size == 1) {
            val left = trimmed.substring(0, ltPos[0]).trim()
            val right = trimmed.substring(ltPos[0] + 3).trim()
            return "$left >= $right"
        }
        val gtPos = findTopLevelOperatorPositions(trimmed, " > ")
        if (gtPos.size == 1) {
            val left = trimmed.substring(0, gtPos[0]).trim()
            val right = trimmed.substring(gtPos[0] + 3).trim()
            return "$left <= $right"
        }

        if (trimmed.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
            return "!$trimmed"
        }
        return "!($trimmed)"
    }

    private fun findTopLevelOperatorPositions(text: String, op: String): List<Int> {
        val positions = mutableListOf<Int>()
        var depth = 0
        var i = 0
        while (i <= text.length - op.length) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                else -> {
                    if (depth == 0 && text.startsWith(op, i)) {
                        positions.add(i)
                        i += op.length
                        continue
                    }
                }
            }
            i++
        }
        return positions
    }

    private fun splitAtIndices(text: String, indices: List<Int>, opLen: Int): List<String> {
        val parts = mutableListOf<String>()
        var last = 0
        for (pos in indices) {
            parts.add(text.substring(last, pos).trim())
            last = pos + opLen
        }
        parts.add(text.substring(last).trim())
        return parts
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
}
