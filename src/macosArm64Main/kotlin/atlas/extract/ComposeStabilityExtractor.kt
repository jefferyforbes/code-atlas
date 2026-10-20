package atlas.extract

import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.GraphEdge
import atlas.model.GraphNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Dual-mode Jetpack Compose & KMP stability and compliance extractor (`semantic:infrastructure`).
 *
 * Combines:
 * 1. Sub-15ms single-pass Kotlin AST `@Composable` extraction with 4-state stability join-semilattice
 *    (`STABLE` < `RUNTIME` < `UNCERTAIN` < `UNSTABLE`) and 12-bit `violation_mask` precomputation.
 * 2. `stability_config.conf` wildcard glob matching (`*` and `**`).
 * 3. Optional Compose Compiler metrics report ingestion (`*-composables.txt`, `*-composables.csv`, `*-classes.txt`).
 * 4. Optional bounded `@TraceRecomposition` runtime telemetry ingestion (`MAX_TELEMETRY_BYTES = 16 MB`, `100,000` lines).
 */
class ComposeStabilityExtractor {

    data class ExtractionBundle(
        val nodes: List<ComposeNodeRecord>,
        val params: List<ComposeParamRecord>,
        val edges: List<GraphEdge>
    )

    data class TraceRecord(
        val fqNameOrNodeId: String,
        val sessionId: String,
        val recompositionCount: Int,
        val skipCount: Int,
        val dirtyParamsJson: String,
        val recordedAt: Long
    )

    companion object {
        const val MAX_TELEMETRY_BYTES: Int = 16 * 1024 * 1024
        const val MAX_TELEMETRY_LINES: Int = 100_000

        // 12-Rule Violation Bitmask Constants
        const val BIT_STAB_001: Int = 1 shl 0   // Restartable UI @Composable is unskippable due to UNSTABLE param
        const val BIT_STAB_002: Int = 1 shl 1   // @Immutable/@Stable class declares var or standard List/Set/Map
        const val BIT_STAB_003: Int = 1 shl 2   // Unstable parameter forwarded across >= 2 composable hops
        const val BIT_STATE_001: Int = 1 shl 3  // Conditional/loop remember, rememberSaveable, LaunchedEffect, DisposableEffect
        const val BIT_STATE_002: Int = 1 shl 4  // collectAsState() instead of collectAsStateWithLifecycle() or MutableStateFlow param
        const val BIT_STATE_003: Int = 1 shl 5  // Unremembered mutableStateOf / derivedStateOf / MutableInteractionSource
        const val BIT_API_001: Int = 1 shl 6    // Public UI-emitting @Composable missing Modifier as first optional param
        const val BIT_API_002: Int = 1 shl 7    // Callback name encodes side-effects (onSaveAndNavigate)
        const val BIT_PERF_001: Int = 1 shl 8   // LazyColumn/LazyRow items(...) without key =
        const val BIT_PERF_002: Int = 1 shl 9   // Direct scroll/offset state read in composition scope
        const val BIT_I18N_001: Int = 1 shl 10  // Hardcoded string literal in Text("...") or contentDescription = "..."
        const val BIT_ARCH_001: Int = 1 shl 11  // Context passed to ViewModel or Screen composable missing @Preview

        private val PRIMITIVE_STABLE_TYPES = setOf(
            "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char",
            "String", "CharSequence", "Unit", "Nothing",
            "UInt", "ULong", "UShort", "UByte",
            "Modifier", "Color", "Dp", "TextUnit", "TextStyle", "Shape", "Painter", "ImageVector",
            "Alignment", "Arrangement", "PaddingValues", "WindowInsets", "ContentScale", "AnnotatedString"
        )

        private val IMMUTABLE_COLLECTION_PREFIXES = listOf(
            "ImmutableList", "ImmutableSet", "ImmutableMap",
            "PersistentList", "PersistentSet", "PersistentMap",
            "kotlinx.collections.immutable.ImmutableList",
            "kotlinx.collections.immutable.ImmutableSet",
            "kotlinx.collections.immutable.ImmutableMap",
            "kotlinx.collections.immutable.PersistentList",
            "kotlinx.collections.immutable.PersistentSet",
            "kotlinx.collections.immutable.PersistentMap"
        )

        private val STANDARD_COLLECTION_PREFIXES = setOf(
            "List", "Set", "Map", "Collection", "Iterable", "Sequence",
            "kotlin.collections.List", "kotlin.collections.Set", "kotlin.collections.Map",
            "kotlin.collections.Collection", "kotlin.collections.Iterable"
        )

        private val MUTABLE_TYPE_PREFIXES = setOf(
            "MutableList", "MutableSet", "MutableMap", "ArrayList", "HashMap", "HashSet",
            "LinkedHashMap", "LinkedHashSet", "Array", "IntArray", "LongArray", "FloatArray",
            "ByteArray", "Flow", "MutableStateFlow", "MutableSharedFlow", "StateFlow", "SharedFlow",
            "Channel", "Context", "Activity", "Application", "ViewModel"
        )

        private val PACKAGE_RE = Regex("""(?m)^\s*package\s+([A-Za-z0-9_.]+)""")
        private val SIDE_EFFECT_CALLBACK_RE = Regex("""^on[A-Z]\w*(And|Then|Navigate|Route|Pop|Dismiss|Fetch|Load)[A-Z]\w*$""")
        private val UI_EMITTING_CALL_RE = Regex("""\b(Column|Row|Box|Scaffold|Surface|Card|Text|Button|Icon|IconButton|LazyColumn|LazyRow|LazyVerticalGrid|LazyHorizontalGrid|OutlinedTextField|TextField|TopAppBar|ModalBottomSheet|AlertDialog|Image|Spacer)\s*\(""")
        private val CONDITIONAL_EFFECT_RE = Regex("""\b(if|when|for|while)\s*\([^)]*\)\s*\{[^}]*\b(remember|rememberSaveable|LaunchedEffect|DisposableEffect)\b|\?\.\s*let\s*\{[^}]*\b(remember|rememberSaveable|LaunchedEffect|DisposableEffect)\b""")
        private val COLLECT_AS_STATE_RAW_RE = Regex("""\.collectAsState\s*\(""")
        private val UNREMEMBERED_STATE_RE = Regex("""(?<!remember\s*\{\s*)(?<!rememberSaveable\s*\{\s*)(?<!by\s+remember\s*\{\s*)\b(mutableStateOf|mutableIntStateOf|mutableLongStateOf|mutableFloatStateOf|derivedStateOf|MutableInteractionSource)\s*\(""")
        private val LAZY_ITEMS_NO_KEY_RE = Regex("""\b(items|itemsIndexed)\s*\((?![^)]*\bkey\s*=)[^)]*\)""")
        private val SCROLL_READ_RE = Regex("""\b\w*(scrollState|ScrollState|lazyListState|LazyListState|pagerState|PagerState)\.(value|firstVisibleItemIndex|firstVisibleItemScrollOffset)\b""")
        private val HARDCODED_TEXT_RE = Regex("""\bText\s*\(\s*(?:text\s*=\s*)?"([^"$\\]{2,})"|\bcontentDescription\s*=\s*"([^"$\\]{2,})"""")
        private val VM_CONTEXT_INJECTION_RE = Regex("""\b\w*ViewModel\s*\([^)]*\b(context|LocalContext\.current|application)\b""")
        private val COMPOSE_CALL_RE = Regex("""\b([A-Z][A-Za-z0-9_]*)\s*\(""")
        private val PROVIDES_LOCAL_RE = Regex("""\b(Local[A-Za-z0-9_]+)\s+provides\b""")
        private val CONSUMES_LOCAL_RE = Regex("""\b(Local[A-Za-z0-9_]+)\.current\b""")
        private val OBSERVES_STATE_RE = Regex("""\b([a-zA-Z_][A-Za-z0-9_]*)\.(collectAsStateWithLifecycle|collectAsState)\s*\(""")
        private val MUTATES_PROP_RE = Regex("""\b([a-zA-Z_][A-Za-z0-9_]*)\.([a-zA-Z_][A-Za-z0-9_]*)\s*=(?!=)""")

        fun buildLineOffsets(text: String): IntArray {
            val offsets = mutableListOf(0)
            for (i in text.indices) {
                if (text[i] == '\n') offsets.add(i + 1)
            }
            return offsets.toIntArray()
        }

        fun lineForOffset(lineOffsets: IntArray, offset: Int): Int {
            var low = 0
            var high = lineOffsets.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val midVal = lineOffsets[mid]
                if (midVal <= offset) {
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return high + 1
        }

        fun detectKmpSourceSet(relPath: String): String {
            val norm = relPath.replace('\\', '/')
            return when {
                norm.contains("/commonMain/") || norm.startsWith("commonMain/") -> "commonMain"
                norm.contains("/androidMain/") || norm.startsWith("androidMain/") -> "androidMain"
                norm.contains("/iosMain/") || norm.startsWith("iosMain/") -> "iosMain"
                norm.contains("/desktopMain/") || norm.contains("/jvmMain/") -> "desktopMain"
                norm.contains("/wasmJsMain/") || norm.contains("/jsMain/") -> "wasmJsMain"
                norm.contains("/macosArm64Main/") -> "macosArm64Main"
                else -> "commonMain"
            }
        }

        fun parseStabilityConfigPatterns(configText: String): List<Regex> {
            return configText.lineSequence()
                .map { it.substringBefore("//").substringBefore("#").trim() }
                .filter { it.isNotEmpty() }
                .map { pattern ->
                    val sb = StringBuilder("^")
                    var i = 0
                    while (i < pattern.length) {
                        val c = pattern[i]
                        if (c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*') {
                            sb.append(".*")
                            i += 2
                        } else if (c == '*') {
                            sb.append("[^.]*")
                            i++
                        } else if (c in ".+()[]{}\$^|?") {
                            sb.append('\\').append(c)
                            i++
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                    sb.append('$')
                    Regex(sb.toString())
                }
                .toList()
        }
    }

    /**
     * Extracts all `@Composable` functions, parameters, 12-rule `violation_mask` bitmasks,
     * and Compose semantic edges from a Kotlin source file in a single pass.
     */
    fun extractKotlinFile(
        projectSlug: String,
        relPath: String,
        sourceText: String,
        stabilityPatterns: List<Regex> = emptyList(),
        workspaceNodes: List<GraphNode> = emptyList(),
        precomputedClassStability: Map<String, Pair<String, String>> = emptyMap()
    ): ExtractionBundle {
        val masked = PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "kotlin")
        val lineOffsets = buildLineOffsets(sourceText)
        val pkg = PACKAGE_RE.find(masked)?.groupValues?.get(1)?.trim() ?: ""
        val sourceSet = detectKmpSourceSet(relPath)
        val fileId = "$projectSlug:$relPath"

        // Collect local class stability info within this file and workspace
        val localClassStability = analyzeLocalClasses(
            projectSlug, relPath, sourceText, masked, stabilityPatterns, workspaceNodes, precomputedClassStability
        )
        val hasPreviewInFile = Regex("""@Preview[A-Za-z0-9_]*""").containsMatchIn(masked)

        val composeNodes = mutableListOf<ComposeNodeRecord>()
        val composeParams = mutableListOf<ComposeParamRecord>()
        val composeEdges = mutableListOf<GraphEdge>()

        // Scan for @Composable fun declarations
        val composableFunRe = Regex("""@Composable\b[\s\S]*?\bfun\s+(?:<[^>]+>\s*)?(?:([A-Za-z0-9_.<>?]+)\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        for (match in composableFunRe.findAll(masked)) {
            val prefixWindow = masked.substring(match.range.first, match.range.last)
            // Ensure no other fun/class intervened between @Composable and fun
            val funCount = Regex("""\bfun\b""").findAll(prefixWindow).count()
            if (funCount != 1) continue

            val receiverType = match.groupValues[1].trim()
            val funName = match.groupValues[2].trim()
            val startLine = lineForOffset(lineOffsets, match.range.first)

            // Look back up to 200 chars before @Composable for annotations/modifiers (e.g. @Preview, @ReadOnlyComposable, inline, private, expect, actual)
            val lookbackStart = (match.range.first - 220).coerceAtLeast(0)
            val declHeaderPrefix = masked.substring(lookbackStart, match.range.last)
            val isPreview = declHeaderPrefix.contains("@Preview") || funName.endsWith("Preview")
            val isReadonly = declHeaderPrefix.contains("@ReadOnlyComposable")
            val isNonRestartable = declHeaderPrefix.contains("@NonRestartableComposable")
            val isInline = Regex("""\binline\b""").containsMatchIn(declHeaderPrefix)
            val isPrivate = Regex("""\bprivate\b""").containsMatchIn(declHeaderPrefix)
            val isExpect = Regex("""\bexpect\b""").containsMatchIn(declHeaderPrefix)
            val isActual = Regex("""\bactual\b""").containsMatchIn(declHeaderPrefix)

            // Extract balanced parameter list starting at match.range.last (which is '(')
            val openParenIdx = match.range.last
            val closeParenIdx = findBalancedCloseParen(masked, openParenIdx)
            if (closeParenIdx < 0) continue

            val rawParamSlice = sourceText.substring(openParenIdx + 1, closeParenIdx)
            val maskedParamSlice = masked.substring(openParenIdx + 1, closeParenIdx)

            // Determine return type and function body bounds
            val (returnsUnit, bodyOpenBraceIdx, bodyCloseBraceIdx) = resolveReturnTypeAndBodyBounds(masked, closeParenIdx)
            val endLine = if (bodyCloseBraceIdx > 0) {
                lineForOffset(lineOffsets, bodyCloseBraceIdx)
            } else {
                lineForOffset(lineOffsets, closeParenIdx)
            }

            val rawBodySlice = if (bodyOpenBraceIdx in 0 until bodyCloseBraceIdx && bodyCloseBraceIdx <= sourceText.length) {
                sourceText.substring(bodyOpenBraceIdx, bodyCloseBraceIdx + 1)
            } else ""
            val maskedBodySlice = if (bodyOpenBraceIdx in 0 until bodyCloseBraceIdx && bodyCloseBraceIdx <= masked.length) {
                masked.substring(bodyOpenBraceIdx, bodyCloseBraceIdx + 1)
            } else ""

            val nodeId = "$fileId#$funName"
            val fqName = if (pkg.isNotEmpty()) "$pkg.$funName" else funName

            val parsedParams = parseParameters(
                nodeId = nodeId,
                rawParamSlice = rawParamSlice,
                maskedParamSlice = maskedParamSlice,
                maskedBodySlice = maskedBodySlice,
                pkg = pkg,
                stabilityPatterns = stabilityPatterns,
                localClassStability = localClassStability,
                workspaceNodes = workspaceNodes
            )
            composeParams.addAll(parsedParams)

            val receiverStability = if (receiverType.isEmpty()) {
                "STABLE"
            } else {
                inferTypeStability(receiverType, pkg, stabilityPatterns, localClassStability, workspaceNodes).first
            }

            val usedUnstableCount = parsedParams.count { it.isUsedInBody && it.stability in setOf("UNSTABLE", "UNCERTAIN") }
            val usedRuntimeCount = parsedParams.count { it.isUsedInBody && it.stability == "RUNTIME" }
            val isRestartable = !isInline && !isReadonly && !isNonRestartable && returnsUnit
            val isSkippable = if (!isRestartable) {
                false
            } else {
                usedUnstableCount == 0 && usedRuntimeCount == 0 && receiverStability == "STABLE"
            }

            // Modifier parameter hygiene check
            val modIdx = parsedParams.indexOfFirst {
                it.paramName == "modifier" || it.typeSignature.removeSuffix("?").endsWith("Modifier")
            }
            val hasModifierParam = modIdx >= 0
            val firstOptionalIdx = parsedParams.indexOfFirst { it.hasDefault }
            val modifierIsFirstOptional = if (!hasModifierParam) {
                false
            } else {
                firstOptionalIdx == -1 || modIdx == firstOptionalIdx
            }
            val modifierHasDefault = if (!hasModifierParam) false else parsedParams[modIdx].hasDefault

            // Compute 12-bit violation_mask
            var violationMask = 0

            // Bit 0: COMPOSE-STAB-001 (Restartable UI @Composable is unskippable due to UNSTABLE parameter)
            if (isRestartable && !isSkippable && usedUnstableCount > 0) {
                violationMask = violationMask or BIT_STAB_001
            }

            // Bit 1: COMPOSE-STAB-002 (Broken @Immutable/@Stable contract on parameter type)
            if (parsedParams.any { it.stabilityReason.startsWith("BROKEN_STABILITY_CONTRACT") }) {
                violationMask = violationMask or BIT_STAB_002
            }

            // Bit 3: COMPOSE-STATE-001 (Conditional remember / LaunchedEffect / DisposableEffect)
            if (hasConditionalEffectOrRemember(maskedBodySlice)) {
                violationMask = violationMask or BIT_STATE_001
            }

            // Bit 4: COMPOSE-STATE-002 (Raw collectAsState() or MutableStateFlow parameter)
            if (COLLECT_AS_STATE_RAW_RE.containsMatchIn(maskedBodySlice) ||
                parsedParams.any { it.typeSignature.contains("MutableStateFlow") || it.typeSignature.contains("MutableSharedFlow") }
            ) {
                violationMask = violationMask or BIT_STATE_002
            }

            // Bit 5: COMPOSE-STATE-003 (Unremembered mutableStateOf / derivedStateOf)
            if (hasUnrememberedStateCall(maskedBodySlice)) {
                violationMask = violationMask or BIT_STATE_003
            }

            // Bit 6: COMPOSE-API-001 (Public UI-emitting @Composable missing modifier: Modifier = Modifier as first optional)
            val emitsUi = UI_EMITTING_CALL_RE.containsMatchIn(maskedBodySlice)
            val isScreenRoot = funName.endsWith("Screen") || funName.endsWith("Route")
            if (!isPrivate && !isPreview && !isScreenRoot && returnsUnit && emitsUi) {
                if (!hasModifierParam || !modifierHasDefault || !modifierIsFirstOptional) {
                    violationMask = violationMask or BIT_API_001
                }
            }

            // Bit 7: COMPOSE-API-002 (Side-effecting callback parameter name like onSaveAndNavigate)
            if (parsedParams.any { SIDE_EFFECT_CALLBACK_RE.matches(it.paramName) }) {
                violationMask = violationMask or BIT_API_002
            }

            // Bit 8: COMPOSE-PERF-001 (LazyColumn/LazyRow items(...) without key =)
            if (hasLazyItemsWithoutKey(maskedBodySlice)) {
                violationMask = violationMask or BIT_PERF_001
            }

            // Bit 9: COMPOSE-PERF-002 (High-frequency scroll state read directly in composition)
            if (SCROLL_READ_RE.containsMatchIn(maskedBodySlice) && !maskedBodySlice.contains("derivedStateOf")) {
                violationMask = violationMask or BIT_PERF_002
            }

            // Bit 10: COMPOSE-I18N-001 (Hardcoded string literal in Text("...") or contentDescription = "...")
            if (!isPreview && HARDCODED_TEXT_RE.containsMatchIn(rawBodySlice)) {
                violationMask = violationMask or BIT_I18N_001
            }

            // Bit 11: COMPOSE-ARCH-001 (Context passed to ViewModel OR *Screen composable without @Preview in file)
            if (VM_CONTEXT_INJECTION_RE.containsMatchIn(maskedBodySlice) ||
                (!isPreview && funName.endsWith("Screen") && !hasPreviewInFile)
            ) {
                violationMask = violationMask or BIT_ARCH_001
            }

            // Extract semantic Compose edges from body
            val unstableParamNames = parsedParams
                .filter { it.stability in setOf("UNSTABLE", "RUNTIME", "UNCERTAIN") }
                .map { it.paramName }
                .toSet()

            for (callMatch in COMPOSE_CALL_RE.findAll(maskedBodySlice)) {
                val calleeName = callMatch.groupValues[1]
                if (calleeName == funName || calleeName in PRIMITIVE_STABLE_TYPES) continue
                val callOffset = bodyOpenBraceIdx + callMatch.range.first
                val callLine = lineForOffset(lineOffsets, callOffset)
                composeEdges.add(
                    GraphEdge(
                        edgeId = "$nodeId:calls_composable:$calleeName:$callLine",
                        projectId = projectSlug,
                        sourceNodeId = nodeId,
                        targetNodeId = "sym:$calleeName",
                        edgeType = "CALLS_COMPOSABLE",
                        provenance = "EXTRACTED_AST",
                        confidence = 0.95,
                        lineNumber = callLine
                    )
                )
                // Check if an unstable param is forwarded to a child composable call
                val callClose = findBalancedCloseParen(maskedBodySlice, callMatch.range.last)
                if (callClose > callMatch.range.last && unstableParamNames.isNotEmpty()) {
                    val argSlice = maskedBodySlice.substring(callMatch.range.last + 1, callClose)
                    if (unstableParamNames.any { pName -> Regex("""\b${Regex.escape(pName)}\b""").containsMatchIn(argSlice) }) {
                        violationMask = violationMask or BIT_STAB_003
                    }
                }
            }

            for (provMatch in PROVIDES_LOCAL_RE.findAll(maskedBodySlice)) {
                val localName = provMatch.groupValues[1]
                val lineNo = lineForOffset(lineOffsets, bodyOpenBraceIdx + provMatch.range.first)
                composeEdges.add(
                    GraphEdge(
                        edgeId = "$nodeId:provides_local:$localName:$lineNo",
                        projectId = projectSlug,
                        sourceNodeId = nodeId,
                        targetNodeId = "sym:$localName",
                        edgeType = "PROVIDES_COMPOSITION_LOCAL",
                        provenance = "EXTRACTED_AST",
                        confidence = 1.0,
                        lineNumber = lineNo
                    )
                )
            }

            for (consMatch in CONSUMES_LOCAL_RE.findAll(maskedBodySlice)) {
                val localName = consMatch.groupValues[1]
                val lineNo = lineForOffset(lineOffsets, bodyOpenBraceIdx + consMatch.range.first)
                composeEdges.add(
                    GraphEdge(
                        edgeId = "$nodeId:consumes_local:$localName:$lineNo",
                        projectId = projectSlug,
                        sourceNodeId = nodeId,
                        targetNodeId = "sym:$localName",
                        edgeType = "CONSUMES_COMPOSITION_LOCAL",
                        provenance = "EXTRACTED_AST",
                        confidence = 1.0,
                        lineNumber = lineNo
                    )
                )
            }

            for (obsMatch in OBSERVES_STATE_RE.findAll(maskedBodySlice)) {
                val stateSym = obsMatch.groupValues[1]
                val lineNo = lineForOffset(lineOffsets, bodyOpenBraceIdx + obsMatch.range.first)
                composeEdges.add(
                    GraphEdge(
                        edgeId = "$nodeId:observes_state:$stateSym:$lineNo",
                        projectId = projectSlug,
                        sourceNodeId = nodeId,
                        targetNodeId = "sym:$stateSym",
                        edgeType = "OBSERVES_STATE",
                        provenance = "EXTRACTED_AST",
                        confidence = 0.95,
                        lineNumber = lineNo
                    )
                )
            }

            for (mutMatch in MUTATES_PROP_RE.findAll(maskedBodySlice)) {
                val propName = mutMatch.groupValues[2]
                val lineNo = lineForOffset(lineOffsets, bodyOpenBraceIdx + mutMatch.range.first)
                composeEdges.add(
                    GraphEdge(
                        edgeId = "$nodeId:mutates_prop:$propName:$lineNo",
                        projectId = projectSlug,
                        sourceNodeId = nodeId,
                        targetNodeId = "sym:$propName",
                        edgeType = "MUTATES_PROPERTY",
                        provenance = "EXTRACTED_AST",
                        confidence = 0.90,
                        lineNumber = lineNo
                    )
                )
            }

            if (isExpect || isActual) {
                composeEdges.add(
                    GraphEdge(
                        edgeId = "$nodeId:expect_actual:$funName:$startLine",
                        projectId = projectSlug,
                        sourceNodeId = nodeId,
                        targetNodeId = "sym:$funName",
                        edgeType = "EXPECT_ACTUAL",
                        provenance = "EXTRACTED_AST",
                        confidence = 1.0,
                        lineNumber = startLine
                    )
                )
            }

            val dominantProv = if (parsedParams.any { it.provenance == "STABILITY_CONFIG" }) {
                "STABILITY_CONFIG"
            } else {
                "STATIC_INFERRED"
            }

            composeNodes.add(
                ComposeNodeRecord(
                    nodeId = nodeId,
                    projectSlug = projectSlug,
                    fqName = fqName,
                    filePath = relPath,
                    startLine = startLine,
                    endLine = endLine,
                    sourceSet = sourceSet,
                    isRestartable = isRestartable,
                    isSkippable = isSkippable,
                    isReadonly = isReadonly,
                    isInline = isInline,
                    returnsUnit = returnsUnit,
                    hasModifierParam = hasModifierParam,
                    modifierIsFirstOptional = modifierIsFirstOptional,
                    modifierHasDefault = modifierHasDefault,
                    unstableParamCount = usedUnstableCount,
                    runtimeParamCount = usedRuntimeCount,
                    receiverStability = receiverStability,
                    provenance = dominantProv,
                    violationMask = violationMask
                )
            )
        }

        // Also scan non-composable functions in the file for MUTATES_PROPERTY edges (write-site proof)
        for (mutMatch in MUTATES_PROP_RE.findAll(masked)) {
            val propName = mutMatch.groupValues[2]
            val lineNo = lineForOffset(lineOffsets, mutMatch.range.first)
            val edgeId = "$fileId:file_mutates:$propName:$lineNo"
            if (composeEdges.none { it.lineNumber == lineNo && it.edgeType == "MUTATES_PROPERTY" }) {
                composeEdges.add(
                    GraphEdge(
                        edgeId = edgeId,
                        projectId = projectSlug,
                        sourceNodeId = "$fileId#file",
                        targetNodeId = "sym:$propName",
                        edgeType = "MUTATES_PROPERTY",
                        provenance = "EXTRACTED_AST",
                        confidence = 0.90,
                        lineNumber = lineNo
                    )
                )
            }
        }

        return ExtractionBundle(
            nodes = composeNodes,
            params = composeParams,
            edges = composeEdges
        )
    }

    /**
     * Parses Compose Compiler metrics (`*-composables.txt` and `*-classes.txt`) to upgrade
     * stability records to `COMPILER_VERIFIED` provenance.
     */
    fun parseCompilerComposablesReport(
        projectSlug: String,
        reportText: String,
        existingNodes: List<ComposeNodeRecord>
    ): Pair<List<ComposeNodeRecord>, List<ComposeParamRecord>> {
        val clipped = if (reportText.length > MAX_TELEMETRY_BYTES) {
            reportText.substring(0, MAX_TELEMETRY_BYTES)
        } else reportText

        val byShortName = existingNodes.groupBy { it.fqName.substringAfterLast('.') }
        val byFqName = existingNodes.associateBy { it.fqName }

        val updatedNodes = mutableListOf<ComposeNodeRecord>()
        val updatedParams = mutableListOf<ComposeParamRecord>()

        // Match blocks like: restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun HomeScreen(
        val headerRe = Regex("""^(.*?\bfun\s+)([A-Za-z0-9_.]+)\s*\(""")
        val paramLineRe = Regex("""^\s*(stable|unstable|runtime|unused|default)\s+(?:(stable|unstable|runtime)\s+)?([A-Za-z0-9_]+)\s*:\s*(.+)$""")

        val lines = clipped.lineSequence().take(MAX_TELEMETRY_LINES).toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val m = headerRe.find(line)
            if (m == null) {
                i++
                continue
            }
            val flags = m.groupValues[1]
            val rawName = m.groupValues[2].trim()
            val shortName = rawName.substringAfterLast('.')
            val matchedNode = byFqName[rawName] ?: byShortName[shortName]?.firstOrNull()

            val isRestartable = flags.contains("restartable")
            val isSkippable = flags.contains("skippable")
            val isReadonly = flags.contains("readonly")
            val isInline = flags.contains("inline")

            val nodeId = matchedNode?.nodeId ?: "$projectSlug:compiler#$rawName"
            val params = mutableListOf<ComposeParamRecord>()
            var pIdx = 0
            i++
            while (i < lines.size) {
                val pLine = lines[i].trim()
                if (pLine.startsWith(")") || pLine.startsWith("fun ") || pLine.startsWith("restartable ")) {
                    break
                }
                val pm = paramLineRe.find(pLine)
                if (pm != null) {
                    val modifier1 = pm.groupValues[1]
                    val modifier2 = pm.groupValues[2]
                    val pName = pm.groupValues[3]
                    val pType = pm.groupValues[4].trim()
                    val isUnused = modifier1 == "unused"
                    val effStability = when (modifier2.ifEmpty { modifier1 }) {
                        "stable" -> "STABLE"
                        "unstable" -> "UNSTABLE"
                        "runtime" -> "RUNTIME"
                        else -> "STABLE"
                    }
                    params.add(
                        ComposeParamRecord(
                            nodeId = nodeId,
                            paramIndex = pIdx++,
                            paramName = pName,
                            typeSignature = pType,
                            stability = effStability,
                            stabilityReason = "COMPILER_REPORT_${effStability}",
                            hasDefault = modifier1 == "default",
                            isUsedInBody = !isUnused,
                            provenance = "COMPILER_VERIFIED"
                        )
                    )
                }
                i++
            }

            val unstableCnt = params.count { it.isUsedInBody && it.stability == "UNSTABLE" }
            val runtimeCnt = params.count { it.isUsedInBody && it.stability == "RUNTIME" }
            val baseNode = matchedNode ?: ComposeNodeRecord(
                nodeId = nodeId,
                projectSlug = projectSlug,
                fqName = rawName,
                filePath = "compiler_report",
                startLine = 1,
                endLine = 1
            )
            var newMask = baseNode.violationMask
            if (isRestartable && !isSkippable && unstableCnt > 0) {
                newMask = newMask or BIT_STAB_001
            } else if (isSkippable) {
                newMask = newMask and BIT_STAB_001.inv()
            }

            updatedNodes.add(
                baseNode.copy(
                    isRestartable = isRestartable,
                    isSkippable = isSkippable,
                    isReadonly = isReadonly,
                    isInline = isInline,
                    unstableParamCount = unstableCnt,
                    runtimeParamCount = runtimeCnt,
                    provenance = "COMPILER_VERIFIED",
                    violationMask = newMask
                )
            )
            updatedParams.addAll(params)
            i++
        }

        return Pair(updatedNodes, updatedParams)
    }

    private fun formatDirtyParamsJson(dirtyList: List<String>): String {
        if (dirtyList.isEmpty()) return "[]"
        return dirtyList.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
    }

    private fun extractJsonTarget(obj: JsonObject): String? {
        val composable = obj["composable"]?.jsonPrimitive?.content
        if (composable != null) return composable
        val nodeId = obj["node_id"]?.jsonPrimitive?.content
        if (nodeId != null) return nodeId
        return obj["fqname"]?.jsonPrimitive?.content
    }

    private fun extractJsonCount(obj: JsonObject, primaryKey: String, secondaryKey: String): Int {
        val primary = obj[primaryKey]?.jsonPrimitive?.content?.toIntOrNull()
        if (primary != null) return primary.coerceAtLeast(0)
        val secondary = obj[secondaryKey]?.jsonPrimitive?.content?.toIntOrNull()
        if (secondary != null) return secondary.coerceAtLeast(0)
        return 0
    }

    private fun parseJsonTraceRecord(
        trimmed: String,
        defaultSessionId: String,
        json: Json
    ): TraceRecord? {
        return try {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            val target = extractJsonTarget(obj) ?: return null
            val session = obj["session_id"]?.jsonPrimitive?.content ?: defaultSessionId
            val recomp = extractJsonCount(obj, "recomposition_count", "recompose")
            val skip = extractJsonCount(obj, "skip_count", "skip")
            val dirtyArr = obj["dirty_params"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val dirtyJson = formatDirtyParamsJson(dirtyArr)
            val ts = obj["recorded_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1729190000L

            TraceRecord(
                fqNameOrNodeId = target,
                sessionId = session,
                recompositionCount = recomp,
                skipCount = skip,
                dirtyParamsJson = dirtyJson,
                recordedAt = ts
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseLogcatTraceRecord(
        trimmed: String,
        defaultSessionId: String,
        logcatRe: Regex
    ): TraceRecord? {
        val m = logcatRe.find(trimmed) ?: return null
        val target = m.groupValues[1]
        val recomp = m.groupValues[2].toIntOrNull() ?: 0
        val skip = m.groupValues[3].toIntOrNull() ?: 0
        val dirtyRaw = m.groupValues[4].trim()
        val dirtyList = if (dirtyRaw.isEmpty()) emptyList() else dirtyRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val dirtyJson = formatDirtyParamsJson(dirtyList)

        return TraceRecord(
            fqNameOrNodeId = target,
            sessionId = defaultSessionId,
            recompositionCount = recomp,
            skipCount = skip,
            dirtyParamsJson = dirtyJson,
            recordedAt = 1729190000L
        )
    }

    private fun clipTelemetry(rawLogText: String): String {
        if (rawLogText.length > MAX_TELEMETRY_BYTES) {
            return rawLogText.substring(0, MAX_TELEMETRY_BYTES)
        }
        return rawLogText
    }

    private fun parseTelemetryLine(
        trimmed: String,
        defaultSessionId: String,
        json: Json,
        logcatRe: Regex
    ): TraceRecord? {
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseJsonTraceRecord(trimmed, defaultSessionId, json)
        }
        return parseLogcatTraceRecord(trimmed, defaultSessionId, logcatRe)
    }

    /**
     * Parses bounded `@TraceRecomposition` runtime telemetry (JSONL or Logcat lines).
     * Enforces `MAX_TELEMETRY_BYTES = 16 MB` and `MAX_TELEMETRY_LINES = 100,000`.
     */
    fun parseTraceTelemetry(
        rawLogText: String,
        defaultSessionId: String = "default_session"
    ): List<TraceRecord> {
        val clipped = clipTelemetry(rawLogText)
        val records = mutableListOf<TraceRecord>()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val logcatRe = Regex("""TraceRecomposition\s*:\s*composable=([A-Za-z0-9_.#:/-]+)\s+recompose=(\d+)\s+skip=(\d+)(?:\s+dirty=\[([^\]]*)\])?""")

        var lineCount = 0
        for (rawLine in clipped.lineSequence()) {
            if (++lineCount > MAX_TELEMETRY_LINES) break
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val record = parseTelemetryLine(trimmed, defaultSessionId, json, logcatRe)
            if (record != null) {
                records.add(record)
            }
        }
        return records
    }

    private fun isClassBodyTermination(maskedText: String, k: Int): Boolean {
        if (maskedText[k] == ';') return true
        if (maskedText[k] != '\n' || k + 1 >= maskedText.length) return false
        val remainder = maskedText.substring(k + 1).trimStart()
        return remainder.startsWith("class ") ||
            remainder.startsWith("interface ") ||
            remainder.startsWith("fun ") ||
            remainder.startsWith("@Composable")
    }

    private fun findClassBodyOpenBrace(maskedText: String, fromOffset: Int): Int? {
        var k = fromOffset
        while (k < maskedText.length) {
            val c = maskedText[k]
            if (c == '{') return k
            if (isClassBodyTermination(maskedText, k)) return null
            k++
        }
        return null
    }

    private fun extractDepthOneBodyText(maskedText: String, openBrace: Int): String? {
        val closeBrace = findBalancedCloseBrace(maskedText, openBrace)
        if (closeBrace <= openBrace + 1) return null

        var depth = 1
        var bIdx = openBrace + 1
        val classLevelChars = CharArray(closeBrace - openBrace - 1)
        while (bIdx < closeBrace) {
            val ch = maskedText[bIdx]
            val slot = bIdx - openBrace - 1
            when (ch) {
                '{' -> { depth++; classLevelChars[slot] = ' ' }
                '}' -> { depth--; classLevelChars[slot] = ' ' }
                else -> { classLevelChars[slot] = if (depth == 1) ch else ' ' }
            }
            bIdx++
        }
        return classLevelChars.concatToString()
    }

    private fun findDepthOneBodyVar(maskedText: String, fromOffset: Int): String? {
        val openBrace = findClassBodyOpenBrace(maskedText, fromOffset) ?: return null
        val bodyText = extractDepthOneBodyText(maskedText, openBrace) ?: return null
        val bodyVarMatch = Regex("""\bvar\s+([A-Za-z_][A-Za-z0-9_]*)""").find(bodyText)
        return bodyVarMatch?.groupValues?.get(1)
    }

    private fun evaluateSpecialClassKind(kindKw: String, hasStableAnnotation: Boolean): Pair<String, String>? {
        if (kindKw == "enum class" || kindKw == "value class") {
            return "STABLE" to "ENUM_OR_VALUE_CLASS"
        }
        if (kindKw == "interface" && !hasStableAnnotation) {
            return "UNCERTAIN" to "UNANNOTATED_INTERFACE"
        }
        return null
    }

    private fun evaluateCollectionPropertyStability(
        className: String,
        propName: String,
        colType: String,
        stabilityPatterns: List<Regex>
    ): Pair<String, String> {
        val matchedByConf = stabilityPatterns.any { it.matches("kotlin.collections.$colType") }
        if (matchedByConf) {
            return "STABLE" to "STABILITY_CONFIG_MATCH:kotlin.collections.$colType"
        }
        return "UNSTABLE" to "UNSTABLE_COLLECTION_PROPERTY:$propName($colType)"
    }

    private fun evaluateClassStability(
        className: String,
        kindKw: String,
        hasStableAnnotation: Boolean,
        ctorSlice: String,
        bodyVarName: String?,
        stabilityPatterns: List<Regex>
    ): Pair<String, String> {
        val special = evaluateSpecialClassKind(kindKw, hasStableAnnotation)
        if (special != null) return special

        val ctorVarMatch = Regex("""\bvar\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""").find(ctorSlice)
        val rawCollectionMatch = Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(List|Set|Map|MutableList|MutableSet|MutableMap)<""").find(ctorSlice)
        val varPropName = ctorVarMatch?.groupValues?.get(1) ?: bodyVarName

        if (hasStableAnnotation && (varPropName != null || rawCollectionMatch != null)) {
            val offender = varPropName ?: rawCollectionMatch?.groupValues?.get(1) ?: "field"
            return "UNSTABLE" to "BROKEN_STABILITY_CONTRACT:$className.$offender"
        }
        if (varPropName != null) {
            return "UNSTABLE" to "MUTABLE_VAR_PROPERTY:$varPropName"
        }
        if (rawCollectionMatch != null) {
            val propName = rawCollectionMatch.groupValues[1]
            val colType = rawCollectionMatch.groupValues[2]
            return evaluateCollectionPropertyStability(className, propName, colType, stabilityPatterns)
        }
        if (hasStableAnnotation) {
            return "STABLE" to "EXPLICIT_STABLE_ANNOTATION"
        }
        return "STABLE" to "ALL_VAL_PROPERTIES_STABLE"
    }

    /**
     * Extracts class stability metadata directly from a Kotlin source text slice.
     */
    fun analyzeClassesInSource(
        sourceText: String,
        maskedText: String,
        stabilityPatterns: List<Regex> = emptyList()
    ): Map<String, Pair<String, String>> {
        val result = mutableMapOf<String, Pair<String, String>>()
        val classRe = Regex("""((?:@(?:Immutable|Stable)\b[\s\S]*?)?)\b(data\s+class|value\s+class|enum\s+class|sealed\s+interface|sealed\s+class|class|interface)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^>]+>)?\s*(\([^)]*\))?""")

        for (declMatch in classRe.findAll(maskedText)) {
            val annotations = declMatch.groupValues[1]
            val kindKw = declMatch.groupValues[2].replace(Regex("""\s+"""), " ")
            val className = declMatch.groupValues[3]
            val ctorSlice = declMatch.groupValues[4]
            val hasStableAnnotation = annotations.contains("@Immutable") || annotations.contains("@Stable")

            val bodyVarName = findDepthOneBodyVar(maskedText, declMatch.range.last)
            result[className] = evaluateClassStability(
                className, kindKw, hasStableAnnotation, ctorSlice, bodyVarName, stabilityPatterns
            )
        }
        return result
    }

    private fun evaluateWorkspaceVarProperty(
        wn: GraphNode,
        sig: String,
        hasStable: Boolean
    ): Pair<String, String> {
        val prop = Regex("""\bvar\s+([A-Za-z_][A-Za-z0-9_]*)""").find(sig)?.groupValues?.get(1) ?: "prop"
        if (hasStable) {
            return "UNSTABLE" to "BROKEN_STABILITY_CONTRACT:${wn.name}.$prop"
        }
        return "UNSTABLE" to "MUTABLE_VAR_PROPERTY:$prop"
    }

    private fun evaluateWorkspaceCollectionProperty(
        wn: GraphNode,
        rawColMatch: MatchResult,
        hasStable: Boolean,
        stabilityPatterns: List<Regex>
    ): Pair<String, String> {
        val propName = rawColMatch.groupValues[1]
        val colType = rawColMatch.groupValues[2]
        val matchedByConf = stabilityPatterns.any { it.matches("kotlin.collections.$colType") }
        if (matchedByConf) {
            return "STABLE" to "STABILITY_CONFIG_MATCH:kotlin.collections.$colType"
        }
        if (hasStable) {
            return "UNSTABLE" to "BROKEN_STABILITY_CONTRACT:${wn.name}.$propName"
        }
        return "UNSTABLE" to "UNSTABLE_COLLECTION_PROPERTY:$propName($colType)"
    }

    private fun evaluateWorkspaceClassSignature(
        wn: GraphNode,
        stabilityPatterns: List<Regex>
    ): Pair<String, String>? {
        val sig = wn.signature
        val hasStable = sig.contains("@Immutable") || sig.contains("@Stable")

        if (sig.contains("var ")) {
            return evaluateWorkspaceVarProperty(wn, sig, hasStable)
        }
        val rawColMatch = Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(List|Set|Map)<""").find(sig)
        if (rawColMatch != null) {
            return evaluateWorkspaceCollectionProperty(wn, rawColMatch, hasStable, stabilityPatterns)
        }
        if (hasStable) {
            return "STABLE" to "EXPLICIT_STABLE_ANNOTATION"
        }
        if (sig.contains("data class") && sig.contains('(') && sig.contains(')')) {
            return "STABLE" to "WORKSPACE_DATA_CLASS"
        }
        return null
    }

    private fun analyzeLocalClasses(
        projectSlug: String,
        relPath: String,
        sourceText: String,
        maskedText: String,
        stabilityPatterns: List<Regex>,
        workspaceNodes: List<GraphNode>,
        precomputedClassStability: Map<String, Pair<String, String>> = emptyMap()
    ): Map<String, Pair<String, String>> {
        val result = mutableMapOf<String, Pair<String, String>>()
        result.putAll(precomputedClassStability)
        result.putAll(analyzeClassesInSource(sourceText, maskedText, stabilityPatterns))

        // Also incorporate workspace class signatures
        for (wn in workspaceNodes) {
            if (wn.kind !in setOf("class", "interface") || wn.name in result) continue
            val stability = evaluateWorkspaceClassSignature(wn, stabilityPatterns)
            if (stability != null) {
                result[wn.name] = stability
            }
        }
        return result
    }

    private fun parseParameters(
        nodeId: String,
        rawParamSlice: String,
        maskedParamSlice: String,
        maskedBodySlice: String,
        pkg: String,
        stabilityPatterns: List<Regex>,
        localClassStability: Map<String, Pair<String, String>>,
        workspaceNodes: List<GraphNode>
    ): List<ComposeParamRecord> {
        if (maskedParamSlice.isBlank()) return emptyList()
        val rawSegments = splitTopLevelComma(rawParamSlice)
        val maskedSegments = splitTopLevelComma(maskedParamSlice)
        val params = mutableListOf<ComposeParamRecord>()

        for (idx in maskedSegments.indices) {
            val seg = maskedSegments[idx].trim()
            val rawSeg = rawSegments.getOrElse(idx) { seg }.trim()
            if (seg.isEmpty()) continue

            val eqIdx = findTopLevelEquals(seg)
            val hasDefault = eqIdx >= 0
            val declPart = if (eqIdx >= 0) seg.substring(0, eqIdx).trim() else seg
            val rawDeclPart = if (eqIdx >= 0 && eqIdx <= rawSeg.length) rawSeg.substring(0, eqIdx).trim() else rawSeg

            val colonIdx = findTopLevelColon(declPart)
            if (colonIdx < 0) continue

            val paramName = declPart.substring(0, colonIdx)
                .trim()
                .split(Regex("""\s+"""))
                .lastOrNull()
                ?.removePrefix("vararg")
                ?.trim()
                ?: continue
            val typeSig = rawDeclPart.substring(colonIdx + 1).trim().replace(Regex("""\s+"""), " ")

            val isUsed = if (maskedBodySlice.isBlank()) {
                true
            } else {
                Regex("""\b${Regex.escape(paramName)}\b""").containsMatchIn(maskedBodySlice)
            }

            val (stability, reason) = inferTypeStability(
                typeSig = typeSig,
                pkg = pkg,
                stabilityPatterns = stabilityPatterns,
                localClassStability = localClassStability,
                workspaceNodes = workspaceNodes
            )
            val prov = if (reason.startsWith("STABILITY_CONFIG_MATCH")) "STABILITY_CONFIG" else "STATIC_INFERRED"

            params.add(
                ComposeParamRecord(
                    nodeId = nodeId,
                    paramIndex = idx,
                    paramName = paramName,
                    typeSignature = typeSig,
                    stability = stability,
                    stabilityReason = reason,
                    hasDefault = hasDefault,
                    isUsedInBody = isUsed,
                    provenance = prov
                )
            )
        }
        return params
    }

    fun inferTypeStability(
        typeSig: String,
        pkg: String,
        stabilityPatterns: List<Regex>,
        localClassStability: Map<String, Pair<String, String>>,
        workspaceNodes: List<GraphNode>
    ): Pair<String, String> {
        val clean = typeSig.trim().removeSuffix("?").trim()
        if (clean.isEmpty()) return "STABLE" to "PRIMITIVE_OR_STRING"

        // 1. Function types or @Composable lambdas: `(T) -> R`, `() -> Unit`
        if (clean.contains("->")) {
            return "STABLE" to "FUNCTION_TYPE"
        }

        val outerType = clean.substringBefore('<').trim()
        val shortOuter = outerType.substringAfterLast('.')
        val genericArgs = if (clean.contains('<') && clean.endsWith('>')) {
            splitTopLevelComma(clean.substringAfter('<').substringBeforeLast('>'))
        } else emptyList()

        // 2. Primitives & core Compose stable value types
        if (shortOuter in PRIMITIVE_STABLE_TYPES) {
            return "STABLE" to "PRIMITIVE_OR_STRING"
        }

        // 3. Explicit stability_config.conf match
        val fqCandidates = listOfNotNull(
            outerType,
            if (!outerType.contains('.') && pkg.isNotEmpty()) "$pkg.$outerType" else null,
            if (shortOuter in setOf("List", "Set", "Map", "Collection")) "kotlin.collections.$shortOuter" else null
        )
        for (cand in fqCandidates) {
            val matchedRegex = stabilityPatterns.firstOrNull { it.matches(cand) }
            if (matchedRegex != null) {
                return "STABLE" to "STABILITY_CONFIG_MATCH:${matchedRegex.pattern}"
            }
        }

        // 4. kotlinx.collections.immutable
        if (IMMUTABLE_COLLECTION_PREFIXES.any { outerType == it || shortOuter == it }) {
            for (arg in genericArgs) {
                val (argStab, argReason) = inferTypeStability(arg, pkg, stabilityPatterns, localClassStability, workspaceNodes)
                if (argStab != "STABLE") {
                    return argStab to "GENERIC_ARG_$argReason"
                }
            }
            return "STABLE" to "KOTLINX_IMMUTABLE_COLLECTION"
        }

        // 5. Standard Kotlin collection interfaces (List, Set, Map) without stability_config override
        if (outerType in STANDARD_COLLECTION_PREFIXES || shortOuter in STANDARD_COLLECTION_PREFIXES) {
            return "UNSTABLE" to "STANDARD_COLLECTION_INTERFACE:$shortOuter"
        }

        // 6. Explicitly mutable types / streams / Android Context
        if (outerType in MUTABLE_TYPE_PREFIXES || shortOuter in MUTABLE_TYPE_PREFIXES) {
            return "UNSTABLE" to "MUTABLE_COLLECTION_OR_STREAM:$shortOuter"
        }

        // 7. Local or workspace-indexed class/interface
        val localHit = localClassStability[shortOuter] ?: localClassStability[outerType]
        if (localHit != null) {
            return localHit
        }

        // 8. State<T> wrapper
        if (shortOuter == "State") {
            return "STABLE" to "COMPOSE_STATE_WRAPPER"
        }

        // 9. Unknown external module type -> RUNTIME stability
        return "RUNTIME" to "EXTERNAL_MODULE_TYPE:$outerType"
    }

    private fun findBalancedCloseParen(text: String, openParenIdx: Int): Int {
        if (openParenIdx < 0 || openParenIdx >= text.length || text[openParenIdx] != '(') return -1
        var depth = 0
        for (i in openParenIdx until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun skipWhitespace(text: String, startIdx: Int): Int {
        var i = startIdx
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun isReturnTypeTerminator(c: Char, angleDepth: Int): Boolean {
        if (angleDepth > 0) return false
        return c == '{' || c == '=' || c == '\n'
    }

    private fun isUnitReturnType(retType: String): Boolean =
        retType.isEmpty() || retType == "Unit"

    private fun scanReturnTypeEnd(masked: String, startIdx: Int): Int {
        var i = startIdx
        var angleDepth = 0
        while (i < masked.length) {
            val c = masked[i]
            if (c == '<') {
                angleDepth++
            } else if (c == '>') {
                if (angleDepth > 0) angleDepth--
            } else if (isReturnTypeTerminator(c, angleDepth)) {
                break
            }
            i++
        }
        return i
    }

    private fun parseReturnType(masked: String, colonIdx: Int): Pair<Boolean, Int> {
        val retStart = colonIdx + 1
        val endIdx = scanReturnTypeEnd(masked, retStart)
        val retType = masked.substring(retStart, endIdx).trim()
        return Pair(isUnitReturnType(retType), endIdx)
    }

    private fun findBlockBodyBounds(masked: String, openBrace: Int): Pair<Int, Int>? {
        var depth = 0
        for (j in openBrace until masked.length) {
            when (masked[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return Pair(openBrace, j)
                }
            }
        }
        return null
    }

    private val exprContinuationSuffixes = listOf(
        "+", "-", "*", "/", "%", "&&", "||", "?:", ".", "?.", "::", ",", "->", "="
    )

    private fun hasTrailingContinuation(prevTrimmed: String): Boolean =
        exprContinuationSuffixes.any { prevTrimmed.endsWith(it) }

    private fun hasLeadingContinuation(masked: String, startK: Int): Boolean {
        var k = startK
        while (k < masked.length && (masked[k] == ' ' || masked[k] == '\t' || masked[k] == '\r')) {
            k++
        }
        if (k >= masked.length) return false
        return masked.startsWith(".", k) || masked.startsWith("?.", k) || masked.startsWith("::", k)
    }

    private fun isExpressionLineContinuation(masked: String, exprStart: Int, newlineIdx: Int): Boolean {
        val prevTrimmed = masked.substring(exprStart + 1, newlineIdx).trimEnd()
        if (hasTrailingContinuation(prevTrimmed)) return true
        return hasLeadingContinuation(masked, newlineIdx + 1)
    }

    private class DepthTracker {
        var paren = 0
        var brace = 0
        var bracket = 0
        val isZero: Boolean get() = paren == 0 && brace == 0 && bracket == 0

        fun update(c: Char) {
            when (c) {
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '{' -> brace++
                '}' -> if (brace > 0) brace--
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
            }
        }
    }

    private fun isExpressionCompleteAtNewline(
        c: Char,
        startedExpr: Boolean,
        depths: DepthTracker,
        masked: String,
        exprStart: Int,
        j: Int
    ): Boolean {
        if (c != '\n') return false
        if (!startedExpr || !depths.isZero) return false
        return !isExpressionLineContinuation(masked, exprStart, j)
    }

    private fun findExpressionBodyBounds(masked: String, exprStart: Int): Pair<Int, Int> {
        val depths = DepthTracker()
        var j = exprStart + 1
        var startedExpr = false

        while (j < masked.length) {
            val c = masked[j]
            if (!startedExpr && !c.isWhitespace()) {
                startedExpr = true
            }
            if (isExpressionCompleteAtNewline(c, startedExpr, depths, masked, exprStart, j)) {
                return Pair(exprStart, j - 1)
            }
            depths.update(c)
            j++
        }
        return Pair(exprStart, (masked.length - 1).coerceAtLeast(exprStart))
    }

    private fun resolveReturnTypeAndBodyBounds(masked: String, closeParenIdx: Int): Triple<Boolean, Int, Int> {
        var i = skipWhitespace(masked, closeParenIdx + 1)
        var returnsUnit = true
        if (i < masked.length && masked[i] == ':') {
            val (unitResult, nextIdx) = parseReturnType(masked, i)
            returnsUnit = unitResult
            i = nextIdx
        }

        i = skipWhitespace(masked, i)
        if (i < masked.length && masked[i] == '{') {
            val bounds = findBlockBodyBounds(masked, i)
            if (bounds != null) {
                return Triple(returnsUnit, bounds.first, bounds.second)
            }
        } else if (i < masked.length && masked[i] == '=') {
            val bounds = findExpressionBodyBounds(masked, i)
            return Triple(returnsUnit, bounds.first, bounds.second)
        }
        return Triple(returnsUnit, -1, -1)
    }

    private fun splitTopLevelComma(slice: String): List<String> {
        val out = mutableListOf<String>()
        var angle = 0
        var paren = 0
        var bracket = 0
        var start = 0
        for (i in slice.indices) {
            when (slice[i]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                ',' -> if (angle == 0 && paren == 0 && bracket == 0) {
                    out.add(slice.substring(start, i))
                    start = i + 1
                }
            }
        }
        if (start <= slice.length) {
            out.add(slice.substring(start))
        }
        return out
    }

    private fun findTopLevelEquals(seg: String): Int {
        var angle = 0
        var paren = 0
        for (i in seg.indices) {
            when (seg[i]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '=' -> if (angle == 0 && paren == 0) return i
            }
        }
        return -1
    }

    private fun findTopLevelColon(seg: String): Int {
        var angle = 0
        var paren = 0
        for (i in seg.indices) {
            when (seg[i]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                ':' -> if (angle == 0 && paren == 0) return i
            }
        }
        return -1
    }

    private fun containsEffectInBlock(maskedBody: String, openBrace: Int, effectRe: Regex): Boolean {
        val closeBrace = findBalancedCloseBrace(maskedBody, openBrace)
        if (closeBrace <= openBrace) return false
        val blockSlice = maskedBody.substring(openBrace + 1, closeBrace)
        return effectRe.containsMatchIn(blockSlice)
    }

    private fun hasEffectInDirectBraceBlocks(maskedBody: String, regex: Regex, effectRe: Regex): Boolean {
        for (m in regex.findAll(maskedBody)) {
            if (containsEffectInBlock(maskedBody, m.range.last, effectRe)) return true
        }
        return false
    }

    private fun findConditionalBlockOpenBrace(maskedBody: String, openParen: Int): Int? {
        val closeParen = findBalancedCloseParen(maskedBody, openParen)
        if (closeParen < 0) return null
        val i = skipWhitespace(maskedBody, closeParen + 1)
        if (i < maskedBody.length && maskedBody[i] == '{') return i
        return null
    }

    private fun hasConditionalParenEffect(maskedBody: String, effectRe: Regex): Boolean {
        val condRe = Regex("""\b(if|when|for|while)\s*\(""")
        for (m in condRe.findAll(maskedBody)) {
            val openBrace = findConditionalBlockOpenBrace(maskedBody, m.range.last)
            if (openBrace != null && containsEffectInBlock(maskedBody, openBrace, effectRe)) {
                return true
            }
        }
        return false
    }

    private fun hasConditionalEffectOrRemember(maskedBody: String): Boolean {
        val effectRe = Regex("""\b(remember|rememberSaveable|LaunchedEffect|DisposableEffect)\b""")
        if (hasConditionalParenEffect(maskedBody, effectRe)) return true
        if (hasEffectInDirectBraceBlocks(maskedBody, Regex("""\bwhen\s*\{"""), effectRe)) return true
        return hasEffectInDirectBraceBlocks(maskedBody, Regex("""\?\.\s*let\s*\{"""), effectRe)
    }

    private fun hasUnrememberedStateCall(maskedBody: String): Boolean {
        val rememberRanges = mutableListOf<IntRange>()
        val remRe = Regex("""\b(remember|rememberSaveable)\b[^{]*\{""")
        for (rm in remRe.findAll(maskedBody)) {
            val openBrace = rm.range.last
            val closeBrace = findBalancedCloseBrace(maskedBody, openBrace)
            if (closeBrace > openBrace) {
                rememberRanges.add(openBrace..closeBrace)
            }
        }
        val stateCallRe = Regex("""\b(mutableStateOf|mutableIntStateOf|mutableLongStateOf|mutableFloatStateOf|derivedStateOf|MutableInteractionSource)\s*\(""")
        for (m in stateCallRe.findAll(maskedBody)) {
            val pos = m.range.first
            if (rememberRanges.none { pos in it }) {
                return true
            }
        }
        return false
    }

    private fun hasLazyItemsWithoutKey(maskedBody: String): Boolean {
        val itemsRe = Regex("""\b(items|itemsIndexed)\s*\(""")
        for (m in itemsRe.findAll(maskedBody)) {
            val openParen = m.range.last
            val closeParen = findBalancedCloseParen(maskedBody, openParen)
            if (closeParen > openParen) {
                val argsSlice = maskedBody.substring(openParen + 1, closeParen)
                if (!Regex("""\bkey\s*=""").containsMatchIn(argsSlice)) {
                    return true
                }
            }
        }
        return false
    }

    private fun findBalancedCloseBrace(text: String, openBraceIdx: Int): Int {
        if (openBraceIdx < 0 || openBraceIdx >= text.length || text[openBraceIdx] != '{') return -1
        var depth = 0
        for (i in openBraceIdx until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
}

