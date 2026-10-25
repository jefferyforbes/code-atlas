package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.extract.PolyglotAstExtractor
import atlas.model.ComplexityDoctorReport
import atlas.model.ComplexityScoreRecord
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

    private fun filterTargetNodes(nodes: List<ComplexityScoreRecord>, target: String?): List<ComplexityScoreRecord> {
        if (target.isNullOrBlank()) return nodes
        return nodes.filter {
            it.nodeId == target || it.name == target || it.qualifiedName == target || it.filePath.contains(target)
        }
    }

    private fun resolveSourceText(filePath: String): String? {
        val root = PlatformFiles.canonicalPath(workspaceRoot).trimEnd('/')
        val fullPath = if (filePath.startsWith("/")) filePath else "$root/$filePath"
        if (!PlatformFiles.exists(fullPath)) return null
        return try {
            PlatformFiles.readText(fullPath)
        } catch (_: Exception) {
            null
        }
    }

    private fun preExtractFileNodes(projectId: String, filePath: String, sourceText: String): List<GraphNode>? {
        val ext = if (filePath.contains('.')) "." + filePath.substringAfterLast('.') else ""
        val lang = PolyglotAstExtractor.supportedLanguage(ext)
        if (lang.isEmpty()) return null
        return try {
            PolyglotAstExtractor.extractFile(projectId, filePath, sourceText).first
        } catch (_: Exception) {
            null
        }
    }

    private fun collectFilePrescriptions(
        projectId: String,
        filePath: String,
        nodes: List<ComplexityScoreRecord>
    ): List<ComplexityDoctorReport.Prescription> {
        val sourceText = resolveSourceText(filePath) ?: return emptyList()
        val preExtractedNodes = preExtractFileNodes(projectId, filePath, sourceText)
        val filePrescriptions = mutableListOf<ComplexityDoctorReport.Prescription>()
        for (node in nodes) {
            val prescriptions = analyzeSource(
                filePath = filePath,
                sourceText = sourceText,
                nodeId = node.nodeId,
                inDegree = node.inDegree,
                projectId = projectId,
                preExtractedNodes = preExtractedNodes
            )
            filePrescriptions.addAll(prescriptions)
        }
        return filePrescriptions
    }

    private fun rankPrescriptions(
        prescriptions: List<ComplexityDoctorReport.Prescription>,
        limit: Int
    ): List<ComplexityDoctorReport.Prescription> {
        return prescriptions
            .sortedWith(
                compareByDescending<ComplexityDoctorReport.Prescription> { it.priorityScore }
                    .thenBy { it.filePath }
                    .thenBy { it.line }
            )
            .take(limit.coerceAtLeast(1))
            .mapIndexed { index, p ->
                p.copy(rank = index + 1)
            }
    }

    fun runDoctor(
        projectId: String,
        target: String? = null,
        limit: Int = 10,
        minCognitive: Int = 10
    ): ComplexityDoctorReport {
        if (store == null) {
            return ComplexityDoctorReport(
                project = projectId,
                totalPrescriptions = 0,
                prescriptions = emptyList()
            )
        }

        val rawNodes = store.queryComplexNodes(projectId, minCognitive = minCognitive, limit = limit * 4)
        val complexNodes = filterTargetNodes(rawNodes, target)
        val candidatePrescriptions = mutableListOf<ComplexityDoctorReport.Prescription>()

        for ((filePath, nodes) in complexNodes.groupBy { it.filePath }) {
            candidatePrescriptions.addAll(collectFilePrescriptions(projectId, filePath, nodes))
        }

        val sorted = rankPrescriptions(candidatePrescriptions, limit)
        return ComplexityDoctorReport(
            project = projectId,
            totalPrescriptions = sorted.size,
            prescriptions = sorted
        )
    }

    private fun isFunctionTargetMatch(func: GraphNode, nodeId: String, sym: String, simpleSym: String): Boolean {
        if (nodeId.isEmpty() || func.nodeId == nodeId || func.name == nodeId || func.qualifiedName == nodeId) {
            return true
        }
        if (sym.isEmpty()) return false
        return func.nodeId.substringAfter('#') == sym ||
            func.name == sym ||
            func.name == simpleSym ||
            func.qualifiedName.endsWith(sym)
    }

    private fun resolveNodesForAnalysis(
        projectId: String,
        filePath: String,
        sourceText: String,
        preExtractedNodes: List<GraphNode>?
    ): List<GraphNode> {
        if (preExtractedNodes != null) return preExtractedNodes
        return try {
            PolyglotAstExtractor.extractFile(projectId, filePath, sourceText).first
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun collectFunctionPrescriptions(
        filePath: String,
        sourceText: String,
        func: GraphNode,
        inDegree: Int,
        lang: String,
        projectId: String
    ): List<ComplexityDoctorReport.Prescription> {
        val list = mutableListOf<ComplexityDoctorReport.Prescription>()
        val guard = synthesizeGuardInversion(filePath, sourceText, func, inDegree, lang, projectId)
        if (guard != null) list.add(guard)
        val elvis = synthesizeElvisGuard(filePath, sourceText, func, inDegree, lang, projectId)
        if (elvis != null) list.add(elvis)
        return list
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

        val nodes = resolveNodesForAnalysis(projectId, filePath, sourceText, preExtractedNodes)
        val candidatePrescriptions = mutableListOf<ComplexityDoctorReport.Prescription>()
        val sym = if (nodeId.contains('#')) nodeId.substringAfter('#') else nodeId
        val simpleSym = sym.substringAfterLast('.')

        for (func in nodes) {
            if (func.kind !in setOf("function", "method", "composable")) continue
            if (!isFunctionTargetMatch(func, nodeId, sym, simpleSym)) continue
            candidatePrescriptions.addAll(
                collectFunctionPrescriptions(filePath, sourceText, func, inDegree, lang, projectId)
            )
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

    private class OuterIfInfo(val lineIdx: Int, val indent: String, val rawCondition: String)

    private class ElseResolution(val spanEndIdx: Int, val defaultReturn: String)

    private fun findFunctionBodyStart(lines: List<String>, startIdx: Int, endIdx: Int): Int? {
        if (startIdx >= endIdx || startIdx >= lines.size) return null
        for (i in startIdx..endIdx) {
            if (lines[i].contains('{')) return i
        }
        return null
    }

    private fun isDeclarationPrefix(trimmed: String): Boolean {
        return trimmed.startsWith("val ") || trimmed.startsWith("var ") ||
            trimmed.startsWith("let ") || trimmed.startsWith("const ") ||
            trimmed.startsWith("final ") || trimmed.startsWith("auto ")
    }

    private fun findOuterIfLine(lines: List<String>, bodyStart: Int, endIdx: Int): OuterIfInfo? {
        val ifRegex = Regex("""^(\s*)if\s*\((.+)\)\s*\{(?:\s*//.*)?$""")
        for (i in (bodyStart + 1)..endIdx) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                continue
            }
            val m = ifRegex.find(lines[i])
            if (m != null) {
                return OuterIfInfo(i, m.groupValues[1], m.groupValues[2].trim())
            }
            if (isDeclarationPrefix(trimmed)) continue
            break
        }
        return null
    }

    private fun findMatchingIfClose(lines: List<String>, ifLineIdx: Int, endIdx: Int, lang: String): Int? {
        var depth = 0
        for (i in ifLineIdx..endIdx) {
            val masked = PolyglotAstExtractor.maskCommentsAndStrings(lines[i], lang)
            depth += masked.count { it == '{' } - masked.count { it == '}' }
            if (depth == 0) return i
        }
        return null
    }

    private fun extractReturnExpr(match: MatchResult): String {
        val retExpr = match.groupValues.getOrNull(1)?.trim() ?: ""
        return if (retExpr.isNotEmpty()) "return $retExpr" else "return"
    }

    private fun findMultiLineElseEnd(
        lines: List<String>,
        closeIfIdx: Int,
        endIdx: Int,
        lang: String,
        retRegex: Regex
    ): ElseResolution? {
        var elseDepth = 1
        var defaultReturn = "return"
        for (i in (closeIfIdx + 1)..endIdx) {
            val rMatch = retRegex.find(lines[i])
            if (rMatch != null) {
                defaultReturn = extractReturnExpr(rMatch)
            }
            val masked = PolyglotAstExtractor.maskCommentsAndStrings(lines[i], lang)
            elseDepth += masked.count { it == '{' } - masked.count { it == '}' }
            if (elseDepth == 0) {
                return ElseResolution(i, defaultReturn)
            }
        }
        return null
    }

    private fun resolveElseBlock(
        lines: List<String>,
        closeIfIdx: Int,
        endIdx: Int,
        lang: String
    ): ElseResolution? {
        val elseRegex = Regex("""^\s*\}\s*else\s*\{(?:\s*return(?:\s+([^;}\n]+))?;?\s*\})?\s*$""")
        val elseStartRegex = Regex("""^\s*\}\s*else\s*\{""")
        val retRegex = Regex("""^\s*return(?:\s+([^;}\n]+))?;?\s*$""")

        val closeIfLine = lines[closeIfIdx]
        val elseMatch = elseRegex.find(closeIfLine)
        if (elseMatch != null) {
            return ElseResolution(closeIfIdx, extractReturnExpr(elseMatch))
        }
        if (elseStartRegex.find(closeIfLine) != null) {
            return findMultiLineElseEnd(lines, closeIfIdx, endIdx, lang, retRegex)
        }
        return null
    }

    private fun resolveTrailingReturn(
        lines: List<String>,
        closeIfIdx: Int,
        endIdx: Int
    ): ElseResolution? {
        val retRegex = Regex("""^\s*return(?:\s+([^;}\n]+))?;?\s*$""")
        var candidateRetIdx = -1
        var defaultReturn = "return"
        for (i in (closeIfIdx + 1)..endIdx) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue
            val rMatch = retRegex.find(lines[i])
            if (rMatch != null) {
                candidateRetIdx = i
                defaultReturn = extractReturnExpr(rMatch)
            } else if (trimmed == "}") {
                break
            } else {
                return null
            }
        }
        val spanEnd = if (candidateRetIdx > 0) candidateRetIdx else closeIfIdx
        return ElseResolution(spanEnd, defaultReturn)
    }

    private fun resolveElseOrTrailingReturn(
        lines: List<String>,
        closeIfIdx: Int,
        endIdx: Int,
        lang: String
    ): ElseResolution? {
        val elseRes = resolveElseBlock(lines, closeIfIdx, endIdx, lang)
        if (elseRes != null) return elseRes
        return resolveTrailingReturn(lines, closeIfIdx, endIdx)
    }

    private fun unindentInnerLines(innerLines: List<String>, ifIndent: String): List<String> {
        val firstNonBlankInner = innerLines.firstOrNull { it.isNotBlank() } ?: ""
        val innerIndent = firstNonBlankInner.takeWhile { it.isWhitespace() }
        val indentToRemove = if (innerIndent.startsWith(ifIndent) && innerIndent.length > ifIndent.length) {
            innerIndent.substring(ifIndent.length)
        } else {
            "    "
        }

        return innerLines.map { l ->
            when {
                l.startsWith(ifIndent + indentToRemove) -> ifIndent + l.substring(ifIndent.length + indentToRemove.length)
                l.startsWith(indentToRemove) -> l.substring(indentToRemove.length)
                else -> l.trimStart()
            }
        }
    }

    private fun buildInvertedCandidateText(
        sourceText: String,
        lines: List<String>,
        ifInfo: OuterIfInfo,
        closeIfIdx: Int,
        spanEndIdx: Int,
        defaultReturn: String
    ): Pair<String, String>? {
        val innerLines = lines.subList(ifInfo.lineIdx + 1, closeIfIdx)
        if (innerLines.isEmpty()) return null

        val unindentedInner = unindentInnerLines(innerLines, ifInfo.indent)
        val invertedCond = invertCondition(ifInfo.rawCondition)

        val beforeSnippet = lines.subList(ifInfo.lineIdx, spanEndIdx + 1).joinToString("\n")
        val afterLines = mutableListOf<String>()
        afterLines.add("${ifInfo.indent}if ($invertedCond) $defaultReturn")
        afterLines.addAll(unindentedInner)
        val afterSnippet = afterLines.joinToString("\n")

        if (countOccurrences(sourceText, beforeSnippet) != 1) return null
        return Pair(beforeSnippet, afterSnippet)
    }

    private fun evaluateGuardInversionPrescription(
        sourceText: String,
        candidateText: String,
        beforeSnippet: String,
        afterSnippet: String,
        node: GraphNode,
        ifLineIdx: Int,
        inDegree: Int,
        filePath: String,
        projectId: String,
        lang: String
    ): ComplexityDoctorReport.Prescription? {
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
        if (diff < 1) return null

        val reductionPct = if (cogBefore > 0) (diff.toDouble() / cogBefore.toDouble()) * 100.0 else 0.0
        val roundedPct = (reductionPct * 10.0).roundToInt() / 10.0

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
        val bodyStart = findFunctionBodyStart(lines, startIdx, endIdx) ?: return null

        val ifInfo = findOuterIfLine(lines, bodyStart, endIdx) ?: return null
        if (ifInfo.rawCondition.isEmpty()) return null

        val closeIfIdx = findMatchingIfClose(lines, ifInfo.lineIdx, endIdx, lang) ?: return null
        if (closeIfIdx <= ifInfo.lineIdx) return null

        val elseRes = resolveElseOrTrailingReturn(lines, closeIfIdx, endIdx, lang) ?: return null
        val (beforeSnippet, afterSnippet) = buildInvertedCandidateText(
            sourceText, lines, ifInfo, closeIfIdx, elseRes.spanEndIdx, elseRes.defaultReturn
        ) ?: return null

        val candidateText = sourceText.replace(beforeSnippet, afterSnippet)
        return evaluateGuardInversionPrescription(
            sourceText, candidateText, beforeSnippet, afterSnippet, node,
            ifInfo.lineIdx, inDegree, filePath, projectId, lang
        )
    }

    private data class ElvisNullCheck(
        val spanEnd: Int,
        val retExpr: String
    )

    private fun findNextNonBlankLineIdx(lines: List<String>, fromIdx: Int, endIdx: Int): Int {
        for (j in fromIdx..endIdx) {
            if (lines[j].isNotBlank()) return j
        }
        return -1
    }

    private fun matchElvisNullCheck(
        lines: List<String>,
        nextIdx: Int,
        endIdx: Int,
        varName: String
    ): ElvisNullCheck? {
        val singleLineIf = Regex("""^\s*if\s*\(\s*${Regex.escape(varName)}\s*==\s*null\s*\)\s*(?:\{\s*return(?:\s+([^;}\n]+))?;?\s*\}|return(?:\s+([^;}\n]+))?;?)\s*$""")
        val sm = singleLineIf.find(lines[nextIdx])
        if (sm != null) {
            val expr = (sm.groupValues.getOrNull(1)?.ifEmpty { null }
                ?: sm.groupValues.getOrNull(2))?.trim() ?: ""
            return ElvisNullCheck(spanEnd = nextIdx, retExpr = expr)
        }

        val multiLineIfStart = Regex("""^\s*if\s*\(\s*${Regex.escape(varName)}\s*==\s*null\s*\)\s*\{$""")
        if (multiLineIfStart.find(lines[nextIdx]) != null && nextIdx + 2 <= endIdx) {
            val retRegex = Regex("""^\s*return(?:\s+([^;}\n]+))?;?\s*$""")
            val rm = retRegex.find(lines[nextIdx + 1])
            if (rm != null && lines[nextIdx + 2].trim() == "}") {
                val expr = rm.groupValues.getOrNull(1)?.trim() ?: ""
                return ElvisNullCheck(spanEnd = nextIdx + 2, retExpr = expr)
            }
        }
        return null
    }

    private fun evaluateElvisPrescription(
        filePath: String,
        sourceText: String,
        node: GraphNode,
        inDegree: Int,
        lang: String,
        projectId: String,
        lineIdx: Int,
        beforeSnippet: String,
        afterSnippet: String
    ): ComplexityDoctorReport.Prescription? {
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
            ruleId = "COMPLEXITY-ELVIS-GUARD",
            fixStrategy = "ELVIS_GUARD",
            writeSiteSafe = true,
            targetNodeId = node.nodeId,
            filePath = filePath,
            line = lineIdx + 1,
            cognitiveBefore = cogBefore,
            cognitiveAfter = cogAfter,
            reductionPercent = roundedPct,
            rationale = "Replacing explicit null check with Elvis return operator flattens decision branching, reducing cognitive complexity from $cogBefore to $cogAfter ($roundedPct% reduction).",
            beforeSnippet = beforeSnippet,
            afterSnippet = afterSnippet
        )
    }

    private fun trySynthesizeElvisGuardAtLine(
        lines: List<String>,
        declLineIdx: Int,
        endIdx: Int,
        declRegex: Regex,
        filePath: String,
        sourceText: String,
        node: GraphNode,
        inDegree: Int,
        lang: String,
        projectId: String
    ): ComplexityDoctorReport.Prescription? {
        val dMatch = declRegex.find(lines[declLineIdx]) ?: return null
        val indent = dMatch.groupValues[1]
        val varName = dMatch.groupValues[2]
        val initExpr = dMatch.groupValues[3].trim()

        val nextIdx = findNextNonBlankLineIdx(lines, declLineIdx + 1, endIdx)
        if (nextIdx < 0) return null

        val nullCheck = matchElvisNullCheck(lines, nextIdx, endIdx, varName) ?: return null
        val retExpr = nullCheck.retExpr
        val elvisRet = if (retExpr.isNotEmpty()) " ?: return $retExpr" else " ?: return"
        val beforeSnippet = lines.subList(declLineIdx, nullCheck.spanEnd + 1).joinToString("\n")
        val afterSnippet = "${indent}val $varName = $initExpr$elvisRet"

        return evaluateElvisPrescription(
            filePath, sourceText, node, inDegree, lang, projectId,
            declLineIdx, beforeSnippet, afterSnippet
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
            val prescription = trySynthesizeElvisGuardAtLine(
                lines, i, endIdx, declRegex,
                filePath, sourceText, node, inDegree, lang, projectId
            )
            if (prescription != null) return prescription
        }

        return null
    }

    private fun isEnclosingParensBalanced(text: String, startIndex: Int = 0): Boolean {
        var depth = 0
        for (idx in startIndex until text.length) {
            if (text[idx] == '(') depth++
            else if (text[idx] == ')') {
                depth--
                if (depth == 0) return idx == text.length - 1
            }
        }
        return false
    }

    private fun stripOuterParens(cond: String): String {
        var trimmed = cond.trim()
        while (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            if (isEnclosingParensBalanced(trimmed, 0)) {
                trimmed = trimmed.substring(1, trimmed.length - 1).trim()
            } else {
                break
            }
        }
        return trimmed
    }

    private fun isSimpleIdentifier(s: String): Boolean {
        return s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '_' || it == '.' }
    }

    private fun invertSimpleNegation(trimmed: String): String? {
        if (trimmed.startsWith("!(") && trimmed.endsWith(")") && isEnclosingParensBalanced(trimmed, 1)) {
            return trimmed.substring(2, trimmed.length - 1).trim()
        }
        if (trimmed.startsWith("!")) {
            val rest = trimmed.substring(1).trim()
            if (isSimpleIdentifier(rest)) {
                return rest
            }
        }
        return null
    }

    private fun invertLogicalOperators(trimmed: String): String? {
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
        return null
    }

    private fun invertComparison(trimmed: String): String? {
        for (entry in COMPARISON_INVERSIONS) {
            val op = entry.first
            val opp = entry.second
            val pos = findTopLevelOperatorPositions(trimmed, op)
            if (pos.size == 1) {
                val left = trimmed.substring(0, pos[0]).trim()
                val right = trimmed.substring(pos[0] + op.length).trim()
                return "$left$opp$right"
            }
        }
        return null
    }

    private fun invertCondition(cond: String): String {
        val trimmed = stripOuterParens(cond)
        val invertedNeg = invertSimpleNegation(trimmed)
        if (invertedNeg != null) return invertedNeg

        val invertedLogic = invertLogicalOperators(trimmed)
        if (invertedLogic != null) return invertedLogic

        val invertedCmp = invertComparison(trimmed)
        if (invertedCmp != null) return invertedCmp

        if (isSimpleIdentifier(trimmed)) {
            return "!$trimmed"
        }
        return "!($trimmed)"
    }

    companion object {
        private val COMPARISON_INVERSIONS = listOf(
            Pair(" === ", " !== "),
            Pair(" !== ", " === "),
            Pair(" == ", " != "),
            Pair(" != ", " == "),
            Pair(" <= ", " > "),
            Pair(" >= ", " < "),
            Pair(" < ", " >= "),
            Pair(" > ", " <= ")
        )
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

    private val closingToOpeningDelimiter = mapOf('}' to '{', ')' to '(', ']' to '[')

    private fun updateDelimiterStack(stack: MutableList<Char>, ch: Char): Boolean {
        if (ch == '{' || ch == '(' || ch == '[') {
            stack.add(ch)
            return true
        }
        val expectedOpen = closingToOpeningDelimiter[ch] ?: return true
        return stack.isNotEmpty() && stack.removeAt(stack.size - 1) == expectedOpen
    }

    private fun hasBalancedDelimiters(maskedText: String): Boolean {
        val stack = mutableListOf<Char>()
        for (c in maskedText) {
            if (!updateDelimiterStack(stack, c)) return false
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
