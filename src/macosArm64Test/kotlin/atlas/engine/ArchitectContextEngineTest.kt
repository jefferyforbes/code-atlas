package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.mcp.AtlasMcpServer
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import platform.posix.getpid

class ArchitectContextEngineTest {

    @Test
    fun testSynthesizeContextWithExplicitSeeds() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_arch_ctx_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val fileOrder = "src/services/OrderService.kt"
            val filePayment = "src/services/PaymentGateway.kt"

            val nodeOrder = GraphNode(
                nodeId = "proj:$fileOrder#OrderService",
                projectId = "proj",
                fileId = "proj:$fileOrder",
                name = "OrderService",
                qualifiedName = "com.example.OrderService",
                kind = "class",
                signature = "class OrderService(val gateway: PaymentGateway) {",
                startLine = 5,
                endLine = 50,
                moduleId = "services",
                archLayer = "domain",
                semioticLayer = "semantic"
            )
            val nodePlaceOrder = GraphNode(
                nodeId = "proj:$fileOrder#OrderService.placeOrder",
                projectId = "proj",
                fileId = "proj:$fileOrder",
                name = "placeOrder",
                qualifiedName = "com.example.OrderService.placeOrder",
                kind = "method",
                signature = "fun placeOrder(cartId: String): OrderResult",
                startLine = 12,
                endLine = 25,
                moduleId = "services",
                archLayer = "domain",
                semioticLayer = "semantic"
            )
            val nodePayment = GraphNode(
                nodeId = "proj:$filePayment#PaymentGateway",
                projectId = "proj",
                fileId = "proj:$filePayment",
                name = "PaymentGateway",
                qualifiedName = "com.example.PaymentGateway",
                kind = "interface",
                signature = "interface PaymentGateway {",
                startLine = 3,
                endLine = 20,
                moduleId = "services",
                archLayer = "infrastructure",
                semioticLayer = "syntactic"
            )

            store.upsertNodesAndEdges(
                "proj",
                listOf(nodeOrder, nodePlaceOrder, nodePayment),
                listOf(
                    GraphEdge(
                        edgeId = "e1",
                        projectId = "proj",
                        sourceNodeId = nodeOrder.nodeId,
                        targetNodeId = nodePayment.nodeId,
                        edgeType = "DEPENDS_ON"
                    ),
                    GraphEdge(
                        edgeId = "e2",
                        projectId = "proj",
                        sourceNodeId = nodePlaceOrder.nodeId,
                        targetNodeId = nodePayment.nodeId,
                        edgeType = "CALLS"
                    )
                )
            )

            val synthesizer = ScopeSynthesizer(store)
            val engine = ArchitectContextEngine(
                store = store,
                synthesizer = synthesizer,
                workspaceRoot = tmpDir,
                artifactsDir = "$tmpDir/artifacts",
                inlineCeilingBytes = 4000
            )

            val res = engine.synthesizeContext("proj", listOf("OrderService"), depth = 2)

            assertFalse(res.spilled, "Payload within ceiling must not spill")
            assertEquals("proj", res.projectId)
            assertTrue(res.files.contains(fileOrder), "Must include OrderService.kt")
            assertTrue(res.files.contains(filePayment), "Must include PaymentGateway.kt in blast radius")
            assertTrue(res.modules.contains("services"), "Must include services module")
            assertTrue(res.aciScore > 0.0, "ACI score must be positive")
            assertTrue(res.inlineMarkdown.contains("3D Concurrency Boundary"), "Must format 3D boundary section")
            assertTrue(res.inlineMarkdown.contains("OrderService"), "Must include OrderService in markdown")
            assertTrue(res.inlineMarkdown.contains("placeOrder"), "Must include child method placeOrder in skeleton")
        } finally {
            store.close()
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }

    @Test
    fun testNaturalLanguagePromptResolution() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_nlp_prompt_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val nodeBilling = GraphNode(
                nodeId = "proj:src/BillingManager.kt#BillingManager",
                projectId = "proj",
                fileId = "proj:src/BillingManager.kt",
                name = "BillingManager",
                qualifiedName = "com.example.BillingManager",
                kind = "class",
                signature = "class BillingManager {",
                startLine = 1,
                endLine = 30,
                moduleId = "billing"
            )
            store.upsertNodesAndEdges("proj", listOf(nodeBilling), emptyList())

            val synthesizer = ScopeSynthesizer(store)
            val engine = ArchitectContextEngine(
                store = store,
                synthesizer = synthesizer,
                workspaceRoot = tmpDir,
                artifactsDir = "$tmpDir/artifacts"
            )

            val res = engine.synthesizeContext("proj", listOf("how does billing manager work"), depth = 1)
            assertTrue(res.seedSymbols.contains("BillingManager"), "NLP prompt must resolve BillingManager symbol")
            assertTrue(res.files.contains("src/BillingManager.kt"), "Must include BillingManager.kt")
        } finally {
            store.close()
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }

    @Test
    fun testSpilloverWhenPayloadExceedsCeiling() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spillover_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val nodeLarge = GraphNode(
                nodeId = "proj:src/LargeSystem.kt#LargeSystem",
                projectId = "proj",
                fileId = "proj:src/LargeSystem.kt",
                name = "LargeSystem",
                qualifiedName = "com.example.LargeSystem",
                kind = "class",
                signature = "class LargeSystem {",
                startLine = 1,
                endLine = 100,
                moduleId = "core"
            )
            store.upsertNodesAndEdges("proj", listOf(nodeLarge), emptyList())

            val synthesizer = ScopeSynthesizer(store)
            val engine = ArchitectContextEngine(
                store = store,
                synthesizer = synthesizer,
                workspaceRoot = tmpDir,
                artifactsDir = "$tmpDir/artifacts",
                inlineCeilingBytes = 50 // Ultra low ceiling to force spillover
            )

            val res = engine.synthesizeContext("proj", listOf("LargeSystem"), depth = 1)
            assertTrue(res.spilled, "Must mark as spilled")
            val path = res.spilloverPath
            assertNotNull(path, "Spillover path must be provided")
            assertTrue(PlatformFiles.exists(path), "Spillover file must exist on disk")

            val diskContent = PlatformFiles.readText(path)
            assertTrue(diskContent.contains("3D Concurrency Boundary"), "Spillover file must have full context")
            assertTrue(res.inlineMarkdown.contains("Spilled to Disk"), "Inline markdown must indicate spillover")
            assertTrue(res.inlineMarkdown.contains(path), "Inline markdown must link to file")
        } finally {
            store.close()
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }

    @Test
    fun testMcpExploreAndArchitectContextIntegration() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_mcp_explore_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val server = AtlasMcpServer(workspaceRoot = tmpDir, customDbPath = dbPath, enableCodegraphTools = false)
        try {
            // Default 5-tool boundary check
            val tools = server.toolDefinitions()
            assertEquals(5, tools.size, "Default MCP mode must expose exactly 5 tools")

            val nodeSym = GraphNode(
                nodeId = "shop:src/Checkout.kt#CheckoutService",
                projectId = "shop",
                fileId = "shop:src/Checkout.kt",
                name = "CheckoutService",
                qualifiedName = "com.shop.CheckoutService",
                kind = "class",
                signature = "class CheckoutService {",
                startLine = 5,
                endLine = 40,
                moduleId = "checkout"
            )
            server.store.upsertNodesAndEdges("shop", listOf(nodeSym), emptyList())

            // 1. Test atlas_query with action="explore"
            val exploreResult = server.callTool("atlas_query", buildJsonObject {
                put("action", "explore")
                put("target", "CheckoutService")
                put("project", "shop")
            })
            val text = exploreResult["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(text.contains("CheckoutService"), "Explore action must discover CheckoutService")

            // 2. Test atlas_audit with action="architect_context"
            val auditResult = server.callTool("atlas_audit", buildJsonObject {
                put("action", "architect_context")
                putJsonArray("targets") { add("CheckoutService") }
                put("project", "shop")
            })
            val md = auditResult["inlineMarkdown"]?.jsonPrimitive?.content ?: ""
            assertTrue(md.contains("3D Concurrency Boundary"), "Architect context must return 3D Concurrency Boundary")
            assertTrue(auditResult["aciScore"]?.jsonPrimitive?.double ?: 0.0 > 0.0, "Must calculate ACI score")
            assertNotNull(auditResult["semiotic_sizing"], "Must include semiotic_sizing")
        } finally {
            server.store.close()
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }

    @Test
    fun testComputeSemioticSizingBands() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_semiotic_sizing_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val synthesizer = ScopeSynthesizer(store)
            val engine = ArchitectContextEngine(store, synthesizer, tmpDir, "$tmpDir/artifacts")

            // Band 1: <= 3 files, <= 1 module, ACI <= 5.0
            val band1 = engine.computeSemioticSizing(filesCount = 2, modulesCount = 1, codeAci = 3.5, vscScore = 0.0)
            assertEquals("Band 1", band1.velocityBand)
            assertEquals("Fast-Path Direct", band1.recommendedPipeline)
            assertTrue(band1.rationale.contains("Fast-Path Direct"))

            // Band 2: 4-8 files or 5.0 < ACI <= 15.0
            val band2ByFiles = engine.computeSemioticSizing(filesCount = 5, modulesCount = 1, codeAci = 4.0, vscScore = 0.0)
            assertEquals("Band 2", band2ByFiles.velocityBand)
            assertEquals("Medium Plan", band2ByFiles.recommendedPipeline)

            val band2ByAci = engine.computeSemioticSizing(filesCount = 2, modulesCount = 1, codeAci = 8.5, vscScore = 0.0)
            assertEquals("Band 2", band2ByAci.velocityBand)

            // Band 3: > 8 files, >= 3 modules, or ACI > 15.0
            val band3ByFiles = engine.computeSemioticSizing(filesCount = 10, modulesCount = 2, codeAci = 6.0, vscScore = 0.0)
            assertEquals("Band 3", band3ByFiles.velocityBand)
            assertEquals("Multi-Agent Swarm", band3ByFiles.recommendedPipeline)

            val band3ByModules = engine.computeSemioticSizing(filesCount = 3, modulesCount = 3, codeAci = 4.0, vscScore = 0.0)
            assertEquals("Band 3", band3ByModules.velocityBand)

            val band3ByAci = engine.computeSemioticSizing(filesCount = 2, modulesCount = 1, codeAci = 18.0, vscScore = 0.0)
            assertEquals("Band 3", band3ByAci.velocityBand)
        } finally {
            store.close()
            PlatformFiles.deleteRecursively(tmpDir)
        }
    }
}
