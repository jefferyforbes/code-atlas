package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.extract.ComposeStabilityExtractor
import atlas.model.ComposeAuditResult
import atlas.model.ComposeDoctorReport
import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.ComposeQueryResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.min
import kotlin.math.round

/**
 * Application intelligence engine (`semantic:application`) for Jetpack Compose & KMP
 * stability queries (`summary`, `cascade`, `blame`), 12-rule Gate 1 compliance auditing
 * (`<= 95.0/100` autonomous score cap + mandatory Remaining 5% Polish Delta),
 * Strong Skipping Reality Check correlation, and write-site verified Doctor remediation previews.
 */
class ComposeStabilityEngine(private val store: SqliteGraphStore) {

    private data class RuleMetadata(
        val bit: Int,
        val ruleId: String,
        val severity: String,
        val deduction: Double,
        val defaultMessage: (ComposeNodeRecord, List<ComposeParamRecord>) -> String,
        val remediation: (ComposeNodeRecord, List<ComposeParamRecord>) -> String
    )

    companion object {
        const val GATE1_CEILING: Double = 95.0
        const val PASS_THRESHOLD: Double = 80.0
        const val PER_RULE_CAP: Double = 25.0

        private val RULES = listOf(
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_STAB_001,
                ruleId = "COMPOSE-STAB-001",
                severity = "HIGH",
                deduction = 5.0,
                defaultMessage = { node, params ->
                    val unstableNames = params.filter { it.isUsedInBody && it.stability in setOf("UNSTABLE", "UNCERTAIN") }
                        .joinToString(", ") { "${it.paramName}: ${it.typeSignature}" }
                    "Restartable @Composable '${node.fqName}' is unskippable due to unstable parameter(s): [$unstableNames]."
                },
                remediation = { _, _ ->
                    "Convert mutable 'var' properties to 'val', replace List/Set/Map with ImmutableList/PersistentList, or register external types in stability_config.conf."
                }
            ),
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_STAB_002,
                ruleId = "COMPOSE-STAB-002",
                severity = "CRITICAL",
                deduction = 8.0,
                defaultMessage = { node, _ ->
                    "@Composable '${node.fqName}' references an @Immutable/@Stable class that violates the stability contract (declares 'var' or raw List/Set/Map)."
                },
                remediation = { _, _ ->
                    "Replace 'var' properties with 'val' and standard collections with kotlinx.collections.immutable.ImmutableList inside @Immutable/@Stable classes."
                }
            ),
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_STAB_003,
                ruleId = "COMPOSE-STAB-003",
                severity = "MEDIUM",
                deduction = 3.0,
                defaultMessage = { node, _ ->
                    "@Composable '${node.fqName}' forwards an unstable parameter downstream to child composables (Unstable Parameter Cascade)."
                },
                remediation = { _, _ ->
                    "Hoist only primitive/stable slices or stabilize the parent UI state model before forwarding across composable trees."
                }
            ),
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_STATE_001,
                ruleId = "COMPOSE-STATE-001",
                severity = "CRITICAL",
                deduction = 8.0,
                defaultMessage = { node, _ ->
                    "@Composable '${node.fqName}' invokes remember, rememberSaveable, LaunchedEffect, or DisposableEffect conditionally inside an if/when/loop block."
                },
                remediation = { _, _ ->
                    "Hoist remember/LaunchedEffect unconditionally to the top of the composable or extract a dedicated child @Composable guard wrapper."
                }
            ),
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_STATE_002,
                ruleId = "COMPOSE-STATE-002",
                severity = "HIGH",
                deduction = 5.0,
                defaultMessage = { node, _ ->
                    "@Composable '${node.fqName}' collects Flow via raw collectAsState() without lifecycle awareness or accepts MutableStateFlow directly."
                },
                remediation = { _, _ ->
                    "Use collectAsStateWithLifecycle() from androidx.lifecycle.compose and expose read-only StateFlow<UiState> or immutable state snapshots."
                }
            ),
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_STATE_003,
                ruleId = "COMPOSE-STATE-003",
                severity = "MEDIUM",
                deduction = 3.0,
                defaultMessage = { node, _ ->
                    "@Composable '${node.fqName}' allocates mutableStateOf, derivedStateOf, or MutableInteractionSource without wrapping in remember { ... }."
                },
                remediation = { _, _ ->
                    "Wrap state allocations in remember { ... } or rememberSaveable { ... } so state survives recomposition."
                }
            ),
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_API_001,
                ruleId = "COMPOSE-API-001",
                severity = "MEDIUM",
                deduction = 3.0,
                defaultMessage = { node, _ ->
                    "Public UI-emitting @Composable '${node.fqName}' lacks 'modifier: Modifier = Modifier' as its first optional parameter."
                },
                remediation = { _, _ ->
                    "Add 'modifier: Modifier = Modifier' as the first optional parameter and pass it to the root layout node."
                }
            ),
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_API_002,
                ruleId = "COMPOSE-API-002",
                severity = "MEDIUM",
                deduction = 3.0,
                defaultMessage = { node, params ->
                    val badCb = params.firstOrNull { it.paramName.startsWith("on") }?.paramName ?: "callback"
                    "@Composable '${node.fqName}' declares side-effecting callback parameter '$badCb' instead of pure action hoisting."
                },
                remediation = { _, _ ->
                    "Rename event callbacks to describe only the user action (e.g. 'onSave', 'onDone') rather than navigation/implementation side effects ('onSaveAndNavigate')."
                }
            ),
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_PERF_001,
                ruleId = "COMPOSE-PERF-001",
                severity = "HIGH",
                deduction = 5.0,
                defaultMessage = { node, _ ->
                    "@Composable '${node.fqName}' invokes LazyColumn/LazyRow items(...) without an explicit 'key = { ... }' lambda."
                },
                remediation = { _, _ ->
                    "Provide a stable unique key (e.g. items(items = list, key = { it.id })) to prevent full-list slot-table recomposition on item insertions/moves."
                }
            ),
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_PERF_002,
                ruleId = "COMPOSE-PERF-002",
                severity = "MEDIUM",
                deduction = 3.0,
                defaultMessage = { node, _ ->
                    "@Composable '${node.fqName}' reads high-frequency scroll/offset state directly in composition scope."
                },
                remediation = { _, _ ->
                    "Wrap threshold checks in remember { derivedStateOf { ... } } or defer reads to layout/draw phases via Modifier.offset { ... } / Modifier.graphicsLayer { ... }."
                }
            ),
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_I18N_001,
                ruleId = "COMPOSE-I18N-001",
                severity = "MEDIUM",
                deduction = 3.0,
                defaultMessage = { node, _ ->
                    "@Composable '${node.fqName}' passes a hardcoded string literal to Text(...) or contentDescription."
                },
                remediation = { _, _ ->
                    "Externalize UI strings to resources and resolve via stringResource(R.string.<id>) or stringResource(Res.string.<id>)."
                }
            ),
            RuleMetadata(
                bit = ComposeStabilityExtractor.BIT_ARCH_001,
                ruleId = "COMPOSE-ARCH-001",
                severity = "HIGH",
                deduction = 5.0,
                defaultMessage = { node, _ ->
                    "@Composable '${node.fqName}' injects Android Context into a ViewModel or is a *Screen composable without a companion @Preview in the file."
                },
                remediation = { _, _ ->
                    "Never pass Context/Application into ViewModels, and provide a stateless @Preview function for every *Screen composable."
                }
            )
        )
    }

    fun queryCompose(
        projectSlug: String,
        action: String,
        target: String? = null,
        maxDepth: Int = 5,
        strongSkipping: Boolean = true
    ): ComposeQueryResult {
        val allNodes = store.listComposeNodes(projectSlug)
        val paramsByNode = store.listComposeParamsForProject(projectSlug)
        val total = allNodes.size
        val skippableCount = allNodes.count { it.isSkippable }
        val skippablePct = if (total == 0) 100.0 else round1((skippableCount.toDouble() / total.toDouble()) * 100.0)
        val unstableParamTotal = allNodes.sumOf { it.unstableParamCount + it.runtimeParamCount }

        return when (action.lowercase()) {
            "cascade", "compose_cascade" -> {
                val rootNode = if (!target.isNullOrBlank()) {
                    store.getComposeNode(projectSlug, target)
                } else {
                    allNodes.firstOrNull { !it.isSkippable } ?: allNodes.firstOrNull()
                }
                val cascade = if (rootNode != null) {
                    store.queryComposeCascade(projectSlug, rootNode.nodeId, maxDepth)
                } else emptyList()
                ComposeQueryResult(
                    project = projectSlug,
                    action = "cascade",
                    strongSkipping = strongSkipping,
                    totalComposables = total,
                    skippableCount = skippableCount,
                    skippablePct = skippablePct,
                    unstableParamTotal = unstableParamTotal,
                    cascade = cascade
                )
            }
            "blame", "compose_blame" -> {
                val targetNode = if (!target.isNullOrBlank()) {
                    store.getComposeNode(projectSlug, target)
                } else {
                    allNodes.firstOrNull { !it.isSkippable } ?: allNodes.firstOrNull()
                }
                val blame = if (targetNode != null) {
                    store.queryComposeBlame(projectSlug, targetNode.nodeId)
                } else emptyList()
                ComposeQueryResult(
                    project = projectSlug,
                    action = "blame",
                    strongSkipping = strongSkipping,
                    totalComposables = total,
                    skippableCount = skippableCount,
                    skippablePct = skippablePct,
                    unstableParamTotal = unstableParamTotal,
                    blame = blame
                )
            }
            else -> {
                val filteredNodes = if (!target.isNullOrBlank()) {
                    allNodes.filter {
                        it.nodeId == target || it.fqName == target || it.fqName.endsWith(".$target") || it.filePath.contains(target)
                    }
                } else allNodes

                val summaries = filteredNodes.map { n ->
                    val unstableParams = (paramsByNode[n.nodeId] ?: emptyList())
                        .filter { it.stability in setOf("UNSTABLE", "RUNTIME", "UNCERTAIN") }
                    ComposeQueryResult.NodeSummary(
                        nodeId = n.nodeId,
                        fqname = n.fqName,
                        filePath = n.filePath,
                        line = n.startLine,
                        sourceSet = n.sourceSet,
                        isSkippable = n.isSkippable,
                        unstableParams = unstableParams,
                        provenance = n.provenance
                    )
                }
                ComposeQueryResult(
                    project = projectSlug,
                    action = "summary",
                    strongSkipping = strongSkipping,
                    totalComposables = total,
                    skippableCount = skippableCount,
                    skippablePct = skippablePct,
                    unstableParamTotal = unstableParamTotal,
                    nodes = summaries
                )
            }
        }
    }

    fun auditCompose(
        projectSlug: String,
        strongSkipping: Boolean = true
    ): ComposeAuditResult {
        val allNodes = store.listComposeNodes(projectSlug)
        val violatingNodes = store.listComposeNodes(projectSlug, onlyViolating = true)
        val paramsByNode = store.listComposeParamsForProject(projectSlug)
        val traceMetrics = store.listComposeTraceMetrics(projectSlug)

        val violations = mutableListOf<ComposeAuditResult.Violation>()
        val countsByRule = linkedMapOf<String, Int>()
        RULES.forEach { countsByRule[it.ruleId] = 0 }

        for (node in violatingNodes) {
            val nodeParams = paramsByNode[node.nodeId] ?: emptyList()
            for (rule in RULES) {
                if ((node.violationMask and rule.bit) != 0) {
                    // Under Strong Skipping mode, COMPOSE-STAB-001 deduction is halved (-2.5) if no runtime trace confirms hotspot,
                    // but still reported so agents don't rely on accidental reference equality.
                    val effDeduction = if (strongSkipping && rule.ruleId == "COMPOSE-STAB-001" && node.provenance == "STATIC_INFERRED") {
                        2.5
                    } else {
                        rule.deduction
                    }
                    countsByRule[rule.ruleId] = (countsByRule[rule.ruleId] ?: 0) + 1
                    violations.add(
                        ComposeAuditResult.Violation(
                            ruleId = rule.ruleId,
                            severity = rule.severity,
                            pointDeduction = effDeduction,
                            nodeId = node.nodeId,
                            filePath = node.filePath,
                            line = node.startLine,
                            message = rule.defaultMessage(node, nodeParams),
                            remediation = rule.remediation(node, nodeParams),
                            provenance = node.provenance
                        )
                    )
                }
            }
        }

        // Compute RawScore with per-rule 25.0 deduction cap, then clamp to Gate 1 <= 95.0 ceiling
        var totalDeduction = 0.0
        for (rule in RULES) {
            val ruleViolations = violations.filter { it.ruleId == rule.ruleId }
            val ruleSum = ruleViolations.sumOf { it.pointDeduction }
            totalDeduction += min(PER_RULE_CAP, ruleSum)
        }
        val rawUnclamped = (100.0 - totalDeduction).coerceIn(0.0, 100.0)
        val overallScore = round1(min(GATE1_CEILING, rawUnclamped))
        val status = if (overallScore >= PASS_THRESHOLD) "PASS" else "FAIL"

        // Evaluate Reality Check Lattice on traced nodes
        val realityItems = mutableListOf<ComposeAuditResult.RealityItem>()
        val json = Json { ignoreUnknownKeys = true }
        for (node in allNodes) {
            val trace = traceMetrics[node.nodeId] ?: continue
            val recomp = trace.first
            val skip = trace.second
            val totalRuns = (recomp + skip).coerceAtLeast(1)
            val recomposeRatio = recomp.toDouble() / totalRuns.toDouble()
            val skipRatio = skip.toDouble() / totalRuns.toDouble()
            val hasStaticInstability = !node.isSkippable || node.unstableParamCount > 0 || node.runtimeParamCount > 0

            val classification = when {
                hasStaticInstability && recomp >= 10 && recomposeRatio >= 0.50 -> "CONFIRMED"
                hasStaticInstability && (recomp <= 2 || skipRatio > 0.80) -> "FALSE_ALARM"
                !hasStaticInstability && recomp >= 10 && skip == 0 -> "SILENT_WASTE"
                else -> "HEALTHY"
            }

            val dirtyList = try {
                json.parseToJsonElement(trace.third).jsonArray.map { it.jsonPrimitive.content }
            } catch (_: Throwable) {
                emptyList()
            }

            realityItems.add(
                ComposeAuditResult.RealityItem(
                    nodeId = node.nodeId,
                    fqname = node.fqName,
                    classification = classification,
                    staticSkippable = node.isSkippable,
                    unstableParamCount = node.unstableParamCount,
                    recompositionCount = recomp,
                    skipCount = skip,
                    dirtyParams = dirtyList
                )
            )
        }

        // Mandatory Remaining 5% Polish Delta when score >= 90.0 (or always populated for Gate 1 guidance)
        val polishDelta = listOf(
            ComposeAuditResult.PolishItem(
                category = "Runtime Recomposition Verification",
                target = allNodes.firstOrNull { it.provenance != "RUNTIME_TRACED" }?.fqName ?: "Top-level Screen Composables",
                recommendation = "Capture @TraceRecomposition telemetry during interactive scroll/navigation journeys to upgrade STATIC_INFERRED nodes to RUNTIME_TRACED."
            ),
            ComposeAuditResult.PolishItem(
                category = "Cross-Module Stability Configuration",
                target = "stability_config.conf",
                recommendation = "Promote external RUNTIME domain/DTO types (java.time.*, kotlinx.datetime.*) into stability_config.conf to eliminate runtime stability bitmask checks."
            ),
            ComposeAuditResult.PolishItem(
                category = "Phase-Skipping GraphicsLayer Modifiers",
                target = "Animated & Scroll-Driven Composables",
                recommendation = "Use lambda-based Modifier.graphicsLayer { alpha = ...; translationY = ... } and Modifier.offset { ... } to bypass composition and layout phases."
            ),
            ComposeAuditResult.PolishItem(
                category = "Multi-Configuration Preview Matrix",
                target = "@Preview Annotations",
                recommendation = "Add @PreviewLightDark and @PreviewFontScale annotations to verify optical alignment, contrast, and text reflow under 200% font scaling."
            )
        )

        return ComposeAuditResult(
            project = projectSlug,
            status = status,
            overallScore = overallScore,
            rawUnclampedScore = round1(rawUnclamped),
            gate1Target = GATE1_CEILING,
            passThreshold = PASS_THRESHOLD,
            totalComposables = allNodes.size,
            skippableComposables = allNodes.count { it.isSkippable },
            strongSkippingMode = strongSkipping,
            violationCounts = countsByRule.filterValues { it > 0 },
            violations = violations,
            realityCheck = realityItems,
            remainingPolishDelta = polishDelta
        )
    }

    fun runDoctor(
        projectSlug: String,
        target: String? = null,
        limit: Int = 10
    ): ComposeDoctorReport {
        val violatingNodes = store.listComposeNodes(projectSlug, onlyViolating = true)
            .let { list ->
                if (!target.isNullOrBlank()) {
                    list.filter { it.nodeId == target || it.fqName == target || it.fqName.endsWith(".$target") || it.filePath.contains(target) }
                } else list
            }
        val paramsByNode = store.listComposeParamsForProject(projectSlug)
        val traceMetrics = store.listComposeTraceMetrics(projectSlug)
        val hasAnyTrace = traceMetrics.isNotEmpty()
        val overallMode = if (hasAnyTrace) "MEASURED" else "ESTIMATED"

        data class RawCandidate(
            val priorityScore: Double,
            val scoringMode: String,
            val ruleId: String,
            val fixStrategy: String,
            val writeSiteSafe: Boolean,
            val targetNodeId: String,
            val filePath: String,
            val line: Int,
            val downstreamCascadeCount: Int,
            val rationale: String,
            val beforeSnippet: String,
            val afterSnippet: String
        )

        val candidates = mutableListOf<RawCandidate>()

        for (node in violatingNodes) {
            val nodeParams = paramsByNode[node.nodeId] ?: emptyList()
            val cascadeCount = store.queryComposeCascade(projectSlug, node.nodeId, maxDepth = 4).size
            val trace = traceMetrics[node.nodeId]
            val nodeMode = if (trace != null) "MEASURED" else "ESTIMATED"

            fun computePriority(severityWeight: Double): Double {
                return if (trace != null) {
                    val recomp = trace.first.coerceAtLeast(1)
                    round1(recomp.toDouble() * (1.0 + cascadeCount.toDouble()) * severityWeight)
                } else {
                    val instabilityFactor = (1 + node.unstableParamCount + node.runtimeParamCount).toDouble()
                    round1(instabilityFactor * (1.0 + cascadeCount.toDouble()) * severityWeight)
                }
            }

            // 1. Stability parameter prescriptions (COMPOSE-STAB-001 / COMPOSE-STAB-002)
            if ((node.violationMask and (ComposeStabilityExtractor.BIT_STAB_001 or ComposeStabilityExtractor.BIT_STAB_002)) != 0) {
                for (p in nodeParams.filter { it.stability in setOf("UNSTABLE", "RUNTIME", "UNCERTAIN") }) {
                    val reason = p.stabilityReason
                    when {
                        reason.startsWith("MUTABLE_VAR_PROPERTY:") || reason.startsWith("BROKEN_STABILITY_CONTRACT:") -> {
                            val propName = reason.substringAfterLast(':').substringAfterLast('.')
                            val mutationCount = store.countPropertyMutations(projectSlug, propName)
                            val writeSiteSafe = mutationCount == 0
                            val className = p.typeSignature.substringBefore('<').substringAfterLast('.')
                            val (before, after, rationale) = if (writeSiteSafe) {
                                Triple(
                                    "data class $className(\n    var $propName: String\n)",
                                    "@Immutable\ndata class $className(\n    val $propName: String\n)",
                                    "Property '$propName' on '$className' has 0 write-site mutations (MUTATES_PROPERTY = 0); safe to convert 'var' -> 'val'."
                                )
                            } else {
                                Triple(
                                    "state.$propName = updatedValue",
                                    "state = state.copy($propName = updatedValue)",
                                    "Property '$propName' on '$className' has $mutationCount active write-site mutation(s) in the call graph; refactor mutations to immutable .copy(...) before converting 'var' -> 'val'."
                                )
                            }
                            val classNode = store.listNodes(projectSlug).firstOrNull {
                                (it.name == className || it.qualifiedName.endsWith(".$className")) &&
                                    it.kind in setOf("class", "data class", "value class", "interface")
                            }
                            val targetFilePath = classNode?.let {
                                val f = it.fileId
                                if (f.contains(':')) f.substringAfter(':') else f
                            } ?: node.filePath
                            val targetLine = classNode?.startLine ?: node.startLine

                            candidates.add(
                                RawCandidate(
                                    priorityScore = computePriority(2.0),
                                    scoringMode = nodeMode,
                                    ruleId = if (reason.startsWith("BROKEN")) "COMPOSE-STAB-002" else "COMPOSE-STAB-001",
                                    fixStrategy = "VAL_CONVERSION",
                                    writeSiteSafe = writeSiteSafe,
                                    targetNodeId = classNode?.nodeId ?: node.nodeId,
                                    filePath = targetFilePath,
                                    line = targetLine,
                                    downstreamCascadeCount = cascadeCount,
                                    rationale = rationale,
                                    beforeSnippet = before,
                                    afterSnippet = after
                                )
                            )
                        }
                        reason.startsWith("STANDARD_COLLECTION_INTERFACE:") || reason.startsWith("UNSTABLE_COLLECTION_PROPERTY:") -> {
                            val elemType = p.typeSignature.substringAfter('<', "T").substringBeforeLast('>')
                            candidates.add(
                                RawCandidate(
                                    priorityScore = computePriority(1.6),
                                    scoringMode = nodeMode,
                                    ruleId = "COMPOSE-STAB-001",
                                    fixStrategy = "PERSISTENT_LIST",
                                    writeSiteSafe = true,
                                    targetNodeId = node.nodeId,
                                    filePath = node.filePath,
                                    line = node.startLine,
                                    downstreamCascadeCount = cascadeCount,
                                    rationale = "Parameter '${p.paramName}: ${p.typeSignature}' uses standard collection interface which Compose treats as UNSTABLE because MutableList can back it.",
                                    beforeSnippet = "${p.paramName}: ${p.typeSignature}",
                                    afterSnippet = "${p.paramName}: ImmutableList<$elemType>"
                                )
                            )
                        }
                        reason.startsWith("EXTERNAL_MODULE_TYPE:") -> {
                            val extType = reason.substringAfter(':')
                            candidates.add(
                                RawCandidate(
                                    priorityScore = computePriority(1.2),
                                    scoringMode = nodeMode,
                                    ruleId = "COMPOSE-STAB-001",
                                    fixStrategy = "STABILITY_CONFIG",
                                    writeSiteSafe = true,
                                    targetNodeId = node.nodeId,
                                    filePath = node.filePath,
                                    line = node.startLine,
                                    downstreamCascadeCount = cascadeCount,
                                    rationale = "External type '$extType' is evaluated at RUNTIME; register it in stability_config.conf if immutable.",
                                    beforeSnippet = "# stability_config.conf (missing entry)",
                                    afterSnippet = "# stability_config.conf\n$extType"
                                )
                            )
                        }
                        else -> {
                            candidates.add(
                                RawCandidate(
                                    priorityScore = computePriority(1.4),
                                    scoringMode = nodeMode,
                                    ruleId = "COMPOSE-STAB-001",
                                    fixStrategy = "IMMUTABLE_ANNOTATION",
                                    writeSiteSafe = true,
                                    targetNodeId = node.nodeId,
                                    filePath = node.filePath,
                                    line = node.startLine,
                                    downstreamCascadeCount = cascadeCount,
                                    rationale = "Annotate '${p.typeSignature}' with @Immutable or @Stable after verifying all properties are read-only.",
                                    beforeSnippet = "interface ${p.typeSignature}",
                                    afterSnippet = "@Stable\ninterface ${p.typeSignature}"
                                )
                            )
                        }
                    }
                }
            }

            // 2. Conditional effect / remember (COMPOSE-STATE-001)
            if ((node.violationMask and ComposeStabilityExtractor.BIT_STATE_001) != 0) {
                candidates.add(
                    RawCandidate(
                        priorityScore = computePriority(2.2),
                        scoringMode = nodeMode,
                        ruleId = "COMPOSE-STATE-001",
                        fixStrategy = "HOIST_EFFECT",
                        writeSiteSafe = true,
                        targetNodeId = node.nodeId,
                        filePath = node.filePath,
                        line = node.startLine,
                        downstreamCascadeCount = cascadeCount,
                        rationale = "Conditional remember/LaunchedEffect corrupts the Compose slot table when branch conditions flip.",
                        beforeSnippet = "if (isVisible) {\n    val state = remember { mutableStateOf(false) }\n}",
                        afterSnippet = "val state = remember { mutableStateOf(false) }\nif (isVisible) {\n    /* use state */\n}"
                    )
                )
            }

            // 3. LazyColumn missing key (COMPOSE-PERF-001)
            if ((node.violationMask and ComposeStabilityExtractor.BIT_PERF_001) != 0) {
                candidates.add(
                    RawCandidate(
                        priorityScore = computePriority(1.5),
                        scoringMode = nodeMode,
                        ruleId = "COMPOSE-PERF-001",
                        fixStrategy = "KEY_INSERTION",
                        writeSiteSafe = true,
                        targetNodeId = node.nodeId,
                        filePath = node.filePath,
                        line = node.startLine,
                        downstreamCascadeCount = cascadeCount,
                        rationale = "Providing a stable key allows LazyColumn/LazyRow to skip unchanged item compositions during list mutations.",
                        beforeSnippet = "items(state.items) { item ->",
                        afterSnippet = "items(items = state.items, key = { it.id }) { item ->"
                    )
                )
            }
        }

        val sorted = candidates
            .sortedWith(compareByDescending<RawCandidate> { it.priorityScore }.thenBy { it.filePath }.thenBy { it.line })
            .take(limit.coerceAtLeast(1))
            .mapIndexed { index, c ->
                ComposeDoctorReport.Prescription(
                    rank = index + 1,
                    priorityScore = c.priorityScore,
                    scoringMode = c.scoringMode,
                    ruleId = c.ruleId,
                    fixStrategy = c.fixStrategy,
                    writeSiteSafe = c.writeSiteSafe,
                    targetNodeId = c.targetNodeId,
                    filePath = c.filePath,
                    line = c.line,
                    downstreamCascadeCount = c.downstreamCascadeCount,
                    rationale = c.rationale,
                    beforeSnippet = c.beforeSnippet,
                    afterSnippet = c.afterSnippet
                )
            }

        return ComposeDoctorReport(
            project = projectSlug,
            scoringMode = overallMode,
            totalPrescriptions = sorted.size,
            prescriptions = sorted
        )
    }

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0
}
