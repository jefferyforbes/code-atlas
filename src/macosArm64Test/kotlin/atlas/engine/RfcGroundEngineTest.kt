package atlas.engine

import atlas.cli.AtlasCli
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid

class RfcGroundEngineTest {

    @Test
    fun `subsystem discovery table outputs formatted markdown table under 800 bytes with accurate contracts and entry points`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_rfc_ground_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "test-billing"

            val orderModel = GraphNode(
                nodeId = "$projectId:src/billing/Order.kt#Order",
                projectId = projectId,
                fileId = "$projectId:src/billing/Order.kt",
                name = "Order",
                qualifiedName = "com.example.billing.Order",
                kind = "data_class",
                signature = "data class Order(val id: String, val amount: Double)",
                startLine = 5,
                endLine = 15,
                moduleId = "billing",
                archLayer = "domain",
                semioticLayer = "syntactic"
            )

            val invoiceContract = GraphNode(
                nodeId = "$projectId:src/billing/InvoiceContract.kt#InvoiceContract",
                projectId = projectId,
                fileId = "$projectId:src/billing/InvoiceContract.kt",
                name = "InvoiceContract",
                qualifiedName = "com.example.billing.InvoiceContract",
                kind = "interface",
                signature = "interface InvoiceContract",
                startLine = 3,
                endLine = 20,
                moduleId = "billing",
                archLayer = "domain",
                semioticLayer = "syntactic"
            )

            val billingEngine = GraphNode(
                nodeId = "$projectId:src/billing/BillingEngine.kt#BillingEngine",
                projectId = projectId,
                fileId = "$projectId:src/billing/BillingEngine.kt",
                name = "BillingEngine",
                qualifiedName = "com.example.billing.BillingEngine",
                kind = "class",
                signature = "class BillingEngine : InvoiceContract",
                startLine = 10,
                endLine = 60,
                moduleId = "billing",
                archLayer = "domain",
                semioticLayer = "semantic",
                inDegree = 2,
                outDegree = 1
            )

            val billingCli = GraphNode(
                nodeId = "$projectId:src/billing/BillingCli.kt#BillingCli",
                projectId = projectId,
                fileId = "$projectId:src/billing/BillingCli.kt",
                name = "BillingCli",
                qualifiedName = "com.example.billing.BillingCli",
                kind = "class",
                signature = "class BillingCli",
                startLine = 4,
                endLine = 30,
                moduleId = "billing",
                archLayer = "presentation",
                semioticLayer = "pragmatic",
                inDegree = 1,
                outDegree = 1
            )

            val externalClient = GraphNode(
                nodeId = "$projectId:src/external/StoreClient.kt#StoreClient",
                projectId = projectId,
                fileId = "$projectId:src/external/StoreClient.kt",
                name = "StoreClient",
                qualifiedName = "com.example.external.StoreClient",
                kind = "class",
                signature = "class StoreClient",
                startLine = 5,
                endLine = 40,
                moduleId = "external",
                archLayer = "application",
                semioticLayer = "semantic"
            )

            val externalPaymentGateway = GraphNode(
                nodeId = "$projectId:src/external/PaymentGateway.kt#PaymentGateway",
                projectId = projectId,
                fileId = "$projectId:src/external/PaymentGateway.kt",
                name = "PaymentGateway",
                qualifiedName = "com.example.external.PaymentGateway",
                kind = "class",
                signature = "class PaymentGateway",
                startLine = 8,
                endLine = 50,
                moduleId = "external",
                archLayer = "infrastructure",
                semioticLayer = "pragmatic"
            )

            store.upsertNodesAndEdges(
                projectId,
                listOf(orderModel, invoiceContract, billingEngine, billingCli, externalClient, externalPaymentGateway),
                listOf(
                    GraphEdge(
                        edgeId = "e1",
                        projectId = projectId,
                        sourceNodeId = externalClient.nodeId,
                        targetNodeId = billingEngine.nodeId,
                        edgeType = "CALLS",
                        lineNumber = 15
                    ),
                    GraphEdge(
                        edgeId = "e2",
                        projectId = projectId,
                        sourceNodeId = billingCli.nodeId,
                        targetNodeId = billingEngine.nodeId,
                        edgeType = "CALLS",
                        lineNumber = 22
                    ),
                    GraphEdge(
                        edgeId = "e3",
                        projectId = projectId,
                        sourceNodeId = billingEngine.nodeId,
                        targetNodeId = externalPaymentGateway.nodeId,
                        edgeType = "USES",
                        lineNumber = 44
                    )
                )
            )

            val engine = RfcGroundEngine(store)
            val result = engine.groundSubsystem(projectId, "billing")

            assertTrue(result.byteSize <= 800, "Markdown table byte size (${result.byteSize}) must be <= 800 bytes")
            assertTrue(result.markdownTable.encodeToByteArray().size <= 800, "Table bytes > 800: ${result.markdownTable}")
            assertTrue(result.matchedNodesCount >= 4, "Should match billing nodes, matched: ${result.matchedNodesCount}")

            assertTrue(result.domainContracts.contains("InvoiceContract") || result.domainContracts.contains("Order"), "domainContracts: ${result.domainContracts}")
            assertTrue(result.syntacticTier.contains("Order") || result.syntacticTier.contains("InvoiceContract"), "syntacticTier: ${result.syntacticTier}")
            assertTrue(result.semanticTier.contains("BillingEngine"), "semanticTier: ${result.semanticTier}")
            assertTrue(result.pragmaticTier.contains("BillingCli"), "pragmaticTier: ${result.pragmaticTier}")

            assertTrue(result.afferentEntryPoints.any { it.contains("BillingEngine") }, "afferentEntryPoints: ${result.afferentEntryPoints}")
            assertTrue(result.efferentDependencies.contains("PaymentGateway"), "efferentDependencies: ${result.efferentDependencies}")

            assertTrue(result.markdownTable.contains("| Dimension | Subsystem Grounding (`billing`) |"))
            assertTrue(result.markdownTable.contains("**Domain Contracts**"))
            assertTrue(result.markdownTable.contains("**Afferent Entry Points**"))
            assertTrue(result.markdownTable.contains("**Efferent Dependencies**"))
            assertTrue(result.markdownTable.contains("**Syntactic Tier**"))
            assertTrue(result.markdownTable.contains("**Semantic Tier**"))
            assertTrue(result.markdownTable.contains("**Pragmatic Tier**"))
        } finally {
            store.close()
        }
    }

    @Test
    fun `handles empty and unindexed query gracefully with default markdown table within budget`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_rfc_empty_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val engine = RfcGroundEngine(store)
            val resultEmpty = engine.groundSubsystem("empty-proj", "")
            assertEquals(0, resultEmpty.matchedNodesCount)
            assertTrue(resultEmpty.byteSize <= 800)
            assertTrue(resultEmpty.markdownTable.contains("(none)"))

            val resultNonExistent = engine.groundSubsystem("empty-proj", "nonexistent-concept")
            assertEquals(0, resultNonExistent.matchedNodesCount)
            assertTrue(resultNonExistent.byteSize <= 800)
            assertTrue(resultNonExistent.markdownTable.contains("(none)"))
        } finally {
            store.close()
        }
    }

    @Test
    fun `CLI dispatch executes atlas rfc-ground with table output and exit code 0`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_rfc_cli_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "cli-test"
            val node = GraphNode(
                nodeId = "$projectId:src/AuthService.kt#AuthService",
                projectId = projectId,
                fileId = "$projectId:src/AuthService.kt",
                name = "AuthService",
                qualifiedName = "com.example.AuthService",
                kind = "class",
                signature = "class AuthService",
                startLine = 1,
                endLine = 20,
                moduleId = "auth",
                archLayer = "domain",
                semioticLayer = "semantic"
            )
            store.upsertNodesAndEdges(projectId, listOf(node), emptyList())

            val exitTable = AtlasCli.run(arrayOf("rfc-ground", "--project", projectId, "--query", "auth", "--db", dbPath, "--workspace", tmpDir))
            assertEquals(0, exitTable)

            val exitJson = AtlasCli.run(arrayOf("rfc-ground", "--project", projectId, "--query", "auth", "--db", dbPath, "--workspace", tmpDir, "--json"))
            assertEquals(0, exitJson)
        } finally {
            store.close()
        }
    }

    @Test
    fun `MCP atlas_query action rfc_ground returns valid json payload`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_rfc_mcp_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val server = AtlasMcpServer(workspaceRoot = tmpDir, customDbPath = dbPath)
        try {
            val projectId = "mcp-test"
            val node = GraphNode(
                nodeId = "$projectId:src/TokenService.kt#TokenService",
                projectId = projectId,
                fileId = "$projectId:src/TokenService.kt",
                name = "TokenService",
                qualifiedName = "com.example.TokenService",
                kind = "class",
                signature = "class TokenService",
                startLine = 1,
                endLine = 20,
                moduleId = "token",
                archLayer = "domain",
                semioticLayer = "semantic"
            )
            server.store.upsertNodesAndEdges(projectId, listOf(node), emptyList())

            val args = buildJsonObject {
                put("action", "rfc_ground")
                put("project", projectId)
                put("query", "Token")
            }
            val res = server.callTool("atlas_query", args)

            val table = res["markdownTable"]?.jsonPrimitive?.content ?: res["markdown_table"]?.jsonPrimitive?.content
            assertNotNull(table)
            assertEquals("Token", res["query"]?.jsonPrimitive?.content)
            val byteSize = res["byteSize"]?.jsonPrimitive?.int ?: res["byte_size"]?.jsonPrimitive?.int ?: 0
            assertTrue(byteSize <= 800)
        } finally {
            server.close()
        }
    }

    @Test
    fun `subsystem discovery matches kebab case snake case and spaced queries against PascalCase symbols`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_rfc_naming_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "naming-test"
            val node = GraphNode(
                nodeId = "$projectId:src/PaymentGatewayService.kt#PaymentGatewayService",
                projectId = projectId,
                fileId = "$projectId:src/PaymentGatewayService.kt",
                name = "PaymentGatewayService",
                qualifiedName = "com.example.PaymentGatewayService",
                kind = "class",
                signature = "class PaymentGatewayService",
                startLine = 1,
                endLine = 30,
                moduleId = "payment",
                archLayer = "domain",
                semioticLayer = "semantic"
            )
            store.upsertNodesAndEdges(projectId, listOf(node), emptyList())

            val engine = RfcGroundEngine(store)

            val resKebab = engine.groundSubsystem(projectId, "payment-gateway")
            assertEquals(1, resKebab.matchedNodesCount)
            assertTrue(resKebab.semanticTier.contains("PaymentGatewayService"))

            val resSnake = engine.groundSubsystem(projectId, "payment_gateway")
            assertEquals(1, resSnake.matchedNodesCount)
            assertTrue(resSnake.semanticTier.contains("PaymentGatewayService"))

            val resSpaced = engine.groundSubsystem(projectId, "payment gateway")
            assertEquals(1, resSpaced.matchedNodesCount)
            assertTrue(resSpaced.semanticTier.contains("PaymentGatewayService"))

            val resPunct = engine.groundSubsystem(projectId, "\"Payment*\"")
            assertEquals(1, resPunct.matchedNodesCount)
            assertTrue(resPunct.semanticTier.contains("PaymentGatewayService"))
        } finally {
            store.close()
        }
    }

    @Test
    fun `subsystem discovery filters out markdown doc nodes and file containment edges from dependencies`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_rfc_doc_filter_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "doc-filter-test"
            val serviceNode = GraphNode(
                nodeId = "$projectId:src/OrderProcessor.kt#OrderProcessor",
                projectId = projectId,
                fileId = "$projectId:src/OrderProcessor.kt",
                name = "OrderProcessor",
                qualifiedName = "com.example.OrderProcessor",
                kind = "class",
                moduleId = "orders",
                archLayer = "domain",
                semioticLayer = "semantic"
            )
            val docNode = GraphNode(
                nodeId = "$projectId:docs/RFC-001.md#RFC-001",
                projectId = projectId,
                fileId = "$projectId:docs/RFC-001.md",
                name = "RFC-001.md",
                qualifiedName = "RFC-001",
                kind = "markdown_doc"
            )
            val testNode = GraphNode(
                nodeId = "$projectId:srcTest/OrderProcessorTest.kt#OrderProcessorTest",
                projectId = projectId,
                fileId = "$projectId:srcTest/OrderProcessorTest.kt",
                name = "OrderProcessorTest",
                qualifiedName = "com.example.OrderProcessorTest",
                kind = "class",
                archLayer = "test",
                semioticLayer = "test"
            )
            val realDepNode = GraphNode(
                nodeId = "$projectId:src/DatabaseClient.kt#DatabaseClient",
                projectId = projectId,
                fileId = "$projectId:src/DatabaseClient.kt",
                name = "DatabaseClient",
                qualifiedName = "com.example.DatabaseClient",
                kind = "class",
                moduleId = "db",
                archLayer = "infrastructure",
                semioticLayer = "pragmatic"
            )

            store.upsertNodesAndEdges(
                projectId,
                listOf(serviceNode, docNode, testNode, realDepNode),
                listOf(
                    GraphEdge(edgeId = "e1", projectId = projectId, sourceNodeId = docNode.nodeId, targetNodeId = serviceNode.nodeId, edgeType = "PLAN_SCOPES_FILE"),
                    GraphEdge(edgeId = "e2", projectId = projectId, sourceNodeId = testNode.nodeId, targetNodeId = serviceNode.nodeId, edgeType = "CALLS"),
                    GraphEdge(edgeId = "e3", projectId = projectId, sourceNodeId = serviceNode.nodeId, targetNodeId = docNode.nodeId, edgeType = "DOCUMENTS"),
                    GraphEdge(edgeId = "e4", projectId = projectId, sourceNodeId = serviceNode.nodeId, targetNodeId = realDepNode.nodeId, edgeType = "CALLS")
                )
            )

            val engine = RfcGroundEngine(store)
            val res = engine.groundSubsystem(projectId, "OrderProcessor")

            assertEquals(1, res.matchedNodesCount)
            assertFalse(res.efferentDependencies.contains("RFC-001.md"))
            assertTrue(res.efferentDependencies.contains("DatabaseClient"))
            assertFalse(res.afferentEntryPoints.any { it.contains("OrderProcessorTest") })
            assertFalse(res.afferentEntryPoints.any { it.contains("RFC-001") })
        } finally {
            store.close()
        }
    }
}
