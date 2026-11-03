package atlas.extract

import atlas.model.GraphNode

/**
 * 4-state Kotlin type and class stability inference engine (`STABLE`, `RUNTIME`, `UNCERTAIN`, `UNSTABLE`)
 * supporting `stability_config.conf` glob matching, `kotlinx.collections.immutable` generic verification,
 * and workspace class signature inspection for [ComposeStabilityExtractor].
 */
internal object ComposeTypeStabilityAnalyzer {

    internal val PRIMITIVE_STABLE_TYPES = setOf(
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

    private val BODY_VAR_RE = Regex("""\bvar\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val CTOR_VAR_RE = Regex("""\bvar\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""")
    private val RAW_COLLECTION_PROP_RE = Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(List|Set|Map|MutableList|MutableSet|MutableMap)<""")
    private val WORKSPACE_COLLECTION_PROP_RE = Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(List|Set|Map)<""")
    private val CLASS_DECL_RE = Regex(
        """((?:@(?:Immutable|Stable)\b(?:[ \t\r\n]+|@[A-Za-z0-9_.]+(?:\([^)]*\))?|(?:public|internal|private|protected|open|abstract|final|inner|expect|actual|fun)\b)*)?)\b(data\s+class|value\s+class|enum\s+class|sealed\s+interface|sealed\s+class|class|interface)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^>]+>)?\s*(\([^)]*\))?"""
    )
    private val WHITESPACE_RE = Regex("""\s+""")

    fun analyzeClassesInSource(
        maskedText: String,
        stabilityPatterns: List<Regex> = emptyList(),
        findBalancedCloseBrace: (String, Int) -> Int
    ): Map<String, Pair<String, String>> {
        val result = mutableMapOf<String, Pair<String, String>>()
        for (declMatch in CLASS_DECL_RE.findAll(maskedText)) {
            val annotations = declMatch.groupValues[1]
            val kindKw = declMatch.groupValues[2].replace(WHITESPACE_RE, " ")
            val className = declMatch.groupValues[3]
            val ctorSlice = declMatch.groupValues[4]
            val hasStableAnnotation = annotations.contains("@Immutable") || annotations.contains("@Stable")
            val bodyVarName = findDepthOneBodyVar(maskedText, declMatch.range.last, findBalancedCloseBrace)
            result[className] = evaluateClassStability(
                className, kindKw, hasStableAnnotation, ctorSlice, bodyVarName, stabilityPatterns
            )
        }
        return result
    }

    fun buildWorkspaceClassStability(
        workspaceNodes: List<GraphNode>,
        precomputedClassStability: Map<String, Pair<String, String>>,
        stabilityPatterns: List<Regex>
    ): Map<String, Pair<String, String>> {
        val result = HashMap<String, Pair<String, String>>(precomputedClassStability.size + 64)
        result.putAll(precomputedClassStability)
        for (wn in workspaceNodes) {
            if (wn.kind !in setOf("class", "interface") || wn.name in result) continue
            val stability = evaluateWorkspaceClassSignature(wn, stabilityPatterns)
            if (stability != null) {
                result[wn.name] = stability
            }
        }
        return result
    }

    fun analyzeLocalClasses(
        maskedText: String,
        stabilityPatterns: List<Regex>,
        workspaceNodes: List<GraphNode>,
        precomputedClassStability: Map<String, Pair<String, String>>,
        findBalancedCloseBrace: (String, Int) -> Int
    ): Map<String, Pair<String, String>> {
        val localInFile = analyzeClassesInSource(maskedText, stabilityPatterns, findBalancedCloseBrace)
        if (workspaceNodes.isEmpty()) {
            if (localInFile.isEmpty()) return precomputedClassStability
            val merged = HashMap<String, Pair<String, String>>(precomputedClassStability.size + localInFile.size)
            merged.putAll(precomputedClassStability)
            merged.putAll(localInFile)
            return merged
        }
        val base = buildWorkspaceClassStability(workspaceNodes, precomputedClassStability, stabilityPatterns)
        if (localInFile.isEmpty()) return base
        val merged = HashMap<String, Pair<String, String>>(base.size + localInFile.size)
        merged.putAll(base)
        merged.putAll(localInFile)
        return merged
    }

    internal data class TypeStabilityContext(
        val pkg: String,
        val stabilityPatterns: List<Regex>,
        val localClassStability: Map<String, Pair<String, String>>,
        val workspaceNodes: List<GraphNode>,
        val splitTopLevelComma: (String) -> List<String>
    )

    fun inferTypeStability(
        typeSig: String,
        ctx: TypeStabilityContext
    ): Pair<String, String> {
        val clean = typeSig.trim().removeSuffix("?").trim()
        val fastHit = inferFastPrimitiveOrFunctionStability(clean)
        if (fastHit != null) return fastHit

        val outerType = clean.substringBefore('<').trim()
        val shortOuter = outerType.substringAfterLast('.')
        val configPattern = matchStabilityConfig(outerType, shortOuter, ctx.pkg, ctx.stabilityPatterns)
        if (configPattern != null) {
            return "STABLE" to "STABILITY_CONFIG_MATCH:$configPattern"
        }

        val genericArgs = extractTopLevelGenericArgs(clean, ctx.splitTopLevelComma)
        val immutableHit = inferImmutableCollectionStability(outerType, genericArgs, ctx)
        return immutableHit ?: inferFallbackTypeStability(outerType, shortOuter, ctx.localClassStability)
    }

    private fun skipLineLeadingWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i++
        return i
    }

    private fun isTopLevelDeclStartAt(text: String, idx: Int): Boolean =
        text.startsWith("class ", idx) ||
            text.startsWith("interface ", idx) ||
            text.startsWith("fun ", idx) ||
            text.startsWith("@Composable", idx)

    private fun isClassBodyTermination(maskedText: String, k: Int): Boolean {
        if (maskedText[k] == ';') return true
        if (maskedText[k] != '\n' || k + 1 >= maskedText.length) return false
        val idx = skipLineLeadingWhitespace(maskedText, k + 1)
        return idx < maskedText.length && isTopLevelDeclStartAt(maskedText, idx)
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

    private fun extractDepthOneBodyText(
        maskedText: String,
        openBrace: Int,
        findBalancedCloseBrace: (String, Int) -> Int
    ): String? {
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

    private fun findDepthOneBodyVar(
        maskedText: String,
        fromOffset: Int,
        findBalancedCloseBrace: (String, Int) -> Int
    ): String? {
        val openBrace = findClassBodyOpenBrace(maskedText, fromOffset) ?: return null
        val bodyText = extractDepthOneBodyText(maskedText, openBrace, findBalancedCloseBrace) ?: return null
        return BODY_VAR_RE.find(bodyText)?.groupValues?.get(1)
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

        val ctorVarMatch = CTOR_VAR_RE.find(ctorSlice)
        val rawCollectionMatch = RAW_COLLECTION_PROP_RE.find(ctorSlice)
        val varPropName = ctorVarMatch?.groupValues?.get(1) ?: bodyVarName

        if (hasStableAnnotation && (varPropName != null || rawCollectionMatch != null)) {
            val offender = varPropName ?: rawCollectionMatch?.groupValues?.get(1) ?: "field"
            return "UNSTABLE" to "BROKEN_STABILITY_CONTRACT:$className.$offender"
        }
        if (varPropName != null) {
            return "UNSTABLE" to "MUTABLE_VAR_PROPERTY:$varPropName"
        }
        if (rawCollectionMatch != null) {
            return evaluateCollectionPropertyStability(
                rawCollectionMatch.groupValues[1], rawCollectionMatch.groupValues[2], stabilityPatterns
            )
        }
        if (hasStableAnnotation) return "STABLE" to "EXPLICIT_STABLE_ANNOTATION"
        return "STABLE" to "ALL_VAL_PROPERTIES_STABLE"
    }

    private fun evaluateWorkspaceVarProperty(wn: GraphNode, sig: String, hasStable: Boolean): Pair<String, String> {
        val prop = BODY_VAR_RE.find(sig)?.groupValues?.get(1) ?: "prop"
        if (hasStable) return "UNSTABLE" to "BROKEN_STABILITY_CONTRACT:${wn.name}.$prop"
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
        if (stabilityPatterns.any { it.matches("kotlin.collections.$colType") }) {
            return "STABLE" to "STABILITY_CONFIG_MATCH:kotlin.collections.$colType"
        }
        if (hasStable) return "UNSTABLE" to "BROKEN_STABILITY_CONTRACT:${wn.name}.$propName"
        return "UNSTABLE" to "UNSTABLE_COLLECTION_PROPERTY:$propName($colType)"
    }

    private fun evaluateWorkspaceClassSignature(
        wn: GraphNode,
        stabilityPatterns: List<Regex>
    ): Pair<String, String>? {
        val sig = wn.signature
        val hasStable = sig.contains("@Immutable") || sig.contains("@Stable")
        if (sig.contains("var ")) return evaluateWorkspaceVarProperty(wn, sig, hasStable)

        val rawColMatch = WORKSPACE_COLLECTION_PROP_RE.find(sig)
        if (rawColMatch != null) return evaluateWorkspaceCollectionProperty(wn, rawColMatch, hasStable, stabilityPatterns)
        if (hasStable) return "STABLE" to "EXPLICIT_STABLE_ANNOTATION"
        if (sig.contains("data class") && sig.contains('(') && sig.contains(')')) {
            return "STABLE" to "WORKSPACE_DATA_CLASS"
        }
        return null
    }

    private fun matchStabilityConfig(
        outerType: String,
        shortOuter: String,
        pkg: String,
        patterns: List<Regex>
    ): String? {
        val fqCandidates = listOfNotNull(
            outerType,
            if (!outerType.contains('.') && pkg.isNotEmpty()) "$pkg.$outerType" else null,
            if (shortOuter in setOf("List", "Set", "Map", "Collection")) "kotlin.collections.$shortOuter" else null
        )
        for (cand in fqCandidates) {
            val matchedRegex = patterns.firstOrNull { it.matches(cand) }
            if (matchedRegex != null) return matchedRegex.pattern
        }
        return null
    }

    private fun inferImmutableCollectionStability(
        outerType: String,
        genericArgs: List<String>,
        ctx: TypeStabilityContext
    ): Pair<String, String>? {
        val shortOuter = outerType.substringAfterLast('.')
        if (!IMMUTABLE_COLLECTION_PREFIXES.any { outerType == it || shortOuter == it }) return null
        for (arg in genericArgs) {
            val (argStab, argReason) = inferTypeStability(arg, ctx)
            if (argStab != "STABLE") return argStab to "GENERIC_ARG_$argReason"
        }
        return "STABLE" to "KOTLINX_IMMUTABLE_COLLECTION"
    }

    private fun inferCollectionOrMutableStability(outerType: String, shortOuter: String): Pair<String, String>? {
        if (outerType in STANDARD_COLLECTION_PREFIXES || shortOuter in STANDARD_COLLECTION_PREFIXES) {
            return "UNSTABLE" to "STANDARD_COLLECTION_INTERFACE:$shortOuter"
        }
        if (outerType in MUTABLE_TYPE_PREFIXES || shortOuter in MUTABLE_TYPE_PREFIXES) {
            return "UNSTABLE" to "MUTABLE_COLLECTION_OR_STREAM:$shortOuter"
        }
        return null
    }

    private fun inferFastPrimitiveOrFunctionStability(clean: String): Pair<String, String>? {
        if (clean.isEmpty()) return "STABLE" to "PRIMITIVE_OR_STRING"
        if (clean.contains("->")) return "STABLE" to "FUNCTION_TYPE"
        val shortOuter = clean.substringBefore('<').trim().substringAfterLast('.')
        if (shortOuter in PRIMITIVE_STABLE_TYPES) return "STABLE" to "PRIMITIVE_OR_STRING"
        return null
    }

    private fun extractTopLevelGenericArgs(
        clean: String,
        splitTopLevelComma: (String) -> List<String>
    ): List<String> {
        if (!clean.contains('<') || !clean.endsWith('>')) return emptyList()
        return splitTopLevelComma(clean.substringAfter('<').substringBeforeLast('>'))
    }

    private fun inferFallbackTypeStability(
        outerType: String,
        shortOuter: String,
        localClassStability: Map<String, Pair<String, String>>
    ): Pair<String, String> {
        inferCollectionOrMutableStability(outerType, shortOuter)?.let { return it }
        (localClassStability[shortOuter] ?: localClassStability[outerType])?.let { return it }
        if (shortOuter == "State") return "STABLE" to "COMPOSE_STATE_WRAPPER"
        return "RUNTIME" to "EXTERNAL_MODULE_TYPE:$outerType"
    }
}
