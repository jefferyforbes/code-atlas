package atlas.bridge

import atlas.extract.UrlTargetResolver
import atlas.model.GraphEdge
import atlas.model.GraphNode

/**
 * Deterministic Obsidian [[Wikilink]], backtick `Symbol`, and Plan Contract graph bridge ($0 token cost).
 */
object VaultPlanBridge {

    private val WIKILINK_RE = Regex("""\[\[([^\]|#]+)(?:#[^\]|]+)?(?:\|[^\]]+)?\]\]""")
    private val BACKTICK_SPAN_RE = Regex("""`([A-Za-z_][A-Za-z0-9_.:/#-]{2,80})`""")
    private val MARKDOWN_URL_RE = Regex("""\[[^\]]*\]\(([^)\s]+)\)""")
    private val BARE_ROUTE_BACKTICK_RE = Regex("""`(/[A-Za-z0-9_./:{}$<>-]+)`""")

    fun extractMarkdownAndPlans(
        projectId: String,
        relPath: String,
        markdownText: String,
        indexedNodes: List<GraphNode>
    ): Pair<List<GraphNode>, List<GraphEdge>> {
        val fileId = "$projectId:$relPath"
        val normRel = relPath.replace('\\', '/')
        val isPlan = normRel.startsWith("plans/") || normRel.contains("/plans/")
        val isSkill = normRel.endsWith("/SKILL.md", ignoreCase = true) ||
            normRel.equals("SKILL.md", ignoreCase = true) ||
            (normRel.contains("skills/") && !normRel.contains("/references/") && !normRel.contains("/scripts/") && !normRel.contains("/examples/") && !normRel.contains("/resources/") && (normRel.endsWith("/SKILL.md", ignoreCase = true) || (normRel.endsWith(".md") && !normRel.substringAfter("skills/").contains("/"))))

        val stem = normRel.substringAfterLast('/').substringBeforeLast('.')
        val nodeKind = if (isSkill) "skill" else if (isPlan) "plan_contract" else "obsidian_note"

        val nameVal = extractFrontmatterScalar(markdownText, "name")
        val skillName = if (isSkill) {
            nameVal.ifEmpty { normRel.substringBeforeLast("/SKILL.md", "").substringAfterLast('/').ifEmpty { stem } }
        } else ""

        val docNodeId = if (isSkill) "$projectId:skill#$skillName" else "$fileId#$stem"

        val statusVal = extractFrontmatterScalar(markdownText, "status").ifEmpty { "active" }
        val descVal = extractFrontmatterScalar(markdownText, "description")
        val docNode = GraphNode(
            nodeId = docNodeId,
            projectId = projectId,
            fileId = fileId,
            name = if (isSkill) skillName else stem,
            qualifiedName = if (isSkill) "skill#$skillName" else normRel,
            kind = nodeKind,
            signature = when {
                isSkill -> if (descVal.isNotEmpty()) "skill ($skillName): $descVal" else "skill: $normRel"
                else -> "$nodeKind ($statusVal): $normRel"
            },
            startLine = 1,
            endLine = markdownText.lines().size.coerceAtLeast(1),
            moduleId = when {
                isSkill -> "skills"
                isPlan -> "plans"
                else -> "vault"
            },
            sourceSet = "docs",
            semioticLayer = when {
                isSkill -> "pragmatic"
                isPlan -> "syntactic"
                else -> "semantic"
            },
            archLayer = "infrastructure",
            description = if (isSkill && descVal.isNotEmpty()) descVal else null
        )

        val docNodes = mutableListOf<GraphNode>()
        docNodes.add(docNode)

        val byName = mutableMapOf<String, GraphNode>()
        val byRelFile = mutableMapOf<String, MutableList<GraphNode>>()
        for (node in indexedNodes) {
            if (node.projectId != projectId) continue
            if (!byName.containsKey(node.name)) {
                byName[node.name] = node
            }
            val nodeRel = node.fileId.substringAfter(':')
            byRelFile.getOrPut(nodeRel) { mutableListOf() }.add(node)
        }

        val edges = mutableListOf<GraphEdge>()
        val seenEdges = mutableSetOf<String>()

        val fmTags = extractFrontmatterTags(markdownText)
        val inlineTags = extractSanitizedInlineTags(markdownText)
        val allTags = mutableMapOf<String, Int>()
        for (t in fmTags) {
            if (t.isNotEmpty() && !allTags.containsKey(t)) {
                allTags[t] = 1
            }
        }
        for ((t, lineNo) in inlineTags) {
            if (t.isNotEmpty() && !allTags.containsKey(t)) {
                allTags[t] = lineNo
            }
        }

        for ((cleanTag, lineNo) in allTags) {
            val tagNodeId = "$projectId:tag#$cleanTag"
            docNodes.add(
                GraphNode(
                    nodeId = tagNodeId,
                    projectId = projectId,
                    fileId = fileId,
                    name = cleanTag,
                    qualifiedName = "tag#$cleanTag",
                    kind = "tag",
                    signature = "tag: #$cleanTag",
                    startLine = lineNo,
                    endLine = lineNo,
                    moduleId = "tags",
                    sourceSet = "docs",
                    semioticLayer = "semantic",
                    archLayer = "infrastructure"
                )
            )
            val eid = "$docNodeId:tagged:$tagNodeId"
            if (seenEdges.add(eid)) {
                edges.add(
                    GraphEdge(
                        edgeId = eid,
                        projectId = projectId,
                        sourceNodeId = docNodeId,
                        targetNodeId = tagNodeId,
                        edgeType = "TAGGED_WITH",
                        provenance = "EXTRACTED_TAG",
                        confidence = 1.0,
                        lineNumber = lineNo
                    )
                )
            }
        }

        if (isSkill) {
            val cmdSet = mutableSetOf<String>()
            if (skillName.isNotEmpty()) {
                cmdSet.add(skillName)
            }
            if (descVal.isNotEmpty()) {
                val slashInDesc = Regex("""(?:^|[^a-zA-Z0-9_./])\/([a-z][a-z0-9_-]{1,30})""")
                for (m in slashInDesc.findAll(descVal)) {
                    cmdSet.add(m.groupValues[1])
                }
            }
            val triggeredPattern = Regex("""(?i)triggered(?:\s+natively)?\s+by\s+([^.\n]+)""")
            for (match in triggeredPattern.findAll(markdownText)) {
                val textClause = match.groupValues[1]
                val slashInClause = Regex("""\/([a-z][a-z0-9_-]{1,30})""")
                for (m in slashInClause.findAll(textClause)) {
                    cmdSet.add(m.groupValues[1])
                }
            }

            for (cmd in cmdSet) {
                val cleanCmd = cmd.removePrefix("/").trim()
                if (cleanCmd.isEmpty()) continue
                val cmdNodeId = "$projectId:cmd#/$cleanCmd"
                docNodes.add(
                    GraphNode(
                        nodeId = cmdNodeId,
                        projectId = projectId,
                        fileId = fileId,
                        name = "/$cleanCmd",
                        qualifiedName = "cmd#/$cleanCmd",
                        kind = "slash_command",
                        signature = "slash_command: /$cleanCmd",
                        startLine = 1,
                        endLine = 1,
                        moduleId = "skills",
                        sourceSet = "docs",
                        semioticLayer = "pragmatic",
                        archLayer = "infrastructure"
                    )
                )
                val eid = "$cmdNodeId:triggers:$docNodeId"
                if (seenEdges.add(eid)) {
                    edges.add(
                        GraphEdge(
                            edgeId = eid,
                            projectId = projectId,
                            sourceNodeId = cmdNodeId,
                            targetNodeId = docNodeId,
                            edgeType = "TRIGGERS_SKILL",
                            provenance = "EXTRACTED_COMMAND",
                            confidence = 1.0,
                            lineNumber = 1
                        )
                    )
                }
            }
        }

        if (isPlan) {
            val scopedFiles = extractScopedFiles(markdownText)
            scopedFiles.forEachIndexed { idx, scopedFile ->
                val cleanF = scopedFile.trim().trim('\'', '"').removePrefix("./")
                val matchedNodes = when {
                    cleanF.contains('*') || cleanF.contains('?') -> {
                        val globRe = globToRegex(cleanF)
                        byRelFile.filterKeys { rel -> globRe.matches(rel) }.values.flatten()
                    }
                    cleanF.endsWith('/') -> {
                        byRelFile.filterKeys { rel -> rel.startsWith(cleanF) }.values.flatten()
                    }
                    else -> {
                        byRelFile[cleanF] ?: byRelFile[cleanF.substringAfterLast('/')] ?: emptyList()
                    }
                }
                for (targetNode in matchedNodes) {
                    val eid = "$docNodeId:scopes:${targetNode.nodeId}"
                    if (seenEdges.add(eid)) {
                        edges.add(
                            GraphEdge(
                                edgeId = eid,
                                projectId = projectId,
                                sourceNodeId = docNodeId,
                                targetNodeId = targetNode.nodeId,
                                edgeType = "PLAN_SCOPES_FILE",
                                provenance = "EXTRACTED_PLAN",
                                confidence = 1.0,
                                lineNumber = idx + 1
                            )
                        )
                    }
                }
            }
        }

        for (match in WIKILINK_RE.findAll(markdownText)) {
            val targetTitle = match.groupValues[1].trim()
            val targetStem = targetTitle.substringAfterLast('/').substringBeforeLast('.')
            val lineNo = markdownText.take(match.range.first).count { it == '\n' } + 1
            val targetNode = byName[targetStem] ?: byName[targetTitle]
            val targetId = targetNode?.nodeId ?: "$projectId:vault_ref#$targetStem"
            val eid = "$docNodeId:wiki:$targetId:$lineNo"
            if (seenEdges.add(eid)) {
                edges.add(
                    GraphEdge(
                        edgeId = eid,
                        projectId = projectId,
                        sourceNodeId = docNodeId,
                        targetNodeId = targetId,
                        edgeType = "WIKILINK_REFS",
                        provenance = "EXTRACTED_WIKILINK",
                        confidence = 1.0,
                        lineNumber = lineNo
                    )
                )
            }
        }

        for (match in BACKTICK_SPAN_RE.findAll(markdownText)) {
            val rawToken = match.groupValues[1].trim()
            val lineNo = markdownText.take(match.range.first).count { it == '\n' } + 1
            val symCandidate = rawToken.substringAfterLast("::").substringAfterLast('#').trimEnd('(', ')')
            val targetNode = byName[symCandidate] ?: byName[symCandidate.substringAfterLast('.')]
            if (targetNode != null) {
                val eid = "$docNodeId:codespan:${targetNode.nodeId}:$lineNo"
                if (seenEdges.add(eid)) {
                    edges.add(
                        GraphEdge(
                            edgeId = eid,
                            projectId = projectId,
                            sourceNodeId = docNodeId,
                            targetNodeId = targetNode.nodeId,
                            edgeType = "CODE_SPAN_REFS",
                            provenance = "INFERRED_CODE_SPAN",
                            confidence = 0.9,
                            lineNumber = lineNo
                        )
                    )
                }
            }
        }

        fun emitUrlRefEdge(targetId: String, lineNo: Int) {
            val eid = "$docNodeId:urlref:$targetId:$lineNo"
            if (seenEdges.add(eid)) {
                edges.add(GraphEdge(eid, projectId, docNodeId, targetId, "URL_REFS", "EXTRACTED_URL", 1.0, lineNo))
            }
        }

        for (match in MARKDOWN_URL_RE.findAll(markdownText)) {
            val rawLink = match.groupValues[1].trim()
            if (rawLink.isEmpty() || rawLink.startsWith("#")) continue
            val lineNo = markdownText.take(match.range.first).count { it == '\n' } + 1

            val isSkillRef = rawLink.endsWith("/SKILL.md", ignoreCase = true) ||
                rawLink.equals("SKILL.md", ignoreCase = true) ||
                (rawLink.contains("/skills/") && rawLink.endsWith(".md"))

            if (isSkillRef) {
                val targetSkillName = when {
                    rawLink.endsWith("/SKILL.md", ignoreCase = true) -> {
                        rawLink.substringBeforeLast("/SKILL.md").substringAfterLast('/')
                    }
                    rawLink.equals("SKILL.md", ignoreCase = true) -> ""
                    else -> {
                        rawLink.substringAfterLast('/').substringBeforeLast('.')
                    }
                }
                if (targetSkillName.isNotEmpty() && targetSkillName != skillName) {
                    val targetSkillId = "$projectId:skill#$targetSkillName"
                    val eid = "$docNodeId:depends:$targetSkillId:$lineNo"
                    if (seenEdges.add(eid)) {
                        edges.add(
                            GraphEdge(
                                edgeId = eid,
                                projectId = projectId,
                                sourceNodeId = docNodeId,
                                targetNodeId = targetSkillId,
                                edgeType = "DEPENDS_ON_SKILL",
                                provenance = "EXTRACTED_SKILL_DEP",
                                confidence = 1.0,
                                lineNumber = lineNo
                            )
                        )
                    }
                    continue
                }
            }

            val codeLoc = UrlTargetResolver.parseCodeLocationUrl(rawLink)
            if (codeLoc != null) {
                val fileCandidates = byRelFile[codeLoc.filePathSuffix]
                    ?: byRelFile.entries.firstOrNull { (k, _) ->
                        k.endsWith("/${codeLoc.filePathSuffix}") || k == codeLoc.filePathSuffix.substringAfterLast('/')
                    }?.value
                    ?: emptyList()
                val resolvedNode = when {
                    codeLoc.startLine != null -> {
                        val lineTarget = codeLoc.startLine
                        fileCandidates
                            .filter { it.kind !in setOf("file", "file_scope", "module") && lineTarget in it.startLine..it.endLine }
                            .minWithOrNull(compareBy<GraphNode> { it.endLine - it.startLine }.thenByDescending { it.startLine })
                            ?: fileCandidates.firstOrNull()
                    }
                    codeLoc.symbolFragment != null -> {
                        fileCandidates.firstOrNull { it.name == codeLoc.symbolFragment } ?: byName[codeLoc.symbolFragment] ?: fileCandidates.firstOrNull()
                    }
                    else -> fileCandidates.firstOrNull()
                }
                if (resolvedNode != null) {
                    emitUrlRefEdge(resolvedNode.nodeId, lineNo)
                    continue
                }
            }

            if (UrlTargetResolver.isHttpRouteCandidate(rawLink, hasExplicitRouteCall = false)) {
                emitUrlRefEdge("$projectId:endpoint#${UrlTargetResolver.normalizeRouteTemplate(rawLink)}", lineNo)
            } else if (rawLink.startsWith("http://", ignoreCase = true) ||
                rawLink.startsWith("https://", ignoreCase = true) ||
                rawLink.startsWith("figma://", ignoreCase = true) ||
                rawLink.startsWith("sidecar://", ignoreCase = true)
            ) {
                emitUrlRefEdge("$projectId:url_ref#${UrlTargetResolver.normalizeExternalUrl(rawLink)}", lineNo)
                val pathPart = UrlTargetResolver.extractPathFromHttpUrl(rawLink)
                if (pathPart != null && UrlTargetResolver.isHttpRouteCandidate(pathPart, hasExplicitRouteCall = false)) {
                    emitUrlRefEdge("$projectId:endpoint#${UrlTargetResolver.normalizeRouteTemplate(pathPart)}", lineNo)
                }
            }
        }

        for (match in BARE_ROUTE_BACKTICK_RE.findAll(markdownText)) {
            val rawRoute = match.groupValues[1].trim()
            if (UrlTargetResolver.isHttpRouteCandidate(rawRoute, hasExplicitRouteCall = false)) {
                val lineNo = markdownText.take(match.range.first).count { it == '\n' } + 1
                emitUrlRefEdge("$projectId:endpoint#${UrlTargetResolver.normalizeRouteTemplate(rawRoute)}", lineNo)
            }
        }

        return Pair(docNodes, edges)
    }

    private fun extractFrontmatterText(markdownText: String): String? {
        if (!markdownText.startsWith("---")) return null
        val parts = markdownText.split("---", limit = 3)
        if (parts.size < 3) return null
        return parts[1]
    }

    private fun extractFrontmatterLines(markdownText: String): List<String>? {
        return extractFrontmatterText(markdownText)?.lines()
    }

    fun extractFrontmatterScalar(markdownText: String, key: String): String {
        val fmText = extractFrontmatterText(markdownText) ?: return ""
        val pattern = Regex("""(?m)^\s*${Regex.escape(key)}\s*:\s*(.+)$""")
        val match = pattern.find(fmText) ?: return ""
        return match.groupValues[1].trim().trim('\'', '"')
    }

    private class ScopedFilesState {
        var inScope = false
        var inFiles = false
        val scoped = mutableListOf<String>()

        fun processLine(line: String) {
            val stripped = line.trim()
            val indent = line.length - line.trimStart().length
            if (indent == 0) {
                handleRootLine(stripped)
                return
            }
            if (!inScope) return
            handleScopedLine(stripped, indent)
        }

        private fun handleRootLine(stripped: String) {
            if (stripped.startsWith("scope:")) {
                inScope = true
                inFiles = false
            } else if (stripped.isNotEmpty() && !stripped.startsWith("#")) {
                inScope = false
                inFiles = false
            }
        }

        private fun handleScopedLine(stripped: String, indent: Int) {
            val isKeyLevel = indent == 2 || indent == 4
            if (isKeyLevel && stripped.startsWith("files:")) {
                inFiles = true
            } else if (isKeyLevel && stripped.endsWith(":") && !stripped.startsWith("-")) {
                inFiles = false
            } else if (inFiles && stripped.startsWith("- ")) {
                scoped.add(stripped.removePrefix("- ").trim())
            }
        }
    }

    fun extractScopedFiles(markdownText: String): List<String> {
        val fmLines = extractFrontmatterLines(markdownText) ?: return emptyList()
        val state = ScopedFilesState()
        for (line in fmLines) {
            state.processLine(line)
        }
        return state.scoped
    }

    private fun appendAsteriskPattern(glob: String, currentIndex: Int, sb: StringBuilder): Int {
        var i = currentIndex
        val hasNext = i + 1 < glob.length
        if (hasNext && glob[i + 1] == '*') {
            i++
            if (i + 1 < glob.length && glob[i + 1] == '/') {
                i++
                sb.append("(?:.*/)?")
            } else {
                sb.append(".*")
            }
        } else {
            sb.append("[^/]*")
        }
        return i
    }

    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> i = appendAsteriskPattern(glob, i, sb)
                '?' -> sb.append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}' -> sb.append('\\').append(c)
                '\\' -> sb.append('/')
                else -> sb.append(c)
            }
            i++
        }
        sb.append('$')
        return Regex(sb.toString())
    }

    private fun cleanTagItem(raw: String): String? {
        val clean = raw.trim().trim('\'', '"').removePrefix("#")
        return clean.ifEmpty { null }
    }

    private fun parseInlineTags(value: String): List<String> {
        val unbracketed = if (value.startsWith("[") && value.endsWith("]")) {
            value.removePrefix("[").removeSuffix("]")
        } else {
            value
        }
        return unbracketed.split(',').mapNotNull { cleanTagItem(it) }
    }

    private class FrontmatterTagsState {
        var inTagsBlock = false
        val tags = mutableListOf<String>()

        fun processLine(line: String) {
            val trimmed = line.trim()
            val indent = line.length - line.trimStart().length
            if (indent == 0) {
                handleRootLine(trimmed)
                return
            }
            if (inTagsBlock && (indent == 2 || indent == 4) && trimmed.startsWith("- ")) {
                cleanTagItem(trimmed.removePrefix("- "))?.let { tags.add(it) }
            }
        }

        private fun handleRootLine(trimmed: String) {
            if (trimmed.startsWith("tags:") || trimmed.startsWith("tag:")) {
                val afterColon = trimmed.substringAfter(':').trim()
                if (afterColon.isNotEmpty()) {
                    tags.addAll(parseInlineTags(afterColon))
                    inTagsBlock = false
                } else {
                    inTagsBlock = true
                }
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                inTagsBlock = false
            }
        }
    }

    fun extractFrontmatterTags(markdownText: String): List<String> {
        val fmLines = extractFrontmatterLines(markdownText) ?: return emptyList()
        val state = FrontmatterTagsState()
        for (line in fmLines) {
            state.processLine(line)
        }
        return state.tags.distinct()
    }

    private val CODE_FENCE_RE = Regex("""(?s)(```|~~~).*?\1""")
    private val INLINE_CODE_RE = Regex("""`[^`\n]+`""")
    private val HTML_COMMENT_RE = Regex("""(?s)<!--.*?-->""")
    private val WIKILINK_MASK_RE = Regex("""\[\[[^\]]+\]\]""")
    private val MD_LINK_MASK_RE = Regex("""\[[^\]]*\]\([^)]+\)""")
    private val HEADING_MASK_RE = Regex("""(?m)^[ \t]*#{1,6}\s+.*$""")
    private val TAG_SCAN_RE = Regex("""(?:^|[\s(.,;:!?'"\[\]{}])#([A-Za-z][A-Za-z0-9_/-]{1,60})""")

    private fun blankFrontmatter(text: String): String {
        if (!text.startsWith("---")) return text
        val endIdx = text.indexOf("\n---", 3)
        if (endIdx == -1) return text
        val fmLines = text.substring(0, endIdx + 4).count { it == '\n' }
        val remainder = text.substring(endIdx + 4)
        return "\n".repeat(fmLines) + remainder
    }

    private fun maskMultilineBlocks(text: String, regex: Regex): String {
        return regex.replace(text) { m ->
            m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
        }
    }

    private fun maskSingleLineSpans(text: String, regex: Regex): String {
        return regex.replace(text) { m -> " ".repeat(m.value.length) }
    }

    private fun sanitizeMarkdownForInlineTags(markdownText: String): String {
        var text = blankFrontmatter(markdownText)
        text = maskMultilineBlocks(text, CODE_FENCE_RE)
        text = maskSingleLineSpans(text, INLINE_CODE_RE)
        text = maskMultilineBlocks(text, HTML_COMMENT_RE)
        text = maskSingleLineSpans(text, WIKILINK_MASK_RE)
        text = maskSingleLineSpans(text, MD_LINK_MASK_RE)
        return maskSingleLineSpans(text, HEADING_MASK_RE)
    }

    private fun isValidInlineTag(rawTag: String): Boolean {
        if (rawTag.length < 2) return false
        val isHex = (rawTag.length == 3 || rawTag.length == 6 || rawTag.length == 8) &&
            rawTag.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        return !isHex
    }

    private fun hasTrailingWordChar(text: String, lastIndex: Int): Boolean {
        val nextCharIdx = lastIndex + 1
        return nextCharIdx < text.length && text[nextCharIdx].isLetterOrDigit()
    }

    fun extractSanitizedInlineTags(markdownText: String): List<Pair<String, Int>> {
        val text = sanitizeMarkdownForInlineTags(markdownText)
        val results = mutableListOf<Pair<String, Int>>()

        var currentLine = 1
        var lastIdx = 0
        for (match in TAG_SCAN_RE.findAll(text)) {
            val hashRelIdx = match.value.indexOf('#')
            val hashIdx = match.range.first + hashRelIdx
            currentLine += text.substring(lastIdx, hashIdx).count { it == '\n' }
            lastIdx = hashIdx

            if (hasTrailingWordChar(text, match.range.last)) continue

            val rawTag = match.groupValues[1].trimEnd('/', '-', '_')
            if (isValidInlineTag(rawTag)) {
                results.add(Pair(rawTag, currentLine))
            }
        }
        return results
    }
}
