package atlas.bridge

import atlas.model.GraphEdge
import atlas.model.GraphNode

/**
 * Deterministic Obsidian [[Wikilink]], backtick `Symbol`, and Plan Contract graph bridge ($0 token cost).
 */
object VaultPlanBridge {

    private val WIKILINK_RE = Regex("""\[\[([^\]|#]+)(?:#[^\]|]+)?(?:\|[^\]]+)?\]\]""")
    private val BACKTICK_SPAN_RE = Regex("""`([A-Za-z_][A-Za-z0-9_.:/#-]{2,80})`""")

    fun extractMarkdownAndPlans(
        projectId: String,
        relPath: String,
        markdownText: String,
        indexedNodes: List<GraphNode>
    ): Pair<List<GraphNode>, List<GraphEdge>> {
        val fileId = "$projectId:$relPath"
        val normRel = relPath.replace('\\', '/')
        val isPlan = normRel.startsWith("plans/") || normRel.contains("/plans/")

        val stem = normRel.substringAfterLast('/').substringBeforeLast('.')
        val nodeKind = if (isPlan) "plan_contract" else "obsidian_note"
        val docNodeId = "$fileId#$stem"

        val statusVal = extractFrontmatterScalar(markdownText, "status").ifEmpty { "active" }
        val docNode = GraphNode(
            nodeId = docNodeId,
            projectId = projectId,
            fileId = fileId,
            name = stem,
            qualifiedName = normRel,
            kind = nodeKind,
            signature = "$nodeKind ($statusVal): $normRel",
            startLine = 1,
            endLine = markdownText.lines().size.coerceAtLeast(1),
            moduleId = if (isPlan) "plans" else "vault",
            sourceSet = "docs",
            semioticLayer = if (isPlan) "syntactic" else "semantic",
            archLayer = "infrastructure"
        )

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

        if (isPlan) {
            val scopedFiles = extractScopedFiles(markdownText)
            scopedFiles.forEachIndexed { idx, scopedFile ->
                val cleanF = scopedFile.trim().trim('\'', '"')
                val matchedNodes = byRelFile[cleanF] ?: emptyList()
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
            val targetNode = byName[symCandidate]
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

        return Pair(listOf(docNode), edges)
    }

    fun extractFrontmatterScalar(markdownText: String, key: String): String {
        if (!markdownText.startsWith("---")) return ""
        val parts = markdownText.split("---", limit = 3)
        if (parts.size < 3) return ""
        val pattern = Regex("""(?m)^\s*${Regex.escape(key)}\s*:\s*(.+)$""")
        val match = pattern.find(parts[1]) ?: return ""
        return match.groupValues[1].trim().trim('\'', '"')
    }

    fun extractScopedFiles(markdownText: String): List<String> {
        if (!markdownText.startsWith("---")) return emptyList()
        val parts = markdownText.split("---", limit = 3)
        if (parts.size < 3) return emptyList()
        val fmLines = parts[1].lines()
        var inScope = false
        var inFiles = false
        val scoped = mutableListOf<String>()
        for (line in fmLines) {
            val stripped = line.trim()
            val indent = line.length - line.trimStart().length
            if (indent == 0 && stripped.startsWith("scope:")) {
                inScope = true
                inFiles = false
                continue
            }
            if (indent == 0 && stripped.isNotEmpty() && !stripped.startsWith("#")) {
                inScope = false
                inFiles = false
            }
            if (inScope && (indent == 2 || indent == 4) && stripped.startsWith("files:")) {
                inFiles = true
                continue
            }
            if (inScope && (indent == 2 || indent == 4) && stripped.endsWith(":") && !stripped.startsWith("-")) {
                inFiles = false
            }
            if (inScope && inFiles && stripped.startsWith("- ")) {
                scoped.add(stripped.removePrefix("- ").trim())
            }
        }
        return scoped
    }
}
