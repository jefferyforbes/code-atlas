package atlas.extract

import atlas.cli.AtlasCli
import atlas.db.SqliteGraphStore
import atlas.mcp.AtlasMcpServer
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid

/**
 * Unit and integration tests for [OutlineExtractor], CLI `atlas outline`, and MCP `atlas_outline`.
 * Adheres to BOOST-003 with a single top-level class.
 */
class OutlineExtractorTest {

    @Test
    fun testKotlinOutlineWithClassesAndMethods() {
        withTestWorkspace { tmpDir, dbPath, server ->
            val kotlinCode = """
                package com.example.shop

                interface CartRepository {
                    fun getCart(id: String): Cart?
                }

                class CartManager(private val repo: CartRepository) {
                    fun addItem(cartId: String, item: Item): Boolean {
                        val cart = repo.getCart(cartId) ?: return false
                        return true
                    }

                    fun removeItem(cartId: String, itemId: String): Boolean {
                        return true
                    }
                }

                fun createCartManager(): CartManager = CartManager(object : CartRepository {
                    override fun getCart(id: String): Cart? = null
                })
            """.trimIndent()

            val filePath = "$tmpDir/src/CartManager.kt"
            PlatformFiles.mkdirs("$tmpDir/src")
            PlatformFiles.writeText(filePath, kotlinCode)

            val res = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = "src/CartManager.kt"
            )

            val text = res["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(text.contains("### Outline: src/CartManager.kt"), "Header missing in: $text")
            assertTrue(text.contains("- interface CartRepository: 3-5"), "Interface CartRepository missing: $text")
            assertTrue(text.contains("- class CartManager: 7-16"), "Class CartManager missing: $text")
            assertTrue(text.contains("  - function addItem: 8-11"), "Method addItem missing: $text")
            assertTrue(text.contains("  - function removeItem: 13-15"), "Method removeItem missing: $text")
            assertTrue(text.contains("- function createCartManager: 18-20"), "Function createCartManager missing: $text")
            assertTrue(text.encodeToByteArray().size <= 400, "Output exceeds 400 bytes: ${text.encodeToByteArray().size}")
        }
    }

    @Test
    fun testPythonOutlineWithFunctionsAndClasses() {
        withTestWorkspace { tmpDir, dbPath, server ->
            val pythonCode = """
                '''Order processing pipeline.'''

                class OrderService:
                    '''Handles order placement and cancellation.'''
                    def __init__(self, db_client):
                        self.db = db_client

                    def submit_order(self, order_id: str) -> bool:
                        return True

                    def cancel_order(self, order_id: str) -> bool:
                        return False

                def calculate_tax(amount: float, rate: float = 0.2) -> float:
                    return amount * rate

                def format_order_summary(order_id: str, amount: float) -> str:
                    return f"Order {order_id}: ${'$'}{amount:.2f}"
            """.trimIndent()

            val filePath = "$tmpDir/src/order_service.py"
            PlatformFiles.mkdirs("$tmpDir/src")
            PlatformFiles.writeText(filePath, pythonCode)

            val res = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = "src/order_service.py"
            )

            val text = res["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(text.contains("### Outline: src/order_service.py"), "Header missing in: $text")
            assertTrue(text.contains("- class OrderService: 3-12"), "Class OrderService missing: $text")
            assertTrue(text.contains("  - function __init__: 5-6"), "Method __init__ missing: $text")
            assertTrue(text.contains("  - function submit_order: 8-9"), "Method submit_order missing: $text")
            assertTrue(text.contains("  - function cancel_order: 11-12"), "Method cancel_order missing: $text")
            assertTrue(text.contains("- function calculate_tax: 14-15"), "Function calculate_tax missing: $text")
            assertTrue(text.contains("- function format_order_summary: 17-18"), "Function format_order_summary missing: $text")
            assertTrue(text.encodeToByteArray().size <= 400, "Output exceeds 400 bytes: ${text.encodeToByteArray().size}")
        }
    }

    @Test
    fun testMarkdownOutlineFenceAwareIgnoresComments() {
        withTestWorkspace { tmpDir, dbPath, server ->
            val mdContent = """
                # Token Reduction Architecture

                Overview of token saving strategies.

                ## CLI Architecture

                ```bash
                # Shell comment inside backtick fence
                # Another shell comment
                atlas outline src/Service.kt
                ```

                ### Supervisor Multiplexer

                ~~~python
                # Python comment inside tilde fence
                def run():
                    pass
                ~~~

                ## Evaluation & Verification
            """.trimIndent()

            val filePath = "$tmpDir/docs/reduction.md"
            PlatformFiles.mkdirs("$tmpDir/docs")
            PlatformFiles.writeText(filePath, mdContent)

            val res = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = "docs/reduction.md"
            )

            val text = res["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(text.contains("### Outline: docs/reduction.md"), "Header missing: $text")
            assertTrue(text.contains("- # Token Reduction Architecture: 1"), "H1 missing: $text")
            assertTrue(text.contains("- ## CLI Architecture: 5"), "H2 missing: $text")
            assertTrue(text.contains("- ### Supervisor Multiplexer: 13"), "H3 missing: $text")
            assertTrue(text.contains("- ## Evaluation & Verification: 21"), "H2 evaluation missing: $text")

            assertFalse(text.contains("Shell comment"), "Comment inside backtick fence leaked: $text")
            assertFalse(text.contains("Python comment"), "Comment inside tilde fence leaked: $text")
            assertTrue(text.encodeToByteArray().size <= 400, "Output exceeds 400 bytes: ${text.encodeToByteArray().size}")
        }
    }

    @Test
    fun testOutputBudgetStrictlyUnder400BytesWithTruncation() {
        withTestWorkspace { tmpDir, dbPath, server ->
            val sb = StringBuilder()
            sb.append("package com.example.massive\n\n")
            sb.append("class MassiveService {\n")
            for (i in 1..50) {
                val num = i.toString().padStart(3, '0')
                sb.append("    fun executeItem$num(): String = \"result_$num\"\n")
            }
            sb.append("}\n")

            val filePath = "$tmpDir/src/MassiveService.kt"
            PlatformFiles.mkdirs("$tmpDir/src")
            PlatformFiles.writeText(filePath, sb.toString())

            val res = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = "src/MassiveService.kt"
            )

            val text = res["text"]?.jsonPrimitive?.content ?: ""
            val byteSize = text.encodeToByteArray().size
            assertTrue(byteSize <= 400, "Output must be <= 400 bytes, was: $byteSize bytes\nContent:\n$text")
            assertTrue(text.contains("... [truncated to <=400B]"), "Truncation indicator missing: $text")
            assertTrue(text.contains("executeItem001"), "First function should be present: $text")
            assertFalse(text.contains("executeItem050"), "Last function should be truncated: $text")
        }
    }

    @Test
    fun testFastPathSqliteVsFallbackInMemoryExtraction() {
        withTestWorkspace { tmpDir, dbPath, server ->
            val fileScopeNode = GraphNode(
                nodeId = "test:src/OrderProcessor.kt#file",
                projectId = "test",
                fileId = "test:src/OrderProcessor.kt",
                name = "OrderProcessor.kt",
                qualifiedName = "src/OrderProcessor.kt",
                kind = "file_scope",
                startLine = 1,
                endLine = 60,
                inDegree = 0
            )
            val cancelNode = GraphNode(
                nodeId = "test:src/OrderProcessor.kt#cancelOrder",
                projectId = "test",
                fileId = "test:src/OrderProcessor.kt",
                name = "cancelOrder",
                qualifiedName = "com.example.OrderProcessor.cancelOrder",
                kind = "fun",
                startLine = 30,
                endLine = 40,
                inDegree = 10,
                isTopLevel = false
            )
            val classNode = GraphNode(
                nodeId = "test:src/OrderProcessor.kt#OrderProcessor",
                projectId = "test",
                fileId = "test:src/OrderProcessor.kt",
                name = "OrderProcessor",
                qualifiedName = "com.example.OrderProcessor",
                kind = "class",
                startLine = 5,
                endLine = 55,
                inDegree = 2,
                isTopLevel = true
            )

            server.store.ensureVirtualFileRecord("test", "src/OrderProcessor.kt")
            server.store.upsertNodesAndEdges("test", listOf(fileScopeNode, cancelNode, classNode), emptyList())

            // Fast-path SQLite test
            val fastRes = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = "src/OrderProcessor.kt"
            )

            assertEquals("sqlite", fastRes["source"]?.jsonPrimitive?.content)
            val fastText = fastRes["text"]?.jsonPrimitive?.content ?: ""
            assertFalse(fastText.contains("file_scope"), "file_scope must be excluded: $fastText")

            val classIndex = fastText.indexOf("OrderProcessor: 5-55")
            val cancelIndex = fastText.indexOf("cancelOrder: 30-40")
            assertTrue(classIndex != -1, "OrderProcessor missing: $fastText")
            assertTrue(cancelIndex != -1, "cancelOrder missing: $fastText")
            assertTrue(classIndex < cancelIndex, "OrderProcessor (line 5) must precede cancelOrder (line 30): $fastText")

            // In-memory fallback test for unindexed file
            val unindexedCode = """
                package com.example.unindexed

                class FreshComponent {
                    fun render(): String = "fresh"
                }
            """.trimIndent()
            PlatformFiles.mkdirs("$tmpDir/src")
            PlatformFiles.writeText("$tmpDir/src/FreshComponent.kt", unindexedCode)

            val fallbackRes = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = "src/FreshComponent.kt"
            )

            assertEquals("polyglot_fallback", fallbackRes["source"]?.jsonPrimitive?.content)
            val fallbackText = fallbackRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(fallbackText.contains("FreshComponent: 3-5"), "FreshComponent missing: $fallbackText")
            assertTrue(fallbackText.contains("function render: 4-5"), "render missing: $fallbackText")
        }
    }

    @Test
    fun testMcpOutlineToolSchemaAndParameterWordCounts() {
        withTestWorkspace { tmpDir, dbPath, server ->
            val tools = server.toolDefinitions()
            val outlineTool = tools.firstOrNull { it["name"]?.jsonPrimitive?.content == "atlas_outline" }
            assertNotNull(outlineTool, "atlas_outline tool must be registered in toolDefinitions()")

            val toolDesc = outlineTool["description"]?.jsonPrimitive?.content ?: ""
            val toolWordCount = toolDesc.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.size
            assertTrue(toolWordCount <= 25, "Tool description exceeds 25 words ($toolWordCount): $toolDesc")

            val schema = outlineTool["inputSchema"]!!.jsonObject
            val props = schema["properties"]!!.jsonObject
            assertTrue(props.containsKey("file"), "Schema must have 'file' property")
            assertTrue(props.containsKey("project"), "Schema must have 'project' property")
            assertTrue(props.containsKey("workspace"), "Schema must have 'workspace' property")

            for ((propName, propDef) in props) {
                val desc = propDef.jsonObject["description"]?.jsonPrimitive?.content ?: ""
                val words = desc.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
                assertTrue(
                    words.size <= 25,
                    "Parameter '$propName' description exceeds 25 words (${words.size}): '$desc'"
                )
            }
        }
    }

    @Test
    fun testMcpOutlineCallDirectExecution() {
        withTestWorkspace { tmpDir, dbPath, server ->
            val code = """
                package com.example.mcp

                class McpTarget {
                    fun execute(): Boolean = true
                }
            """.trimIndent()
            PlatformFiles.mkdirs("$tmpDir/src")
            PlatformFiles.writeText("$tmpDir/src/McpTarget.kt", code)

            val toolCallRes = server.callTool(
                "atlas_outline",
                buildJsonObject {
                    put("file", "src/McpTarget.kt")
                    put("project", "test")
                }
            )

            assertEquals(true, toolCallRes["_mcp_raw_text"]?.jsonPrimitive?.booleanOrNull)
            val text = toolCallRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(text.contains("### Outline: src/McpTarget.kt"), "Header missing in: $text")
            assertTrue(text.contains("McpTarget: 3-5"), "McpTarget missing in: $text")
            assertTrue(text.encodeToByteArray().size <= 400, "Output exceeds 400 bytes")
        }
    }

    @Test
    fun testCliOutlineCommandExecution() {
        withTestWorkspace { tmpDir, dbPath, _ ->
            val code = """
                package com.example.cli

                class CliSample {
                    fun runAction(): Int = 42
                }
            """.trimIndent()
            PlatformFiles.mkdirs("$tmpDir/src")
            PlatformFiles.writeText("$tmpDir/src/CliSample.kt", code)

            val exitCode = AtlasCli.run(
                arrayOf(
                    "--workspace", tmpDir,
                    "--db", dbPath,
                    "--project", "test",
                    "outline", "src/CliSample.kt"
                )
            )
            assertEquals(0, exitCode, "atlas outline CLI command must exit with code 0")

            val exitCodeJson = AtlasCli.run(
                arrayOf(
                    "--workspace", tmpDir,
                    "--db", dbPath,
                    "--project", "test",
                    "outline", "src/CliSample.kt",
                    "--json"
                )
            )
            assertEquals(0, exitCodeJson, "atlas outline --json CLI command must exit with code 0")
        }
    }

    @Test
    fun testMissingOrEmptyFileHandling() {
        withTestWorkspace { tmpDir, dbPath, server ->
            // Missing file
            val missingRes = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = "nonexistent/file.kt"
            )
            val missingText = missingRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(missingText.contains("not found") || missingText.contains("Error:"), "Expected error text: $missingText")
            assertTrue(missingText.encodeToByteArray().size <= 400)

            // Empty path
            val emptyPathRes = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = ""
            )
            val emptyPathText = emptyPathRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(emptyPathText.contains("required") || emptyPathText.contains("Error:"), "Expected error text: $emptyPathText")
            assertTrue(emptyPathText.encodeToByteArray().size <= 400)

            // Empty file (0 bytes)
            PlatformFiles.mkdirs("$tmpDir/src")
            PlatformFiles.writeText("$tmpDir/src/empty.kt", "")
            val emptyFileRes = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = "src/empty.kt"
            )
            val emptyFileText = emptyFileRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(emptyFileText.contains("Outline: src/empty.kt"), "Header expected: $emptyFileText")
            assertTrue(emptyFileText.encodeToByteArray().size <= 400)
        }
    }

    @Test
    fun testIndexedMarkdownOutlineIgnoresSqliteDocNodeAndExtractsHeadings() {
        withTestWorkspace { tmpDir, dbPath, server ->
            // 1. Create a markdown documentation file on disk with headings
            val mdContent = """
                # System Architecture Specification

                This document defines the system topology.

                ## Storage Subsystem

                SQLite graph store handles nodes and edges.

                ### Fast Path Query

                Querying nodes by fileId is indexed by startLine.

                ## Inspection Engine

                Outline extraction for polyglot AST and Markdown.
            """.trimIndent()

            val filePath = "$tmpDir/docs/spec.md"
            PlatformFiles.mkdirs("$tmpDir/docs")
            PlatformFiles.writeText(filePath, mdContent)

            // 2. Pre-populate SQLite with obsidian_note node (simulating atlas index)
            val obsidianNode = GraphNode(
                nodeId = "test:docs/spec.md#spec",
                projectId = "test",
                fileId = "test:docs/spec.md",
                name = "spec",
                qualifiedName = "docs/spec.md",
                kind = "obsidian_note",
                startLine = 1,
                endLine = 20,
                inDegree = 0,
                isTopLevel = true
            )
            server.store.ensureVirtualFileRecord("test", "docs/spec.md")
            server.store.upsertNodesAndEdges("test", listOf(obsidianNode), emptyList())

            // Verify SQLite actually contains the node before calling outline
            val sqliteNodes = server.store.getNodesForFile("test", "docs/spec.md")
            assertEquals(1, sqliteNodes.size, "SQLite must contain the pre-indexed obsidian_note node")
            assertEquals("obsidian_note", sqliteNodes[0].kind)

            // 3. Extract outline via engine
            val res = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = "docs/spec.md"
            )

            // 4. Assert source is markdown and headings are extracted, NOT obsidian_note
            assertEquals("markdown", res["source"]?.jsonPrimitive?.content, "Source must be 'markdown', not 'sqlite'")
            val text = res["text"]?.jsonPrimitive?.content ?: ""

            assertTrue(text.contains("### Outline: docs/spec.md"), "Header missing in: $text")
            assertTrue(text.contains("- # System Architecture Specification: 1"), "H1 missing in: $text")
            assertTrue(text.contains("- ## Storage Subsystem: 5"), "H2 Storage Subsystem missing in: $text")
            assertTrue(text.contains("- ### Fast Path Query: 9"), "H3 Fast Path Query missing in: $text")
            assertTrue(text.contains("- ## Inspection Engine: 13"), "H2 Inspection Engine missing in: $text")

            assertFalse(text.contains("obsidian_note"), "obsidian_note leaked into outline output: $text")
            assertTrue(text.encodeToByteArray().size <= 400, "Output exceeds 400 bytes: ${text.encodeToByteArray().size}")

            // 5. Also verify with plan_contract node (plans/*.md)
            val planContent = """
                # Milestone 1 Plan Contract

                ## Objectives
                - Deliver atlas outline CLI and MCP

                ## Verification
                - macosArm64Test
            """.trimIndent()
            val planPath = "$tmpDir/plans/m1.md"
            PlatformFiles.mkdirs("$tmpDir/plans")
            PlatformFiles.writeText(planPath, planContent)

            val planNode = GraphNode(
                nodeId = "test:plans/m1.md#m1",
                projectId = "test",
                fileId = "test:plans/m1.md",
                name = "m1",
                qualifiedName = "plans/m1.md",
                kind = "plan_contract",
                startLine = 1,
                endLine = 10,
                inDegree = 0,
                isTopLevel = true
            )
            server.store.ensureVirtualFileRecord("test", "plans/m1.md")
            server.store.upsertNodesAndEdges("test", listOf(planNode), emptyList())

            val planRes = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = "plans/m1.md"
            )
            assertEquals("markdown", planRes["source"]?.jsonPrimitive?.content, "Source must be 'markdown'")
            val planText = planRes["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(planText.contains("- # Milestone 1 Plan Contract: 1"), "Plan H1 missing: $planText")
            assertTrue(planText.contains("- ## Objectives: 3"), "Plan H2 missing: $planText")
            assertFalse(planText.contains("plan_contract"), "plan_contract leaked: $planText")
            assertTrue(planText.encodeToByteArray().size <= 400, "Output exceeds 400 bytes")
        }
    }

    @Test
    fun testMarkdownOutlineIgnoresIndentedCodeBlocks() {
        withTestWorkspace { tmpDir, dbPath, server ->
            val mdContent = """
                # Primary Architecture

                Overview paragraph before code blocks.

                    # Indented shell comment with 4 spaces
                    echo "indented 4-space block"

                ## Second Section

                	# Indented python comment with tab
                	def hello():
                	    pass

                        # Indented comment with 8 spaces
                        return True

                   ### Valid Heading With 3 Spaces Indentation

                ## Final Section
            """.trimIndent()

            val filePath = "$tmpDir/docs/indented_blocks.md"
            PlatformFiles.mkdirs("$tmpDir/docs")
            PlatformFiles.writeText(filePath, mdContent)

            val res = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = "docs/indented_blocks.md"
            )

            val text = res["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(text.contains("### Outline: docs/indented_blocks.md"), "Header missing in: $text")
            assertTrue(text.contains("- # Primary Architecture: 1"), "H1 missing in: $text")
            assertTrue(text.contains("- ## Second Section: 8"), "H2 Second Section missing in: $text")
            assertTrue(text.contains("- ### Valid Heading With 3 Spaces Indentation: 17"), "H3 with 3 spaces missing: $text")
            assertTrue(text.contains("- ## Final Section: 19"), "H2 Final Section missing in: $text")

            assertFalse(text.contains("Indented shell comment"), "Comment inside 4-space indented block leaked: $text")
            assertFalse(text.contains("Indented python comment"), "Comment inside tab-indented block leaked: $text")
            assertFalse(text.contains("Indented comment with 8 spaces"), "Comment inside 8-space block leaked: $text")
            assertFalse(text.contains("echo \"indented"), "Code inside indented block leaked: $text")
            assertFalse(text.contains("def hello"), "Code inside tab block leaked: $text")
            assertTrue(text.encodeToByteArray().size <= 400, "Output exceeds 400 bytes: ${text.encodeToByteArray().size}")
        }
    }

    @Test
    fun testMarkdownOutlineExtractorDirectIndentedCodeBlocks() {
        val markdown = """
            # Heading 1
                # 4-space comment
            	# Tab-indented comment
                    # 8-space comment
              ## 2-space indented heading
            ### Heading 3
        """.trimIndent()

        val headings = OutlineExtractor.MarkdownOutlineExtractor.extract(markdown)
        assertEquals(3, headings.size, "Should extract exactly 3 headings, got ${headings.size}: $headings")
        assertEquals("#", headings[0].hashes)
        assertEquals("Heading 1", headings[0].title)
        assertEquals(1, headings[0].line)

        assertEquals("##", headings[1].hashes)
        assertEquals("2-space indented heading", headings[1].title)
        assertEquals(5, headings[1].line)

        assertEquals("###", headings[2].hashes)
        assertEquals("Heading 3", headings[2].title)
        assertEquals(6, headings[2].line)
    }

    @Test
    fun testLongNonExistentPathDoesNotExceedBudget() {
        withTestWorkspace { tmpDir, dbPath, server ->
            val longFileName = "nonexistent/" + "a".repeat(480) + ".kt"
            val res = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = longFileName
            )
            val text = res["text"]?.jsonPrimitive?.content ?: ""
            val byteSize = text.encodeToByteArray().size
            assertTrue(byteSize <= 400, "Error output for long path must be <= 400 bytes, was: $byteSize bytes")
            assertTrue(text.contains("... [truncated to <=400B]"), "Truncation indicator missing: $text")
            assertTrue(text.startsWith("Error: File 'nonexistent/"), "Error message prefix missing: $text")

            val byteSizeField = res["byte_size"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            assertEquals(byteSize, byteSizeField, "byte_size field in JSON must match text byte size")
            assertTrue(byteSizeField <= 400, "byte_size field in JSON must be <= 400 bytes")
        }
    }

    @Test
    fun testLongNonExistentPathMultiByteUnicode() {
        withTestWorkspace { tmpDir, dbPath, server ->
            val emojiFileName = "nonexistent/" + "🚀🎯🔥".repeat(50) + ".kt"
            val res = server.codegraphEngine.outline(
                projectId = "test",
                targetFile = emojiFileName
            )
            val text = res["text"]?.jsonPrimitive?.content ?: ""
            val byteSize = text.encodeToByteArray().size
            assertTrue(byteSize <= 400, "Error output for emoji path must be <= 400 bytes, was: $byteSize bytes")
            assertTrue(text.contains("... [truncated to <=400B]"), "Truncation indicator missing: $text")
            assertNotNull(text)
        }
    }

    @Test
    fun testClampUtf8BoundaryInvariants() {
        val short = "Hello World"
        assertEquals(short, OutlineExtractor.clampUtf8(short, 400))

        val shortDefault = "Error: File parameter is required."
        assertEquals(shortDefault, OutlineExtractor.clampUtf8(shortDefault))

        val over400 = "x".repeat(500)
        val clampedOver = OutlineExtractor.clampUtf8(over400, 400)
        assertTrue(clampedOver.encodeToByteArray().size <= 399, "Clamped bytes must be <= 399: ${clampedOver.encodeToByteArray().size}")
        assertTrue(clampedOver.endsWith(OutlineExtractor.TRUNCATION_INDICATOR))

        val multiByte = "测试".repeat(100)
        val clampedMulti = OutlineExtractor.clampUtf8(multiByte, 400)
        val clampedBytes = clampedMulti.encodeToByteArray()
        assertTrue(clampedBytes.size <= 399)
        assertTrue(clampedMulti.endsWith(OutlineExtractor.TRUNCATION_INDICATOR))
        assertEquals(clampedMulti, clampedBytes.decodeToString())
    }

    @Test
    fun testEmptySymbolsOrHeadingsWithLongRelPathDoesNotExceedBudget() {
        val longRelPath = "a/long/nested/path/".repeat(25) + "EmptyFile.kt"
        val textNodes = OutlineExtractor.formatFromNodes(longRelPath, emptyList())
        assertTrue(
            textNodes.encodeToByteArray().size <= 400,
            "Empty nodes with long path must be <= 400 bytes: ${textNodes.encodeToByteArray().size}"
        )

        val longMdPath = "a/long/nested/path/".repeat(25) + "EmptyDoc.md"
        val textMd = OutlineExtractor.formatMarkdown(longMdPath, "Plain text with no headings.")
        assertTrue(
            textMd.encodeToByteArray().size <= 400,
            "Empty headings with long path must be <= 400 bytes: ${textMd.encodeToByteArray().size}"
        )
    }

    private fun withTestWorkspace(block: (tmpDir: String, dbPath: String, server: AtlasMcpServer) -> Unit) {
        val pid = getpid()
        val rnd = Random.nextInt(100000, 999999)
        val tmpDir = "/tmp/atlas_outline_test_${pid}_$rnd"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val server = AtlasMcpServer(
            workspaceRoot = tmpDir,
            customDbPath = dbPath,
            enableCodegraphTools = true
        )
        try {
            block(tmpDir, dbPath, server)
        } finally {
            server.close()
            PlatformFiles.delete(dbPath)
        }
    }
}
