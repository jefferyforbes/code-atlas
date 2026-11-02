package atlas.engine

import atlas.bridge.VaultPlanBridge
import atlas.db.SqliteGraphStore
import atlas.extract.UrlTargetResolver
import atlas.model.Compose3DScope
import atlas.model.ComposeNodeRecord
import atlas.model.ComposeParamRecord
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.model.ImpactRadiusResult
import atlas.model.ScopeRecommendation

/**
 * 3D Concurrency Scope Synthesizer for /architect and /plan task decomposition.
 */
class ScopeSynthesizer(private val store: SqliteGraphStore) {

    private data class ImpactSets(
        val files: Set<String>,
        val modules: Set<String>,
        val architecture: Set<String>,
        val impactedNodeIds: Set<String>
    )

    fun synthesizeScope(
        projectId: String,
        seedTargets: List<String>,
        depth: Int = 2
    ): ScopeRecommendation {
        val resolvedNodes = resolveSeedNodes(projectId, seedTargets)
        val impact = collectImpactSets(projectId, resolvedNodes, depth)
        val godTouched = findGodNodesTouched(projectId, impact.impactedNodeIds)
        val testFixtures = findTestFixtures(impact.files)
        val collidingPlans = findCollidingPlans(projectId, impact.impactedNodeIds)

        return ScopeRecommendation(
            projectId = projectId,
            seedTargets = seedTargets,
            files = impact.files.sorted(),
            modules = impact.modules.sorted(),
            architecture = impact.architecture.sorted(),
            godNodesTouched = godTouched,
            requiredTestFixtures = testFixtures,
            collidingPlans = collidingPlans
        )
    }

    private fun resolveSeedNodes(projectId: String, seedTargets: List<String>): List<GraphNode> {
        val resolved = mutableListOf<GraphNode>()
        for (target in seedTargets) {
            resolved.addAll(resolveTargetNodes(projectId, target))
        }
        return resolved
    }

    private fun resolveTargetNodes(projectId: String, target: String): List<GraphNode> {
        val cleanTarget = target.trim().trim('\'', '"').removePrefix("./").replace('\\', '/')
        val matchedNodes = matchTargetPattern(projectId, cleanTarget)
        if (matchedNodes.isNotEmpty()) {
            return matchedNodes
        }
        val node = store.getNode(projectId, target)
        if (node != null) {
            return listOf(node)
        }
        return store.searchNodes(projectId, target, limit = 3)
    }

    private fun matchTargetPattern(projectId: String, cleanTarget: String): List<GraphNode> {
        if (UrlTargetResolver.isUrlOrDeepLink(cleanTarget)) {
            return resolveUrlTarget(projectId, cleanTarget)
        }
        if (cleanTarget.contains('*')) {
            return resolveGlobTarget(projectId, cleanTarget)
        }
        if (cleanTarget.contains('?')) {
            return resolveGlobTarget(projectId, cleanTarget)
        }
        if (cleanTarget.endsWith('/')) {
            return resolveDirTarget(projectId, cleanTarget)
        }
        if (cleanTarget.contains('.') && !cleanTarget.contains('#')) {
            return store.getNodesForFile(projectId, cleanTarget)
        }
        return emptyList()
    }

    private fun resolveUrlTarget(projectId: String, cleanTarget: String): List<GraphNode> {
        val codeLink = UrlTargetResolver.parseCodeLocationUrl(cleanTarget)
        if (codeLink == null) {
            return store.resolveNodesByUrlOrRoute(projectId, cleanTarget)
        }
        if (codeLink.startLine != null) {
            return listOfNotNull(store.getNode(projectId, cleanTarget))
        }
        if (codeLink.symbolFragment != null) {
            return listOfNotNull(store.getNode(projectId, cleanTarget))
        }
        return store.getNodesForFile(projectId, codeLink.filePathSuffix)
    }

    private fun resolveGlobTarget(projectId: String, cleanTarget: String): List<GraphNode> {
        val globRe = VaultPlanBridge.globToRegex(cleanTarget)
        val allFiles = store.listIndexedFiles(projectId)
        val matchingFiles = allFiles.filter { rel -> globRe.matches(rel) }
        val fileIds = matchingFiles.map { "$projectId:$it" }
        return store.getNodesForFileIds(projectId, fileIds)
    }

    private fun resolveDirTarget(projectId: String, cleanTarget: String): List<GraphNode> {
        val allFiles = store.listIndexedFiles(projectId)
        val matchingFiles = allFiles.filter { rel -> rel.startsWith(cleanTarget) }
        val fileIds = matchingFiles.map { "$projectId:$it" }
        return store.getNodesForFileIds(projectId, fileIds)
    }

    private fun collectImpactSets(
        projectId: String,
        resolvedNodes: List<GraphNode>,
        depth: Int
    ): ImpactSets {
        val filesSet = mutableSetOf<String>()
        val modulesSet = mutableSetOf<String>()
        val archSet = mutableSetOf<String>()
        val impactedNodeIds = mutableSetOf<String>()

        for (seed in resolvedNodes) {
            val impact = store.getImpactRadius(projectId, seed.nodeId, depth = depth)
            accumulateImpact(impact, filesSet, modulesSet, archSet, impactedNodeIds)
        }
        return ImpactSets(filesSet, modulesSet, archSet, impactedNodeIds)
    }

    private fun accumulateImpact(
        impact: ImpactRadiusResult,
        filesSet: MutableSet<String>,
        modulesSet: MutableSet<String>,
        archSet: MutableSet<String>,
        impactedNodeIds: MutableSet<String>
    ) {
        accumulateFilesAndModules(impact, filesSet, modulesSet)
        accumulateLayersAndNodes(impact, archSet, impactedNodeIds)
    }

    private fun accumulateFilesAndModules(
        impact: ImpactRadiusResult,
        filesSet: MutableSet<String>,
        modulesSet: MutableSet<String>
    ) {
        for (fPath in impact.impactedFiles) {
            if (!fPath.startsWith("plans/")) {
                filesSet.add(fPath)
            }
        }
        for (mod in impact.impactedModules) {
            if (mod.isEmpty()) continue
            if (mod == "plans") continue
            modulesSet.add(mod)
        }
    }

    private fun accumulateLayersAndNodes(
        impact: ImpactRadiusResult,
        archSet: MutableSet<String>,
        impactedNodeIds: MutableSet<String>
    ) {
        for (layer in impact.impactedLayers) {
            if (!layer.endsWith(":infrastructure")) {
                archSet.add(layer)
            }
        }
        for (n in impact.impactedNodes) {
            impactedNodeIds.add(n.nodeId)
        }
    }

    private fun findGodNodesTouched(projectId: String, impactedNodeIds: Set<String>): List<String> {
        val godNodes = store.getGodNodes(projectId, topN = 10)
        return godNodes.filter { it.nodeId in impactedNodeIds }.map { it.name }
    }

    private fun findTestFixtures(filesSet: Set<String>): List<String> {
        return filesSet.filter { isTestFixturePath(it) }.sorted()
    }

    private fun isTestFixturePath(fPath: String): Boolean {
        if (fPath.contains("fixtures/")) return true
        val lower = fPath.lowercase()
        if (lower.contains("fake_")) return true
        if (lower.contains("commontest")) return true
        return false
    }

    private fun findCollidingPlans(projectId: String, impactedNodeIds: Set<String>): List<String> {
        val collidingPlans = mutableListOf<String>()
        val planEdges = store.listEdges(projectId, edgeTypes = listOf("PLAN_SCOPES_FILE"))
        for (edge in planEdges) {
            if (edge.targetNodeId !in impactedNodeIds) continue
            val planNode = store.getNode(projectId, edge.sourceNodeId)
            if (planNode == null) continue
            if (ArchitectureAuditor.TERMINAL_PLAN_STATUSES.any { planNode.signature.contains(it) }) continue
            val planName = planNode.name
            if (planName !in collidingPlans) {
                collidingPlans.add(planName)
            }
        }
        return collidingPlans.sorted()
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
        val nodesById = store.listNodes(projectId).associateBy { it.nodeId }

        val targetFiles = (baseScope.files + seedTargets.filter { it.endsWith(".kt") }).distinct().sorted()
        val scopedComposeNodes = allComposeNodes.filter { cn ->
            isScopedComposeNode(cn, targetFiles, seedTargets)
        }

        val paramTypeToFiles = indexParamTypesToFiles(allComposeNodes, paramsByNode)
        val downstreamScreens = mutableSetOf<String>()
        val sharedStableModels = mutableSetOf<String>()
        val collisionRisks = mutableListOf<Compose3DScope.CollisionRisk>()

        for (cn in scopedComposeNodes) {
            collectDownstreamScreens(projectId, cn, downstreamScreens)
            inspectModelCollisions(
                cn = cn,
                params = paramsByNode[cn.nodeId] ?: emptyList(),
                paramTypeToFiles = paramTypeToFiles,
                targetFiles = targetFiles,
                sharedStableModels = sharedStableModels,
                collisionRisks = collisionRisks
            )
        }

        val themeProviders = mutableSetOf<String>()
        val compositionLocals = mutableSetOf<String>()
        inspectCompositionLocalEdges(
            projectId = projectId,
            nodesById = nodesById,
            targetFiles = targetFiles,
            compositionLocals = compositionLocals,
            themeProviders = themeProviders,
            collisionRisks = collisionRisks
        )

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

    private fun isScopedComposeNode(
        node: ComposeNodeRecord,
        targetFiles: List<String>,
        seedTargets: List<String>
    ): Boolean {
        if (matchesAnyTargetFile(node, targetFiles)) return true
        return matchesAnySeedTarget(node, seedTargets)
    }

    private fun matchesAnyTargetFile(node: ComposeNodeRecord, targetFiles: List<String>): Boolean {
        for (tf in targetFiles) {
            if (node.filePath == tf) return true
            if (node.filePath.endsWith(tf)) return true
            if (node.fqName.endsWith(".$tf")) return true
        }
        return false
    }

    private fun matchesAnySeedTarget(node: ComposeNodeRecord, seedTargets: List<String>): Boolean {
        for (st in seedTargets) {
            if (node.nodeId == st) return true
            if (node.fqName == st) return true
            if (node.fqName.endsWith(".$st")) return true
        }
        return false
    }

    private fun extractBaseType(typeSignature: String): String {
        return typeSignature.substringBefore('<').substringAfterLast('.').removeSuffix("?").trim()
    }

    private fun isSharedModelCandidate(baseType: String, stabilityReason: String): Boolean {
        if (baseType.endsWith("State")) return true
        if (baseType.endsWith("UiModel")) return true
        if (baseType.endsWith("Item")) return true
        return stabilityReason == "EXPLICIT_STABLE_ANNOTATION"
    }

    private fun indexParamTypesToFiles(
        allComposeNodes: List<ComposeNodeRecord>,
        paramsByNode: Map<String, List<ComposeParamRecord>>
    ): Map<String, Set<String>> {
        val paramTypeToFiles = mutableMapOf<String, MutableSet<String>>()
        for (cn in allComposeNodes) {
            val params = paramsByNode[cn.nodeId] ?: emptyList()
            for (p in params) {
                val baseType = extractBaseType(p.typeSignature)
                if (isSharedModelCandidate(baseType, p.stabilityReason)) {
                    paramTypeToFiles.getOrPut(baseType) { mutableSetOf() }.add(cn.filePath)
                }
            }
        }
        return paramTypeToFiles
    }

    private fun collectDownstreamScreens(
        projectId: String,
        cn: ComposeNodeRecord,
        downstreamScreens: MutableSet<String>
    ) {
        if (cn.fqName.endsWith("Screen")) {
            downstreamScreens.add(cn.fqName)
        }
        val hops = store.queryComposeCascade(projectId, cn.nodeId, maxDepth = 4)
        for (hop in hops) {
            if (hop.calleeFqName.endsWith("Screen")) {
                downstreamScreens.add(hop.calleeFqName)
            }
        }
    }

    private fun inspectModelCollisions(
        cn: ComposeNodeRecord,
        params: List<ComposeParamRecord>,
        paramTypeToFiles: Map<String, Set<String>>,
        targetFiles: List<String>,
        sharedStableModels: MutableSet<String>,
        collisionRisks: MutableList<Compose3DScope.CollisionRisk>
    ) {
        for (p in params) {
            val baseType = extractBaseType(p.typeSignature)
            val consumers = paramTypeToFiles[baseType] ?: emptySet()
            if (consumers.size <= 1) continue
            sharedStableModels.add(baseType)
            val outsideFiles = consumers.filter { it !in targetFiles }.sorted()
            if (outsideFiles.isNotEmpty()) {
                collisionRisks.add(
                    Compose3DScope.CollisionRisk(
                        sharedSymbolId = baseType,
                        riskKind = "SHARED_STABLE_MODEL",
                        collidingFiles = consumers.sorted(),
                        remediation = "Serialize edits to shared UI model '$baseType' in a prerequisite foundation PR before dispatching parallel screen workers."
                    )
                )
            }
        }
    }

    private fun inspectCompositionLocalEdges(
        projectId: String,
        nodesById: Map<String, GraphNode>,
        targetFiles: List<String>,
        compositionLocals: MutableSet<String>,
        themeProviders: MutableSet<String>,
        collisionRisks: MutableList<Compose3DScope.CollisionRisk>
    ) {
        val composeEdges = store.listEdges(
            projectId,
            edgeTypes = listOf("PROVIDES_COMPOSITION_LOCAL", "CONSUMES_COMPOSITION_LOCAL", "EXPECT_ACTUAL")
        )
        val localToFiles = mutableMapOf<String, MutableSet<String>>()
        for (edge in composeEdges) {
            val srcFile = resolveEdgeSourceFile(edge, nodesById)
            val tgtName = resolveEdgeTargetName(edge, nodesById)
            if (edge.edgeType in setOf("PROVIDES_COMPOSITION_LOCAL", "CONSUMES_COMPOSITION_LOCAL")) {
                compositionLocals.add(tgtName)
                localToFiles.getOrPut(tgtName) { mutableSetOf() }.add(srcFile)
                if (isThemeTokenProvider(tgtName)) {
                    themeProviders.add(tgtName)
                }
            }
        }
        collectLocalCollisions(localToFiles, themeProviders, targetFiles, collisionRisks)
    }

    private fun resolveEdgeSourceFile(edge: GraphEdge, nodesById: Map<String, GraphNode>): String {
        val node = nodesById[edge.sourceNodeId]
        if (node?.fileId != null) {
            return node.fileId.substringAfter(':')
        }
        return edge.sourceNodeId.substringAfter(':').substringBefore('#')
    }

    private fun resolveEdgeTargetName(edge: GraphEdge, nodesById: Map<String, GraphNode>): String {
        val node = nodesById[edge.targetNodeId]
        if (node != null) {
            return node.name
        }
        return edge.targetNodeId.substringAfterLast('#').removePrefix("sym:")
    }

    private fun isThemeTokenProvider(name: String): Boolean {
        if (name.contains("Theme")) return true
        if (name.contains("Color")) return true
        if (name.contains("Typography")) return true
        return name.contains("Spacing")
    }

    private fun collectLocalCollisions(
        localToFiles: Map<String, Set<String>>,
        themeProviders: Set<String>,
        targetFiles: List<String>,
        collisionRisks: MutableList<Compose3DScope.CollisionRisk>
    ) {
        for ((localSym, files) in localToFiles) {
            if (files.size <= 1) continue
            if (!files.any { it in targetFiles }) continue
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
}
