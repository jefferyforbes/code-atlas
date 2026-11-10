package atlas.engine

import atlas.cli.AtlasCli
import atlas.db.SqliteGraphStore
import atlas.mcp.AtlasMcpServer
import atlas.util.PlatformFiles
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Mock fixtures for hermetic offline testing without live network
class UrlGraphIndexingAndQueryTest {

    private fun makeTempDir(): String {
        val path = "/tmp/atlas_url_graph_test_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(path)
        return path
    }

    private fun cleanDir(dir: String) {
        for (f in PlatformFiles.listFilesRecursively(dir)) PlatformFiles.delete(f)
        rmdir(dir)
    }

    @Test
    fun testPolyglotRouteAndUrlGraphIndexingIncrementalPersistenceAndAuditSafety() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/atlas.db"
        val store = SqliteGraphStore(dbPath)
        try {
            val serverDir = "$tmpDir/src/main/kotlin/com/acme/server"
            val clientDir = "$tmpDir/src/main/kotlin/com/acme/client"
            val docsDir = "$tmpDir/docs"
            PlatformFiles.mkdirs(serverDir); PlatformFiles.mkdirs(clientDir); PlatformFiles.mkdirs(docsDir)

            val serverFile = "$serverDir/OrderRoutes.kt"
            PlatformFiles.writeText(
                serverFile,
                """
                package com.acme.server
                const val UPSTREAM_PAYMENT_URL = "https://payments.acme.internal/v1/charge"
                /** Registers order HTTP routes. */
                class OrderController {
                    fun handleGetOrder() {
                        get("/api/v1/orders/{orderId}") { println(UPSTREAM_PAYMENT_URL) }
                    }
                }
                """.trimIndent()
            )
            val clientFile = "$clientDir/OrderApiClient.kt"
            PlatformFiles.writeText(
                clientFile,
                """
                package com.acme.client
                /** Retrofit client for orders. */
                interface OrderApiClient {
                    @GET("/api/v1/orders/{id}")
                    suspend fun fetchOrder(id: String): String
                }
                """.trimIndent()
            )
            val docFile = "$docsDir/order-architecture.md"
            PlatformFiles.writeText(
                docFile,
                """
                # Order Service Architecture
                Calls `/api/v1/orders/{orderId}` handled by
                [handleGetOrder](https://github.com/acme/repo/blob/main/src/main/kotlin/com/acme/server/OrderRoutes.kt#L6)
                forwarding to [Payments](https://payments.acme.internal/v1/charge).
                """.trimIndent()
            )

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("acme")

            val endpointNode = store.getNode("acme", "/api/v1/orders/12345")
            assertNotNull(endpointNode)
            assertEquals("acme:endpoint#/api/v1/orders/{param}", endpointNode.nodeId)
            val callerNames = store.getCallers("acme", endpointNode.nodeId, depth = 1).map { it.name }.toSet()
            assertTrue("handleGetOrder" in callerNames && "fetchOrder" in callerNames && "order-architecture" in callerNames)

            val auditor = ArchitectureAuditor(store)
            assertTrue(auditor.runFullAudit("acme").singleDeclarationViolations.none { it.relPath.endsWith("OrderRoutes.kt") })
            assertFalse(auditor.detectDeadCode("acme").any { it.kind in setOf("endpoint", "url_ref", "file_scope") })
            assertFalse(store.getGodNodes("acme", topN = 20).any { it.kind in setOf("endpoint", "url_ref", "file_scope") })

            indexer.indexWorkspace("acme", subpaths = listOf("src/main/kotlin/com/acme/client"))
            assertNotNull(store.getNode("acme", "/api/v1/orders/{param}"))

            val mcpServer = AtlasMcpServer(workspaceRoot = tmpDir, customDbPath = dbPath)
            try {
                assertEquals(4, mcpServer.toolDefinitions().size)
                val urlSearchRes = mcpServer.callTool(
                    "atlas_query",
                    buildJsonObject {
                        put("action", "url"); put("project", "acme")
                        put("target", "https://payments.acme.internal/v1/charge")
                    }
                )
                assertTrue((urlSearchRes["document_references"]?.jsonArray?.size ?: 0) >= 2)
            } finally {
                mcpServer.close()
            }

            assertEquals(0, AtlasCli.run(arrayOf("--workspace", tmpDir, "--db", dbPath, "--project", "acme", "--url", "/api/v1/orders/999", "--json")))

            PlatformFiles.writeText(serverFile, "package com.acme.server\n/** No routes. */\nclass OrderController { fun handleGetOrder() {} }")
            PlatformFiles.writeText(clientFile, "package com.acme.client\n/** No routes. */\ninterface OrderApiClient { suspend fun fetchOrder(id: String): String }")
            PlatformFiles.writeText(docFile, "# Order Service\nNo routes.")
            indexer.indexWorkspace("acme")
            assertEquals(null, store.listNodes("acme").firstOrNull { it.nodeId == "acme:endpoint#/api/v1/orders/{param}" })
            assertTrue(store.searchNodes("acme", "/api/v1/orders/{param}", limit = 10).none { it.kind == "endpoint" })
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testPythonFastApiAndTypeScriptExpressRouteCrossLanguageMatching() {
        val tmpDir = makeTempDir()
        val store = SqliteGraphStore("$tmpDir/polyglot_url.db")
        try {
            PlatformFiles.mkdirs("$tmpDir/backend"); PlatformFiles.mkdirs("$tmpDir/frontend")
            PlatformFiles.writeText(
                "$tmpDir/backend/api.py",
                "@app.get(\"/api/v2/invoices/{invoice_id}\")\ndef get_invoice(invoice_id: str):\n    return {\"id\": invoice_id}\n"
            )
            PlatformFiles.writeText(
                "$tmpDir/frontend/client.ts",
                "export async function loadInvoice(id: string) { return await fetch(`/api/v2/invoices/\${id}`); }\n"
            )
            WorkspaceIndexer(tmpDir, store).indexWorkspace("poly")
            val urlReport = store.searchByUrl("poly", "/api/v2/invoices/98765")
            val producers = urlReport["producers"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.content }.orEmpty()
            val consumers = urlReport["consumers"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.content }.orEmpty()
            assertTrue("get_invoice" in producers)
            assertTrue("loadInvoice" in consumers)
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }
}
