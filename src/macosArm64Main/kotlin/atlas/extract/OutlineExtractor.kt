package atlas.extract

import atlas.cli.AtlasCli
import atlas.model.GraphNode
import atlas.model.GraphReader
import atlas.util.PlatformFiles

/**
 * High-performance structural outline extractor and formatter for polyglot code and Markdown files.
 * Adheres to BOOST-003 with a single public top-level declaration.
 * Enforces a strict output ceiling of <= 400 bytes on stdout and MCP.
 */
object OutlineExtractor {

    const val TRUNCATION_INDICATOR = "... [truncated to <=400B]"

    data class OutlineResult(
        val text: String,
        val file: String,
        val symbolsCount: Int,
        val source: String,
        val byteSize: Int,
        val error: String? = null
    )

    fun extract(
        store: GraphReader,
        projectId: String,
        targetFile: String,
        workspaceRoot: String
    ): OutlineResult {
        val clean = targetFile.trim().trim('\'', '"')
        if (clean.isEmpty()) {
            val errText = clampUtf8("Error: File parameter is required.", AtlasCli.SUMMARY_CEILING_BYTES)
            return OutlineResult(
                text = errText,
                file = "",
                symbolsCount = 0,
                source = "none",
                byteSize = errText.encodeToByteArray().size,
                error = clampUtf8("Missing required file parameter for outline", AtlasCli.SUMMARY_CEILING_BYTES)
            )
        }

        val effectiveWorkspace = workspaceRoot.trim()
        val relPath = resolveRelativePath(store, projectId, clean, effectiveWorkspace)
        val ext = relPath.substringAfterLast('.', "").lowercase()

        // 1. Markdown outline: ALWAYS route .md and .markdown to fence-aware disk extractor
        if (ext == "md" || ext == "markdown") {
            val fullDiskPath = resolveDiskPath(clean, relPath, effectiveWorkspace)
            if (!PlatformFiles.exists(fullDiskPath)) {
                val errText = clampUtf8("Error: File '$clean' not found.", AtlasCli.SUMMARY_CEILING_BYTES)
                val errDetail = clampUtf8("File not found: $clean", AtlasCli.SUMMARY_CEILING_BYTES)
                return OutlineResult(
                    text = errText,
                    file = relPath,
                    symbolsCount = 0,
                    source = "none",
                    byteSize = errText.encodeToByteArray().size,
                    error = errDetail
                )
            }

            val content = PlatformFiles.readText(fullDiskPath)
            val headings = MarkdownOutlineExtractor.extract(content)
            val text = formatMarkdownHeadings(relPath, headings)
            return OutlineResult(
                text = text,
                file = relPath,
                symbolsCount = headings.size,
                source = "markdown",
                byteSize = text.encodeToByteArray().size
            )
        }

        // 2. Fast-Path SQLite query for indexed polyglot code files
        val indexedNodes = store.getNodesForFile(projectId, relPath)
            .filter { it.kind != "file_scope" }
            .sortedBy { it.startLine }

        if (indexedNodes.isNotEmpty()) {
            val text = formatFromNodes(relPath, indexedNodes)
            return OutlineResult(
                text = text,
                file = relPath,
                symbolsCount = indexedNodes.size,
                source = "sqlite",
                byteSize = text.encodeToByteArray().size
            )
        }

        // 3. Physical disk check for unindexed or dirty polyglot code files
        val fullDiskPath = resolveDiskPath(clean, relPath, effectiveWorkspace)

        if (!PlatformFiles.exists(fullDiskPath)) {
            val errText = clampUtf8("Error: File '$clean' not found.", AtlasCli.SUMMARY_CEILING_BYTES)
            val errDetail = clampUtf8("File not found: $clean", AtlasCli.SUMMARY_CEILING_BYTES)
            return OutlineResult(
                text = errText,
                file = relPath,
                symbolsCount = 0,
                source = "none",
                byteSize = errText.encodeToByteArray().size,
                error = errDetail
            )
        }

        val content = PlatformFiles.readText(fullDiskPath)

        // 4. In-Memory Polyglot Fallback
        val (dynamicNodes, _, _) = PolyglotAstExtractor.extractFile(projectId, relPath, content)
        val filteredNodes = dynamicNodes
            .filter { it.kind != "file_scope" }
            .sortedBy { it.startLine }

        val text = formatFromNodes(relPath, filteredNodes)
        return OutlineResult(
            text = text,
            file = relPath,
            symbolsCount = filteredNodes.size,
            source = "polyglot_fallback",
            byteSize = text.encodeToByteArray().size
        )
    }

    fun formatFromNodes(relPath: String, nodes: List<GraphNode>): String {
        val header = "### Outline: $relPath\n"
        if (nodes.isEmpty()) {
            return clampUtf8("${header}(no symbols found)", AtlasCli.SUMMARY_CEILING_BYTES)
        }

        val lines = nodes.map { node ->
            val prefix = if (node.isTopLevel) "- " else "  - "
            val lineSpan = if (node.startLine == node.endLine) "${node.startLine}" else "${node.startLine}-${node.endLine}"
            "$prefix${node.kind} ${node.name}: $lineSpan"
        }

        return enforceBudget(header, lines)
    }

    fun formatMarkdown(relPath: String, content: String): String {
        val headings = MarkdownOutlineExtractor.extract(content)
        return formatMarkdownHeadings(relPath, headings)
    }

    private fun formatMarkdownHeadings(relPath: String, headings: List<MarkdownHeading>): String {
        val header = "### Outline: $relPath\n"
        if (headings.isEmpty()) {
            return clampUtf8("${header}(no headings found)", AtlasCli.SUMMARY_CEILING_BYTES)
        }

        val lines = headings.map { h ->
            val headingPart = if (h.title.isEmpty()) h.hashes else "${h.hashes} ${h.title}"
            "- $headingPart: ${h.line}"
        }

        return enforceBudget(header, lines)
    }

    private fun enforceBudget(
        header: String,
        lines: List<String>,
        ceilingBytes: Int = AtlasCli.SUMMARY_CEILING_BYTES
    ): String {
        val indicator = TRUNCATION_INDICATOR
        val indicatorWithNewline = "\n$indicator"
        val indicatorBytes = indicatorWithNewline.encodeToByteArray().size
        val maxAllowedBeforeIndicator = ceilingBytes - indicatorBytes

        val sb = StringBuilder(header)
        var currentBytes = header.encodeToByteArray().size
        var truncated = false

        for (i in lines.indices) {
            val candidate = lines[i] + "\n"
            val candidateBytes = candidate.encodeToByteArray().size
            if (currentBytes + candidateBytes > maxAllowedBeforeIndicator) {
                truncated = true
                break
            }
            sb.append(candidate)
            currentBytes += candidateBytes
        }

        if (truncated) {
            sb.append(indicator)
        }

        val result = sb.toString().trimEnd()
        return clampUtf8(result, ceilingBytes)
    }

    internal fun clampUtf8(text: String, maxBytes: Int = AtlasCli.SUMMARY_CEILING_BYTES): String {
        val bytes = text.encodeToByteArray()
        val targetMax = if (maxBytes == AtlasCli.SUMMARY_CEILING_BYTES) maxBytes - 1 else maxBytes
        if (bytes.size <= targetMax) return text
        val indicator = TRUNCATION_INDICATOR
        val indBytes = indicator.encodeToByteArray()
        var end = targetMax - indBytes.size
        if (end < 0) end = 0
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOfRange(0, end).decodeToString() + indicator
    }

    private fun resolveDiskPath(clean: String, relPath: String, effectiveWorkspace: String): String =
        when {
            clean.startsWith("/") && PlatformFiles.exists(clean) -> clean
            effectiveWorkspace.isEmpty() -> relPath
            effectiveWorkspace.endsWith("/") -> "$effectiveWorkspace$relPath"
            else -> "$effectiveWorkspace/$relPath"
        }

    private fun resolveRelativePath(
        store: GraphReader,
        projectId: String,
        clean: String,
        effectiveWorkspace: String
    ): String {
        val normalized = clean.removePrefix(effectiveWorkspace).trimStart('/')
        val allFiles = store.listIndexedFiles(projectId)
        val exact = allFiles.firstOrNull { it == normalized || it == clean.trimStart('/') }
        if (exact != null) return exact
        val suffixMatch = allFiles.firstOrNull { it.endsWith("/$normalized") || it.endsWith(normalized) }
        if (suffixMatch != null) return suffixMatch
        return normalized
    }

    internal data class MarkdownHeading(
        val level: Int,
        val hashes: String,
        val title: String,
        val line: Int
    )

    internal object MarkdownOutlineExtractor {
        private val ATX_HEADING_REGEX = Regex("""^(#{1,6})(?:\s+(.*)|$)""")
        private val TRAILING_HASHES_REGEX = Regex("""\s+#+$""")

        fun extract(markdownText: String): List<MarkdownHeading> {
            val lines = markdownText.lines()
            val entries = mutableListOf<MarkdownHeading>()
            var inCodeFence = false
            var fenceChar = ' '
            var fenceLen = 0

            for (idx in lines.indices) {
                val line = lines[idx]
                val trimmed = line.trim()
                val lineNum = idx + 1

                if (!inCodeFence) {
                    if (line.startsWith("    ") || line.startsWith("\t")) {
                        continue
                    }
                    if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                        inCodeFence = true
                        fenceChar = trimmed[0]
                        fenceLen = trimmed.takeWhile { it == fenceChar }.length
                        continue
                    }
                } else {
                    if (trimmed.takeWhile { it == fenceChar }.length >= fenceLen &&
                        trimmed.dropWhile { it == fenceChar }.trim().isEmpty()
                    ) {
                        inCodeFence = false
                    }
                    continue
                }

                val match = ATX_HEADING_REGEX.matchEntire(trimmed) ?: continue
                val hashes = match.groupValues[1]
                val rawTitle = match.groupValues[2].trim()
                val title = rawTitle.replace(TRAILING_HASHES_REGEX, "").trim()
                entries.add(MarkdownHeading(hashes.length, hashes, title, lineNum))
            }
            return entries
        }
    }
}
