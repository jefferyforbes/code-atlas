package atlas.engine

import atlas.db.SqliteGraphStore
import atlas.util.PlatformFiles
import platform.posix.getpid
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceIndexerIsolationTest {

    private fun makeTempDir(): String {
        val path = "/tmp/atlas_indexer_isolation_test_${getpid()}_${Random.nextInt(100000, 999999)}"
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
    fun testSyntheticVariableIsolationAndStdlibFirstMethodNotHijacked() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)

        try {
            // 1. Non-Compose Kotlin file containing equality check cached.first == sha256
            val nonComposeDir = "$tmpDir/src/main/kotlin/atlas/engine"
            PlatformFiles.mkdirs(nonComposeDir)

            val nonComposeFile = "$nonComposeDir/WorkspaceIndexer.kt"
            PlatformFiles.writeText(
                nonComposeFile,
                """
                package atlas.engine

                class WorkspaceIndexer {
                    fun checkCache(cached: Pair<String, Double>?, sha256: String): Boolean {
                        val isUnchanged = (cached != null && cached.first == sha256)
                        return isUnchanged
                    }
                }
                """.trimIndent()
            )

            // 2. Caller Kotlin file invoking stdlib .first()
            val callerDir = "$tmpDir/src/main/kotlin/atlas/caller"
            PlatformFiles.mkdirs(callerDir)
            val callerFile = "$callerDir/DataConsumer.kt"
            PlatformFiles.writeText(
                callerFile,
                """
                package atlas.caller

                class DataConsumer {
                    fun processItems(items: List<String>): String {
                        return items.first()
                    }
                }
                """.trimIndent()
            )

            // 3. Compose Kotlin file with actual @Composable
            val composeDir = "$tmpDir/src/main/kotlin/atlas/ui"
            PlatformFiles.mkdirs(composeDir)
            val composeFile = "$composeDir/ItemCard.kt"
            PlatformFiles.writeText(
                composeFile,
                """
                package atlas.ui

                import androidx.compose.runtime.Composable

                @Composable
                fun ItemCard(title: String) {
                    // Compose card
                }
                """.trimIndent()
            )

            val indexer = WorkspaceIndexer(tmpDir, store)
            val indexRes = indexer.indexWorkspace("test_proj")

            val nodes = store.listNodes("test_proj")
            val edges = store.listEdges("test_proj")

            // Verify: NO synthetic variable node named "first" exists anywhere in the project
            val firstVarNodes = nodes.filter { it.name == "first" && it.kind == "variable" }
            assertTrue(
                firstVarNodes.isEmpty(),
                "Synthetic variable 'first' must NOT be hallucinated from non-mutating equality expressions or non-Compose files. Found: ${firstVarNodes.map { it.nodeId }}"
            )

            // Verify: Caller's reference to .first() is NOT resolved to any WorkspaceIndexer.kt#first synthetic node
            val hijackedEdges = edges.filter { it.targetNodeId.contains("WorkspaceIndexer.kt#first") }
            assertTrue(
                hijackedEdges.isEmpty(),
                "Calls to .first() must NOT be hijacked by a synthetic WorkspaceIndexer.kt#first variable node"
            )

            // Verify: ItemCard is properly indexed as a composable
            val composeNodes = store.listComposeNodes("test_proj")
            assertEquals(1, composeNodes.size)
            assertEquals("atlas.ui.ItemCard", composeNodes.first().fqName)
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }

    @Test
    fun testNonComposeCommentsAndStringsDoNotTriggerComposeClassification() {
        val tmpDir = makeTempDir()
        val dbPath = "$tmpDir/test.db"
        val store = SqliteGraphStore(dbPath)

        try {
            val serviceDir = "$tmpDir/src/main/kotlin/atlas/service"
            PlatformFiles.mkdirs(serviceDir)

            val serviceFile = "$serviceDir/ApiService.kt"
            PlatformFiles.writeText(
                serviceFile,
                """
                package atlas.service

                // Note: consider androidx.compose in future UI layer
                class ApiService {
                    val composeDocs = "https://developer.android.com/jetpack/compose"
                    val library = "org.jetbrains.compose"

                    fun handleResponse(status: StatusModel) {
                        status.code = 200
                    }
                }
                """.trimIndent()
            )

            val indexer = WorkspaceIndexer(tmpDir, store)
            indexer.indexWorkspace("test_proj")

            val composeNodes = store.listComposeNodes("test_proj")
            assertTrue(composeNodes.isEmpty(), "Non-Compose file with Compose mentions in comments/strings must NOT have compose nodes")

            val rawEdges = store.listRawSymbolEdges("test_proj")
            val composeEdges = rawEdges.filter { it.edgeType in setOf("MUTATES_PROPERTY", "PROVIDES_COMPOSITION_LOCAL", "CONSUMES_COMPOSITION_LOCAL", "OBSERVES_STATE") }
            assertTrue(composeEdges.isEmpty(), "Non-Compose file must NOT emit synthetic Compose edges into raw_symbol_edges")
        } finally {
            store.close()
            cleanDir(tmpDir)
        }
    }
}

