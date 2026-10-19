package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.model.GraphNode
import atlas.model.LiveSourceSlice
import atlas.util.PlatformFiles
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * High-performance, zero-latency engine implementing drop-in CodeGraph compatibility for Tescode.
 * Provides codegraph_explore, codegraph_node, codegraph_search, codegraph_callers,
 * codegraph_callees, codegraph_impact, codegraph_status, and codegraph_files.
 */
class CodegraphCompatibilityEngine(
    val store: SqliteGraphStore,
    val workspaceRoot: String
) {

    companion object {
        private val STOP_WORDS = setOf(
            "how", "does", "the", "work", "what", "is", "where", "to", "in", "for", "a",
            "an", "and", "or", "of", "from", "with", "by", "on", "at", "it", "this", "that"
        )

        private val EXTENSION_LANG_MAP = mapOf(
            "kt" to "kotlin", "kts" to "kotlin",
            "swift" to "swift",
            "ts" to "typescript", "tsx" to "tsx",
            "js" to "javascript", "jsx" to "jsx",
            "py" to "python",
            "rs" to "rust",
            "go" to "go",
            "java" to "java",
            "c" to "c", "h" to "c",
            "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp",
            "json" to "json",
            "xml" to "xml",
            "yaml" to "yaml", "yml" to "yaml"
        )
    }

    fun explore(projectId: String, query: String, maxFiles: Int = 12): JsonObject {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return buildJsonObject {
                put("text", "Query cannot be empty.")
            }
        }

        val tokens = tokenizeQuery(trimmedQuery)
        val matchedNodes = collectExploreNodes(projectId, trimmedQuery, tokens)
        if (matchedNodes.isEmpty()) {
            return buildJsonObject {
                put("text", "No relevant code found for \"$trimmedQuery\" in project '$projectId'.")
            }
        }

        val effectiveMaxFiles = maxFiles.coerceIn(1, 20)
        val sb = StringBuilder()
        sb.append("### CodeGraph Explore Context for \"$trimmedQuery\"\n\n")

        appendTargetFiles(projectId, sb, matchedNodes, effectiveMaxFiles)
        appendCallRelationships(projectId, sb, matchedNodes)
        appendFlowPath(projectId, sb, tokens, matchedNodes)

        return buildJsonObject {
            put("text", sb.toString().trimEnd())
        }
    }

    private fun collectExploreNodes(projectId: String, trimmedQuery: String, tokens: List<String>): List<GraphNode> {
        val matchedNodes = mutableListOf<GraphNode>()
        val seenNodeIds = mutableSetOf<String>()

        collectTokenMatches(projectId, tokens, matchedNodes, seenNodeIds)
        if (matchedNodes.size < 5) {
            collectFullQueryMatches(projectId, trimmedQuery, matchedNodes, seenNodeIds)
        }
        return matchedNodes
    }

    private fun collectTokenMatches(
        projectId: String,
        tokens: List<String>,
        matchedNodes: MutableList<GraphNode>,
        seenNodeIds: MutableSet<String>
    ) {
        for (token in tokens) {
            val directNode = store.getNode(projectId, token)
            if (directNode != null && seenNodeIds.add(directNode.nodeId)) {
                matchedNodes.add(directNode)
            }
            val partials = store.searchNodes(projectId, token, limit = 8)
            for (p in partials) {
                if (seenNodeIds.add(p.nodeId)) {
                    matchedNodes.add(p)
                }
            }
        }
    }

    private fun collectFullQueryMatches(
        projectId: String,
        query: String,
        matchedNodes: MutableList<GraphNode>,
        seenNodeIds: MutableSet<String>
    ) {
        val fullMatches = store.searchNodes(projectId, query, limit = 15)
        for (m in fullMatches) {
            if (seenNodeIds.add(m.nodeId)) {
                matchedNodes.add(m)
            }
        }
    }

    private fun appendTargetFiles(projectId: String, sb: StringBuilder, matchedNodes: List<GraphNode>, maxFiles: Int) {
        val nodesByFile = matchedNodes.groupBy { node ->
            if (node.fileId.contains(':')) node.fileId.substringAfter(':') else node.fileId
        }
        val targetFiles = nodesByFile.keys.take(maxFiles)

        for (fileRelPath in targetFiles) {
            val fileNodes = nodesByFile[fileRelPath] ?: emptyList()
            sb.append("## $fileRelPath\n")
            for (node in fileNodes.take(3)) {
                appendNodeSourcePreview(projectId, sb, node, fileRelPath)
            }
        }
    }

    private fun appendNodeSourcePreview(projectId: String, sb: StringBuilder, node: GraphNode, fileRelPath: String) {
        val slice = store.sliceLiveSource(workspaceRoot, projectId, node)
        val lang = detectLanguage(fileRelPath)
        sb.append("```$lang\n")
        if (slice.source.isNotEmpty()) {
            sb.append(slice.source).append("\n")
        } else {
            sb.append("// ${node.kind} ${node.name} (lines ${node.startLine}-${node.endLine})\n")
            if (!node.signature.isNullOrEmpty()) {
                sb.append("${node.signature}\n")
            }
        }
        sb.append("```\n\n")
    }

    private fun appendCallRelationships(projectId: String, sb: StringBuilder, matchedNodes: List<GraphNode>) {
        val relationships = mutableListOf<String>()
        val topPrimaryNodes = matchedNodes.take(6)
        for (node in topPrimaryNodes) {
            val callers = store.getCallers(projectId, node.nodeId, depth = 1).take(4)
            for (c in callers) {
                relationships.add("- `${c.name}` calls `${node.name}`")
            }
            val callees = store.getCallees(projectId, node.nodeId, depth = 1).take(4)
            for (c in callees) {
                relationships.add("- `${node.name}` calls `${c.name}`")
            }
        }

        if (relationships.isNotEmpty()) {
            sb.append("### Call Relationships\n")
            relationships.distinct().take(12).forEach { sb.append(it).append("\n") }
            sb.append("\n")
        }
    }

    private fun appendFlowPath(projectId: String, sb: StringBuilder, tokens: List<String>, matchedNodes: List<GraphNode>) {
        if (tokens.size < 2) return
        val firstNode = matchedNodes.firstOrNull { it.name.equals(tokens[0], ignoreCase = true) }
        val secondNode = matchedNodes.firstOrNull { it.name.equals(tokens[1], ignoreCase = true) }
        if (firstNode != null && secondNode != null && firstNode.nodeId != secondNode.nodeId) {
            val path = store.getShortestPath(projectId, firstNode.nodeId, secondNode.nodeId)
            if (path.isNotEmpty()) {
                sb.append("### Call Flow Path\n")
                val pathStr = path.map { id -> id.substringAfterLast('#').substringAfterLast('.') }
                sb.append(pathStr.joinToString(" -> ") { "`$it`" }).append("\n\n")
            }
        }
    }

    fun node(
        projectId: String,
        symbol: String?,
        file: String?,
        includeCode: Boolean = false,
        offset: Int? = null,
        limit: Int? = null,
        symbolsOnly: Boolean = false,
        line: Int? = null
    ): JsonObject {
        if (symbol.isNullOrBlank() && !file.isNullOrBlank()) {
            return readNodeFile(projectId, file, symbolsOnly, offset, limit)
        }

        val targetSymbol = symbol?.trim().orEmpty()
        if (targetSymbol.isEmpty()) {
            return buildJsonObject {
                put("text", "Either 'symbol' or 'file' must be specified.")
            }
        }

        return inspectNodeSymbol(projectId, targetSymbol, file, line, includeCode)
    }

    private fun readNodeFile(
        projectId: String,
        file: String,
        symbolsOnly: Boolean,
        offset: Int?,
        limit: Int?
    ): JsonObject {
        val resolvedRelPath = resolveFilePath(projectId, file)
            ?: return buildJsonObject {
                put("text", "No indexed file matches \"$file\".")
            }

        val fullDiskPath = if (workspaceRoot.endsWith("/")) "$workspaceRoot$resolvedRelPath" else "$workspaceRoot/$resolvedRelPath"
        if (!PlatformFiles.exists(fullDiskPath)) {
            return buildJsonObject {
                put("text", "File missing on disk: $fullDiskPath")
            }
        }

        return if (symbolsOnly) {
            readNodeFileSymbols(projectId, resolvedRelPath)
        } else {
            readNodeFileLines(resolvedRelPath, fullDiskPath, offset, limit)
        }
    }

    private fun readNodeFileSymbols(projectId: String, resolvedRelPath: String): JsonObject {
        val fileNodes = store.getNodesForFile(projectId, resolvedRelPath)
        val sb = StringBuilder()
        sb.append("### Symbols in `$resolvedRelPath` (${fileNodes.size} symbols)\n\n")
        fileNodes.forEach { n ->
            sb.append("- **${n.name}** (`${n.kind}`) lines ${n.startLine}-${n.endLine}")
            if (!n.signature.isNullOrEmpty()) sb.append(": `${n.signature}`")
            sb.append("\n")
        }
        return buildJsonObject {
            put("text", sb.toString().trimEnd())
        }
    }

    private fun readNodeFileLines(
        resolvedRelPath: String,
        fullDiskPath: String,
        offset: Int?,
        limit: Int?
    ): JsonObject {
        val rawBytes = PlatformFiles.readBytes(fullDiskPath)
        val lines = rawBytes.decodeToString().lines()
        val total = lines.size
        val startLine = (offset ?: 1).coerceAtLeast(1)
        val lineLimit = (limit ?: 2000).coerceAtLeast(1)

        if (startLine > total) {
            return buildJsonObject {
                put("text", "**$resolvedRelPath** has $total lines — offset $startLine is past the end.")
            }
        }

        val endLine = (startLine + lineLimit - 1).coerceAtMost(total)
        val sb = StringBuilder()
        sb.append("// $resolvedRelPath (lines $startLine-$endLine of $total)\n")
        for (idx in (startLine - 1) until endLine) {
            sb.append("${idx + 1}\t${lines[idx]}\n")
        }

        return buildJsonObject {
            put("text", sb.toString().trimEnd())
        }
    }

    private fun inspectNodeSymbol(
        projectId: String,
        targetSymbol: String,
        file: String?,
        line: Int?,
        includeCode: Boolean
    ): JsonObject {
        val node = findNode(projectId, targetSymbol, file, line)
            ?: return buildSymbolNotFoundResponse(projectId, targetSymbol)

        val sb = StringBuilder()
        val relPath = if (node.fileId.contains(':')) node.fileId.substringAfter(':') else node.fileId
        sb.append("### Symbol: `${node.name}` (${node.kind})\n")
        sb.append("- **Location:** `$relPath:${node.startLine}-${node.endLine}`\n")
        if (!node.qualifiedName.isNullOrEmpty()) sb.append("- **Qualified:** `${node.qualifiedName}`\n")
        if (!node.signature.isNullOrEmpty()) sb.append("- **Signature:** `${node.signature}`\n")
        sb.append("- **Complexity:** cognitive=${node.cognitiveComplexity}, cyclomatic=${node.cyclomaticComplexity}\n")
        sb.append("- **Connections:** in-degree=${node.inDegree}, out-degree=${node.outDegree}\n\n")

        if (includeCode) {
            appendSymbolSourceCode(projectId, sb, node, relPath)
        }

        appendSymbolCallersCallees(projectId, sb, node)

        return buildJsonObject {
            put("text", sb.toString().trimEnd())
        }
    }

    private fun buildSymbolNotFoundResponse(projectId: String, targetSymbol: String): JsonObject {
        val candidates = store.searchNodes(projectId, targetSymbol, limit = 5)
        val sb = StringBuilder()
        sb.append("Symbol \"$targetSymbol\" not found in project '$projectId'.")
        if (candidates.isNotEmpty()) {
            sb.append(" Did you mean:\n")
            candidates.forEach { c ->
                sb.append("- **${c.name}** (${c.kind}) at `${c.fileId.substringAfter(":")}:${c.startLine}`\n")
            }
        }
        return buildJsonObject {
            put("text", sb.toString().trimEnd())
        }
    }

    private fun appendSymbolSourceCode(projectId: String, sb: StringBuilder, node: GraphNode, relPath: String) {
        val slice = store.sliceLiveSource(workspaceRoot, projectId, node)
        val lang = detectLanguage(relPath)
        sb.append("```$lang\n")
        if (slice.source.isNotEmpty()) {
            sb.append(slice.source).append("\n")
        } else {
            sb.append("// Source unavailable\n")
        }
        sb.append("```\n\n")
    }

    private fun appendSymbolCallersCallees(projectId: String, sb: StringBuilder, node: GraphNode) {
        val callers = store.getCallers(projectId, node.nodeId, depth = 1).take(10)
        if (callers.isNotEmpty()) {
            sb.append("#### Direct Callers (${callers.size}):\n")
            callers.forEach { c ->
                val f = if (c.fileId.contains(':')) c.fileId.substringAfter(':') else c.fileId
                sb.append("- `${c.name}` in `$f:${c.startLine}`\n")
            }
            sb.append("\n")
        }

        val callees = store.getCallees(projectId, node.nodeId, depth = 1).take(10)
        if (callees.isNotEmpty()) {
            sb.append("#### Direct Callees (${callees.size}):\n")
            callees.forEach { c ->
                val f = if (c.fileId.contains(':')) c.fileId.substringAfter(':') else c.fileId
                sb.append("- `${c.name}` in `$f:${c.startLine}`\n")
            }
            sb.append("\n")
        }
    }

    fun search(projectId: String, query: String, kind: String? = null, limit: Int = 10): JsonObject {
        val matches = store.searchNodes(projectId, query, limit = limit * 2)
        val filtered = if (!kind.isNullOrBlank()) {
            matches.filter { it.kind.equals(kind.trim(), ignoreCase = true) }
        } else {
            matches
        }.take(limit)

        if (filtered.isEmpty()) {
            return buildJsonObject {
                put("text", "No symbols matching \"$query\" found in project '$projectId'.")
            }
        }

        val sb = StringBuilder()
        sb.append("### Search Results for \"$query\" (${filtered.size} matches):\n\n")
        filtered.forEach { n ->
            val relPath = if (n.fileId.contains(':')) n.fileId.substringAfter(':') else n.fileId
            sb.append("- **${n.name}** (`${n.kind}`) in `$relPath:${n.startLine}`")
            if (!n.signature.isNullOrEmpty()) sb.append(" — `${n.signature}`")
            sb.append("\n")
        }

        return buildJsonObject {
            put("text", sb.toString().trimEnd())
        }
    }

    fun callers(projectId: String, symbol: String, file: String? = null, limit: Int = 20): JsonObject {
        val node = findNode(projectId, symbol, file)
            ?: return buildJsonObject {
                put("text", "Symbol \"$symbol\" not found in project '$projectId'.")
            }

        val callers = store.getCallers(projectId, node.nodeId, depth = 1).take(limit)
        val sb = StringBuilder()
        sb.append("### Callers of `${node.name}` (${callers.size}):\n\n")
        if (callers.isEmpty()) {
            sb.append("No callers found in index.\n")
        } else {
            callers.forEach { c ->
                val f = if (c.fileId.contains(':')) c.fileId.substringAfter(':') else c.fileId
                sb.append("- `${c.name}` in `$f:${c.startLine}`\n")
            }
        }

        return buildJsonObject {
            put("text", sb.toString().trimEnd())
        }
    }

    fun callees(projectId: String, symbol: String, file: String? = null, limit: Int = 20): JsonObject {
        val node = findNode(projectId, symbol, file)
            ?: return buildJsonObject {
                put("text", "Symbol \"$symbol\" not found in project '$projectId'.")
            }

        val callees = store.getCallees(projectId, node.nodeId, depth = 1).take(limit)
        val sb = StringBuilder()
        sb.append("### Callees of `${node.name}` (${callees.size}):\n\n")
        if (callees.isEmpty()) {
            sb.append("No callees found in index.\n")
        } else {
            callees.forEach { c ->
                val f = if (c.fileId.contains(':')) c.fileId.substringAfter(':') else c.fileId
                sb.append("- `${c.name}` in `$f:${c.startLine}`\n")
            }
        }

        return buildJsonObject {
            put("text", sb.toString().trimEnd())
        }
    }

    fun impact(projectId: String, symbol: String, file: String? = null, depth: Int = 2): JsonObject {
        val node = findNode(projectId, symbol, file)
            ?: return buildJsonObject {
                put("text", "Symbol \"$symbol\" not found in project '$projectId'.")
            }

        val impact = store.getImpactRadius(projectId, node.nodeId, depth = depth)
        val sb = StringBuilder()
        sb.append("### Impact Blast Radius for `${node.name}` (depth: $depth)\n\n")
        sb.append("- **Total Affected Nodes:** ${impact.impactedNodes.size}\n")
        sb.append("- **Total Affected Files:** ${impact.impactedFiles.size}\n\n")

        val directCallers = impact.callers
        if (directCallers.isNotEmpty()) {
            sb.append("#### Direct Callers (${directCallers.size}):\n")
            directCallers.take(15).forEach { c ->
                val f = if (c.fileId.contains(':')) c.fileId.substringAfter(':') else c.fileId
                sb.append("- `${c.name}` in `$f:${c.startLine}`\n")
            }
            sb.append("\n")
        }

        val transitive = impact.impactedNodes.filter { it.nodeId != node.nodeId }
        if (transitive.isNotEmpty()) {
            sb.append("#### Impacted Nodes (${transitive.size}):\n")
            transitive.take(15).forEach { n ->
                val f = if (n.fileId.contains(':')) n.fileId.substringAfter(':') else n.fileId
                sb.append("- `${n.name}` (${n.kind}) in `$f:${n.startLine}`\n")
            }
            sb.append("\n")
        }

        return buildJsonObject {
            put("text", sb.toString().trimEnd())
        }
    }

    fun status(projectId: String): JsonObject {
        val stats = store.getStats(projectId)
        val sb = StringBuilder()
        sb.append("### Atlas Index Status (Project: `$projectId`)\n\n")
        sb.append("- **Indexed Files:** ${stats.files}\n")
        sb.append("- **AST Nodes:** ${stats.nodes}\n")
        sb.append("- **Graph Edges:** ${stats.edges}\n")
        sb.append("- **Database:** `${store.dbPath}`\n")
        sb.append("- **Engine:** Atlas Native AOT (sub-10ms query latency)\n")

        return buildJsonObject {
            put("text", sb.toString().trimEnd())
        }
    }

    fun files(projectId: String, path: String? = null, pattern: String? = null, format: String = "tree"): JsonObject {
        val allFiles = store.listIndexedFiles(projectId)
        val prefix = path?.trim()?.trimStart('/')?.removeSuffix("/") ?: ""
        var filtered = if (prefix.isNotEmpty()) {
            allFiles.filter { it.startsWith(prefix) }
        } else {
            allFiles
        }

        if (!pattern.isNullOrBlank()) {
            val glob = pattern.trim()
            val regex = globToRegex(glob)
            filtered = filtered.filter { regex.matches(it) }
        }

        if (filtered.isEmpty()) {
            return buildJsonObject {
                put("text", "No files matching criteria found in project '$projectId'.")
            }
        }

        val sb = StringBuilder()
        sb.append("### Indexed Files in `$projectId` (${filtered.size} files)\n\n")

        if (format.equals("flat", ignoreCase = true)) {
            filtered.take(200).forEach { sb.append("- `$it`\n") }
        } else {
            // Tree format
            val limited = filtered.take(150)
            limited.forEach { sb.append("- `$it`\n") }
            if (filtered.size > 150) {
                sb.append("... and ${filtered.size - 150} more files.\n")
            }
        }

        return buildJsonObject {
            put("text", sb.toString().trimEnd())
        }
    }

    private fun findNode(projectId: String, symbol: String, file: String? = null, line: Int? = null): GraphNode? {
        val cleanSymbol = symbol.trim()
        if (!file.isNullOrBlank()) {
            val nodesInFile = store.getNodesForFile(projectId, file.trim())
            val matched = findNodeInFile(nodesInFile, cleanSymbol, line)
            if (matched != null) return matched
        }

        return store.getNode(projectId, cleanSymbol)
            ?: store.searchNodes(projectId, cleanSymbol, limit = 1).firstOrNull()
    }

    private fun findNodeInFile(nodesInFile: List<GraphNode>, symbol: String, line: Int?): GraphNode? {
        if (nodesInFile.isEmpty()) return null
        if (line != null) {
            val atLine = nodesInFile.firstOrNull { it.name == symbol && it.startLine <= line && it.endLine >= line }
            if (atLine != null) return atLine
        }
        return nodesInFile.firstOrNull { it.name == symbol }
    }

    private fun resolveFilePath(projectId: String, fileQuery: String): String? {
        val clean = fileQuery.trim().trimStart('/')
        val allFiles = store.listIndexedFiles(projectId)
        val exact = allFiles.firstOrNull { it == clean }
        if (exact != null) return exact

        val suffixMatch = allFiles.firstOrNull { it.endsWith("/$clean") || it.endsWith(clean) }
        if (suffixMatch != null) return suffixMatch

        return allFiles.firstOrNull { it.contains(clean) }
    }

    private fun tokenizeQuery(query: String): List<String> {
        return query
            .split(" ", "\t", ",", ";", "\n")
            .map { it.trim().trim('"', '\'', '`', '(', ')', '[', ']', '{', '}') }
            .filter { it.length >= 2 && !STOP_WORDS.contains(it.lowercase()) }
    }

    private fun detectLanguage(filePath: String): String {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return EXTENSION_LANG_MAP[ext] ?: ""
    }

    private fun globToRegex(glob: String): Regex {
        val pattern = buildString {
            append("^")
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when (c) {
                    '*' -> {
                        if (i + 1 < glob.length && glob[i + 1] == '*') {
                            append(".*")
                            i++
                        } else {
                            append("[^/]*")
                        }
                    }
                    '?' -> append(".")
                    '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                        append("\\")
                        append(c)
                    }
                    else -> append(c)
                }
                i++
            }
            append("$")
        }
        return Regex(pattern)
    }
}
