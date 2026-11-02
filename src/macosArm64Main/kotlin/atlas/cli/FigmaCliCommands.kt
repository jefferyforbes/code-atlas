package atlas.cli

import atlas.engine.FigmaDesignAuditor
import atlas.extract.FigmaSnapshotExtractor
import atlas.extract.UrlTargetResolver
import atlas.mcp.AtlasMcpServer
import atlas.model.FigmaNode
import atlas.model.FigmaSpatialBounds
import atlas.model.FigmaVariable
import atlas.util.PlatformFiles
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * CLI command handlers for `atlas figma <index|query|audit|synthesize-scope>` subcommands.
 */
internal object FigmaCliCommands {

    private data class FigmaResolvedContext(
        val fileKey: String,
        val figmaAction: String,
        val normTarget: String,
        val normSecondaryTarget: String,
        val normSeeds: List<String>
    )

    fun runFigmaCommand(server: AtlasMcpServer, args: CliArgs): Int {
        val ctx = resolveFigmaContext(server, args)
        return when (args.command) {
            "index" -> runFigmaIndexSubcommand(server, args, ctx.fileKey)
            "query" -> runFigmaQuerySubcommand(server, args, ctx)
            "audit" -> runFigmaAuditSubcommand(server, args.project, ctx.fileKey, args.isJson)
            "synthesize-scope" -> runFigmaScopeSubcommand(server, args.project, ctx.fileKey, ctx.normSeeds, args.isJson)
            "version" -> {
                AtlasCli.printCliVersion(args.isJson)
                0
            }
            else -> {
                println("Unknown figma command: ${args.command}")
                AtlasCli.printHelp()
                1
            }
        }
    }

    private fun resolveFigmaContext(server: AtlasMcpServer, args: CliArgs): FigmaResolvedContext {
        val parsedFromFileArg = args.fileKey?.let { UrlTargetResolver.parseFigmaUrl(it) }
        val parsedFromTarget = UrlTargetResolver.parseFigmaUrl(args.target)
        val parsedFromSecondary = UrlTargetResolver.parseFigmaUrl(args.secondaryTarget)
        val parsedSeedUrls = args.seeds.mapNotNull { UrlTargetResolver.parseFigmaUrl(it) }
        val fileKey = parsedFromFileArg?.fileKey
            ?: parsedFromTarget?.fileKey
            ?: parsedSeedUrls.firstOrNull()?.fileKey
            ?: args.fileKey
            ?: server.store.getLatestFigmaFileKey()
            ?: "default"
        val normTarget = resolveNormTarget(args.target, parsedFromTarget, parsedFromFileArg)
        val normSecondaryTarget = parsedFromSecondary?.nodeId ?: UrlTargetResolver.normalizeFigmaNodeId(args.secondaryTarget)
        val normSeeds = resolveFigmaSeeds(args.seeds, normTarget, parsedFromFileArg?.nodeId)
        val figmaAction = if (args.action == "search") "symbol" else args.action
        return FigmaResolvedContext(fileKey, figmaAction, normTarget, normSecondaryTarget, normSeeds)
    }

    private fun resolveNormTarget(
        target: String,
        parsedFromTarget: UrlTargetResolver.FigmaDeepLink?,
        parsedFromFileArg: UrlTargetResolver.FigmaDeepLink?
    ): String {
        if (parsedFromTarget?.nodeId != null) return parsedFromTarget.nodeId
        if (target.isEmpty()) return parsedFromFileArg?.nodeId ?: ""
        return UrlTargetResolver.normalizeFigmaNodeId(target)
    }

    private fun resolveFigmaSeeds(seeds: List<String>, normTarget: String, fileArgNodeId: String?): List<String> {
        if (seeds.isNotEmpty()) {
            return seeds.map { s -> UrlTargetResolver.parseFigmaUrl(s)?.nodeId ?: UrlTargetResolver.normalizeFigmaNodeId(s) }
        }
        if (normTarget.isNotEmpty()) return listOf(normTarget)
        if (fileArgNodeId != null) return listOf(fileArgNodeId)
        return emptyList()
    }

    private fun readFigmaAstJson(isStdin: Boolean, snapshotPath: String?): String? {
        if (isStdin) return AtlasCli.readStdin()
        if (snapshotPath != null) {
            if (!PlatformFiles.exists(snapshotPath)) {
                println("Error: Snapshot file not found at $snapshotPath")
                return null
            }
            return PlatformFiles.readText(snapshotPath)
        }
        println("Error: atlas figma index requires --snapshot <path> or --stdin")
        return null
    }

    private fun runFigmaIndexSubcommand(server: AtlasMcpServer, args: CliArgs, fileKey: String): Int {
        val astJson = readFigmaAstJson(args.isStdin, args.snapshotPath) ?: return 1
        val tokensPath = args.tokensPath
        val tokensJson = if (tokensPath != null && PlatformFiles.exists(tokensPath)) {
            PlatformFiles.readText(tokensPath)
        } else null

        val snapshot = FigmaSnapshotExtractor().ingest(server.store, fileKey, astJson, tokensJson)
        if (args.isJson) {
            val resObj = buildJsonObject {
                put("file_key", fileKey)
                put("nodes_indexed", snapshot.nodes.size)
                put("variables_indexed", snapshot.variables.size)
                put("edges_indexed", snapshot.edges.size)
                put("document_name", snapshot.document.name)
            }
            println(AtlasCli.jsonPretty.encodeToString(resObj))
        } else {
            val text = "[Atlas Figma] Indexed project=${args.project} fileKey=$fileKey nodes=${snapshot.nodes.size} variables=${snapshot.variables.size} edges=${snapshot.edges.size}"
            println(AtlasCli.truncateSummary(text, false))
        }
        return 0
    }

    private fun renderFigmaSymbolOutput(
        fileKey: String,
        normTarget: String,
        nodes: List<FigmaNode>,
        variable: FigmaVariable?,
        isJson: Boolean
    ) {
        if (isJson) {
            val jsonElement = buildJsonObject {
                put("file_key", fileKey)
                put("target", normTarget)
                putJsonArray("nodes") {
                    nodes.forEach { n -> add(AtlasCli.jsonCompact.encodeToJsonElement(n)) }
                }
                if (variable != null) {
                    put("variable", AtlasCli.jsonCompact.encodeToJsonElement(variable))
                }
            }
            println(AtlasCli.jsonPretty.encodeToString(jsonElement))
            return
        }
        val count = nodes.size + (if (variable != null) 1 else 0)
        val names = nodes.map { it.name } + listOfNotNull(variable?.name)
        val text = "[Figma Query: symbol] target=$normTarget found=$count matches=[${names.take(3).joinToString(", ")}]"
        println(AtlasCli.truncateSummary(text, false))
    }

    private fun queryFigmaSymbol(server: AtlasMcpServer, fileKey: String, normTarget: String, isJson: Boolean): Int {
        val node = server.store.queryFigmaNodeById(fileKey, normTarget)
        val nodes = if (node != null) listOf(node) else server.store.queryFigmaNodesByName(fileKey, normTarget)
        val variable = if (nodes.isEmpty()) {
            server.store.queryFigmaVariableByName(fileKey, normTarget)
                ?: server.store.queryFigmaVariableById(fileKey, normTarget)
        } else null
        renderFigmaSymbolOutput(fileKey, normTarget, nodes, variable, isJson)
        return 0
    }

    private fun queryFigmaCallers(server: AtlasMcpServer, fileKey: String, normTarget: String, depth: Int, isJson: Boolean): Int {
        val callers = server.store.queryFigmaCallers(fileKey, normTarget, depth)
        if (isJson) {
            println(AtlasCli.jsonPretty.encodeToString(callers))
        } else {
            val names = callers.map { it.name }
            val text = "[Figma Query: callers] target=$normTarget callers=${callers.size} instances=[${names.take(3).joinToString(", ")}]"
            println(AtlasCli.truncateSummary(text, false))
        }
        return 0
    }

    private fun queryFigmaImpact(server: AtlasMcpServer, fileKey: String, normTarget: String, depth: Int, isJson: Boolean): Int {
        val impact = server.store.resolveVariableBlastRadius(fileKey, normTarget, depth)
        if (isJson) {
            println(AtlasCli.jsonPretty.encodeToString(impact))
        } else {
            val vars = impact.impactedVariables.map { it.name }
            val bound = impact.boundNodes.map { it.name }
            val text = "[Figma Query: impact] target=$normTarget impacted_vars=${vars.size} bound_nodes=${bound.size} vars=[${vars.take(3).joinToString(", ")}]"
            println(AtlasCli.truncateSummary(text, false))
        }
        return 0
    }

    private fun parseSpatialBounds(
        server: AtlasMcpServer,
        fileKey: String,
        normTarget: String,
        boundsArg: String?
    ): FigmaSpatialBounds {
        if (boundsArg != null) {
            val parts = boundsArg.split(',').filter { it.isNotBlank() }.map { it.trim().toDoubleOrNull() ?: 0.0 }
            if (parts.size >= 4) return FigmaSpatialBounds(parts[0], parts[1], parts[2], parts[3])
            return FigmaSpatialBounds(0.0, 0.0, 0.0, 0.0)
        }
        val targetNode = server.store.queryFigmaNodeById(fileKey, normTarget)
            ?: server.store.queryFigmaNodesByName(fileKey, normTarget).firstOrNull()
        return targetNode?.spatialBounds ?: FigmaSpatialBounds(0.0, 0.0, 0.0, 0.0)
    }

    private fun queryFigmaSpatial(
        server: AtlasMcpServer,
        fileKey: String,
        normTarget: String,
        boundsArg: String?,
        isJson: Boolean
    ): Int {
        val bounds = parseSpatialBounds(server, fileKey, normTarget, boundsArg)
        val intersecting = server.store.queryFigmaSpatialIntersection(fileKey, bounds)
        if (isJson) {
            println(AtlasCli.jsonPretty.encodeToString(intersecting))
        } else {
            val names = intersecting.map { it.name }
            val text = "[Figma Query: spatial] bounds=[${bounds.minX.toInt()}..${bounds.maxX.toInt()}, ${bounds.minY.toInt()}..${bounds.maxY.toInt()}] nodes=${intersecting.size} items=[${names.take(3).joinToString(", ")}]"
            println(AtlasCli.truncateSummary(text, false))
        }
        return 0
    }

    private fun queryFigmaGodNodes(server: AtlasMcpServer, fileKey: String, isJson: Boolean): Int {
        val godNodes = server.store.queryFigmaGodNodes(fileKey, limit = 10)
        if (isJson) {
            val arr = buildJsonArray {
                for ((node, count) in godNodes) {
                    add(buildJsonObject {
                        put("node_id", node.nodeId)
                        put("name", node.name)
                        put("type", node.nodeType)
                        put("references", count)
                    })
                }
            }
            println(AtlasCli.jsonPretty.encodeToString(arr))
        } else {
            val items = godNodes.map { "${it.first.name} (${it.second} refs)" }
            val text = "[Figma Query: god_nodes] fileKey=$fileKey top_nodes=${godNodes.size} nodes=[${items.take(3).joinToString(", ")}]"
            println(AtlasCli.truncateSummary(text, false))
        }
        return 0
    }

    private fun queryFigmaShortestPath(
        server: AtlasMcpServer,
        ctx: FigmaResolvedContext,
        depth: Int,
        isJson: Boolean
    ): Int {
        val path = server.store.findFigmaShortestPath(
            ctx.fileKey, ctx.normTarget, ctx.normSecondaryTarget, depth = maxOf(depth, 10)
        )
        if (isJson) {
            println(AtlasCli.jsonPretty.encodeToString(path))
        } else {
            val names = path.map { it.name }
            val text = "[Figma Query: shortest_path] fileKey=${ctx.fileKey} from=${ctx.normTarget} to=${ctx.normSecondaryTarget} hops=${maxOf(0, path.size - 1)} path=[${names.joinToString(" -> ")}]"
            println(AtlasCli.truncateSummary(text, false))
        }
        return 0
    }

    private fun runFigmaQuerySubcommand(
        server: AtlasMcpServer,
        args: CliArgs,
        ctx: FigmaResolvedContext
    ): Int = when (ctx.figmaAction) {
        "symbol" -> queryFigmaSymbol(server, ctx.fileKey, ctx.normTarget, args.isJson)
        "callers" -> queryFigmaCallers(server, ctx.fileKey, ctx.normTarget, args.depth, args.isJson)
        "impact" -> queryFigmaImpact(server, ctx.fileKey, ctx.normTarget, args.depth, args.isJson)
        "spatial" -> queryFigmaSpatial(server, ctx.fileKey, ctx.normTarget, args.boundsArg, args.isJson)
        "god_nodes" -> queryFigmaGodNodes(server, ctx.fileKey, args.isJson)
        "shortest_path" -> queryFigmaShortestPath(server, ctx, args.depth, args.isJson)
        else -> {
            println("Unknown figma query action: ${ctx.figmaAction}. Valid: symbol, callers, impact, spatial, god_nodes, shortest_path")
            1
        }
    }

    private fun runFigmaAuditSubcommand(server: AtlasMcpServer, project: String, fileKey: String, isJson: Boolean): Int {
        val result = FigmaDesignAuditor(server.store).audit(fileKey)
        if (isJson) {
            println(AtlasCli.jsonPretty.encodeToString(result))
        } else {
            val violationSnippets = if (result.violations.isNotEmpty()) {
                " violations=[${result.violations.take(3).joinToString("; ") { "${it.rule}: ${it.nodeName}" }}]"
            } else ""
            val text = "[Figma Audit] project=$project file=$fileKey passed=${result.passed} score=${result.maturityScore.toInt()}/100 violations=${result.violations.size} polish_delta=${result.polishDelta.size}$violationSnippets"
            println(AtlasCli.truncateSummary(text, false))
        }
        return if (result.passed) 0 else 1
    }

    private fun runFigmaScopeSubcommand(
        server: AtlasMcpServer,
        project: String,
        fileKey: String,
        normSeeds: List<String>,
        isJson: Boolean
    ): Int {
        val scope = FigmaDesignAuditor(server.store).synthesizeFigmaScope(project, fileKey, normSeeds)
        if (isJson) {
            println(AtlasCli.jsonPretty.encodeToString(scope))
        } else {
            val collisionSnippets = if (scope.potentialCollisions.isNotEmpty()) {
                " collisions=${scope.potentialCollisions.size}"
            } else ""
            val text = "[Figma 3D Scope] project=$project file=$fileKey bounds=[${scope.spatialBounds.minX.toInt()}..${scope.spatialBounds.maxX.toInt()}, ${scope.spatialBounds.minY.toInt()}..${scope.spatialBounds.maxY.toInt()}] nodes=${scope.scopedNodesCount} shared_components=${scope.sharedMasterComponents.size} tokens=${scope.tokenLayers.size}$collisionSnippets"
            println(AtlasCli.truncateSummary(text, false))
        }
        return 0
    }
}
