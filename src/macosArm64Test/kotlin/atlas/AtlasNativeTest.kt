package atlas

import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectureAuditor
import atlas.engine.ScopeSynthesizer
import atlas.engine.WorkMemoryStore
import atlas.extract.ModuleBoundaryDetector
import atlas.extract.NameSegmentTokenizer
import atlas.extract.PolyglotAstExtractor
import atlas.extract.SemioticLayerClassifier
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import atlas.util.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AtlasNativeTest {

    @Test
    fun testSha256StandardVector() {
        val hash = Sha256.digest("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    @Test
    fun testNameSegmentTokenizer() {
        val segments = NameSegmentTokenizer.tokenize("ProductCardViewModel")
        assertEquals(listOf("product", "card", "view", "model"), segments)

        val snakeSegments = NameSegmentTokenizer.tokenize("render_audio_stream")
        assertEquals(listOf("render", "audio", "stream"), snakeSegments)
    }

    @Test
    fun testSemioticLayerClassifier() {
        val (semiotic, arch, _) = SemioticLayerClassifier.classify("shared/domain/model/Product.kt")
        assertEquals("syntactic", semiotic)
        assertEquals("domain", arch)

        val (uiSemiotic, uiArch, _) = SemioticLayerClassifier.classify("feature/cart/ui/CartScreen.kt")
        assertEquals("pragmatic", uiSemiotic)
        assertEquals("presentation", uiArch)
    }

    @Test
    fun testModuleBoundaryDetectorTypeSafeAccessor() {
        val known = listOf(":core:designsystem", ":feature:audio-studio")
        val mod = ModuleBoundaryDetector.convertTypesafeAccessorToModuleId("core.designsystem", known)
        assertEquals(":core:designsystem", mod)

        val camelMod = ModuleBoundaryDetector.convertTypesafeAccessorToModuleId("feature.audioStudio", known)
        assertEquals(":feature:audio-studio", camelMod)
    }

    @Test
    fun testPolyglotAstExtractorKotlin() {
        val ktCode = """
            package com.tesco.product
            
            import com.tesco.core.BaseCard
            
            class ProductCard : BaseCard() {
                fun render() {
                    displayTitle()
                }
            }
        """.trimIndent()

        val (nodes, edges, declCount) = PolyglotAstExtractor.extractFile("test_proj", "ProductCard.kt", ktCode)
        assertEquals(1, declCount)
        assertTrue(nodes.any { it.name == "ProductCard" })
        assertTrue(edges.any { it.edgeType == "IMPORTS" })
    }

    @Test
    fun testSqliteGraphStoreAndCTE() {
        val dbPath = "/tmp/test_atlas_unit_${platform.posix.getpid()}.db"
        PlatformFiles.delete(dbPath)
        val store = SqliteGraphStore(dbPath)
        try {
            val nodeA = GraphNode(
                nodeId = "p:A",
                projectId = "p",
                fileId = "p:A.kt",
                name = "A",
                qualifiedName = "pkg.A",
                kind = "class",
                signature = "class A",
                archLayer = "domain"
            )
            val nodeB = GraphNode(
                nodeId = "p:B",
                projectId = "p",
                fileId = "p:B.kt",
                name = "B",
                qualifiedName = "pkg.B",
                kind = "class",
                signature = "class B",
                archLayer = "presentation"
            )
            val edge = GraphEdge(
                edgeId = "e1",
                projectId = "p",
                sourceNodeId = "p:A",
                targetNodeId = "p:B",
                edgeType = "CALLS"
            )

            store.upsertNodesAndEdges("p", listOf(nodeA, nodeB), listOf(edge))

            val found = store.getNode("p", "A")
            assertNotNull(found)
            assertEquals("A", found.name)

            val callees = store.getCallees("p", "p:A", depth = 2)
            assertEquals(1, callees.size)
            assertEquals("p:B", callees[0].targetId)

            val shortest = store.getShortestPath("p", "p:A", "p:B")
            assertEquals(listOf("p:A", "p:B"), shortest)

            // Test architecture layer inversion detection
            val auditor = ArchitectureAuditor(store)
            val inversions = auditor.checkLayerInversions("p")
            assertEquals(1, inversions.size)
            assertEquals("ARCH-LAYER-INVERSION", inversions[0].rule)

            // Test 3D scope synthesis
            val synth = ScopeSynthesizer(store)
            val scope = synth.synthesizeScope("p", listOf("A"))
            assertTrue(scope.files.contains("A.kt"))
            assertTrue(scope.files.contains("B.kt"))

            // Test experiential work memory with time decay
            val memory = WorkMemoryStore(store)
            val annId = memory.recordOutcome("p", "p:A", "useful", "Verified A calls B directly", createdAtEpoch = 1000.0)
            assertNotNull(annId)

            // 0 days age: weight should be 1.0
            val active = memory.getActiveAnnotations("p", "p:A", nowEpoch = 1000.0)
            assertEquals(1, active.size)
            assertEquals(1.0, active[0].decayWeight)

            // 30 days age (1 half-life): weight should be 0.5
            val active30d = memory.getActiveAnnotations("p", "p:A", nowEpoch = 1000.0 + 30 * 86400.0)
            assertEquals(1, active30d.size)
            assertEquals(0.5, active30d[0].decayWeight)

            // Shortest path with branching and cycle handling
            val nodeC = GraphNode("p:C", "p", "p:C.kt", "C", "pkg.C", "class")
            val nodeD = GraphNode("p:D", "p", "p:D.kt", "D", "pkg.D", "class")
            val edgeAC = GraphEdge("e2", "p", "p:A", "p:C", "CALLS")
            val edgeCD = GraphEdge("e3", "p", "p:C", "p:D", "CALLS")
            val edgeDC = GraphEdge("e4", "p", "p:D", "p:C", "CALLS") // cycle
            val edgeDB = GraphEdge("e5", "p", "p:D", "p:B", "CALLS")
            store.upsertNodesAndEdges("p", listOf(nodeC, nodeD), listOf(edgeAC, edgeCD, edgeDC, edgeDB))

            val shortestMulti = store.getShortestPath("p", "p:C", "p:B")
            assertEquals(listOf("p:C", "p:D", "p:B"), shortestMulti)

            val disconnected = store.getShortestPath("p", "p:B", "p:A")
            assertEquals(emptyList(), disconnected)
        } finally {
            store.close()
            PlatformFiles.delete(dbPath)
        }
    }
}
