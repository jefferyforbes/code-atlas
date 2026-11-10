package atlas.export

import atlas.model.AuditReport
import atlas.model.ComplexityViolation
import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.model.LayerInversionViolation
import atlas.model.PlanCollision
import atlas.model.SingleDeclarationViolation
import atlas.model.SkillAuditViolation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SarifAndVisualExporterTest {

    @Test
    fun testSarifExporterWithAllViolationCategories() {
        val report = AuditReport(
            projectId = "sarif-test",
            passed = false,
            singleDeclarationViolations = listOf(
                SingleDeclarationViolation(
                    rule = "BOOST-003",
                    relPath = "src/MultiClass.kt",
                    topLevelDeclCount = 2,
                    message = "MultiClass.kt contains 2 top-level declarations"
                )
            ),
            layerInversions = listOf(
                LayerInversionViolation(
                    rule = "ARCH-LAYER-INVERSION",
                    source = "domain:DomainService",
                    target = "data:DataRepo",
                    edgeType = "calls",
                    lineNumber = 42,
                    message = "Domain layer depends directly on Data layer"
                )
            ),
            plan3dCollisions = listOf(
                PlanCollision(
                    planA = "plans/task-a.md",
                    planB = "plans/task-b.md",
                    sharedFiles = listOf("src/Shared.kt"),
                    sharedModules = listOf("core")
                )
            ),
            complexityViolations = listOf(
                ComplexityViolation(
                    ruleId = "COMPLEXITY-001",
                    severity = "WARNING",
                    message = "process() cognitive complexity is 18 (limit 15)",
                    nodeId = "src/Heavy.kt:process",
                    lineSpan = "50-120",
                    cognitiveComplexity = 18
                )
            ),
            parameterAdvisories = listOf(
                ComplexityViolation(
                    ruleId = "PARAM-001",
                    severity = "NOTE",
                    message = "configure() has 6 parameters (limit 4)",
                    nodeId = "src/Config.kt:configure",
                    lineSpan = "10-25",
                    paramCount = 6
                )
            ),
            skillViolations = listOf(
                SkillAuditViolation(
                    rule = "SKILL-001",
                    skillName = "test-skill",
                    relPath = "skills/test/SKILL.md",
                    lineNumber = 5,
                    message = "Missing description frontmatter"
                )
            )
        )

        val jsonString = SarifExporter.toSarifJson(report)
        assertNotNull(jsonString)

        val parsed = Json.parseToJsonElement(jsonString).jsonObject
        assertEquals("2.1.0", parsed["version"]?.jsonPrimitive?.content)
        assertTrue(parsed["\$schema"]?.jsonPrimitive?.content?.contains("sarif-2.1.0.json") == true)

        val runs = parsed["runs"]?.jsonArray
        assertNotNull(runs)
        assertEquals(1, runs.size)

        val run = runs.first().jsonObject
        val driver = run["tool"]?.jsonObject?.get("driver")?.jsonObject
        assertEquals("Code Atlas", driver?.get("name")?.jsonPrimitive?.content)

        val rules = driver?.get("rules")?.jsonArray
        assertNotNull(rules)
        assertTrue(rules.size >= 6)

        val results = run["results"]?.jsonArray
        assertNotNull(results)
        // 1 single-decl + 1 layer-inv + 1 complexity + 1 param + 1 plan + 1 skill = 6 results
        assertEquals(6, results.size)

        val ruleIds = results.map { it.jsonObject["ruleId"]?.jsonPrimitive?.content }
        assertTrue(ruleIds.contains("BOOST-003"))
        assertTrue(ruleIds.contains("ARCH-LAYER-INVERSION"))
        assertTrue(ruleIds.contains("COMPLEXITY-001"))
        assertTrue(ruleIds.contains("PARAM-001"))
        assertTrue(ruleIds.contains("TEAMWORK-3D-COLLISION"))
        assertTrue(ruleIds.contains("SKILL-001"))
    }

    @Test
    fun testMermaidFlowchartExporter() {
        val nodes = listOf(
            GraphNode(
                nodeId = "pkg.DomainService",
                projectId = "p",
                fileId = "src/Service.kt",
                name = "DomainService",
                qualifiedName = "pkg.DomainService",
                kind = "class",
                moduleId = "core",
                archLayer = "domain"
            ),
            GraphNode(
                nodeId = "pkg.DataRepo",
                projectId = "p",
                fileId = "src/Repo.kt",
                name = "DataRepo",
                qualifiedName = "pkg.DataRepo",
                kind = "interface",
                moduleId = "data",
                archLayer = "data"
            )
        )
        val edges = listOf(
            GraphEdge(
                edgeId = "e1",
                projectId = "p",
                sourceNodeId = "pkg.DomainService",
                targetNodeId = "pkg.DataRepo",
                edgeType = "calls"
            )
        )

        val mermaidDefault = GraphVisualExporter.toMermaidFlowchart(nodes, edges, direction = "TD", clusterBy = "module")
        assertTrue(mermaidDefault.startsWith("flowchart TD"))
        assertTrue(mermaidDefault.contains("subgraph core"))
        assertTrue(mermaidDefault.contains("subgraph data"))
        assertTrue(mermaidDefault.contains("-->|calls|"))

        val mermaidLayer = GraphVisualExporter.toMermaidFlowchart(nodes, edges, direction = "LR", clusterBy = "layer")
        assertTrue(mermaidLayer.startsWith("flowchart LR"))
        assertTrue(mermaidLayer.contains("subgraph domain"))
        assertTrue(mermaidLayer.contains("subgraph data"))

        val mermaidFlat = GraphVisualExporter.toMermaidFlowchart(nodes, edges, clusterBy = null)
        assertTrue(!mermaidFlat.contains("subgraph"))
        assertTrue(mermaidFlat.contains("-->|calls|"))
    }

    @Test
    fun testGraphvizDotExporter() {
        val nodes = listOf(
            GraphNode(
                nodeId = "pkg.A",
                projectId = "p",
                fileId = "A.kt",
                name = "A",
                qualifiedName = "pkg.A",
                kind = "class"
            ),
            GraphNode(
                nodeId = "pkg.B",
                projectId = "p",
                fileId = "B.kt",
                name = "B",
                qualifiedName = "pkg.B",
                kind = "class"
            )
        )
        val edges = listOf(
            GraphEdge(
                edgeId = "e1",
                projectId = "p",
                sourceNodeId = "pkg.A",
                targetNodeId = "pkg.B",
                edgeType = "depends_on"
            )
        )

        val dot = GraphVisualExporter.toGraphvizDot(nodes, edges, rankdir = "TB")
        assertTrue(dot.contains("digraph CodeAtlas {"))
        assertTrue(dot.contains("rankdir=TB;"))
        assertTrue(dot.contains("\"pkg.A\" [label=\"A\\n(class, domain)\"];"))
        assertTrue(dot.contains("\"pkg.A\" -> \"pkg.B\" [label=\"depends_on\"];"))
    }

    @Test
    fun testCytoscapeJsonExporter() {
        val nodes = listOf(
            GraphNode(
                nodeId = "node1",
                projectId = "p",
                fileId = "N1.kt",
                name = "Node1",
                qualifiedName = "pkg.Node1",
                kind = "interface",
                moduleId = "api",
                archLayer = "domain"
            )
        )
        val edges = listOf(
            GraphEdge(
                edgeId = "e1",
                projectId = "p",
                sourceNodeId = "node1",
                targetNodeId = "node2",
                edgeType = "implements"
            )
        )

        val jsonString = GraphVisualExporter.toCytoscapeJson(nodes, edges)
        val parsed = Json.parseToJsonElement(jsonString).jsonObject
        val elements = parsed["elements"]?.jsonObject
        assertNotNull(elements)

        val jsonNodes = elements["nodes"]?.jsonArray
        assertNotNull(jsonNodes)
        assertEquals(1, jsonNodes.size)
        val nData = jsonNodes.first().jsonObject["data"]?.jsonObject
        assertEquals("node1", nData?.get("id")?.jsonPrimitive?.content)
        assertEquals("Node1", nData?.get("label")?.jsonPrimitive?.content)
        assertEquals("api", nData?.get("module")?.jsonPrimitive?.content)

        val jsonEdges = elements["edges"]?.jsonArray
        assertNotNull(jsonEdges)
        assertEquals(1, jsonEdges.size)
        val eData = jsonEdges.first().jsonObject["data"]?.jsonObject
        assertEquals("e1", eData?.get("id")?.jsonPrimitive?.content)
        assertEquals("node1", eData?.get("source")?.jsonPrimitive?.content)
        assertEquals("node2", eData?.get("target")?.jsonPrimitive?.content)
    }
}
