package atlas

import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.testdoubles.FakeGraphStore
import atlas.util.PlatformFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.posix.getpid

class AtlasFacadeTest {

    @Test
    fun testAtlasInMemoryLifecycleAndQueries() {
        val client = Atlas.inMemory(projectSlug = "facade-test")

        val n1 = GraphNode(
            nodeId = "pkg.Service",
            projectId = "facade-test",
            fileId = "src/Service.kt",
            name = "Service",
            qualifiedName = "pkg.Service",
            kind = "class"
        )
        val n2 = GraphNode(
            nodeId = "pkg.Repository",
            projectId = "facade-test",
            fileId = "src/Repository.kt",
            name = "Repository",
            qualifiedName = "pkg.Repository",
            kind = "interface"
        )
        val edge = GraphEdge(
            edgeId = "pkg.Service->pkg.Repository:calls",
            projectId = "facade-test",
            sourceNodeId = "pkg.Service",
            targetNodeId = "pkg.Repository",
            edgeType = "calls"
        )

        client.store.upsertNodesAndEdges("facade-test", listOf(n1, n2), listOf(edge))

        val retrieved = client.getNode("Service")
        assertNotNull(retrieved)
        assertEquals("pkg.Service", retrieved.nodeId)

        val nonExistent = client.getNode("Unknown")
        assertNull(nonExistent)

        val callees = client.callees("Service", depth = 1)
        assertEquals(1, callees.size)
        assertEquals("pkg.Repository", callees.first().targetId)

        val callers = client.callers("Repository", depth = 1)
        assertEquals(1, callers.size)
        assertEquals("pkg.Service", callers.first().sourceId)

        val impact = client.impactRadius("Service", depth = 2)
        assertEquals("pkg.Service", impact.seedNodeId)
        assertTrue(impact.impactedNodes.any { it.nodeId == "pkg.Repository" })

        val path = client.shortestPath("Service", "Repository", maxHops = 3)
        assertEquals(listOf("pkg.Service", "pkg.Repository"), path)

        val searchResults = client.searchNodes("Repo", limit = 5)
        assertEquals(1, searchResults.size)
        assertEquals("Repository", searchResults.first().name)

        val stats = client.stats()
        assertEquals(2, stats.nodes)
        assertEquals(1, stats.edges)

        val spike = client.runSpike(hypothesis = "decoupling", from = "Service", to = "Repository")
        assertNotNull(spike)

        client.close()

        // Post-close calls should fail
        assertFailsWith<IllegalStateException> {
            client.stats()
        }
        assertFailsWith<IllegalStateException> {
            client.getNode("Service")
        }
    }

    @Test
    fun testAtlasEphemeralCleansUpDatabaseFiles() {
        val pid = getpid()
        val rnd = Random.nextInt(100000, 999999)
        val tempDir = "/tmp/atlas_test_facade_${pid}_${rnd}"
        PlatformFiles.mkdirs(tempDir)
        val customDb = "$tempDir/custom_eph.db"

        try {
            val client = Atlas.open(
                projectSlug = "ephemeral-test",
                workspacePath = tempDir,
                dbPath = customDb
            )

            val n = GraphNode(
                nodeId = "org.TestNode",
                projectId = "ephemeral-test",
                fileId = "TestNode.kt",
                name = "TestNode",
                qualifiedName = "org.TestNode",
                kind = "class"
            )
            client.store.upsertNodesAndEdges("ephemeral-test", listOf(n), emptyList())

            val found = client.getNode("TestNode")
            assertNotNull(found)
            assertTrue(PlatformFiles.exists(customDb))

            client.close()
        } finally {
            PlatformFiles.deleteRecursively(tempDir)
        }

        // Test standard Atlas.ephemeral() factory with automated cleanup
        val ephClient = Atlas.ephemeral(projectSlug = "auto-eph", workspacePath = ".")
        val store = ephClient.store as? atlas.db.SqliteGraphStore
        assertNotNull(store)
        val tempDb = store.dbPath
        assertTrue(PlatformFiles.exists(tempDb))

        ephClient.close()
        assertFalse(PlatformFiles.exists(tempDb))
    }

    @Test
    fun testAtlasFromStoreWithFakeGraphStore() {
        val fakeStore = FakeGraphStore()
        var cleanedUp = false
        val client = Atlas.fromStore(
            projectSlug = "fake-test",
            workspacePath = ".",
            store = fakeStore,
            cleanup = { cleanedUp = true }
        )

        val node = GraphNode(
            nodeId = "FakeNode",
            projectId = "fake-test",
            fileId = "Fake.kt",
            name = "FakeNode",
            qualifiedName = "FakeNode",
            kind = "function"
        )
        fakeStore.upsertNodesAndEdges("fake-test", listOf(node), emptyList())

        assertEquals("FakeNode", client.getNode("FakeNode")?.nodeId)
        assertEquals(1, client.stats().nodes)

        client.close()
        assertTrue(cleanedUp)
    }

    @Test
    fun testAtlasClientAuditArchitecture() {
        val client = Atlas.inMemory(projectSlug = "audit-test")
        val report = client.auditArchitecture()
        assertNotNull(report)
        assertEquals("audit-test", report.projectId)
        client.close()
    }
}
