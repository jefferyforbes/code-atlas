package atlas.export

import atlas.model.GraphEdge
import atlas.model.GraphNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Exporter converting Code Atlas graph nodes and edges into visual graph representations.
 * Supports Mermaid flowcharts, Graphviz DOT graphs, and Cytoscape.js JSON.
 */
object GraphVisualExporter {

    private val jsonPretty = Json { prettyPrint = true }

    /**
     * Render the given nodes and edges as a Mermaid flowchart diagram.
     * [direction] can be "TD" (top-down) or "LR" (left-to-right).
     * [clusterBy] can be "module", "layer", or null for no grouping.
     */
    fun toMermaidFlowchart(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        direction: String = "TD",
        clusterBy: String? = "module"
    ): String {
        val sb = StringBuilder()
        sb.append("flowchart ").append(direction).append("\n")

        val nodeIdMap = mutableMapOf<String, String>()
        for ((idx, node) in nodes.withIndex()) {
            val safeId = "n" + idx + "_" + sanitizeIdentifier(node.name.ifBlank { node.nodeId })
            nodeIdMap[node.nodeId] = safeId
        }

        if (clusterBy != null && nodes.isNotEmpty()) {
            val groups = when (clusterBy) {
                "layer" -> nodes.groupBy { it.archLayer.ifBlank { "unassigned" } }
                else -> nodes.groupBy { it.moduleId.ifBlank { "root" } }
            }

            for ((groupName, groupNodes) in groups) {
                val safeGroupName = sanitizeIdentifier(groupName)
                sb.append("    subgraph ").append(safeGroupName).append(" [\"").append(escapeQuotes(groupName)).append("\"]\n")
                for (node in groupNodes) {
                    val safeId = nodeIdMap[node.nodeId] ?: continue
                    val label = escapeQuotes(node.name.ifBlank { node.nodeId })
                    sb.append("        ").append(safeId).append("[\"").append(label).append("\"]\n")
                }
                sb.append("    end\n")
            }
        } else {
            for (node in nodes) {
                val safeId = nodeIdMap[node.nodeId] ?: continue
                val label = escapeQuotes(node.name.ifBlank { node.nodeId })
                sb.append("    ").append(safeId).append("[\"").append(label).append("\"]\n")
            }
        }

        for (edge in edges) {
            val src = nodeIdMap[edge.sourceNodeId] ?: continue
            val tgt = nodeIdMap[edge.targetNodeId] ?: continue
            val edgeLabel = escapeQuotes(edge.edgeType)
            if (edgeLabel.isNotBlank()) {
                sb.append("    ").append(src).append(" -->|").append(edgeLabel).append("| ").append(tgt).append("\n")
            } else {
                sb.append("    ").append(src).append(" --> ").append(tgt).append("\n")
            }
        }

        return sb.toString()
    }

    /**
     * Render the given nodes and edges as a Graphviz DOT directed graph.
     */
    fun toGraphvizDot(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        rankdir: String = "LR"
    ): String {
        val sb = StringBuilder()
        sb.append("digraph CodeAtlas {\n")
        sb.append("    rankdir=").append(rankdir).append(";\n")
        sb.append("    node [shape=box, style=\"rounded,filled\", fillcolor=\"#f3f4f6\", fontname=\"Helvetica\"];\n")
        sb.append("    edge [fontname=\"Helvetica\", fontsize=10];\n")

        for (node in nodes) {
            val label = "${escapeQuotes(node.name)}\\n(${escapeQuotes(node.kind)}, ${escapeQuotes(node.archLayer)})"
            sb.append("    \"").append(escapeQuotes(node.nodeId)).append("\" [label=\"").append(label).append("\"];\n")
        }

        for (edge in edges) {
            val label = escapeQuotes(edge.edgeType)
            sb.append("    \"").append(escapeQuotes(edge.sourceNodeId)).append("\" -> \"")
                .append(escapeQuotes(edge.targetNodeId)).append("\" [label=\"").append(label).append("\"];\n")
        }

        sb.append("}\n")
        return sb.toString()
    }

    /**
     * Render the given nodes and edges as Cytoscape.js elements JSON.
     */
    fun toCytoscapeJson(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        pretty: Boolean = true
    ): String {
        val root = buildJsonObject {
            putJsonObject("elements") {
                putJsonArray("nodes") {
                    for (node in nodes) {
                        addJsonObject {
                            putJsonObject("data") {
                                put("id", node.nodeId)
                                put("label", node.name)
                                put("kind", node.kind)
                                put("module", node.moduleId)
                                put("layer", node.archLayer)
                                put("file", node.fileId)
                                put("in_degree", node.inDegree)
                                put("out_degree", node.outDegree)
                            }
                        }
                    }
                }
                putJsonArray("edges") {
                    for (edge in edges) {
                        addJsonObject {
                            putJsonObject("data") {
                                put("id", edge.edgeId)
                                put("source", edge.sourceNodeId)
                                put("target", edge.targetNodeId)
                                put("label", edge.edgeType)
                                put("provenance", edge.provenance)
                            }
                        }
                    }
                }
            }
        }

        return if (pretty) jsonPretty.encodeToString(JsonObject.serializer(), root)
        else root.toString()
    }

    private fun sanitizeIdentifier(raw: String): String {
        return raw.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
    }

    private fun escapeQuotes(raw: String): String {
        return raw.replace("\"", "\\\"").replace("\n", " ")
    }
}
