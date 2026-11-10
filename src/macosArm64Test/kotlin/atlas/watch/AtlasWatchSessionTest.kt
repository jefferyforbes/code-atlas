package atlas.watch

import atlas.Atlas
import atlas.util.PlatformFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.posix.getpid

class AtlasWatchSessionTest {

    @Test
    fun testWatchSessionDetectsChangesAndEmitsDeltas() {
        val pid = getpid()
        val rnd = Random.nextInt(100000, 999999)
        val tempWs = "/tmp/atlas_test_watch_${pid}_${rnd}"
        PlatformFiles.mkdirs(tempWs)
        val fileA = "$tempWs/ServiceA.kt"

        try {
            PlatformFiles.writeText(
                fileA,
                """
                package test
                class ServiceA {
                    fun doWork() = 42
                }
                """.trimIndent()
            )

            val client = Atlas.inMemory(projectSlug = "watch-test", workspacePath = tempWs)
            client.index()

            val session = AtlasWatchSession(client)

            // When no files have changed, pollOnce returns null
            val noDelta = session.pollOnce()
            assertNull(noDelta)

            // Add a new file ServiceB.kt
            val fileB = "$tempWs/ServiceB.kt"
            PlatformFiles.writeText(
                fileB,
                """
                package test
                class ServiceB {
                    fun execute() = true
                }
                """.trimIndent()
            )

            var notifiedDelta: AtlasWatchSession.GraphDelta? = null
            val unregister = session.registerListener { delta ->
                notifiedDelta = delta
            }

            // Trigger check: must detect ServiceB.kt
            val delta = session.triggerCheck()
            assertNotNull(delta)
            assertTrue(delta.hasChanges)
            assertTrue(delta.changedFiles.any { it.contains("ServiceB.kt") })
            assertTrue(delta.addedNodes.any { it.name == "ServiceB" })
            assertEquals(delta, notifiedDelta)

            // Unregister listener
            unregister()
            notifiedDelta = null

            // Delete ServiceA.kt
            PlatformFiles.delete(fileA)

            val deleteDelta = session.triggerCheck()
            assertNotNull(deleteDelta)
            assertTrue(deleteDelta.changedFiles.any { it.contains("ServiceA.kt") })
            assertTrue(deleteDelta.removedNodeIds.any { it.contains("ServiceA") })
            // Listener was unregistered, so notifiedDelta should remain null
            assertNull(notifiedDelta)

            client.close()
        } finally {
            PlatformFiles.deleteRecursively(tempWs)
        }
    }
}
