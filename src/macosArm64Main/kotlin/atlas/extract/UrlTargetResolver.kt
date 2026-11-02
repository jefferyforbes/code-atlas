package atlas.extract

/**
 * Sovereign offline parser and normalizer for Figma deep links, IDE/GitHub code location URLs,
 * canonical HTTP route templates, and external URL references.
 */
object UrlTargetResolver {

    data class FigmaDeepLink(val fileKey: String, val nodeId: String?, val rawUrl: String)

    data class CodeLocationLink(
        val filePathSuffix: String,
        val startLine: Int?,
        val endLine: Int?,
        val symbolFragment: String?,
        val rawUrl: String
    )

    data class ExtractedEndpointOrUrl(
        val rawValue: String,
        val canonicalRouteOrUrl: String,
        val isHttpEndpointRoute: Boolean,
        val edgeType: String, // "EXPOSES_ENDPOINT" | "CALLS_ENDPOINT" | "URL_REFS"
        val httpMethod: String? = null,
        val lineNumber: Int
    )

    private val FIGMA_URL_RE = Regex(
        """^https?://(?:www\.)?figma\.com/(?:design|file|board|proto)/([A-Za-z0-9_-]+)(?:/[^?#]*)?(?:\?([^#]*))?(?:#.*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val GITHUB_BLOB_URL_RE = Regex(
        """^https?://(?:www\.)?github\.com/[^/]+/[^/]+/(?:blob|tree)/[^/]+/([^?#]+)(?:\?[^#]*)?(?:#(.*))?$""",
        RegexOption.IGNORE_CASE
    )
    private val GITLAB_BLOB_URL_RE = Regex(
        """^https?://(?:www\.)?gitlab\.com/(?:.+?/-/(?:blob|tree)/[^/]+/([^?#]+)|[^/]+/[^/]+/(?:blob|tree)/[^/]+/([^?#]+))(?:\?[^#]*)?(?:#(.*))?$""",
        RegexOption.IGNORE_CASE
    )
    private val MARKDOWN_LINK_WRAP_RE = Regex("""^\[[^\]]*\]\(([^)]+)\)$""")
    private val LINE_FRAGMENT_RE = Regex("""^L(\d+)(?:-L?(\d+))?$""", RegexOption.IGNORE_CASE)
    private val FILE_PATH_FRAGMENT_RE = Regex("""^([A-Za-z0-9_./\\-]+\.[A-Za-z0-9]{1,8})#(.+)$""")

    private val CODE_EXTENSIONS = setOf(
        "kt", "kts", "py", "swift", "ts", "tsx", "js", "jsx", "rs", "cpp", "cc", "h", "hpp",
        "java", "go", "rb", "c", "m", "mm", "md", "yaml", "yml", "json", "toml", "sql", "xml"
    )
    private val NON_ROUTE_FILE_EXTENSIONS = CODE_EXTENSIONS + setOf(
        "db", "kexe", "png", "jpg", "webp", "svg", "pdf", "txt", "csv", "jsonl", "conf", "lock"
    )
    private val LOCAL_FS_PREFIXES = listOf("/Users/", "/home/", "/tmp/", "/var/", "/etc/")
    private val WORKSPACE_SRC_MARKERS = listOf(
        "/src/", "/plans/", "/skills/", "/app/", "/lib/", "/packages/", "/test/", "/tests/"
    )
    private val SERVER_ROUTE_CALL_RE = Regex(
        """\b(?:(?:app|router|routing|route|server)\s*\.\s*)?(get|post|put|delete|patch|head|options|route)\s*\(\s*['"`]([^'"`]+)['"`]""",
        RegexOption.IGNORE_CASE
    )
    private val SPRING_MAPPING_RE = Regex(
        """@(Get|Post|Put|Delete|Patch|Request)Mapping\s*\(\s*(?:(?:value|path)\s*=\s*)?(?:\[\s*)?['"]([^'"]+)['"]"""
    )
    private val PYTHON_ROUTE_DECORATOR_RE = Regex(
        """@[A-Za-z0-9_.]+\.(get|post|put|delete|patch|route|api_route)\s*\(\s*['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val EXPRESS_ROUTE_RE = Regex(
        """\b(?:app|router)\s*\.\s*(get|post|put|delete|patch|use|all)\s*\(\s*['"`]([^'"`]+)['"`]""",
        RegexOption.IGNORE_CASE
    )
    private val RETROFIT_ANNOTATION_RE = Regex(
        """@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|HTTP)\s*\(\s*(?:(?:value|path)\s*=\s*)?['"]([^'"]+)['"]"""
    )
    private val CLIENT_CALL_RE = Regex(
        """\b(?:(?:httpClient|client|api|http|axios)\s*\.\s*(get|post|put|delete|patch|request)|fetch)\s*(?:<[^>]*>)?\s*\(\s*['"`]([^'"`]+)['"`]""",
        RegexOption.IGNORE_CASE
    )
    private val CLIENT_CALL_RECEIVER_PREFIXES = listOf("client.", "httpclient.", "axios.", "api.", "http.")
    private val UUID_SEGMENT_RE = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")
    private val NUMERIC_SEGMENT_RE = Regex("""^\d+$""")
    private val BRACE_PARAM_RE = Regex("""\{[^{}/]+\}""")
    private val INTERP_BRACE_PARAM_RE = Regex("""\$\{[^{}/]+\}""")
    private val INTERP_VAR_PARAM_RE = Regex("""\$[A-Za-z_][A-Za-z0-9_]*""")
    private val ANGLE_PARAM_RE = Regex("""<[^<>/]+>""")
    private val COLON_PARAM_RE = Regex("""^:[A-Za-z_][A-Za-z0-9_]*$""")
    private val FIGMA_HYPHEN_NODE_ID_RE = Regex("""^I?\d+-\d+$""")
    private val ROUTE_PREFIXES = listOf(
        "/api/", "/v1/", "/v2/", "/v3/", "/graphql", "/rest/", "/webhook", "/auth/", "/oauth/", "/health", "/metrics", "/ws/"
    )
    private val SUPPORTED_URL_SCHEMES = listOf("http://", "https://", "file://", "figma://", "sidecar://")

    private fun unwrapMarkdownOrQuotes(input: String): String {
        val s = input.trim().trim('\'', '"', '`', '<', '>')
        val mdMatch = MARKDOWN_LINK_WRAP_RE.matchEntire(s) ?: return s
        return mdMatch.groupValues[1].trim().trim('\'', '"', '`', '<', '>')
    }

    fun parseFigmaUrl(input: String): FigmaDeepLink? {
        val trimmed = unwrapMarkdownOrQuotes(input)
        val match = FIGMA_URL_RE.matchEntire(trimmed) ?: return null
        return FigmaDeepLink(match.groupValues[1], extractFigmaNodeIdFromQuery(match.groupValues[2]), trimmed)
    }

    private fun extractFigmaNodeIdFromQuery(query: String): String? {
        if (query.isEmpty()) return null
        for (param in query.split('&').filter { it.isNotBlank() }) {
            val eqIdx = param.indexOf('=')
            if (eqIdx <= 0) continue
            val key = param.substring(0, eqIdx).lowercase()
            val rawVal = param.substring(eqIdx + 1)
            if ((key == "node-id" || key == "node_id") && rawVal.isNotEmpty()) {
                return normalizeFigmaNodeId(rawVal).ifEmpty { null }
            }
        }
        return null
    }

    fun normalizeFigmaNodeId(rawNodeId: String): String {
        val trimmed = unwrapMarkdownOrQuotes(rawNodeId)
        parseFigmaUrl(trimmed)?.let { return it.nodeId ?: it.fileKey }
        val decoded = trimmed
            .replace("%3A", ":").replace("%3a", ":")
            .replace("%3B", ";").replace("%3b", ";")
            .replace("%2D", "-").replace("%2d", "-")
        return decoded.split(';').filter { it.isNotBlank() }.joinToString(";") { segment ->
            val seg = segment.trim()
            if (FIGMA_HYPHEN_NODE_ID_RE.matches(seg)) seg.replace('-', ':') else seg
        }
    }

    fun parseCodeLocationUrl(input: String, workspaceRoot: String? = null): CodeLocationLink? {
        val trimmed = unwrapMarkdownOrQuotes(input)
        if (trimmed.isEmpty() || parseFigmaUrl(trimmed) != null) return null
        val (rawPath, fragment) = extractCodePathAndFragment(trimmed) ?: return null
        val normalizedPath = normalizeWorkspaceRelativePath(rawPath, workspaceRoot)
        if (normalizedPath.isEmpty()) return null
        val (startLine, endLine, symbolFragment) = parseLocationFragment(fragment)
        return CodeLocationLink(normalizedPath, startLine, endLine, symbolFragment, trimmed)
    }

    private fun extractCodePathAndFragment(trimmed: String): Pair<String, String?>? {
        if (trimmed.startsWith("file://", ignoreCase = true)) {
            return parseFileSchemePathAndFragment(trimmed.substring(7))
        }
        GITHUB_BLOB_URL_RE.matchEntire(trimmed)?.let { m ->
            return Pair(m.groupValues[1], m.groupValues[2].ifEmpty { null })
        }
        GITLAB_BLOB_URL_RE.matchEntire(trimmed)?.let { m ->
            return Pair(m.groupValues[1].ifEmpty { m.groupValues[2] }, m.groupValues[3].ifEmpty { null })
        }
        if (!trimmed.contains("://")) {
            FILE_PATH_FRAGMENT_RE.matchEntire(trimmed)?.let { m ->
                val ext = m.groupValues[1].substringAfterLast('.', "").lowercase()
                if (ext in CODE_EXTENSIONS && m.groupValues[2].isNotBlank()) {
                    return Pair(m.groupValues[1], m.groupValues[2])
                }
            }
        }
        return null
    }

    private fun parseFileSchemePathAndFragment(withoutScheme: String): Pair<String, String?> {
        val withoutHost = if (withoutScheme.startsWith("/")) {
            withoutScheme
        } else {
            val slashIdx = withoutScheme.indexOf('/')
            if (slashIdx >= 0) withoutScheme.substring(slashIdx) else withoutScheme
        }
        val pathPart = withoutHost.substringBefore('#').substringBefore('?')
        val fragment = if (withoutHost.contains('#')) withoutHost.substringAfter('#') else null
        return Pair(pathPart, fragment)
    }

    private fun normalizeWorkspaceRelativePath(rawPath: String, workspaceRoot: String?): String {
        var normalized = urlDecodePath(rawPath).replace('\\', '/')
        if (!workspaceRoot.isNullOrBlank()) {
            val cleanRoot = workspaceRoot.replace('\\', '/').trimEnd('/')
            if (cleanRoot.isNotEmpty() && normalized.startsWith("$cleanRoot/")) {
                normalized = normalized.removePrefix("$cleanRoot/")
            }
        }
        if (normalized.startsWith("/")) {
            val matchedMarker = WORKSPACE_SRC_MARKERS.firstOrNull { normalized.contains(it) }
            if (matchedMarker != null) {
                normalized = normalized.substring(normalized.indexOf(matchedMarker) + 1)
            }
            normalized = normalized.trimStart('/')
        }
        return normalized.removePrefix("./")
    }

    private fun parseLocationFragment(fragment: String?): Triple<Int?, Int?, String?> {
        if (fragment.isNullOrBlank()) return Triple(null, null, null)
        val cleanFrag = fragment.trim()
        val lineMatch = LINE_FRAGMENT_RE.matchEntire(cleanFrag) ?: return Triple(null, null, cleanFrag)
        val s = lineMatch.groupValues[1].toIntOrNull() ?: return Triple(null, null, null)
        val e = lineMatch.groupValues[2].toIntOrNull() ?: s
        return Triple(minOf(s, e), maxOf(s, e), null)
    }

    fun isUrlOrDeepLink(input: String): Boolean {
        val trimmed = unwrapMarkdownOrQuotes(input)
        if (trimmed.isEmpty()) return false
        if (SUPPORTED_URL_SCHEMES.any { trimmed.startsWith(it, ignoreCase = true) }) return true
        return parseCodeLocationUrl(trimmed) != null || isHttpRouteCandidate(trimmed, hasExplicitRouteCall = false)
    }

    fun normalizeRouteTemplate(rawPathOrUrl: String): String {
        val trimmed = rawPathOrUrl.trim().trim('\'', '"', '`')
        if (trimmed.isEmpty()) return ""
        val (schemeHostPrefix, pathPart) = splitSchemeHostAndPath(trimmed)
        if (pathPart.isEmpty()) return schemeHostPrefix
        val rawSegments = pathPart.substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
        if (rawSegments.isEmpty()) return schemeHostPrefix.ifEmpty { "/" }
        return "$schemeHostPrefix/" + rawSegments.joinToString("/") { normalizeSingleRouteSegment(it) }
    }

    private fun splitSchemeHostAndPath(trimmed: String): Pair<String, String> {
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return Pair("", trimmed)
        }
        val pathStart = trimmed.indexOf('/', trimmed.indexOf("://") + 3)
        if (pathStart < 0) {
            return Pair(trimmed.lowercase().substringBefore('?').substringBefore('#').trimEnd('/'), "")
        }
        return Pair(trimmed.substring(0, pathStart).lowercase(), trimmed.substring(pathStart))
    }

    private fun normalizeSingleRouteSegment(seg: String): String {
        if (COLON_PARAM_RE.matches(seg) || BRACE_PARAM_RE.matches(seg) || INTERP_BRACE_PARAM_RE.matches(seg) ||
            INTERP_VAR_PARAM_RE.matches(seg) || ANGLE_PARAM_RE.matches(seg) || NUMERIC_SEGMENT_RE.matches(seg) ||
            UUID_SEGMENT_RE.matches(seg)
        ) {
            return "{param}"
        }
        if (seg.contains('{') || seg.contains('$') || seg.contains('<')) {
            return seg.replace(INTERP_BRACE_PARAM_RE, "{param}")
                .replace(BRACE_PARAM_RE, "{param}")
                .replace(INTERP_VAR_PARAM_RE, "{param}")
                .replace(ANGLE_PARAM_RE, "{param}")
        }
        return seg
    }

    fun isHttpRouteCandidate(value: String, hasExplicitRouteCall: Boolean = false): Boolean {
        val s = value.trim().trim('\'', '"', '`')
        if (!isPlausibleRouteLexeme(s)) return false
        if (hasExplicitRouteCall && s.startsWith("/")) return s.length >= 2
        val lower = s.lowercase()
        if (ROUTE_PREFIXES.any { lower == it.trimEnd('/') || lower.startsWith(it) }) return true
        return hasRouteParameterPlaceholder(s)
    }

    private fun isPlausibleRouteLexeme(s: String): Boolean {
        if (s.isEmpty() || s.any { it.isWhitespace() } || s.startsWith("//") || s.startsWith("/*")) return false
        if (LOCAL_FS_PREFIXES.any { s.startsWith(it) }) return false
        val lastSeg = s.substringBefore('?').substringBefore('#').substringAfterLast('/')
        if (lastSeg.contains('.') && lastSeg.substringAfterLast('.').lowercase() in NON_ROUTE_FILE_EXTENSIONS) return false
        return true
    }

    private fun hasRouteParameterPlaceholder(s: String): Boolean {
        if (!s.startsWith("/") || s.count { it == '/' } < 2) return false
        return BRACE_PARAM_RE.containsMatchIn(s) || s.split('/').filter { it.isNotBlank() }.any { COLON_PARAM_RE.matches(it) || ANGLE_PARAM_RE.matches(it) }
    }

    fun extractCodeRoutesAndUrls(sourceText: String, maskedText: String, language: String): List<ExtractedEndpointOrUrl> {
        val results = mutableListOf<ExtractedEndpointOrUrl>()
        val seenPerLine = mutableSetOf<String>()
        val rawLines = sourceText.lines()
        val maskedLines = maskedText.lines()
        for (i in rawLines.indices) {
            val lineNo = i + 1
            val rawLine = rawLines[i]
            val maskedLine = if (i < maskedLines.size) maskedLines[i] else rawLine
            if (maskedLine.isBlank()) continue
            val effectiveLine = sliceUncommentedLine(rawLine, maskedLine)
            if (effectiveLine.isBlank()) continue
            extractFrameworkServerRoutes(effectiveLine, language, lineNo, seenPerLine, results)
            extractRetrofitAndClientRoutes(effectiveLine, lineNo, seenPerLine, results)
            extractKtorAndGenericServerRoutes(effectiveLine, lineNo, seenPerLine, results)
            extractStringLiteralUrlsAndRoutes(rawLine, maskedLine, lineNo, seenPerLine, results)
        }
        return results
    }

    private fun addExtractedItem(
        item: ExtractedEndpointOrUrl,
        seenPerLine: MutableSet<String>,
        results: MutableList<ExtractedEndpointOrUrl>
    ) {
        if (seenPerLine.add("${item.lineNumber}:${item.edgeType}:${item.canonicalRouteOrUrl}")) {
            results.add(item)
        }
    }

    private fun extractFrameworkServerRoutes(
        effectiveLine: String, language: String, lineNo: Int,
        seenPerLine: MutableSet<String>, results: MutableList<ExtractedEndpointOrUrl>
    ) {
        extractRegexServerRoutes(SPRING_MAPPING_RE, effectiveLine, lineNo, setOf("REQUEST"), seenPerLine, results)
        if (language == "python") {
            extractRegexServerRoutes(PYTHON_ROUTE_DECORATOR_RE, effectiveLine, lineNo, setOf("ROUTE", "API_ROUTE"), seenPerLine, results)
        }
        if (language == "typescript" || language == "javascript") {
            extractRegexServerRoutes(EXPRESS_ROUTE_RE, effectiveLine, lineNo, setOf("USE", "ALL"), seenPerLine, results)
        }
    }

    private fun extractRegexServerRoutes(
        regex: Regex, effectiveLine: String, lineNo: Int, anyVerbs: Set<String>,
        seenPerLine: MutableSet<String>, results: MutableList<ExtractedEndpointOrUrl>
    ) {
        for (m in regex.findAll(effectiveLine)) processFrameworkRouteMatch(m, lineNo, anyVerbs, seenPerLine, results)
    }

    private fun processFrameworkRouteMatch(
        m: MatchResult, lineNo: Int, anyVerbs: Set<String>,
        seenPerLine: MutableSet<String>, results: MutableList<ExtractedEndpointOrUrl>
    ) {
        val verb = m.groupValues[1].uppercase()
        val rawRoute = m.groupValues[2].trim()
        if (isHttpRouteCandidate(rawRoute, hasExplicitRouteCall = true)) {
            val method = if (verb in anyVerbs) "ANY" else verb
            addExtractedItem(ExtractedEndpointOrUrl(rawRoute, normalizeRouteTemplate(rawRoute), true, "EXPOSES_ENDPOINT", method, lineNo), seenPerLine, results)
        }
    }

    private fun extractRetrofitAndClientRoutes(
        effectiveLine: String, lineNo: Int, seenPerLine: MutableSet<String>, results: MutableList<ExtractedEndpointOrUrl>
    ) {
        for (m in RETROFIT_ANNOTATION_RE.findAll(effectiveLine)) {
            recordClientTarget(m.groupValues[2].trim(), m.groupValues[1].uppercase(), lineNo, seenPerLine, results)
        }
        for (m in CLIENT_CALL_RE.findAll(effectiveLine)) {
            recordClientTarget(m.groupValues[2].trim(), m.groupValues[1].ifEmpty { "GET" }.uppercase(), lineNo, seenPerLine, results)
        }
    }

    private fun recordClientTarget(
        rawTarget: String, verb: String, lineNo: Int,
        seenPerLine: MutableSet<String>, results: MutableList<ExtractedEndpointOrUrl>
    ) {
        if (rawTarget.startsWith("http://", ignoreCase = true) || rawTarget.startsWith("https://", ignoreCase = true)) {
            val pathPart = extractPathFromHttpUrl(rawTarget)
            if (pathPart != null && isHttpRouteCandidate(pathPart, hasExplicitRouteCall = true)) {
                addExtractedItem(ExtractedEndpointOrUrl(rawTarget, normalizeRouteTemplate(pathPart), true, "CALLS_ENDPOINT", verb, lineNo), seenPerLine, results)
            }
            addExtractedItem(ExtractedEndpointOrUrl(rawTarget, normalizeExternalUrl(rawTarget), false, "URL_REFS", verb, lineNo), seenPerLine, results)
            return
        }
        val normalizedInput = if (rawTarget.startsWith("/")) rawTarget else "/$rawTarget"
        if (isHttpRouteCandidate(normalizedInput, hasExplicitRouteCall = true)) {
            addExtractedItem(ExtractedEndpointOrUrl(rawTarget, normalizeRouteTemplate(normalizedInput), true, "CALLS_ENDPOINT", verb, lineNo), seenPerLine, results)
        }
    }

    private fun extractKtorAndGenericServerRoutes(
        effectiveLine: String, lineNo: Int, seenPerLine: MutableSet<String>, results: MutableList<ExtractedEndpointOrUrl>
    ) {
        for (m in SERVER_ROUTE_CALL_RE.findAll(effectiveLine)) processServerRouteMatch(m, lineNo, seenPerLine, results)
    }

    private fun processServerRouteMatch(
        m: MatchResult, lineNo: Int, seenPerLine: MutableSet<String>, results: MutableList<ExtractedEndpointOrUrl>
    ) {
        if (isClientReceiverPrefix(m.value.lowercase())) return
        val verb = m.groupValues[1].uppercase()
        val rawRoute = m.groupValues[2].trim()
        if (!isHttpRouteCandidate(rawRoute, hasExplicitRouteCall = true)) return
        val canon = normalizeRouteTemplate(rawRoute)
        if (!seenPerLine.contains("$lineNo:CALLS_ENDPOINT:$canon")) {
            addExtractedItem(ExtractedEndpointOrUrl(rawRoute, canon, true, "EXPOSES_ENDPOINT", if (verb == "ROUTE") "ANY" else verb, lineNo), seenPerLine, results)
        }
    }

    private fun isClientReceiverPrefix(fullLower: String): Boolean {
        for (prefix in CLIENT_CALL_RECEIVER_PREFIXES) if (fullLower.startsWith(prefix)) return true
        return false
    }

    private fun extractStringLiteralUrlsAndRoutes(
        rawLine: String, maskedLine: String, lineNo: Int,
        seenPerLine: MutableSet<String>, results: MutableList<ExtractedEndpointOrUrl>
    ) {
        for (literal in extractStringLiteralsFromLine(rawLine, maskedLine)) {
            val trimmedLit = literal.trim()
            if (trimmedLit.length < 4 || trimmedLit.any { it.isWhitespace() }) continue
            if (isExternalSchemeUrl(trimmedLit)) {
                recordExternalLiteralUrl(trimmedLit, lineNo, seenPerLine, results)
            } else if (isHttpRouteCandidate(trimmedLit, hasExplicitRouteCall = false)) {
                val canon = normalizeRouteTemplate(trimmedLit)
                if ("$lineNo:EXPOSES_ENDPOINT:$canon" !in seenPerLine) {
                    addExtractedItem(ExtractedEndpointOrUrl(trimmedLit, canon, true, "CALLS_ENDPOINT", null, lineNo), seenPerLine, results)
                }
            }
        }
    }

    private fun isExternalSchemeUrl(s: String): Boolean {
        if (s.length <= 8) return false
        return s.startsWith("https://", ignoreCase = true) || s.startsWith("http://", ignoreCase = true) ||
            s.startsWith("figma://", ignoreCase = true) || s.startsWith("sidecar://", ignoreCase = true)
    }

    private fun recordExternalLiteralUrl(
        trimmedLit: String, lineNo: Int, seenPerLine: MutableSet<String>, results: MutableList<ExtractedEndpointOrUrl>
    ) {
        addExtractedItem(ExtractedEndpointOrUrl(trimmedLit, normalizeExternalUrl(trimmedLit), false, "URL_REFS", null, lineNo), seenPerLine, results)
        val pathPart = extractPathFromHttpUrl(trimmedLit) ?: return
        if (isHttpRouteCandidate(pathPart, hasExplicitRouteCall = false)) {
            val canonRoute = normalizeRouteTemplate(pathPart)
            if ("$lineNo:EXPOSES_ENDPOINT:$canonRoute" !in seenPerLine) {
                addExtractedItem(ExtractedEndpointOrUrl(trimmedLit, canonRoute, true, "CALLS_ENDPOINT", null, lineNo), seenPerLine, results)
            }
        }
    }

    fun normalizeExternalUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trim('\'', '"', '`')
        val figma = parseFigmaUrl(trimmed) ?: return trimmed.trimEnd('/')
        return if (figma.nodeId != null) {
            "https://www.figma.com/design/${figma.fileKey}?node-id=${figma.nodeId}"
        } else {
            "https://www.figma.com/design/${figma.fileKey}"
        }
    }

    fun extractPathFromHttpUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim().trim('\'', '"', '`')
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return null
        val schemeEnd = trimmed.indexOf("://")
        val slashIdx = if (schemeEnd >= 0) trimmed.indexOf('/', schemeEnd + 3) else -1
        return if (slashIdx >= 0) trimmed.substring(slashIdx).substringBefore('?').substringBefore('#') else null
    }

    private fun sliceUncommentedLine(rawLine: String, maskedLine: String): String {
        if (rawLine.length != maskedLine.length) return rawLine
        val sb = StringBuilder(rawLine.length)
        var i = 0
        while (i < rawLine.length) {
            val m = maskedLine[i]
            if (isQuoteDelimiter(m)) {
                i = appendQuotedSegment(rawLine, maskedLine, i, m, sb)
            } else {
                val r = rawLine[i]
                sb.append(if (r != ' ' && r != '\t' && m == ' ') ' ' else r)
                i++
            }
        }
        return sb.toString()
    }

    private fun appendQuotedSegment(rawLine: String, maskedLine: String, startIdx: Int, quote: Char, sb: StringBuilder): Int {
        sb.append(rawLine[startIdx])
        var i = startIdx + 1
        while (i < rawLine.length) {
            sb.append(rawLine[i])
            if (maskedLine[i] == quote) return i + 1
            i++
        }
        return i
    }

    private fun extractStringLiteralsFromLine(rawLine: String, maskedLine: String): List<String> {
        if (rawLine.length != maskedLine.length) return emptyList()
        val literals = mutableListOf<String>()
        var i = 0
        while (i < rawLine.length) {
            val quote = maskedLine[i]
            if (isQuoteDelimiter(quote)) {
                val closeIdx = maskedLine.indexOf(quote, i + 1)
                if (closeIdx > i) {
                    literals.add(rawLine.substring(i + 1, closeIdx))
                    i = closeIdx + 1
                    continue
                }
            }
            i++
        }
        return literals
    }

    private fun isQuoteDelimiter(c: Char): Boolean = c == '"' || c == '\'' || c == '`'

    private fun urlDecodePath(raw: String): String {
        if (!raw.contains('%')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val decoded = decodePercentAt(raw, i)
            if (decoded != null) {
                sb.append(decoded)
                i += 3
            } else {
                sb.append(raw[i++])
            }
        }
        return sb.toString()
    }

    private fun decodePercentAt(raw: String, i: Int): Char? {
        if (raw[i] != '%' || i + 2 >= raw.length) return null
        return raw.substring(i + 1, i + 3).toIntOrNull(16)?.toChar()
    }
}
