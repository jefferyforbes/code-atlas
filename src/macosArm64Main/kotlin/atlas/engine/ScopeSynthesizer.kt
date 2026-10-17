package atlas.engine

import atlas.bridge.VaultPlanBridge
import atlas.db.SqliteGraphStore
import atlas.model.Compose3DScope
import atlas.model.GraphNode
import atlas.model.ScopeRecommendation

/**
 * 3D Concurrency Scope Synthesizer for /architect and /plan task decomposition.
 */
class ScopeSynthesizer(private val store: SqliteGraphStore) {

    fun synthesizeScope(
        projectId: String,
        seedTargets: List<String>,
        depth: Int = 2
    ): ScopeRecommendation {
        val resolvedNodes = mutableListOf<GraphNode>()
        for (target in seedTargets) {
            val cleanTarget = target.trim().trim('\'', '"').removePrefix("./").replace('\\', '/')
            val matchedNodes = when {
                cleanTarget.contains('*') || cleanTarget.contains('?') -> {
                    val globRe = VaultPlanBridge.globToRegex(cleanTarget)
                    val allFiles = store.listIndexedFiles(projectId)
                    val matchingFiles = allFiles.filter { rel -> globRe.matches(rel) }
                    val fileIds = matchingFiles.map { "$projectId:$it" }
                    store.getNodesForFileIds(projectId, fileIds)
                }
                cleanTarget.endsWith('/') -> {
                    val allFiles = store.listIndexedFiles(projectId)
                    val matchingFiles = allFiles.filter { rel -> rel.startsWith(cleanTarget) }
                    val fileIds = matchingFiles.map { "$projectId:$it" }
                    store.getNodesForFileIds(projectId, fileIds)
                }
                cleanTarget.contains('.') -> {
                    store.getNodesForFile(projectId, cleanTarget)
                }
                else -> emptyList()
            }

            if (matchedNodes.isNotEmpty()) {
                resolvedNodes.addAll(matchedNodes)
            } else {
                val node = store.getNode(projectId, target)
                if (node != null) {
                    resolvedNodes.add(node)
                } else {
                    val matches = store.searchNodes(projectId, target, limit = 3)
                    resolvedNodes.addAll(matches)
                }
            }
        }

        val filesSet = mutableSetOf<String>()
        val modulesSet = mutableSetOf<String>()
        val archSet = mutableSetOf<String>()
        val impactedNodeIds = mutableSetOf<String>()

        for (seed in resolvedNodes) {
            val impact = store.getImpactRadius(projectId, seed.nodeId, depth = depth)
            for (fPath in impact.impactedFiles) {
                if (!fPath.startsWith("plans/")) {
                    filesSet.add(fPath)
                }
            }
            for (mod in impact.impactedModules) {
                if (mod.isNotEmpty() && mod != "plans") {
                    modulesSet.add(mod)
                }
            }
            for (layer in impact.impactedLayers) {
                if (!layer.endsWith(":infrastructure")) {
                    archSet.add(layer)
                }
            }
            for (n in impact.impactedNodes) {
                impactedNodeIds.add(n.nodeId)
            }
        }

        val godNodes = store.getGodNodes(projectId, topN = 10)
        val godTouched = godNodes.filter { it.nodeId in impactedNodeIds }.map { it.name }

        val testFixtures = filesSet.filter { fPath ->
            fPath.contains("fixtures/") || fPath.lowercase().contains("fake_") || fPath.lowercase().contains("commontest")
        }.sorted()

        val terminalPlanStatuses = setOf("(merged)", "(completed)", "(done)", "(landed)", "(closed)")
        val collidingPlans = mutableListOf<String>()
        val planEdges = store.listEdges(projectId, edgeTypes = listOf("PLAN_SCOPES_FILE"))
        for (edge in planEdges) {
            if (edge.targetNodeId in impactedNodeIds) {
                val planNode = store.getNode(projectId, edge.sourceNodeId)
                if (planNode == null || terminalPlanStatuses.any { planNode.signature.contains(it) }) {
                    continue
                }
                val planName = planNode.name
                if (planName !in collidingPlans) {
                    collidingPlans.add(planName)
                }
            }
        }

        return ScopeRecommendation(
            projectId = projectId,
            seedTargets = seedTargets,
            files = filesSet.sorted(),
            modules = modulesSet.sorted(),
            architecture = archSet.sorted(),
            godNodesTouched = godTouched,
            requiredTestFixtures = testFixtures,
            collidingPlans = collidingPlans.sorted()
        )
    }

    /**
     * Synthesizes a Compose-aware 3D Concurrency Boundary for `/teamwork` and `/plan`,
     * identifying shared `@Stable`/`@Immutable` UI state models, `CompositionLocal` providers,
     * `ComposeTheme` token providers, and downstream `*Screen` composables that would collide
     * if modified concurrently by parallel UI workers.
     */
    fun synthesizeComposeScope(
        projectId: String,
        seedTargets: List<String>
    ): Compose3DScope {
        val baseScope = synthesizeScope(projectId, seedTargets, depth = 3)
        val allComposeNodes = store.listComposeNodes(projectId)
        val paramsByNode = store.listComposeParamsForProject(projectId)
        val allGraphNodes = store.listNodes(projectId)
        val nodesById = allGraphNodes.associateBy { it.nodeId }

        val targetFiles = (baseScope.files + seedTargets.filter { it.endsWith(".kt") }).distinct().sorted()
        val scopedComposeNodes = allComposeNodes.filter { cn ->
            targetFiles.any { tf -> cn.filePath == tf || cn.filePath.endsWith(tf) || cn.fqName.endsWith(".$tf") } ||
                seedTargets.any { st -> cn.nodeId == st || cn.fqName == st || cn.fqName.endsWith(".$st") }
        }

        val sharedStableModels = mutableSetOf<String>()
        val compositionLocals = mutableSetOf<String>()
        val themeProviders = mutableSetOf<String>()
        val downstreamScreens = mutableSetOf<String>()
        val collisionRisks = mutableListOf<Compose3DScope.CollisionRisk>()

        // 1. Identify shared UI state models used across multiple composables/files
        val paramTypeToFiles = mutableMapOf<String, MutableSet<String>>()
        for (cn in allComposeNodes) {
            for (p in paramsByNode[cn.nodeId] ?: emptyList()) {
                val baseType = p.typeSignature.substringBefore('<').substringAfterLast('.').removeSuffix("?").trim()
                if (baseType.endsWith("State") || baseType.endsWith("UiModel") || baseType.endsWith("Item") || p.stabilityReason == "EXPLICIT_STABLE_ANNOTATION") {
                    paramTypeToFiles.getOrPut(baseType) { mutableSetOf() }.add(cn.filePath)
                }
            }
        }

        for (cn in scopedComposeNodes) {
            if (cn.fqName.endsWith("Screen")) {
                downstreamScreens.add(cn.fqName)
            }
            for (hop in store.queryComposeCascade(projectId, cn.nodeId, maxDepth = 4)) {
                if (hop.calleeFqName.endsWith("Screen") || hop.calleeFqName.isNotEmpty()) {
                    downstreamScreens.add(hop.calleeFqName)
                }
            }
            for (p in paramsByNode[cn.nodeId] ?: emptyList()) {
                val baseType = p.typeSignature.substringBefore('<').substringAfterLast('.').removeSuffix("?").trim()
                val consumers = paramTypeToFiles[baseType] ?: emptySet()
                if (consumers.size > 1) {
                    sharedStableModels.add(baseType)
                    val outsideFiles = consumers.filter { it !in targetFiles }.sorted()
                    if (outsideFiles.isNotEmpty()) {
                        collisionRisks.add(
                            Compose3DScope.CollisionRisk(
                                sharedSymbolId = baseType,
                                riskKind = "SHARED_STABLE_MODEL",
                                collidingFiles = (consumers).sorted(),
                                remediation = "Serialize edits to shared UI model '$baseType' in a prerequisite foundation PR before dispatching parallel screen workers."
                            )
                        )
                    }
                }
            }
        }

        // 2. Inspect CompositionLocal and Theme edges
        val composeEdges = store.listEdges(
            projectId,
            edgeTypes = listOf("PROVIDES_COMPOSITION_LOCAL", "CONSUMES_COMPOSITION_LOCAL", "EXPECT_ACTUAL")
        )
        val localToFiles = mutableMapOf<String, MutableSet<String>>()
        for (edge in composeEdges) {
            val srcFile = nodesById[edge.sourceNodeId]?.fileId?.substringAfter(':')
                ?: edge.sourceNodeId.substringAfter(':').substringBefore('#')
            val tgtName = nodesById[edge.targetNodeId]?.name
                ?: edge.targetNodeId.substringAfterLast('#').removePrefix("sym:")
            if (edge.edgeType in setOf("PROVIDES_COMPOSITION_LOCAL", "CONSUMES_COMPOSITION_LOCAL")) {
                compositionLocals.add(tgtName)
                localToFiles.getOrPut(tgtName) { mutableSetOf() }.add(srcFile)
                if (tgtName.contains("Theme") || tgtName.contains("Color") || tgtName.contains("Typography") || tgtName.contains("Spacing")) {
                    themeProviders.add(tgtName)
                }
            }
        }

        for ((localSym, files) in localToFiles) {
            if (files.size > 1 && files.any { it in targetFiles }) {
                val isTheme = localSym in themeProviders
                collisionRisks.add(
                    Compose3DScope.CollisionRisk(
                        sharedSymbolId = localSym,
                        riskKind = if (isTheme) "THEME_TOKEN_DRIFT" else "COMPOSITION_LOCAL_PROVIDER",
                        collidingFiles = files.sorted(),
                        remediation = "Lock CompositionLocal '$localSym' provider contract before parallel worker execution."
                    )
                )
            }
        }

        val dedupedRisks = collisionRisks.distinctBy { "${it.sharedSymbolId}:${it.riskKind}" }
        return Compose3DScope(
            project = projectId,
            targetFiles = targetFiles,
            modules = baseScope.modules,
            architectureLayers = baseScope.architecture.ifEmpty { listOf("pragmatic:presentation") },
            sharedStableModels = sharedStableModels.sorted(),
            compositionLocals = compositionLocals.sorted(),
            themeTokenProviders = themeProviders.sorted(),
            downstreamScreens = downstreamScreens.sorted(),
            collisionRisks = dedupedRisks,
            safeForParallelDispatch = dedupedRisks.isEmpty()
        )
    }
}

