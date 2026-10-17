package atlas.extract

import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.GraphEdge
import atlas.model.GraphNode
import kotlinx.serialization.json.Json
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
        private val MUTATES_PROP_RE = Regex("""\b([a-zA-Z_][A-Za-z0-9_]*)\.([a-zA-Z_][A-Za-z0-9_]*)\s*=""")

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
        workspaceNodes: List<GraphNode> = emptyList()
    ): ExtractionBundle {
        val masked = PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "kotlin")
        val lineOffsets = buildLineOffsets(sourceText)
        val pkg = PACKAGE_RE.find(masked)?.groupValues?.get(1)?.trim() ?: ""
        val sourceSet = detectKmpSourceSet(relPath)
        val fileId = "$projectSlug:$relPath"

        // Collect local class stability info within this file and workspace
        val localClassStability = analyzeLocalClasses(projectSlug, relPath, sourceText, masked, stabilityPatterns, workspaceNodes)
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

            // Bit 1: COMPOSE-STAB-002 (Broken @Immutable/@Stable contract on parameter type or file class)
            if (parsedParams.any { it.stabilityReason.startsWith("BROKEN_STABILITY_CONTRACT") } ||
                localClassStability.values.any { it.second.startsWith("BROKEN_STABILITY_CONTRACT") }
            ) {
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

    /**
     * Parses bounded `@TraceRecomposition` runtime telemetry (JSONL or Logcat lines).
     * Enforces `MAX_TELEMETRY_BYTES = 16 MB` and `MAX_TELEMETRY_LINES = 100,000`.
     */
    fun parseTraceTelemetry(
        rawLogText: String,
        defaultSessionId: String = "default_session"
    ): List<TraceRecord> {
        val clipped = if (rawLogText.length > MAX_TELEMETRY_BYTES) {
            rawLogText.substring(0, MAX_TELEMETRY_BYTES)
        } else rawLogText

        val records = mutableListOf<TraceRecord>()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val logcatRe = Regex("""TraceRecomposition\s*:\s*composable=([A-Za-z0-9_.#:/-]+)\s+recompose=(\d+)\s+skip=(\d+)(?:\s+dirty=\[([^\]]*)\])?""")

        var lineCount = 0
        for (rawLine in clipped.lineSequence()) {
            if (++lineCount > MAX_TELEMETRY_LINES) break
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    val obj = json.parseToJsonElement(trimmed).jsonObject
                    val target = obj["composable"]?.jsonPrimitive?.content
                        ?: obj["node_id"]?.jsonPrimitive?.content
                        ?: obj["fqname"]?.jsonPrimitive?.content
                        ?: continue
                    val session = obj["session_id"]?.jsonPrimitive?.content ?: defaultSessionId
                    val recomp = obj["recomposition_count"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: obj["recompose"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: 0
                    val skip = obj["skip_count"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: obj["skip"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: 0
                    val dirtyArr = obj["dirty_params"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    val dirtyJson = dirtyArr.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
                        .let { if (dirtyArr.isEmpty()) "[]" else it }
                    val ts = obj["recorded_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1729190000L
                    records.add(
                        TraceRecord(
                            fqNameOrNodeId = target,
                            sessionId = session,
                            recompositionCount = recomp.coerceAtLeast(0),
                            skipCount = skip.coerceAtLeast(0),
                            dirtyParamsJson = dirtyJson,
                            recordedAt = ts
                        )
                    )
                } catch (_: Throwable) {
                    // Malformed JSONL line — skip safely without aborting
                }
                continue
            }

            val m = logcatRe.find(trimmed)
            if (m != null) {
                val target = m.groupValues[1]
                val recomp = m.groupValues[2].toIntOrNull() ?: 0
                val skip = m.groupValues[3].toIntOrNull() ?: 0
                val dirtyRaw = m.groupValues[4].trim()
                val dirtyList = if (dirtyRaw.isEmpty()) emptyList() else dirtyRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val dirtyJson = if (dirtyList.isEmpty()) "[]" else dirtyList.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
                records.add(
                    TraceRecord(
                        fqNameOrNodeId = target,
                        sessionId = defaultSessionId,
                        recompositionCount = recomp,
                        skipCount = skip,
                        dirtyParamsJson = dirtyJson,
                        recordedAt = 1729190000L
                    )
                )
            }
        }
        return records
    }

    private fun analyzeLocalClasses(
        projectSlug: String,
        relPath: String,
        sourceText: String,
        maskedText: String,
        stabilityPatterns: List<Regex>,
        workspaceNodes: List<GraphNode>
    ): Map<String, Pair<String, String>> {
        val result = mutableMapOf<String, Pair<String, String>>()
        val classRe = Regex("""((?:@(?:Immutable|Stable)\b[\s\S]*?)?)\b(data\s+class|value\s+class|enum\s+class|sealed\s+interface|sealed\s+class|class|interface)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^>]+>)?\s*(\([^)]*\))?""")

        for (m in classRe.findAll(maskedText)) {
            val annotations = m.groupValues[1]
            val kindKw = m.groupValues[2].replace(Regex("""\s+"""), " ")
            val className = m.groupValues[3]
            val ctorSlice = m.groupValues[4]

            val hasStableAnnotation = annotations.contains("@Immutable") || annotations.contains("@Stable")
            if (kindKw == "enum class" || kindKw == "value class") {
                result[className] = "STABLE" to "ENUM_OR_VALUE_CLASS"
                continue
            }
            if (kindKw == "interface" && !hasStableAnnotation) {
                result[className] = "UNCERTAIN" to "UNANNOTATED_INTERFACE"
                continue
            }

            // Inspect constructor parameters for `var` or standard `List`/`Set`/`Map`
            val ctorVarMatch = Regex("""\bvar\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""").find(ctorSlice)
            val rawCollectionMatch = Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(List|Set|Map|MutableList|MutableSet|MutableMap)<""").find(ctorSlice)

            // Inspect class body { ... } at depth 1 for mutable `var` properties
            var bodyVarName: String? = null
            var k = m.range.last
            var foundBrace = false
            while (k < maskedText.length) {
                val c = maskedText[k]
                if (c == '{') {
                    foundBrace = true
                    break
                }
                if (c == ';' || (c == '\n' && k + 1 < maskedText.length && maskedText.substring(k + 1).trimStart().let {
                    it.startsWith("class ") || it.startsWith("interface ") || it.startsWith("fun ") || it.startsWith("@Composable")
                })) {
                    break
                }
                k++
            }

            if (foundBrace) {
                val openBrace = k
                val closeBrace = findBalancedCloseBrace(maskedText, openBrace)
                if (closeBrace > openBrace) {
                    var depth = 1
                    var bIdx = openBrace + 1
                    val classLevelChars = CharArray(closeBrace - openBrace - 1)
                    while (bIdx < closeBrace) {
                        val ch = maskedText[bIdx]
                        when (ch) {
                            '{' -> { depth++; classLevelChars[bIdx - openBrace - 1] = ' ' }
                            '}' -> { depth--; classLevelChars[bIdx - openBrace - 1] = ' ' }
                            else -> {
                                classLevelChars[bIdx - openBrace - 1] = if (depth == 1) ch else ' '
                            }
                        }
                        bIdx++
                    }
                    val classLevelText = classLevelChars.concatToString()
                    val bodyVarMatch = Regex("""\bvar\s+([A-Za-z_][A-Za-z0-9_]*)""").find(classLevelText)
                    if (bodyVarMatch != null) {
                        bodyVarName = bodyVarMatch.groupValues[1]
                    }
                }
            }

            val varPropName = ctorVarMatch?.groupValues?.get(1) ?: bodyVarName

            if (hasStableAnnotation && (varPropName != null || rawCollectionMatch != null)) {
                val offender = varPropName ?: rawCollectionMatch?.groupValues?.get(1) ?: "field"
                result[className] = "UNSTABLE" to "BROKEN_STABILITY_CONTRACT:$className.$offender"
            } else if (varPropName != null) {
                result[className] = "UNSTABLE" to "MUTABLE_VAR_PROPERTY:$varPropName"
            } else if (rawCollectionMatch != null) {
                val propName = rawCollectionMatch.groupValues[1]
                val colType = rawCollectionMatch.groupValues[2]
                val matchedByConf = stabilityPatterns.any { it.matches("kotlin.collections.$colType") }
                if (matchedByConf) {
                    result[className] = "STABLE" to "STABILITY_CONFIG_MATCH:kotlin.collections.$colType"
                } else {
                    result[className] = "UNSTABLE" to "UNSTABLE_COLLECTION_PROPERTY:$propName($colType)"
                }
            } else if (hasStableAnnotation) {
                result[className] = "STABLE" to "EXPLICIT_STABLE_ANNOTATION"
            } else {
                result[className] = "STABLE" to "ALL_VAL_PROPERTIES_STABLE"
            }
        }

        // Also incorporate workspace class signatures
        for (wn in workspaceNodes) {
            if (wn.kind in setOf("class", "interface") && wn.name !in result) {
                val sig = wn.signature
                if (sig.contains("var ")) {
                    val prop = Regex("""\bvar\s+([A-Za-z_][A-Za-z0-9_]*)""").find(sig)?.groupValues?.get(1) ?: "prop"
                    result[wn.name] = "UNSTABLE" to "MUTABLE_VAR_PROPERTY:$prop"
                } else if (sig.contains("@Immutable") || sig.contains("@Stable") || sig.contains("data class")) {
                    result[wn.name] = "STABLE" to "WORKSPACE_DATA_CLASS"
                }
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

    private fun resolveReturnTypeAndBodyBounds(masked: String, closeParenIdx: Int): Triple<Boolean, Int, Int> {
        var i = closeParenIdx + 1
        while (i < masked.length && masked[i].isWhitespace()) i++

        var returnsUnit = true
        if (i < masked.length && masked[i] == ':') {
            i++
            val retStart = i
            var angleDepth = 0
            while (i < masked.length) {
                val c = masked[i]
                if (c == '<') angleDepth++
                else if (c == '>' && angleDepth > 0) angleDepth--
                else if (angleDepth == 0 && (c == '{' || c == '=' || c == '\n')) break
                i++
            }
            val retType = masked.substring(retStart, i).trim()
            returnsUnit = retType.isEmpty() || retType == "Unit"
        }

        while (i < masked.length && masked[i].isWhitespace()) i++
        if (i < masked.length && masked[i] == '{') {
            val openBrace = i
            var depth = 0
            for (j in openBrace until masked.length) {
                when (masked[j]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return Triple(returnsUnit, openBrace, j)
                    }
                }
            }
        } else if (i < masked.length && masked[i] == '=') {
            val exprStart = i
            var depthParen = 0
            var depthBrace = 0
            var depthBracket = 0
            var j = i + 1
            var startedExpr = false
            var exprEnd = (masked.length - 1).coerceAtLeast(exprStart)

            while (j < masked.length) {
                val c = masked[j]
                if (!startedExpr && !c.isWhitespace()) {
                    startedExpr = true
                }
                when (c) {
                    '(' -> depthParen++
                    ')' -> if (depthParen > 0) depthParen--
                    '{' -> depthBrace++
                    '}' -> if (depthBrace > 0) depthBrace--
                    '[' -> depthBracket++
                    ']' -> if (depthBracket > 0) depthBracket--
                    '\n' -> {
                        if (startedExpr && depthParen == 0 && depthBrace == 0 && depthBracket == 0) {
                            val prevTrimmed = masked.substring(i + 1, j).trimEnd()
                            val isContinuation = prevTrimmed.endsWith("+") ||
                                prevTrimmed.endsWith("-") ||
                                prevTrimmed.endsWith("*") ||
                                prevTrimmed.endsWith("/") ||
                                prevTrimmed.endsWith("%") ||
                                prevTrimmed.endsWith("&&") ||
                                prevTrimmed.endsWith("||") ||
                                prevTrimmed.endsWith("?:") ||
                                prevTrimmed.endsWith(".") ||
                                prevTrimmed.endsWith("?.") ||
                                prevTrimmed.endsWith("::") ||
                                prevTrimmed.endsWith(",") ||
                                prevTrimmed.endsWith("->") ||
                                prevTrimmed.endsWith("=")

                            var nextK = j + 1
                            while (nextK < masked.length && (masked[nextK] == ' ' || masked[nextK] == '\t' || masked[nextK] == '\r')) {
                                nextK++
                            }
                            val nextStartsContinuation = if (nextK < masked.length) {
                                masked.startsWith(".", nextK) ||
                                masked.startsWith("?.", nextK) ||
                                masked.startsWith("::", nextK)
                            } else false

                            if (!isContinuation && !nextStartsContinuation) {
                                exprEnd = j - 1
                                return Triple(returnsUnit, exprStart, exprEnd)
                            }
                        }
                    }
                }
                j++
            }
            return Triple(returnsUnit, exprStart, exprEnd)
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

    private fun hasConditionalEffectOrRemember(maskedBody: String): Boolean {
        val effectRe = Regex("""\b(remember|rememberSaveable|LaunchedEffect|DisposableEffect)\b""")
        val condRe = Regex("""\b(if|when|for|while)\s*\(""")
        for (m in condRe.findAll(maskedBody)) {
            val openParen = m.range.last
            val closeParen = findBalancedCloseParen(maskedBody, openParen)
            if (closeParen < 0) continue
            var i = closeParen + 1
            while (i < maskedBody.length && maskedBody[i].isWhitespace()) i++
            if (i < maskedBody.length && maskedBody[i] == '{') {
                val closeBrace = findBalancedCloseBrace(maskedBody, i)
                if (closeBrace > i) {
                    val blockSlice = maskedBody.substring(i + 1, closeBrace)
                    if (effectRe.containsMatchIn(blockSlice)) return true
                }
            }
        }
        val arglessWhenRe = Regex("""\bwhen\s*\{""")
        for (m in arglessWhenRe.findAll(maskedBody)) {
            val openBrace = m.range.last
            val closeBrace = findBalancedCloseBrace(maskedBody, openBrace)
            if (closeBrace > openBrace) {
                val blockSlice = maskedBody.substring(openBrace + 1, closeBrace)
                if (effectRe.containsMatchIn(blockSlice)) return true
            }
        }
        val letRe = Regex("""\?\.\s*let\s*\{""")
        for (m in letRe.findAll(maskedBody)) {
            val openBrace = m.range.last
            val closeBrace = findBalancedCloseBrace(maskedBody, openBrace)
            if (closeBrace > openBrace) {
                val blockSlice = maskedBody.substring(openBrace + 1, closeBrace)
                if (effectRe.containsMatchIn(blockSlice)) return true
            }
        }
        return false
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

