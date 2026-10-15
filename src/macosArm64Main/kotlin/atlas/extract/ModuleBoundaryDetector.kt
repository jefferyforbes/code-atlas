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

        fun convertTypesafeAccessorToModuleId(accessor: String, knownModules: List<String> = emptyList()): String {
            val clean = accessor.trim().removePrefix("projects.")
            val segments = clean.split('.')
            val directMod = ":" + segments.joinToString(":")

            val kebabSegments = segments.map { seg ->
                val sb = StringBuilder()
                for (c in seg) {
                    if (c.isUpperCase()) sb.append('-').append(c.lowercaseChar())
                    else sb.append(c)
                }
                sb.toString()
            }
            val kebabMod = ":" + kebabSegments.joinToString(":")

            if (knownModules.isNotEmpty()) {
                if (kebabMod in knownModules) return kebabMod
                val snakeSegments = segments.map { seg ->
                    val sb = StringBuilder()
                    for (c in seg) {
                        if (c.isUpperCase()) sb.append('_').append(c.lowercaseChar())
                        else sb.append(c)
                    }
                    sb.toString()
                }
                val snakeMod = ":" + snakeSegments.joinToString(":")
                if (snakeMod in knownModules) return snakeMod
                if (directMod in knownModules) return directMod
                val lowerMod = ":" + segments.joinToString(":") { it.lowercase() }
                for (km in knownModules) {
                    if (km.lowercase() == lowerMod) return km
                }
            }
            return directMod
        }

        fun extractFromBuildContent(
            projectId: String,
            relPath: String,
            sourceText: String,
            parentModule: String
        ): Pair<List<GraphNode>, List<GraphEdge>> {
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()

            val srcNodeId = "$projectId:module:$parentModule"
            nodes.add(
                GraphNode(
                    nodeId = srcNodeId,
                    projectId = projectId,
                    fileId = "$projectId:$relPath",
                    name = parentModule,
                    qualifiedName = parentModule,
                    kind = "module",
                    signature = "module $parentModule",
                    moduleId = parentModule,
                    semioticLayer = "syntactic",
                    archLayer = "infrastructure"
                )
            )

            var idx = 1
            for (match in GRADLE_PROJECT_DEP_REGEX.findAll(sourceText)) {
                var targetMod = match.groupValues[1]
                if (!targetMod.startsWith(":")) targetMod = ":$targetMod"
                val tgtNodeId = "$projectId:module:$targetMod"
                nodes.add(
                    GraphNode(
                        nodeId = tgtNodeId,
                        projectId = projectId,
                        fileId = "$projectId:$relPath",
                        name = targetMod,
                        qualifiedName = targetMod,
                        kind = "module",
                        signature = "module $targetMod",
                        moduleId = targetMod,
                        semioticLayer = "syntactic",
                        archLayer = "infrastructure"
                    )
                )
                edges.add(
                    GraphEdge(
                        edgeId = "$projectId:mod_dep:$parentModule->$targetMod:$idx",
                        projectId = projectId,
                        sourceNodeId = srcNodeId,
                        targetNodeId = tgtNodeId,
                        edgeType = "MODULE_DEPENDS_ON",
                        provenance = "EXTRACTED_GRADLE",
                        confidence = 1.0,
                        lineNumber = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                    )
                )
                idx++
            }

            for (match in GRADLE_TYPE_SAFE_REGEX.findAll(sourceText)) {
                val accessor = match.groupValues[1]
                val converted = convertTypesafeAccessorToModuleId(accessor)
                val tgtNodeId = "$projectId:module:$converted"
                nodes.add(
                    GraphNode(
                        nodeId = tgtNodeId,
                        projectId = projectId,
                        fileId = "$projectId:$relPath",
                        name = converted,
                        qualifiedName = converted,
                        kind = "module",
                        signature = "module $converted",
                        moduleId = converted,
                        semioticLayer = "syntactic",
                        archLayer = "infrastructure"
                    )
                )
                edges.add(
                    GraphEdge(
                        edgeId = "$projectId:mod_dep:$parentModule->$converted:$idx",
                        projectId = projectId,
                        sourceNodeId = srcNodeId,
                        targetNodeId = tgtNodeId,
                        edgeType = "MODULE_DEPENDS_ON",
                        provenance = "EXTRACTED_GRADLE",
                        confidence = 1.0,
                        lineNumber = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                    )
                )
                idx++
            }

            return Pair(nodes, edges)
        }

        fun parseSettingsIncludes(sourceText: String): List<String> {
            val list = mutableListOf<String>()
            for (match in GRADLE_INCLUDE_REGEX.findAll(sourceText)) {
                val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
                val tokens = Regex("""["']([^"']+)["']""").findAll(raw).map { it.groupValues[1] }
                for (t in tokens) {
                    val mod = if (t.startsWith(":")) t else ":$t"
                    if (mod !in list) list.add(mod)
                }
            }
            return list
        }

        fun extractModuleGraph(
            projectRoot: String,
            projectId: String
        ): Triple<List<GraphNode>, List<GraphEdge>, List<String>> {
            val nodes = mutableMapOf<String, GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val knownModules = mutableListOf<String>()

            for (settingsName in listOf("settings.gradle.kts", "settings.gradle")) {
                val settingsPath = if (projectRoot.endsWith("/")) "$projectRoot$settingsName" else "$projectRoot/$settingsName"
                if (PlatformFiles.exists(settingsPath)) {
                    val text = PlatformFiles.readText(settingsPath)
                    val includes = parseSettingsIncludes(text)
                    for (mod in includes) {
                        if (mod !in knownModules) knownModules.add(mod)
                    }
                }
            }

            val allFiles = PlatformFiles.listFilesRecursively(projectRoot)
            for (filePath in allFiles) {
                val fileName = PlatformFiles.fileName(filePath)
                if (fileName != "build.gradle" && fileName != "build.gradle.kts") continue

                val rel = filePath.removePrefix(projectRoot).removePrefix("/").replace('\\', '/')
                val parts = rel.split('/')
                if (parts.any { it.startsWith(".") || it in setOf("build", "node_modules", "dist") }) continue

                val parentRel = PlatformFiles.parentOf(rel)
                val sourceMod = if (parentRel.isEmpty() || parentRel == ".") ":root" else ":" + parentRel.replace('/', ':')
                if (sourceMod !in knownModules) knownModules.add(sourceMod)

                val (buildNodes, buildEdges) = extractFromBuildContent(
                    projectId = projectId,
                    relPath = rel,
                    sourceText = PlatformFiles.readText(filePath),
                    parentModule = sourceMod
                )
                for (n in buildNodes) {
                    nodes[n.nodeId] = n
                }
                edges.addAll(buildEdges)
            }

            return Triple(nodes.values.toList(), edges, knownModules)
        }

        fun synthesizeKmpExpectActualEdges(
            projectId: String,
            nodes: List<GraphNode>
        ): List<GraphEdge> {
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

            val result = mutableListOf<GraphEdge>()
            for ((key, expList) in expects) {
                val actList = actuals[key] ?: continue
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
            return result
        }
    }
}
