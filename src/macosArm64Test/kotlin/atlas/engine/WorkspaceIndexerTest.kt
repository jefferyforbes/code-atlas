package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.util.PlatformFiles
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getpid
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceIndexerTest {

    private fun makeTempDir(): String {
        val path = "/tmp/atlas_workspace_indexer_test_${getpid()}_${Random.nextInt(100000, 999999)}"
        PlatformFiles.mkdirs(path)
        return path
    }

    private fun cleanDir(dir: String) {
        for (f in PlatformFiles.listFilesRecursively(dir)) {
            PlatformFiles.delete(f)
        }
        rmdir(dir)
    }

    @Test
    fun `test scoped incremental edge resolution preserves unaffected cross-file code edges`() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)

        try {
            val srcDir = "$tmpDir/src/main/kotlin/example"
            PlatformFiles.mkdirs(srcDir)

            val serviceA = "$srcDir/ServiceA.kt"
            PlatformFiles.writeText(
                serviceA,
                """
                package example

                class ServiceA {
                    fun run() {
                        ServiceB.action()
                    }
                }
                """.trimIndent()
            )

            val serviceB = "$srcDir/ServiceB.kt"
            PlatformFiles.writeText(
                serviceB,
                """
                package example

                object ServiceB {
                    fun action() {}
                }
                """.trimIndent()
            )

            val serviceC = "$srcDir/ServiceC.kt"
            PlatformFiles.writeText(
                serviceC,
                """
                package example

                class ServiceC {
                    fun helper() {}
                }
                """.trimIndent()
            )

            val indexer = WorkspaceIndexer(tmpDir, store)
            val res1 = indexer.indexWorkspace("proj")
            assertEquals(3, res1["files_indexed"]?.jsonPrimitive?.intOrNull)

            // Verify edge ServiceA -> ServiceB exists in edges table
            val edges1 = store.listEdges("proj", listOf("CALLS"))
            assertTrue(edges1.any { it.sourceNodeId.contains("ServiceA") && it.targetNodeId.contains("ServiceB") })
            val edgeCountBefore = edges1.size

            // Now modify ONLY ServiceC.kt
            PlatformFiles.writeText(
                serviceC,
                """
                package example

                class ServiceC {
                    fun helper() {}
                    fun additionalHelper() {}
                }
                """.trimIndent()
            )

            // Run incremental indexing (force = false)
            val res2 = indexer.indexWorkspace("proj")
            assertEquals(1, res2["files_indexed"]?.jsonPrimitive?.intOrNull)
            assertEquals(2, res2["files_skipped"]?.jsonPrimitive?.intOrNull)

            // Scoped edge resolution check: The cross-file edge between ServiceA and ServiceB
            // MUST remain intact in SQLite without having been wiped and rebuilt!
            val edges2 = store.listEdges("proj", listOf("CALLS"))
            assertTrue(
                edges2.any { it.sourceNodeId.contains("ServiceA") && it.targetNodeId.contains("ServiceB") },
                "Unaffected cross-file code edge ServiceA -> ServiceB must remain intact during incremental reindexing"
            )
            assertEquals(edgeCountBefore, edges2.size)

            // ServiceC new method should be present in nodes
            val nodeAdditional = store.getNode("proj", "additionalHelper")
            assertNotNull(nodeAdditional, "Newly added method in ServiceC must be indexed")
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun `test incremental index resolves cross-file edge when callee is introduced in untouched caller`() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)

        try {
            val srcDir = "$tmpDir/src/main/kotlin/example"
            PlatformFiles.mkdirs(srcDir)

            val callerFile = "$srcDir/Caller.kt"
            PlatformFiles.writeText(
                callerFile,
                """
                package example

                class Caller {
                    fun execute() {
                        newlyIntroducedCallee()
                    }
                }
                """.trimIndent()
            )

            val indexer = WorkspaceIndexer(tmpDir, store)
            val res1 = indexer.indexWorkspace("proj")
            assertEquals(1, res1["files_indexed"]?.jsonPrimitive?.intOrNull)

            // Callee does not exist yet; no CALLS edge
            val callersBefore = store.getCallers("proj", "example.Callee#newlyIntroducedCallee", depth = 1)
            assertTrue(callersBefore.isEmpty())

            // Add Callee.kt declaring newlyIntroducedCallee
            val calleeFile = "$srcDir/Callee.kt"
            PlatformFiles.writeText(
                calleeFile,
                """
                package example

                fun newlyIntroducedCallee() {}
                """.trimIndent()
            )

            // Incremental index: Caller.kt is unchanged, Callee.kt is indexed
            val res2 = indexer.indexWorkspace("proj")
            assertEquals(1, res2["files_indexed"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, res2["files_skipped"]?.jsonPrimitive?.intOrNull)

            val calleeNode = store.getNode("proj", "newlyIntroducedCallee")
            assertNotNull(calleeNode, "newlyIntroducedCallee should be indexed")

            // Unchanged Caller must now have resolved CALLS edge to newlyIntroducedCallee
            val callersAfter = store.getCallers("proj", calleeNode.nodeId, depth = 1)
            assertTrue(
                callersAfter.any { it.name == "execute" },
                "Unchanged Caller#execute must have CALLS edge to newlyIntroducedCallee via scoped edge resolution"
            )
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun `test circular call chain when file A is deleted and file C is modified in same incremental batch`() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)

        try {
            val srcDir = "$tmpDir/src/main/kotlin/example"
            PlatformFiles.mkdirs(srcDir)

            val fileA = "$srcDir/ServiceA.kt"
            PlatformFiles.writeText(
                fileA,
                """
                package example

                class ServiceA {
                    fun actionA() {}
                    fun callB() {
                        ServiceB.actionB()
                    }
                }
                """.trimIndent()
            )

            val fileB = "$srcDir/ServiceB.kt"
            PlatformFiles.writeText(
                fileB,
                """
                package example

                object ServiceB {
                    fun actionB() {}
                    fun callA() {
                        ServiceA.actionA()
                    }
                }
                """.trimIndent()
            )

            val fileC = "$srcDir/ServiceC.kt"
            PlatformFiles.writeText(
                fileC,
                """
                package example

                class ServiceC {
                    fun task() {}
                }
                """.trimIndent()
            )

            val indexer = WorkspaceIndexer(tmpDir, store)
            val res1 = indexer.indexWorkspace("proj")
            assertEquals(3, res1["files_indexed"]?.jsonPrimitive?.intOrNull)

            val edgesBefore = store.listEdges("proj", listOf("CALLS"))
            assertTrue(edgesBefore.any { it.sourceNodeId.contains("ServiceA") && it.targetNodeId.contains("ServiceB") })
            assertTrue(edgesBefore.any { it.sourceNodeId.contains("ServiceB") && it.targetNodeId.contains("ServiceA") })

            // Now delete ServiceA.kt and modify ServiceC.kt in the same batch
            PlatformFiles.delete(fileA)
            PlatformFiles.writeText(
                fileC,
                """
                package example

                class ServiceC {
                    fun task() {}
                    fun extraTask() {}
                }
                """.trimIndent()
            )

            val res2 = indexer.indexWorkspace("proj")
            assertEquals(1, res2["files_indexed"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, res2["files_skipped"]?.jsonPrimitive?.intOrNull)

            // Both edges of the circular dependency involving deleted ServiceA must be gone
            val edgesAfter = store.listEdges("proj", listOf("CALLS"))
            assertTrue(
                edgesAfter.none { it.sourceNodeId.contains("ServiceA") || it.targetNodeId.contains("ServiceA") },
                "Edges involving deleted ServiceA must be completely purged from edges table"
            )

            // ServiceC new method should be present in nodes
            assertNotNull(store.getNode("proj", "extraTask"))
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }
}
