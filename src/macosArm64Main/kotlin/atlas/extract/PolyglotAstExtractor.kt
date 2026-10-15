package atlas.extract

import atlas.model.GraphEdge
import atlas.model.GraphNode

class PolyglotAstExtractor {
    companion object {
        private val EXT_MAP = mapOf(
            ".kt" to "kotlin", ".kts" to "kotlin",
            ".py" to "python",
            ".swift" to "swift",
            ".ts" to "typescript", ".tsx" to "typescript",
            ".js" to "javascript", ".jsx" to "javascript",
            ".rs" to "rust",
            ".cpp" to "cpp", ".cc" to "cpp", ".h" to "cpp", ".hpp" to "cpp"
        )

        private val KT_PKG_RE = Regex("""^\s*package\s+([a-zA-Z0-9_.]+)""", RegexOption.MULTILINE)
        private val KT_IMP_RE = Regex("""^\s*import\s+([a-zA-Z0-9_.]+)""", RegexOption.MULTILINE)
        private val KT_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?<annotations>(?:@[A-Za-z0-9_]+(?:\([^)]*\))?\s*)*)""" +
            """(?<modifiers>(?:(?:public|internal|private|protected|open|abstract|sealed|data|value|inline|suspend|expect|actual|override|fun)\s+)*)""" +
            """(?<kw>class|interface|object|fun|typealias)\s+(?:[A-Za-z0-9_<>?,.\s]+\.)?(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )

        private val SWIFT_IMP_RE = Regex("""^\s*(?:@\w+\s+)?(?:public\s+|internal\s+)?import\s+([a-zA-Z0-9_.]+)""", RegexOption.MULTILINE)
        private val SWIFT_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:(?:public|private|fileprivate|internal|open|final|actor|indirect|nonisolated|isolated)\s+)*""" +
            """(?<kw>struct|class|protocol|actor|enum|func|extension)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )

        private val TS_IMP_RE = Regex("""import\s+(?:type\s+)?(?:(?:\{([^}]+)\})|([A-Za-z0-9_]+))\s+from\s+['"]([^'"]+)['"]""")
        private val TS_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:export\s+)?(?:default\s+)?(?:async\s+)?(?<kw>class|interface|type|function|enum|const)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )

        private val RS_USE_RE = Regex("""^\s*(?:pub(?:\([^)]*\))?\s+)?use\s+([a-zA-Z0-9_:]+)""", RegexOption.MULTILINE)
        private val RS_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?<kw>struct|enum|trait|fn|type|mod)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )

        private val CPP_INC_RE = Regex("""^\s*#include\s+[<"]([^>"]+)[>"]""", RegexOption.MULTILINE)
        private val CPP_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:template\s*<[^>]*>\s*)?(?<kw>class|struct|namespace|enum(?:\s+class)?)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )

        private val PY_IMP_RE = Regex("""^\s*(?:from\s+([a-zA-Z0-9_.]+)\s+import|import\s+([a-zA-Z0-9_.]+))""", RegexOption.MULTILINE)
        private val PY_DECL_RE = Regex("""^(?<indent>[ \t]*)(?<kw>class|def|async\s+def)\s+(?<name>[A-Za-z0-9_]+)""", RegexOption.MULTILINE)

        private val CALL_RE = Regex("""\b(?:(?<receiver>[A-Za-z_][A-Za-z0-9_]*)(?:::|\.))?(?<callee>[A-Za-z_][A-Za-z0-9_]{2,})\s*\(""")
        private val CALL_STOPWORDS = setOf(
            "if", "for", "while", "switch", "when", "return", "catch", "super", "this", "self",
            "print", "println", "len", "str", "int", "float", "bool", "listOf", "mapOf", "setOf",
            "require", "check", "match", "throw", "yield", "guard", "case", "typeof"
        )

        fun supportedLanguage(ext: String): String = EXT_MAP[ext.lowercase()] ?: ""

        fun extractFile(
            projectId: String,
            relPath: String,
            sourceText: String,
            knownModules: List<String> = emptyList()
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val ext = if (relPath.contains('.')) "." + relPath.substringAfterLast('.') else ""
            val lang = supportedLanguage(ext)
            val moduleId = ModuleBoundaryDetector.resolveModuleId(relPath, knownModules)
            val sourceSet = SemioticLayerClassifier.detectSourceSet(relPath)

            return when (lang) {
                "kotlin" -> extractKotlin(projectId, relPath, sourceText, moduleId, sourceSet)
                "swift" -> extractSwift(projectId, relPath, sourceText, moduleId, sourceSet)
                "python" -> extractPython(projectId, relPath, sourceText, moduleId, sourceSet)
                "typescript", "javascript" -> extractTypeScript(projectId, relPath, sourceText, moduleId, sourceSet)
                "rust" -> extractRust(projectId, relPath, sourceText, moduleId, sourceSet)
                "cpp" -> extractCpp(projectId, relPath, sourceText, moduleId, sourceSet)
                else -> Triple(emptyList(), emptyList(), 0)
            }
        }

        private fun findBlockEndLine(lines: List<String>, startLine: Int): Int {
            var depth = 0
            var started = false
            for (idx in (startLine - 1) until lines.size) {
                val line = lines[idx]
                for (c in line) {
                    if (c == '{') { depth++; started = true }
                    else if (c == '}') {
                        depth--
                        if (started && depth <= 0) return idx + 1
                    }
                }
            }
            return minOf(lines.size, startLine + 40)
        }

        private fun extractKotlin(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val pkgMatch = KT_PKG_RE.find(sourceText)
            val pkgName = pkgMatch?.groupValues?.get(1) ?: relPath.replace('/', '.')
            val imports = KT_IMP_RE.findAll(sourceText).map { match ->
                Pair(match.groupValues[1], sourceText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in KT_DECL_RE.findAll(sourceText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val annotations = match.groups["annotations"]?.value ?: ""
                val modifiers = match.groups["modifiers"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "class"
                val name = match.groups["name"]?.value ?: continue

                val isTopLevel = indent.isEmpty()
                if (isTopLevel && "private" !in modifiers.split(" ")) topLevelCount++

                val startLine = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine)
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name

                val modTokens = modifiers.split(" ").toSet()
                val annTokens = Regex("""@([A-Za-z0-9_]+)""").findAll(annotations).map { it.groupValues[1] }.toList()
                val kind = when {
                    "expect" in modTokens -> "expect_decl"
                    "actual" in modTokens -> "actual_decl"
                    "Composable" in annTokens -> "composable"
                    kw == "fun" -> "function"
                    else -> kw
                }

                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name, annTokens)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$pkgName.$name", kind = kind, signature = sigLine,
                        startLine = startLine, endLine = endLine, moduleId = moduleId,
                        sourceSet = sourceSet, semioticLayer = sem, archLayer = arch, isTestDouble = isTd
                    )
                )

                for ((impTarget, impLine) in imports) {
                    edges.add(GraphEdge(
                        edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                        sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                        lineNumber = impLine
                    ))
                }

                if (":" in sigLine && kw in listOf("class", "interface", "object")) {
                    val superPart = sigLine.substringAfter(":").substringBefore("{")
                    for (st in superPart.split(",")) {
                        val clean = st.trim().substringBefore("(").substringBefore("<").trim()
                        if (clean.isNotEmpty() && clean[0].isUpperCase()) {
                            val edgeType = if ("(" in st) "INHERITS" else "IMPLEMENTS"
                            edges.add(GraphEdge(
                                edgeId = "$nodeId:sup:$clean:$startLine", projectId = projectId,
                                sourceNodeId = nodeId, targetNodeId = "sym:$clean", edgeType = edgeType,
                                lineNumber = startLine
                            ))
                        }
                    }
                }

                val body = lines.subList(startLine - 1, minOf(lines.size, endLine)).joinToString("\n")
                for (callMatch in CALL_RE.findAll(body)) {
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    if (callee != name && callee !in CALL_STOPWORDS) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:call:$callee:$startLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = startLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractSwift(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val imports = SWIFT_IMP_RE.findAll(sourceText).map { match ->
                Pair(match.groupValues[1], sourceText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in SWIFT_DECL_RE.findAll(sourceText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "class"
                val name = match.groups["name"]?.value ?: continue

                if (indent.isEmpty()) topLevelCount++
                val startLine = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine)
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$relPath:$name", kind = if (kw == "func") "function" else kw,
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd
                    )
                )

                for ((impTarget, impLine) in imports) {
                    edges.add(GraphEdge(
                        edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                        sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                        lineNumber = impLine
                    ))
                }

                val body = lines.subList(startLine - 1, minOf(lines.size, endLine)).joinToString("\n")
                for (callMatch in CALL_RE.findAll(body)) {
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    if (callee != name && callee !in CALL_STOPWORDS) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:call:$callee:$startLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = startLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractTypeScript(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val imports = mutableListOf<Pair<String, Int>>()
            for (m in TS_IMP_RE.findAll(sourceText)) {
                val named = m.groupValues[1].ifEmpty { m.groupValues[2] }
                val lineNo = sourceText.substring(0, m.range.first).count { it == '\n' } + 1
                for (sym in named.split(",")) {
                    val clean = sym.trim().substringBefore(" as ").trim()
                    if (clean.isNotEmpty()) imports.add(Pair(clean, lineNo))
                }
            }

            var topLevelCount = 0
            for (match in TS_DECL_RE.findAll(sourceText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "class"
                val name = match.groups["name"]?.value ?: continue

                if (indent.isEmpty()) topLevelCount++
                val startLine = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine)
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$relPath:$name", kind = if (kw in listOf("function", "const")) "function" else "class",
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd
                    )
                )

                for ((impTarget, impLine) in imports) {
                    edges.add(GraphEdge(
                        edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                        sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                        lineNumber = impLine
                    ))
                }

                val body = lines.subList(startLine - 1, minOf(lines.size, endLine)).joinToString("\n")
                for (callMatch in CALL_RE.findAll(body)) {
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    if (callee != name && callee !in CALL_STOPWORDS) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:call:$callee:$startLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = startLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractRust(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val imports = RS_USE_RE.findAll(sourceText).map { match ->
                val sym = match.groupValues[1].substringAfterLast("::")
                Pair(sym, sourceText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in RS_DECL_RE.findAll(sourceText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "struct"
                val name = match.groups["name"]?.value ?: continue

                if (indent.isEmpty()) topLevelCount++
                val startLine = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine)
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$relPath:$name", kind = if (kw == "fn") "function" else "class",
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd
                    )
                )

                for ((impTarget, impLine) in imports) {
                    edges.add(GraphEdge(
                        edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                        sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                        lineNumber = impLine
                    ))
                }

                val body = lines.subList(startLine - 1, minOf(lines.size, endLine)).joinToString("\n")
                for (callMatch in CALL_RE.findAll(body)) {
                    val receiver = callMatch.groups["receiver"]?.value
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    val targetSym = if (callee == "new" && !receiver.isNullOrEmpty()) receiver else callee
                    if (targetSym != name && targetSym !in CALL_STOPWORDS) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:call:$targetSym:$startLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$targetSym", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = startLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractCpp(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val imports = CPP_INC_RE.findAll(sourceText).map { match ->
                val header = match.groupValues[1].substringAfterLast('/')
                Pair(header, sourceText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in CPP_DECL_RE.findAll(sourceText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "class"
                val name = match.groups["name"]?.value ?: continue

                if (indent.isEmpty()) topLevelCount++
                val startLine = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine)
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$relPath:$name", kind = "class",
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd
                    )
                )

                for ((impTarget, impLine) in imports) {
                    edges.add(GraphEdge(
                        edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                        sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                        lineNumber = impLine
                    ))
                }

                val body = lines.subList(startLine - 1, minOf(lines.size, endLine)).joinToString("\n")
                for (callMatch in CALL_RE.findAll(body)) {
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    if (callee != name && callee !in CALL_STOPWORDS) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:call:$callee:$startLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = startLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractPython(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()

            val imports = PY_IMP_RE.findAll(sourceText).map { match ->
                val mod = match.groupValues[1].ifEmpty { match.groupValues[2] }
                Pair(mod, sourceText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in PY_DECL_RE.findAll(sourceText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "def"
                val name = match.groups["name"]?.value ?: continue

                if (name.startsWith("_")) continue
                val isTopLevel = indent.isEmpty()
                if (isTopLevel) topLevelCount++

                val startLine = sourceText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = minOf(lines.size, startLine + 30)
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
                val nodeId = "$fileId#$name"

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = "$relPath:$name", kind = if ("class" in kw) "class" else "function",
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd
                    )
                )

                for ((impTarget, impLine) in imports) {
                    edges.add(GraphEdge(
                        edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                        sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                        lineNumber = impLine
                    ))
                }

                val body = lines.subList(startLine - 1, minOf(lines.size, endLine)).joinToString("\n")
                for (callMatch in CALL_RE.findAll(body)) {
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    if (callee != name && callee !in CALL_STOPWORDS && !callee.startsWith("_")) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:call:$callee:$startLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = startLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        fun resolveCrossFileEdges(
            projectId: String,
            nodes: List<GraphNode>,
            rawEdges: List<GraphEdge>
        ): List<GraphEdge> {
            val byName = mutableMapOf<String, MutableList<GraphNode>>()
            val byQName = mutableMapOf<String, GraphNode>()
            for (node in nodes) {
                if (node.projectId != projectId) continue
                byName.getOrPut(node.name) { mutableListOf() }.add(node)
                byQName[node.qualifiedName] = node
            }

            val resolved = mutableListOf<GraphEdge>()
            val seen = mutableSetOf<String>()

            for (edge in rawEdges) {
                var targetId = edge.targetNodeId
                var conf = edge.confidence

                if (targetId.startsWith("sym:")) {
                    val sym = targetId.removePrefix("sym:")
                    val exact = byQName[sym]
                    if (exact != null) {
                        targetId = exact.nodeId
                    } else {
                        val shortSym = sym.substringAfterLast('.').substringAfterLast("::")
                        val candidates = byName[shortSym]
                        if (candidates.isNullOrEmpty()) continue
                        targetId = candidates[0].nodeId
                        if (candidates.size > 1 && edge.provenance == "INFERRED_CALL") {
                            conf = minOf(conf, 0.75)
                        }
                    }
                }

                if (targetId == edge.sourceNodeId) continue
                val dedupeKey = "${edge.edgeId}->$targetId"
                if (!seen.add(dedupeKey)) continue

                resolved.add(
                    GraphEdge(
                        edgeId = dedupeKey,
                        projectId = edge.projectId,
                        sourceNodeId = edge.sourceNodeId,
                        targetNodeId = targetId,
                        edgeType = edge.edgeType,
                        provenance = edge.provenance,
                        confidence = conf,
                        lineNumber = edge.lineNumber
                    )
                )
            }
            return resolved
        }
    }
}
