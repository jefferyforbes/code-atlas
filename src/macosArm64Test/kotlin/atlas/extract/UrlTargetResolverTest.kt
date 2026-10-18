package atlas.extract

import atlas.db.SqliteGraphStore
import atlas.engine.FigmaDesignAuditor
import atlas.engine.ScopeSynthesizer
import atlas.engine.WorkspaceIndexer
import atlas.model.FigmaDocument
import atlas.model.FigmaNode
import atlas.util.PlatformFiles
import platform.posix.getpid
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlTargetResolverTest {

    @Test
    fun testParseFigmaUrlAcrossDesignFileBoardProtoAndInstanceSubNodes() {
        val designLink = UrlTargetResolver.parseFigmaUrl("https://www.figma.com/design/abcFileKey123/Checkout-Screen?node-id=304-335&t=xyz123")
        assertNotNull(designLink)
        assertEquals("abcFileKey123", designLink.fileKey)
        assertEquals("304:335", designLink.nodeId)

        val fileLink = UrlTargetResolver.parseFigmaUrl("https://figma.com/file/legacyKey999/My-Design?m=dev&node-id=12%3A45")
        assertNotNull(fileLink)
        assertEquals("legacyKey999", fileLink.fileKey)
        assertEquals("12:45", fileLink.nodeId)

        val boardLink = UrlTargetResolver.parseFigmaUrl("https://www.figma.com/board/boardKey777/Architecture-Topology")
        assertNotNull(boardLink)
        assertEquals("boardKey777", boardLink.fileKey)
        assertNull(boardLink.nodeId)

        val protoLink = UrlTargetResolver.parseFigmaUrl("https://www.figma.com/proto/protoKey555/Prototype?node-id=I12-34%3B56-78")
        assertNotNull(protoLink)
        assertEquals("protoKey555", protoLink.fileKey)
        assertEquals("I12:34;56:78", protoLink.nodeId)

        val mdWrapped = UrlTargetResolver.parseFigmaUrl("[Checkout Spec](https://www.figma.com/design/abcFileKey123/Spec?node-id=99-100)")
        assertNotNull(mdWrapped)
        assertEquals("abcFileKey123", mdWrapped.fileKey)
        assertEquals("99:100", mdWrapped.nodeId)

        assertNull(UrlTargetResolver.parseFigmaUrl("https://github.com/org/repo/blob/main/App.kt"))
        assertNull(UrlTargetResolver.parseFigmaUrl("simpleFileKey"))
    }

    @Test
    fun testNormalizeFigmaNodeIdPreservesNamesAndConvertsIds() {
        assertEquals("304:335", UrlTargetResolver.normalizeFigmaNodeId("304-335"))
        assertEquals("304:335", UrlTargetResolver.normalizeFigmaNodeId("304%3A335"))
        assertEquals("I12:34;56:78", UrlTargetResolver.normalizeFigmaNodeId("I12-34;56-78"))
        assertEquals("I12:34;56:78", UrlTargetResolver.normalizeFigmaNodeId("I12-34%3B56%3A78"))
        assertEquals("Button-Primary", UrlTargetResolver.normalizeFigmaNodeId("Button-Primary"))
        assertEquals("Header", UrlTargetResolver.normalizeFigmaNodeId("Header"))
    }

    @Test
    fun testParseCodeLocationUrlForFileGithubGitlabAndRelativeAnchors() {
        val fileUri = UrlTargetResolver.parseCodeLocationUrl("file:///Users/dev/project/src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt#L45-L60")
        assertNotNull(fileUri)
        assertEquals("src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt", fileUri.filePathSuffix)
        assertEquals(45, fileUri.startLine)
        assertEquals(60, fileUri.endLine)

        val ghBlob = UrlTargetResolver.parseCodeLocationUrl("https://github.com/acme/code-atlas/blob/main/src/macosArm64Main/kotlin/atlas/engine/ScopeSynthesizer.kt#L120")
        assertNotNull(ghBlob)
        assertEquals("src/macosArm64Main/kotlin/atlas/engine/ScopeSynthesizer.kt", ghBlob.filePathSuffix)
        assertEquals(120, ghBlob.startLine)
        assertEquals(120, ghBlob.endLine)

        val glBlob = UrlTargetResolver.parseCodeLocationUrl("https://gitlab.com/group/subgroup/service/-/blob/develop/app/controllers/user_controller.py#L10-25")
        assertNotNull(glBlob)
        assertEquals("app/controllers/user_controller.py", glBlob.filePathSuffix)
        assertEquals(10, glBlob.startLine)
        assertEquals(25, glBlob.endLine)

        val relAnchor = UrlTargetResolver.parseCodeLocationUrl("src/macosArm64Main/kotlin/atlas/cli/CodeGraphCli.kt#L15")
        assertNotNull(relAnchor)
        assertEquals("src/macosArm64Main/kotlin/atlas/cli/CodeGraphCli.kt", relAnchor.filePathSuffix)
        assertEquals(15, relAnchor.startLine)
        assertEquals(15, relAnchor.endLine)

        val relSymbol = UrlTargetResolver.parseCodeLocationUrl("src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt#searchByUrl")
        assertNotNull(relSymbol)
        assertEquals("src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt", relSymbol.filePathSuffix)
        assertNull(relSymbol.startLine)
        assertEquals("searchByUrl", relSymbol.symbolFragment)

        val glEncoded = UrlTargetResolver.parseCodeLocationUrl("https://gitlab.com/acme/platform/mobile/-/blob/main/src/Tools%20&%20Scripts/runner.py#L42")
        assertNotNull(glEncoded)
        assertEquals("src/Tools & Scripts/runner.py", glEncoded.filePathSuffix)
        assertEquals(42, glEncoded.startLine)
    }

    @Test
    fun testNormalizeRouteTemplateUnifiesParameterPlaceholders() {
        assertEquals("/api/v1/orders/{param}", UrlTargetResolver.normalizeRouteTemplate("/api/v1/orders/{orderId}"))
        assertEquals("/api/v1/orders/{param}", UrlTargetResolver.normalizeRouteTemplate("/api/v1/orders/:id"))
        assertEquals("/api/v1/orders/{param}", UrlTargetResolver.normalizeRouteTemplate("/api/v1/orders/\${order.id}"))
        assertEquals("/api/v1/orders/{param}", UrlTargetResolver.normalizeRouteTemplate("/api/v1/orders/\$orderId"))
        assertEquals("/api/v1/orders/{param}", UrlTargetResolver.normalizeRouteTemplate("/api/v1/orders/<int:order_id>"))
        assertEquals("/api/v1/orders/{param}", UrlTargetResolver.normalizeRouteTemplate("/api/v1/orders/42910"))
        assertEquals(
            "https://api.example.com/api/v1/orders/{param}",
            UrlTargetResolver.normalizeRouteTemplate("https://api.example.com/api/v1/orders/550e8400-e29b-41d4-a716-446655440000?expand=items")
        )
        assertFalse(UrlTargetResolver.isHttpRouteCandidate("/Users/jefferyforbes/Development/code-atlas/build.gradle.kts"))
        assertFalse(UrlTargetResolver.isHttpRouteCandidate("/tmp/test.db"))
    }

    @Test
    fun testIsUrlOrDeepLinkClassification() {
        assertTrue(UrlTargetResolver.isUrlOrDeepLink("https://github.com/org/repo/blob/main/App.kt#L10"))
        assertTrue(UrlTargetResolver.isUrlOrDeepLink("file:///Users/dev/App.kt#L10"))
        assertTrue(UrlTargetResolver.isUrlOrDeepLink("/api/v1/users/{id}"))
        assertTrue(UrlTargetResolver.isUrlOrDeepLink("src/main/App.kt#L20"))
        assertFalse(UrlTargetResolver.isUrlOrDeepLink("src/main/App.kt"))
        assertFalse(UrlTargetResolver.isUrlOrDeepLink("atlas.engine.ScopeSynthesizer"))
    }

    @Test
    fun testInnermostAstSpanAndFigmaDeepLinkResolution() {
        val tmpDir = "/tmp/atlas_pr2_test_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs("$tmpDir/src/main/kotlin/com/acme")
        val store = SqliteGraphStore("$tmpDir/atlas.db")
        try {
            val srcFile = "$tmpDir/src/main/kotlin/com/acme/OrderController.kt"
            PlatformFiles.writeText(
                srcFile,
                """
                package com.acme
                class OrderController {
                    fun handleGetOrder() {
                        println("order")
                    }
                }
                """.trimIndent()
            )
            WorkspaceIndexer(tmpDir, store).indexWorkspace("acme")

            val ghNode = store.getNode("acme", "https://github.com/acme/repo/blob/main/src/main/kotlin/com/acme/OrderController.kt#L4")
            assertNotNull(ghNode)
            assertEquals("handleGetOrder", ghNode.name)

            val fileNode = store.getNode("acme", "file://$srcFile#L3-L5")
            assertNotNull(fileNode)
            assertEquals("handleGetOrder", fileNode.name)

            val scope = ScopeSynthesizer(store).synthesizeScope(
                "acme",
                listOf("https://github.com/acme/repo/blob/main/src/main/kotlin/com/acme/OrderController.kt#L4")
            )
            assertTrue(scope.files.any { it.endsWith("OrderController.kt") })

            val fileKey = "abcFigmaKey123"
            store.insertFigmaDocument(FigmaDocument(fileKey = fileKey, name = "Checkout", version = "1"))
            store.insertFigmaNodes(
                fileKey,
                listOf(
                    FigmaNode(
                        nodeId = "304:335", fileKey = fileKey, pageName = "Page 1", name = "CheckoutHeader",
                        nodeType = "COMPONENT", layoutMode = "HORIZONTAL", sizingHorizontal = "FIXED",
                        sizingVertical = "HUG", width = 320.0, height = 64.0
                    )
                )
            )
            val fullFigmaUrl = "https://www.figma.com/design/abcFigmaKey123/Checkout-Design?node-id=304-335&t=abcd"
            assertEquals("CheckoutHeader", store.queryFigmaNodeById(fullFigmaUrl, "304-335")?.name)
            assertEquals(1, store.findFigmaShortestPath(fullFigmaUrl, "304-335", "304-335").size)
            val auditor = FigmaDesignAuditor(store)
            assertEquals(fileKey, auditor.audit(fullFigmaUrl).fileKey)
            assertEquals(1, auditor.synthesizeFigmaScope("acme", fullFigmaUrl, emptyList()).scopedNodesCount)
        } finally {
            store.close()
            for (f in PlatformFiles.listFilesRecursively(tmpDir)) PlatformFiles.delete(f)
            rmdir(tmpDir)
        }
    }

    @Test
    fun testExtractCodeRoutesAndEndpointGraphSynthesis() {
        val source = """
            package com.acme
            // Commented out URL https://commented.example.com/api/v1/ignored and route "/api/v1/commented"
            const val ORDERS_BASE_URL = "https://orders.internal.service/api/v1/orders"
            interface OrderApiClient {
                @GET("/api/v1/orders/{id}")
                suspend fun fetchOrder(id: String): String
            }
            fun registerRoutes() {
                get("/api/v1/orders/{orderId}") {
                    val upstream = client.get("https://billing.internal.service/v2/invoices")
                }
            }
        """.trimIndent()
        val masked = source.lines().joinToString("\n") { line ->
            if (line.trimStart().startsWith("//")) " ".repeat(line.length) else line
        }
        val extracted = UrlTargetResolver.extractCodeRoutesAndUrls(source, masked, "kotlin")
        val canonicals = extracted.map { it.canonicalRouteOrUrl }.toSet()
        assertTrue("/api/v1/orders/{param}" in canonicals)
        assertTrue("https://orders.internal.service/api/v1/orders" in canonicals)
        assertTrue("https://billing.internal.service/v2/invoices" in canonicals)
        assertFalse(canonicals.any { it.contains("ignored") || it.contains("commented") })

        val tmpDir = "/tmp/atlas_pr4_test_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs("$tmpDir/src/main/kotlin/com/acme")
        val store = SqliteGraphStore("$tmpDir/atlas.db")
        try {
            PlatformFiles.writeText("$tmpDir/src/main/kotlin/com/acme/Orders.kt", source)
            WorkspaceIndexer(tmpDir, store).indexWorkspace("acme")
            val epNode = store.getNode("acme", "/api/v1/orders/999")
            assertNotNull(epNode, "Expected canonical endpoint node for /api/v1/orders/999")
            assertEquals("endpoint", epNode.kind)
            assertEquals("/api/v1/orders/{param}", epNode.name)
            val resolved = store.resolveNodesByUrlOrRoute("acme", "/api/v1/orders/999")
            assertTrue(resolved.any { it.name == "fetchOrder" })
            assertTrue(resolved.any { it.name == "registerRoutes" })
        } finally {
            store.close()
            for (f in PlatformFiles.listFilesRecursively(tmpDir)) PlatformFiles.delete(f)
            rmdir(tmpDir)
        }
    }
}

