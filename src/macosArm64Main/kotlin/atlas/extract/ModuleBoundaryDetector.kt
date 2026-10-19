package atlas.extract

import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles

class ModuleBoundaryDetector {
    companion object {
        private val GRADLE_PROJECT_DEP_REGEX = Regex(
            """(?:implementation|api|compileOnly|debugImplementation|releaseImplementation|testImplementation|androidTestImplementation)\s*\(\s*project\s*\(\s*["']([^"']+)["']\s*\)\s*\)"""
        )
        private val GRADLE_TYPE_SAFE_REGEX = Regex(
            """(?:implementation|api|compileOnly|debugImplementation|releaseImplementation|testImplementation|androidTestImplementation)\s*\(\s*projects\.([a-zA-Z0-9_.]+)\s*\)"""
        )
        private val GRADLE_INCLUDE_REGEX = Regex("""include\s*\(([^)]+)\)|include\s+([^\n]+)""")

        fun resolveModuleId(relPath: String, knownModules: List<String> = emptyList()): String {
            val norm = relPath.replace('\\', '/').trim('/')
            val parts = norm.split('/')

            for (mod in knownModules.sortedByDescending { it.length }) {
                val modPath = mod.removePrefix(":").replace(':', '/')
                if (norm.startsWith("$modPath/") || norm == modPath) {
                    return mod
                }
            }

            if ("src" in parts) {
                val srcIdx = parts.indexOf("src")
                if (srcIdx > 0) {
                    return ":" + parts.subList(0, srcIdx).joinToString(":")
                }
                return ":root"
            }

            if (parts.size >= 3 && parts[0] in listOf("orchestrator", "shared", "feature", "features", "packages", "apps")) {
                return "${parts[0]}/${parts[1]}"
            }
            if (parts.size >= 2) return parts[0]
            return "root"
        }

        private val EXCLUDED_BUILD_DIRS = setOf(
            ".git", ".gradle", ".idea", ".venv", "venv", "node_modules", "build", "dist", ".agents", ".agent", ".atlas"
        )

        fun convertTypesafeAccessorToModuleId(accessor: String, knownModules: List<String> = emptyList()): String {
            val clean = accessor.trim().removePrefix("projects.")
            val segments = clean.split('.')
            val directMod = ":" + segments.joinToString(":")
            if (knownModules.isEmpty()) return directMod
            return matchKnownModule(segments, directMod, knownModules) ?: directMod
        }

        private fun camelToDelimiter(str: String, delimiter: Char): String {
            val sb = StringBuilder()
            for ((idx, c) in str.withIndex()) {
                if (c.isUpperCase()) {
                    if (idx > 0) sb.append(delimiter)
                    sb.append(c.lowercaseChar())
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun matchKnownModule(
            segments: List<String>,
            directMod: String,
            knownModules: List<String>
        ): String? {
            val kebabMod = ":" + segments.joinToString(":") { camelToDelimiter(it, '-') }
            if (kebabMod in knownModules) return kebabMod

            val snakeMod = ":" + segments.joinToString(":") { camelToDelimiter(it, '_') }
            if (snakeMod in knownModules) return snakeMod

            if (directMod in knownModules) return directMod

            val lowerMod = ":" + segments.joinToString(":") { it.lowercase() }
            for (km in knownModules) {
                if (km.lowercase() == lowerMod) return km
            }
            return null
        }

        fun extractFromBuildContent(
            projectId: String,
            relPath: String,
            sourceText: String,
            parentModule: String,
            knownModules: List<String> = emptyList()
        ): Pair<List<GraphNode>, List<GraphEdge>> {
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val seenEdgeIds = mutableSetOf<String>()

            val srcNode = createModuleNode(projectId, relPath, parentModule)
            nodes.add(srcNode)

            extractProjectDeps(sourceText, projectId, relPath, parentModule, srcNode.nodeId, nodes, edges, seenEdgeIds)
            extractTypeSafeDeps(sourceText, projectId, relPath, parentModule, knownModules, srcNode.nodeId, nodes, edges, seenEdgeIds)

            return Pair(nodes, edges)
        }

        private fun createModuleNode(projectId: String, relPath: String, moduleId: String): GraphNode {
            return GraphNode(
                nodeId = "$projectId:module:$moduleId",
                projectId = projectId,
                fileId = "$projectId:$relPath",
                name = moduleId,
                qualifiedName = moduleId,
                kind = "module",
                signature = "module $moduleId",
                moduleId = moduleId,
                semioticLayer = "syntactic",
                archLayer = "infrastructure"
            )
        }

        private fun addModuleDependencyEdge(
            projectId: String,
            parentModule: String,
            targetMod: String,
            srcNodeId: String,
            tgtNodeId: String,
            lineIndex: Int,
            edges: MutableList<GraphEdge>,
            seenEdgeIds: MutableSet<String>
        ) {
            val edgeId = "$projectId:mod_dep:$parentModule->$targetMod"
            if (seenEdgeIds.add(edgeId)) {
                edges.add(
                    GraphEdge(
                        edgeId = edgeId,
                        projectId = projectId,
                        sourceNodeId = srcNodeId,
                        targetNodeId = tgtNodeId,
                        edgeType = "MODULE_DEPENDS_ON",
                        provenance = "EXTRACTED_GRADLE",
                        confidence = 1.0,
                        lineNumber = lineIndex
                    )
                )
            }
        }

        private fun extractProjectDeps(
            sourceText: String,
            projectId: String,
            relPath: String,
            parentModule: String,
            srcNodeId: String,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>,
            seenEdgeIds: MutableSet<String>
        ) {
            for (match in GRADLE_PROJECT_DEP_REGEX.findAll(sourceText)) {
                var targetMod = match.groupValues[1]
                if (!targetMod.startsWith(":")) targetMod = ":$targetMod"
                val tgtNode = createModuleNode(projectId, relPath, targetMod)
                nodes.add(tgtNode)
                val line = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                addModuleDependencyEdge(projectId, parentModule, targetMod, srcNodeId, tgtNode.nodeId, line, edges, seenEdgeIds)
            }
        }

        private fun extractTypeSafeDeps(
            sourceText: String,
            projectId: String,
            relPath: String,
            parentModule: String,
            knownModules: List<String>,
            srcNodeId: String,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>,
            seenEdgeIds: MutableSet<String>
        ) {
            for (match in GRADLE_TYPE_SAFE_REGEX.findAll(sourceText)) {
                val accessor = match.groupValues[1]
                val converted = convertTypesafeAccessorToModuleId(accessor, knownModules)
                val tgtNode = createModuleNode(projectId, relPath, converted)
                nodes.add(tgtNode)
                val line = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                addModuleDependencyEdge(projectId, parentModule, converted, srcNodeId, tgtNode.nodeId, line, edges, seenEdgeIds)
            }
        }

        fun parseSettingsIncludes(sourceText: String): List<String> {
            val list = mutableListOf<String>()
            for (match in GRADLE_INCLUDE_REGEX.findAll(sourceText)) {
                val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
                extractIncludeTokens(raw, list)
            }
            return list
        }

        private fun extractIncludeTokens(raw: String, list: MutableList<String>) {
            for (match in Regex("""["']([^"']+)["']""").findAll(raw)) {
                val t = match.groupValues[1]
                val mod = if (t.startsWith(":")) t else ":$t"
                if (mod !in list) list.add(mod)
            }
        }

        fun extractModuleGraph(
            projectRoot: String,
            projectId: String
        ): Triple<List<GraphNode>, List<GraphEdge>, List<String>> {
            val nodes = mutableMapOf<String, GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val knownModules = collectSettingsModules(projectRoot)

            val buildEntries = collectBuildGradleEntries(projectRoot, knownModules)
            for ((filePath, rel, sourceMod) in buildEntries) {
                val (buildNodes, buildEdges) = extractFromBuildContent(
                    projectId = projectId,
                    relPath = rel,
                    sourceText = PlatformFiles.readText(filePath),
                    parentModule = sourceMod,
                    knownModules = knownModules
                )
                for (n in buildNodes) {
                    nodes[n.nodeId] = n
                }
                edges.addAll(buildEdges)
            }

            return Triple(nodes.values.toList(), edges, knownModules)
        }

        private fun collectSettingsModules(projectRoot: String): MutableList<String> {
            val knownModules = mutableListOf<String>()
            for (settingsName in listOf("settings.gradle.kts", "settings.gradle")) {
                val settingsPath = if (projectRoot.endsWith("/")) "$projectRoot$settingsName" else "$projectRoot/$settingsName"
                readSettingsModules(settingsPath, knownModules)
            }
            return knownModules
        }

        private fun readSettingsModules(settingsPath: String, knownModules: MutableList<String>) {
            if (!PlatformFiles.exists(settingsPath)) return
            val text = PlatformFiles.readText(settingsPath)
            for (mod in parseSettingsIncludes(text)) {
                if (mod !in knownModules) knownModules.add(mod)
            }
        }

        private fun collectBuildGradleEntries(
            projectRoot: String,
            knownModules: MutableList<String>
        ): List<Triple<String, String, String>> {
            val allFiles = PlatformFiles.listFilesRecursively(projectRoot) { dirName ->
                dirName in EXCLUDED_BUILD_DIRS
            }
            val buildEntries = mutableListOf<Triple<String, String, String>>()
            for (filePath in allFiles) {
                val entry = parseBuildEntry(filePath, projectRoot, knownModules) ?: continue
                buildEntries.add(entry)
            }
            return buildEntries
        }

        private fun parseBuildEntry(
            filePath: String,
            projectRoot: String,
            knownModules: MutableList<String>
        ): Triple<String, String, String>? {
            val fileName = PlatformFiles.fileName(filePath)
            if (fileName != "build.gradle" && fileName != "build.gradle.kts") return null

            val rel = filePath.removePrefix(projectRoot).removePrefix("/").replace('\\', '/')
            val parts = rel.split('/')
            if (parts.any { it.startsWith(".") || it in setOf("build", "node_modules", "dist") }) return null

            val parentRel = PlatformFiles.parentOf(rel)
            val sourceMod = if (parentRel.isEmpty() || parentRel == ".") ":root" else ":" + parentRel.replace('/', ':')
            if (sourceMod !in knownModules) knownModules.add(sourceMod)
            return Triple(filePath, rel, sourceMod)
        }

        fun synthesizeKmpExpectActualEdges(
            projectId: String,
            nodes: List<GraphNode>
        ): List<GraphEdge> {
            val (expects, actuals) = groupKmpDeclarations(projectId, nodes)
            val result = mutableListOf<GraphEdge>()
            for ((key, expList) in expects) {
                val actList = actuals[key] ?: continue
                connectExpectActualPairs(projectId, expList, actList, result)
            }
            return result
        }

        private fun groupKmpDeclarations(
            projectId: String,
            nodes: List<GraphNode>
        ): Pair<Map<Pair<String, String>, List<GraphNode>>, Map<Pair<String, String>, List<GraphNode>>> {
            val expects = mutableMapOf<Pair<String, String>, MutableList<GraphNode>>()
            val actuals = mutableMapOf<Pair<String, String>, MutableList<GraphNode>>()
            for (node in nodes) {
                if (node.projectId != projectId) continue
                val key = Pair(node.moduleId, node.name)
                if (node.kind == "expect_decl") {
                    expects.getOrPut(key) { mutableListOf() }.add(node)
                } else if (node.kind == "actual_decl") {
                    actuals.getOrPut(key) { mutableListOf() }.add(node)
                }
            }
            return Pair(expects, actuals)
        }

        private fun connectExpectActualPairs(
            projectId: String,
            expList: List<GraphNode>,
            actList: List<GraphNode>,
            result: MutableList<GraphEdge>
        ) {
            for (exp in expList) {
                for (act in actList) {
                    result.add(
                        GraphEdge(
                            edgeId = "$projectId:kmp:${exp.nodeId}->${act.nodeId}",
                            projectId = projectId,
                            sourceNodeId = act.nodeId,
                            targetNodeId = exp.nodeId,
                            edgeType = "EXPECT_ACTUAL",
                            provenance = "EXTRACTED_AST",
                            confidence = 1.0,
                            lineNumber = act.startLine
                        )
                    )
                }
            }
        }
    }
}
