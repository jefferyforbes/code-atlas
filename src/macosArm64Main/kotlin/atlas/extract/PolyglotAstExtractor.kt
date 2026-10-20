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
        private val SWIFT_SCOPE_KEYWORDS = setOf("class", "struct", "protocol", "actor", "enum", "func", "extension")

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
        private val RUST_DOC_KINDS = setOf("class", "interface", "enum", "function")
        private val RUST_SCOPE_KEYWORDS = setOf("struct", "enum", "trait", "fn", "mod")
        private val RUST_NON_COUNTABLE_TOP_LEVEL = setOf("typealias", "module")

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

        private class LineCleanerSession(
            private val line: String,
            private val state: LexerState
        ) {
            val sb = StringBuilder()
            var i = 0

            fun clean(): String {
                while (i < line.length) {
                    if (handleActiveState()) continue
                    if (handleDelimiterStart()) continue
                    if (isLineCommentStart(line, i)) break
                    sb.append(line[i])
                    i++
                }
                return sb.toString()
            }

            private fun handleActiveState(): Boolean {
                if (state.inBlockComment) {
                    i = resumeBlockComment(line, i, state)
                    return true
                }
                if (state.inTripleQuote) {
                    i = resumeTripleQuote(line, i, state)
                    return true
                }
                if (state.inBacktick) {
                    i = resumeBacktick(line, i, state)
                    return true
                }
                return false
            }

            private fun handleDelimiterStart(): Boolean {
                if (isBlockCommentStart(line, i)) {
                    state.inBlockComment = true
                    i += 2
                    return true
                }
                if (isTripleQuoteStart(line, i)) {
                    state.inTripleQuote = true
                    i += 3
                    return true
                }
                if (line[i] == '"') {
                    i = skipQuotedString(line, i, '"')
                    return true
                }
                if (line[i] == '\'') {
                    i = skipQuotedString(line, i, '\'')
                    return true
                }
                if (line[i] == '`') {
                    i = skipBacktickLiteral(line, i, state)
                    return true
                }
                return false
            }
        }

        private fun resumeBlockComment(line: String, pos: Int, state: LexerState): Int {
            if (line[pos] == '*') {
                if (pos + 1 < line.length && line[pos + 1] == '/') {
                    state.inBlockComment = false
                    return pos + 2
                }
            }
            return pos + 1
        }

        private fun isTripleQuoteTokenAt(line: String, pos: Int): Boolean {
            if (pos + 2 >= line.length) return false
            return line[pos] == '"' && line[pos + 1] == '"' && line[pos + 2] == '"'
        }

        private fun resumeTripleQuote(line: String, pos: Int, state: LexerState): Int {
            if (isTripleQuoteTokenAt(line, pos)) {
                state.inTripleQuote = false
                return pos + 3
            }
            return pos + 1
        }

        private fun resumeBacktick(line: String, pos: Int, state: LexerState): Int {
            if (line[pos] == '\\' && pos + 1 < line.length) {
                return pos + 2
            }
            if (line[pos] == '`') {
                state.inBacktick = false
                return pos + 1
            }
            return pos + 1
        }

        private fun isBlockCommentStart(line: String, pos: Int): Boolean {
            if (pos + 1 >= line.length) return false
            return line[pos] == '/' && line[pos + 1] == '*'
        }

        private fun isLineCommentStart(line: String, pos: Int): Boolean {
            if (pos + 1 >= line.length) return false
            return line[pos] == '/' && line[pos + 1] == '/'
        }

        private fun isTripleQuoteStart(line: String, pos: Int): Boolean {
            return isTripleQuoteTokenAt(line, pos)
        }

        private fun skipQuotedString(line: String, startPos: Int, delimiter: Char): Int {
            var pos = startPos + 1
            while (pos < line.length && line[pos] != delimiter) {
                if (line[pos] == '\\' && pos + 1 < line.length) pos += 2 else pos++
            }
            if (pos < line.length && line[pos] == delimiter) pos++
            return pos
        }

        private fun skipBacktickLiteral(line: String, startPos: Int, state: LexerState): Int {
            var pos = startPos + 1
            var closed = false
            while (pos < line.length) {
                if (line[pos] == '\\' && pos + 1 < line.length) {
                    pos += 2
                    continue
                }
                if (line[pos] == '`') {
                    closed = true
                    pos++
                    break
                }
                pos++
            }
            if (!closed) {
                state.inBacktick = true
            }
            return pos
        }

        private fun cleanLine(line: String, state: LexerState = LexerState()): String {
            return LineCleanerSession(line, state).clean()
        }

        private fun cleanLinesFrom(lines: List<String>, startIdx: Int): List<String> {
            val state = LexerState()
            val result = ArrayList<String>(lines.size - startIdx)
            for (idx in startIdx until lines.size) {
                result.add(cleanLine(lines[idx], state))
            }
            return result
        }

        private val DECL_KEYWORDS = setOf(
            "fun", "class", "interface", "object", "typealias", "val", "var",
            "def",
            "func", "struct", "protocol", "actor", "enum", "extension", "let",
            "function", "const", "type",
            "fn", "trait", "mod", "impl",
            "namespace"
        )

        private val MODIFIER_KEYWORDS = setOf(
            "public", "private", "protected", "internal", "open", "abstract", "sealed",
            "override", "expect", "actual", "inline", "suspend", "data", "value",
            "export", "default", "declare", "readonly", "static", "final", "mutating",
            "fileprivate", "nonisolated", "isolated", "indirect",
            "pub", "async", "unsafe", "extern", "virtual", "constexpr"
        )

        private fun isAnnotationOrAttribute(trimmed: String): Boolean {
            return trimmed.startsWith("@") || trimmed.startsWith("#[")
        }

        private fun extractDeclarationWords(trimmed: String): List<String> {
            return trimmed.split(' ', '\t', '(', ':', '<')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        private fun hasDeclAfterModifiers(words: List<String>): Boolean {
            if (words.size == 1) return true
            for (w in words.drop(1)) {
                if (w in DECL_KEYWORDS) return true
                if (w !in MODIFIER_KEYWORDS) return false
            }
            return false
        }

        private fun isNewDeclarationStart(trimmed: String): Boolean {
            if (isAnnotationOrAttribute(trimmed)) return true
            val words = extractDeclarationWords(trimmed)
            if (words.isEmpty()) return false

            val firstWord = words.first()
            if (firstWord in DECL_KEYWORDS) return true
            if (firstWord !in MODIFIER_KEYWORDS) return false
            return hasDeclAfterModifiers(words)
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

        private val LINE_CONTINUATION_SUFFIXES = setOf(
            "=", "+", "-", "*", "/", "%", "&&", "||", "?:",
            ".", "?.", "::", ",", "->", ":", "&", "|"
        )

        private val NEXT_LINE_CONTINUATION_PREFIXES = setOf(
            ".", "?.", "?:", "->", "+", "-", "*", "/", "&&", "||", "&", "|"
        )

        private val COMPARISON_CHARS = setOf('=', '!', '<', '>')

        private class BlockScanState {
            var depth = 0
            var started = false
            var hasEquals = false
            var seenKeyword = false
            var parenDepth = 0
            var bracketDepth = 0

            fun isExpressionBoundary(): Boolean {
                if (!seenKeyword) return false
                if (parenDepth != 0) return false
                return bracketDepth == 0
            }

            fun scanLine(clean: String) {
                if (!seenKeyword && hasDeclarationKeyword(clean)) {
                    seenKeyword = true
                }
                for (i in clean.indices) {
                    processChar(clean, i)
                }
            }

            private fun processChar(clean: String, i: Int) {
                when (clean[i]) {
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    '=' -> recordEquals(clean, i)
                    '{' -> recordOpenBrace()
                    '}' -> recordCloseBrace()
                }
            }

            private fun recordEquals(clean: String, i: Int) {
                if (started) return
                if (parenDepth != 0) return
                if (bracketDepth != 0) return
                val prev = if (i > 0) clean[i - 1] else ' '
                val next = if (i + 1 < clean.length) clean[i + 1] else ' '
                if (prev in COMPARISON_CHARS) return
                if (next == '=') return
                hasEquals = true
            }

            private fun recordOpenBrace() {
                if (parenDepth == 0 && bracketDepth == 0) {
                    depth++
                    started = true
                } else if (started) {
                    depth++
                }
            }

            private fun recordCloseBrace() {
                if (started) {
                    depth--
                }
            }
        }

        private fun isLineContinuation(trimmed: String): Boolean =
            LINE_CONTINUATION_SUFFIXES.any { trimmed.endsWith(it) }

        private fun isNextLineContinuation(nextTrimmed: String): Boolean =
            NEXT_LINE_CONTINUATION_PREFIXES.any { nextTrimmed.startsWith(it) }

        private fun findNextNonEmptyLineIndex(cleanLines: List<String>, fromIndex: Int): Int {
            var lookAhead = fromIndex
            while (lookAhead < cleanLines.size && cleanLines[lookAhead].trim().isEmpty()) {
                lookAhead++
            }
            return lookAhead
        }

        private fun isBlockContinuation(trimmed: String, cleanLines: List<String>, fromIndex: Int): Boolean {
            if (isLineContinuation(trimmed)) return true
            val lookAhead = findNextNonEmptyLineIndex(cleanLines, fromIndex)
            if (lookAhead >= cleanLines.size) return false
            return isNextLineContinuation(cleanLines[lookAhead].trim())
        }

        private fun isNonEqualsTerminated(nextTrimmed: String): Boolean =
            !nextTrimmed.startsWith("=") && !nextTrimmed.startsWith("=>")

        private fun isIndentedPastBase(lines: List<String>, startLine: Int, lookAhead: Int): Boolean {
            val baseIndent = lines[startLine - 1].takeWhile { it == ' ' || it == '\t' }.length
            val nextIndent = lines[lookAhead].takeWhile { it == ' ' || it == '\t' }.length
            return nextIndent <= baseIndent
        }

        private fun isSingleExpressionTerminated(
            lines: List<String>,
            cleanLines: List<String>,
            startLine: Int,
            lookAhead: Int,
            hasEquals: Boolean
        ): Boolean {
            val nextTrimmed = cleanLines[lookAhead].trim()
            if (nextTrimmed.startsWith("{")) return false
            if (!hasEquals) return isNonEqualsTerminated(nextTrimmed)
            if (isNewDeclarationStart(nextTrimmed)) return true
            if (nextTrimmed.startsWith("}")) return true
            return isIndentedPastBase(lines, startLine, lookAhead)
        }

        private fun checkExpressionEndLine(
            lines: List<String>,
            cleanLines: List<String>,
            clean: String,
            idx: Int,
            startLine: Int,
            hasEquals: Boolean
        ): Int? {
            val trimmed = clean.trimEnd()
            if (trimmed.endsWith(";")) return idx + 1
            if (isLineContinuation(trimmed)) return null

            val lookAhead = findNextNonEmptyLineIndex(cleanLines, idx + 1)
            if (lookAhead >= cleanLines.size) return idx + 1
            if (isNextLineContinuation(cleanLines[lookAhead].trim())) return null

            if (isSingleExpressionTerminated(lines, cleanLines, startLine, lookAhead, hasEquals)) {
                return idx + 1
            }
            return null
        }

        private fun checkStartedBlockEnd(
            clean: String,
            cleanLines: List<String>,
            idx: Int,
            state: BlockScanState
        ): Int? {
            if (state.depth > 0) return null
            if (!state.hasEquals) return idx + 1
            if (isBlockContinuation(clean.trimEnd(), cleanLines, idx + 1)) return null
            return idx + 1
        }

        private fun checkLineTermination(
            lines: List<String>,
            cleanLines: List<String>,
            clean: String,
            idx: Int,
            startLine: Int,
            state: BlockScanState
        ): Int? {
            if (state.started) {
                return checkStartedBlockEnd(clean, cleanLines, idx, state)
            }
            if (state.isExpressionBoundary()) {
                return checkExpressionEndLine(lines, cleanLines, clean, idx, startLine, state.hasEquals)
            }
            return null
        }

        private fun findBlockEndLine(
            lines: List<String>,
            startLine: Int,
            precomputedCleanLines: List<String>? = null
        ): Int {
            if (startLine > lines.size) return lines.size

            val startIdx = startLine - 1
            val cleanLines = precomputedCleanLines ?: cleanLinesFrom(lines, 0)
            val state = BlockScanState()

            for (idx in startIdx until cleanLines.size) {
                val clean = cleanLines[idx]
                state.scanLine(clean)

                val terminatedLine = checkLineTermination(lines, cleanLines, clean, idx, startLine, state)
                if (terminatedLine != null) return terminatedLine
            }
            return minOf(lines.size, startLine)
        }

        private val TRIPLE_QUOTE_TOKENS = setOf("\"\"\"", "'''")

        private class PythonBlockScanState(val baseIndent: Int) {
            var parenDepth = 0
            var bracketDepth = 0
            var braceDepth = 0
            var headerEnded = false
            var inTripleQuote: String? = null

            fun isBalanced(): Boolean {
                if (inTripleQuote != null) return false
                if (parenDepth != 0) return false
                if (bracketDepth != 0) return false
                if (braceDepth != 0) return false
                return true
            }

            fun handleInTripleQuote(raw: String): Boolean {
                val quote = inTripleQuote ?: return false
                val closePos = raw.indexOf(quote)
                if (closePos >= 0) {
                    inTripleQuote = null
                }
                return true
            }

            fun isBlockTerminated(isPastStart: Boolean, raw: String, trimmed: String): Boolean {
                if (!isPastStart) return false
                if (!headerEnded) return false
                if (!isBalanced()) return false
                val indent = raw.takeWhile { it == ' ' || it == '\t' }.length
                if (indent > baseIndent) return false
                if (trimmed.startsWith(")")) return false
                if (trimmed.startsWith("]")) return false
                if (trimmed.startsWith("}")) return false
                return true
            }

            fun scanLine(raw: String, masked: String) {
                scanTripleQuotes(raw.substringBefore("#"))
                updateDepths(masked)
                if (isBalanced() && masked.trim().endsWith(":")) {
                    headerEnded = true
                }
            }

            private fun scanTripleQuotes(stripped: String) {
                var j = 0
                while (j < stripped.length) {
                    j = stepTripleQuote(stripped, j)
                    if (inTripleQuote != null) break
                }
            }

            private fun stepTripleQuote(stripped: String, j: Int): Int {
                if (j + 2 >= stripped.length) return j + 1
                val sub3 = stripped.substring(j, j + 3)
                if (sub3 !in TRIPLE_QUOTE_TOKENS) return j + 1
                val closeOnSameLine = stripped.indexOf(sub3, j + 3)
                if (closeOnSameLine >= 0) return closeOnSameLine + 3
                inTripleQuote = sub3
                return j + 3
            }

            private fun updateDepths(masked: String) {
                for (i in masked.indices) {
                    stepDepth(masked[i])
                }
            }

            private fun stepDepth(ch: Char) {
                when (ch) {
                    '(' -> parenDepth++
                    ')' -> decParen()
                    '[' -> bracketDepth++
                    ']' -> decBracket()
                    '{' -> braceDepth++
                    '}' -> decBrace()
                }
            }

            private fun decParen() { if (parenDepth > 0) parenDepth-- }
            private fun decBracket() { if (bracketDepth > 0) bracketDepth-- }
            private fun decBrace() { if (braceDepth > 0) braceDepth-- }
        }

        private class PythonBlockScanner(
            private val lines: List<String>,
            private val maskedLines: List<String>,
            private val startLine: Int,
            baseIndent: Int
        ) {
            private val state = PythonBlockScanState(baseIndent)
            private var lastContentLine = startLine

            fun scan(startIdx: Int): Int {
                for (idx in startIdx until lines.size) {
                    if (processLine(idx, lines[idx])) break
                }
                return lastContentLine
            }

            private fun processLine(idx: Int, raw: String): Boolean {
                if (state.handleInTripleQuote(raw)) {
                    lastContentLine = idx + 1
                    return false
                }
                val masked = resolveMaskedLine(idx, raw)
                val trimmed = masked.trim()
                if (trimmed.isEmpty()) return false

                if (state.isBlockTerminated(idx >= startLine, raw, trimmed)) {
                    return true
                }
                state.scanLine(raw, masked)
                lastContentLine = idx + 1
                return false
            }

            private fun resolveMaskedLine(idx: Int, raw: String): String {
                if (idx < maskedLines.size) return maskedLines[idx]
                return raw.substringBefore("#")
            }
        }

        private fun findPythonBlockEndLine(lines: List<String>, maskedLines: List<String>, startLine: Int): Int {
            if (startLine > lines.size) return lines.size
            val startIdx = startLine - 1
            val baseIndent = lines[startIdx].takeWhile { it == ' ' || it == '\t' }.length
            return PythonBlockScanner(lines, maskedLines, startLine, baseIndent).scan(startIdx)
        }

        private fun scanMaskedHeader(state: PythonColonScanState, masked: String): Boolean {
            for (i in masked.indices) {
                stepColonScan(state, masked[i])
            }
            return state.isAtHeaderColon() && masked.endsWith(":")
        }

        private fun buildPythonHeaderSignature(lines: List<String>, maskedLines: List<String>, startLine: Int, endLine: Int): String {
            if (startLine > lines.size) return ""
            val safeEnd = minOf(lines.size, endLine)
            val parts = mutableListOf<String>()
            val state = PythonColonScanState()
            for (lineNo in startLine..safeEnd) {
                val raw = lines[lineNo - 1].substringBefore("#").trim()
                val masked = if (lineNo - 1 < maskedLines.size) maskedLines[lineNo - 1].trim() else raw
                if (raw.isNotEmpty()) parts.add(raw)
                if (scanMaskedHeader(state, masked)) break
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

        private class HeaderDepthTracker(var paren: Int = 0, var angle: Int = 0) {
            val isTopLevel: Boolean get() = paren == 0 && angle == 0

            fun adjust(c: Char): Boolean {
                when (c) {
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    else -> return false
                }
                return true
            }
        }

        private fun buildDeclarationHeader(
            lines: List<String>,
            startLine: Int,
            endLine: Int
        ): String {
            val safeEnd = minOf(lines.size, endLine)
            val headerBuilder = StringBuilder()
            for (lineNo in startLine..safeEnd) {
                val raw = lines[lineNo - 1]
                val line = if (lineNo == startLine) stripLeadingInlineAnnotations(raw) else raw
                val clean = line.substringBefore("//")
                val braceIdx = clean.indexOf('{')
                if (braceIdx >= 0) {
                    headerBuilder.append(clean.substring(0, braceIdx))
                    break
                }
                headerBuilder.append(clean).append(' ')
            }
            return headerBuilder.toString()
        }

        private fun findSupertypesColonIndex(header: String): Int {
            val tracker = HeaderDepthTracker()
            for (i in header.indices) {
                val c = header[i]
                if (tracker.adjust(c)) continue
                if (c == ':' && tracker.isTopLevel && isStandaloneColon(header, i)) {
                    return i
                }
            }
            return -1
        }

        private fun isStandaloneColon(header: String, i: Int): Boolean {
            val nextChar = if (i + 1 < header.length) header[i + 1] else ' '
            val prevChar = if (i > 0) header[i - 1] else ' '
            return nextChar != ':' && prevChar != ':'
        }

        private fun splitTopLevelSupertypes(superPart: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            val tracker = HeaderDepthTracker()
            for (c in superPart) {
                if (c == ',' && tracker.isTopLevel) {
                    parts.add(current.toString())
                    current.clear()
                    continue
                }
                tracker.adjust(c)
                current.append(c)
            }
            if (current.isNotEmpty()) parts.add(current.toString())
            return parts
        }

        private fun parseSupertypeEntry(st: String): Pair<String, String>? {
            val trimmed = st.trim()
            val clean = trimmed.substringBefore("(").substringBefore("<").substringBefore(" by ").trim()
                .substringAfterLast('.')
            if (clean.isEmpty() || !clean[0].isUpperCase()) return null
            val edgeType = if ("(" in trimmed.substringBefore(" by ")) "INHERITS" else "IMPLEMENTS"
            return Pair(clean, edgeType)
        }

        private fun parseSupertypeEntries(parts: List<String>): List<Pair<String, String>> {
            val results = mutableListOf<Pair<String, String>>()
            for (st in parts) {
                val entry = parseSupertypeEntry(st)
                if (entry != null) results.add(entry)
            }
            return results
        }

        private fun extractKotlinSupertypes(
            lines: List<String>,
            startLine: Int,
            endLine: Int
        ): List<Pair<String, String>> {
            val header = buildDeclarationHeader(lines, startLine, endLine)
            val colonIdx = findSupertypesColonIndex(header)
            if (colonIdx < 0) return emptyList()
            val superPart = header.substring(colonIdx + 1)
            val parts = splitTopLevelSupertypes(superPart)
            return parseSupertypeEntries(parts)
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
        private class BracketDepths(var p: Int = 0, var b: Int = 0, var a: Int = 0) {
            fun isClosed(): Boolean = p <= 0 && b <= 0 && a <= 0
            fun update(line: String, language: String) {
                for (c in line) {
                    when (c) {
                        '(' -> p--
                        ')' -> p++
                        '[' -> b--
                        ']' -> b++
                        '<' -> if (language == "cpp") a--
                        '>' -> if (language == "cpp") a++
                    }
                }
            }
        }

        private fun isMultilineAnnotationEnd(trimmed: String, language: String): Boolean {
            return trimmed.endsWith(")") || trimmed.endsWith("]") || (language == "cpp" && trimmed.endsWith(">"))
        }

        private fun skipMultilineAnnotation(
            lines: List<String>,
            cur: Int,
            trimmed: String,
            language: String
        ): Int? {
            if (!isMultilineAnnotationEnd(trimmed, language)) return null
            val depths = BracketDepths()
            var scanIdx = cur
            while (scanIdx >= 0) {
                val sLine = lines[scanIdx].trim()
                depths.update(sLine, language)
                if (depths.isClosed() && isAnnotationOrAttributeLine(sLine, language)) {
                    return scanIdx - 1
                }
                scanIdx--
            }
            return null
        }

        private fun parseLeadingDocComment(
            lines: List<String>,
            cur: Int,
            trimmed: String,
            language: String
        ): String? {
            if (trimmed.endsWith("*/")) {
                return parseBlockDocComment(lines, cur)
            }
            if (language == "rust" && (trimmed.startsWith("//!") || trimmed.startsWith("/*!"))) {
                return null
            }
            if (trimmed.startsWith("///")) {
                return parseTripleSlashDocComment(lines, cur)
            }
            if (trimmed.startsWith("//")) {
                val low = trimmed.lowercase()
                if (NON_DOC_LINE_PREFIXES.any { low.startsWith(it) }) {
                    return null
                }
                return parseDoubleSlashDocComment(lines, cur)
            }
            return null
        }

        private fun findNextPrecedingIndex(
            lines: List<String>,
            cur: Int,
            trimmed: String,
            language: String
        ): Int? {
            if (isAnnotationOrAttributeLine(trimmed, language)) {
                return cur - 1
            }
            return skipMultilineAnnotation(lines, cur, trimmed, language)
        }

        fun extractDocDescription(
            lines: List<String>,
            matchStartLine: Int,
            language: String
        ): String? {
            val startIdx = matchStartLine - 1
            if (startIdx !in 1..lines.size) return null

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
                val nextIdx = findNextPrecedingIndex(lines, cur, trimmed, language)
                if (nextIdx != null) {
                    cur = nextIdx
                    blankCount = 0
                    continue
                }
                return parseLeadingDocComment(lines, cur, trimmed, language)
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
            val inlineDoc = extractPythonInlineDoc(lines, declStartLine)
            if (inlineDoc != null) return inlineDoc

            val blockDoc = extractPythonBlockDoc(lines, hdrEndLine, bodyEndLine)
            if (blockDoc != null) return blockDoc

            return extractPythonPrecedingComments(lines, declStartLine)
        }

        private val PYTHON_TRIPLE_QUOTES = listOf(
            "\"\"\"" to "\"\"\"",
            "r\"\"\"" to "\"\"\"",
            "f\"\"\"" to "\"\"\"",
            "'''" to "'''",
            "r'''" to "'''",
            "f'''" to "'''"
        )

        private fun detectPythonTripleQuote(text: String): String? {
            for (entry in PYTHON_TRIPLE_QUOTES) {
                if (text.startsWith(entry.first)) return entry.second
            }
            return null
        }

        private class PythonColonScanState {
            var parenDepth = 0
            var bracketDepth = 0

            fun onParenOpen() { parenDepth++ }
            fun onParenClose() { if (parenDepth > 0) parenDepth-- }
            fun onBracketOpen() { bracketDepth++ }
            fun onBracketClose() { if (bracketDepth > 0) bracketDepth-- }
            fun isAtHeaderColon(): Boolean = parenDepth == 0 && bracketDepth == 0
        }

        private fun stepColonScan(state: PythonColonScanState, ch: Char): Boolean {
            when (ch) {
                '(' -> state.onParenOpen()
                ')' -> state.onParenClose()
                '[' -> state.onBracketOpen()
                ']' -> state.onBracketClose()
                ':' -> return state.isAtHeaderColon()
            }
            return false
        }

        private fun findPythonHeaderColonIndex(line: String): Int {
            val state = PythonColonScanState()
            for (i in line.indices) {
                if (stepColonScan(state, line[i])) return i
            }
            return -1
        }

        private fun extractPythonInlineDoc(lines: List<String>, declStartLine: Int): String? {
            val declLineIdx = declStartLine - 1
            if (declLineIdx !in lines.indices) return null
            val declLine = lines[declLineIdx]
            val colonIdx = findPythonHeaderColonIndex(declLine)
            if (colonIdx < 0) return null
            if (colonIdx + 1 >= declLine.length) return null

            val afterColon = declLine.substring(colonIdx + 1).trim()
            val quote = detectPythonTripleQuote(afterColon) ?: return null
            val quotePos = afterColon.indexOf(quote)
            val afterFirst = afterColon.substring(quotePos + quote.length)
            if (!afterFirst.contains(quote)) return null
            val content = afterFirst.substringBefore(quote).trim()
            return if (content.isNotEmpty()) content else null
        }

        private fun findFirstPythonDocLineIndex(lines: List<String>, start: Int, end: Int): Int {
            for (idx in start until end) {
                val trimmed = lines[idx].trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("#")) continue
                return idx
            }
            return -1
        }

        private fun isEligibleDocLine(line: String): Boolean {
            if (line.isEmpty()) return false
            if (line.startsWith(":")) return false
            if (line.startsWith("@")) return false
            return true
        }

        private fun isDocTerminalLine(line: String): Boolean {
            if (line.startsWith(":")) return true
            if (line.startsWith("@")) return true
            return false
        }

        private class MultilineDocCollector(private val quote: String) {
            val docLines = mutableListOf<String>()

            fun addInitial(initial: String) {
                val trimmed = initial.trim()
                if (trimmed.isNotEmpty()) docLines.add(trimmed)
            }

            fun processLine(curLine: String): Boolean {
                if (curLine.contains(quote)) {
                    val beforeEnd = curLine.substringBefore(quote).trim()
                    if (isEligibleDocLine(beforeEnd)) docLines.add(beforeEnd)
                    return false
                }
                if (isDocTerminalLine(curLine)) return false
                if (curLine.isNotEmpty()) docLines.add(curLine)
                return true
            }

            fun buildResult(): String? {
                val result = docLines.joinToString(" ").trim()
                return if (result.isNotEmpty()) result else null
            }
        }

        private fun collectMultilineDocContent(
            lines: List<String>,
            startIdx: Int,
            searchEnd: Int,
            quote: String,
            afterFirst: String
        ): String? {
            val collector = MultilineDocCollector(quote)
            collector.addInitial(afterFirst)
            for (idx in startIdx until searchEnd) {
                val shouldContinue = collector.processLine(lines[idx].trim())
                if (!shouldContinue) break
            }
            return collector.buildResult()
        }

        private fun extractPythonBlockDoc(lines: List<String>, hdrEndLine: Int, bodyEndLine: Int): String? {
            val searchStart = hdrEndLine
            val searchEnd = minOf(lines.size, bodyEndLine)
            val docLineIdx = findFirstPythonDocLineIndex(lines, searchStart, searchEnd)
            if (docLineIdx == -1) return null

            val line = lines[docLineIdx].trim()
            val quote = detectPythonTripleQuote(line) ?: return null
            val afterFirst = line.substring(line.indexOf(quote) + quote.length)
            if (afterFirst.contains(quote)) {
                val content = afterFirst.substringBefore(quote).trim()
                return if (content.isNotEmpty()) content else null
            }
            return collectMultilineDocContent(lines, docLineIdx + 1, searchEnd, quote, afterFirst)
        }

        private fun findPrecedingCommentEndIndex(lines: List<String>, startIdx: Int): Int {
            var cur = startIdx - 1
            while (cur >= 0 && lines[cur].trim().startsWith("@")) cur--
            return cur
        }

        private fun isPythonNonDocComment(lowercaseLine: String): Boolean {
            for (prefix in PYTHON_NON_DOC_PREFIXES) {
                if (lowercaseLine.startsWith(prefix)) return true
            }
            return false
        }

        private fun findPrecedingCommentStartIndex(lines: List<String>, commentEnd: Int): Int {
            var cStart = commentEnd
            while (cStart > 0 && lines[cStart - 1].trim().startsWith("#")) {
                val prevLow = lines[cStart - 1].trim().lowercase()
                if (isPythonNonDocComment(prevLow)) break
                cStart--
            }
            return cStart
        }

        private fun collectPrecedingCommentLines(lines: List<String>, cStart: Int, cEnd: Int): String? {
            val commentLines = mutableListOf<String>()
            for (i in cStart..cEnd) {
                val cTrim = lines[i].trim()
                val cLow = cTrim.lowercase()
                if (isPythonNonDocComment(cLow)) break
                val c = cTrim.removePrefix("#").trim()
                if (c.isNotEmpty()) {
                    commentLines.add(c)
                }
            }
            val result = commentLines.joinToString(" ").trim()
            return if (result.isNotEmpty()) result else null
        }

        private fun extractPythonPrecedingComments(lines: List<String>, declStartLine: Int): String? {
            val startIdx = declStartLine - 1
            if (startIdx <= 0) return null
            val cEnd = findPrecedingCommentEndIndex(lines, startIdx)
            if (cEnd < 0) return null
            if (!lines[cEnd].trim().startsWith("#")) return null
            val cStart = findPrecedingCommentStartIndex(lines, cEnd)
            return collectPrecedingCommentLines(lines, cStart, cEnd)
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
                    val isSelfCall = callee == node.name && (receiver.isNullOrEmpty() || receiver == "this")
                    if (!isSelfCall && callee !in CALL_STOPWORDS && seenCallees.add(targetSym)) {
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

        private fun extractSwiftImports(maskedText: String): List<Pair<String, Int>> =
            SWIFT_IMP_RE.findAll(maskedText).map { match ->
                Pair(match.groupValues[1], maskedText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

        private fun parseSwiftCallEdge(
            callMatch: MatchResult,
            node: GraphNode,
            body: String,
            seenCallees: MutableSet<String>,
            projectId: String
        ): GraphEdge? {
            val receiver = callMatch.groups["receiver"]?.value?.trim()
            val callee = callMatch.groups["callee"]?.value ?: return null
            val isSelfCall = callee == node.name && (receiver.isNullOrEmpty() || receiver == "self")
            if (isSelfCall || callee in CALL_STOPWORDS) return null
            val targetSym = if (!receiver.isNullOrEmpty()) "$receiver.$callee" else callee
            if (!seenCallees.add(targetSym)) return null

            val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
            return GraphEdge(
                edgeId = "${node.nodeId}:call:$targetSym:$callLine",
                projectId = projectId,
                sourceNodeId = node.nodeId,
                targetNodeId = "sym:$targetSym",
                edgeType = "CALLS",
                provenance = "INFERRED_CALL",
                confidence = 0.85,
                lineNumber = callLine
            )
        }

        private fun extractSwiftCallEdges(
            node: GraphNode,
            allNodes: List<GraphNode>,
            maskedLines: List<String>,
            projectId: String
        ): List<GraphEdge> {
            val childRanges = buildChildRanges(allNodes, node)
            val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
            val seenCallees = mutableSetOf<String>()
            val edges = mutableListOf<GraphEdge>()
            for (callMatch in CALL_RE.findAll(body)) {
                val edge = parseSwiftCallEdge(callMatch, node, body, seenCallees, projectId)
                if (edge != null) edges.add(edge)
            }
            return edges
        }

        private fun extractSwiftDeclHeader(match: MatchResult): Triple<String, String, String>? {
            val name = match.groups["name"]?.value ?: return null
            val groups = match.groups
            val kw = groups["kw"]?.value ?: "class"
            val indent = groups["indent"]?.value ?: ""
            return Triple(indent, kw, name)
        }

        private fun buildSwiftNodeIdentifiers(fileId: String, relPath: String, name: String, scopeStack: List<ScopeEntry>): Pair<String, String> {
            val prefix = scopeStack.joinToString(".") { it.name }
            return if (prefix.isNotEmpty()) {
                Pair("$fileId#$prefix.$name", "$relPath:$prefix.$name")
            } else {
                Pair("$fileId#$name", "$relPath:$name")
            }
        }

        private fun buildSwiftNode(
            nodeId: String,
            qualifiedName: String,
            name: String,
            kw: String,
            startLine: Int,
            endLine: Int,
            isTopLevel: Boolean,
            lines: List<String>,
            maskedLines: List<String>,
            fileId: String,
            relPath: String,
            projectId: String,
            moduleId: String,
            sourceSet: String
        ): GraphNode {
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
            return GraphNode(
                nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                qualifiedName = qualifiedName, kind = kind,
                signature = sigLine, startLine = startLine, endLine = endLine,
                moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                archLayer = arch, isTestDouble = isTd,
                description = description, isTopLevel = isTopLevel,
                cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                maxNestingDepth = cVec.maxNesting, complexityScore = cScore
            )
        }

        private fun buildSwiftImportEdges(nodeId: String, projectId: String, imports: List<Pair<String, Int>>): List<GraphEdge> {
            return imports.map { (impTarget, impLine) ->
                GraphEdge(
                    edgeId = "$nodeId:imp:$impTarget:$impLine",
                    projectId = projectId,
                    sourceNodeId = nodeId,
                    targetNodeId = "sym:$impTarget",
                    edgeType = "IMPORTS",
                    lineNumber = impLine
                )
            }
        }

        private fun isSwiftTopLevel(indent: String, scopeStack: List<ScopeEntry>): Boolean =
            indent.isEmpty() && scopeStack.isEmpty()

        private fun recordSwiftScopeEntry(kw: String, name: String, startLine: Int, endLine: Int, scopeStack: MutableList<ScopeEntry>) {
            if (kw in SWIFT_SCOPE_KEYWORDS) {
                scopeStack.add(ScopeEntry(name, startLine, endLine))
            }
        }

        private fun recordSwiftNodeAndEdges(
            node: GraphNode,
            isTopLevel: Boolean,
            nodeId: String,
            projectId: String,
            imports: List<Pair<String, Int>>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ) {
            nodes.add(node)
            if (isTopLevel) {
                edges.addAll(buildSwiftImportEdges(nodeId, projectId, imports))
            }
        }

        private fun processSwiftDeclaration(
            match: MatchResult,
            maskedText: String,
            lines: List<String>,
            maskedLines: List<String>,
            cleanLines: List<String>,
            scopeStack: MutableList<ScopeEntry>,
            fileId: String,
            relPath: String,
            projectId: String,
            moduleId: String,
            sourceSet: String,
            imports: List<Pair<String, Int>>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ): Boolean {
            val (indent, kw, name) = extractSwiftDeclHeader(match) ?: return false
            val startLine = maskedText.substring(0, match.range.first).count { it == '\n' } + 1
            val endLine = findBlockEndLine(lines, startLine, cleanLines)

            pruneScopeStack(scopeStack, startLine)
            val (nodeId, qualifiedName) = buildSwiftNodeIdentifiers(fileId, relPath, name, scopeStack)
            val isTopLevel = isSwiftTopLevel(indent, scopeStack)

            val node = buildSwiftNode(
                nodeId, qualifiedName, name, kw, startLine, endLine, isTopLevel,
                lines, maskedLines, fileId, relPath, projectId, moduleId, sourceSet
            )
            recordSwiftNodeAndEdges(node, isTopLevel, nodeId, projectId, imports, nodes, edges)
            recordSwiftScopeEntry(kw, name, startLine, endLine, scopeStack)

            return isTopLevel && kw != "extension"
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
            val imports = extractSwiftImports(maskedText)

            var topLevelCount = 0
            for (match in SWIFT_DECL_RE.findAll(maskedText)) {
                if (processSwiftDeclaration(
                    match, maskedText, lines, maskedLines, cleanLines, scopeStack,
                    fileId, relPath, projectId, moduleId, sourceSet, imports, nodes, edges
                )) {
                    topLevelCount++
                }
            }

            for (node in nodes) {
                edges.addAll(extractSwiftCallEdges(node, nodes, maskedLines, projectId))
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
                    val isSelfCall = callee == node.name && (receiver.isNullOrEmpty() || receiver == "this")
                    if (!isSelfCall && callee !in CALL_STOPWORDS && seenCallees.add(targetSym)) {
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

        private fun extractRustImports(maskedText: String): List<Pair<String, Int>> =
            RS_USE_RE.findAll(maskedText).map { match ->
                val sym = match.groupValues[1].substringAfterLast("::")
                Pair(sym, maskedText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()

        private fun resolveRustKind(kw: String): String = when (kw) {
            "fn" -> "function"
            "trait" -> "interface"
            "enum" -> "enum"
            "type" -> "typealias"
            "mod" -> "module"
            else -> "class"
        }

        private fun buildRustNodeIdentifiers(fileId: String, relPath: String, name: String, scopeStack: List<ScopeEntry>): Pair<String, String> {
            val prefix = scopeStack.joinToString("::") { it.name }
            return if (prefix.isNotEmpty()) {
                Pair("$fileId#$prefix::$name", "$relPath:$prefix::$name")
            } else {
                Pair("$fileId#$name", "$relPath:$name")
            }
        }

        private fun buildRustNode(
            nodeId: String,
            qualifiedName: String,
            name: String,
            kind: String,
            startLine: Int,
            endLine: Int,
            isTopLevel: Boolean,
            lines: List<String>,
            maskedLines: List<String>,
            fileId: String,
            relPath: String,
            projectId: String,
            moduleId: String,
            sourceSet: String
        ): GraphNode {
            val sigLine = if (startLine <= lines.size) lines[startLine - 1].trim() else name
            val (sem, arch, isTd) = SemioticLayerClassifier.classify(relPath, name)
            val description = if (isTopLevel && kind in RUST_DOC_KINDS) extractDocDescription(lines, startLine, "rust") else null
            val (cVec, cScore) = computeComplexityMetrics(
                kind = kind,
                startLine = startLine,
                endLine = endLine,
                maskedLines = maskedLines,
                lines = lines,
                language = "rust",
                functionName = name
            )
            return GraphNode(
                nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                qualifiedName = qualifiedName, kind = kind,
                signature = sigLine, startLine = startLine, endLine = endLine,
                moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                archLayer = arch, isTestDouble = isTd,
                description = description, isTopLevel = isTopLevel,
                cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                maxNestingDepth = cVec.maxNesting, complexityScore = cScore
            )
        }

        private fun buildRustImportEdges(nodeId: String, projectId: String, imports: List<Pair<String, Int>>): List<GraphEdge> {
            return imports.map { (impTarget, impLine) ->
                GraphEdge(
                    edgeId = "$nodeId:imp:$impTarget:$impLine",
                    projectId = projectId,
                    sourceNodeId = nodeId,
                    targetNodeId = "sym:$impTarget",
                    edgeType = "IMPORTS",
                    lineNumber = impLine
                )
            }
        }

        private fun isRustTopLevel(indent: String, scopeStack: List<ScopeEntry>): Boolean =
            indent.isEmpty() && scopeStack.isEmpty()

        private fun recordRustScopeEntry(kw: String, name: String, startLine: Int, endLine: Int, scopeStack: MutableList<ScopeEntry>) {
            if (kw in RUST_SCOPE_KEYWORDS) {
                scopeStack.add(ScopeEntry(name, startLine, endLine))
            }
        }

        private fun recordRustNodeAndEdges(
            node: GraphNode,
            isTopLevel: Boolean,
            nodeId: String,
            projectId: String,
            imports: List<Pair<String, Int>>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ) {
            nodes.add(node)
            if (isTopLevel) {
                edges.addAll(buildRustImportEdges(nodeId, projectId, imports))
            }
        }

        private fun processRustDeclaration(
            match: MatchResult,
            maskedText: String,
            lines: List<String>,
            maskedLines: List<String>,
            cleanLines: List<String>,
            scopeStack: MutableList<ScopeEntry>,
            fileId: String,
            relPath: String,
            projectId: String,
            moduleId: String,
            sourceSet: String,
            imports: List<Pair<String, Int>>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ): Boolean {
            val (indent, kw, name) = extractSwiftDeclHeader(match) ?: return false
            val kind = resolveRustKind(kw)
            val startLine = maskedText.substring(0, match.range.first).count { it == '\n' } + 1
            val endLine = findBlockEndLine(lines, startLine, cleanLines)

            pruneScopeStack(scopeStack, startLine)
            val (nodeId, qualifiedName) = buildRustNodeIdentifiers(fileId, relPath, name, scopeStack)
            val isTopLevel = isRustTopLevel(indent, scopeStack)

            val node = buildRustNode(
                nodeId, qualifiedName, name, kind, startLine, endLine, isTopLevel,
                lines, maskedLines, fileId, relPath, projectId, moduleId, sourceSet
            )
            recordRustNodeAndEdges(node, isTopLevel, nodeId, projectId, imports, nodes, edges)
            recordRustScopeEntry(kw, name, startLine, endLine, scopeStack)

            return isTopLevel && kind !in RUST_NON_COUNTABLE_TOP_LEVEL
        }

        private fun parseRustCallEdge(
            callMatch: MatchResult,
            node: GraphNode,
            body: String,
            seenCallees: MutableSet<String>,
            projectId: String
        ): GraphEdge? {
            val receiver = callMatch.groups["receiver"]?.value?.trim()
            val callee = callMatch.groups["callee"]?.value ?: return null
            val targetSym = when {
                callee == "new" && !receiver.isNullOrEmpty() -> receiver
                !receiver.isNullOrEmpty() -> "$receiver::$callee"
                else -> callee
            }
            val isSelfCall = targetSym == node.name || (callee == node.name && (receiver.isNullOrEmpty() || receiver == "self"))
            if (isSelfCall || targetSym in CALL_STOPWORDS || !seenCallees.add(targetSym)) return null

            val callLine = node.startLine + body.substring(0, callMatch.range.first).count { it == '\n' }
            return GraphEdge(
                edgeId = "${node.nodeId}:call:$targetSym:$callLine",
                projectId = projectId,
                sourceNodeId = node.nodeId,
                targetNodeId = "sym:$targetSym",
                edgeType = "CALLS",
                provenance = "INFERRED_CALL",
                confidence = 0.85,
                lineNumber = callLine
            )
        }

        private fun extractRustCallEdges(
            node: GraphNode,
            allNodes: List<GraphNode>,
            maskedLines: List<String>,
            projectId: String
        ): List<GraphEdge> {
            val childRanges = buildChildRanges(allNodes, node)
            val body = maskChildLineRanges(maskedLines, node.startLine, node.endLine, childRanges)
            val seenCallees = mutableSetOf<String>()
            val edges = mutableListOf<GraphEdge>()
            for (callMatch in CALL_RE.findAll(body)) {
                val edge = parseRustCallEdge(callMatch, node, body, seenCallees, projectId)
                if (edge != null) edges.add(edge)
            }
            return edges
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
            val imports = extractRustImports(maskedText)

            var topLevelCount = 0
            for (match in RS_DECL_RE.findAll(maskedText)) {
                if (processRustDeclaration(
                    match, maskedText, lines, maskedLines, cleanLines, scopeStack,
                    fileId, relPath, projectId, moduleId, sourceSet, imports, nodes, edges
                )) {
                    topLevelCount++
                }
            }

            for (node in nodes) {
                edges.addAll(extractRustCallEdges(node, nodes, maskedLines, projectId))
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
                    val isSelfCall = callee == node.name && (receiver.isNullOrEmpty() || receiver == "this")
                    if (!isSelfCall && callee !in CALL_STOPWORDS && seenCallees.add(targetSym)) {
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
            val state = PythonColonScanState()
            for (lineNo in startLine..safeEnd) {
                val masked = maskedLines[lineNo - 1].trim()
                if (scanMaskedHeader(state, masked)) return lineNo
            }
            return startLine
        }

        private val ALLOWED_PYTHON_DUNDERS = setOf(
            "__init__", "__enter__", "__exit__", "__call__", "__str__", "__repr__",
            "__getitem__", "__setitem__", "__len__", "__iter__", "__next__", "__contains__",
            "__aenter__", "__aexit__", "__eq__", "__hash__"
        )

        private fun isAllowedPythonDeclaration(name: String): Boolean {
            if (!name.startsWith("_")) return true
            val isDunder = name.startsWith("__") && name.endsWith("__")
            return isDunder && name in ALLOWED_PYTHON_DUNDERS
        }

        private fun extractPythonImports(maskedText: String): List<Pair<String, Int>> {
            return PY_IMP_RE.findAll(maskedText).map { match ->
                val mod = match.groupValues[1].ifEmpty { match.groupValues[2] }
                Pair(mod, maskedText.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()
        }

        private fun buildPythonNodeIdentifiers(
            fileId: String,
            relPath: String,
            name: String,
            scopeStack: List<ScopeEntry>
        ): Pair<String, String> {
            val prefix = scopeStack.joinToString(".") { it.name }
            return if (prefix.isNotEmpty()) {
                Pair("$fileId#$prefix.$name", "$relPath:$prefix.$name")
            } else {
                Pair("$fileId#$name", "$relPath:$name")
            }
        }

        private fun buildPythonNode(
            nodeId: String,
            qualifiedName: String,
            name: String,
            kw: String,
            startLine: Int,
            endLine: Int,
            isTopLevel: Boolean,
            lines: List<String>,
            maskedLines: List<String>,
            fileId: String,
            relPath: String,
            projectId: String,
            moduleId: String,
            sourceSet: String
        ): GraphNode {
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

            return GraphNode(
                nodeId = nodeId, projectId = projectId, fileId = fileId, name = name,
                qualifiedName = qualifiedName, kind = kind,
                signature = sigLine, startLine = startLine, endLine = endLine,
                moduleId = moduleId, sourceSet = sourceSet, semioticLayer = sem,
                archLayer = arch, isTestDouble = isTd,
                description = description, isTopLevel = isTopLevel,
                cyclomaticComplexity = cVec.cyclomatic, cognitiveComplexity = cVec.cognitive,
                maxNestingDepth = cVec.maxNesting, complexityScore = cScore
            )
        }

        private fun buildPythonImportEdges(
            nodeId: String,
            projectId: String,
            imports: List<Pair<String, Int>>
        ): List<GraphEdge> {
            return imports.map { (impTarget, impLine) ->
                GraphEdge(
                    edgeId = "$nodeId:imp:$impTarget:$impLine",
                    projectId = projectId,
                    sourceNodeId = nodeId,
                    targetNodeId = "sym:$impTarget",
                    edgeType = "IMPORTS",
                    lineNumber = impLine
                )
            }
        }

        private fun isAllowedPythonCallee(callee: String, nodeName: String, receiver: String?): Boolean {
            val isSelfCall = callee == nodeName && (receiver.isNullOrEmpty() || receiver == "self")
            return !isSelfCall && callee !in CALL_STOPWORDS && !callee.startsWith("_")
        }

        private fun pruneScopeStack(scopeStack: MutableList<ScopeEntry>, startLine: Int) {
            while (scopeStack.isNotEmpty() && scopeStack.last().endLine < startLine) {
                scopeStack.removeAt(scopeStack.size - 1)
            }
        }

        private fun getGroupName(match: MatchResult): String? {
            val name = match.groups["name"]?.value
            return if (name != null && isAllowedPythonDeclaration(name)) name else null
        }

        private fun extractDeclHeader(match: MatchResult): Triple<String, String, String>? {
            val name = getGroupName(match) ?: return null
            val groups = match.groups
            val indent = groups["indent"]?.value ?: ""
            val kw = groups["kw"]?.value ?: "def"
            return Triple(indent, kw, name)
        }

        private fun appendScopeIfBlock(kw: String, name: String, startLine: Int, endLine: Int, scopeStack: MutableList<ScopeEntry>) {
            if ("class" in kw || "def" in kw) {
                scopeStack.add(ScopeEntry(name, startLine, endLine))
            }
        }

        private fun recordNodeAndEdges(
            node: GraphNode,
            isTopLevel: Boolean,
            nodeId: String,
            projectId: String,
            imports: List<Pair<String, Int>>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ) {
            nodes.add(node)
            if (isTopLevel) {
                edges.addAll(buildPythonImportEdges(nodeId, projectId, imports))
            }
        }

        private fun processPythonDeclaration(
            match: MatchResult,
            maskedText: String,
            lines: List<String>,
            maskedLines: List<String>,
            scopeStack: MutableList<ScopeEntry>,
            fileId: String,
            relPath: String,
            projectId: String,
            moduleId: String,
            sourceSet: String,
            imports: List<Pair<String, Int>>,
            nodes: MutableList<GraphNode>,
            edges: MutableList<GraphEdge>
        ): Boolean {
            val (indent, kw, name) = extractDeclHeader(match) ?: return false
            val startLine = maskedText.substring(0, match.range.first).count { it == '\n' } + 1
            val endLine = findPythonBlockEndLine(lines, maskedLines, startLine)

            pruneScopeStack(scopeStack, startLine)
            val (nodeId, qualifiedName) = buildPythonNodeIdentifiers(fileId, relPath, name, scopeStack)
            val isTopLevel = indent.isEmpty() && scopeStack.isEmpty()

            val node = buildPythonNode(
                nodeId = nodeId, qualifiedName = qualifiedName, name = name, kw = kw,
                startLine = startLine, endLine = endLine, isTopLevel = isTopLevel,
                lines = lines, maskedLines = maskedLines, fileId = fileId,
                relPath = relPath, projectId = projectId, moduleId = moduleId,
                sourceSet = sourceSet
            )
            recordNodeAndEdges(node, isTopLevel, nodeId, projectId, imports, nodes, edges)
            appendScopeIfBlock(kw, name, startLine, endLine, scopeStack)
            return isTopLevel
        }

        private fun buildChildRanges(nodes: List<GraphNode>, parent: GraphNode): List<IntRange> {
            return nodes.filter { child ->
                child.nodeId != parent.nodeId &&
                    child.startLine >= parent.startLine &&
                    child.endLine <= parent.endLine &&
                    (child.startLine > parent.startLine || child.endLine < parent.endLine)
            }.map { it.startLine..it.endLine }
        }

        private fun parseCallEdge(
            callMatch: MatchResult,
            node: GraphNode,
            scanStartLine: Int,
            body: String,
            seenCallees: MutableSet<String>,
            projectId: String
        ): GraphEdge? {
            val receiver = callMatch.groups["receiver"]?.value?.trim()
            val callee = callMatch.groups["callee"]?.value ?: return null
            val targetSym = if (!receiver.isNullOrEmpty()) "$receiver.$callee" else callee
            if (!isAllowedPythonCallee(callee, node.name, receiver) || !seenCallees.add(targetSym)) {
                return null
            }
            val callLine = scanStartLine + body.substring(0, callMatch.range.first).count { it == '\n' }
            return GraphEdge(
                edgeId = "${node.nodeId}:call:$targetSym:$callLine", projectId = projectId,
                sourceNodeId = node.nodeId, targetNodeId = "sym:$targetSym", edgeType = "CALLS",
                provenance = "INFERRED_CALL", confidence = 0.85, lineNumber = callLine
            )
        }

        private fun extractNodeCallEdges(
            node: GraphNode,
            nodes: List<GraphNode>,
            maskedLines: List<String>,
            projectId: String
        ): List<GraphEdge> {
            val childRanges = buildChildRanges(nodes, node)
            val headerEndLine = findPythonHeaderEndLine(maskedLines, node.startLine, node.endLine)
            val scanStartLine = if (headerEndLine < node.endLine) headerEndLine + 1 else node.startLine
            val body = maskChildLineRanges(maskedLines, scanStartLine, node.endLine, childRanges)
            val seenCallees = mutableSetOf<String>()
            val edges = mutableListOf<GraphEdge>()

            for (callMatch in CALL_RE.findAll(body)) {
                val edge = parseCallEdge(callMatch, node, scanStartLine, body, seenCallees, projectId)
                if (edge != null) edges.add(edge)
            }
            return edges
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
            val imports = extractPythonImports(maskedText)

            var topLevelCount = 0
            for (match in PY_DECL_RE.findAll(maskedText)) {
                val isTop = processPythonDeclaration(
                    match, maskedText, lines, maskedLines, scopeStack,
                    fileId, relPath, projectId, moduleId, sourceSet, imports, nodes, edges
                )
                if (isTop) topLevelCount++
            }

            for (node in nodes) {
                edges.addAll(extractNodeCallEdges(node, nodes, maskedLines, projectId))
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
