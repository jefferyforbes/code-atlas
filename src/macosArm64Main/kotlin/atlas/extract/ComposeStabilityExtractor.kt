package atlas.extract

import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.GraphEdge
import atlas.model.GraphNode

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

        private val PACKAGE_RE = Regex("""(?m)^\s*package\s+([A-Za-z0-9_.]+)""")
        private val SIDE_EFFECT_CALLBACK_RE = Regex("""^on[A-Z]\w*(And|Then|Navigate|Route|Pop|Dismiss|Fetch|Load)[A-Z]\w*$""")
        private val UI_EMITTING_CALL_RE = Regex("""\b(Column|Row|Box|Scaffold|Surface|Card|Text|Button|Icon|IconButton|LazyColumn|LazyRow|LazyVerticalGrid|LazyHorizontalGrid|OutlinedTextField|TextField|TopAppBar|ModalBottomSheet|AlertDialog|Image|Spacer)\s*\(""")
        private val COLLECT_AS_STATE_RAW_RE = Regex("""\.collectAsState\s*\(""")
        private val SCROLL_READ_RE = Regex("""\b\w*(scrollState|ScrollState|lazyListState|LazyListState|pagerState|PagerState)\.(value|firstVisibleItemIndex|firstVisibleItemScrollOffset)\b""")
        private val HARDCODED_TEXT_RE = Regex("""\bText\s*\(\s*(?:text\s*=\s*)?"([^"$\\]{2,})"|\bcontentDescription\s*=\s*"([^"$\\]{2,})"""")
        private val VM_CONTEXT_INJECTION_RE = Regex("""\b\w*ViewModel\s*\([^)]*\b(context|LocalContext\.current|application)\b""")
        private val COMPOSE_CALL_RE = Regex("""\b([A-Z][A-Za-z0-9_]*)\s*\(""")
        private val PROVIDES_LOCAL_RE = Regex("""\b(Local[A-Za-z0-9_]+)\s+provides\b""")
        private val CONSUMES_LOCAL_RE = Regex("""\b(Local[A-Za-z0-9_]+)\.current\b""")
        private val OBSERVES_STATE_RE = Regex("""\b([a-zA-Z_][A-Za-z0-9_]*)\.(collectAsStateWithLifecycle|collectAsState)\s*\(""")
        private val MUTATES_PROP_RE = Regex("""\b([a-zA-Z_][A-Za-z0-9_]*)\.([a-zA-Z_][A-Za-z0-9_]*)\s*=(?!=)""")
        private val INLINE_MOD_RE = Regex("""\binline\b""")
        private val PRIVATE_MOD_RE = Regex("""\bprivate\b""")
        private val EXPECT_MOD_RE = Regex("""\bexpect\b""")
        private val ACTUAL_MOD_RE = Regex("""\bactual\b""")
        private val FUN_KEYWORD_RE = Regex("""\bfun\b""")
        private val PREVIEW_ANNOTATION_RE = Regex("""@Preview[A-Za-z0-9_]*""")
        private val COMPOSABLE_FUN_RE = Regex("""@Composable\b[\s\S]*?\bfun\s+(?:<(?:[^<>]|<[^<>]*>)+>\s*)?(?:([A-Za-z0-9_.<>?]+)\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        private val WHITESPACE_RE = Regex("""\s+""")
        private val EFFECT_CALL_RE = Regex("""\b(remember|rememberSaveable|LaunchedEffect|DisposableEffect)\b""")
        private val CONDITIONAL_PAREN_RE = Regex("""\b(if|when|for|while)\s*\(""")
        private val WHEN_BRACE_RE = Regex("""\bwhen\s*\{""")
        private val SAFE_LET_BRACE_RE = Regex("""\?\.\s*let\s*\{""")
        private val REMEMBER_BLOCK_RE = Regex("""\b(remember|rememberSaveable)\b[^{]*\{""")
        private val STATE_ALLOC_CALL_RE = Regex("""\b(mutableStateOf|mutableIntStateOf|mutableLongStateOf|mutableFloatStateOf|derivedStateOf|MutableInteractionSource)\s*\(""")
        private val LAZY_ITEMS_CALL_RE = Regex("""\b(items|itemsIndexed)\s*\(""")
        private val KEY_NAMED_ARG_RE = Regex("""\bkey\s*=""")

        private fun containsIdentifier(text: String, identifier: String): Boolean {
            if (identifier.isEmpty()) return false
            var idx = text.indexOf(identifier)
            while (idx >= 0) {
                val beforeOk = idx == 0 || (!text[idx - 1].isLetterOrDigit() && text[idx - 1] != '_')
                val afterIdx = idx + identifier.length
                val afterOk = afterIdx >= text.length || (!text[afterIdx].isLetterOrDigit() && text[afterIdx] != '_')
                if (beforeOk && afterOk) return true
                idx = text.indexOf(identifier, idx + 1)
            }
            return false
        }

        fun buildLineOffsets(text: String): IntArray =
            LineOffsetIndex.from(text).offsets

        fun lineForOffset(lineOffsets: IntArray, offset: Int): Int =
            LineOffsetIndex(lineOffsets).lineNumberAt(offset)

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

    private data class ComposableHeaderFlags(
        val isPreview: Boolean,
        val isReadonly: Boolean,
        val isNonRestartable: Boolean,
        val isInline: Boolean,
        val isPrivate: Boolean,
        val isExpect: Boolean,
        val isActual: Boolean
    )

    private data class ComposableSlices(
        val rawParamSlice: String,
        val maskedParamSlice: String,
        val rawBodySlice: String,
        val maskedBodySlice: String,
        val bodyOpenBraceIdx: Int,
        val endLine: Int,
        val returnsUnit: Boolean
    )

    private data class ModifierParamFlags(
        val hasModifierParam: Boolean,
        val modifierHasDefault: Boolean,
        val modifierIsFirstOptional: Boolean
    )

    private data class ComposableStabilityMetrics(
        val receiverStability: String,
        val usedUnstableCount: Int,
        val usedRuntimeCount: Int,
        val isRestartable: Boolean,
        val isSkippable: Boolean
    )

    private data class ComposeFileContext(
        val sourceText: String,
        val masked: String,
        val lineOffsets: IntArray,
        val fileId: String,
        val projectSlug: String,
        val relPath: String,
        val pkg: String,
        val sourceSet: String,
        val stabilityPatterns: List<Regex>,
        val localClassStability: Map<String, Pair<String, String>>,
        val workspaceNodes: List<GraphNode>,
        val hasPreviewInFile: Boolean
    )

    private data class ComposableDeclContext(
        val nodeId: String,
        val fqName: String,
        val funName: String,
        val startLine: Int,
        val header: ComposableHeaderFlags,
        val slices: ComposableSlices,
        val parsedParams: List<ComposeParamRecord>,
        val modFlags: ModifierParamFlags,
        val metrics: ComposableStabilityMetrics
    )

    private fun resolveReceiverStability(
        receiverType: String,
        pkg: String,
        stabilityPatterns: List<Regex>,
        localClassStability: Map<String, Pair<String, String>>,
        workspaceNodes: List<GraphNode>
    ): String {
        if (receiverType.isEmpty()) return "STABLE"
        return inferTypeStability(receiverType, pkg, stabilityPatterns, localClassStability, workspaceNodes).first
    }

    private fun countUnstableAndRuntimeParams(parsedParams: List<ComposeParamRecord>): Pair<Int, Int> {
        var unstable = 0
        var runtime = 0
        for (p in parsedParams) {
            if (!p.isUsedInBody) continue
            when (p.stability) {
                "RUNTIME" -> runtime++
                "UNSTABLE", "UNCERTAIN" -> unstable++
            }
        }
        return Pair(unstable, runtime)
    }

    private fun computeStabilityMetrics(
        receiverStability: String,
        unstableCount: Int,
        runtimeCount: Int,
        header: ComposableHeaderFlags,
        returnsUnit: Boolean
    ): ComposableStabilityMetrics {
        val isRestartable = !header.isInline && !header.isReadonly && !header.isNonRestartable && returnsUnit
        val isSkippable = isRestartable && unstableCount == 0 && runtimeCount == 0 && receiverStability == "STABLE"
        return ComposableStabilityMetrics(
            receiverStability = receiverStability,
            usedUnstableCount = unstableCount,
            usedRuntimeCount = runtimeCount,
            isRestartable = isRestartable,
            isSkippable = isSkippable
        )
    }

    private fun buildComposeNodeRecord(
        fileCtx: ComposeFileContext,
        declCtx: ComposableDeclContext,
        dominantProv: String,
        violationMask: Int
    ): ComposeNodeRecord {
        return ComposeNodeRecord(
            nodeId = declCtx.nodeId,
            projectSlug = fileCtx.projectSlug,
            fqName = declCtx.fqName,
            filePath = fileCtx.relPath,
            startLine = declCtx.startLine,
            endLine = declCtx.slices.endLine,
            sourceSet = fileCtx.sourceSet,
            isRestartable = declCtx.metrics.isRestartable,
            isSkippable = declCtx.metrics.isSkippable,
            isReadonly = declCtx.header.isReadonly,
            isInline = declCtx.header.isInline,
            returnsUnit = declCtx.slices.returnsUnit,
            hasModifierParam = declCtx.modFlags.hasModifierParam,
            modifierIsFirstOptional = declCtx.modFlags.modifierIsFirstOptional,
            modifierHasDefault = declCtx.modFlags.modifierHasDefault,
            unstableParamCount = declCtx.metrics.usedUnstableCount,
            runtimeParamCount = declCtx.metrics.usedRuntimeCount,
            receiverStability = declCtx.metrics.receiverStability,
            provenance = dominantProv,
            violationMask = violationMask
        )
    }

    private fun extractHeaderFlags(masked: String, declMatch: MatchResult, funName: String): ComposableHeaderFlags {
        val lookbackStart = (declMatch.range.first - 220).coerceAtLeast(0)
        val declHeaderPrefix = masked.substring(lookbackStart, declMatch.range.last)
        return ComposableHeaderFlags(
            isPreview = declHeaderPrefix.contains("@Preview") || funName.endsWith("Preview"),
            isReadonly = declHeaderPrefix.contains("@ReadOnlyComposable"),
            isNonRestartable = declHeaderPrefix.contains("@NonRestartableComposable"),
            isInline = INLINE_MOD_RE.containsMatchIn(declHeaderPrefix),
            isPrivate = PRIVATE_MOD_RE.containsMatchIn(declHeaderPrefix),
            isExpect = EXPECT_MOD_RE.containsMatchIn(declHeaderPrefix),
            isActual = ACTUAL_MOD_RE.containsMatchIn(declHeaderPrefix)
        )
    }

    private fun extractComposableSlices(
        sourceText: String,
        masked: String,
        openParenIdx: Int,
        closeParenIdx: Int,
        lineOffsets: IntArray
    ): ComposableSlices {
        val rawParamSlice = sourceText.substring(openParenIdx + 1, closeParenIdx)
        val maskedParamSlice = masked.substring(openParenIdx + 1, closeParenIdx)
        val (returnsUnit, bodyOpenBraceIdx, bodyCloseBraceIdx) = resolveReturnTypeAndBodyBounds(masked, closeParenIdx)
        val endLine = if (bodyCloseBraceIdx > 0) lineForOffset(lineOffsets, bodyCloseBraceIdx) else lineForOffset(lineOffsets, closeParenIdx)
        val hasValidBody = bodyOpenBraceIdx in 0 until bodyCloseBraceIdx
        val rawBodySlice = if (hasValidBody && bodyCloseBraceIdx <= sourceText.length) {
            sourceText.substring(bodyOpenBraceIdx, bodyCloseBraceIdx + 1)
        } else ""
        val maskedBodySlice = if (hasValidBody && bodyCloseBraceIdx <= masked.length) {
            masked.substring(bodyOpenBraceIdx, bodyCloseBraceIdx + 1)
        } else ""
        return ComposableSlices(
            rawParamSlice = rawParamSlice,
            maskedParamSlice = maskedParamSlice,
            rawBodySlice = rawBodySlice,
            maskedBodySlice = maskedBodySlice,
            bodyOpenBraceIdx = bodyOpenBraceIdx,
            endLine = endLine,
            returnsUnit = returnsUnit
        )
    }

    private fun analyzeModifierParams(parsedParams: List<ComposeParamRecord>): ModifierParamFlags {
        val modIdx = parsedParams.indexOfFirst {
            it.paramName == "modifier" || it.typeSignature.removeSuffix("?").endsWith("Modifier")
        }
        val hasModifier = modIdx >= 0
        val firstOptionalIdx = parsedParams.indexOfFirst { it.hasDefault }
        return ModifierParamFlags(
            hasModifierParam = hasModifier,
            modifierHasDefault = hasModifier && parsedParams[modIdx].hasDefault,
            modifierIsFirstOptional = hasModifier && (firstOptionalIdx == -1 || modIdx == firstOptionalIdx)
        )
    }

    private fun computeStabilityViolationBits(
        isRestartable: Boolean,
        isSkippable: Boolean,
        usedUnstableCount: Int,
        parsedParams: List<ComposeParamRecord>
    ): Int {
        var mask = 0
        if (isRestartable && !isSkippable && usedUnstableCount > 0) mask = mask or BIT_STAB_001
        if (parsedParams.any { it.stabilityReason.startsWith("BROKEN_STABILITY_CONTRACT") }) mask = mask or BIT_STAB_002
        return mask
    }

    private fun computeStateViolationBits(
        maskedBodySlice: String,
        parsedParams: List<ComposeParamRecord>
    ): Int {
        var mask = 0
        if (hasConditionalEffectOrRemember(maskedBodySlice)) mask = mask or BIT_STATE_001
        val hasStateFlowParam = parsedParams.any {
            it.typeSignature.contains("MutableStateFlow") || it.typeSignature.contains("MutableSharedFlow")
        }
        if (COLLECT_AS_STATE_RAW_RE.containsMatchIn(maskedBodySlice) || hasStateFlowParam) {
            mask = mask or BIT_STATE_002
        }
        if (hasUnrememberedStateCall(maskedBodySlice)) mask = mask or BIT_STATE_003
        return mask
    }

    private fun computeApiViolationBits(declCtx: ComposableDeclContext): Int {
        var mask = 0
        val emitsUi = UI_EMITTING_CALL_RE.containsMatchIn(declCtx.slices.maskedBodySlice)
        val isScreenRoot = declCtx.funName.endsWith("Screen") || declCtx.funName.endsWith("Route")
        val requiresModifier = !declCtx.header.isPrivate && !declCtx.header.isPreview && !isScreenRoot && declCtx.slices.returnsUnit && emitsUi
        if (requiresModifier) {
            val modFlags = declCtx.modFlags
            val invalidModifier = !modFlags.hasModifierParam || !modFlags.modifierHasDefault || !modFlags.modifierIsFirstOptional
            if (invalidModifier) mask = mask or BIT_API_001
        }
        if (declCtx.parsedParams.any { SIDE_EFFECT_CALLBACK_RE.matches(it.paramName) }) mask = mask or BIT_API_002
        return mask
    }

    private fun computePerfAndArchViolationBits(
        maskedBodySlice: String,
        rawBodySlice: String,
        funName: String,
        isPreview: Boolean,
        hasPreviewInFile: Boolean
    ): Int {
        var mask = 0
        if (hasLazyItemsWithoutKey(maskedBodySlice)) mask = mask or BIT_PERF_001
        if (SCROLL_READ_RE.containsMatchIn(maskedBodySlice) && !maskedBodySlice.contains("derivedStateOf")) mask = mask or BIT_PERF_002
        if (!isPreview && HARDCODED_TEXT_RE.containsMatchIn(rawBodySlice)) mask = mask or BIT_I18N_001
        val isScreenMissingPreview = !isPreview && funName.endsWith("Screen") && !hasPreviewInFile
        if (VM_CONTEXT_INJECTION_RE.containsMatchIn(maskedBodySlice) || isScreenMissingPreview) {
            mask = mask or BIT_ARCH_001
        }
        return mask
    }

    /**
     * Computes the 12-rule Compose violation bitmask for a composable function.
     */
    private fun computeComposableViolationMask(
        fileCtx: ComposeFileContext,
        declCtx: ComposableDeclContext
    ): Int {
        return computeStabilityViolationBits(
            declCtx.metrics.isRestartable,
            declCtx.metrics.isSkippable,
            declCtx.metrics.usedUnstableCount,
            declCtx.parsedParams
        ) or
            computeStateViolationBits(declCtx.slices.maskedBodySlice, declCtx.parsedParams) or
            computeApiViolationBits(declCtx) or
            computePerfAndArchViolationBits(
                declCtx.slices.maskedBodySlice,
                declCtx.slices.rawBodySlice,
                declCtx.funName,
                declCtx.header.isPreview,
                fileCtx.hasPreviewInFile
            )
    }

    private fun extractCompositionLocalEdges(
        nodeId: String,
        projectSlug: String,
        bodyOpenBraceIdx: Int,
        maskedBodySlice: String,
        lineOffsets: IntArray,
        composeEdges: MutableList<GraphEdge>
    ) {
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
    }

    private fun extractStateAndPropertyEdges(
        nodeId: String,
        projectSlug: String,
        bodyOpenBraceIdx: Int,
        maskedBodySlice: String,
        lineOffsets: IntArray,
        composeEdges: MutableList<GraphEdge>
    ) {
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
    }

    private fun checkUnstableParamForwarding(
        maskedBodySlice: String,
        callMatch: MatchResult,
        unstableParamNames: Set<String>
    ): Boolean {
        if (unstableParamNames.isEmpty()) return false
        val callClose = findBalancedCloseParen(maskedBodySlice, callMatch.range.last)
        if (callClose <= callMatch.range.last) return false
        val argSlice = maskedBodySlice.substring(callMatch.range.last + 1, callClose)
        return unstableParamNames.any { pName -> containsIdentifier(argSlice, pName) }
    }

    private fun extractComposableSemanticEdges(
        fileCtx: ComposeFileContext,
        declCtx: ComposableDeclContext,
        composeEdges: MutableList<GraphEdge>
    ): Boolean {
        var hasUnstableForwarding = false
        val unstableParamNames = declCtx.parsedParams
            .filter { it.stability in setOf("UNSTABLE", "RUNTIME", "UNCERTAIN") }
            .map { it.paramName }
            .toSet()

        val maskedBodySlice = declCtx.slices.maskedBodySlice
        val bodyOpenBraceIdx = declCtx.slices.bodyOpenBraceIdx
        for (callMatch in COMPOSE_CALL_RE.findAll(maskedBodySlice)) {
            val calleeName = callMatch.groupValues[1]
            if (calleeName == declCtx.funName || calleeName in ComposeTypeStabilityAnalyzer.PRIMITIVE_STABLE_TYPES) continue
            val callOffset = bodyOpenBraceIdx + callMatch.range.first
            val callLine = lineForOffset(fileCtx.lineOffsets, callOffset)
            composeEdges.add(
                GraphEdge(
                    edgeId = "${declCtx.nodeId}:calls_composable:$calleeName:$callLine",
                    projectId = fileCtx.projectSlug,
                    sourceNodeId = declCtx.nodeId,
                    targetNodeId = "sym:$calleeName",
                    edgeType = "CALLS_COMPOSABLE",
                    provenance = "EXTRACTED_AST",
                    confidence = 0.95,
                    lineNumber = callLine
                )
            )
            if (checkUnstableParamForwarding(maskedBodySlice, callMatch, unstableParamNames)) {
                hasUnstableForwarding = true
            }
        }

        extractCompositionLocalEdges(declCtx.nodeId, fileCtx.projectSlug, bodyOpenBraceIdx, maskedBodySlice, fileCtx.lineOffsets, composeEdges)
        extractStateAndPropertyEdges(declCtx.nodeId, fileCtx.projectSlug, bodyOpenBraceIdx, maskedBodySlice, fileCtx.lineOffsets, composeEdges)

        if (declCtx.header.isExpect || declCtx.header.isActual) {
            composeEdges.add(
                GraphEdge(
                    edgeId = "${declCtx.nodeId}:expect_actual:${declCtx.funName}:${declCtx.startLine}",
                    projectId = fileCtx.projectSlug,
                    sourceNodeId = declCtx.nodeId,
                    targetNodeId = "sym:${declCtx.funName}",
                    edgeType = "EXPECT_ACTUAL",
                    provenance = "EXTRACTED_AST",
                    confidence = 1.0,
                    lineNumber = declCtx.startLine
                )
            )
        }
        return hasUnstableForwarding
    }

    private fun extractFilePropertyMutations(
        fileId: String,
        projectSlug: String,
        masked: String,
        lineOffsets: IntArray,
        composeEdges: MutableList<GraphEdge>
    ) {
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
    }

    private fun resolveDominantProvenance(parsedParams: List<ComposeParamRecord>): String {
        return if (parsedParams.any { it.provenance == "STABILITY_CONFIG" }) "STABILITY_CONFIG" else "STATIC_INFERRED"
    }

    private fun resolveFqName(pkg: String, funName: String): String {
        return if (pkg.isNotEmpty()) "$pkg.$funName" else funName
    }

    private fun parseSingleComposableDecl(
        composableMatch: MatchResult,
        fileCtx: ComposeFileContext,
        composeParams: MutableList<ComposeParamRecord>,
        composeEdges: MutableList<GraphEdge>
    ): ComposeNodeRecord? {
        val prefixWindow = fileCtx.masked.substring(composableMatch.range.first, composableMatch.range.last)
        if (FUN_KEYWORD_RE.findAll(prefixWindow).count() != 1) return null

        val receiverType = composableMatch.groupValues[1].trim()
        val funName = composableMatch.groupValues[2].trim()
        val startLine = lineForOffset(fileCtx.lineOffsets, composableMatch.range.first)
        val header = extractHeaderFlags(fileCtx.masked, composableMatch, funName)

        val openParenIdx = composableMatch.range.last
        val closeParenIdx = findBalancedCloseParen(fileCtx.masked, openParenIdx)
        if (closeParenIdx < 0) return null

        val slices = extractComposableSlices(fileCtx.sourceText, fileCtx.masked, openParenIdx, closeParenIdx, fileCtx.lineOffsets)
        val nodeId = "${fileCtx.fileId}#$funName"
        val fqName = resolveFqName(fileCtx.pkg, funName)

        val parsedParams = parseParameters(nodeId, slices, fileCtx)
        composeParams.addAll(parsedParams)

        val receiverStability = resolveReceiverStability(
            receiverType, fileCtx.pkg, fileCtx.stabilityPatterns, fileCtx.localClassStability, fileCtx.workspaceNodes
        )
        val (usedUnstableCount, usedRuntimeCount) = countUnstableAndRuntimeParams(parsedParams)
        val metrics = computeStabilityMetrics(receiverStability, usedUnstableCount, usedRuntimeCount, header, slices.returnsUnit)
        val modFlags = analyzeModifierParams(parsedParams)

        val declCtx = ComposableDeclContext(
            nodeId = nodeId,
            fqName = fqName,
            funName = funName,
            startLine = startLine,
            header = header,
            slices = slices,
            parsedParams = parsedParams,
            modFlags = modFlags,
            metrics = metrics
        )

        var violationMask = computeComposableViolationMask(fileCtx, declCtx)
        val hasUnstableForwarding = extractComposableSemanticEdges(fileCtx, declCtx, composeEdges)
        if (hasUnstableForwarding) {
            violationMask = violationMask or BIT_STAB_003
        }

        val dominantProv = resolveDominantProvenance(parsedParams)
        return buildComposeNodeRecord(fileCtx, declCtx, dominantProv, violationMask)
    }

    fun extractKotlinFile(
        projectSlug: String,
        relPath: String,
        sourceText: String,
        stabilityPatterns: List<Regex> = emptyList(),
        workspaceNodes: List<GraphNode> = emptyList(),
        precomputedClassStability: Map<String, Pair<String, String>> = emptyMap()
    ): ExtractionBundle {
        val masked = PolyglotAstExtractor.maskCommentsAndStrings(sourceText, "kotlin")
        val localClassStability = analyzeLocalClasses(
            sourceText, masked, stabilityPatterns, workspaceNodes, precomputedClassStability
        )
        return extractWithPrecomputedStability(
            PrecomputedComposeInput(
                projectSlug = projectSlug,
                relPath = relPath,
                sourceText = sourceText,
                masked = masked,
                stabilityPatterns = stabilityPatterns,
                localClassStability = localClassStability,
                workspaceNodes = workspaceNodes
            )
        )
    }

    internal data class PrecomputedComposeInput(
        val projectSlug: String,
        val relPath: String,
        val sourceText: String,
        val masked: String,
        val stabilityPatterns: List<Regex>,
        val localClassStability: Map<String, Pair<String, String>>,
        val workspaceNodes: List<GraphNode> = emptyList()
    )

    internal fun extractWithPrecomputedStability(input: PrecomputedComposeInput): ExtractionBundle {
        val masked = input.masked
        val lineOffsets = buildLineOffsets(input.sourceText)
        val pkg = PACKAGE_RE.find(masked)?.groupValues?.get(1)?.trim() ?: ""
        val sourceSet = detectKmpSourceSet(input.relPath)
        val fileId = "${input.projectSlug}:${input.relPath}"
        val hasPreviewInFile = PREVIEW_ANNOTATION_RE.containsMatchIn(masked)
        val fileCtx = ComposeFileContext(
            sourceText = input.sourceText,
            masked = masked,
            lineOffsets = lineOffsets,
            fileId = fileId,
            projectSlug = input.projectSlug,
            relPath = input.relPath,
            pkg = pkg,
            sourceSet = sourceSet,
            stabilityPatterns = input.stabilityPatterns,
            localClassStability = input.localClassStability,
            workspaceNodes = input.workspaceNodes,
            hasPreviewInFile = hasPreviewInFile
        )

        val composeNodes = mutableListOf<ComposeNodeRecord>()
        val composeParams = mutableListOf<ComposeParamRecord>()
        val composeEdges = mutableListOf<GraphEdge>()

        for (declMatch in COMPOSABLE_FUN_RE.findAll(masked)) {
            val record = parseSingleComposableDecl(declMatch, fileCtx, composeParams, composeEdges) ?: continue
            composeNodes.add(record)
        }

        extractFilePropertyMutations(fileId, input.projectSlug, masked, lineOffsets, composeEdges)

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
    ): Pair<List<ComposeNodeRecord>, List<ComposeParamRecord>> =
        ComposeTelemetryParser.parseCompilerComposablesReport(projectSlug, reportText, existingNodes)

    /**
     * Parses bounded `@TraceRecomposition` runtime telemetry (JSONL or Logcat lines).
     * Enforces `MAX_TELEMETRY_BYTES = 16 MB` and `MAX_TELEMETRY_LINES = 100,000`.
     */
    fun parseTraceTelemetry(
        rawLogText: String,
        defaultSessionId: String = "default_session"
    ): List<TraceRecord> =
        ComposeTelemetryParser.parseTraceTelemetry(rawLogText, defaultSessionId)

    /**
     * Extracts class stability metadata directly from a Kotlin source text slice.
     */
    fun analyzeClassesInSource(
        sourceText: String,
        maskedText: String,
        stabilityPatterns: List<Regex> = emptyList()
    ): Map<String, Pair<String, String>> =
        ComposeTypeStabilityAnalyzer.analyzeClassesInSource(
            maskedText = maskedText,
            stabilityPatterns = stabilityPatterns,
            findBalancedCloseBrace = ::findBalancedCloseBrace
        )

    private fun analyzeLocalClasses(
        sourceText: String,
        maskedText: String,
        stabilityPatterns: List<Regex>,
        workspaceNodes: List<GraphNode>,
        precomputedClassStability: Map<String, Pair<String, String>> = emptyMap()
    ): Map<String, Pair<String, String>> =
        ComposeTypeStabilityAnalyzer.analyzeLocalClasses(
            maskedText = maskedText,
            stabilityPatterns = stabilityPatterns,
            workspaceNodes = workspaceNodes,
            precomputedClassStability = precomputedClassStability,
            findBalancedCloseBrace = ::findBalancedCloseBrace
        )

    private fun extractParamName(declPart: String, colonIdx: Int): String? {
        val rawPrefix = declPart.substring(0, colonIdx).trim()
        val tokens = rawPrefix.split(WHITESPACE_RE).filter { it.isNotBlank() }
        val candidate = tokens.lastOrNull()?.removePrefix("vararg")?.trim()
        if (candidate.isNullOrEmpty()) return null
        return candidate
    }

    private fun isParamUsedInBody(paramName: String, maskedBodySlice: String): Boolean {
        if (maskedBodySlice.isBlank()) return true
        return containsIdentifier(maskedBodySlice, paramName)
    }

    private fun parseSingleParameter(
        nodeId: String,
        idx: Int,
        seg: String,
        rawSeg: String,
        maskedBodySlice: String,
        fileCtx: ComposeFileContext
    ): ComposeParamRecord? {
        val eqIdx = findTopLevelEquals(seg)
        val hasDefault = eqIdx >= 0
        val declPart = if (hasDefault) seg.substring(0, eqIdx).trim() else seg
        val rawDeclPart = if (hasDefault && eqIdx <= rawSeg.length) rawSeg.substring(0, eqIdx).trim() else rawSeg

        val colonIdx = findTopLevelColon(declPart)
        if (colonIdx < 0) return null

        val paramName = extractParamName(declPart, colonIdx) ?: return null
        val typeSig = rawDeclPart.substring(colonIdx + 1).trim().replace(WHITESPACE_RE, " ")
        val isUsed = isParamUsedInBody(paramName, maskedBodySlice)

        val (stability, reason) = inferTypeStability(
            typeSig = typeSig,
            pkg = fileCtx.pkg,
            stabilityPatterns = fileCtx.stabilityPatterns,
            localClassStability = fileCtx.localClassStability,
            workspaceNodes = fileCtx.workspaceNodes
        )
        val prov = if (reason.startsWith("STABILITY_CONFIG_MATCH")) "STABILITY_CONFIG" else "STATIC_INFERRED"

        return ComposeParamRecord(
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
    }

    private fun parseParameters(
        nodeId: String,
        slices: ComposableSlices,
        fileCtx: ComposeFileContext
    ): List<ComposeParamRecord> {
        if (slices.maskedParamSlice.isBlank()) return emptyList()
        val rawSegments = splitTopLevelComma(slices.rawParamSlice)
        val maskedSegments = splitTopLevelComma(slices.maskedParamSlice)
        val params = mutableListOf<ComposeParamRecord>()

        for (idx in maskedSegments.indices) {
            val seg = maskedSegments[idx].trim()
            val rawSeg = rawSegments.getOrElse(idx) { seg }.trim()
            if (seg.isEmpty()) continue

            val param = parseSingleParameter(
                nodeId = nodeId,
                idx = idx,
                seg = seg,
                rawSeg = rawSeg,
                maskedBodySlice = slices.maskedBodySlice,
                fileCtx = fileCtx
            )
            if (param != null) {
                params.add(param)
            }
        }
        return params
    }

    fun inferTypeStability(
        typeSig: String,
        pkg: String,
        stabilityPatterns: List<Regex>,
        localClassStability: Map<String, Pair<String, String>>,
        workspaceNodes: List<GraphNode>
    ): Pair<String, String> =
        ComposeTypeStabilityAnalyzer.inferTypeStability(
            typeSig = typeSig,
            ctx = ComposeTypeStabilityAnalyzer.TypeStabilityContext(
                pkg = pkg,
                stabilityPatterns = stabilityPatterns,
                localClassStability = localClassStability,
                workspaceNodes = workspaceNodes,
                splitTopLevelComma = ::splitTopLevelComma
            )
        )

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

    private fun hasTrailingContinuation(masked: String, exprStart: Int, newlineIdx: Int): Boolean {
        var p = newlineIdx - 1
        while (p > exprStart && (masked[p] == ' ' || masked[p] == '\t' || masked[p] == '\r')) {
            p--
        }
        if (p <= exprStart) return false
        return exprContinuationSuffixes.any { s ->
            p - s.length + 1 > exprStart && masked.startsWith(s, p - s.length + 1)
        }
    }

    private fun hasLeadingContinuation(masked: String, startK: Int): Boolean {
        var k = startK
        while (k < masked.length && (masked[k] == ' ' || masked[k] == '\t' || masked[k] == '\r')) {
            k++
        }
        if (k >= masked.length) return false
        return masked.startsWith(".", k) || masked.startsWith("?.", k) || masked.startsWith("::", k)
    }

    private fun isExpressionLineContinuation(masked: String, exprStart: Int, newlineIdx: Int): Boolean {
        if (hasTrailingContinuation(masked, exprStart, newlineIdx)) return true
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

    private class NestingDepthTracker {
        var angle = 0
        var paren = 0
        var bracket = 0

        val isTopLevel: Boolean get() = angle == 0 && paren == 0 && bracket == 0
        val isTopLevelNoBracket: Boolean get() = angle == 0 && paren == 0

        fun onChar(c: Char) {
            when (c) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
            }
        }
    }

    private fun splitTopLevelComma(slice: String): List<String> {
        val out = mutableListOf<String>()
        val tracker = NestingDepthTracker()
        var start = 0
        for (i in slice.indices) {
            val c = slice[i]
            if (c == ',' && tracker.isTopLevel) {
                out.add(slice.substring(start, i))
                start = i + 1
            } else {
                tracker.onChar(c)
            }
        }
        if (start <= slice.length) {
            out.add(slice.substring(start))
        }
        return out
    }

    private fun findTopLevelChar(seg: String, target: Char): Int {
        val tracker = NestingDepthTracker()
        for (i in seg.indices) {
            val c = seg[i]
            if (c == target && tracker.isTopLevelNoBracket) return i
            tracker.onChar(c)
        }
        return -1
    }

    private fun findTopLevelEquals(seg: String): Int = findTopLevelChar(seg, '=')

    private fun findTopLevelColon(seg: String): Int = findTopLevelChar(seg, ':')

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
        for (m in CONDITIONAL_PAREN_RE.findAll(maskedBody)) {
            val openBrace = findConditionalBlockOpenBrace(maskedBody, m.range.last)
            if (openBrace != null && containsEffectInBlock(maskedBody, openBrace, effectRe)) {
                return true
            }
        }
        return false
    }

    private fun hasConditionalEffectOrRemember(maskedBody: String): Boolean {
        if (hasConditionalParenEffect(maskedBody, EFFECT_CALL_RE)) return true
        if (hasEffectInDirectBraceBlocks(maskedBody, WHEN_BRACE_RE, EFFECT_CALL_RE)) return true
        return hasEffectInDirectBraceBlocks(maskedBody, SAFE_LET_BRACE_RE, EFFECT_CALL_RE)
    }

    private fun hasUnrememberedStateCall(maskedBody: String): Boolean {
        val rememberRanges = mutableListOf<IntRange>()
        for (rm in REMEMBER_BLOCK_RE.findAll(maskedBody)) {
            val openBrace = rm.range.last
            val closeBrace = findBalancedCloseBrace(maskedBody, openBrace)
            if (closeBrace > openBrace) {
                rememberRanges.add(openBrace..closeBrace)
            }
        }
        for (m in STATE_ALLOC_CALL_RE.findAll(maskedBody)) {
            val pos = m.range.first
            if (rememberRanges.none { pos in it }) {
                return true
            }
        }
        return false
    }

    private fun hasLazyItemsWithoutKey(maskedBody: String): Boolean {
        for (m in LAZY_ITEMS_CALL_RE.findAll(maskedBody)) {
            val openParen = m.range.last
            val closeParen = findBalancedCloseParen(maskedBody, openParen)
            if (closeParen > openParen) {
                val argsSlice = maskedBody.substring(openParen + 1, closeParen)
                if (!KEY_NAMED_ARG_RE.containsMatchIn(argsSlice)) {
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

