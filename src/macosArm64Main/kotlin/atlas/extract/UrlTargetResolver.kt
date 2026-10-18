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
    private val UUID_SEGMENT_RE = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")
    private val NUMERIC_SEGMENT_RE = Regex("""^\d+$""")
    private val BRACE_PARAM_RE = Regex("""\{[^{}/]+\}""")
    private val INTERP_BRACE_PARAM_RE = Regex("""\$\{[^{}/]+\}""")
    private val INTERP_VAR_PARAM_RE = Regex("""\$[A-Za-z_][A-Za-z0-9_]*""")
    private val ANGLE_PARAM_RE = Regex("""<[^<>/]+>""")
    private val COLON_PARAM_RE = Regex("""^:[A-Za-z_][A-Za-z0-9_]*$""")
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
        for (param in query.split('&')) {
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
        return decoded.split(';').joinToString(";") { segment ->
            val seg = segment.trim()
            if (Regex("""^I?\d+-\d+$""").matches(seg)) seg.replace('-', ':') else seg
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
        return BRACE_PARAM_RE.containsMatchIn(s) || s.split('/').any { COLON_PARAM_RE.matches(it) || ANGLE_PARAM_RE.matches(it) }
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
