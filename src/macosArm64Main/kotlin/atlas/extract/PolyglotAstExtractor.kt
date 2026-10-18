package atlas.extract

import atlas.model.ComplexityVector
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
        private val KT_IMP_RE = Regex("""^\s*import\s+([a-zA-Z0-9_.*]+)""", RegexOption.MULTILINE)
        private val KT_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?<annotations>(?:@[A-Za-z0-9_]+(?:\((?:[^()]|\([^()]*\))*\))?\s*)*)""" +
            """(?<modifiers>(?:(?:public|internal|private|protected|open|abstract|sealed|data|value|inline|suspend|expect|actual|override|companion|fun|final|inner|enum|annotation|operator|infix|tailrec|external)\s+)*)""" +
            """(?<kw>class|interface|object|fun|typealias)(?:\s+(?:[A-Za-z0-9_<>?,.\t ]+\.)?(?<name>[A-Za-z0-9_]+))?""",
            RegexOption.MULTILINE
        )

        private val SWIFT_IMP_RE = Regex("""^\s*(?:@\w+\s+)?(?:public\s+|internal\s+)?import\s+([a-zA-Z0-9_.]+)""", RegexOption.MULTILINE)
        private val SWIFT_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|fileprivate|internal|open|final|actor|indirect|nonisolated|isolated|override|static|mutating|class|convenience|required|lazy|weak|unowned)\s+)*""" +
            """(?<kw>struct|class|protocol|actor|enum|func|extension)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )

        private val TS_IMP_RE = Regex("""import\s+(?:type\s+)?(?:(?:\{([^}]+)\})|([A-Za-z0-9_]+))\s+from\s+['"]([^'"]+)['"]""")
        private val TS_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:export\s+)?(?:default\s+)?(?:async\s+)?(?<kw>class|interface|type|function|const\s+enum|enum|const|let|var)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )
        private val TS_METHOD_DECL_RE = Regex(
            """^(?<indent>[ \t]+)(?:(?:public|private|protected|static|async|override|readonly|abstract)\s+)*(?:(?<getter>get|set)\s+)?(?<name>[A-Za-z0-9_$]+)\s*(?:<[^>]+>)?\s*\(""",
            RegexOption.MULTILINE
        )

        private val RS_USE_RE = Regex("""^\s*(?:pub(?:\([^)]*\))?\s+)?use\s+([a-zA-Z0-9_:*]+)""", RegexOption.MULTILINE)
        private val RS_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?<kw>struct|enum|trait|fn|type|mod)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )

        data class ScopeEntry(val name: String, val startLine: Int, val endLine: Int, val kind: String = "")

        private val CPP_INC_RE = Regex("""^\s*#include\s+[<"]([^>"]+)[>"]""", RegexOption.MULTILINE)
        private val CPP_DECL_RE = Regex(
            """^(?<indent>[ \t]*)(?:template\s*<[^>]*>\s*)?(?<kw>class|struct|namespace|enum(?:\s+class)?)\s+(?<name>[A-Za-z0-9_]+)""",
            RegexOption.MULTILINE
        )
        private val CPP_FUNC_RE = Regex(
            """^(?<indent>[ \t]*)(?:template\s*<[^>]*>\s*)?(?:(?:inline|static|virtual|explicit|constexpr|extern|friend|const|unsigned|signed|typename)\s+)*(?:[A-Za-z_][A-Za-z0-9_:<>*,&\s]*?)\b(?<name>(?:[A-Za-z_][A-Za-z0-9_]*::)?[A-Za-z_][A-Za-z0-9_]*)\s*\([^;{}]*\)\s*(?:const\s*)?(?:noexcept\s*)?(?:override\s*)?\{""",
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

        const val EXTRACTOR_VERSION: String = "1.4.0"

        private val INFIX_CALL_RE = Regex(
            """(?<=\s|[)\]a-zA-Z0-9_])\s+(?<callee>[a-zA-Z_][a-zA-Z0-9_]{1,})\s+(?=[A-Za-z0-9_"'(\[])"""
        )
        private val INFIX_STOPWORDS = setOf(
            "val", "var", "fun", "class", "interface", "object", "package", "import",
            "return", "throw", "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
            "in", "is", "as", "by", "where", "get", "set", "constructor", "init", "out", "super",
            "this", "typealias", "companion", "enum", "sealed", "open", "abstract", "data", "value",
            "inline", "noinline", "crossinline", "suspend", "expect", "actual", "const", "lateinit",
            "override", "private", "protected", "public", "internal", "operator", "infix", "tailrec", "external"
        )

        private val LOCAL_VAL_COLON_RE = Regex("""(?:val|var)\s+([A-Za-z0-9_$]+)\s*:\s*([A-Z][A-Za-z0-9_$.]*)""")
        private val LOCAL_VAL_ASSIGN_RE = Regex("""(?:val|var)\s+([A-Za-z0-9_$]+)\s*=\s*([A-Z][A-Za-z0-9_$.]*)\s*(?:<[^>]+>)?\s*\(""")
        private val KOTLIN_PRIMITIVE_TYPES = setOf(
            "String", "Int", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Char", "Any", "Unit", "Nothing"
        )
        private val COMMON_CONSTRUCTORS = setOf(
            "listOf", "setOf", "mapOf", "mutableListOf", "mutableSetOf", "mutableMapOf",
            "buildList", "buildSet", "buildMap", "emptyList", "emptySet", "emptyMap",
            "arrayOf", "byteArrayOf", "intArrayOf"
        )

        private fun scoreReceiverCandidate(
            cand: GraphNode,
            receiver: String?,
            srcFileId: String,
            srcNode: GraphNode?
        ): Double {
            if (receiver.isNullOrBlank()) return 0.0
            val clean = receiver.trim()

            val candSimpleName = cand.fileId.substringAfterLast('/').substringBeforeLast('.')
            val qn = cand.qualifiedName

            if (clean == "this") {
                return if (cand.fileId == srcFileId) 100.0 else 0.0
            }
            if (clean == "super") {
                return if (cand.fileId == srcFileId) 90.0 else 0.0
            }

            if (candSimpleName.equals(clean, ignoreCase = true) ||
                qn.contains(".$clean.", ignoreCase = true) ||
                qn.endsWith(".$clean", ignoreCase = true) ||
                cand.name.equals(clean, ignoreCase = true)
            ) {
                return 100.0
            }

            if (qn.contains(clean, ignoreCase = true) || cand.signature.contains(clean, ignoreCase = true)) {
                return 60.0
            }

            val recTokens = NameSegmentTokenizer.tokenize(clean).toSet()
            if (recTokens.isEmpty()) return 0.0

            val candTokens = (
                NameSegmentTokenizer.tokenize(candSimpleName) +
                NameSegmentTokenizer.tokenize(cand.name) +
                NameSegmentTokenizer.tokenize(qn)
            ).toSet()

            val matchCount = recTokens.count { it in candTokens }
            if (matchCount == 0) return 0.0

            val fraction = matchCount.toDouble() / recTokens.size
            return fraction * 40.0
        }

        fun supportedLanguage(ext: String): String = EXT_MAP[ext.lowercase()] ?: ""

        /**
         * Replaces comment bodies and string literal contents with spaces while preserving
         * exact string length and all newline (`\n`) offsets so regex match offsets map 1:1
         * to original source lines.
         */
        fun maskCommentsAndStrings(source: String, language: String = "kotlin"): String {
            val out = CharArray(source.length)
            val isPy = language == "python"
            val isRust = language == "rust"
            val isTsJs = language == "typescript" || language == "javascript"
            val isKotlin = language == "kotlin"
            val isSwift = language == "swift"
            var i = 0
            val n = source.length

            fun writeSpaceOrNewline(idx: Int) {
                out[idx] = if (source[idx] == '\n') '\n' else ' '
            }

            while (i < n) {
                val c = source[i]

                // Python line comment
                if (isPy && c == '#') {
                    while (i < n && source[i] != '\n') {
                        out[i] = ' '
                        i++
                    }
                    continue
                }

                // C-style line comment
                if (!isPy && c == '/' && i + 1 < n && source[i + 1] == '/') {
                    while (i < n && source[i] != '\n') {
                        out[i] = ' '
                        i++
                    }
                    continue
                }

                // C-style block comment
                if (!isPy && c == '/' && i + 1 < n && source[i + 1] == '*') {
                    out[i] = ' '
                    out[i + 1] = ' '
                    i += 2
                    while (i < n) {
                        if (source[i] == '*' && i + 1 < n && source[i + 1] == '/') {
                            out[i] = ' '
                            out[i + 1] = ' '
                            i += 2
                            break
                        }
                        writeSpaceOrNewline(i)
                        i++
                    }
                    continue
                }

                // Triple-quoted strings (Kotlin """ or Python """ / ''')
                if (i + 2 < n && (
                        (c == '"' && source[i + 1] == '"' && source[i + 2] == '"') ||
                        (isPy && c == '\'' && source[i + 1] == '\'' && source[i + 2] == '\'')
                    )
                ) {
                    val delim = c
                    out[i] = delim
                    out[i + 1] = ' '
                    out[i + 2] = ' '
                    i += 3
                    while (i < n) {
                        if (i + 2 < n && source[i] == delim && source[i + 1] == delim && source[i + 2] == delim) {
                            out[i] = ' '
                            out[i + 1] = ' '
                            out[i + 2] = delim
                            i += 3
                            break
                        }
                        if (isKotlin && delim == '"' && source[i] == '$' && i + 1 < n && source[i + 1] == '{') {
                            out[i] = ' '
                            out[i + 1] = ' '
                            i += 2
                            var depth = 1
                            while (i < n && depth > 0) {
                                if (source[i] == '{') {
                                    depth++
                                    out[i] = '{'
                                    i++
                                } else if (source[i] == '}') {
                                    depth--
                                    if (depth == 0) out[i] = ' ' else out[i] = '}'
                                    i++
                                } else if (source[i] == '"') {
                                    out[i] = '"'
                                    i++
                                    while (i < n) {
                                        if (source[i] == '\\' && i + 1 < n) {
                                            writeSpaceOrNewline(i); writeSpaceOrNewline(i + 1); i += 2; continue
                                        }
                                        if (source[i] == '"') {
                                            out[i] = '"'; i++; break
                                        }
                                        if (source[i] == '\n') {
                                            out[i] = '\n'; i++; break
                                        }
                                        writeSpaceOrNewline(i)
                                        i++
                                    }
                                } else {
                                    out[i] = source[i]
                                    i++
                                }
                            }
                            continue
                        }
                        if (isSwift && delim == '"' && source[i] == '\\' && i + 1 < n && source[i + 1] == '(') {
                            out[i] = ' '
                            out[i + 1] = ' '
                            i += 2
                            var depth = 1
                            while (i < n && depth > 0) {
                                if (source[i] == '(') {
                                    depth++
                                    out[i] = '('
                                    i++
                                } else if (source[i] == ')') {
                                    depth--
                                    if (depth == 0) out[i] = ' ' else out[i] = ')'
                                    i++
                                } else if (source[i] == '"') {
                                    out[i] = '"'
                                    i++
                                    while (i < n) {
                                        if (source[i] == '\\' && i + 1 < n) {
                                            writeSpaceOrNewline(i); writeSpaceOrNewline(i + 1); i += 2; continue
                                        }
                                        if (source[i] == '"') {
                                            out[i] = '"'; i++; break
                                        }
                                        writeSpaceOrNewline(i)
                                        i++
                                    }
                                } else {
                                    out[i] = source[i]
                                    i++
                                }
                            }
                            continue
                        }
                        writeSpaceOrNewline(i)
                        i++
                    }
                    continue
                }

                // Double-quoted string
                if (c == '"') {
                    out[i] = '"'
                    i++
                    while (i < n) {
                        if (isSwift && source[i] == '\\' && i + 1 < n && source[i + 1] == '(') {
                            out[i] = ' '
                            out[i + 1] = ' '
                            i += 2
                            var depth = 1
                            while (i < n && depth > 0) {
                                if (source[i] == '(') {
                                    depth++
                                    out[i] = '('
                                    i++
                                } else if (source[i] == ')') {
                                    depth--
                                    if (depth == 0) out[i] = ' ' else out[i] = ')'
                                    i++
                                } else if (source[i] == '"') {
                                    out[i] = '"'
                                    i++
                                    while (i < n) {
                                        if (source[i] == '\\' && i + 1 < n) {
                                            writeSpaceOrNewline(i); writeSpaceOrNewline(i + 1); i += 2; continue
                                        }
                                        if (source[i] == '"') {
                                            out[i] = '"'; i++; break
                                        }
                                        writeSpaceOrNewline(i)
                                        i++
                                    }
                                } else {
                                    out[i] = source[i]
                                    i++
                                }
                            }
                            continue
                        }
                        if (source[i] == '\\' && i + 1 < n) {
                            writeSpaceOrNewline(i)
                            writeSpaceOrNewline(i + 1)
                            i += 2
                            continue
                        }
                        if (isKotlin && source[i] == '$' && i + 1 < n && source[i + 1] == '{') {
                            out[i] = ' '
                            out[i + 1] = ' '
                            i += 2
                            var depth = 1
                            while (i < n && depth > 0) {
                                if (source[i] == '{') {
                                    depth++
                                    out[i] = '{'
                                    i++
                                } else if (source[i] == '}') {
                                    depth--
                                    if (depth == 0) out[i] = ' ' else out[i] = '}'
                                    i++
                                } else if (source[i] == '"') {
                                    out[i] = '"'
                                    i++
                                    while (i < n) {
                                        if (source[i] == '\\' && i + 1 < n) {
                                            writeSpaceOrNewline(i); writeSpaceOrNewline(i + 1); i += 2; continue
                                        }
                                        if (source[i] == '"') {
                                            out[i] = '"'; i++; break
                                        }
                                        if (source[i] == '\n') {
                                            out[i] = '\n'; i++; break
                                        }
                                        writeSpaceOrNewline(i)
                                        i++
                                    }
                                } else {
                                    out[i] = source[i]
                                    i++
                                }
                            }
                            continue
                        }
                        if (source[i] == '"') {
                            out[i] = '"'
                            i++
                            break
                        }
                        if (!isRust && source[i] == '\n') {
                            out[i] = '\n'
                            i++
                            break
                        }
                        writeSpaceOrNewline(i)
                        i++
                    }
                    continue
                }

                // Single-quoted string or char literal (in Rust, avoid eating lifetimes like `'a`)
                if (c == '\'') {
                    if (isRust) {
                        if (i + 2 < n && source[i + 1] != '\\' && source[i + 2] == '\'') {
                            out[i] = '\''
                            out[i + 1] = ' '
                            out[i + 2] = '\''
                            i += 3
                            continue
                        } else if (i + 3 < n && source[i + 1] == '\\' && source[i + 3] == '\'') {
                            out[i] = '\''
                            out[i + 1] = ' '
                            out[i + 2] = ' '
                            out[i + 3] = '\''
                            i += 4
                            continue
                        } else {
                            out[i] = c
                            i++
                            continue
                        }
                    } else {
                        out[i] = '\''
                        i++
                        while (i < n) {
                            if (source[i] == '\\' && i + 1 < n) {
                                writeSpaceOrNewline(i)
                                writeSpaceOrNewline(i + 1)
                                i += 2
                                continue
                            }
                            if (source[i] == '\'') {
                                out[i] = '\''
                                i++
                                break
                            }
                            if (source[i] == '\n') {
                                out[i] = '\n'
                                i++
                                break
                            }
                            writeSpaceOrNewline(i)
                            i++
                        }
                        continue
                    }
                }

                // Backtick template literal in JS/TS
                if (isTsJs && c == '`') {
                    out[i] = '`'
                    i++
                    while (i < n) {
                        if (source[i] == '\\' && i + 1 < n) {
                            writeSpaceOrNewline(i)
                            writeSpaceOrNewline(i + 1)
                            i += 2
                            continue
                        }
                        if (source[i] == '$' && i + 1 < n && source[i + 1] == '{') {
                            out[i] = ' '
                            out[i + 1] = ' '
                            i += 2
                            var depth = 1
                            while (i < n && depth > 0) {
                                if (source[i] == '{') {
                                    depth++
                                    out[i] = '{'
                                    i++
                                } else if (source[i] == '}') {
                                    depth--
                                    if (depth == 0) out[i] = ' ' else out[i] = '}'
                                    i++
                                } else if (source[i] == '"' || source[i] == '\'') {
                                    val strQuote = source[i]
                                    out[i] = strQuote
                                    i++
                                    while (i < n) {
                                        if (source[i] == '\\' && i + 1 < n) {
                                            writeSpaceOrNewline(i); writeSpaceOrNewline(i + 1); i += 2; continue
                                        }
                                        if (source[i] == strQuote) {
                                            out[i] = strQuote; i++; break
                                        }
                                        if (source[i] == '\n') {
                                            out[i] = '\n'; i++; break
                                        }
                                        writeSpaceOrNewline(i)
                                        i++
                                    }
                                } else if (source[i] == '`') {
                                    out[i] = '`'
                                    i++
                                    var innerDepth = 0
                                    while (i < n) {
                                        if (source[i] == '\\' && i + 1 < n) {
                                            writeSpaceOrNewline(i); writeSpaceOrNewline(i + 1); i += 2; continue
                                        }
                                        if (source[i] == '$' && i + 1 < n && source[i + 1] == '{') {
                                            out[i] = ' '; out[i + 1] = ' '; i += 2
                                            innerDepth++
                                            while (i < n && innerDepth > 0) {
                                                if (source[i] == '{') { innerDepth++; out[i] = '{'; i++ }
                                                else if (source[i] == '}') { innerDepth--; out[i] = if (innerDepth == 0) ' ' else '}'; i++ }
                                                else { out[i] = source[i]; i++ }
                                            }
                                            continue
                                        }
                                        if (source[i] == '`') {
                                            out[i] = '`'; i++; break
                                        }
                                        writeSpaceOrNewline(i)
                                        i++
                                    }
                                } else {
                                    out[i] = source[i]
                                    i++
                                }
                            }
                            continue
                        }
                        if (source[i] == '`') {
                            out[i] = '`'
                            i++
                            break
                        }
                        writeSpaceOrNewline(i)
                        i++
                    }
                    continue
                }

                out[i] = c
                i++
            }
            return out.concatToString()
        }

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

            val maskedText = maskCommentsAndStrings(sourceText, lang)
            val (baseNodes, baseEdges, topLevelCount) = when (lang) {
                "kotlin" -> extractKotlin(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                "swift" -> extractSwift(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                "python" -> extractPython(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                "typescript", "javascript" -> extractTypeScript(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                "rust" -> extractRust(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                "cpp" -> extractCpp(projectId, relPath, sourceText, moduleId, sourceSet, maskedText)
                else -> return Triple(emptyList(), emptyList(), 0)
            }

            val extractedUrls = UrlTargetResolver.extractCodeRoutesAndUrls(sourceText, maskedText, lang)
            if (extractedUrls.isEmpty()) {
                return Triple(baseNodes, baseEdges, topLevelCount)
            }

            val fileId = "$projectId:$relPath"
            val finalNodes = baseNodes.toMutableList()
            val finalEdges = baseEdges.toMutableList()
            var fileScopeNode: GraphNode? = finalNodes.firstOrNull { it.nodeId == "$fileId#file" }

            for (item in extractedUrls) {
                val directEnclosing = baseNodes
                    .filter { it.kind != "file_scope" && item.lineNumber in it.startLine..it.endLine }
                    .minWithOrNull(compareBy<GraphNode> { it.endLine - it.startLine }.thenByDescending { it.startLine })
                val annotatedChild = if (directEnclosing == null || directEnclosing.kind in setOf("class", "interface", "object")) {
                    baseNodes
                        .filter {
                            it.kind in setOf("function", "method", "composable") &&
                                it.startLine in (item.lineNumber + 1)..(item.lineNumber + 5) &&
                                (directEnclosing == null || (it.startLine >= directEnclosing.startLine && it.endLine <= directEnclosing.endLine))
                        }
                        .minByOrNull { it.startLine - item.lineNumber }
                } else null

                val sourceNode: GraphNode = annotatedChild ?: directEnclosing ?: (fileScopeNode ?: run {
                    val (semLayer, archLayer, _) = SemioticLayerClassifier.classify(relPath)
                    val totalLines = sourceText.lines().size.coerceAtLeast(1)
                    val created = GraphNode(
                        nodeId = "$fileId#file", projectId = projectId, fileId = fileId,
                        name = relPath.substringAfterLast('/'), qualifiedName = relPath,
                        kind = "file_scope", signature = relPath, startLine = 1, endLine = totalLines,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = semLayer,
                        archLayer = archLayer, isTestDouble = false, isTopLevel = false
                    )
                    fileScopeNode = created
                    finalNodes.add(created)
                    created
                })

                val targetId = if (item.isHttpEndpointRoute) {
                    "$projectId:endpoint#${item.canonicalRouteOrUrl}"
                } else {
                    "$projectId:url_ref#${item.canonicalRouteOrUrl}"
                }
                finalEdges.add(
                    GraphEdge(
                        edgeId = "${sourceNode.nodeId}:url:${item.edgeType.lowercase()}:${item.canonicalRouteOrUrl}:${item.lineNumber}",
                        projectId = projectId, sourceNodeId = sourceNode.nodeId, targetNodeId = targetId,
                        edgeType = item.edgeType, provenance = "EXTRACTED_URL", confidence = 1.0, lineNumber = item.lineNumber
                    )
                )
            }

            return Triple(finalNodes, finalEdges, topLevelCount)
        }

        private class LexerState {
            var inBlockComment = false
            var inTripleQuote = false
            var inBacktick = false
        }

        private fun cleanLine(line: String, state: LexerState = LexerState()): String {
            val sb = StringBuilder()
            var i = 0
            while (i < line.length) {
                if (state.inBlockComment) {
                    if (line[i] == '*' && i + 1 < line.length && line[i + 1] == '/') {
                        state.inBlockComment = false
                        i += 2
                    } else {
                        i++
                    }
                    continue
                }
                if (state.inTripleQuote) {
                    if (line[i] == '"' && i + 2 < line.length && line[i + 1] == '"' && line[i + 2] == '"') {
                        state.inTripleQuote = false
                        i += 3
                    } else {
                        i++
                    }
                    continue
                }
                if (state.inBacktick) {
                    if (line[i] == '\\' && i + 1 < line.length) {
                        i += 2
                    } else if (line[i] == '`') {
                        state.inBacktick = false
                        i++
                    } else {
                        i++
                    }
                    continue
                }

                // Check for block comment start
                if (line[i] == '/' && i + 1 < line.length && line[i + 1] == '*') {
                    state.inBlockComment = true
                    i += 2
                    continue
                }
                // Check for line comment start
                if (line[i] == '/' && i + 1 < line.length && line[i + 1] == '/') {
                    break
                }
                // Check for triple quote start
                if (line[i] == '"' && i + 2 < line.length && line[i + 1] == '"' && line[i + 2] == '"') {
                    state.inTripleQuote = true
                    i += 3
                    continue
                }
                // Check for single-line double-quoted string
                if (line[i] == '"') {
                    i++
                    while (i < line.length && line[i] != '"') {
                        if (line[i] == '\\' && i + 1 < line.length) i += 2 else i++
                    }
                    if (i < line.length && line[i] == '"') i++
                    continue
                }
                // Check for single-line single-quoted string / char
                if (line[i] == '\'') {
                    i++
                    while (i < line.length && line[i] != '\'') {
                        if (line[i] == '\\' && i + 1 < line.length) i += 2 else i++
                    }
                    if (i < line.length && line[i] == '\'') i++
                    continue
                }
                // Check for backtick (JS/TS template literal or Kotlin identifier)
                if (line[i] == '`') {
                    i++
                    var closed = false
                    while (i < line.length) {
                        if (line[i] == '\\' && i + 1 < line.length) {
                            i += 2
                            continue
                        }
                        if (line[i] == '`') {
                            closed = true
                            i++
                            break
                        }
                        i++
                    }
                    if (!closed) {
                        state.inBacktick = true
                    }
                    continue
                }

                sb.append(line[i])
                i++
            }
            return sb.toString()
        }

        private fun cleanLinesFrom(lines: List<String>, startIdx: Int): List<String> {
            val state = LexerState()
            val result = ArrayList<String>(lines.size - startIdx)
            for (idx in startIdx until lines.size) {
                result.add(cleanLine(lines[idx], state))
            }
            return result
        }

        private fun isNewDeclarationStart(trimmed: String): Boolean {
            if (trimmed.startsWith("@") || trimmed.startsWith("#[")) return true
            val words = trimmed.split(' ', '\t', '(', ':', '<')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (words.isEmpty()) return false

            val declKeywords = setOf(
                "fun", "class", "interface", "object", "typealias", "val", "var",
                "def",
                "func", "struct", "protocol", "actor", "enum", "extension", "let",
                "function", "const", "type",
                "fn", "trait", "mod", "impl",
                "namespace"
            )
            val modifierKeywords = setOf(
                "public", "private", "protected", "internal", "open", "abstract", "sealed",
                "override", "expect", "actual", "inline", "suspend", "data", "value",
                "export", "default", "declare", "readonly", "static", "final", "mutating",
                "fileprivate", "nonisolated", "isolated", "indirect",
                "pub", "async", "unsafe", "extern", "virtual", "constexpr"
            )

            val firstWord = words.first()
            if (firstWord in declKeywords) return true
            if (firstWord in modifierKeywords) {
                if (words.size == 1) return true
                for (w in words.drop(1)) {
                    if (w in declKeywords) return true
                    if (w !in modifierKeywords) break
                }
            }
            return false
        }

        private fun hasDeclarationKeyword(str: String): Boolean {
            val tokens = str.split(' ', '\t', '(', ':', '<', '=')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val kws = setOf(
                "class", "interface", "object", "fun", "typealias",
                "func", "struct", "protocol", "actor", "enum", "extension",
                "function", "const", "let", "var", "type",
                "fn", "trait", "mod", "impl",
                "def", "namespace"
            )
            return tokens.any { it in kws }
        }

        private fun findBlockEndLine(
            lines: List<String>,
            startLine: Int,
            precomputedCleanLines: List<String>? = null
        ): Int {
            if (startLine > lines.size) return lines.size

            val startIdx = startLine - 1
            val cleanLines = precomputedCleanLines ?: cleanLinesFrom(lines, 0)

            var depth = 0
            var started = false
            var hasEquals = false
            var seenKeyword = false
            var parenDepth = 0
            var bracketDepth = 0

            for (idx in startIdx until cleanLines.size) {
                val clean = cleanLines[idx]

                if (!seenKeyword) {
                    if (hasDeclarationKeyword(clean)) {
                        seenKeyword = true
                    }
                }

                for (i in clean.indices) {
                    val c = clean[i]
                    when (c) {
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '[' -> bracketDepth++
                        ']' -> if (bracketDepth > 0) bracketDepth--
                        '=' -> {
                            if (!started && parenDepth == 0 && bracketDepth == 0) {
                                val prev = if (i > 0) clean[i - 1] else ' '
                                val next = if (i + 1 < clean.length) clean[i + 1] else ' '
                                val isComparison = prev in listOf('=', '!', '<', '>') || next == '='
                                if (!isComparison) {
                                    hasEquals = true
                                }
                            }
                        }
                        '{' -> {
                            if (parenDepth == 0 && bracketDepth == 0) {
                                depth++
                                started = true
                            } else if (started) {
                                depth++
                            }
                        }
                        '}' -> {
                            if (started) {
                                depth--
                            }
                        }
                    }
                }

                if (started && depth <= 0) {
                    if (!hasEquals) {
                        return idx + 1
                    }
                    val trimmed = clean.trimEnd()
                    val isContinuation = trimmed.endsWith("=") ||
                        trimmed.endsWith("+") ||
                        trimmed.endsWith("-") ||
                        trimmed.endsWith("*") ||
                        trimmed.endsWith("/") ||
                        trimmed.endsWith("%") ||
                        trimmed.endsWith("&&") ||
                        trimmed.endsWith("||") ||
                        trimmed.endsWith("?:") ||
                        trimmed.endsWith(".") ||
                        trimmed.endsWith("?.") ||
                        trimmed.endsWith("::") ||
                        trimmed.endsWith(",") ||
                        trimmed.endsWith("->") ||
                        trimmed.endsWith(":") ||
                        trimmed.endsWith("&") ||
                        trimmed.endsWith("|")

                    var lookAhead = idx + 1
                    while (lookAhead < cleanLines.size && cleanLines[lookAhead].trim().isEmpty()) {
                        lookAhead++
                    }

                    if (lookAhead >= cleanLines.size) {
                        return idx + 1
                    }

                    val nextClean = cleanLines[lookAhead]
                    val nextTrimmed = nextClean.trim()
                    val nextIsContinuation = nextTrimmed.startsWith(".") ||
                        nextTrimmed.startsWith("?.") ||
                        nextTrimmed.startsWith("?:") ||
                        nextTrimmed.startsWith("->") ||
                        nextTrimmed.startsWith("+") ||
                        nextTrimmed.startsWith("-") ||
                        nextTrimmed.startsWith("*") ||
                        nextTrimmed.startsWith("/") ||
                        nextTrimmed.startsWith("&&") ||
                        nextTrimmed.startsWith("||") ||
                        nextTrimmed.startsWith("&") ||
                        nextTrimmed.startsWith("|")

                    if (!isContinuation && !nextIsContinuation) {
                        return idx + 1
                    }
                    continue
                }

                if (started) {
                    continue
                }

                if (seenKeyword && parenDepth == 0 && bracketDepth == 0) {
                    val trimmed = clean.trimEnd()
                    if (trimmed.endsWith(";")) {
                        return idx + 1
                    }

                    val isContinuation = trimmed.endsWith("=") ||
                        trimmed.endsWith("+") ||
                        trimmed.endsWith("-") ||
                        trimmed.endsWith("*") ||
                        trimmed.endsWith("/") ||
                        trimmed.endsWith("%") ||
                        trimmed.endsWith("&&") ||
                        trimmed.endsWith("||") ||
                        trimmed.endsWith("?:") ||
                        trimmed.endsWith(".") ||
                        trimmed.endsWith("?.") ||
                        trimmed.endsWith("::") ||
                        trimmed.endsWith(",") ||
                        trimmed.endsWith("->") ||
                        trimmed.endsWith(":") ||
                        trimmed.endsWith("&") ||
                        trimmed.endsWith("|")

                    var lookAhead = idx + 1
                    while (lookAhead < cleanLines.size && cleanLines[lookAhead].trim().isEmpty()) {
                        lookAhead++
                    }

                    if (lookAhead >= cleanLines.size) {
                        return idx + 1
                    }

                    val nextClean = cleanLines[lookAhead]
                    val nextTrimmed = nextClean.trim()

                    val nextIsContinuation = nextTrimmed.startsWith(".") ||
                        nextTrimmed.startsWith("?.") ||
                        nextTrimmed.startsWith("?:") ||
                        nextTrimmed.startsWith("->") ||
                        nextTrimmed.startsWith("+") ||
                        nextTrimmed.startsWith("-") ||
                        nextTrimmed.startsWith("*") ||
                        nextTrimmed.startsWith("/") ||
                        nextTrimmed.startsWith("&&") ||
                        nextTrimmed.startsWith("||") ||
                        nextTrimmed.startsWith("&") ||
                        nextTrimmed.startsWith("|")

                    if (!isContinuation && !nextIsContinuation) {
                        if (nextTrimmed.startsWith("{")) {
                            continue
                        }

                        if (hasEquals) {
                            if (isNewDeclarationStart(nextTrimmed) || nextTrimmed.startsWith("}")) {
                                return idx + 1
                            }
                            val baseIndent = lines[startLine - 1].takeWhile { it == ' ' || it == '\t' }.length
                            val nextRaw = lines[lookAhead]
                            val nextIndent = nextRaw.takeWhile { it == ' ' || it == '\t' }.length
                            if (nextIndent <= baseIndent) {
                                return idx + 1
                            }
                        } else {
                            if (!nextTrimmed.startsWith("=") && !nextTrimmed.startsWith("=>")) {
                                return idx + 1
                            }
                        }
                    }
                }
            }
            return minOf(lines.size, startLine)
        }

        private fun findPythonBlockEndLine(lines: List<String>, maskedLines: List<String>, startLine: Int): Int {
            if (startLine > lines.size) return lines.size
            val startIdx = startLine - 1
            val declLine = lines[startIdx]
            val baseIndent = declLine.takeWhile { it == ' ' || it == '\t' }.length

            var parenDepth = 0
            var bracketDepth = 0
            var braceDepth = 0
            var headerEnded = false
            var inTripleQuote: String? = null
            var lastContentLine = startLine

            for (idx in startIdx until lines.size) {
                val raw = lines[idx]

                if (inTripleQuote != null) {
                    val closePos = raw.indexOf(inTripleQuote)
                    if (closePos >= 0) {
                        inTripleQuote = null
                    }
                    lastContentLine = idx + 1
                    continue
                }

                val masked = if (idx < maskedLines.size) maskedLines[idx] else raw.substringBefore("#")
                val trimmed = masked.trim()
                if (trimmed.isEmpty()) continue

                if (idx > startIdx && headerEnded && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    val indent = raw.takeWhile { it == ' ' || it == '\t' }.length
                    if (indent <= baseIndent && !trimmed.startsWith(")") && !trimmed.startsWith("]") && !trimmed.startsWith("}")) {
                        break
                    }
                }

                val stripped = raw.substringBefore("#")
                var j = 0
                while (j < stripped.length) {
                    if (j + 2 < stripped.length) {
                        val sub3 = stripped.substring(j, j + 3)
                        if (sub3 == "\"\"\"" || sub3 == "'''") {
                            val closeOnSameLine = stripped.indexOf(sub3, j + 3)
                            if (closeOnSameLine >= 0) {
                                j = closeOnSameLine + 3
                                continue
                            } else {
                                inTripleQuote = sub3
                                break
                            }
                        }
                    }
                    j++
                }

                for (i in masked.indices) {
                    when (masked[i]) {
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '[' -> bracketDepth++
                        ']' -> if (bracketDepth > 0) bracketDepth--
                        '{' -> braceDepth++
                        '}' -> if (braceDepth > 0) braceDepth--
                    }
                }
                if (inTripleQuote == null && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && trimmed.endsWith(":")) {
                    headerEnded = true
                }
                lastContentLine = idx + 1
            }
            return lastContentLine
        }

        private fun buildPythonHeaderSignature(lines: List<String>, maskedLines: List<String>, startLine: Int, endLine: Int): String {
            if (startLine > lines.size) return ""
            val safeEnd = minOf(lines.size, endLine)
            val parts = mutableListOf<String>()
            var parenDepth = 0
            var bracketDepth = 0
            for (lineNo in startLine..safeEnd) {
                val raw = lines[lineNo - 1].substringBefore("#").trim()
                val masked = if (lineNo - 1 < maskedLines.size) maskedLines[lineNo - 1].trim() else raw
                if (raw.isNotEmpty()) parts.add(raw)
                for (c in masked) {
                    when (c) {
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '[' -> bracketDepth++
                        ']' -> if (bracketDepth > 0) bracketDepth--
                    }
                }
                if (parenDepth == 0 && bracketDepth == 0 && masked.endsWith(":")) {
                    break
                }
            }
            return parts.joinToString(" ").replace(Regex("""\s+"""), " ")
        }

        private fun maskChildLineRanges(
            lines: List<String>,
            startLine: Int,
            endLine: Int,
            childRanges: List<IntRange>
        ): String {
            val safeEnd = minOf(lines.size, endLine)
            val sb = StringBuilder()
            for (lineNo in startLine..safeEnd) {
                val inChild = childRanges.any { lineNo in it }
                if (!inChild) {
                    sb.append(lines[lineNo - 1])
                }
                if (lineNo < safeEnd) sb.append('\n')
            }
            return sb.toString()
        }

        private fun stripLeadingInlineAnnotations(line: String): String {
            var rest = line.trim()
            val inlineAnnRe = Regex("""^@[A-Za-z0-9_]+(?:\((?:[^()]|\([^()]*\))*\))?\s*""")
            while (true) {
                val m = inlineAnnRe.find(rest) ?: break
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            return rest
        }

        private fun computeComplexityMetrics(
            kind: String,
            startLine: Int,
            endLine: Int,
            maskedLines: List<String>,
            lines: List<String>,
            language: String,
            isComposable: Boolean = false,
            functionName: String? = null
        ): Pair<ComplexityVector, Double> {
            return if (kind in setOf("function", "composable", "method")) {
                val sIdx = (startLine - 1).coerceAtLeast(0)
                val eIdx = endLine.coerceAtMost(maskedLines.size)
                val fMasked = if (sIdx < eIdx) maskedLines.subList(sIdx, eIdx) else emptyList()
                val fRaw = if (sIdx < lines.size) lines.subList(sIdx, minOf(eIdx, lines.size)) else emptyList()
                val vec = ComplexityExtractor.calculate(fMasked, fRaw, language, isComposable, functionName)
                val score = ComplexityExtractor.computeScore(vec)
                Pair(vec, score)
            } else {
                Pair(ComplexityVector(), 100.0)
            }
        }

        private fun extractKotlinSupertypes(
            lines: List<String>,
            startLine: Int,
            endLine: Int
        ): List<Pair<String, String>> {
            val safeEnd = minOf(lines.size, endLine)
            val headerBuilder = StringBuilder()
            for (lineNo in startLine..safeEnd) {
                val line = if (lineNo == startLine) stripLeadingInlineAnnotations(lines[lineNo - 1]) else lines[lineNo - 1]
                val clean = line.substringBefore("//")
                val braceIdx = clean.indexOf('{')
                if (braceIdx >= 0) {
                    headerBuilder.append(clean.substring(0, braceIdx))
                    break
                }
                headerBuilder.append(clean).append(' ')
            }
            val header = headerBuilder.toString()
            var parenDepth = 0
            var angleDepth = 0
            var colonIdx = -1
            for (i in header.indices) {
                when (header[i]) {
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '<' -> angleDepth++
                    '>' -> if (angleDepth > 0) angleDepth--
                    ':' -> if (parenDepth == 0 && angleDepth == 0) {
                        val nextChar = if (i + 1 < header.length) header[i + 1] else ' '
                        val prevChar = if (i > 0) header[i - 1] else ' '
                        if (nextChar != ':' && prevChar != ':') {
                            colonIdx = i
                            break
                        }
                    }
                }
            }
            if (colonIdx < 0) return emptyList()
            val superPart = header.substring(colonIdx + 1)

            // Split top-level comma-separated supertypes (ignoring commas inside (...) or <...>)
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var pDepth = 0
            var aDepth = 0
            for (c in superPart) {
                when (c) {
                    '(' -> { pDepth++; current.append(c) }
                    ')' -> { if (pDepth > 0) pDepth--; current.append(c) }
                    '<' -> { aDepth++; current.append(c) }
                    '>' -> { if (aDepth > 0) aDepth--; current.append(c) }
                    ',' -> if (pDepth == 0 && aDepth == 0) {
                        parts.add(current.toString())
                        current.clear()
                    } else {
                        current.append(c)
                    }
                    else -> current.append(c)
                }
            }
            if (current.isNotEmpty()) parts.add(current.toString())

            val results = mutableListOf<Pair<String, String>>()
            for (st in parts) {
                val trimmed = st.trim()
                val clean = trimmed.substringBefore("(").substringBefore("<").substringBefore(" by ").trim()
                    .substringAfterLast('.')
                if (clean.isNotEmpty() && clean[0].isUpperCase()) {
                    val edgeType = if ("(" in trimmed.substringBefore(" by ")) "INHERITS" else "IMPLEMENTS"
                    results.add(Pair(clean, edgeType))
                }
            }
            return results
        }

        private val NON_DOC_LINE_PREFIXES = listOf(
            "// copyright", "// (c)", "// license", "// spdx-", "// all rights reserved",
            "// mark:", "// todo", "// fixme", "// note:", "// hack:", "// xxx",
            "// nolint", "// clang-format", "// eslint-", "// @ts-", "// ts-ignore",
            "// --", "// ==", "// **", "// ##"
        )

        private val PYTHON_NON_DOC_PREFIXES = listOf(
            "# copyright", "# (c)", "# license", "# spdx-", "# all rights reserved",
            "# todo", "# fixme", "# note:", "# type:", "# noqa", "# pylint:", "# flake8:",
            "# mypy:", "# pyright:", "# --", "# ==", "# **", "# ##"
        )

        /**
         * Extracts doc comment description (KDoc, Javadoc, JSDoc, Swift/Rust ///, C++ Doxygen)
         * preceding a declaration at [matchStartLine] (1-based line of the declaration or leading annotation).
         */
        fun extractDocDescription(
            lines: List<String>,
            matchStartLine: Int,
            language: String
        ): String? {
            val startIdx = matchStartLine - 1
            if (startIdx <= 0 || startIdx > lines.size) return null

            var cur = startIdx - 1
            var blankCount = 0
            while (cur >= 0) {
                val raw = lines[cur]
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) {
                    blankCount++
                    if (blankCount > 1) break
                    cur--
                    continue
                }
                // Skip single-line annotations or attributes preceding the declaration
                if (isAnnotationOrAttributeLine(trimmed, language)) {
                    blankCount = 0
                    cur--
                    continue
                }
                // Skip multi-line annotations / attributes / templates ending on this line:
                if (trimmed.endsWith(")") || trimmed.endsWith("]") || (language == "cpp" && trimmed.endsWith(">"))) {
                    var pDepth = 0
                    var bDepth = 0
                    var aDepth = 0
                    var scanIdx = cur
                    var matchedAnnoStart = false
                    while (scanIdx >= 0) {
                        val sLine = lines[scanIdx].trim()
                        for (c in sLine) {
                            when (c) {
                                '(' -> pDepth--
                                ')' -> pDepth++
                                '[' -> bDepth--
                                ']' -> bDepth++
                                '<' -> if (language == "cpp") aDepth--
                                '>' -> if (language == "cpp") aDepth++
                            }
                        }
                        if (pDepth <= 0 && bDepth <= 0 && aDepth <= 0) {
                            if (isAnnotationOrAttributeLine(sLine, language)) {
                                matchedAnnoStart = true
                                cur = scanIdx - 1
                                blankCount = 0
                                break
                            }
                        }
                        scanIdx--
                    }
                    if (matchedAnnoStart) continue
                }
                // Block doc comment closing */
                if (trimmed.endsWith("*/")) {
                    return parseBlockDocComment(lines, cur)
                }
                // Rust inner doc comments document the module/crate, not the item
                if (language == "rust" && (trimmed.startsWith("//!") || trimmed.startsWith("/*!"))) {
                    break
                }
                // Triple-slash doc comment ///
                if (trimmed.startsWith("///")) {
                    return parseTripleSlashDocComment(lines, cur)
                }
                // Double-slash doc comment //
                if (trimmed.startsWith("//")) {
                    val low = trimmed.lowercase()
                    if (NON_DOC_LINE_PREFIXES.any { low.startsWith(it) }) {
                        break
                    }
                    return parseDoubleSlashDocComment(lines, cur)
                }
                // Any other code line means no doc comment immediately preceding
                break
            }
            return null
        }

        private fun isAnnotationOrAttributeLine(trimmed: String, language: String): Boolean {
            return when (language) {
                "rust" -> trimmed.startsWith("#[") || trimmed.startsWith("#![")
                "cpp" -> trimmed.startsWith("[[") || trimmed.startsWith("template") ||
                         trimmed.startsWith("__attribute__") ||
                         trimmed.matches(Regex("""^[A-Z][A-Z0-9_]+(?:\(.*\))?$"""))
                else -> trimmed.startsWith("@")
            }
        }

        private fun parseBlockDocComment(lines: List<String>, endIdx: Int): String? {
            var startIdx = endIdx
            while (startIdx >= 0) {
                if (lines[startIdx].trimStart().startsWith("/*")) {
                    break
                }
                startIdx--
            }
            if (startIdx < 0) return null
            val firstLine = lines[startIdx].trimStart()
            if (!firstLine.startsWith("/**") && !firstLine.startsWith("/*!")) {
                return null
            }

            val contentLines = mutableListOf<String>()
            for (i in startIdx..endIdx) {
                var line = lines[i].trim()
                if (i == startIdx) {
                    line = if (line.startsWith("/**")) line.removePrefix("/**").trim()
                           else line.removePrefix("/*!").trim()
                }
                if (i == endIdx) {
                    line = line.removeSuffix("*/").trim()
                }
                if (line.startsWith("*")) {
                    line = line.removePrefix("*").trim()
                }
                if (line.isNotEmpty()) {
                    if (line.startsWith("@") || line.startsWith(":param") || line.startsWith("- Parameter") || line.startsWith("- Returns")) {
                        break
                    }
                    contentLines.add(line)
                }
            }
            val result = contentLines.joinToString(" ").trim()
            return if (result.isNotEmpty()) result else null
        }

        private fun parseTripleSlashDocComment(lines: List<String>, endIdx: Int): String? {
            var startIdx = endIdx
            while (startIdx > 0 && lines[startIdx - 1].trim().startsWith("///")) {
                startIdx--
            }
            val contentLines = mutableListOf<String>()
            for (i in startIdx..endIdx) {
                val line = lines[i].trim().removePrefix("///").trim()
                if (line.startsWith("@") || line.startsWith(":param") || line.startsWith("- Parameter") || line.startsWith("- Returns")) {
                    break
                }
                if (line.isNotEmpty()) {
                    contentLines.add(line)
                }
            }
            val result = contentLines.joinToString(" ").trim()
            return if (result.isNotEmpty()) result else null
        }

        private fun parseDoubleSlashDocComment(lines: List<String>, endIdx: Int): String? {
            var startIdx = endIdx
            while (startIdx > 0 && lines[startIdx - 1].trim().startsWith("//")) {
                val prevLow = lines[startIdx - 1].trim().lowercase()
                if (NON_DOC_LINE_PREFIXES.any { prevLow.startsWith(it) }) break
                startIdx--
            }
            val contentLines = mutableListOf<String>()
            for (i in startIdx..endIdx) {
                val rawTrim = lines[i].trim()
                val line = rawTrim.removePrefix("//").trim()
                val lLow = rawTrim.lowercase()
                if (NON_DOC_LINE_PREFIXES.any { lLow.startsWith(it) }) {
                    break
                }
                if (line.isNotEmpty()) {
                    contentLines.add(line)
                }
            }
            val result = contentLines.joinToString(" ").trim()
            return if (result.isNotEmpty()) result else null
        }

        /**
         * Extracts Python docstring (PEP 257) or preceding comment description for a function/class.
         */
        fun extractPythonDocDescription(
            lines: List<String>,
            declStartLine: Int,
            hdrEndLine: Int,
            bodyEndLine: Int
        ): String? {
            // Check if inline docstring on the header line itself
            val declLineIdx = declStartLine - 1
            if (declLineIdx >= 0 && declLineIdx < lines.size) {
                val declLine = lines[declLineIdx]
                var pDepth = 0
                var bDepth = 0
                var headerColonIdx = -1
                for (i in declLine.indices) {
                    when (declLine[i]) {
                        '(' -> pDepth++
                        ')' -> if (pDepth > 0) pDepth--
                        '[' -> bDepth++
                        ']' -> if (bDepth > 0) bDepth--
                        ':' -> if (pDepth == 0 && bDepth == 0) {
                            headerColonIdx = i
                            break
                        }
                    }
                }
                if (headerColonIdx >= 0 && headerColonIdx + 1 < declLine.length) {
                    val afterColon = declLine.substring(headerColonIdx + 1).trim()
                    val quote = when {
                        afterColon.startsWith("\"\"\"") || afterColon.startsWith("r\"\"\"") || afterColon.startsWith("f\"\"\"") -> "\"\"\""
                        afterColon.startsWith("'''") || afterColon.startsWith("r'''") || afterColon.startsWith("f'''") -> "'''"
                        else -> null
                    }
                    if (quote != null) {
                        val afterFirst = afterColon.substring(afterColon.indexOf(quote) + quote.length)
                        if (afterFirst.contains(quote)) {
                            val content = afterFirst.substringBefore(quote).trim()
                            if (content.isNotEmpty()) return content
                        }
                    }
                }
            }

            val searchStart = hdrEndLine
            val searchEnd = minOf(lines.size, bodyEndLine)
            var docLineIdx = -1
            for (idx in searchStart until searchEnd) {
                val trimmed = lines[idx].trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                docLineIdx = idx
                break
            }

            if (docLineIdx != -1) {
                val line = lines[docLineIdx].trim()
                val quote = when {
                    line.startsWith("\"\"\"") || line.startsWith("r\"\"\"") || line.startsWith("f\"\"\"") -> "\"\"\""
                    line.startsWith("'''") || line.startsWith("r'''") || line.startsWith("f'''") -> "'''"
                    else -> null
                }
                if (quote != null) {
                    val afterFirst = line.substring(line.indexOf(quote) + quote.length)
                    if (afterFirst.contains(quote)) {
                        val content = afterFirst.substringBefore(quote).trim()
                        if (content.isNotEmpty()) return content
                    } else {
                        val contentLines = mutableListOf<String>()
                        if (afterFirst.trim().isNotEmpty()) {
                            contentLines.add(afterFirst.trim())
                        }
                        for (idx in (docLineIdx + 1) until searchEnd) {
                            val curLine = lines[idx].trim()
                            if (curLine.contains(quote)) {
                                val beforeEnd = curLine.substringBefore(quote).trim()
                                if (beforeEnd.isNotEmpty() && !beforeEnd.startsWith(":") && !beforeEnd.startsWith("@")) {
                                    contentLines.add(beforeEnd)
                                }
                                break
                            } else {
                                if (curLine.startsWith(":") || curLine.startsWith("@")) break
                                if (curLine.isNotEmpty()) contentLines.add(curLine)
                            }
                        }
                        val result = contentLines.joinToString(" ").trim()
                        if (result.isNotEmpty()) return result
                    }
                }
            }

            // Fallback: check preceding # comments right before declStartLine
            val startIdx = declStartLine - 1
            if (startIdx > 0) {
                var cur = startIdx - 1
                while (cur >= 0 && lines[cur].trim().startsWith("@")) cur--
                if (cur >= 0 && lines[cur].trim().startsWith("#")) {
                    var cEnd = cur
                    var cStart = cur
                    while (cStart > 0 && lines[cStart - 1].trim().startsWith("#")) {
                        val prevLow = lines[cStart - 1].trim().lowercase()
                        if (PYTHON_NON_DOC_PREFIXES.any { prevLow.startsWith(it) }) break
                        cStart--
                    }
                    val commentLines = mutableListOf<String>()
                    for (i in cStart..cEnd) {
                        val cTrim = lines[i].trim()
                        val cLow = cTrim.lowercase()
                        if (PYTHON_NON_DOC_PREFIXES.any { cLow.startsWith(it) }) break
                        val c = cTrim.removePrefix("#").trim()
                        if (c.isNotEmpty()) {
                            commentLines.add(c)
                        }
                    }
                    val result = commentLines.joinToString(" ").trim()
                    if (result.isNotEmpty()) return result
                }
            }

            return null
        }

        private fun extractKotlin(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "kotlin")
            val maskedLines = maskedText.lines()
            val cleanLines = cleanLinesFrom(lines, 0)

            val pkgMatch = KT_PKG_RE.find(maskedText)
            val pkgName = pkgMatch?.groupValues?.get(1) ?: relPath.replace('/', '.')
            val imports = KT_IMP_RE.findAll(maskedText).map { match ->
                Pair(match.groupValues[1], maskedText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            val scopeStack = mutableListOf<ScopeEntry>()

            var topLevelCount = 0
            for (match in KT_DECL_RE.findAll(maskedText)) {
                val annotations = match.groups["annotations"]?.value ?: ""
                val modifiersGroup = match.groups["modifiers"]
                val modifiers = modifiersGroup?.value ?: ""
                val kwGroup = match.groups["kw"] ?: continue
                val kw = kwGroup.value
                val rawName = match.groups["name"]?.value
                val modTokens = modifiers.split(Regex("""\s+""")).filter { it.isNotEmpty() }.toSet()

                val name = when {
                    !rawName.isNullOrEmpty() -> rawName
                    kw == "object" && "companion" in modTokens -> "Companion"
                    else -> continue
                }

                val matchStartLine = maskedText.substring(0, match.range.first).count { it == '\n' } + 1
                val declStartOffset = if (modifiers.isNotEmpty() && modifiersGroup != null) {
                    modifiersGroup.range.first
                } else {
                    kwGroup.range.first
                }
                val startLine = maskedText.substring(0, declStartOffset).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine, cleanLines)

                while (scopeStack.isNotEmpty() && scopeStack.last().endLine < startLine) {
                    scopeStack.removeAt(scopeStack.size - 1)
                }

                val isTopLevel = scopeStack.isEmpty()
                if (isTopLevel && "private" !in modTokens && "companion" !in modTokens && kw != "typealias") topLevelCount++

                val enclosingPrefix = scopeStack.joinToString(".") { it.name }
                val (nodeId, qualifiedName) = if (enclosingPrefix.isNotEmpty()) {
                    Pair("$fileId#$enclosingPrefix.$name", "$pkgName.$enclosingPrefix.$name")
                } else {
                    Pair("$fileId#$name", "$pkgName.$name")
                }

                val annTokens = Regex("""@([A-Za-z0-9_]+)""").findAll(annotations).map { it.groupValues[1] }.toList()
                val sigLine = if (startLine <= lines.size) stripLeadingInlineAnnotations(lines[startLine - 1]) else name
                val kind = when {
                    "expect" in modTokens -> "expect_decl"
                    "actual" in modTokens -> "actual_decl"
                    "Composable" in annTokens -> "composable"
                    "enum" in modTokens -> "enum"
                    "annotation" in modTokens -> "annotation"
                    kw == "fun" -> "function"
                    else -> kw
                }

                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name, annTokens)

                val description = if (isTopLevel && (kw in listOf("class", "interface", "object", "fun") || "Composable" in annTokens)) {
                    extractDocDescription(lines, matchStartLine, "kotlin")
                } else {
                    null
                }

                val (cVec, cScore) = computeComplexityMetrics(
                    kind = kind,
                    startLine = startLine,
                    endLine = endLine,
                    maskedLines = maskedLines,
                    lines = lines,
                    language = "kotlin",
                    isComposable = "Composable" in annTokens,
                    functionName = name
                )

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = qualifiedName, kind = kind, signature = sigLine,
                        startLine = startLine, endLine = endLine, moduleId = moduleId,
                        sourceSet = sourceSet, semioticLayer = sem, archLayer = arch, isTestDouble = isTd,
                        description = description, isTopLevel = isTopLevel,
                        cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                        maxNestingDepth = cVec.maxNesting, complexityScore = cScore
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }

                if (kw in listOf("class", "interface", "object")) {
                    for ((superName, edgeType) in extractKotlinSupertypes(lines, startLine, endLine)) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:sup:$superName:$startLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$superName", edgeType = edgeType,
                            lineNumber = startLine
                        ))
                    }
                }

                if (kw in setOf("class", "interface", "object", "enum", "fun")) {
                    scopeStack.add(ScopeEntry(name, startLine, endLine))
                }
            }

            val fileVarTypes = mutableMapOf<String, String>()
            for (m in LOCAL_VAL_COLON_RE.findAll(maskedText)) {
                val varName = m.groupValues[1]
                val typeName = m.groupValues[2].substringAfterLast('.').substringBefore('<').trim()
                if (varName.isNotEmpty() && typeName.isNotEmpty() && typeName !in KOTLIN_PRIMITIVE_TYPES) {
                    fileVarTypes[varName] = typeName
                }
            }
            for (m in LOCAL_VAL_ASSIGN_RE.findAll(maskedText)) {
                val varName = m.groupValues[1]
                val typeName = m.groupValues[2].substringAfterLast('.').substringBefore('<').trim()
                if (varName.isNotEmpty() && typeName.isNotEmpty() && typeName !in KOTLIN_PRIMITIVE_TYPES && typeName !in COMMON_CONSTRUCTORS) {
                    fileVarTypes[varName] = typeName
                }
            }

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
                val localReceiverTypes = fileVarTypes.toMutableMap()
                for (m in LOCAL_VAL_COLON_RE.findAll(body)) {
                    val varName = m.groupValues[1]
                    val typeName = m.groupValues[2].substringAfterLast('.').substringBefore('<').trim()
                    if (varName.isNotEmpty() && typeName.isNotEmpty() && typeName !in KOTLIN_PRIMITIVE_TYPES) {
                        localReceiverTypes[varName] = typeName
                    }
                }
                for (m in LOCAL_VAL_ASSIGN_RE.findAll(body)) {
                    val varName = m.groupValues[1]
                    val typeName = m.groupValues[2].substringAfterLast('.').substringBefore('<').trim()
                    if (varName.isNotEmpty() && typeName.isNotEmpty() && typeName !in KOTLIN_PRIMITIVE_TYPES && typeName !in COMMON_CONSTRUCTORS) {
                        localReceiverTypes[varName] = typeName
                    }
                }
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val receiver = callMatch.groups["receiver"]?.value?.trim()
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    val resolvedType = if (!receiver.isNullOrEmpty()) localReceiverTypes[receiver] else null
                    val targetSym = when {
                        resolvedType != null -> "$resolvedType.$callee"
                        !receiver.isNullOrEmpty() -> "$receiver.$callee"
                        else -> callee
                    }
                    val provenance = when {
                        resolvedType != null -> "CALL_RECEIVER:$receiver;type=$resolvedType"
                        !receiver.isNullOrEmpty() -> "CALL_RECEIVER:$receiver"
                        else -> "INFERRED_CALL"
                    }
                    if (callee != node.name && callee !in CALL_STOPWORDS && seenCallees.add(targetSym)) {
                        val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$targetSym:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$targetSym", edgeType = "CALLS",
                            provenance = provenance, confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }

                for (infixMatch in INFIX_CALL_RE.findAll(body)) {
                    val callee = infixMatch.groups["callee"]?.value ?: continue
                    if (callee != node.name && callee !in INFIX_STOPWORDS && callee !in CALL_STOPWORDS && seenCallees.add(callee)) {
                        val callLine = node.startLine + body.substring(0, infixMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:infix:$callee:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$callee", edgeType = "CALLS",
                            provenance = "INFERRED_INFIX_CALL", confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractSwift(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "swift")
            val maskedLines = maskedText.lines()
            val cleanLines = cleanLinesFrom(lines, 0)
            val scopeStack = mutableListOf<ScopeEntry>()

            val imports = SWIFT_IMP_RE.findAll(maskedText).map { match ->
                Pair(match.groupValues[1], maskedText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in SWIFT_DECL_RE.findAll(maskedText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "class"
                val name = match.groups["name"]?.value ?: continue

                val startLine = maskedText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine, cleanLines)

                while (scopeStack.isNotEmpty() && scopeStack.last().endLine < startLine) {
                    scopeStack.removeAt(scopeStack.size - 1)
                }
                val enclosingPrefix = scopeStack.joinToString(".") { it.name }
                val (nodeId, qualifiedName) = if (enclosingPrefix.isNotEmpty()) {
                    Pair("$fileId#$enclosingPrefix.$name", "$relPath:$enclosingPrefix.$name")
                } else {
                    Pair("$fileId#$name", "$relPath:$name")
                }

                val isTopLevel = indent.isEmpty() && scopeStack.isEmpty()
                if (isTopLevel && kw != "extension") topLevelCount++
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)

                val description = if (isTopLevel) extractDocDescription(lines, startLine, "swift") else null

                val kind = if (kw == "func") "function" else kw
                val (cVec, cScore) = computeComplexityMetrics(
                    kind = kind,
                    startLine = startLine,
                    endLine = endLine,
                    maskedLines = maskedLines,
                    lines = lines,
                    language = "swift",
                    functionName = name
                )

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = qualifiedName, kind = kind,
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd,
                        description = description, isTopLevel = isTopLevel,
                        cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                        maxNestingDepth = cVec.maxNesting, complexityScore = cScore
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }

                if (kw in setOf("class", "struct", "protocol", "actor", "enum", "func", "extension")) {
                    scopeStack.add(ScopeEntry(name, startLine, endLine))
                }
            }

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val receiver = callMatch.groups["receiver"]?.value?.trim()
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    val targetSym = if (!receiver.isNullOrEmpty()) "$receiver.$callee" else callee
                    if (callee != node.name && callee !in CALL_STOPWORDS && seenCallees.add(targetSym)) {
                        val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$targetSym:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$targetSym", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private val TS_ARROW_OR_FN_RE = Regex("""=\s*(?:async\s*)?(?:function\b|(?:\([^)]*\)|[A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]+)?=>)""")

        private fun extractTypeScript(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "typescript")
            val maskedLines = maskedText.lines()
            val cleanLines = cleanLinesFrom(lines, 0)
            val scopeStack = mutableListOf<ScopeEntry>()

            val imports = mutableListOf<Pair<String, Int>>()
            for (m in TS_IMP_RE.findAll(maskedText)) {
                val origMatch = sourceText.substring(m.range)
                val parsed = TS_IMP_RE.find(origMatch) ?: m
                val named = parsed.groupValues[1].ifEmpty { parsed.groupValues[2] }
                val lineNo = maskedText.substring(0, m.range.first).count { it == '\n' } + 1
                for (sym in named.split(",")) {
                    val clean = sym.trim().substringBefore(" as ").trim()
                    if (clean.isNotEmpty()) imports.add(Pair(clean, lineNo))
                }
            }

            data class TsDeclCandidate(
                val rangeStart: Int,
                val indent: String,
                val kw: String,
                val name: String,
                val isMethod: Boolean
            )

            val declCandidates = mutableListOf<TsDeclCandidate>()
            for (match in TS_DECL_RE.findAll(maskedText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "class"
                val name = match.groups["name"]?.value ?: continue
                declCandidates.add(TsDeclCandidate(match.range.first, indent, kw, name, isMethod = false))
            }
            val tsMethodStopwords = setOf("if", "while", "for", "switch", "catch", "function", "return", "throw", "new", "typeof", "instanceof")
            for (match in TS_METHOD_DECL_RE.findAll(maskedText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val name = match.groups["name"]?.value ?: continue
                if (name in tsMethodStopwords) continue
                declCandidates.add(TsDeclCandidate(match.range.first, indent, "method", name, isMethod = true))
            }
            declCandidates.sortBy { it.rangeStart }

            var topLevelCount = 0
            val seenStartOffsets = mutableSetOf<Int>()
            for (candidate in declCandidates) {
                if (!seenStartOffsets.add(candidate.rangeStart)) continue
                val indent = candidate.indent
                val kw = candidate.kw
                val name = candidate.name

                val startLine = maskedText.substring(0, candidate.rangeStart).count { it == '\n' } + 1
                while (scopeStack.isNotEmpty() && scopeStack.last().endLine < startLine) {
                    scopeStack.removeAt(scopeStack.size - 1)
                }

                if (candidate.isMethod && scopeStack.isEmpty()) {
                    continue
                }

                val hasBrace = (startLine - 1 until minOf(cleanLines.size, startLine + 3)).any { cleanLines[it].contains('{') }
                val endLine = if (hasBrace) {
                    findBlockEndLine(lines, startLine, cleanLines)
                } else {
                    startLine
                }

                val enclosingPrefix = scopeStack.joinToString(".") { it.name }
                val (nodeId, qualifiedName) = if (enclosingPrefix.isNotEmpty()) {
                    Pair("$fileId#$enclosingPrefix.$name", "$relPath:$enclosingPrefix.$name")
                } else {
                    Pair("$fileId#$name", "$relPath:$name")
                }

                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val declHeaderSlice = maskedLines.subList(startLine - 1, minOf(maskedLines.size, minOf(endLine, startLine + 2))).joinToString(" ")
                val kind = when {
                    candidate.isMethod -> "method"
                    kw == "function" -> "function"
                    kw in setOf("const", "let", "var") -> if (TS_ARROW_OR_FN_RE.containsMatchIn(declHeaderSlice)) "function" else "variable"
                    kw == "interface" -> "interface"
                    kw == "type" -> "typealias"
                    kw == "enum" || kw.endsWith("enum") -> "enum"
                    else -> "class"
                }

                val isTopLevel = !candidate.isMethod && indent.isEmpty() && scopeStack.isEmpty()
                if (!isTopLevel && kind == "variable") continue
                if (isTopLevel && kind !in setOf("typealias", "variable")) topLevelCount++
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)

                val description = if (isTopLevel && kind in setOf("class", "interface", "function", "enum")) {
                    extractDocDescription(lines, startLine, "typescript")
                } else null

                val (cVec, cScore) = computeComplexityMetrics(
                    kind = kind,
                    startLine = startLine,
                    endLine = endLine,
                    maskedLines = maskedLines,
                    lines = lines,
                    language = "typescript",
                    functionName = name
                )

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = qualifiedName, kind = kind,
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd,
                        description = description, isTopLevel = isTopLevel,
                        cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                        maxNestingDepth = cVec.maxNesting, complexityScore = cScore
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }

                if (kind in setOf("class", "interface", "function", "enum")) {
                    scopeStack.add(ScopeEntry(name, startLine, endLine))
                }
            }

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val receiver = callMatch.groups["receiver"]?.value?.trim()
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    val targetSym = if (!receiver.isNullOrEmpty()) "$receiver.$callee" else callee
                    if (callee != node.name && callee !in CALL_STOPWORDS && seenCallees.add(targetSym)) {
                        val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$targetSym:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$targetSym", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractRust(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "rust")
            val maskedLines = maskedText.lines()
            val cleanLines = cleanLinesFrom(lines, 0)
            val scopeStack = mutableListOf<ScopeEntry>()

            val imports = RS_USE_RE.findAll(maskedText).map { match ->
                val sym = match.groupValues[1].substringAfterLast("::")
                Pair(sym, maskedText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in RS_DECL_RE.findAll(maskedText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "struct"
                val name = match.groups["name"]?.value ?: continue

                val kind = when (kw) {
                    "fn" -> "function"
                    "trait" -> "interface"
                    "enum" -> "enum"
                    "type" -> "typealias"
                    "mod" -> "module"
                    else -> "class"
                }

                val startLine = maskedText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine, cleanLines)

                while (scopeStack.isNotEmpty() && scopeStack.last().endLine < startLine) {
                    scopeStack.removeAt(scopeStack.size - 1)
                }
                val enclosingPrefix = scopeStack.joinToString("::") { it.name }
                val (nodeId, qualifiedName) = if (enclosingPrefix.isNotEmpty()) {
                    Pair("$fileId#$enclosingPrefix::$name", "$relPath:$enclosingPrefix::$name")
                } else {
                    Pair("$fileId#$name", "$relPath:$name")
                }

                val isTopLevel = indent.isEmpty() && scopeStack.isEmpty()
                if (isTopLevel && kind !in setOf("typealias", "module")) topLevelCount++
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)

                val description = if (isTopLevel && kind in setOf("class", "interface", "enum", "function")) {
                    extractDocDescription(lines, startLine, "rust")
                } else null

                val (cVec, cScore) = computeComplexityMetrics(
                    kind = kind,
                    startLine = startLine,
                    endLine = endLine,
                    maskedLines = maskedLines,
                    lines = lines,
                    language = "rust",
                    functionName = name
                )

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = qualifiedName, kind = kind,
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd,
                        description = description, isTopLevel = isTopLevel,
                        cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                        maxNestingDepth = cVec.maxNesting, complexityScore = cScore
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }

                if (kw in setOf("struct", "enum", "trait", "fn", "mod")) {
                    scopeStack.add(ScopeEntry(name, startLine, endLine))
                }
            }

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val receiver = callMatch.groups["receiver"]?.value?.trim()
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    val targetSym = if (callee == "new" && !receiver.isNullOrEmpty()) receiver else if (!receiver.isNullOrEmpty()) "$receiver::$callee" else callee
                    if (targetSym != node.name && targetSym !in CALL_STOPWORDS && seenCallees.add(targetSym)) {
                        val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$targetSym:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$targetSym", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun extractCpp(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "cpp")
            val maskedLines = maskedText.lines()
            val cleanLines = cleanLinesFrom(lines, 0)
            val scopeStack = mutableListOf<ScopeEntry>()

            val imports = CPP_INC_RE.findAll(sourceText).mapNotNull { match ->
                val lineIdx = sourceText.substring(0, match.range.first).count { it == '\n' }
                val maskedLine = if (lineIdx < maskedLines.size) maskedLines[lineIdx] else ""
                if (maskedLine.trim().isEmpty()) {
                    null
                } else {
                    val header = match.groupValues[1].substringAfterLast('/')
                    Pair(header, lineIdx + 1)
                }
            }.toList()

            var topLevelCount = 0
            val seenNodeIds = mutableSetOf<String>()
            for (match in CPP_DECL_RE.findAll(maskedText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "class"
                val name = match.groups["name"]?.value ?: continue

                val kind = when {
                    kw == "namespace" -> "namespace"
                    kw == "struct" -> "struct"
                    kw.startsWith("enum") -> "enum"
                    else -> "class"
                }

                val startLine = maskedText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findBlockEndLine(lines, startLine, cleanLines)

                while (scopeStack.isNotEmpty() && scopeStack.last().endLine < startLine) {
                    scopeStack.removeAt(scopeStack.size - 1)
                }
                val enclosingPrefix = scopeStack.joinToString("::") { it.name }
                val (nodeId, qualifiedName) = if (enclosingPrefix.isNotEmpty()) {
                    Pair("$fileId#$enclosingPrefix::$name", "$relPath:$enclosingPrefix::$name")
                } else {
                    Pair("$fileId#$name", "$relPath:$name")
                }

                val isInsideClassOrStruct = scopeStack.any { it.kind in setOf("class", "struct") }
                val isTopLevel = indent.isEmpty() && !isInsideClassOrStruct
                if (isTopLevel && kind != "namespace") topLevelCount++
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
                if (!seenNodeIds.add(nodeId)) continue

                val description = if (isTopLevel && kind in setOf("class", "struct", "enum")) {
                    extractDocDescription(lines, startLine, "cpp")
                } else null

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = qualifiedName, kind = kind,
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd,
                        description = description, isTopLevel = isTopLevel
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }

                if (kind in setOf("class", "struct", "namespace")) {
                    scopeStack.add(ScopeEntry(name, startLine, endLine, kind))
                }
            }

            for (match in CPP_FUNC_RE.findAll(maskedText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val rawName = match.groups["name"]?.value ?: continue
                val shortName = rawName.substringAfterLast("::")
                if (shortName in CALL_STOPWORDS || shortName in setOf("main", "sizeof", "alignof", "decltype", "static_assert")) continue

                val startLine = maskedText.substring(0, match.range.first).count { it == '\n' } + 1
                // Skip constructor if a class/struct with the same name already exists at or enclosing this line
                if (nodes.any { it.name == shortName && it.kind in setOf("class", "struct") && startLine in it.startLine..it.endLine }) continue

                val endLine = findBlockEndLine(lines, startLine, cleanLines)
                val enclosingClass = nodes.firstOrNull { it.kind in setOf("class", "struct") && startLine > it.startLine && startLine < it.endLine }
                val qualifiedCppName = if (rawName.contains("::")) rawName else if (enclosingClass != null) "${enclosingClass.name}::$rawName" else rawName
                val isTopLevel = indent.isEmpty() && enclosingClass == null && !rawName.contains("::")
                if (isTopLevel) topLevelCount++
                val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else shortName
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, shortName)
                val nodeId = "$fileId#$qualifiedCppName"
                if (!seenNodeIds.add(nodeId)) continue

                val description = if (isTopLevel) {
                    extractDocDescription(lines, startLine, "cpp")
                } else null

                val (cVec, cScore) = computeComplexityMetrics(
                    kind = "function",
                    startLine = startLine,
                    endLine = endLine,
                    maskedLines = maskedLines,
                    lines = lines,
                    language = "cpp",
                    functionName = shortName
                )

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = shortName,
                        qualifiedName = "$relPath:$qualifiedCppName", kind = "function",
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd,
                        description = description, isTopLevel = isTopLevel,
                        cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                        maxNestingDepth = cVec.maxNesting, complexityScore = cScore
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }
            }

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val receiver = callMatch.groups["receiver"]?.value?.trim()
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    val targetSym = if (!receiver.isNullOrEmpty()) "$receiver::$callee" else callee
                    if (callee != node.name && callee !in CALL_STOPWORDS && seenCallees.add(targetSym)) {
                        val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$targetSym:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$targetSym", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
                        ))
                    }
                }
            }
            return Triple(nodes, edges, topLevelCount)
        }

        private fun findPythonHeaderEndLine(maskedLines: List<String>, startLine: Int, endLine: Int): Int {
            if (startLine > maskedLines.size) return startLine
            val safeEnd = minOf(maskedLines.size, endLine)
            var parenDepth = 0
            var bracketDepth = 0
            for (lineNo in startLine..safeEnd) {
                val masked = maskedLines[lineNo - 1].trim()
                for (c in masked) {
                    when (c) {
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '[' -> bracketDepth++
                        ']' -> if (bracketDepth > 0) bracketDepth--
                    }
                }
                if (parenDepth == 0 && bracketDepth == 0 && masked.endsWith(":")) {
                    return lineNo
                }
            }
            return startLine
        }

        private fun extractPython(
            projectId: String, relPath: String, sourceText: String, moduleId: String, sourceSet: String,
            precomputedMaskedText: String? = null
        ): Triple<List<GraphNode>, List<GraphEdge>, Int> {
            val fileId = "$projectId:$relPath"
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val lines = sourceText.lines()
            val maskedText = precomputedMaskedText ?: maskCommentsAndStrings(sourceText, "python")
            val maskedLines = maskedText.lines()
            val scopeStack = mutableListOf<ScopeEntry>()

            val imports = PY_IMP_RE.findAll(maskedText).map { match ->
                val mod = match.groupValues[1].ifEmpty { match.groupValues[2] }
                Pair(mod, maskedText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

            var topLevelCount = 0
            for (match in PY_DECL_RE.findAll(maskedText)) {
                val indent = match.groups["indent"]?.value ?: ""
                val kw = match.groups["kw"]?.value ?: "def"
                val name = match.groups["name"]?.value ?: continue
                val isDunder = name.startsWith("__") && name.endsWith("__")
                val isAllowedDunder = isDunder && name in setOf(
                    "__init__", "__enter__", "__exit__", "__call__", "__str__", "__repr__",
                    "__getitem__", "__setitem__", "__len__", "__iter__", "__next__", "__contains__",
                    "__aenter__", "__aexit__", "__eq__", "__hash__"
                )
                if (name.startsWith("_") && !isAllowedDunder) continue

                val startLine = maskedText.substring(0, match.range.first).count { it == '\n' } + 1
                val endLine = findPythonBlockEndLine(lines, maskedLines, startLine)

                while (scopeStack.isNotEmpty() && scopeStack.last().endLine < startLine) {
                    scopeStack.removeAt(scopeStack.size - 1)
                }
                val enclosingPrefix = scopeStack.joinToString(".") { it.name }
                val (nodeId, qualifiedName) = if (enclosingPrefix.isNotEmpty()) {
                    Pair("$fileId#$enclosingPrefix.$name", "$relPath:$enclosingPrefix.$name")
                } else {
                    Pair("$fileId#$name", "$relPath:$name")
                }

                val isTopLevel = indent.isEmpty() && scopeStack.isEmpty()
                if (isTopLevel) topLevelCount++

                val sigLine = buildPythonHeaderSignature(lines, maskedLines, startLine, endLine)
                val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)

                val headerEndLine = findPythonHeaderEndLine(maskedLines, startLine, endLine)
                val description = if (isTopLevel) {
                    extractPythonDocDescription(lines, startLine, headerEndLine, endLine)
                } else null

                val kind = if ("class" in kw) "class" else "function"
                val (cVec, cScore) = computeComplexityMetrics(
                    kind = kind,
                    startLine = startLine,
                    endLine = endLine,
                    maskedLines = maskedLines,
                    lines = lines,
                    language = "python",
                    functionName = name
                )

                nodes.add(
                    GraphNode(
                        nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                        qualifiedName = qualifiedName, kind = kind,
                        signature = sigLine, startLine = startLine, endLine = endLine,
                        moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                        archLayer = arch, isTestDouble = isTd,
                        description = description, isTopLevel = isTopLevel,
                        cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                        maxNestingDepth = cVec.maxNesting, complexityScore = cScore
                    )
                )

                if (isTopLevel) {
                    for ((impTarget, impLine) in imports) {
                        edges.add(GraphEdge(
                            edgeId = "$nodeId:imp:$impTarget:$impLine", projectId = projectId,
                            sourceNodeId = nodeId, targetNodeId = "sym:$impTarget", edgeType = "IMPORTS",
                            lineNumber = impLine
                        ))
                    }
                }

                if ("class" in kw || "def" in kw) {
                    scopeStack.add(ScopeEntry(name, startLine, endLine))
                }
            }

            for (node in nodes) {
                val childRanges = nodes
                    .filter { it.nodeId != node.nodeId && it.startLine >= node.startLine && it.endLine <= node.endLine && (it.startLine > node.startLine || it.endLine < node.endLine) }
                    .map { it.startLine..it.endLine }
                val headerEndLine = findPythonHeaderEndLine(maskedLines, node.startLine, node.endLine)
                val scanStartLine = if (headerEndLine < node.endLine) headerEndLine + 1 else node.startLine
                val body = maskChildLineRanges(maskedLines, scanStartLine, node.endLine, childRanges)
                val seenCallees = mutableSetOf<String>()
                for (callMatch in CALL_RE.findAll(body)) {
                    val receiver = callMatch.groups["receiver"]?.value?.trim()
                    val callee = callMatch.groups["callee"]?.value ?: continue
                    val targetSym = if (!receiver.isNullOrEmpty()) "$receiver.$callee" else callee
                    if (callee != node.name && callee !in CALL_STOPWORDS && !callee.startsWith("_") && seenCallees.add(targetSym)) {
                        val callLine = scanStartLine + body.substring(0, callMatch.range.first).count { it == '\n' }
                        edges.add(GraphEdge(
                            edgeId = "${node.nodeId}:call:$targetSym:$callLine", projectId = projectId,
                            sourceNodeId = node.nodeId, targetNodeId = "sym:$targetSym", edgeType = "CALLS",
                            provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
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
            val byNodeId = mutableMapOf<String, GraphNode>()
            for (node in nodes) {
                if (node.projectId != projectId) continue
                byName.getOrPut(node.name) { mutableListOf() }.add(node)
                byQName[node.qualifiedName] = node
                byNodeId[node.nodeId] = node
            }

            // Collect imported symbols/packages per source fileId for context-aware disambiguation
            val importsByFile = mutableMapOf<String, MutableSet<String>>()
            for (edge in rawEdges) {
                if (edge.edgeType == "IMPORTS") {
                    val srcFileId = byNodeId[edge.sourceNodeId]?.fileId ?: edge.sourceNodeId.substringBefore("#")
                    val impSym = edge.targetNodeId.removePrefix("sym:")
                    importsByFile.getOrPut(srcFileId) { mutableSetOf() }.add(impSym)
                }
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
                        val receiver = if (sym.contains('.')) sym.substringBeforeLast('.') else if (sym.contains("::")) sym.substringBeforeLast("::") else null
                        val candidates = byName[shortSym]
                        if (candidates.isNullOrEmpty()) continue

                        if (candidates.size == 1) {
                            targetId = candidates[0].nodeId
                        } else {
                            val srcNode = byNodeId[edge.sourceNodeId]
                            val srcFileId = srcNode?.fileId ?: edge.sourceNodeId.substringBefore("#")
                            val srcModId = srcNode?.moduleId ?: ""
                            val fileImports = importsByFile[srcFileId] ?: emptySet()

                            // Priority 0: Candidate matching receiver via semantic receiver scoring
                            val scoredCandidates = if (receiver != null) {
                                candidates.map { cand -> cand to scoreReceiverCandidate(cand, receiver, srcFileId, srcNode) }
                            } else emptyList()

                            val maxReceiverScore = scoredCandidates.maxOfOrNull { it.second } ?: 0.0
                            val receiverMatches = if (maxReceiverScore > 0.0) {
                                scoredCandidates.filter { it.second == maxReceiverScore }.map { it.first }
                            } else emptyList()

                            val candidatePool = if (receiverMatches.isNotEmpty()) receiverMatches else candidates

                            // Priority 1: Same file
                            val sameFile = candidatePool.filter { it.fileId == srcFileId }
                            // Priority 2: Explicitly imported symbol or wildcard package
                            val imported = candidatePool.filter { cand ->
                                val qn = cand.qualifiedName
                                val pkg = qn.substringBeforeLast('.', "")
                                qn in fileImports || "$pkg.*" in fileImports || fileImports.any { imp -> qn.endsWith(".$imp") }
                            }
                            // Priority 3: Same module
                            val sameModule = if (srcModId.isNotEmpty()) candidatePool.filter { it.moduleId == srcModId } else emptyList()

                            val chosenPool = when {
                                sameFile.isNotEmpty() -> sameFile
                                imported.isNotEmpty() -> imported
                                receiverMatches.isNotEmpty() -> receiverMatches
                                sameModule.isNotEmpty() -> sameModule
                                else -> candidates
                            }

                            val chosen = chosenPool.sortedWith(
                                compareByDescending<GraphNode> { it.inDegree }.thenBy { it.nodeId }
                            ).first()
                            targetId = chosen.nodeId

                            if (chosenPool.size > 1 && edge.provenance.startsWith("INFERRED_CALL")) {
                                conf = minOf(conf, 0.75)
                            }
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
