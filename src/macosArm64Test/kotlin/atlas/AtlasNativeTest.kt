package atlas

import atlas.cli.CodeGraphCli
import atlas.db.NativeSqliteDatabase
import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectureAuditor
import atlas.engine.ScopeSynthesizer
import atlas.engine.WorkMemoryStore
import atlas.extract.ModuleBoundaryDetector
import atlas.extract.NameSegmentTokenizer
import atlas.extract.PolyglotAstExtractor
import atlas.extract.SemioticLayerClassifier
import atlas.mcp.CodeGraphMcpServer
import atlas.model.FigmaDocument
import atlas.model.FigmaNode
import atlas.model.FigmaSpatialBounds
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import atlas.util.Sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AtlasNativeTest {

    @Test
    fun testSha256StandardVector() {
        val hash = Sha256.digest("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    @Test
    fun testNameSegmentTokenizer() {
        val segments = NameSegmentTokenizer.tokenize("ProductCardViewModel")
        assertEquals(listOf("product", "card", "view", "model"), segments)

        val snakeSegments = NameSegmentTokenizer.tokenize("render_audio_stream")
        assertEquals(listOf("render", "audio", "stream"), snakeSegments)
    }

    @Test
    fun testSemioticLayerClassifier() {
        val (semiotic, arch, _) = SemioticLayerClassifier.classify("shared/domain/model/Product.kt")
        assertEquals("syntactic", semiotic)
        assertEquals("domain", arch)

        val (uiSemiotic, uiArch, _) = SemioticLayerClassifier.classify("feature/cart/ui/CartScreen.kt")
        assertEquals("pragmatic", uiSemiotic)
        assertEquals("presentation", uiArch)

        // Non-test production files containing "test" suffix must not be misclassified as test
        val (contestSem, contestArch, _) = SemioticLayerClassifier.classify("shared/domain/model/Contest.kt")
        assertEquals("syntactic", contestSem)
        assertEquals("domain", contestArch)

        // Actual test files ending in Test.kt or in test/ directory must be classified as test
        val (testSem, testArch, _) = SemioticLayerClassifier.classify("shared/domain/model/ContestTest.kt")
        assertEquals("test", testSem)
        assertEquals("test", testArch)
    }

    @Test
    fun testModuleBoundaryDetectorTypeSafeAccessor() {
        val known = listOf(":core:designsystem", ":feature:audio-studio")
        val mod = ModuleBoundaryDetector.convertTypesafeAccessorToModuleId("core.designsystem", known)
        assertEquals(":core:designsystem", mod)

        val camelMod = ModuleBoundaryDetector.convertTypesafeAccessorToModuleId("feature.audioStudio", known)
        assertEquals(":feature:audio-studio", camelMod)
    }

    @Test
    fun testPolyglotAstExtractorKotlin() {
        val ktCode = """
            package com.tesco.product
            
            import com.tesco.core.BaseCard
            
            class ProductCard : BaseCard() {
                fun render() {
                    displayTitle()
                }
            }
        """.trimIndent()

        val (nodes, edges, declCount) = PolyglotAstExtractor.extractFile("test_proj", "ProductCard.kt", ktCode)
        assertEquals(1, declCount)
        assertTrue(nodes.any { it.name == "ProductCard" })
        assertTrue(edges.any { it.edgeType == "IMPORTS" })
    }

    @Test
    fun testSqliteGraphStoreAndCTE() {
        val dbPath = "/tmp/test_atlas_unit_${getpid()}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val nodeA = GraphNode(
                nodeId = "p:A",
                projectId = "p",
                fileId = "p:A.kt",
                name = "A",
                qualifiedName = "pkg.A",
                kind = "class",
                signature = "class A",
                archLayer = "domain"
            )
            val nodeB = GraphNode(
                nodeId = "p:B",
                projectId = "p",
                fileId = "p:B.kt",
                name = "B",
                qualifiedName = "pkg.B",
                kind = "class",
                signature = "class B",
                archLayer = "presentation"
            )
            val edge = GraphEdge(
                edgeId = "e1",
                projectId = "p",
                sourceNodeId = "p:A",
                targetNodeId = "p:B",
                edgeType = "CALLS"
            )

            store.upsertNodesAndEdges("p", listOf(nodeA, nodeB), listOf(edge))

            val found = store.getNode("p", "A")
            assertNotNull(found)
            assertEquals("A", found.name)

            val callees = store.getCallees("p", "p:A", depth = 2)
            assertEquals(1, callees.size)
            assertEquals("p:B", callees[0].targetId)

            val shortest = store.getShortestPath("p", "p:A", "p:B")
            assertEquals(listOf("p:A", "p:B"), shortest)

            // Test architecture layer inversion detection
            val auditor = ArchitectureAuditor(store)
            val inversions = auditor.checkLayerInversions("p")
            assertEquals(1, inversions.size)
            assertEquals("ARCH-LAYER-INVERSION", inversions[0].rule)

            // Test 3D scope synthesis
            val synth = ScopeSynthesizer(store)
            val scope = synth.synthesizeScope("p", listOf("A"))
            assertTrue(scope.files.contains("A.kt"))
            assertTrue(scope.files.contains("B.kt"))

            // Test experiential work memory with time decay
            val memory = WorkMemoryStore(store)
            val annId = memory.recordOutcome("p", "p:A", "useful", "Verified A calls B directly", createdAtEpoch = 1000.0)
            assertNotNull(annId)

            // 0 days age: weight should be 1.0
            val active = memory.getActiveAnnotations("p", "p:A", nowEpoch = 1000.0)
            assertEquals(1, active.size)
            assertEquals(1.0, active[0].decayWeight)

            // 30 days age (1 half-life): weight should be 0.5
            val active30d = memory.getActiveAnnotations("p", "p:A", nowEpoch = 1000.0 + 30 * 86400.0)
            assertEquals(1, active30d.size)
            assertEquals(0.5, active30d[0].decayWeight)

            // Shortest path with branching and cycle handling
            val nodeC = GraphNode("p:C", "p", "p:C.kt", "C", "pkg.C", "class")
            val nodeD = GraphNode("p:D", "p", "p:D.kt", "D", "pkg.D", "class")
            val edgeAC = GraphEdge("e2", "p", "p:A", "p:C", "CALLS")
            val edgeCD = GraphEdge("e3", "p", "p:C", "p:D", "CALLS")
            val edgeDC = GraphEdge("e4", "p", "p:D", "p:C", "CALLS") // cycle
            val edgeDB = GraphEdge("e5", "p", "p:D", "p:B", "CALLS")
            store.upsertNodesAndEdges("p", listOf(nodeC, nodeD), listOf(edgeAC, edgeCD, edgeDC, edgeDB))

            val shortestMulti = store.getShortestPath("p", "p:C", "p:B")
            assertEquals(listOf("p:C", "p:D", "p:B"), shortestMulti)

            val disconnected = store.getShortestPath("p", "p:B", "p:A")
            assertEquals(emptyList(), disconnected)

            // Test file record pruning and deletion
            store.upsertFileRecord(
                projectId = "p",
                relPath = "A.kt",
                language = "kotlin",
                moduleId = "m1",
                sourceSet = "main",
                semioticLayer = "syntactic",
                archLayer = "domain",
                sha256 = "shaA",
                mtime = 100.0,
                loc = 10,
                topLevelDeclCount = 1
            )
            val indexed = store.listIndexedFiles("p")
            assertTrue(indexed.contains("A.kt"))

            store.deleteFileRecord("p", "A.kt")
            val indexedAfter = store.listIndexedFiles("p")
            assertTrue(!indexedAfter.contains("A.kt"))
            val nodeAAfter = store.getNode("p", "A")
            assertEquals(null, nodeAAfter)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testCliVersionFlag() {
        assertEquals("0.4.0", CodeGraphCli.VERSION)
        val exitCodeVersion = CodeGraphCli.run(arrayOf("--version"))
        assertEquals(0, exitCodeVersion)

        val exitCodeV = CodeGraphCli.run(arrayOf("-v"))
        assertEquals(0, exitCodeV)

        val exitCodeCmd = CodeGraphCli.run(arrayOf("version"))
        assertEquals(0, exitCodeCmd)

        val exitCodeVersionJson = CodeGraphCli.run(arrayOf("version", "--json"))
        assertEquals(0, exitCodeVersionJson)

        val exitCodeFlagJson = CodeGraphCli.run(arrayOf("--version", "--json"))
        assertEquals(0, exitCodeFlagJson)

        val exitCodeVJson = CodeGraphCli.run(arrayOf("-v", "--json"))
        assertEquals(0, exitCodeVJson)

        val exitCodeFigmaVersion = CodeGraphCli.run(arrayOf("figma", "version"))
        assertEquals(0, exitCodeFigmaVersion)

        val exitCodeFigmaVersionJson = CodeGraphCli.run(arrayOf("figma", "version", "--json"))
        assertEquals(0, exitCodeFigmaVersionJson)

        val exitCodeFigmaHelp = CodeGraphCli.run(arrayOf("figma", "--help"))
        assertEquals(0, exitCodeFigmaHelp)
    }

    @Test
    fun testPolyglotAstExtractorExpressionBodyAndDeclarations() {
        val ktCode = """
            package com.tesco.product
            
            fun add(a: Int, b: Int): Int = a + b
            
            fun sub(a: Int, b: Int): Int = a - b
            
            fun multiline(
                x: Int,
                y: Int
            ): Int =
                x * y
            
            interface Service {
                fun fetch(): String
                fun delete(): Boolean
            }
            
            fun withBlock() {
                val z = 10
            }
            
            fun lambdaBlock() = run {
                val step1 = 1
                step1 + 2
            }
        """.trimIndent()

        val (nodes, _, _) = PolyglotAstExtractor.extractFile("test_proj", "MathUtils.kt", ktCode)
        val nodeMap = nodes.associateBy { it.name }

        val addNode = nodeMap["add"]
        assertNotNull(addNode)
        assertEquals(3, addNode.startLine)
        assertEquals(3, addNode.endLine)

        val subNode = nodeMap["sub"]
        assertNotNull(subNode)
        assertEquals(5, subNode.startLine)
        assertEquals(5, subNode.endLine)

        val multilineNode = nodeMap["multiline"]
        assertNotNull(multilineNode)
        assertEquals(7, multilineNode.startLine)
        assertEquals(11, multilineNode.endLine)

        val fetchNode = nodeMap["fetch"]
        assertNotNull(fetchNode)
        assertEquals(14, fetchNode.startLine)
        assertEquals(14, fetchNode.endLine)

        val deleteNode = nodeMap["delete"]
        assertNotNull(deleteNode)
        assertEquals(15, deleteNode.startLine)
        assertEquals(15, deleteNode.endLine)

        val withBlockNode = nodeMap["withBlock"]
        assertNotNull(withBlockNode)
        assertEquals(18, withBlockNode.startLine)
        assertEquals(20, withBlockNode.endLine)

        val lambdaBlockNode = nodeMap["lambdaBlock"]
        assertNotNull(lambdaBlockNode)
        assertEquals(22, lambdaBlockNode.startLine)
        assertEquals(25, lambdaBlockNode.endLine)
    }

    @Test
    fun testScopeSynthesizerIgnoresMergedAndCompletedPlans() {
        val dbPath = "/tmp/test_atlas_scope_plans_${getpid()}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val codeNode = GraphNode(
                nodeId = "p:Code", projectId = "p", fileId = "p:Code.kt",
                name = "Code", qualifiedName = "pkg.Code", kind = "class"
            )
            val activePlan = GraphNode(
                nodeId = "p:plans/active#active", projectId = "p", fileId = "p:plans/active.md",
                name = "active_plan", qualifiedName = "plans/active.md", kind = "plan_contract",
                signature = "plan_contract (active): plans/active.md"
            )
            val mergedPlan = GraphNode(
                nodeId = "p:plans/merged#merged", projectId = "p", fileId = "p:plans/merged.md",
                name = "merged_plan", qualifiedName = "plans/merged.md", kind = "plan_contract",
                signature = "plan_contract (merged): plans/merged.md"
            )
            val completedPlan = GraphNode(
                nodeId = "p:plans/completed#completed", projectId = "p", fileId = "p:plans/completed.md",
                name = "completed_plan", qualifiedName = "plans/completed.md", kind = "plan_contract",
                signature = "plan_contract (completed): plans/completed.md"
            )

            val edgeActive = GraphEdge(
                edgeId = "e1", projectId = "p", sourceNodeId = activePlan.nodeId,
                targetNodeId = codeNode.nodeId, edgeType = "PLAN_SCOPES_FILE"
            )
            val edgeMerged = GraphEdge(
                edgeId = "e2", projectId = "p", sourceNodeId = mergedPlan.nodeId,
                targetNodeId = codeNode.nodeId, edgeType = "PLAN_SCOPES_FILE"
            )
            val edgeCompleted = GraphEdge(
                edgeId = "e3", projectId = "p", sourceNodeId = completedPlan.nodeId,
                targetNodeId = codeNode.nodeId, edgeType = "PLAN_SCOPES_FILE"
            )

            store.upsertNodesAndEdges("p", listOf(codeNode, activePlan, mergedPlan, completedPlan), listOf(edgeActive, edgeMerged, edgeCompleted))

            val synth = ScopeSynthesizer(store)
            val scope = synth.synthesizeScope("p", listOf("Code"))
            assertEquals(listOf("active_plan"), scope.collidingPlans)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testCodeGraphMcpServerFigmaToolsAndSingleLineJson() {
        val dbPath = "/tmp/test_atlas_mcp_${getpid()}.db"
        PlatformFiles.delete(dbPath)
        val server = CodeGraphMcpServer(workspaceRoot = ".", customDbPath = dbPath)
        try {
            val defs = server.toolDefinitions()
            val toolNames = defs.map { it["name"]?.jsonPrimitive?.content }
            assertTrue(toolNames.contains("codegraph_query"))
            assertTrue(toolNames.contains("codegraph_audit"))
            assertTrue(toolNames.contains("figma_audit"))
            assertTrue(toolNames.contains("figma_scope"))

            val auditTool = defs.first { it["name"]?.jsonPrimitive?.content == "codegraph_audit" }
            val actions = auditTool["inputSchema"]?.jsonObject?.get("properties")?.jsonObject
                ?.get("action")?.jsonObject?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            assertTrue(actions.contains("figma_audit"))
            assertTrue(actions.contains("figma_scope"))

            // Seed a Figma document in store
            val doc = FigmaDocument("fk_test", "TestDoc", "1", "2026-01-01")
            server.store.insertFigmaDocument(doc)
            val node = FigmaNode(
                nodeId = "1:1",
                fileKey = "fk_test",
                pageName = "Page 1",
                name = "TestFrame",
                nodeType = "FRAME",
                layoutMode = "VERTICAL",
                sizingHorizontal = "HUG",
                sizingVertical = "HUG",
                width = 100.0,
                height = 100.0
            )
            server.store.insertFigmaNodes("fk_test", listOf(node))

            // Test figma_audit tool call
            val auditOut = server.callTool("figma_audit", buildJsonObject {
                put("file_key", "fk_test")
            })
            assertNotNull(auditOut["maturityScore"])

            // Test codegraph_audit with figma_audit action
            val auditActionOut = server.callTool("codegraph_audit", buildJsonObject {
                put("action", "figma_audit")
                put("file_key", "fk_test")
            })
            assertNotNull(auditActionOut["maturityScore"])

            // Test figma_scope tool call
            val scopeOut = server.callTool("figma_scope", buildJsonObject {
                put("file_key", "fk_test")
                put("project", "test_proj")
            })
            assertNotNull(scopeOut["spatialBounds"])

            // Test codegraph_audit with figma_scope action
            val scopeActionOut = server.callTool("codegraph_audit", buildJsonObject {
                put("action", "figma_scope")
                put("file_key", "fk_test")
                put("project", "test_proj")
            })
            assertNotNull(scopeActionOut["spatialBounds"])

            // Verify single-line NDJSON serialization for all tool outputs
            val encodedAudit = Json.encodeToString(JsonObject.serializer(), auditOut)
            assertFalse(encodedAudit.contains("\n"), "MCP JSON serialization must be strict single-line NDJSON")

            // Verify full JSON-RPC stdio protocol pipeline via processJsonRpcMessage
            val initMsg = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
            val initResp = server.processJsonRpcMessage(initMsg)
            assertNotNull(initResp)
            assertFalse(initResp.contains("\n"), "initialize response must be strict single-line NDJSON")
            assertTrue(initResp.contains(""""protocolVersion":"2024-11-05""""))
            assertTrue(initResp.contains(""""version":"0.4.0""""))

            val listMsg = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
            val listResp = server.processJsonRpcMessage(listMsg)
            assertNotNull(listResp)
            assertFalse(listResp.contains("\n"), "tools/list response must be strict single-line NDJSON")
            assertTrue(listResp.contains(""""name":"figma_audit""""))
            assertTrue(listResp.contains(""""name":"figma_scope""""))

            val callAuditMsg = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"figma_audit","arguments":{"file_key":"fk_test"}}}"""
            val callAuditResp = server.processJsonRpcMessage(callAuditMsg)
            assertNotNull(callAuditResp)
            assertFalse(callAuditResp.contains("\n"), "tools/call figma_audit response must be strict single-line NDJSON")
            assertTrue(callAuditResp.contains("maturityScore"))

            val callScopeMsg = """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"figma_scope","arguments":{"file_key":"fk_test","target":"TestFrame"}}}"""
            val callScopeResp = server.processJsonRpcMessage(callScopeMsg)
            assertNotNull(callScopeResp)
            assertFalse(callScopeResp.contains("\n"), "tools/call figma_scope response must be strict single-line NDJSON")
            assertTrue(callScopeResp.contains("spatialBounds"))

            val callErr = server.processJsonRpcMessage("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"unknown_tool","arguments":{}}}""")
            assertNotNull(callErr)
            assertFalse(callErr.contains("\n"), "error response must be strict single-line NDJSON")
            assertTrue(callErr.contains("error"))
        } finally {
            server.store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testNativeSqliteBusyTimeout() {
        val dbPath = "/tmp/test_atlas_busy_${getpid()}.db"
        PlatformFiles.delete(dbPath)
        val db1 = NativeSqliteDatabase(dbPath)
        val db2 = NativeSqliteDatabase(dbPath)
        try {
            val pragma1 = db1.prepare("PRAGMA busy_timeout;")
            assertTrue(pragma1.step())
            assertEquals(5000, pragma1.columnInt(0), "db1 must have busy_timeout = 5000")
            pragma1.close()

            val pragma2 = db2.prepare("PRAGMA busy_timeout;")
            assertTrue(pragma2.step())
            assertEquals(5000, pragma2.columnInt(0), "db2 must have busy_timeout = 5000")
            pragma2.close()

            db1.exec("CREATE TABLE items (id TEXT PRIMARY KEY, value INT);")
            db1.exec("INSERT INTO items VALUES ('k1', 100);")

            val stmt = db2.prepare("SELECT value FROM items WHERE id = ?;")
            stmt.bindString(1, "k1")
            assertTrue(stmt.step())
            assertEquals(100, stmt.columnInt(0))
            stmt.close()
        } finally {
            db1.close()
            db2.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testPolyglotAstExtractorSwiftAndTypeScriptDeclarations() {
        val swiftCode = """
            import Foundation
            
            protocol Greeter {
                func greet(name: String) -> String
                func bye()
            }
            
            class ConcreteGreeter: Greeter {
                func greet(name: String) -> String {
                    return "Hello " + name
                }
            }
        """.trimIndent()

        val (swiftNodes, _, _) = PolyglotAstExtractor.extractFile("proj", "Greeter.swift", swiftCode)
        val greetProto = swiftNodes.first { it.name == "greet" && it.startLine == 4 }
        assertEquals(4, greetProto.startLine)
        assertEquals(4, greetProto.endLine)

        val byeProto = swiftNodes.first { it.name == "bye" }
        assertEquals(5, byeProto.startLine)
        assertEquals(5, byeProto.endLine)

        val greetConcrete = swiftNodes.first { it.name == "greet" && it.startLine == 9 }
        assertEquals(9, greetConcrete.startLine)
        assertEquals(11, greetConcrete.endLine)

        // TypeScript without semicolons (standard modern TS)
        val tsCode = """
            import { Item } from './types'
            
            export interface Repo {
                findById(id: string): Item
                delete(id: string): void
            }
            
            export const add = (a: number, b: number) => a + b
            export const sub = (a: number, b: number) => a - b
        """.trimIndent()

        val (tsNodes, _, _) = PolyglotAstExtractor.extractFile("proj", "repo.ts", tsCode)
        val tsMap = tsNodes.associateBy { it.name }
        val addTs = tsMap["add"]
        assertNotNull(addTs)
        assertEquals(8, addTs.startLine)
        assertEquals(8, addTs.endLine)

        val subTs = tsMap["sub"]
        assertNotNull(subTs)
        assertEquals(9, subTs.startLine)
        assertEquals(9, subTs.endLine)
    }

    @Test
    fun testPolyglotAstExtractorEdgeCases() {
        val ktCode = """
            package com.tesco.test
            
            fun withDefaultParam(
                name: String,
                action: () -> Unit = {}
            ) {
                action()
                println("done")
            }
            
            fun branchy(x: Int) = if (x > 0) {
                val p = x * 2
                p
            } else {
                val n = x - 1
                n
            }
            
            fun tryCatchy() = try {
                100 / 2
            } catch (e: Exception) {
                0
            }
            
            fun chainedLambdas(items: List<String>) = items
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotEmpty()
                }
            
            fun rawMultilineTemplate() = ""${'"'}
                {
                    "item": 123
                }
            ""${'"'}.trimIndent()
            
            fun withBlockComment() {
                /*
                 * multiline { comment }
                 */
                println("safe")
            }
        """.trimIndent()

        val (nodes, _, _) = PolyglotAstExtractor.extractFile("p", "EdgeCases.kt", ktCode)
        val nodeMap = nodes.associateBy { it.name }

        val withDef = nodeMap["withDefaultParam"]
        assertNotNull(withDef)
        assertEquals(3, withDef.startLine)
        assertEquals(9, withDef.endLine)

        val branchyNode = nodeMap["branchy"]
        assertNotNull(branchyNode)
        assertEquals(11, branchyNode.startLine)
        assertEquals(17, branchyNode.endLine)

        val tryCatchyNode = nodeMap["tryCatchy"]
        assertNotNull(tryCatchyNode)
        assertEquals(19, tryCatchyNode.startLine)
        assertEquals(23, tryCatchyNode.endLine)

        val chainedNode = nodeMap["chainedLambdas"]
        assertNotNull(chainedNode)
        assertEquals(25, chainedNode.startLine)
        assertEquals(31, chainedNode.endLine)

        val rawNode = nodeMap["rawMultilineTemplate"]
        assertNotNull(rawNode)
        assertEquals(33, rawNode.startLine)
        assertEquals(37, rawNode.endLine)

        val commentNode = nodeMap["withBlockComment"]
        assertNotNull(commentNode)
        assertEquals(39, commentNode.startLine)
        assertEquals(44, commentNode.endLine)
    }

    @Test
    fun testRawSymbolEdgesPersistenceAndGetFileRecordsMap() {
        val dbPath = "/tmp/test_atlas_raw_edges_${getpid()}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val idxStmt = store.prepare(
                "SELECT name FROM sqlite_master WHERE type='index' AND name IN ('idx_vocab_node', 'idx_raw_edges_proj', 'idx_raw_edges_file');"
            )
            val indexNames = mutableSetOf<String>()
            try {
                while (idxStmt.step()) {
                    indexNames.add(idxStmt.columnString(0))
                }
            } finally {
                idxStmt.close()
            }
            assertTrue(indexNames.contains("idx_vocab_node"))
            assertTrue(indexNames.contains("idx_raw_edges_proj"))
            assertTrue(indexNames.contains("idx_raw_edges_file"))

            store.upsertFileRecord(
                projectId = "p",
                relPath = "src/Caller.kt",
                language = "kotlin",
                moduleId = ":app",
                sourceSet = "main",
                semioticLayer = "semantic",
                archLayer = "application",
                sha256 = "sha_caller",
                mtime = 1234.5,
                loc = 20,
                topLevelDeclCount = 1
            )
            val fileMap = store.getFileRecordsMap("p")
            assertEquals(Pair("sha_caller", 1234.5), fileMap["src/Caller.kt"])

            val rawCall = GraphEdge(
                edgeId = "p:src/Caller.kt#Caller.run:call:TargetService:10",
                projectId = "p",
                sourceNodeId = "p:src/Caller.kt#Caller.run",
                targetNodeId = "sym:TargetService",
                edgeType = "CALLS",
                provenance = "INFERRED_CALL",
                confidence = 0.85,
                lineNumber = 10
            )
            val containsEdge = GraphEdge(
                edgeId = "p:src/Caller.kt#Caller:contains:run",
                projectId = "p",
                sourceNodeId = "p:src/Caller.kt#Caller",
                targetNodeId = "p:src/Caller.kt#Caller.run",
                edgeType = "CONTAINS"
            )
            store.replaceFileRawSymbolEdges("p", "src/Caller.kt", listOf(rawCall, containsEdge))
            val storedRaw = store.listRawSymbolEdges("p")
            assertEquals(1, storedRaw.size)
            assertEquals("sym:TargetService", storedRaw[0].targetNodeId)
            assertEquals("p:src/Caller.kt#Caller.run:call:TargetService:10", storedRaw[0].edgeId)

            store.deleteFileRawSymbolEdges("p", "src/Caller.kt")
            assertTrue(store.listRawSymbolEdges("p").isEmpty())
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testCyclicCallersAndCalleesTerminateDeterministically() {
        val dbPath = "/tmp/test_atlas_cyclic_cte_${getpid()}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val nA = GraphNode("p:A", "p", "p:A.kt", "A", "pkg.A", "function")
            val nB = GraphNode("p:B", "p", "p:B.kt", "B", "pkg.B", "function")
            val nC = GraphNode("p:C", "p", "p:C.kt", "C", "pkg.C", "function")
            val edges = listOf(
                GraphEdge("e_ab", "p", "p:A", "p:B", "CALLS"),
                GraphEdge("e_ba", "p", "p:B", "p:A", "CALLS"),
                GraphEdge("e_bc", "p", "p:B", "p:C", "CALLS"),
                GraphEdge("e_ca", "p", "p:C", "p:A", "CALLS")
            )
            store.upsertNodesAndEdges("p", listOf(nA, nB, nC), edges)

            val callersA = store.getCallers("p", "p:A", depth = 10)
            assertTrue(callersA.isNotEmpty())
            assertTrue(callersA.all { it.hop <= 3 }, "Cyclic caller traversal must terminate without walking up to depth=10")
            assertTrue(callersA.any { it.sourceId == "p:B" && it.hop == 1 })
            assertTrue(callersA.any { it.sourceId == "p:C" && it.hop == 1 })

            val calleesA = store.getCallees("p", "p:A", depth = 10)
            assertTrue(calleesA.isNotEmpty())
            assertTrue(calleesA.all { it.hop <= 3 }, "Cyclic callee traversal must terminate without walking up to depth=10")
            assertTrue(calleesA.any { it.targetId == "p:B" && it.hop == 1 })
            assertTrue(calleesA.any { it.targetId == "p:C" && it.hop == 2 })
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }

    @Test
    fun testSearchNodesRankedOrderAndGetNodeQualifiedNameAndFileAnchoring() {
        val dbPath = "/tmp/test_atlas_search_getnode_${getpid()}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val nodes = mutableListOf<GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            for (i in 1..25) {
                nodes.add(
                    GraphNode(
                        nodeId = "p:noise_$i",
                        projectId = "p",
                        fileId = "p:src/FooBar.kt",
                        name = "parseItemNoise$i",
                        qualifiedName = "pkg.noise.parseItemNoise$i",
                        kind = "function"
                    )
                )
            }
            val exactNode = GraphNode(
                nodeId = "p:exact",
                projectId = "p",
                fileId = "p:src/Bar.kt",
                name = "parseItem",
                qualifiedName = "pkg.core.parseItem",
                kind = "function"
            )
            val highDegreeNode = GraphNode(
                nodeId = "p:popular",
                projectId = "p",
                fileId = "p:src/Popular.kt",
                name = "parseItem popular",
                qualifiedName = "pkg.core.parseItemPopular",
                kind = "function"
            )
            nodes.add(exactNode)
            nodes.add(highDegreeNode)
            for (i in 1..10) {
                edges.add(GraphEdge("e_pop_$i", "p", "p:noise_$i", "p:popular", "CALLS"))
            }
            store.upsertNodesAndEdges("p", nodes, edges)

            val topResults = store.searchNodes("p", "parseItem", limit = 3)
            assertTrue(topResults.isNotEmpty())
            assertEquals("p:exact", topResults[0].nodeId)
            assertTrue(topResults.any { it.nodeId == "p:popular" })

            // Verify wildcard escaping in searchNodes does not treat % as match-all
            val wildcardResults = store.searchNodes("p", "100%GuaranteedNonExistent", limit = 5)
            assertTrue(wildcardResults.isEmpty())

            // Verify getNode prioritizes exact qualified_name over higher in_degree file matches
            val byQName = store.getNode("p", "pkg.core.parseItem")
            assertNotNull(byQName)
            assertEquals("p:exact", byQName.nodeId)

            // Verify getNode anchors file suffix: "Bar.kt" matches "p:src/Bar.kt" and NOT "p:src/FooBar.kt"
            val byFileSuffix = store.getNode("p", "Bar.kt")
            assertNotNull(byFileSuffix)
            assertEquals("p:exact", byFileSuffix.nodeId)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }
}
