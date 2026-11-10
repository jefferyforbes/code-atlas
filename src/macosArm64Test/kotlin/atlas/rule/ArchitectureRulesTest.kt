package atlas.rule

import atlas.model.GraphEdge
import atlas.model.GraphNode
import atlas.testdoubles.FakeGraphStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureRulesTest {

    @Test
    fun testForbiddenLayerDependencyFailsEvaluation() {
        val store = FakeGraphStore()
        val project = "rules-test"

        val domainNode = GraphNode(
            nodeId = "domain.OrderService",
            projectId = project,
            fileId = "src/domain/OrderService.kt",
            name = "OrderService",
            qualifiedName = "domain.OrderService",
            kind = "class"
        )
        val dataNode = GraphNode(
            nodeId = "data.OrderSqlRepository",
            projectId = project,
            fileId = "src/data/OrderSqlRepository.kt",
            name = "OrderSqlRepository",
            qualifiedName = "data.OrderSqlRepository",
            kind = "class"
        )
        val edge = GraphEdge(
            edgeId = "order-call",
            projectId = project,
            sourceNodeId = "domain.OrderService",
            targetNodeId = "data.OrderSqlRepository",
            edgeType = "calls",
            lineNumber = 25
        )

        store.upsertNodesAndEdges(project, listOf(domainNode, dataNode), listOf(edge))

        val rules = ArchitectureRules.define {
            layer("domain", "/domain/")
            layer("data", "/data/")

            "domain" shouldNotDependOn "data"
        }

        val report = rules.evaluate(store, project)
        assertFalse(report.passed)
        assertEquals(1, report.violations.size)

        val v = report.violations.first()
        assertEquals("ARCH-FORBIDDEN-DEPENDENCY", v.ruleId)
        assertEquals(ArchitectureRules.Severity.Error, v.severity)
        assertTrue(v.message.contains("OrderService"))
        assertTrue(v.message.contains("OrderSqlRepository"))
        assertEquals("src/domain/OrderService.kt", v.filePath)
        assertEquals(25, v.lineNumber)
    }

    @Test
    fun testAllowedLayerWhitelistEnforcement() {
        val store = FakeGraphStore()
        val project = "whitelist-test"

        val uiNode = GraphNode(
            nodeId = "ui.OrderScreen",
            projectId = project,
            fileId = "src/ui/OrderScreen.kt",
            name = "OrderScreen",
            qualifiedName = "ui.OrderScreen",
            kind = "class"
        )
        val dataNode = GraphNode(
            nodeId = "data.OrderDao",
            projectId = project,
            fileId = "src/data/OrderDao.kt",
            name = "OrderDao",
            qualifiedName = "data.OrderDao",
            kind = "class"
        )
        val edge = GraphEdge(
            edgeId = "ui-to-dao",
            projectId = project,
            sourceNodeId = "ui.OrderScreen",
            targetNodeId = "data.OrderDao",
            edgeType = "calls"
        )

        store.upsertNodesAndEdges(project, listOf(uiNode, dataNode), listOf(edge))

        val rules = ArchitectureRules.define {
            layer("presentation", "/ui/")
            layer("domain", "/domain/")
            layer("data", "/data/")

            "presentation" mayOnlyDependOn setOf("presentation", "domain")
        }

        val report = rules.evaluate(store, project)
        assertFalse(report.passed)
        assertEquals(1, report.violations.size)
        assertEquals("ARCH-DISALLOWED-DEPENDENCY", report.violations.first().ruleId)
        assertTrue(report.violations.first().message.contains("data"))
    }

    @Test
    fun testCustomNodeAndEdgeRules() {
        val store = FakeGraphStore()
        val project = "custom-rules-test"

        val godClass = GraphNode(
            nodeId = "core.GodService",
            projectId = project,
            fileId = "src/core/GodService.kt",
            name = "GodService",
            qualifiedName = "core.GodService",
            kind = "class",
            outDegree = 25
        )
        val normalClass = GraphNode(
            nodeId = "core.Helper",
            projectId = project,
            fileId = "src/core/Helper.kt",
            name = "Helper",
            qualifiedName = "core.Helper",
            kind = "class",
            outDegree = 3
        )

        store.upsertNodesAndEdges(project, listOf(godClass, normalClass), emptyList())

        val rules = ArchitectureRules.define {
            nodeRule(
                name = "FANOUT-CEILING",
                severity = ArchitectureRules.Severity.Error,
                message = { "Class '${it.name}' exceeds fanout ceiling with outDegree=${it.outDegree}" }
            ) { node ->
                node.outDegree <= 20
            }

            nodeRule(
                name = "WARN-SINGLE-LETTER",
                severity = ArchitectureRules.Severity.Warning
            ) { node ->
                node.name.length > 1
            }
        }

        val report = rules.evaluate(store, project)
        assertFalse(report.passed)
        assertEquals(1, report.violations.size)
        assertEquals("FANOUT-CEILING", report.violations.first().ruleId)
        assertTrue(report.violations.first().message.contains("outDegree=25"))
    }

    @Test
    fun testCleanArchitecturePassesWhenNoInversionsExist() {
        val store = FakeGraphStore()
        val project = "clean-test"

        val ui = GraphNode(nodeId = "ui.View", projectId = project, fileId = "src/ui/View.kt", name = "View", qualifiedName = "ui.View", kind = "class")
        val domain = GraphNode(nodeId = "domain.Logic", projectId = project, fileId = "src/domain/Logic.kt", name = "Logic", qualifiedName = "domain.Logic", kind = "class")
        val data = GraphNode(nodeId = "data.Repo", projectId = project, fileId = "src/data/Repo.kt", name = "Repo", qualifiedName = "data.Repo", kind = "class")

        // UI -> Domain, Data -> Domain (Dependency Inversion!)
        val e1 = GraphEdge(edgeId = "ui->domain", projectId = project, sourceNodeId = "ui.View", targetNodeId = "domain.Logic", edgeType = "calls")
        val e2 = GraphEdge(edgeId = "data->domain", projectId = project, sourceNodeId = "data.Repo", targetNodeId = "domain.Logic", edgeType = "implements")

        store.upsertNodesAndEdges(project, listOf(ui, domain, data), listOf(e1, e2))

        val rules = ArchitectureRules.define {
            layer("ui", "/ui/")
            layer("domain", "/domain/")
            layer("data", "/data/")

            "domain" shouldNotDependOn "ui"
            "domain" shouldNotDependOn "data"
        }

        val report = rules.evaluate(store, project)
        assertTrue(report.passed)
        assertEquals(0, report.violations.size)
        assertEquals(2, report.rulesChecked)
    }
}
