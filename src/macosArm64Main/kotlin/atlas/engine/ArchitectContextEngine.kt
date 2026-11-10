package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.model.ArchitectContextResult
import atlas.model.GraphNode
import atlas.model.ScopeRecommendation
import atlas.model.SemioticSizing
import atlas.util.PlatformFiles
import kotlin.math.roundToInt

/**
 * High-performance, token-budgeted engine synthesizing 3D Concurrency Scope
 * and structural AST skeletons for /architect, /boost, and /plan.
 */
class ArchitectContextEngine(
    val store: SqliteGraphStore,
    val synthesizer: ScopeSynthesizer,
    val workspaceRoot: String,
    val artifactsDir: String,
    val inlineCeilingBytes: Int = 3500
) {

    internal data class ClassSkeletonSpec(
        val store: SqliteGraphStore,
        val projectId: String,
        val node: GraphNode,
        val relPath: String,
        val maxMembers: Int = 8,
        val trailingNewline: Boolean = true
    )

    companion object {
        private val PROMPT_TOKEN_SPLIT_RE = Regex("[^a-zA-Z0-9_]+")
        private val SLUG_SANITIZE_RE = Regex("[^a-z0-9_-]+")

        internal val STOP_WORDS = setOf(
            "how", "does", "the", "work", "what", "is", "where", "to", "in", "for", "a",
            "an", "and", "or", "of", "from", "with", "by", "on", "at", "it", "this", "that",
            "we", "can", "could", "should", "add", "build", "create", "implement"
        )

        internal fun formatClassSkeleton(
            spec: ClassSkeletonSpec,
            sb: StringBuilder
        ) {
            val node = spec.node
            val header = if (node.signature.isNotEmpty()) node.signature else "${node.kind} ${node.name}"
            sb.append(header)
            if (!header.endsWith("{") && !header.endsWith(":")) {
                sb.append(" {")
            }
            sb.append("\n")

            appendMemberSignatures(spec, sb)

            if (header.endsWith("{") || !header.endsWith(":")) {
                sb.append("}")
                if (spec.trailingNewline) sb.append("\n")
            }
        }

        private fun appendMemberSignatures(
            spec: ClassSkeletonSpec,
            sb: StringBuilder
        ) {
            val node = spec.node
            val fileNodes = spec.store.getNodesForFile(spec.projectId, spec.relPath)
            val members = fileNodes.filter { m ->
                m.startLine > node.startLine && m.endLine <= node.endLine &&
                    m.nodeId != node.nodeId &&
                    (m.kind == "method" || m.kind == "function" || m.kind == "property" || m.kind == "field")
            }

            for (m in members.take(spec.maxMembers)) {
                val sig = if (m.signature.isNotEmpty()) m.signature else "${m.kind} ${m.name}"
                sb.append("  ").append(sig).append("\n")
            }
            if (members.size > spec.maxMembers) {
                sb.append("  // ... and ").append(members.size - spec.maxMembers).append(" more members\n")
            }
        }
    }

    fun synthesizeContext(
        projectId: String,
        seedTargets: List<String>,
        depth: Int = 2
    ): ArchitectContextResult {
        val cleanSeeds = seedTargets.map { it.trim().trim('\'', '"') }.filter { it.isNotEmpty() }
        if (cleanSeeds.isEmpty()) {
            val emptyMd = "# Architect Context: (none)\n\nNo seed symbols or files provided. Specify 'targets' or 'seeds' to synthesize 3D Concurrency Scope."
            val emptySizing = computeSemioticSizing(0, 0, 0.0, 0.0)
            return ArchitectContextResult(
                projectId = projectId,
                queryOrSeeds = emptyList(),
                aciScore = 0.0,
                isFastPath = true,
                files = emptyList(),
                modules = emptyList(),
                architectureLayers = emptyList(),
                collidingPlans = emptyList(),
                godNodesTouched = emptyList(),
                seedSymbols = emptyList(),
                spilled = false,
                spilloverPath = null,
                inlineMarkdown = emptyMd,
                semioticSizing = emptySizing
            )
        }
        val effectiveSeeds = cleanSeeds

        val seedNodes = resolveSeedNodes(projectId, effectiveSeeds)
        val resolvedTargetStrings = if (seedNodes.isNotEmpty()) {
            seedNodes.map { it.nodeId }.distinct()
        } else {
            effectiveSeeds
        }

        val scope = synthesizer.synthesizeScope(projectId, resolvedTargetStrings, depth = depth)
        val aciScore = computeAci(scope.files.size, depth, scope.modules.size)
        val isFastPath = aciScore <= 5.0 && scope.files.size <= 3
        val vscScore = computeVscScore(projectId, scope.files)
        val semioticSizing = computeSemioticSizing(
            filesCount = scope.files.size,
            modulesCount = scope.modules.size,
            codeAci = aciScore,
            vscScore = vscScore
        )

        val keyNodes = if (seedNodes.isNotEmpty()) seedNodes.take(8) else emptyList()
        val skeletons = keyNodes.map { extractStructuralSkeleton(projectId, it) }
        val callHierarchy = buildCallHierarchy(projectId, keyNodes.take(5))

        val aciSummary = AciSummary(aciScore, isFastPath, semioticSizing)
        val fullMarkdown = buildFullMarkdown(
            headerTitle = effectiveSeeds.firstOrNull() ?: projectId,
            scope = scope,
            aciSummary = aciSummary,
            sections = MarkdownSections(skeletons, callHierarchy)
        )

        val fullBytes = fullMarkdown.encodeToByteArray().size
        if (fullBytes <= inlineCeilingBytes) {
            return ArchitectContextResult(
                projectId = projectId,
                queryOrSeeds = effectiveSeeds,
                aciScore = aciScore,
                isFastPath = isFastPath,
                files = scope.files,
                modules = scope.modules,
                architectureLayers = scope.architecture,
                collidingPlans = scope.collidingPlans,
                godNodesTouched = scope.godNodesTouched,
                seedSymbols = keyNodes.map { it.name },
                spilled = false,
                spilloverPath = null,
                inlineMarkdown = fullMarkdown,
                semioticSizing = semioticSizing
            )
        }

        val slug = sanitizeSlug(effectiveSeeds.firstOrNull() ?: "task")
        val destDir = artifactsDir.ifBlank { "$workspaceRoot/.artifacts/atlas" }
        val dirCreated = PlatformFiles.mkdirs(destDir)
        if (!dirCreated && !PlatformFiles.exists(destDir)) {
            PlatformFiles.logWarning("ArchitectContextEngine", "Failed to create spillover directory: $destDir")
        }
        val spilloverPath = "$destDir/context-$slug.md"
        PlatformFiles.writeSecureText(spilloverPath, fullMarkdown)

        val inlineSummary = buildSpilloverSummary(
            spilloverMeta = SpilloverMetadata(slug, spilloverPath, fullBytes),
            scope = scope,
            aciSummary = aciSummary,
            topSkeletons = skeletons.take(3)
        )

        return ArchitectContextResult(
            projectId = projectId,
            queryOrSeeds = effectiveSeeds,
            aciScore = aciScore,
            isFastPath = isFastPath,
            files = scope.files,
            modules = scope.modules,
            architectureLayers = scope.architecture,
            collidingPlans = scope.collidingPlans,
            godNodesTouched = scope.godNodesTouched,
            seedSymbols = keyNodes.map { it.name },
            spilled = true,
            spilloverPath = spilloverPath,
            inlineMarkdown = inlineSummary,
            semioticSizing = semioticSizing
        )
    }

    fun extractStructuralSkeleton(projectId: String, node: GraphNode): String {
        val relPath = if (node.fileId.contains(':')) node.fileId.substringAfter(':') else node.fileId
        val sb = StringBuilder()
        sb.append("// ").append(relPath).append(":").append(node.startLine)
        sb.append(" [").append(node.archLayer).append(":").append(node.semioticLayer).append("]\n")

        if (node.kind == "class" || node.kind == "interface" || node.kind == "object" || node.kind == "struct") {
            formatClassSkeleton(ClassSkeletonSpec(store, projectId, node, relPath, maxMembers = 8, trailingNewline = true), sb)
        } else {
            val sig = if (node.signature.isNotEmpty()) node.signature else "${node.kind} ${node.name}"
            sb.append(sig).append("\n")
        }
        return sb.toString().trimEnd()
    }

    private fun resolveSeedNodes(projectId: String, seedTargets: List<String>): List<GraphNode> {
        val seenNodeIds = mutableSetOf<String>()
        val isNaturalPrompt = seedTargets.size == 1 &&
            seedTargets[0].contains(' ') &&
            !seedTargets[0].contains('/') &&
            !seedTargets[0].contains('.')

        if (isNaturalPrompt) {
            return resolveNaturalPromptSeeds(projectId, seedTargets[0], seenNodeIds)
        }
        return resolveExplicitTargetSeeds(projectId, seedTargets, seenNodeIds)
    }

    private fun resolveNaturalPromptSeeds(projectId: String, prompt: String, seenNodeIds: MutableSet<String>): List<GraphNode> {
        val resolved = mutableListOf<GraphNode>()
        for (token in tokenizePrompt(prompt)) {
            resolveTokenSeeds(projectId, token, seenNodeIds, resolved)
        }
        val phraseMatches = store.searchNodes(projectId, prompt, limit = 4)
        for (p in phraseMatches) {
            if (seenNodeIds.add(p.nodeId)) {
                resolved.add(p)
            }
        }
        return resolved
    }

    private fun resolveTokenSeeds(projectId: String, token: String, seenNodeIds: MutableSet<String>, resolved: MutableList<GraphNode>) {
        val direct = store.getNode(projectId, token)
        if (direct != null && seenNodeIds.add(direct.nodeId)) {
            resolved.add(direct)
        }
        val partials = store.searchNodes(projectId, token, limit = 3)
        for (p in partials) {
            if (seenNodeIds.add(p.nodeId)) {
                resolved.add(p)
            }
        }
    }

    private fun resolveExplicitTargetSeeds(projectId: String, seedTargets: List<String>, seenNodeIds: MutableSet<String>): List<GraphNode> {
        val resolved = mutableListOf<GraphNode>()
        for (target in seedTargets) {
            resolveSingleTargetSeed(projectId, target, seenNodeIds, resolved)
        }
        return resolved
    }

    private fun resolveSingleTargetSeed(projectId: String, target: String, seenNodeIds: MutableSet<String>, resolved: MutableList<GraphNode>) {
        val clean = target.removePrefix("./").replace('\\', '/')
        val direct = store.getNode(projectId, clean)
        if (direct != null && seenNodeIds.add(direct.nodeId)) {
            resolved.add(direct)
            return
        }
        if (clean.contains('/') || clean.contains('.')) {
            resolveFileSeeds(projectId, clean, seenNodeIds, resolved)
        } else {
            resolveFuzzySeeds(projectId, clean, seenNodeIds, resolved)
        }
    }

    private fun resolveFileSeeds(projectId: String, clean: String, seenNodeIds: MutableSet<String>, resolved: MutableList<GraphNode>) {
        val fileNodes = store.getNodesForFile(projectId, clean)
        for (fn in fileNodes) {
            if (fn.isTopLevel && seenNodeIds.add(fn.nodeId)) {
                resolved.add(fn)
            }
        }
    }

    private fun resolveFuzzySeeds(projectId: String, clean: String, seenNodeIds: MutableSet<String>, resolved: MutableList<GraphNode>) {
        val matches = store.searchNodes(projectId, clean, limit = 3)
        for (m in matches) {
            if (seenNodeIds.add(m.nodeId)) {
                resolved.add(m)
            }
        }
    }

    private fun buildCallHierarchy(projectId: String, primaryNodes: List<GraphNode>): List<String> {
        val links = mutableListOf<String>()
        for (node in primaryNodes) {
            val callers = store.getCallers(projectId, node.nodeId, depth = 1).take(3)
            for (c in callers) {
                links.add("- `${c.name}` calls `${node.name}`")
            }
            val callees = store.getCallees(projectId, node.nodeId, depth = 1).take(3)
            for (c in callees) {
                links.add("- `${node.name}` calls `${c.name}`")
            }
        }
        return links.distinct().take(10)
    }

    private fun computeAci(fileCount: Int, depth: Int, moduleCount: Int): Double {
        val raw = (fileCount * 1.0) + (depth * 2.0) + (moduleCount * 3.0)
        return (raw * 10.0).roundToInt() / 10.0
    }

    private fun tokenizePrompt(prompt: String): List<String> {
        return prompt.split(PROMPT_TOKEN_SPLIT_RE)
            .map { it.trim() }
            .filter { it.length >= 3 && !STOP_WORDS.contains(it.lowercase()) }
            .distinct()
    }

    private fun sanitizeSlug(target: String): String {
        return target.lowercase()
            .replace(SLUG_SANITIZE_RE, "-")
            .trim('-')
            .take(32)
            .ifEmpty { "architect" }
    }

    private data class AciSummary(
        val aciScore: Double,
        val isFastPath: Boolean,
        val semioticSizing: SemioticSizing
    )

    internal fun computeSemioticSizing(
        filesCount: Int,
        modulesCount: Int,
        codeAci: Double,
        vscScore: Double = 0.0
    ): SemioticSizing {
        val unifiedAci = (codeAci * 0.7 + vscScore * 0.3 * 10.0).coerceAtLeast(codeAci)
        val roundedCodeAci = (codeAci * 10.0).roundToInt() / 10.0
        val roundedVsc = (vscScore * 10.0).roundToInt() / 10.0
        val roundedUnified = (unifiedAci * 10.0).roundToInt() / 10.0

        val (band, pipeline, rationale) = when {
            filesCount > 8 || modulesCount >= 3 || roundedUnified > 15.0 -> Triple(
                "Band 3",
                "Multi-Agent Swarm",
                "Target scope touches > 8 files, >= 3 modules, or unified ACI > 15.0. Full compound pipeline (/goal -> /grill -> /architect -> /plan -> /teamwork -> /review)."
            )
            filesCount <= 3 && modulesCount <= 1 && roundedUnified <= 5.0 -> Triple(
                "Band 1",
                "Fast-Path Direct",
                "Target scope touches <= 3 files across 1 module with unified ACI <= 5.0 (Fast-Path Direct). Bypass /grill-me, /architect, /plan, and /teamwork. Execute Inspect -> Implement -> Verify directly."
            )
            else -> Triple(
                "Band 2",
                "Medium Plan",
                "Target scope touches 4-8 files or unified ACI is between 5.0 and 15.0. Single-agent /plan contract -> /execute -> /review."
            )
        }

        return SemioticSizing(
            codeAci = roundedCodeAci,
            vscScore = roundedVsc,
            unifiedAci = roundedUnified,
            velocityBand = band,
            recommendedPipeline = pipeline,
            rationale = rationale
        )
    }

    private fun computeVscScore(projectId: String, files: List<String>): Double {
        if (files.isEmpty()) return 0.0
        val uiNodesCount = files.sumOf { relPath ->
            val nodes = store.getNodesForFile(projectId, relPath)
            nodes.count { it.kind == "composable" || it.name.startsWith("Preview") || it.fileId.contains("figma") || it.archLayer == "presentation" }
        }
        return if (uiNodesCount == 0) 0.0 else ((uiNodesCount * 0.5) * 10.0).roundToInt() / 10.0
    }

    private data class MarkdownSections(
        val skeletons: List<String>,
        val callHierarchy: List<String>
    )

    private data class SpilloverMetadata(
        val slug: String,
        val spilloverPath: String,
        val fullBytes: Int
    )

    private fun buildFullMarkdown(
        headerTitle: String,
        scope: ScopeRecommendation,
        aciSummary: AciSummary,
        sections: MarkdownSections
    ): String {
        val sb = StringBuilder()
        sb.append("# Architect Context: ").append(headerTitle).append("\n\n")
        appendConcurrencyBoundarySection(sb, scope, aciSummary)
        appendSkeletonsSection(sb, sections.skeletons)
        appendCallHierarchySection(sb, sections.callHierarchy)
        return sb.toString().trimEnd()
    }

    private fun appendConcurrencyBoundarySection(
        sb: StringBuilder,
        scope: ScopeRecommendation,
        aciSummary: AciSummary
    ) {
        val aciScore = aciSummary.aciScore
        val isFastPath = aciSummary.isFastPath
        val sizing = aciSummary.semioticSizing

        sb.append("## 1. 3D Concurrency Boundary\n")
        appendBoundaryFiles(sb, scope.files)
        sb.append("- **Modules (").append(scope.modules.size).append(")**: ")
        sb.append(if (scope.modules.isNotEmpty()) scope.modules.joinToString(", ") else "root").append("\n")
        sb.append("- **Semiotic / Arch Layers**: ")
        sb.append(if (scope.architecture.isNotEmpty()) scope.architecture.joinToString(", ") else "unclassified").append("\n")
        sb.append("- **ACI Complexity Score**: ").append(aciScore)
        sb.append(" (Fast Path: ").append(if (isFastPath) "YES <= 5.0" else "NO -> Multi-Agent Swarm").append(")\n")
        sb.append("- **Semiotic Sizing**: ").append(sizing.velocityBand)
        sb.append(" (Unified ACI: ").append(sizing.unifiedAci)
        sb.append(", Pipeline: ").append(sizing.recommendedPipeline).append(")\n")
        sb.append("- **Velocity Rationale**: ").append(sizing.rationale).append("\n")

        if (scope.collidingPlans.isNotEmpty()) {
            sb.append("- **Active Plan Collisions**: ").append(scope.collidingPlans.joinToString(", ")).append("\n")
        } else {
            sb.append("- **Plan Collisions**: None detected\n")
        }

        if (scope.godNodesTouched.isNotEmpty()) {
            sb.append("- **God Nodes Touched**: ").append(scope.godNodesTouched.joinToString(", ")).append("\n")
        }
        sb.append("\n")
    }

    private fun appendBoundaryFiles(sb: StringBuilder, scopeFiles: List<String>) {
        sb.append("- **Files (").append(scopeFiles.size).append(")**: ")
        if (scopeFiles.isNotEmpty()) {
            sb.append(scopeFiles.take(12).joinToString(", "))
            if (scopeFiles.size > 12) sb.append(" (and ").append(scopeFiles.size - 12).append(" more)")
        } else {
            sb.append("None")
        }
        sb.append("\n")
    }

    private fun appendSkeletonsSection(sb: StringBuilder, skeletons: List<String>) {
        if (skeletons.isEmpty()) return
        sb.append("## 2. Core Symbol Structural Skeletons\n")
        for (sk in skeletons) {
            sb.append("```\n").append(sk).append("\n```\n\n")
        }
    }

    private fun appendCallHierarchySection(sb: StringBuilder, callHierarchy: List<String>) {
        if (callHierarchy.isEmpty()) return
        sb.append("## 3. Call Hierarchy\n")
        callHierarchy.forEach { sb.append(it).append("\n") }
        sb.append("\n")
    }

    private fun buildSpilloverSummary(
        spilloverMeta: SpilloverMetadata,
        scope: ScopeRecommendation,
        aciSummary: AciSummary,
        topSkeletons: List<String>
    ): String {
        val slug = spilloverMeta.slug
        val spilloverPath = spilloverMeta.spilloverPath
        val fullBytes = spilloverMeta.fullBytes
        val aciScore = aciSummary.aciScore
        val isFastPath = aciSummary.isFastPath
        val scopeFiles = scope.files
        val scopeModules = scope.modules
        val collidingPlans = scope.collidingPlans
        val sb = StringBuilder()
        val kbSize = (fullBytes / 1024.0 * 10).roundToInt() / 10.0
        sb.append("# Architect Context: ").append(slug).append(" (Spilled to Disk)\n\n")
        sb.append("> [!NOTE]\n")
        sb.append("> Full architectural context (").append(kbSize).append(" KB) written to disk:\n")
        sb.append("> [context-").append(slug).append(".md](file://").append(spilloverPath).append(")\n\n")

        sb.append("## 3D Concurrency Boundary Summary\n")
        sb.append("- **Files (").append(scopeFiles.size).append(")**: ")
        sb.append(scopeFiles.take(6).joinToString(", "))
        if (scopeFiles.size > 6) sb.append(" ...")
        sb.append("\n")

        sb.append("- **Modules (").append(scopeModules.size).append(")**: ")
        sb.append(if (scopeModules.isNotEmpty()) scopeModules.joinToString(", ") else "root").append("\n")

        sb.append("- **ACI Score**: ").append(aciScore)
        sb.append(" (").append(if (isFastPath) "Solo Fast Path" else "Multi-Agent Swarm").append(")\n")
        sb.append("- **Semiotic Sizing**: ").append(aciSummary.semioticSizing.velocityBand)
        sb.append(" (Unified ACI: ").append(aciSummary.semioticSizing.unifiedAci)
        sb.append(", Pipeline: ").append(aciSummary.semioticSizing.recommendedPipeline).append(")\n")

        if (collidingPlans.isNotEmpty()) {
            sb.append("- **Plan Collisions**: ").append(collidingPlans.joinToString(", ")).append("\n")
        }
        sb.append("\n")

        if (topSkeletons.isNotEmpty()) {
            sb.append("## Primary Symbol Skeletons (Top ").append(topSkeletons.size).append(")\n")
            for (sk in topSkeletons) {
                sb.append("```\n").append(sk).append("\n```\n\n")
            }
        }
        return sb.toString().trimEnd()
    }
}
