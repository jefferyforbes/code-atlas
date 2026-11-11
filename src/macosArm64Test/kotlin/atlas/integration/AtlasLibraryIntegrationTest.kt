package atlas.integration

import atlas.Atlas
import atlas.export.GraphVisualExporter
import atlas.export.SarifExporter
import atlas.query.GraphQueryEngine
import atlas.rule.ArchitectureRules
import atlas.util.PlatformFiles
import atlas.watch.AtlasWatchSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.posix.getpid

class AtlasLibraryIntegrationTest {

    @Test
    fun testCompleteLibraryWorkflowEndToEnd() {
        val pid = getpid()
        val rnd = Random.nextInt(100000, 999999)
        val wsDir = "/tmp/atlas_lib_e2e_${pid}_${rnd}"
        val dbPath = "$wsDir/integration.db"
        PlatformFiles.mkdirs("$wsDir/src/domain")
        PlatformFiles.mkdirs("$wsDir/src/data")
        PlatformFiles.mkdirs("$wsDir/src/ui")

        try {
            // 1. Create realistic multi-tier Kotlin codebase on disk
            PlatformFiles.writeText(
                "$wsDir/src/domain/OrderService.kt",
                """
                package domain
                class OrderService {
                    fun calculateTotal(items: List<Int>): Int = items.sum()
                }
                """.trimIndent()
            )
            PlatformFiles.writeText(
                "$wsDir/src/data/OrderRepository.kt",
                """
                package data
                class OrderRepository {
                    fun fetchOrders(): List<Int> = listOf(1, 2, 3)
                }
                """.trimIndent()
            )
            PlatformFiles.writeText(
                "$wsDir/src/ui/OrderController.kt",
                """
                package ui
                import domain.OrderService
                class OrderController {
                    fun render(): Int {
                        val svc = OrderService()
                        return svc.calculateTotal(emptyList())
                    }
                }
                """.trimIndent()
            )

            // 2. Open AtlasClient with disk SQLite database
            val client = Atlas.open(
                projectSlug = "lib-e2e",
                workspacePath = wsDir,
                dbPath = dbPath
            )

            // 3. Index workspace
            val indexReport = client.index(incremental = false)
            assertNotNull(indexReport)

            val stats = client.stats()
            assertTrue(stats.files >= 3, "Must have indexed at least 3 source files")
            assertTrue(stats.nodes >= 3, "Must have extracted at least 3 class declarations")

            val serviceNode = client.getNode("OrderService")
            assertNotNull(serviceNode, "OrderService node must exist in graph")
            val controllerNode = client.getNode("OrderController")
            assertNotNull(controllerNode, "OrderController node must exist in graph")

            // 4. GraphQueryEngine topological analysis
            val engine = GraphQueryEngine(client.store, "lib-e2e")
            val cycles = engine.findCycles()
            assertEquals(0, cycles.size, "Clean layered architecture must not contain circular dependencies")

            val topoSort = engine.topologicalSort()
            assertNotNull(topoSort, "DAG must produce a valid topological ordering")

            val roots = engine.findRoots()
            assertTrue(roots.isNotEmpty(), "OrderController or top-level components must be detected as roots")

            // 5. ArchitectureRules evaluation
            val rules = ArchitectureRules.define {
                layer("ui", "/ui/")
                layer("domain", "/domain/")
                layer("data", "/data/")

                "domain" shouldNotDependOn "ui"
                "domain" shouldNotDependOn "data"
                "ui" shouldNotDependOn "data"
            }
            val ruleReport = rules.evaluate(client.store, "lib-e2e")
            assertTrue(ruleReport.passed, "Clean codebase must pass architectural boundary rules")
            assertEquals(3, ruleReport.rulesChecked)

            // 6. SARIF and Visual Exporters
            val auditReport = client.auditArchitecture()
            val sarifJson = SarifExporter.toSarifJson(auditReport)
            assertTrue(sarifJson.contains("\"version\": \"2.1.0\""))
            assertTrue(sarifJson.contains("\"name\": \"Code Atlas\""))

            val allNodes = client.store.listNodes("lib-e2e")
            val allEdges = client.store.listEdges("lib-e2e")
            val mermaid = GraphVisualExporter.toMermaidFlowchart(allNodes, allEdges, direction = "TD", clusterBy = "layer")
            assertTrue(mermaid.startsWith("flowchart TD"))

            val dot = GraphVisualExporter.toGraphvizDot(allNodes, allEdges)
            assertTrue(dot.startsWith("digraph CodeAtlas {"))

            val cyto = GraphVisualExporter.toCytoscapeJson(allNodes, allEdges)
            assertTrue(cyto.contains("\"elements\""))

            // 7. AtlasWatchSession reactive change detection
            val watchSession = AtlasWatchSession(client)
            val noDelta = watchSession.pollOnce()
            assertNull(noDelta, "No filesystem changes should return null delta")

            // Add new file DiscountPolicy.kt
            PlatformFiles.writeText(
                "$wsDir/src/domain/DiscountPolicy.kt",
                """
                package domain
                class DiscountPolicy {
                    fun applyDiscount(amount: Int): Int = amount - 5
                }
                """.trimIndent()
            )

            var notifiedDelta: AtlasWatchSession.GraphDelta? = null
            val unregister = watchSession.registerListener { delta ->
                notifiedDelta = delta
            }

            val delta = watchSession.triggerCheck()
            assertNotNull(delta, "Watch session must detect new source file")
            assertTrue(delta.hasChanges)
            assertTrue(delta.changedFiles.any { it.contains("DiscountPolicy.kt") })
            assertTrue(delta.addedNodes.any { it.name == "DiscountPolicy" })
            assertEquals(delta, notifiedDelta, "Registered listener must receive delta")

            unregister()
            client.close()
        } finally {
            PlatformFiles.deleteRecursively(wsDir)
        }
    }
}
