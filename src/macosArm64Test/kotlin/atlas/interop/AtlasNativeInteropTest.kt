package atlas.interop

import atlas.model.GraphNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtlasNativeInteropTest {

    @Test
    fun testVersionMatchesExpected() {
        assertEquals("0.9.0", AtlasNativeInterop.version())
    }

    @Test
    fun testNativeInteropClientLifecycleAndJsonBridge() {
        val client = AtlasNativeInterop.openInMemory(projectSlug = "interop-test")
        assertNotNull(client)

        val node = GraphNode(
            nodeId = "swift.Bridge",
            projectId = "interop-test",
            fileId = "Bridge.swift",
            name = "Bridge",
            qualifiedName = "swift.Bridge",
            kind = "struct",
            moduleId = "native",
            archLayer = "infra"
        )
        client.store.upsertNodesAndEdges("interop-test", listOf(node), emptyList())

        val nodeJson = AtlasNativeInterop.queryNodeJson(client, "Bridge")
        assertNotNull(nodeJson)

        val parsedNode = Json.parseToJsonElement(nodeJson).jsonObject
        assertEquals("swift.Bridge", parsedNode["node_id"]?.jsonPrimitive?.content)
        assertEquals("Bridge", parsedNode["name"]?.jsonPrimitive?.content)
        assertEquals("struct", parsedNode["kind"]?.jsonPrimitive?.content)

        val missingJson = AtlasNativeInterop.queryNodeJson(client, "NonExistent")
        assertNull(missingJson)

        val statsJson = AtlasNativeInterop.statsJson(client)
        val parsedStats = Json.parseToJsonElement(statsJson).jsonObject
        assertEquals("interop-test", parsedStats["project_id"]?.jsonPrimitive?.content)
        assertEquals("1", parsedStats["nodes"]?.jsonPrimitive?.content)

        AtlasNativeInterop.closeClient(client)

        assertFailsWith<IllegalStateException> {
            client.stats()
        }
    }

    @Test
    fun testEphemeralClientLifecycle() {
        val client = AtlasNativeInterop.openEphemeral(projectSlug = "eph-interop")
        assertNotNull(client)
        assertEquals(0, client.stats().nodes)
        AtlasNativeInterop.closeClient(client)
    }
}
