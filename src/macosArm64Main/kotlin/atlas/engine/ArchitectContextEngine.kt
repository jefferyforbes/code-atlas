package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.model.ArchitectContextResult
import atlas.model.GraphNode
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

    companion object {
        private val STOP_WORDS = setOf(
            "how", "does", "the", "work", "what", "is", "where", "to", "in", "for", "a",
            "an", "and", "or", "of", "from", "with", "by", "on", "at", "it", "this", "that",
            "we", "can", "could", "should", "add", "build", "create", "implement"
        )
    }

    fun synthesizeContext(
        projectId: String,
        seedTargets: List<String>,
        depth: Int = 2
    ): ArchitectContextResult {
        val cleanSeeds = seedTargets.map { it.trim().trim('\'', '"') }.filter { it.isNotEmpty() }
        if (cleanSeeds.isEmpty()) {
            val emptyMd = "# Architect Context: (none)\n\nNo seed symbols or files provided. Specify 'targets' or 'seeds' to synthesize 3D Concurrency Scope."
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
                inlineMarkdown = emptyMd
            )
        }
        val effectiveSeeds = cleanSeeds

        val seedNodes = resolveSeedNodes(projectId, effectiveSeeds)
        val resolvedTargetStrings = if (seedNodes.isNotEmpty()) {
            seedNodes.map { it.name }.distinct()
        } else {
            effectiveSeeds
        }

        val scope = synthesizer.synthesizeScope(projectId, resolvedTargetStrings, depth = depth)
        val aciScore = computeAci(scope.files.size, depth, scope.modules.size)
        val isFastPath = aciScore <= 5.0 && scope.files.size <= 3

        val keyNodes = if (seedNodes.isNotEmpty()) seedNodes.take(8) else emptyList()
        val skeletons = keyNodes.map { extractStructuralSkeleton(projectId, it) }
        val callHierarchy = buildCallHierarchy(projectId, keyNodes.take(5))

        val fullMarkdown = buildFullMarkdown(
            projectId = projectId,
            seedTargets = effectiveSeeds,
            scopeFiles = scope.files,
            scopeModules = scope.modules,
            architecture = scope.architecture,
            collidingPlans = scope.collidingPlans,
            godNodesTouched = scope.godNodesTouched,
            aciScore = aciScore,
            isFastPath = isFastPath,
            skeletons = skeletons,
            callHierarchy = callHierarchy
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
                inlineMarkdown = fullMarkdown
            )
        }

        val slug = sanitizeSlug(effectiveSeeds.firstOrNull() ?: "task")
        val destDir = "$workspaceRoot/.artifacts/atlas"
        PlatformFiles.mkdirs(destDir)
        val spilloverPath = "$destDir/context-$slug.md"
        PlatformFiles.writeText(spilloverPath, fullMarkdown)

        val inlineSummary = buildSpilloverSummary(
            slug = slug,
            spilloverPath = spilloverPath,
            fullBytes = fullBytes,
            scopeFiles = scope.files,
            scopeModules = scope.modules,
            collidingPlans = scope.collidingPlans,
            aciScore = aciScore,
            isFastPath = isFastPath,
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
            inlineMarkdown = inlineSummary
        )
    }

    fun extractStructuralSkeleton(projectId: String, node: GraphNode): String {
        val relPath = if (node.fileId.contains(':')) node.fileId.substringAfter(':') else node.fileId
        val sb = StringBuilder()
        sb.append("// ").append(relPath).append(":").append(node.startLine)
        sb.append(" [").append(node.archLayer).append(":").append(node.semioticLayer).append("]\n")

        if (node.kind == "class" || node.kind == "interface" || node.kind == "object" || node.kind == "struct") {
            val header = if (node.signature.isNotEmpty()) node.signature else "${node.kind} ${node.name}"
            sb.append(header)
            if (!header.endsWith("{") && !header.endsWith(":")) {
                sb.append(" {")
            }
            sb.append("\n")

            val fileNodes = store.getNodesForFile(projectId, relPath)
            val members = fileNodes.filter { m ->
                m.startLine > node.startLine && m.endLine <= node.endLine &&
                    m.nodeId != node.nodeId &&
                    (m.kind == "method" || m.kind == "function" || m.kind == "property" || m.kind == "field")
            }

            for (m in members.take(8)) {
                val sig = if (m.signature.isNotEmpty()) m.signature else "${m.kind} ${m.name}"
                sb.append("  ").append(sig).append("\n")
            }
            if (members.size > 8) {
                sb.append("  // ... and ").append(members.size - 8).append(" more members\n")
            }
            if (header.endsWith("{") || !header.endsWith(":")) {
                sb.append("}\n")
            }
        } else {
            val sig = if (node.signature.isNotEmpty()) node.signature else "${node.kind} ${node.name}"
            sb.append(sig).append("\n")
        }
        return sb.toString().trimEnd()
    }

    private fun resolveSeedNodes(projectId: String, seedTargets: List<String>): List<GraphNode> {
        val resolved = mutableListOf<GraphNode>()
        val seenNodeIds = mutableSetOf<String>()

        val isNaturalPrompt = seedTargets.size == 1 &&
            seedTargets[0].contains(' ') &&
            !seedTargets[0].contains('/') &&
            !seedTargets[0].contains('.')

        if (isNaturalPrompt) {
            val tokens = tokenizePrompt(seedTargets[0])
            for (token in tokens) {
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
            val phraseMatches = store.searchNodes(projectId, seedTargets[0], limit = 4)
            for (p in phraseMatches) {
                if (seenNodeIds.add(p.nodeId)) {
                    resolved.add(p)
                }
            }
            return resolved
        }

        for (target in seedTargets) {
            val clean = target.removePrefix("./").replace('\\', '/')
            val direct = store.getNode(projectId, clean)
            if (direct != null && seenNodeIds.add(direct.nodeId)) {
                resolved.add(direct)
                continue
            }
            if (clean.contains('/') || clean.contains('.')) {
                val fileNodes = store.getNodesForFile(projectId, clean)
                for (fn in fileNodes) {
                    if (fn.isTopLevel && seenNodeIds.add(fn.nodeId)) {
                        resolved.add(fn)
                    }
                }
            } else {
                val matches = store.searchNodes(projectId, clean, limit = 3)
                for (m in matches) {
                    if (seenNodeIds.add(m.nodeId)) {
                        resolved.add(m)
                    }
                }
            }
        }
        return resolved
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
        return prompt.split(Regex("[^a-zA-Z0-9_]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && !STOP_WORDS.contains(it.lowercase()) }
            .distinct()
    }

    private fun sanitizeSlug(target: String): String {
        return target.lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .take(32)
            .ifEmpty { "architect" }
    }

    private fun buildFullMarkdown(
        projectId: String,
        seedTargets: List<String>,
        scopeFiles: List<String>,
        scopeModules: List<String>,
        architecture: List<String>,
        collidingPlans: List<String>,
        godNodesTouched: List<String>,
        aciScore: Double,
        isFastPath: Boolean,
        skeletons: List<String>,
        callHierarchy: List<String>
    ): String {
        val sb = StringBuilder()
        sb.append("# Architect Context: ").append(seedTargets.firstOrNull() ?: projectId).append("\n\n")

        sb.append("## 1. 3D Concurrency Boundary\n")
        sb.append("- **Files (").append(scopeFiles.size).append(")**: ")
        if (scopeFiles.isNotEmpty()) {
            sb.append(scopeFiles.take(12).joinToString(", "))
            if (scopeFiles.size > 12) sb.append(" (and ").append(scopeFiles.size - 12).append(" more)")
        } else {
            sb.append("None")
        }
        sb.append("\n")

        sb.append("- **Modules (").append(scopeModules.size).append(")**: ")
        sb.append(if (scopeModules.isNotEmpty()) scopeModules.joinToString(", ") else "root").append("\n")

        sb.append("- **Semiotic / Arch Layers**: ")
        sb.append(if (architecture.isNotEmpty()) architecture.joinToString(", ") else "unclassified").append("\n")

        sb.append("- **ACI Complexity Score**: ").append(aciScore)
        sb.append(" (Fast Path: ").append(if (isFastPath) "YES <= 5.0" else "NO -> Multi-Agent Swarm").append(")\n")

        if (collidingPlans.isNotEmpty()) {
            sb.append("- **Active Plan Collisions**: ").append(collidingPlans.joinToString(", ")).append("\n")
        } else {
            sb.append("- **Plan Collisions**: None detected\n")
        }

        if (godNodesTouched.isNotEmpty()) {
            sb.append("- **God Nodes Touched**: ").append(godNodesTouched.joinToString(", ")).append("\n")
        }
        sb.append("\n")

        if (skeletons.isNotEmpty()) {
            sb.append("## 2. Core Symbol Structural Skeletons\n")
            for (sk in skeletons) {
                sb.append("```\n").append(sk).append("\n```\n\n")
            }
        }

        if (callHierarchy.isNotEmpty()) {
            sb.append("## 3. Call Hierarchy\n")
            callHierarchy.forEach { sb.append(it).append("\n") }
            sb.append("\n")
        }

        return sb.toString().trimEnd()
    }

    private fun buildSpilloverSummary(
        slug: String,
        spilloverPath: String,
        fullBytes: Int,
        scopeFiles: List<String>,
        scopeModules: List<String>,
        collidingPlans: List<String>,
        aciScore: Double,
        isFastPath: Boolean,
        topSkeletons: List<String>
    ): String {
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
