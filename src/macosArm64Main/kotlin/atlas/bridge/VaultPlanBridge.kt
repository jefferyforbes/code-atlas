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

    private val SLASH_IN_DESC_RE = Regex("""(?:^|[^a-zA-Z0-9_./])\/([a-z][a-z0-9_-]{1,30})""")
    private val TRIGGERED_PATTERN_RE = Regex("""(?i)triggered(?:\s+natively)?\s+by\s+([^.\n]+)""")
    private val SLASH_COMMAND_RE = Regex("""\/([a-z][a-z0-9_-]{1,30})""")

    private val SCRIPT_EXTENSIONS = setOf("py", "sh", "bash", "zsh", "kexe", "rb", "ts", "js")
    private val NON_SCRIPT_EXTENSIONS = setOf(
        "md", "txt", "json", "yaml", "yml", "toml", "xml", "csv", "sql", "conf",
        "properties", "png", "jpg", "jpeg", "webp", "svg", "pdf"
    )
    private val IGNORED_SCRIPT_INTERPRETERS = setOf(
        "python", "python3", "bash", "sh", "zsh", "node", "bun", "deno", "uv", "poetry",
        "pip", "brew", "git", "gradlew", "./gradlew", "sudo", "export", "cd"
    )
    private val WORKSPACE_PATH_MARKERS = listOf(
        "/src/", "/plans/", "/skills/", "/app/", "/lib/", "/packages/", "/test/", "/tests/",
        "/scripts/", "/bin/", "/references/", "/plugins/"
    )
    private val WHITESPACE_RE = Regex("""\s+""")

    fun extractMarkdownAndPlans(
        projectId: String,
        relPath: String,
        markdownText: String,
        indexedNodes: List<GraphNode>
    ): Pair<List<GraphNode>, List<GraphEdge>> {
        return MarkdownPlanExtractor(projectId, relPath, markdownText, indexedNodes).extract()
    }

    private class MarkdownPlanExtractor(
        val projectId: String,
        val relPath: String,
        val markdownText: String,
        val indexedNodes: List<GraphNode>
    ) {
        val fileId = "$projectId:$relPath"
        val normRel = relPath.replace('\\', '/')
        val isPlan = normRel.startsWith("plans/") || normRel.contains("/plans/")
        val isSkill = isSkillPath(normRel)
        val stem = normRel.substringAfterLast('/').substringBeforeLast('.')
        val nodeKind = when {
            isSkill -> "skill"
            isPlan -> "plan_contract"
            else -> "obsidian_note"
        }

        val nameVal = extractFrontmatterScalar(markdownText, "name")
        val skillName = resolveSkillName()
        val docNodeId = if (isSkill) "$projectId:skill#$skillName" else "$fileId#$stem"
        val descVal = extractFrontmatterScalar(markdownText, "description")

        val docNodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val seenEdges = mutableSetOf<String>()

        val byName = mutableMapOf<String, GraphNode>()
        val byRelFile = mutableMapOf<String, MutableList<GraphNode>>()

        fun extract(): Pair<List<GraphNode>, List<GraphEdge>> {
            docNodes.add(buildDocNode())
            indexLookupMaps()
            extractTags()
            extractSkillCommands()
            extractPlanScopes()
            extractWikilinks()
            extractBacktickCodeSpans()
            extractMarkdownUrls()
            extractBareRoutes()
            extractSkillScriptExecutions()
            return Pair(docNodes, edges)
        }

        private fun isSkillPath(path: String): Boolean {
            if (path.endsWith("/SKILL.md", ignoreCase = true) || path.equals("SKILL.md", ignoreCase = true)) {
                return true
            }
            if (!path.contains("skills/")) return false
            val excluded = setOf("/references/", "/scripts/", "/examples/", "/resources/")
            if (excluded.any { path.contains(it) }) return false
            val after = path.substringAfter("skills/")
            return path.endsWith("/SKILL.md", ignoreCase = true) || (path.endsWith(".md") && !after.contains("/"))
        }

        private fun resolveSkillName(): String {
            if (!isSkill) return ""
            if (nameVal.isNotEmpty()) return nameVal
            val before = normRel.substringBeforeLast("/SKILL.md", "").substringAfterLast('/')
            return before.ifEmpty { stem }
        }

        private fun computeDocSignature(statusVal: String): String {
            return when {
                isSkill -> if (descVal.isNotEmpty()) "skill ($skillName): $descVal" else "skill: $normRel"
                else -> "$nodeKind ($statusVal): $normRel"
            }
        }

        private fun computeDocModule(): String {
            return when {
                isSkill -> "skills"
                isPlan -> "plans"
                else -> "vault"
            }
        }

        private fun computeDocSemioticLayer(): String {
            return when {
                isSkill -> "pragmatic"
                isPlan -> "syntactic"
                else -> "semantic"
            }
        }

        private fun buildDocNode(): GraphNode {
            val statusVal = extractFrontmatterScalar(markdownText, "status").ifEmpty { "active" }
            return GraphNode(
                nodeId = docNodeId,
                projectId = projectId,
                fileId = fileId,
                name = if (isSkill) skillName else stem,
                qualifiedName = if (isSkill) "skill#$skillName" else normRel,
                kind = nodeKind,
                signature = computeDocSignature(statusVal),
                startLine = 1,
                endLine = markdownText.lines().size.coerceAtLeast(1),
                moduleId = computeDocModule(),
                sourceSet = "docs",
                semioticLayer = computeDocSemioticLayer(),
                archLayer = "infrastructure",
                description = if (isSkill && descVal.isNotEmpty()) descVal else null
            )
        }

        private fun indexLookupMaps() {
            for (node in indexedNodes) {
                if (node.projectId != projectId) continue
                if (!byName.containsKey(node.name)) {
                    byName[node.name] = node
                }
                val nodeRel = node.fileId.substringAfter(':')
                byRelFile.getOrPut(nodeRel) { mutableListOf() }.add(node)
            }
        }

        private fun emitEdge(
            edgeId: String,
            targetId: String,
            edgeType: String,
            provenance: String,
            confidence: Double,
            lineNumber: Int,
            sourceId: String = docNodeId
        ): Boolean {
            if (!seenEdges.add(edgeId)) return false
            edges.add(
                GraphEdge(
                    edgeId = edgeId,
                    projectId = projectId,
                    sourceNodeId = sourceId,
                    targetNodeId = targetId,
                    edgeType = edgeType,
                    provenance = provenance,
                    confidence = confidence,
                    lineNumber = lineNumber
                )
            )
            return true
        }

        private fun emitUrlRefEdge(targetId: String, lineNo: Int) {
            emitEdge("$docNodeId:urlref:$targetId:$lineNo", targetId, "URL_REFS", "EXTRACTED_URL", 1.0, lineNo)
        }

        private fun lineNumberAt(charIndex: Int): Int {
            return markdownText.take(charIndex).count { it == '\n' } + 1
        }

        private fun extractTags() {
            val allTags = mutableMapOf<String, Int>()
            for (t in extractFrontmatterTags(markdownText)) {
                if (t.isNotEmpty() && !allTags.containsKey(t)) allTags[t] = 1
            }
            for ((t, lineNo) in extractSanitizedInlineTags(markdownText)) {
                if (t.isNotEmpty() && !allTags.containsKey(t)) allTags[t] = lineNo
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
                emitEdge("$docNodeId:tagged:$tagNodeId", tagNodeId, "TAGGED_WITH", "EXTRACTED_TAG", 1.0, lineNo)
            }
        }

        private fun collectTriggeredCommands(cmdSet: MutableSet<String>) {
            for (match in TRIGGERED_PATTERN_RE.findAll(markdownText)) {
                for (m in SLASH_COMMAND_RE.findAll(match.groupValues[1])) {
                    cmdSet.add(m.groupValues[1])
                }
            }
        }

        private fun collectSkillCommandNames(): Set<String> {
            val cmdSet = mutableSetOf<String>()
            if (skillName.isNotEmpty()) cmdSet.add(skillName)
            if (descVal.isNotEmpty()) {
                for (m in SLASH_IN_DESC_RE.findAll(descVal)) {
                    cmdSet.add(m.groupValues[1])
                }
            }
            collectTriggeredCommands(cmdSet)
            return cmdSet
        }

        private fun extractSkillCommands() {
            if (!isSkill) return
            for (cmd in collectSkillCommandNames()) {
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
                emitEdge(
                    edgeId = "$cmdNodeId:triggers:$docNodeId",
                    targetId = docNodeId,
                    edgeType = "TRIGGERS_SKILL",
                    provenance = "EXTRACTED_COMMAND",
                    confidence = 1.0,
                    lineNumber = 1,
                    sourceId = cmdNodeId
                )
            }
        }

        private fun resolveScopedNodes(cleanF: String): List<GraphNode> {
            return when {
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
        }

        private fun extractPlanScopes() {
            if (!isPlan) return
            val scopedFiles = extractScopedFiles(markdownText)
            scopedFiles.forEachIndexed { idx, scopedFile ->
                val cleanF = scopedFile.trim().trim('\'', '"').removePrefix("./")
                for (targetNode in resolveScopedNodes(cleanF)) {
                    emitEdge(
                        edgeId = "$docNodeId:scopes:${targetNode.nodeId}",
                        targetId = targetNode.nodeId,
                        edgeType = "PLAN_SCOPES_FILE",
                        provenance = "EXTRACTED_PLAN",
                        confidence = 1.0,
                        lineNumber = idx + 1
                    )
                }
            }
        }

        private fun extractWikilinks() {
            for (match in WIKILINK_RE.findAll(markdownText)) {
                val targetTitle = match.groupValues[1].trim()
                val targetStem = targetTitle.substringAfterLast('/').substringBeforeLast('.')
                val lineNo = lineNumberAt(match.range.first)
                val targetNode = byName[targetStem] ?: byName[targetTitle]
                val targetId = targetNode?.nodeId ?: "$projectId:vault_ref#$targetStem"
                emitEdge("$docNodeId:wiki:$targetId:$lineNo", targetId, "WIKILINK_REFS", "EXTRACTED_WIKILINK", 1.0, lineNo)
            }
        }

        private fun extractBacktickCodeSpans() {
            for (match in BACKTICK_SPAN_RE.findAll(markdownText)) {
                val rawToken = match.groupValues[1].trim()
                val lineNo = lineNumberAt(match.range.first)
                val symCandidate = rawToken.substringAfterLast("::").substringAfterLast('#').trimEnd('(', ')')
                val targetNode = byName[symCandidate] ?: byName[symCandidate.substringAfterLast('.')]
                if (targetNode != null) {
                    emitEdge("$docNodeId:codespan:${targetNode.nodeId}:$lineNo", targetNode.nodeId, "CODE_SPAN_REFS", "INFERRED_CODE_SPAN", 0.9, lineNo)
                }
            }
        }

        private fun resolveSkillRefName(rawLink: String): String {
            return when {
                rawLink.endsWith("/SKILL.md", ignoreCase = true) -> {
                    rawLink.substringBeforeLast("/SKILL.md").substringAfterLast('/')
                }
                rawLink.equals("SKILL.md", ignoreCase = true) -> ""
                else -> {
                    rawLink.substringAfterLast('/').substringBeforeLast('.')
                }
            }
        }

        private fun handleSkillReference(rawLink: String, lineNo: Int): Boolean {
            val isSkillRef = rawLink.endsWith("/SKILL.md", ignoreCase = true) ||
                rawLink.equals("SKILL.md", ignoreCase = true) ||
                (rawLink.contains("/skills/") && rawLink.endsWith(".md"))
            if (!isSkillRef) return false

            val targetSkillName = resolveSkillRefName(rawLink)
            if (targetSkillName.isNotEmpty() && targetSkillName != skillName) {
                val targetSkillId = "$projectId:skill#$targetSkillName"
                emitEdge("$docNodeId:depends:$targetSkillId:$lineNo", targetSkillId, "DEPENDS_ON_SKILL", "EXTRACTED_SKILL_DEP", 1.0, lineNo)
                return true
            }
            return false
        }

        private fun resolveCodeLocNode(codeLoc: UrlTargetResolver.CodeLocationLink, fileCandidates: List<GraphNode>): GraphNode? {
            return when {
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
        }

        private fun handleCodeLocationReference(rawLink: String, lineNo: Int): Boolean {
            val codeLoc = UrlTargetResolver.parseCodeLocationUrl(rawLink) ?: return false
            val fileCandidates = byRelFile[codeLoc.filePathSuffix]
                ?: byRelFile.entries.firstOrNull { (k, _) ->
                    k.endsWith("/${codeLoc.filePathSuffix}") || k == codeLoc.filePathSuffix.substringAfterLast('/')
                }?.value
                ?: emptyList()
            val resolvedNode = resolveCodeLocNode(codeLoc, fileCandidates)
            if (resolvedNode != null) {
                emitUrlRefEdge(resolvedNode.nodeId, lineNo)
                return true
            }
            return false
        }

        private fun isExternalOrSchemeUrl(rawLink: String): Boolean {
            return rawLink.startsWith("http://", ignoreCase = true) ||
                rawLink.startsWith("https://", ignoreCase = true) ||
                rawLink.startsWith("figma://", ignoreCase = true) ||
                rawLink.startsWith("sidecar://", ignoreCase = true)
        }

        private fun handleGenericUrlReference(rawLink: String, lineNo: Int) {
            if (UrlTargetResolver.isHttpRouteCandidate(rawLink, hasExplicitRouteCall = false)) {
                emitUrlRefEdge("$projectId:endpoint#${UrlTargetResolver.normalizeRouteTemplate(rawLink)}", lineNo)
            } else if (isExternalOrSchemeUrl(rawLink)) {
                emitUrlRefEdge("$projectId:url_ref#${UrlTargetResolver.normalizeExternalUrl(rawLink)}", lineNo)
                val pathPart = UrlTargetResolver.extractPathFromHttpUrl(rawLink)
                if (pathPart != null && UrlTargetResolver.isHttpRouteCandidate(pathPart, hasExplicitRouteCall = false)) {
                    emitUrlRefEdge("$projectId:endpoint#${UrlTargetResolver.normalizeRouteTemplate(pathPart)}", lineNo)
                }
            }
        }

        private fun isIgnoredLink(link: String): Boolean =
            link.isEmpty() || link.startsWith("#")

        private fun processMarkdownUrlMatch(urlMatch: MatchResult) {
            val rawLink = urlMatch.groupValues[1].trim()
            if (isIgnoredLink(rawLink)) return
            val lineNo = lineNumberAt(urlMatch.range.first)
            if (handleSkillReference(rawLink, lineNo)) return
            if (handleCodeLocationReference(rawLink, lineNo)) return
            if (handleRelativeFileReference(rawLink, lineNo)) return
            handleGenericUrlReference(rawLink, lineNo)
        }

        private fun normalizeAbsolutePath(cleanTarget: String): String {
            val matchedMarker = WORKSPACE_PATH_MARKERS.firstOrNull { cleanTarget.contains(it) }
            return if (matchedMarker != null) {
                cleanTarget.substring(cleanTarget.indexOf(matchedMarker) + 1).trimStart('/')
            } else {
                cleanTarget.trimStart('/')
            }
        }

        private fun pushPathSegment(stack: MutableList<String>, seg: String) {
            if (seg.isEmpty() || seg == ".") return
            if (seg == ".." && stack.isNotEmpty() && stack.last() != "..") {
                stack.removeAt(stack.size - 1)
            } else {
                stack.add(seg)
            }
        }

        private fun collapseDotSegments(rawCombined: String): String {
            val stack = mutableListOf<String>()
            for (seg in rawCombined.split('/')) {
                pushPathSegment(stack, seg)
            }
            return stack.joinToString("/")
        }

        private fun resolveRelativePath(baseRelPath: String, targetRel: String): String {
            val cleanTarget = targetRel.substringBefore('#').substringBefore('?').replace('\\', '/')
            if (cleanTarget.startsWith("/")) return normalizeAbsolutePath(cleanTarget)
            val baseDir = if (baseRelPath.contains('/')) baseRelPath.substringBeforeLast('/') else ""
            val rawCombined = if (baseDir.isEmpty()) cleanTarget else "$baseDir/$cleanTarget"
            return collapseDotSegments(rawCombined)
        }

        private fun isScriptPath(path: String): Boolean {
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext in NON_SCRIPT_EXTENSIONS) return false
            if (ext in SCRIPT_EXTENSIONS) return true
            val lower = path.lowercase()
            return lower.contains("/scripts/") || lower.startsWith("scripts/") ||
                lower.contains("/bin/") || lower.startsWith("bin/")
        }

        private fun isUnsupportedScheme(link: String): Boolean {
            if (link.isEmpty() || link.startsWith("#")) return true
            if (isExternalOrSchemeUrl(link)) return true
            val lower = link.lowercase()
            return lower.startsWith("mailto:") || lower.startsWith("javascript:") ||
                lower.startsWith("data:") || lower.startsWith("tel:")
        }

        private fun tryEmitSkillDependency(resolvedRelPath: String, lineNo: Int): Boolean {
            if (!isSkillPath(resolvedRelPath)) return false
            val targetSkillName = resolveSkillRefName(resolvedRelPath)
            if (targetSkillName.isEmpty() || targetSkillName == skillName) return false
            val targetSkillId = "$projectId:skill#$targetSkillName"
            emitEdge("$docNodeId:depends:$targetSkillId:$lineNo", targetSkillId, "DEPENDS_ON_SKILL", "EXTRACTED_SKILL_DEP", 1.0, lineNo)
            return true
        }

        private fun findCandidateFileNodes(resolvedRelPath: String, cleanLink: String): List<GraphNode>? {
            val withoutDotSlash = cleanLink.removePrefix("./")
            return byRelFile[resolvedRelPath]
                ?: byRelFile[withoutDotSlash]
                ?: byRelFile.entries.firstOrNull { (k, _) ->
                    k.endsWith("/$resolvedRelPath") || k.endsWith("/$withoutDotSlash")
                }?.value
        }

        private fun resolveTargetFileRecord(
            candidateNodes: List<GraphNode>?,
            resolvedRelPath: String
        ): Pair<String, Double> {
            if (candidateNodes != null) {
                val primary = candidateNodes.firstOrNull { it.kind in setOf("skill", "plan_contract", "obsidian_note", "file_scope", "file") }
                    ?: candidateNodes.firstOrNull()
                val targetId = primary?.nodeId ?: "$projectId:$resolvedRelPath"
                return Pair(targetId, 1.0)
            }
            return Pair("$projectId:$resolvedRelPath", 0.8)
        }

        private fun handleRelativeFileReference(rawLink: String, lineNo: Int): Boolean {
            if (isUnsupportedScheme(rawLink)) return false
            val cleanLink = rawLink.substringBefore('#').substringBefore('?').replace('\\', '/')
            if (cleanLink.isEmpty()) return false

            val resolvedRelPath = resolveRelativePath(normRel, cleanLink)
            if (resolvedRelPath.isEmpty()) return false
            if (tryEmitSkillDependency(resolvedRelPath, lineNo)) return true

            val edgeType = if (isScriptPath(resolvedRelPath)) "EXECUTES_SCRIPT" else "REFERENCES_FILE"
            val candidateNodes = findCandidateFileNodes(resolvedRelPath, cleanLink)
            val (targetId, confidence) = resolveTargetFileRecord(candidateNodes, resolvedRelPath)
            val provenance = if (confidence == 1.0) "EXTRACTED_MARKDOWN_LINK" else "EXTRACTED_LOCAL_FILE_REF"

            emitEdge(
                edgeId = "$docNodeId:file:$targetId:$lineNo",
                targetId = targetId,
                edgeType = edgeType,
                provenance = provenance,
                confidence = confidence,
                lineNumber = lineNo
            )
            return true
        }

        private fun isScriptCandidateToken(clean: String): Boolean {
            if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) return false
            if (clean.startsWith("-")) return false
            if (clean.contains('$') || clean.contains('*') || clean.contains('{') || clean.contains('}')) return false

            val ext = clean.substringAfterLast('.', "").lowercase()
            if (ext in NON_SCRIPT_EXTENSIONS) return false
            if (ext in SCRIPT_EXTENSIONS) return true

            val lower = clean.lowercase()
            return lower.contains("/scripts/") || lower.startsWith("scripts/") ||
                lower.contains("/bin/") || lower.startsWith("bin/") ||
                lower.startsWith("./")
        }

        private fun resolveScriptCandidatePath(token: String): String {
            val clean = token.substringBefore('?').substringBefore('#').replace('\\', '/')
            val withoutPrefix = clean.removePrefix("./")
            val stripped = when {
                withoutPrefix.contains("/plugins/") -> withoutPrefix.substring(withoutPrefix.indexOf("/plugins/") + 1)
                withoutPrefix.contains("/skills/") -> withoutPrefix.substring(withoutPrefix.indexOf("/skills/") + 1)
                withoutPrefix.startsWith("/") -> withoutPrefix.trimStart('/')
                else -> withoutPrefix
            }

            if (byRelFile.containsKey(stripped)) return stripped
            val relToSkill = resolveRelativePath(normRel, clean)
            if (byRelFile.containsKey(relToSkill)) return relToSkill

            val suffixMatch = byRelFile.keys.firstOrNull { it.endsWith("/$stripped") }
            if (suffixMatch != null) return suffixMatch

            return if (clean.startsWith("./") || clean.startsWith("../")) relToSkill else stripped
        }

        private fun extractScriptInvocationsFromLine(line: String, lineNo: Int) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return
            for (rawToken in line.split(WHITESPACE_RE)) {
                val clean = rawToken.trim('\'', '"', '`', '(', ')', '[', ']', '<', '>', ',', ';')
                processScriptToken(clean, lineNo)
            }
        }

        private fun processScriptToken(clean: String, lineNo: Int) {
            if (clean.isEmpty() || clean in IGNORED_SCRIPT_INTERPRETERS || !isScriptCandidateToken(clean)) return
            val resolvedPath = resolveScriptCandidatePath(clean)
            if (resolvedPath.isEmpty()) return

            val directMatch = byRelFile[resolvedPath]
                ?: byRelFile.entries.firstOrNull { (k, _) -> k.endsWith("/$resolvedPath") }?.value
            val primaryNode = directMatch?.firstOrNull()
            val targetId = primaryNode?.nodeId ?: "$projectId:$resolvedPath"
            val confidence = if (directMatch != null) 1.0 else 0.85

            emitEdge(
                edgeId = "$docNodeId:exec:$targetId:$lineNo",
                targetId = targetId,
                edgeType = "EXECUTES_SCRIPT",
                provenance = "EXTRACTED_SCRIPT_REF",
                confidence = confidence,
                lineNumber = lineNo
            )
        }

        private fun scanInlineCodeScriptExecutions() {
            for (match in INLINE_CODE_RE.findAll(markdownText)) {
                val spanText = match.value.trim('`').trim()
                val lineNo = lineNumberAt(match.range.first)
                extractScriptInvocationsFromLine(spanText, lineNo)
            }
        }

        private fun processCodeFenceBlock(match: MatchResult) {
            val fenceLines = match.value.lines()
            if (fenceLines.size <= 2) return
            var currentOffset = match.range.first + fenceLines.first().length + 1
            val innerLines = fenceLines.subList(1, fenceLines.size - 1)
            for (line in innerLines) {
                val lineNo = lineNumberAt(currentOffset)
                extractScriptInvocationsFromLine(line.trim(), lineNo)
                currentOffset += line.length + 1
            }
        }

        private fun scanCodeFenceScriptExecutions() {
            for (match in CODE_FENCE_RE.findAll(markdownText)) {
                processCodeFenceBlock(match)
            }
        }

        private fun extractSkillScriptExecutions() {
            if (!isSkill) return
            scanInlineCodeScriptExecutions()
            scanCodeFenceScriptExecutions()
        }

        private fun extractMarkdownUrls() {
            for (match in MARKDOWN_URL_RE.findAll(markdownText)) {
                processMarkdownUrlMatch(match)
            }
        }

        private fun extractBareRoutes() {
            for (match in BARE_ROUTE_BACKTICK_RE.findAll(markdownText)) {
                val rawRoute = match.groupValues[1].trim()
                if (UrlTargetResolver.isHttpRouteCandidate(rawRoute, hasExplicitRouteCall = false)) {
                    val lineNo = lineNumberAt(match.range.first)
                    emitUrlRefEdge("$projectId:endpoint#${UrlTargetResolver.normalizeRouteTemplate(rawRoute)}", lineNo)
                }
            }
        }
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
        return match.groupValues[1].substringBefore('#').trim().trim('\'', '"')
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

    private fun countNewlines(text: String, start: Int, end: Int): Int {
        var count = 0
        for (i in start until end) {
            if (text[i] == '\n') count++
        }
        return count
    }

    private fun tryExtractTagMatch(
        text: String,
        match: MatchResult,
        lineNo: Int
    ): Pair<String, Int>? {
        if (hasTrailingWordChar(text, match.range.last)) return null
        val rawTag = match.groupValues[1].trimEnd('/', '-', '_')
        if (!isValidInlineTag(rawTag)) return null
        return Pair(rawTag, lineNo)
    }

    fun extractSanitizedInlineTags(markdownText: String): List<Pair<String, Int>> {
        val text = sanitizeMarkdownForInlineTags(markdownText)
        val results = mutableListOf<Pair<String, Int>>()

        var currentLine = 1
        var lastIdx = 0
        for (match in TAG_SCAN_RE.findAll(text)) {
            val hashRelIdx = match.value.indexOf('#')
            val hashIdx = match.range.first + hashRelIdx
            currentLine += countNewlines(text, lastIdx, hashIdx)
            lastIdx = hashIdx

            tryExtractTagMatch(text, match, currentLine)?.let { results.add(it) }
        }
        return results
    }
}
