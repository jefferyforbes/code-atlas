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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.getpid

class SpikeAnalysisEngineTest {

    @Test
    fun `decoupling hypothesis confirmed when zero coupling paths exist between subsystems`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_decoupling_conf_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "decouple-test"

            val nodeA = GraphNode(
                nodeId = "$projectId:src/SubsystemA.kt#SubsystemA",
                projectId = projectId,
                fileId = "$projectId:src/SubsystemA.kt",
                name = "SubsystemA",
                qualifiedName = "com.example.SubsystemA",
                kind = "class",
                moduleId = "moduleA"
            )

            val nodeB = GraphNode(
                nodeId = "$projectId:src/SubsystemB.kt#SubsystemB",
                projectId = projectId,
                fileId = "$projectId:src/SubsystemB.kt",
                name = "SubsystemB",
                qualifiedName = "com.example.SubsystemB",
                kind = "class",
                moduleId = "moduleB"
            )

            val nodeC = GraphNode(
                nodeId = "$projectId:src/SubsystemC.kt#SubsystemC",
                projectId = projectId,
                fileId = "$projectId:src/SubsystemC.kt",
                name = "SubsystemC",
                qualifiedName = "com.example.SubsystemC",
                kind = "class",
                moduleId = "moduleC"
            )

            // A calls C, but C does NOT call B. B calls C. Directed path A -> B does not exist.
            store.upsertNodesAndEdges(
                projectId,
                listOf(nodeA, nodeB, nodeC),
                listOf(
                    GraphEdge(
                        edgeId = "e1",
                        projectId = projectId,
                        sourceNodeId = nodeA.nodeId,
                        targetNodeId = nodeC.nodeId,
                        edgeType = "CALLS",
                        lineNumber = 10
                    ),
                    GraphEdge(
                        edgeId = "e2",
                        projectId = projectId,
                        sourceNodeId = nodeB.nodeId,
                        targetNodeId = nodeC.nodeId,
                        edgeType = "CALLS",
                        lineNumber = 20
                    )
                )
            )

            val engine = SpikeAnalysisEngine(store)
            val result = engine.runSpike(projectId, hypothesis = "decoupling", from = "SubsystemA", to = "SubsystemB")

            assertEquals("CONFIRMED", result.status)
            assertTrue(result.passed)
            assertEquals("CONFIRMED (zero coupling paths)", result.message)
            assertTrue(result.path.isEmpty())
            assertTrue(result.markdownBlock.contains("CONFIRMED"))
        } finally {
            store.close()
        }
    }

    @Test
    fun `decoupling hypothesis falsified when directed coupling path exists with edge types and lines`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_decoupling_fals_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "decouple-fals-test"

            val nodeA = GraphNode(
                nodeId = "$projectId:src/A.kt#A",
                projectId = projectId,
                fileId = "$projectId:src/A.kt",
                name = "A",
                qualifiedName = "com.example.A",
                kind = "class",
                moduleId = "moduleA"
            )

            val nodeC = GraphNode(
                nodeId = "$projectId:src/C.kt#C",
                projectId = projectId,
                fileId = "$projectId:src/C.kt",
                name = "C",
                qualifiedName = "com.example.C",
                kind = "class",
                moduleId = "moduleC"
            )

            val nodeB = GraphNode(
                nodeId = "$projectId:src/B.kt#B",
                projectId = projectId,
                fileId = "$projectId:src/B.kt",
                name = "B",
                qualifiedName = "com.example.B",
                kind = "class",
                moduleId = "moduleB"
            )

            store.upsertNodesAndEdges(
                projectId,
                listOf(nodeA, nodeC, nodeB),
                listOf(
                    GraphEdge(
                        edgeId = "e1",
                        projectId = projectId,
                        sourceNodeId = nodeA.nodeId,
                        targetNodeId = nodeC.nodeId,
                        edgeType = "CALLS",
                        lineNumber = 14
                    ),
                    GraphEdge(
                        edgeId = "e2",
                        projectId = projectId,
                        sourceNodeId = nodeC.nodeId,
                        targetNodeId = nodeB.nodeId,
                        edgeType = "IMPORTS",
                        lineNumber = 2
                    )
                )
            )

            val engine = SpikeAnalysisEngine(store)
            val result = engine.runSpike(projectId, hypothesis = "decoupling", from = "A", to = "B")

            assertEquals("FALSIFIED", result.status)
            assertFalse(result.passed)
            assertTrue(result.message.startsWith("FALSIFIED (path: A -> C -> B with edge types and lines: A -[CALLS:14]-> C -[IMPORTS:2]-> B)"))
            assertEquals(2, result.path.size)
            assertEquals("A -> C -> B", result.path[0])
            assertEquals("A -[CALLS:14]-> C -[IMPORTS:2]-> B", result.path[1])
        } finally {
            store.close()
        }
    }

    @Test
    fun `clean arch hypothesis confirmed when domain layer has no lower tier efferent references`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_clean_conf_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "clean-conf-test"

            val domainEntity = GraphNode(
                nodeId = "$projectId:src/User.kt#User",
                projectId = projectId,
                fileId = "$projectId:src/User.kt",
                name = "User",
                qualifiedName = "com.example.domain.User",
                kind = "data_class",
                archLayer = "domain",
                semioticLayer = "syntactic"
            )

            val domainService = GraphNode(
                nodeId = "$projectId:src/UserService.kt#UserService",
                projectId = projectId,
                fileId = "$projectId:src/UserService.kt",
                name = "UserService",
                qualifiedName = "com.example.domain.UserService",
                kind = "class",
                archLayer = "domain",
                semioticLayer = "semantic"
            )

            store.upsertNodesAndEdges(
                projectId,
                listOf(domainEntity, domainService),
                listOf(
                    GraphEdge(
                        edgeId = "e1",
                        projectId = projectId,
                        sourceNodeId = domainService.nodeId,
                        targetNodeId = domainEntity.nodeId,
                        edgeType = "USES",
                        lineNumber = 12
                    )
                )
            )

            val engine = SpikeAnalysisEngine(store)
            val result = engine.runSpike(projectId, hypothesis = "clean-arch", target = "UserService")

            assertEquals("CONFIRMED", result.status)
            assertTrue(result.passed)
            assertTrue(result.violations.isEmpty())
            assertEquals("CONFIRMED (Clean Architecture invariants satisfied, zero layer inversions)", result.message)
        } finally {
            store.close()
        }
    }

    @Test
    fun `clean arch hypothesis falsified when domain references data or infrastructure with violation details`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_clean_fals_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "clean-fals-test"

            val domainEntity = GraphNode(
                nodeId = "$projectId:src/OrderEntity.kt#OrderEntity",
                projectId = projectId,
                fileId = "$projectId:src/OrderEntity.kt",
                name = "OrderEntity",
                qualifiedName = "com.example.domain.OrderEntity",
                kind = "class",
                archLayer = "domain",
                semioticLayer = "semantic"
            )

            val dataDao = GraphNode(
                nodeId = "$projectId:src/OrderDao.kt#OrderDao",
                projectId = projectId,
                fileId = "$projectId:src/OrderDao.kt",
                name = "OrderDao",
                qualifiedName = "com.example.data.OrderDao",
                kind = "class",
                archLayer = "data",
                semioticLayer = "pragmatic"
            )

            store.upsertNodesAndEdges(
                projectId,
                listOf(domainEntity, dataDao),
                listOf(
                    GraphEdge(
                        edgeId = "e1",
                        projectId = projectId,
                        sourceNodeId = domainEntity.nodeId,
                        targetNodeId = dataDao.nodeId,
                        edgeType = "CALLS",
                        lineNumber = 42
                    )
                )
            )

            val engine = SpikeAnalysisEngine(store)
            val result = engine.runSpike(projectId, hypothesis = "clean-arch", target = "OrderEntity")

            assertEquals("FALSIFIED", result.status)
            assertFalse(result.passed)
            assertTrue(result.violations.isNotEmpty())
            assertEquals("ARCH-LAYER-INVERSION", result.violations.first().rule)
            assertEquals("com.example.domain.OrderEntity", result.violations.first().source)
            assertEquals("com.example.data.OrderDao", result.violations.first().target)
            assertEquals(42, result.violations.first().lineNumber)
            assertTrue(result.message.contains("Domain symbol 'OrderEntity' (domain) illegally references Data/Infrastructure symbol 'OrderDao' (data)"))
        } finally {
            store.close()
        }
    }

    @Test
    fun `clean arch hypothesis falsified when target symbol references explicit forbidden layer`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_forbidden_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "forbidden-test"

            val serviceNode = GraphNode(
                nodeId = "$projectId:src/CoreService.kt#CoreService",
                projectId = projectId,
                fileId = "$projectId:src/CoreService.kt",
                name = "CoreService",
                qualifiedName = "com.example.CoreService",
                kind = "class",
                archLayer = "application",
                semioticLayer = "semantic"
            )

            val uiNode = GraphNode(
                nodeId = "$projectId:src/MainView.kt#MainView",
                projectId = projectId,
                fileId = "$projectId:src/MainView.kt",
                name = "MainView",
                qualifiedName = "com.example.MainView",
                kind = "composable",
                archLayer = "presentation",
                semioticLayer = "pragmatic"
            )

            store.upsertNodesAndEdges(
                projectId,
                listOf(serviceNode, uiNode),
                listOf(
                    GraphEdge(
                        edgeId = "e1",
                        projectId = projectId,
                        sourceNodeId = serviceNode.nodeId,
                        targetNodeId = uiNode.nodeId,
                        edgeType = "REFERENCES",
                        lineNumber = 18
                    )
                )
            )

            val engine = SpikeAnalysisEngine(store)
            val result = engine.runSpike(
                projectId = projectId,
                hypothesis = "clean-arch",
                target = "CoreService",
                forbiddenLayer = "pragmatic:presentation"
            )

            assertEquals("FALSIFIED", result.status)
            assertFalse(result.passed)
            assertTrue(result.violations.isNotEmpty())
            assertTrue(result.message.contains("forbidden layer 'pragmatic:presentation'"))
        } finally {
            store.close()
        }
    }

    @Test
    fun `blast ceiling hypothesis evaluates file threshold`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_blast_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "blast-test"

            val nodeA = GraphNode(
                nodeId = "$projectId:src/FileA.kt#RootA",
                projectId = projectId,
                fileId = "$projectId:src/FileA.kt",
                name = "RootA",
                qualifiedName = "com.example.RootA",
                kind = "class"
            )
            val nodeB = GraphNode(
                nodeId = "$projectId:src/FileB.kt#NodeB",
                projectId = projectId,
                fileId = "$projectId:src/FileB.kt",
                name = "NodeB",
                qualifiedName = "com.example.NodeB",
                kind = "class"
            )
            val nodeC = GraphNode(
                nodeId = "$projectId:src/FileC.kt#NodeC",
                projectId = projectId,
                fileId = "$projectId:src/FileC.kt",
                name = "NodeC",
                qualifiedName = "com.example.NodeC",
                kind = "class"
            )

            store.upsertNodesAndEdges(
                projectId,
                listOf(nodeA, nodeB, nodeC),
                listOf(
                    GraphEdge(edgeId = "e1", projectId = projectId, sourceNodeId = nodeA.nodeId, targetNodeId = nodeB.nodeId, edgeType = "CALLS"),
                    GraphEdge(edgeId = "e2", projectId = projectId, sourceNodeId = nodeB.nodeId, targetNodeId = nodeC.nodeId, edgeType = "CALLS")
                )
            )

            val engine = SpikeAnalysisEngine(store)
            val passResult = engine.runSpike(projectId, hypothesis = "blast-ceiling", target = "RootA", maxFiles = 3)
            assertEquals("CONFIRMED", passResult.status)
            assertTrue(passResult.passed)

            val failResult = engine.runSpike(projectId, hypothesis = "blast-ceiling", target = "RootA", maxFiles = 2)
            assertEquals("FALSIFIED", failResult.status)
            assertFalse(failResult.passed)
        } finally {
            store.close()
        }
    }

    @Test
    fun `CLI dispatch executes atlas spike with confirmation and falsification exit codes`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_cli_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "cli-spike"

            val nodeX = GraphNode(
                nodeId = "$projectId:src/X.kt#X",
                projectId = projectId,
                fileId = "$projectId:src/X.kt",
                name = "X",
                qualifiedName = "com.example.X",
                kind = "class"
            )
            val nodeY = GraphNode(
                nodeId = "$projectId:src/Y.kt#Y",
                projectId = projectId,
                fileId = "$projectId:src/Y.kt",
                name = "Y",
                qualifiedName = "com.example.Y",
                kind = "class"
            )

            store.upsertNodesAndEdges(
                projectId,
                listOf(nodeX, nodeY),
                listOf(
                    GraphEdge(edgeId = "e1", projectId = projectId, sourceNodeId = nodeX.nodeId, targetNodeId = nodeY.nodeId, edgeType = "CALLS", lineNumber = 10)
                )
            )

            // Decoupling from X to Y should be FALSIFIED -> exit code 1
            val exitFalsified = AtlasCli.run(arrayOf("spike", "--project", projectId, "--hypothesis", "decoupling", "--from", "X", "--to", "Y", "--db", dbPath, "--workspace", tmpDir))
            assertEquals(1, exitFalsified)

            // Decoupling from Y to X has 0 edges -> CONFIRMED -> exit code 0
            val exitConfirmed = AtlasCli.run(arrayOf("spike", "--project", projectId, "--hypothesis", "decoupling", "--from", "Y", "--to", "X", "--db", dbPath, "--workspace", tmpDir))
            assertEquals(0, exitConfirmed)
        } finally {
            store.close()
        }
    }

    @Test
    fun `MCP atlas_audit action spike returns structured result payload`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_mcp_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val server = AtlasMcpServer(workspaceRoot = tmpDir, customDbPath = dbPath)
        try {
            val projectId = "mcp-spike"
            val nodeA = GraphNode(
                nodeId = "$projectId:src/ServiceA.kt#ServiceA",
                projectId = projectId,
                fileId = "$projectId:src/ServiceA.kt",
                name = "ServiceA",
                qualifiedName = "com.example.ServiceA",
                kind = "class"
            )
            val nodeB = GraphNode(
                nodeId = "$projectId:src/ServiceB.kt#ServiceB",
                projectId = projectId,
                fileId = "$projectId:src/ServiceB.kt",
                name = "ServiceB",
                qualifiedName = "com.example.ServiceB",
                kind = "class"
            )
            server.store.upsertNodesAndEdges(projectId, listOf(nodeA, nodeB), emptyList())

            val args = buildJsonObject {
                put("action", "spike")
                put("project", projectId)
                put("hypothesis", "decoupling")
                put("from", "ServiceA")
                put("to", "ServiceB")
            }
            val res = server.callTool("atlas_audit", args)

            assertEquals("CONFIRMED", res["status"]?.jsonPrimitive?.content)
            assertTrue(res["passed"]?.jsonPrimitive?.boolean == true)
            assertEquals("CONFIRMED (zero coupling paths)", res["message"]?.jsonPrimitive?.content)
        } finally {
            server.close()
        }
    }

    @Test
    fun `decoupling hypothesis falsified for self-reference when from and to identify the same component`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_self_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "self-test"
            val node = GraphNode(
                nodeId = "$projectId:src/SelfService.kt#SelfService",
                projectId = projectId,
                fileId = "$projectId:src/SelfService.kt",
                name = "SelfService",
                qualifiedName = "com.example.SelfService",
                kind = "class"
            )
            store.upsertNodesAndEdges(projectId, listOf(node), emptyList())

            val engine = SpikeAnalysisEngine(store)
            val result = engine.runSpike(projectId, hypothesis = "decoupling", from = "SelfService", to = "SelfService")

            assertEquals("FALSIFIED", result.status)
            assertFalse(result.passed)
            assertTrue(result.message.contains("self-reference"), "Expected self-reference in message: ${result.message}")
        } finally {
            store.close()
        }
    }

    @Test
    fun `decoupling hypothesis resolves package prefixes and module directories`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_pkg_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "pkg-test"
            val engineNode = GraphNode(
                nodeId = "$projectId:src/engine/MyEngine.kt#MyEngine",
                projectId = projectId,
                fileId = "$projectId:src/engine/MyEngine.kt",
                name = "MyEngine",
                qualifiedName = "com.example.engine.MyEngine",
                kind = "class"
            )
            val dbNode = GraphNode(
                nodeId = "$projectId:src/db/MyStore.kt#MyStore",
                projectId = projectId,
                fileId = "$projectId:src/db/MyStore.kt",
                name = "MyStore",
                qualifiedName = "com.example.db.MyStore",
                kind = "class"
            )
            store.upsertNodesAndEdges(
                projectId,
                listOf(engineNode, dbNode),
                listOf(
                    GraphEdge(edgeId = "e1", projectId = projectId, sourceNodeId = engineNode.nodeId, targetNodeId = dbNode.nodeId, edgeType = "CALLS", lineNumber = 15)
                )
            )

            val engine = SpikeAnalysisEngine(store)

            // Test package prefix
            val resPkg = engine.runSpike(projectId, hypothesis = "decoupling", from = "com.example.engine", to = "com.example.db")
            assertEquals("FALSIFIED", resPkg.status)
            assertFalse(resPkg.passed)

            // Test directory segment
            val resDir = engine.runSpike(projectId, hypothesis = "decoupling", from = "engine", to = "db")
            assertEquals("FALSIFIED", resDir.status)
            assertFalse(resDir.passed)
        } finally {
            store.close()
        }
    }

    @Test
    fun `CLI dispatch uses default 10 hops to detect 3-hop coupling path without explicit depth`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_depth_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "depth-test"
            val n1 = GraphNode(nodeId = "$projectId:src/N1.kt#N1", projectId = projectId, fileId = "$projectId:src/N1.kt", name = "N1", qualifiedName = "N1", kind = "class")
            val n2 = GraphNode(nodeId = "$projectId:src/N2.kt#N2", projectId = projectId, fileId = "$projectId:src/N2.kt", name = "N2", qualifiedName = "N2", kind = "class")
            val n3 = GraphNode(nodeId = "$projectId:src/N3.kt#N3", projectId = projectId, fileId = "$projectId:src/N3.kt", name = "N3", qualifiedName = "N3", kind = "class")
            val n4 = GraphNode(nodeId = "$projectId:src/N4.kt#N4", projectId = projectId, fileId = "$projectId:src/N4.kt", name = "N4", qualifiedName = "N4", kind = "class")

            // N1 -> N2 -> N3 -> N4 (3 hops)
            store.upsertNodesAndEdges(
                projectId,
                listOf(n1, n2, n3, n4),
                listOf(
                    GraphEdge(edgeId = "e1", projectId = projectId, sourceNodeId = n1.nodeId, targetNodeId = n2.nodeId, edgeType = "CALLS", lineNumber = 1),
                    GraphEdge(edgeId = "e2", projectId = projectId, sourceNodeId = n2.nodeId, targetNodeId = n3.nodeId, edgeType = "CALLS", lineNumber = 2),
                    GraphEdge(edgeId = "e3", projectId = projectId, sourceNodeId = n3.nodeId, targetNodeId = n4.nodeId, edgeType = "CALLS", lineNumber = 3)
                )
            )

            // Without --depth, default should be 10 hops -> finds path -> exit code 1 (FALSIFIED)
            val exitDefault = AtlasCli.run(arrayOf("spike", "--project", projectId, "--hypothesis", "decoupling", "--from", "N1", "--to", "N4", "--db", dbPath, "--workspace", tmpDir))
            assertEquals(1, exitDefault)

            // With explicit --depth 2, path is beyond 2 hops -> exits with code 0 (CONFIRMED)
            val exitDepth2 = AtlasCli.run(arrayOf("spike", "--project", projectId, "--hypothesis", "decoupling", "--from", "N1", "--to", "N4", "--depth", "2", "--db", dbPath, "--workspace", tmpDir))
            assertEquals(0, exitDepth2)
        } finally {
            store.close()
        }
    }

    @Test
    fun `CLI dispatch accepts positional endpoints for spike`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_pos_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "pos-test"
            val src = GraphNode(nodeId = "$projectId:src/Src.kt#Src", projectId = projectId, fileId = "$projectId:src/Src.kt", name = "Src", qualifiedName = "Src", kind = "class")
            val dst = GraphNode(nodeId = "$projectId:src/Dst.kt#Dst", projectId = projectId, fileId = "$projectId:src/Dst.kt", name = "Dst", qualifiedName = "Dst", kind = "class")
            store.upsertNodesAndEdges(
                projectId,
                listOf(src, dst),
                listOf(
                    GraphEdge(edgeId = "e1", projectId = projectId, sourceNodeId = src.nodeId, targetNodeId = dst.nodeId, edgeType = "CALLS", lineNumber = 5)
                )
            )

            // Positional endpoints: atlas spike Src Dst
            val exitPos = AtlasCli.run(arrayOf("spike", "Src", "Dst", "--project", projectId, "--hypothesis", "decoupling", "--db", dbPath, "--workspace", tmpDir))
            assertEquals(1, exitPos)
        } finally {
            store.close()
        }
    }

    @Test
    fun `decoupling hypothesis traverses cycles deterministically`() {
        val pid = getpid()
        val tmpDir = "/tmp/atlas_spike_cycle_test_$pid"
        PlatformFiles.mkdirs(tmpDir)
        val dbPath = "$tmpDir/atlas.db"

        val store = SqliteGraphStore(dbPath)
        try {
            val projectId = "cycle-test"
            val a = GraphNode(nodeId = "$projectId:src/A.kt#A", projectId = projectId, fileId = "$projectId:src/A.kt", name = "A", qualifiedName = "A", kind = "class")
            val b = GraphNode(nodeId = "$projectId:src/B.kt#B", projectId = projectId, fileId = "$projectId:src/B.kt", name = "B", qualifiedName = "B", kind = "class")
            val c = GraphNode(nodeId = "$projectId:src/C.kt#C", projectId = projectId, fileId = "$projectId:src/C.kt", name = "C", qualifiedName = "C", kind = "class")
            val target = GraphNode(nodeId = "$projectId:src/Target.kt#Target", projectId = projectId, fileId = "$projectId:src/Target.kt", name = "Target", qualifiedName = "Target", kind = "class")

            // Cycle: A -> B -> C -> A, and B -> Target
            store.upsertNodesAndEdges(
                projectId,
                listOf(a, b, c, target),
                listOf(
                    GraphEdge(edgeId = "e1", projectId = projectId, sourceNodeId = a.nodeId, targetNodeId = b.nodeId, edgeType = "CALLS", lineNumber = 10),
                    GraphEdge(edgeId = "e2", projectId = projectId, sourceNodeId = b.nodeId, targetNodeId = c.nodeId, edgeType = "CALLS", lineNumber = 20),
                    GraphEdge(edgeId = "e3", projectId = projectId, sourceNodeId = c.nodeId, targetNodeId = a.nodeId, edgeType = "CALLS", lineNumber = 30),
                    GraphEdge(edgeId = "e4", projectId = projectId, sourceNodeId = b.nodeId, targetNodeId = target.nodeId, edgeType = "CALLS", lineNumber = 40)
                )
            )

            val engine = SpikeAnalysisEngine(store)
            val result = engine.runSpike(projectId, hypothesis = "decoupling", from = "A", to = "Target")

            assertEquals("FALSIFIED", result.status)
            assertFalse(result.passed)
            assertEquals("A -> B -> Target", result.path[0])
            assertEquals("A -[CALLS:10]-> B -[CALLS:40]-> Target", result.path[1])
        } finally {
            store.close()
        }
    }
}
