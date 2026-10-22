package atlas.mcp

import atlas.config.CacheProfileResolver
import atlas.db.SqliteGraphStore
import atlas.engine.ArchitectureAuditor
import atlas.engine.ArchitectureRemediationEngine
import atlas.engine.AutonomousRemediationVerifier
import atlas.engine.CodegraphCompatibilityEngine
import atlas.engine.ComplexityDoctorEngine
import atlas.engine.ComposeStabilityEngine
import atlas.engine.FigmaDesignAuditor
import atlas.engine.ScopeSynthesizer
import atlas.engine.ToolMigrationEngine
import atlas.engine.WorkMemoryStore
import atlas.engine.WorkspaceIndexer
import atlas.engine.ArchitectContextEngine
import atlas.extract.UrlTargetResolver
import atlas.model.ArchitectContextResult
import atlas.model.ComplexityScoreRecord
import atlas.model.ComplexityViolation
import atlas.model.GraphNode
import atlas.util.PlatformFiles
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import platform.posix.fflush
import platform.posix.stdout
import platform.posix.time

/**
 * Standards-compliant 4-tool MCP Server and incremental workspace indexer with 32KB disk spillover.
 */
@OptIn(ExperimentalForeignApi::class)
class AtlasMcpServer(
    val workspaceRoot: String,
    val customDbPath: String? = null,
    val cacheProfile: String? = null,
    val enableCodegraphTools: Boolean = (PlatformFiles.getenv("ATLAS_CODEGRAPH_COMPAT") == "1")
) {
    companion object {
        const val SPILLOVER_THRESHOLD_BYTES = 32768
        private var spilloverSeq = 0L
        private val COMPOSE_QUERY_ACTIONS = setOf("summary", "compose_summary", "cascade", "compose_cascade", "blame", "compose_blame")
    }

    val dbPath: String
    val artifactsDir: String
    val store: SqliteGraphStore
    val synthesizer: ScopeSynthesizer
    val auditor: ArchitectureAuditor
    val memory: WorkMemoryStore
    val figmaAuditor: FigmaDesignAuditor
    val composeEngine: ComposeStabilityEngine
    val complexityDoctor: ComplexityDoctorEngine
    val indexer: WorkspaceIndexer
    val remediationVerifier: AutonomousRemediationVerifier
    val remediationEngine: ArchitectureRemediationEngine
    val codegraphEngine: CodegraphCompatibilityEngine
    val architectContextEngine: ArchitectContextEngine

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    init {
        val (resolvedDb, artifacts) = CacheProfileResolver.resolvePaths(
            workspaceRoot = workspaceRoot,
            customDb = customDbPath,
            profile = cacheProfile
        )
        dbPath = resolvedDb
        artifactsDir = artifacts
        store = SqliteGraphStore(dbPath)
        synthesizer = ScopeSynthesizer(store)
        auditor = ArchitectureAuditor(store, workspaceRoot)
        memory = WorkMemoryStore(store)
        figmaAuditor = FigmaDesignAuditor(store)
        composeEngine = ComposeStabilityEngine(store)
        complexityDoctor = ComplexityDoctorEngine(store, workspaceRoot)
        indexer = WorkspaceIndexer(workspaceRoot, store)
        remediationVerifier = AutonomousRemediationVerifier(workspaceRoot, store)
        remediationEngine = ArchitectureRemediationEngine(workspaceRoot, store, remediationVerifier)
        codegraphEngine = CodegraphCompatibilityEngine(store, workspaceRoot)
        architectContextEngine = ArchitectContextEngine(store, synthesizer, workspaceRoot, artifactsDir)
    }

    fun toolDefinitions(): List<JsonObject> {
        val querySchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("search")
                        add("symbol")
                        add("callers")
                        add("callees")
                        add("impact")
                        add("god_nodes")
                        add("shortest_path")
                        add("status")
                        add("summary")
                        add("compose_summary")
                        add("cascade")
                        add("compose_cascade")
                        add("blame")
                        add("compose_blame")
                        add("complexity")
                        add("url")
                        add("explore")
                    }
                    put("description", "Query operation to execute against the indexed polyglot code, Compose stability, complexity, and vault graph.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Target project slug (for example: organisation, relay, reflect, forma).")
                }
                putJsonObject("target") {
                    put("type", "string")
                    put("description", "Symbol name, qualified identifier, file path, or search query string.")
                }
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Search query or exploration target (alias for target).")
                }
                putJsonObject("secondary_target") {
                    put("type", "string")
                    put("description", "Destination symbol or node identifier when computing shortest_path.")
                }
                putJsonObject("depth") {
                    put("type", "integer")
                    put("description", "Maximum traversal hops for callers, callees, cascade, or blast-radius impact (default: 2).")
                }
                putJsonObject("threshold") {
                    put("type", "integer")
                    put("description", "Cognitive complexity threshold for filtering (default: 10).")
                }
                putJsonObject("include_source") {
                    put("type", "boolean")
                    put("description", "Include live SHA-256 verified source lines sliced from disk (default: false).")
                }
                putJsonObject("strong_skipping") {
                    put("type", "boolean")
                    put("description", "Evaluate Compose stability under Kotlin 2.0+ Strong Skipping rules (default: true).")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum results to return (default: 50).")
                }
            }
            putJsonArray("required") {
                add("action")
                add("project")
            }
        }

        val auditSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("verify_architecture")
                        add("remedy_architecture")
                        add("synthesize_3d_scope")
                        add("record_memory")
                        add("sync_index")
                        add("dead_code")
                        add("check_descriptions")
                        add("figma_audit")
                        add("figma_scope")
                        add("audit_compose")
                        add("compose_doctor")
                        add("synthesize_compose_scope")
                        add("verify_remediation")
                        add("audit_complexity")
                        add("complexity_doctor")
                        add("migrate_tools")
                        add("architect_context")
                        add("audit_skills")
                    }
                    put("description", "Audit, synthesis, Compose compliance, complexity audit, learning, or indexing action to execute.")
                }
                putJsonObject("depth") {
                    put("type", "integer")
                    put("description", "Maximum traversal depth for architect context synthesis (default: 2).")
                }
                putJsonObject("threshold") {
                    put("type", "integer")
                    put("description", "Cognitive complexity threshold for Gate 1 complexity audit (default: 10).")
                }
                putJsonObject("apply") {
                    put("type", "boolean")
                    put("description", "Apply verified remediation prescriptions directly to disk (default: false).")
                }
                putJsonObject("description_mode") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("domain-only")
                        add("all")
                        add("off")
                    }
                    put("description", "Filter mode for top-level doc descriptions (domain-only, all, off; default: domain-only).")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Target project slug.")
                }
                putJsonObject("file_key") {
                    put("type", "string")
                    put("description", "Figma file key or document slug for figma_audit or figma_scope.")
                }
                putJsonObject("targets") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Seed symbols, file paths, plan names, or Figma nodes to scope, audit, or record outcomes.")
                }
                putJsonObject("seeds") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Seed symbols, file paths, or nodes (alias for targets).")
                }
                putJsonObject("outcome") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("useful")
                        add("dead_end")
                        add("corrected")
                    }
                    put("description", "Agent outcome annotation to record into experiential work memory.")
                }
                putJsonObject("note") {
                    put("type", "string")
                    put("description", "Short rationale or learning note explaining why this path was useful or a dead end.")
                }
                putJsonObject("stability_config_path") {
                    put("type", "string")
                    put("description", "Optional path to a Compose stability_config.conf file for wildcard type matching.")
                }
                putJsonObject("compiler_reports_dir") {
                    put("type", "string")
                    put("description", "Optional directory or file path for Compose Compiler metrics reports.")
                }
                putJsonObject("trace_log_path") {
                    put("type", "string")
                    put("description", "Optional file path for bounded @TraceRecomposition JSONL or Logcat runtime telemetry.")
                }
                putJsonObject("strong_skipping") {
                    put("type", "boolean")
                    put("description", "Evaluate Compose audit rules under Kotlin 2.0+ Strong Skipping mode (default: true).")
                }
                putJsonObject("file_path") {
                    put("type", "string")
                    put("description", "Target file path for autonomous remediation verification.")
                }
                putJsonObject("before_snippet") {
                    put("type", "string")
                    put("description", "Original code snippet targeted for replacement.")
                }
                putJsonObject("after_snippet") {
                    put("type", "string")
                    put("description", "Proposed replacement code snippet.")
                }
                putJsonObject("rule_id") {
                    put("type", "string")
                    put("description", "Target rule identifier (e.g. COMPOSE-STAB-001, COMPOSE-API-001).")
                }
                putJsonObject("target_node_id") {
                    put("type", "string")
                    put("description", "Optional target composable/symbol identifier to scope rule clearance verification.")
                }
                putJsonObject("write_site_safe") {
                    put("type", "boolean")
                    put("description", "Write-site safety indicator (default: true).")
                }
                putJsonObject("dry_run") {
                    put("type", "boolean")
                    put("description", "Validate without committing changes to disk (default: true).")
                }
                putJsonObject("test_command") {
                    put("type", "string")
                    put("description", "Optional shell command to verify test suite preservation (e.g. './gradlew test').")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum prescriptions or violations to return (default: 10 for doctor, 50 for audit).")
                }
                putJsonObject("max_cyclomatic") {
                    put("type", "integer")
                    put("description", "Maximum cyclomatic complexity threshold for audit_complexity (default: 15).")
                }
                putJsonObject("warn_params") {
                    put("type", "integer")
                    put("description", "Warning threshold for function parameter count in audit_complexity (default: 5).")
                }
                putJsonObject("max_params") {
                    put("type", "integer")
                    put("description", "Error/HIGH severity threshold for function parameter count in audit_complexity (default: 7).")
                }
                putJsonObject("compose_warn_params") {
                    put("type", "integer")
                    put("description", "Warning threshold for Compose function parameter count in audit_complexity (default: 10).")
                }
                putJsonObject("compose_max_params") {
                    put("type", "integer")
                    put("description", "Error/HIGH severity threshold for Compose function parameter count in audit_complexity (default: 14).")
                }
                putJsonObject("check_params") {
                    put("type", "boolean")
                    put("description", "Enable parameter count complexity rule COMPLEXITY-002-PARAMS (default: true).")
                }
                putJsonObject("ratchet") {
                    put("type", "integer")
                    put("description", "Maximum allowed violations for audit_complexity ratchet gate.")
                }
                putJsonObject("high_ratchet") {
                    put("type", "integer")
                    put("description", "Maximum allowed HIGH severity violations for audit_complexity ratchet gate.")
                }
                putJsonObject("max_high_violations") {
                    put("type", "integer")
                    put("description", "Alias for high_ratchet.")
                }
                putJsonObject("baseline") {
                    put("type", "string")
                    put("description", "Explicit baseline violation count or path to baseline JSON file.")
                }
                putJsonObject("split") {
                    put("type", "boolean")
                    put("description", "Enable BOOST-003 file splitting during remediation (default: true).")
                }
                putJsonObject("make_private") {
                    put("type", "boolean")
                    put("description", "Enable BOOST-003 visibility reduction to private during remediation (default: false).")
                }
                putJsonObject("rule") {
                    put("type", "string")
                    put("description", "Filter by specific architecture rule ID (e.g. BOOST-003, ARCH-LAYER-INVERSION).")
                }
                putJsonObject("target") {
                    put("type", "string")
                    put("description", "Target symbol name, file path, or composable identifier.")
                }
            }
            putJsonArray("required") {
                add("action")
                add("project")
            }
        }

        val figmaAuditSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_key") {
                    put("type", "string")
                    put("description", "Figma document file key or identifier.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Target project slug (default: organisation).")
                }
            }
            putJsonArray("required") {
                add("file_key")
            }
        }

        val figmaScopeSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_key") {
                    put("type", "string")
                    put("description", "Figma document file key or identifier.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Target project slug (default: organisation).")
                }
                putJsonObject("seeds") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Seed node names, component names, or IDs to scope.")
                }
            }
            putJsonArray("required") {
                add("file_key")
            }
        }

        val codegraphExploreSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Symbol names, file names, or short code terms to explore.")
                }
                putJsonObject("maxFiles") {
                    put("type", "integer")
                    put("description", "Maximum number of files to include source code from (default: 12).")
                }
                putJsonObject("projectPath") {
                    put("type", "string")
                    put("description", "Optional project path or root directory to query.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Optional project slug.")
                }
            }
            putJsonArray("required") {
                add("query")
            }
        }

        val codegraphNodeSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("symbol") {
                    put("type", "string")
                    put("description", "Name of the symbol to inspect or read.")
                }
                putJsonObject("file") {
                    put("type", "string")
                    put("description", "A file path or basename to read or disambiguate symbol.")
                }
                putJsonObject("includeCode") {
                    put("type", "boolean")
                    put("description", "Include the symbol's full body (default: false).")
                }
                putJsonObject("offset") {
                    put("type", "integer")
                    put("description", "1-based line to start reading from in file mode.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum number of lines to return in file mode (default: 2000).")
                }
                putJsonObject("symbolsOnly") {
                    put("type", "boolean")
                    put("description", "Return symbol map instead of source (default: false).")
                }
                putJsonObject("line") {
                    put("type", "integer")
                    put("description", "Disambiguate symbol around this line.")
                }
                putJsonObject("projectPath") {
                    put("type", "string")
                    put("description", "Optional project path or root directory.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Optional project slug.")
                }
            }
        }

        val codegraphSearchSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Symbol name or partial name to search.")
                }
                putJsonObject("kind") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("function")
                        add("method")
                        add("class")
                        add("interface")
                        add("type")
                        add("variable")
                        add("route")
                        add("component")
                    }
                    put("description", "Filter by node kind.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum results (default: 10).")
                }
                putJsonObject("projectPath") {
                    put("type", "string")
                    put("description", "Optional project path or root directory.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Optional project slug.")
                }
            }
            putJsonArray("required") {
                add("query")
            }
        }

        val codegraphCallersSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("symbol") {
                    put("type", "string")
                    put("description", "Name of the function, method, or class to find callers for.")
                }
                putJsonObject("file") {
                    put("type", "string")
                    put("description", "Narrow to definition in this file.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum number of callers to return (default: 20).")
                }
                putJsonObject("projectPath") {
                    put("type", "string")
                    put("description", "Optional project path or root directory.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Optional project slug.")
                }
            }
            putJsonArray("required") {
                add("symbol")
            }
        }

        val codegraphCalleesSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("symbol") {
                    put("type", "string")
                    put("description", "Name of the function, method, or class to find callees for.")
                }
                putJsonObject("file") {
                    put("type", "string")
                    put("description", "Narrow to definition in this file.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum number of callees to return (default: 20).")
                }
                putJsonObject("projectPath") {
                    put("type", "string")
                    put("description", "Optional project path or root directory.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Optional project slug.")
                }
            }
            putJsonArray("required") {
                add("symbol")
            }
        }

        val codegraphImpactSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("symbol") {
                    put("type", "string")
                    put("description", "Name of the symbol to analyze impact for.")
                }
                putJsonObject("file") {
                    put("type", "string")
                    put("description", "Narrow to definition in this file.")
                }
                putJsonObject("depth") {
                    put("type", "integer")
                    put("description", "Levels of dependencies to traverse (default: 2).")
                }
                putJsonObject("projectPath") {
                    put("type", "string")
                    put("description", "Optional project path or root directory.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Optional project slug.")
                }
            }
            putJsonArray("required") {
                add("symbol")
            }
        }

        val codegraphStatusSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("projectPath") {
                    put("type", "string")
                    put("description", "Optional project path or root directory.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Optional project slug.")
                }
            }
        }

        val codegraphFilesSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Filter files under this directory path.")
                }
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "Filter files matching this glob pattern.")
                }
                putJsonObject("format") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("tree")
                        add("flat")
                    }
                    put("description", "Output format: tree (default) or flat.")
                }
                putJsonObject("projectPath") {
                    put("type", "string")
                    put("description", "Optional project path or root directory.")
                }
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "Optional project slug.")
                }
            }
        }

        val baseTools = listOf(
            buildJsonObject {
                put("name", "atlas_query")
                put("description", "Query indexed polyglot AST symbols, structural skeletons, explore semantics, callers, callees, blast radius, or Compose stability.")
                put("inputSchema", querySchema)
            },
            buildJsonObject {
                put("name", "atlas_audit")
                put("description", "Audit architecture layers, Compose stability, complexity doctor, synthesize 3D scopes, or assemble architect context.")
                put("inputSchema", auditSchema)
            },
            buildJsonObject {
                put("name", "figma_audit")
                put("description", "Run 12 deterministic design invariant audits and evaluate Gate 1 maturity compliance.")
                put("inputSchema", figmaAuditSchema)
            },
            buildJsonObject {
                put("name", "figma_scope")
                put("description", "Synthesize Figma 3D Concurrency Scope (spatial canvas bounds, shared components, token layers).")
                put("inputSchema", figmaScopeSchema)
            }
        )

        if (!enableCodegraphTools) {
            return baseTools
        }

        return baseTools + listOf(
            buildJsonObject {
                put("name", "codegraph_explore")
                put("description", "Explore relevant code symbols, live line-numbered source, and caller relationships in one round-trip.")
                put("inputSchema", codegraphExploreSchema)
            },
            buildJsonObject {
                put("name", "codegraph_node")
                put("description", "Read whole file with line numbers or inspect symbol details, body, callers, and callees.")
                put("inputSchema", codegraphNodeSchema)
            },
            buildJsonObject {
                put("name", "codegraph_search")
                put("description", "Quick symbol search by name and kind. Returns locations and signatures.")
                put("inputSchema", codegraphSearchSchema)
            },
            buildJsonObject {
                put("name", "codegraph_callers")
                put("description", "List functions and methods that call a symbol.")
                put("inputSchema", codegraphCallersSchema)
            },
            buildJsonObject {
                put("name", "codegraph_callees")
                put("description", "List functions and methods that a symbol calls.")
                put("inputSchema", codegraphCalleesSchema)
            },
            buildJsonObject {
                put("name", "codegraph_impact")
                put("description", "List symbols affected by changing a symbol before refactoring.")
                put("inputSchema", codegraphImpactSchema)
            },
            buildJsonObject {
                put("name", "codegraph_status")
                put("description", "Index health check reporting indexed files, nodes, edges, and database location.")
                put("inputSchema", codegraphStatusSchema)
            },
            buildJsonObject {
                put("name", "codegraph_files")
                put("description", "Indexed file tree with pattern and directory filtering.")
                put("inputSchema", codegraphFilesSchema)
            }
        )
    }

    private val projectServers = mutableMapOf<String, AtlasMcpServer>()

    fun indexWorkspace(
        projectId: String,
        subpaths: List<String>? = null,
        force: Boolean = false
    ): JsonObject = resolveServerForProject(projectId).indexer.indexWorkspace(projectId, subpaths, force = force)

    fun indexComposeArtifacts(
        projectId: String,
        subpaths: List<String>? = null,
        stabilityConfigPath: String? = null,
        compilerReportsDir: String? = null,
        traceLogPath: String? = null
    ): JsonObject = resolveServerForProject(projectId).indexer.indexComposeArtifacts(
        projectId = projectId,
        subpaths = subpaths,
        stabilityConfigPath = stabilityConfigPath,
        compilerReportsDir = compilerReportsDir,
        traceLogPath = traceLogPath
    )

    fun resolveServerForProject(projectId: String, explicitWorkspace: String? = null): AtlasMcpServer {
        if (customDbPath != null || (projectId.isEmpty() && explicitWorkspace.isNullOrEmpty())) return this
        val localStats = store.getStats(projectId)
        if (localStats.files > 0) return this

        val candidateWs = if (!explicitWorkspace.isNullOrEmpty() && explicitWorkspace != "." && explicitWorkspace != workspaceRoot) {
            val path = if (PlatformFiles.isDirectory(explicitWorkspace)) explicitWorkspace
                       else PlatformFiles.parentOf(explicitWorkspace)
            PlatformFiles.canonicalPath(path)
        } else {
            CacheProfileResolver.resolveProjectWorkspace(workspaceRoot, projectId)
        }
        val canonCurrent = PlatformFiles.canonicalPath(workspaceRoot).replace('\\', '/').trimEnd('/')
        val canonCandidate = PlatformFiles.canonicalPath(candidateWs).replace('\\', '/').trimEnd('/')
        if (canonCandidate == canonCurrent) return this

        return projectServers.getOrPut(canonCandidate) {
            AtlasMcpServer(
                workspaceRoot = canonCandidate,
                customDbPath = null,
                cacheProfile = cacheProfile,
                enableCodegraphTools = enableCodegraphTools
            )
        }
    }

    fun close() {
        projectServers.values.forEach { it.store.close() }
        projectServers.clear()
        store.close()
    }

    fun callTool(name: String, arguments: JsonObject): JsonObject {
        val explicitPath = arguments["projectPath"]?.jsonPrimitive?.contentOrNull
            ?: arguments["project-path"]?.jsonPrimitive?.contentOrNull
        val explicitProject = arguments["project"]?.jsonPrimitive?.contentOrNull
        val project = when {
            !explicitProject.isNullOrBlank() -> explicitProject
            !explicitPath.isNullOrBlank() -> {
                val clean = explicitPath.trimEnd('/', '\\')
                if (clean.contains('/')) clean.substringAfterLast('/') else clean
            }
            else -> "organisation"
        }
        val activeServer = resolveServerForProject(project, explicitPath)
        if (activeServer !== this) {
            return activeServer.callToolDirect(name, arguments)
        }
        return callToolDirect(name, arguments)
    }

    private fun callToolDirect(name: String, arguments: JsonObject): JsonObject {
        val project = resolveProjectFromArgs(arguments)
        val payload = when (name) {
            "atlas_query" -> handleQuery(arguments)
            "atlas_audit" -> handleAudit(arguments)
            "figma_audit" -> handleFigmaAudit(arguments)
            "figma_scope" -> handleFigmaScope(arguments)
            "codegraph_explore" -> handleCodegraphExplore(arguments)
            "codegraph_node" -> handleCodegraphNode(arguments)
            "codegraph_search" -> handleCodegraphSearch(arguments)
            "codegraph_callers" -> handleCodegraphCallers(arguments)
            "codegraph_callees" -> handleCodegraphCallees(arguments)
            "codegraph_impact" -> handleCodegraphImpact(arguments)
            "codegraph_status" -> handleCodegraphStatus(arguments)
            "codegraph_files" -> handleCodegraphFiles(arguments)
            else -> throw IllegalArgumentException("Unknown MCP tool: $name")
        }
        return enforceSpillover(project, payload)
    }

    private fun extractQueryTarget(args: JsonObject): String {
        val t = args["target"]?.jsonPrimitive?.content
        if (!t.isNullOrEmpty()) return t
        return args["query"]?.jsonPrimitive?.content.orEmpty()
    }

    private fun handleQuery(args: JsonObject): JsonObject {
        val action = args["action"]?.jsonPrimitive?.content ?: "status"
        val project = args["project"]?.jsonPrimitive?.content ?: "organisation"
        val target = extractQueryTarget(args)
        val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 2
        val includeSource = args["include_source"]?.jsonPrimitive?.booleanOrNull ?: false
        val strongSkipping = args["strong_skipping"]?.jsonPrimitive?.booleanOrNull ?: true

        if (action in COMPOSE_QUERY_ACTIONS) {
            return handleQueryCompose(action, project, target, args, depth, strongSkipping)
        }
        return when (action) {
            "status" -> json.encodeToJsonElement(store.getStats(project)).jsonObject
            "god_nodes" -> buildJsonObject {
                put("project", project)
                put("god_nodes", json.encodeToJsonElement(store.getGodNodes(project, topN = 10)))
            }
            "search" -> buildJsonObject {
                put("project", project)
                put("query", target)
                put("results", json.encodeToJsonElement(store.searchNodes(project, target, limit = 15)))
            }
            "url" -> store.searchByUrl(project, target)
            "complexity" -> handleQueryComplexity(project, target, args)
            "explore" -> {
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 12
                codegraphEngine.explore(project, target, maxFiles = limit)
            }
            else -> handleQueryNodeAction(action, project, target, args, depth, includeSource)
        }
    }

    private fun handleQueryCompose(
        action: String,
        project: String,
        target: String,
        args: JsonObject,
        depth: Int,
        strongSkipping: Boolean
    ): JsonObject {
        val mappedAction = action.removePrefix("compose_")
        val maxDepth = if (args.containsKey("depth")) depth else 5
        val res = composeEngine.queryCompose(
            projectSlug = project,
            action = mappedAction,
            target = target.ifEmpty { null },
            maxDepth = maxDepth,
            strongSkipping = strongSkipping
        )
        return json.encodeToJsonElement(res).jsonObject
    }

    private fun handleQueryComplexity(project: String, target: String, args: JsonObject): JsonObject {
        val minCognitive = args["threshold"]?.jsonPrimitive?.intOrNull ?: 10
        val queryLimit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
        val results = if (target.isNotEmpty()) {
            resolveTargetComplexityRecords(project, target, queryLimit)
        } else {
            store.queryComplexNodes(project, minCognitive = minCognitive, limit = queryLimit)
        }
        return buildJsonObject {
            put("project", project)
            put("total_results", results.size)
            put("threshold", minCognitive)
            put("results", json.encodeToJsonElement(results))
        }
    }

    private fun resolveTargetComplexityRecords(project: String, target: String, queryLimit: Int): List<ComplexityScoreRecord> {
        val matching = store.queryComplexNodes(project, minCognitive = 0, limit = queryLimit * 4)
            .filter { isComplexityTargetMatch(it, target) }
        if (matching.isNotEmpty()) {
            return matching.take(queryLimit)
        }
        return createDirectNodeComplexityRecord(project, target)
    }

    private fun isComplexityTargetMatch(node: ComplexityScoreRecord, target: String): Boolean {
        return node.nodeId == target ||
            node.name == target ||
            node.qualifiedName == target ||
            node.qualifiedName.endsWith(".$target") ||
            node.nodeId.contains("#$target.") ||
            node.nodeId.endsWith("#$target") ||
            node.filePath.contains(target)
    }

    private fun createDirectNodeComplexityRecord(project: String, target: String): List<ComplexityScoreRecord> {
        val directNode = store.getNode(project, target) ?: return emptyList()
        if (directNode.kind !in setOf("function", "method", "composable")) {
            return emptyList()
        }
        val score = directNode.complexityScore
        val grade = when {
            score >= 90.0 -> "A"
            score >= 80.0 -> "B"
            score >= 70.0 -> "C"
            score >= 60.0 -> "D"
            else -> "F"
        }
        val risk = directNode.cognitiveComplexity.toDouble() * (1.0 + ln(1.0 + directNode.inDegree.toDouble()))
        val roundedRisk = (risk * 10.0).roundToInt() / 10.0
        val filePath = if (directNode.fileId.contains(':')) directNode.fileId.substringAfter(':') else directNode.fileId
        return listOf(
            ComplexityScoreRecord(
                nodeId = directNode.nodeId,
                projectId = project,
                name = directNode.name,
                qualifiedName = directNode.qualifiedName,
                filePath = filePath,
                line = directNode.startLine,
                cyclomatic = directNode.cyclomaticComplexity,
                cognitive = directNode.cognitiveComplexity,
                maxNesting = directNode.maxNestingDepth,
                inDegree = directNode.inDegree,
                overallScore = score,
                grade = grade,
                riskIndex = roundedRisk
            )
        )
    }

    private fun handleQueryNodeAction(
        action: String,
        project: String,
        target: String,
        args: JsonObject,
        depth: Int,
        includeSource: Boolean
    ): JsonObject {
        val node = store.getNode(project, target)
        if (node == null) {
            val matches = store.searchNodes(project, target, limit = 5)
            return buildJsonObject {
                put("project", project)
                put("error", "Symbol '$target' not found directly.")
                put("candidates", json.encodeToJsonElement(matches))
            }
        }
        return when (action) {
            "symbol" -> handleQuerySymbol(project, node, includeSource)
            "callers" -> handleQueryCallers(project, node, depth)
            "callees" -> handleQueryCallees(project, node, depth)
            "impact" -> handleQueryImpact(project, node, depth)
            "shortest_path" -> handleQueryShortestPath(project, node, args)
            else -> throw IllegalArgumentException("Unsupported atlas_query action: $action")
        }
    }

    private fun handleQuerySymbol(project: String, node: GraphNode, includeSource: Boolean): JsonObject {
        val annotations = memory.getActiveAnnotations(project, node.nodeId)
        return buildJsonObject {
            put("node", json.encodeToJsonElement(node))
            put("annotations", json.encodeToJsonElement(annotations))
            put("callers_count", node.inDegree)
            put("callees_count", node.outDegree)
            if (includeSource) {
                val slice = store.sliceLiveSource(workspaceRoot, project, node)
                put("live_source", json.encodeToJsonElement(slice))
            }
        }
    }

    private fun handleQueryCallers(project: String, node: GraphNode, depth: Int): JsonObject {
        val callers = store.getCallers(project, node.nodeId, depth = depth)
        return buildJsonObject {
            put("node_id", node.nodeId)
            put("callers", json.encodeToJsonElement(callers))
        }
    }

    private fun handleQueryCallees(project: String, node: GraphNode, depth: Int): JsonObject {
        val callees = store.getCallees(project, node.nodeId, depth = depth)
        return buildJsonObject {
            put("node_id", node.nodeId)
            put("callees", json.encodeToJsonElement(callees))
        }
    }

    private fun handleQueryImpact(project: String, node: GraphNode, depth: Int): JsonObject {
        val impact = store.getImpactRadius(project, node.nodeId, depth = depth)
        val baseObj = json.encodeToJsonElement(impact).jsonObject
        val annotations = memory.getActiveAnnotations(project, node.nodeId)
        return buildJsonObject {
            baseObj.forEach { (k, v) -> put(k, v) }
            put("annotations", json.encodeToJsonElement(annotations))
        }
    }

    private fun handleQueryShortestPath(project: String, node: GraphNode, args: JsonObject): JsonObject {
        val sec = args["secondary_target"]?.jsonPrimitive?.content ?: ""
        val tgtNode = store.getNode(project, sec)
            ?: return buildJsonObject { put("error", "Destination '$sec' not found.") }
        val path = store.getShortestPath(project, node.nodeId, tgtNode.nodeId)
        return buildJsonObject {
            put("source", node.nodeId)
            put("target", tgtNode.nodeId)
            put("path", json.encodeToJsonElement(path))
        }
    }

    private fun extractAuditTargets(args: JsonObject): List<String> {
        val fromArray = args["targets"]?.jsonArray ?: args["seeds"]?.jsonArray
        if (fromArray != null) {
            return fromArray.map { it.jsonPrimitive.content }.filter { it.isNotBlank() }
        }
        val single = args["target"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        return if (single != null) listOf(single) else emptyList()
    }

    private fun handleAudit(args: JsonObject): JsonObject {
        val action = args["action"]?.jsonPrimitive?.content ?: "verify_architecture"
        val project = args["project"]?.jsonPrimitive?.content ?: "organisation"
        val targets = extractAuditTargets(args)

        return when {
            action.startsWith("figma_") -> handleAuditFigmaAction(action, args)
            action.startsWith("compose_") || action == "audit_compose" -> handleAuditComposeAction(action, args, project, targets)
            action.startsWith("complexity_") || action == "audit_complexity" -> handleAuditComplexityAction(action, args, project, targets)
            else -> handleAuditCoreAction(action, args, project, targets)
        }
    }

    private fun handleAuditFigmaAction(action: String, args: JsonObject): JsonObject {
        return when (action) {
            "figma_audit" -> handleFigmaAudit(args)
            "figma_scope" -> handleFigmaScope(args)
            else -> throw IllegalArgumentException("Unsupported figma action: $action")
        }
    }

    private fun handleAuditComposeAction(
        action: String,
        args: JsonObject,
        project: String,
        targets: List<String>
    ): JsonObject {
        return when (action) {
            "audit_compose" -> {
                val stabilityConfigPath = args["stability_config_path"]?.jsonPrimitive?.contentOrNull
                val compilerReportsDir = args["compiler_reports_dir"]?.jsonPrimitive?.contentOrNull
                val traceLogPath = args["trace_log_path"]?.jsonPrimitive?.contentOrNull
                val strongSkipping = args["strong_skipping"]?.jsonPrimitive?.booleanOrNull ?: true
                handleAuditCompose(project, targets, stabilityConfigPath, compilerReportsDir, traceLogPath, strongSkipping)
            }
            "compose_doctor" -> {
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 10
                handleAuditComposeDoctor(args, project, targets, limit)
            }
            else -> throw IllegalArgumentException("Unsupported compose action: $action")
        }
    }

    private fun handleAuditComplexityAction(
        action: String,
        args: JsonObject,
        project: String,
        targets: List<String>
    ): JsonObject {
        return when (action) {
            "audit_complexity" -> handleAuditComplexity(args, project, targets)
            "complexity_doctor" -> {
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 10
                handleAuditComplexityDoctor(args, project, targets, limit)
            }
            else -> throw IllegalArgumentException("Unsupported complexity action: $action")
        }
    }

    private fun handleAuditCoreAction(
        action: String,
        args: JsonObject,
        project: String,
        targets: List<String>
    ): JsonObject {
        return when (action) {
            "sync_index" -> {
                val stabilityConfigPath = args["stability_config_path"]?.jsonPrimitive?.contentOrNull
                val compilerReportsDir = args["compiler_reports_dir"]?.jsonPrimitive?.contentOrNull
                val traceLogPath = args["trace_log_path"]?.jsonPrimitive?.contentOrNull
                handleAuditSyncIndex(project, targets, stabilityConfigPath, compilerReportsDir, traceLogPath)
            }
            "synthesize_3d_scope" -> handleAuditSynthesize3dScope(project, targets)
            "synthesize_compose_scope" -> handleAuditSynthesizeComposeScope(project, targets)
            "architect_context" -> handleAuditArchitectContext(args, project, targets)
            "verify_architecture" -> handleAuditVerifyArchitecture(args, project, targets)
            "check_descriptions" -> handleAuditCheckDescriptions(args, project, targets)
            "verify_remediation" -> handleAuditVerifyRemediation(args, project, targets)
            "dead_code" -> handleAuditDeadCode(project, targets)
            "record_memory" -> handleAuditRecordMemory(args, project, targets)
            "remedy_architecture", "fix_architecture", "fix", "remedy" -> handleAuditRemedyArchitecture(args, project, targets)
            "migrate_tools", "check_legacy_tools" -> handleAuditMigrateTools(args, targets)
            "audit_skills" -> handleAuditSkills(project, targets)
            else -> throw IllegalArgumentException("Unsupported atlas_audit action: $action")
        }
    }

    private fun handleAuditSyncIndex(
        project: String,
        targets: List<String>,
        stabilityConfigPath: String?,
        compilerReportsDir: String?,
        traceLogPath: String?
    ): JsonObject {
        val hasComposeArtifacts = stabilityConfigPath != null || compilerReportsDir != null || traceLogPath != null
        val subpaths = if (targets.isNotEmpty()) targets else null
        return if (hasComposeArtifacts) {
            indexComposeArtifacts(
                projectId = project,
                subpaths = subpaths,
                stabilityConfigPath = stabilityConfigPath,
                compilerReportsDir = compilerReportsDir,
                traceLogPath = traceLogPath
            )
        } else {
            indexWorkspace(project, subpaths = subpaths)
        }
    }

    private fun handleAuditSynthesize3dScope(project: String, targets: List<String>): JsonObject {
        val rec = synthesizer.synthesizeScope(project, targets)
        val baseObj = json.encodeToJsonElement(rec).jsonObject
        val scopeAnnotations = rec.seedTargets.flatMap { target ->
            val node = store.getNode(project, target)
            if (node != null) memory.getActiveAnnotations(project, node.nodeId)
            else emptyList()
        }
        return buildJsonObject {
            baseObj.forEach { (k, v) -> put(k, v) }
            put("yaml_block", rec.toYamlBlock())
            put("annotations", json.encodeToJsonElement(scopeAnnotations))
        }
    }

    private fun handleAuditSynthesizeComposeScope(project: String, targets: List<String>): JsonObject {
        val scope = synthesizer.synthesizeComposeScope(project, targets)
        val baseObj = json.encodeToJsonElement(scope).jsonObject
        val scopeAnnotations = scope.sharedStableModels.flatMap { modelId ->
            memory.getActiveAnnotations(project, modelId)
        }
        return buildJsonObject {
            baseObj.forEach { (k, v) -> put(k, v) }
            put("annotations", json.encodeToJsonElement(scopeAnnotations))
        }
    }

    private fun handleAuditArchitectContext(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 2
        val result = architectContextEngine.synthesizeContext(
            projectId = project,
            seedTargets = targets,
            depth = depth
        )
        return json.encodeToJsonElement(result).jsonObject
    }

    private fun handleAuditVerifyArchitecture(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val descMode = args["description_mode"]?.jsonPrimitive?.contentOrNull ?: "domain-only"
        val report = auditor.runFullAudit(
            projectId = project,
            pathPrefixes = if (targets.isNotEmpty()) targets else null,
            descriptionMode = descMode
        )
        return json.encodeToJsonElement(report).jsonObject
    }

    private fun handleAuditCheckDescriptions(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val descMode = args["description_mode"]?.jsonPrimitive?.contentOrNull ?: "domain-only"
        val suggestions = auditor.checkTopLevelDescriptions(
            projectId = project,
            pathPrefixes = targets,
            mode = descMode
        )
        return buildJsonObject {
            put("project", project)
            put("mode", descMode)
            put("total_suggestions", suggestions.size)
            put("suggestions", json.encodeToJsonElement(suggestions))
        }
    }

    private fun handleAuditSkills(project: String, targets: List<String>): JsonObject {
        val violations = auditor.checkSkillViolations(project, if (targets.isNotEmpty()) targets else null)
        val passed = violations.isEmpty()
        return buildJsonObject {
            put("project", project)
            put("passed", passed)
            put("violations_count", violations.size)
            put("violations", json.encodeToJsonElement(violations))
        }
    }

    private fun handleAuditCompose(
        project: String,
        targets: List<String>,
        stabilityConfigPath: String?,
        compilerReportsDir: String?,
        traceLogPath: String?,
        strongSkipping: Boolean
    ): JsonObject {
        if (stabilityConfigPath != null || compilerReportsDir != null || traceLogPath != null) {
            indexComposeArtifacts(
                projectId = project,
                subpaths = if (targets.isNotEmpty()) targets else null,
                stabilityConfigPath = stabilityConfigPath,
                compilerReportsDir = compilerReportsDir,
                traceLogPath = traceLogPath
            )
        }
        val report = auditor.runComposeAudit(project, strongSkipping = strongSkipping)
        return json.encodeToJsonElement(report).jsonObject
    }

    private fun handleAuditComposeDoctor(args: JsonObject, project: String, targets: List<String>, limit: Int): JsonObject {
        val targetSym = targets.firstOrNull() ?: args["target"]?.jsonPrimitive?.contentOrNull
        val report = composeEngine.runDoctor(project, target = targetSym, limit = limit, workspaceRoot = workspaceRoot)
        return json.encodeToJsonElement(report).jsonObject
    }

    private fun handleAuditVerifyRemediation(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val filePath = args["file_path"]?.jsonPrimitive?.contentOrNull
            ?: targets.firstOrNull()
            ?: args["target"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val beforeSnippet = args["before_snippet"]?.jsonPrimitive?.content ?: ""
        val afterSnippet = args["after_snippet"]?.jsonPrimitive?.content ?: ""
        val ruleId = args["rule_id"]?.jsonPrimitive?.content ?: "GENERIC"
        val targetNodeId = args["target_node_id"]?.jsonPrimitive?.contentOrNull
        val writeSiteSafe = args["write_site_safe"]?.jsonPrimitive?.booleanOrNull ?: true
        val dryRun = args["dry_run"]?.jsonPrimitive?.booleanOrNull ?: true
        val testCmd = args["test_command"]?.jsonPrimitive?.contentOrNull
        val res = resolveServerForProject(project).remediationVerifier.verifyAndApply(
            projectSlug = project,
            relPath = filePath,
            beforeSnippet = beforeSnippet,
            afterSnippet = afterSnippet,
            ruleId = ruleId,
            targetNodeId = targetNodeId,
            writeSiteSafe = writeSiteSafe,
            dryRun = dryRun,
            testCommand = testCmd
        )
        return json.encodeToJsonElement(res).jsonObject
    }

    private fun handleAuditDeadCode(project: String, targets: List<String>): JsonObject {
        val deadNodes = auditor.detectDeadCode(project, pathPrefixes = if (targets.isNotEmpty()) targets else null)
        return buildJsonObject {
            put("project", project)
            put("dead_code_count", deadNodes.size)
            put("dead_nodes", json.encodeToJsonElement(deadNodes))
        }
    }

    private fun handleAuditRecordMemory(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val targetStr = if (targets.isNotEmpty()) targets[0] else ""
        val node = store.getNode(project, targetStr)
        val nodeId = node?.nodeId ?: targetStr
        val outcome = args["outcome"]?.jsonPrimitive?.content ?: "useful"
        val note = args["note"]?.jsonPrimitive?.content ?: ""
        val annId = memory.recordOutcome(project, nodeId, outcome, note)
        return buildJsonObject {
            put("annotation_id", annId)
            put("node_id", nodeId)
            put("outcome", outcome)
        }
    }

    private fun handleAuditRemedyArchitecture(args: JsonObject, project: String, targets: List<String>): JsonObject {
        val targetList = if (targets.isNotEmpty()) {
            targets
        } else {
            args["path"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) } ?: emptyList()
        }
        val ruleFilter = args["rule"]?.jsonPrimitive?.contentOrNull ?: args["rule_id"]?.jsonPrimitive?.contentOrNull
        val dryRun = args["dry_run"]?.jsonPrimitive?.booleanOrNull ?: (!args["apply"]?.jsonPrimitive?.booleanOrNull.let { it == true })
        val split = args["split"]?.jsonPrimitive?.booleanOrNull ?: true
        val makePrivate = args["make_private"]?.jsonPrimitive?.booleanOrNull ?: false
        val testCmd = args["test_command"]?.jsonPrimitive?.contentOrNull
        val limitArg = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
        val active = resolveServerForProject(project)
        val report = active.remediationEngine.planAndRemediate(
            projectId = project,
            targetPaths = targetList,
            ruleFilter = ruleFilter,
            dryRun = dryRun,
            splitBoost003 = split,
            makePrivateBoost003 = makePrivate,
            testCommand = testCmd,
            limit = limitArg
        )
        if (!dryRun) {
            active.indexer.indexWorkspace(project, force = true)
        }
        return json.encodeToJsonElement(report).jsonObject
    }

    private fun handleAuditMigrateTools(args: JsonObject, targets: List<String>): JsonObject {
        val apply = args["apply"]?.jsonPrimitive?.booleanOrNull ?: false
        val scanPaths = if (targets.isNotEmpty()) {
            targets
        } else {
            val p = args["path"]?.jsonPrimitive?.contentOrNull
                ?: args["file_path"]?.jsonPrimitive?.contentOrNull
                ?: workspaceRoot
            listOf(p)
        }
        val report = ToolMigrationEngine.scanAndMigrate(scanPaths, apply = apply)
        return json.encodeToJsonElement(report).jsonObject
    }

    private fun handleAuditComplexity(
        args: JsonObject,
        project: String,
        targets: List<String>
    ): JsonObject {
        val threshold = args["threshold"]?.jsonPrimitive?.intOrNull ?: 10
        val maxCyclomatic = args["max_cyclomatic"]?.jsonPrimitive?.intOrNull ?: 15
        val warnParams = extractIntOrParsed(args, "warn_params", "max_parameters") ?: 5
        val maxParams = extractIntOrParsed(args, "max_params") ?: 7
        val composeWarnParams = extractIntOrParsed(args, "compose_warn_params") ?: 10
        val composeMaxParams = extractIntOrParsed(args, "compose_max_params") ?: 14
        val checkParams = args["check_params"]?.jsonPrimitive?.booleanOrNull ?: true
        val thresholds = ArchitectureAuditor.ComplexityThresholds(
            maxCognitive = threshold,
            maxCyclomatic = maxCyclomatic,
            warnParams = warnParams,
            maxParams = maxParams,
            composeWarnParams = composeWarnParams,
            composeMaxParams = composeMaxParams,
            checkParams = checkParams
        )
        val violations = auditor.checkComplexityViolations(
            projectId = project,
            pathPrefixes = if (targets.isNotEmpty()) targets else null,
            thresholds = thresholds
        )
        val (resolvedRatchet, resolvedHighRatchet) = resolveComplexityRatchets(args, project)
        return buildComplexityAuditResponse(project, threshold, maxCyclomatic, violations, resolvedRatchet, resolvedHighRatchet)
    }

    private fun extractIntOrParsed(args: JsonObject, vararg keys: String): Int? {
        for (key in keys) {
            val prim = args[key]?.jsonPrimitive ?: continue
            val direct = prim.intOrNull
            if (direct != null) return direct
            val parsed = prim.contentOrNull?.toIntOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun resolveExplicitBaselineFile(args: JsonObject): String? {
        val prim = args["baseline"]?.jsonPrimitive ?: return null
        if (prim.intOrNull != null) return null
        if (prim.contentOrNull?.toIntOrNull() != null) return null
        return prim.contentOrNull
    }

    private fun resolveComplexityRatchets(args: JsonObject, project: String): Pair<Int?, Int?> {
        val rRatchet = extractIntOrParsed(args, "ratchet", "max_violations", "baseline")
        val rHigh = extractIntOrParsed(args, "high_ratchet", "max_high_violations")
        if (rRatchet != null && rHigh != null) return Pair(rRatchet, rHigh)

        val explicitFile = resolveExplicitBaselineFile(args)
        return findBaselineInPaths(project, explicitFile, rRatchet, rHigh)
    }

    private fun candidateBaselinePaths(explicitFile: String?): List<String> {
        return listOfNotNull(
            explicitFile,
            "$workspaceRoot/.atlas/complexity-baseline.json",
            "$workspaceRoot/complexity-baseline.json",
            ".atlas/complexity-baseline.json",
            "complexity-baseline.json"
        ).distinct()
    }

    private fun isBothResolved(ratchet: Int?, high: Int?): Boolean {
        return ratchet != null || high != null
    }

    private fun tryReadBaseline(
        cPath: String,
        project: String,
        ratchet: Int?,
        high: Int?
    ): Pair<Int?, Int?> {
        val resolvedPath = resolveExistingPath(cPath) ?: return Pair(ratchet, high)
        val parsed = parseBaselineJson(resolvedPath, project) ?: return Pair(ratchet, high)
        val finalRatchet = ratchet ?: parsed.first
        val finalHigh = high ?: parsed.second
        return Pair(finalRatchet, finalHigh)
    }

    private fun findBaselineInPaths(
        project: String,
        explicitFile: String?,
        initialRatchet: Int?,
        initialHigh: Int?
    ): Pair<Int?, Int?> {
        var ratchet = initialRatchet
        var high = initialHigh
        val candidatePaths = candidateBaselinePaths(explicitFile)

        for (cPath in candidatePaths) {
            val (newRatchet, newHigh) = tryReadBaseline(cPath, project, ratchet, high)
            ratchet = newRatchet
            high = newHigh
            if (isBothResolved(ratchet, high)) break
        }
        return Pair(ratchet, high)
    }

    private fun resolveExistingPath(cPath: String): String? {
        if (PlatformFiles.exists(cPath)) return cPath
        if (!cPath.startsWith("/") && PlatformFiles.exists("$workspaceRoot/$cPath")) {
            return "$workspaceRoot/$cPath"
        }
        return null
    }

    private fun extractIntFromObject(obj: JsonObject, vararg keys: String): Int? {
        for (key in keys) {
            val v = obj[key]?.jsonPrimitive?.intOrNull
            if (v != null) return v
        }
        return null
    }

    private fun parseBaselineJson(resolvedPath: String, project: String): Pair<Int?, Int?>? {
        return try {
            val text = PlatformFiles.readText(resolvedPath)
            val parsed = json.parseToJsonElement(text).jsonObject
            val fileProject = parsed["project"]?.jsonPrimitive?.contentOrNull
            if (fileProject != null && fileProject != project) return null
            val r = extractIntFromObject(parsed, "ratchet", "total_violations", "max_violations")
            val h = extractIntFromObject(parsed, "high_ratchet", "high_severity_count", "max_high_violations")
            Pair(r, h)
        } catch (e: Exception) {
            PlatformFiles.logWarning("AtlasMcpServer", "Failed to parse complexity baseline from $resolvedPath: ${e.message}")
            null
        }
    }

    private fun buildComplexityAuditResponse(
        project: String,
        threshold: Int,
        maxCyclomatic: Int,
        violations: List<ComplexityViolation>,
        resolvedRatchet: Int?,
        resolvedHighRatchet: Int?
    ): JsonObject {
        val blockingViolations = violations.filter { it.ruleId == "COMPLEXITY-001" }
        val advisoryViolations = violations.filter { it.ruleId != "COMPLEXITY-001" }
        val highCount = blockingViolations.count { it.severity == "HIGH" }
        val warningCount = blockingViolations.count { it.severity == "WARNING" }
        val totalPassed = if (resolvedRatchet != null) blockingViolations.size <= resolvedRatchet else blockingViolations.isEmpty()
        val highPassed = if (resolvedHighRatchet != null) highCount <= resolvedHighRatchet else true
        val passed = totalPassed && highPassed

        return buildJsonObject {
            put("project", project)
            put("passed", passed)
            put("threshold", threshold)
            put("max_cyclomatic", maxCyclomatic)
            put("total_violations", blockingViolations.size)
            put("high_severity_count", highCount)
            put("warning_count", warningCount)
            put("advisory_count", advisoryViolations.size)
            if (resolvedRatchet != null) {
                put("ratchet", resolvedRatchet)
                put("baseline_delta", blockingViolations.size - resolvedRatchet)
            }
            if (resolvedHighRatchet != null) {
                put("high_ratchet", resolvedHighRatchet)
                put("high_delta", highCount - resolvedHighRatchet)
            }
            put("violations", json.encodeToJsonElement(violations))
        }
    }

    private fun handleAuditComplexityDoctor(
        args: JsonObject,
        project: String,
        targets: List<String>,
        limit: Int
    ): JsonObject {
        val targetSym = targets.firstOrNull() ?: args["target"]?.jsonPrimitive?.contentOrNull
        val apply = args["apply"]?.jsonPrimitive?.booleanOrNull ?: false
        val testCmd = args["test_command"]?.jsonPrimitive?.contentOrNull
        val minCognitive = args["threshold"]?.jsonPrimitive?.intOrNull ?: 10
        val report = complexityDoctor.runDoctor(
            projectId = project,
            target = targetSym,
            limit = limit,
            minCognitive = minCognitive
        )
        val finalReport = if (apply) {
            complexityDoctor.applyRemediations(report, dryRun = false, testCommand = testCmd)
        } else {
            report
        }
        return json.encodeToJsonElement(finalReport).jsonObject
    }

    private fun handleFigmaAudit(args: JsonObject): JsonObject {
        val rawFileKey = args["file_key"]?.jsonPrimitive?.content
            ?: args["fileKey"]?.jsonPrimitive?.content
            ?: args["target"]?.jsonPrimitive?.content
            ?: (args["targets"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content)
            ?: ""
        if (rawFileKey.isEmpty()) {
            return buildJsonObject {
                put("error", "Missing required 'file_key' parameter for figma_audit")
            }
        }
        val fileKey = UrlTargetResolver.parseFigmaUrl(rawFileKey)?.fileKey ?: rawFileKey
        val result = figmaAuditor.audit(fileKey)
        return json.encodeToJsonElement(result).jsonObject
    }

    private fun extractRawFileKey(args: JsonObject): String {
        val direct = args["file_key"]?.jsonPrimitive?.content
        if (direct != null) return direct
        val camel = args["fileKey"]?.jsonPrimitive?.content
        if (camel != null) return camel
        val target = args["target"]?.jsonPrimitive?.content
        if (target != null) return target
        val firstTarget = args["targets"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
        if (firstTarget != null) return firstTarget
        return ""
    }

    private fun extractRawSeeds(args: JsonObject): List<String> {
        val seedsArray = args["seeds"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (seedsArray != null) return seedsArray
        val targetsArray = args["targets"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (targetsArray != null) return targetsArray
        val singleSeed = args["seed"]?.jsonPrimitive?.content
        if (singleSeed != null) return listOf(singleSeed)
        val hasFileKey = args.containsKey("file_key") || args.containsKey("fileKey")
        if (hasFileKey) {
            val target = args["target"]?.jsonPrimitive?.content
            if (target != null) return listOf(target)
        }
        return emptyList()
    }

    private fun normalizeFigmaSeeds(rawSeeds: List<String>, fallbackNodeId: String?): List<String> {
        if (rawSeeds.isEmpty() && fallbackNodeId != null) {
            return listOf(fallbackNodeId)
        }
        return rawSeeds.map { seed ->
            val parsedUrl = UrlTargetResolver.parseFigmaUrl(seed)
            if (parsedUrl?.nodeId != null) parsedUrl.nodeId else UrlTargetResolver.normalizeFigmaNodeId(seed)
        }
    }

    private fun handleFigmaScope(args: JsonObject): JsonObject {
        val project = args["project"]?.jsonPrimitive?.content ?: "organisation"
        val rawFileKey = extractRawFileKey(args)
        if (rawFileKey.isEmpty()) {
            return buildJsonObject {
                put("error", "Missing required 'file_key' parameter for figma_scope")
            }
        }
        val parsedFileUrl = UrlTargetResolver.parseFigmaUrl(rawFileKey)
        val fileKey = if (parsedFileUrl?.fileKey != null) parsedFileUrl.fileKey else rawFileKey
        val rawSeeds = extractRawSeeds(args)
        val seeds = normalizeFigmaSeeds(rawSeeds, parsedFileUrl?.nodeId)
        val scope = figmaAuditor.synthesizeFigmaScope(project, fileKey, seeds)
        return json.encodeToJsonElement(scope).jsonObject
    }

    private fun resolveProjectFromArgs(args: JsonObject): String {
        val explicitProject = args["project"]?.jsonPrimitive?.contentOrNull
        if (!explicitProject.isNullOrBlank()) return explicitProject
        val explicitPath = args["projectPath"]?.jsonPrimitive?.contentOrNull
            ?: args["project-path"]?.jsonPrimitive?.contentOrNull
        if (!explicitPath.isNullOrBlank()) {
            val clean = explicitPath.trimEnd('/', '\\')
            return if (clean.contains('/')) clean.substringAfterLast('/') else clean
        }
        return "organisation"
    }

    private fun handleCodegraphExplore(args: JsonObject): JsonObject {
        val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val maxFiles = args["maxFiles"]?.jsonPrimitive?.intOrNull ?: 12
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.explore(project, query, maxFiles)
    }

    private fun handleCodegraphNode(args: JsonObject): JsonObject {
        val symbol = args["symbol"]?.jsonPrimitive?.contentOrNull
        val file = args["file"]?.jsonPrimitive?.contentOrNull
        val includeCode = args["includeCode"]?.jsonPrimitive?.booleanOrNull ?: false
        val offset = args["offset"]?.jsonPrimitive?.intOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull
        val symbolsOnly = args["symbolsOnly"]?.jsonPrimitive?.booleanOrNull ?: false
        val line = args["line"]?.jsonPrimitive?.intOrNull
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.node(project, symbol, file, includeCode, offset, limit, symbolsOnly, line)
    }

    private fun handleCodegraphSearch(args: JsonObject): JsonObject {
        val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val kind = args["kind"]?.jsonPrimitive?.contentOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 10
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.search(project, query, kind, limit)
    }

    private fun handleCodegraphCallers(args: JsonObject): JsonObject {
        val symbol = args["symbol"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val file = args["file"]?.jsonPrimitive?.contentOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.callers(project, symbol, file, limit)
    }

    private fun handleCodegraphCallees(args: JsonObject): JsonObject {
        val symbol = args["symbol"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val file = args["file"]?.jsonPrimitive?.contentOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.callees(project, symbol, file, limit)
    }

    private fun handleCodegraphImpact(args: JsonObject): JsonObject {
        val symbol = args["symbol"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val file = args["file"]?.jsonPrimitive?.contentOrNull
        val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 2
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.impact(project, symbol, file, depth)
    }

    private fun handleCodegraphStatus(args: JsonObject): JsonObject {
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.status(project)
    }

    private fun handleCodegraphFiles(args: JsonObject): JsonObject {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
        val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull
        val format = args["format"]?.jsonPrimitive?.contentOrNull ?: "tree"
        val project = resolveProjectFromArgs(args)
        return codegraphEngine.files(project, path, pattern, format)
    }

    private fun enforceSpillover(projectId: String, payload: JsonObject): JsonObject {
        val rawJson = json.encodeToString(payload)
        val byteLen = rawJson.encodeToByteArray().size
        if (byteLen <= SPILLOVER_THRESHOLD_BYTES) {
            return payload
        }

        val ts = time(null) * 1000L
        val seq = ++spilloverSeq
        val safeProject = PlatformFiles.sanitizeFileName(projectId)
        val spillFile = "$artifactsDir/spillover_${safeProject}_${ts}_$seq.json"
        PlatformFiles.writeSecureText(spillFile, rawJson)

        val gods = store.getGodNodes(projectId, topN = 5).map { it.name }
        return buildJsonObject {
            put("spillover", true)
            put("byte_length", byteLen)
            put("threshold_bytes", SPILLOVER_THRESHOLD_BYTES)
            put("artifact_uri", spillFile)
            put("top_central_nodes", json.encodeToJsonElement(gods))
            put("summary", "Payload ($byteLen bytes) exceeded 32KB ceiling and was written to $spillFile.")
        }
    }

    private class JsonRpcException(
        val code: Int,
        override val message: String,
        val id: JsonPrimitive? = null
    ) : Exception(message)

    private class JsonRpcRequest(
        val id: JsonPrimitive?,
        val hasId: Boolean,
        val method: String,
        val params: JsonObject
    )

    private fun jsonRpcError(id: JsonPrimitive?, code: Int, message: String): String {
        return json.encodeToString(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        })
    }

    private fun jsonRpcResult(id: JsonPrimitive?, resultBuilder: JsonObjectBuilder.() -> Unit): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("result", resultBuilder)
        }
    }

    private fun validateReqId(req: JsonObject): Pair<JsonPrimitive?, Boolean> {
        val rawId = req["id"]
        val hasId = req.containsKey("id")
        if (hasId && rawId !is JsonPrimitive) {
            throw JsonRpcException(-32600, "Invalid Request: 'id' must be a primitive string, number, or null")
        }
        return Pair(rawId as? JsonPrimitive, hasId)
    }

    private fun extractMethod(req: JsonObject, hasId: Boolean, reqId: JsonPrimitive?): String? {
        val methodPrimitive = req["method"] as? JsonPrimitive
        if (methodPrimitive != null && methodPrimitive.isString) {
            return methodPrimitive.content
        }
        if (!hasId) return null
        throw JsonRpcException(-32600, "Invalid Request: 'method' must be a string", reqId)
    }

    private fun extractParams(req: JsonObject, hasId: Boolean, reqId: JsonPrimitive?): JsonObject? {
        val params = req["params"] ?: return buildJsonObject {}
        if (params is JsonObject) return params
        if (!hasId) return null
        throw JsonRpcException(-32602, "Invalid params: 'params' must be an object", reqId)
    }

    private fun parseJsonRpcRequest(trimmed: String): JsonRpcRequest? {
        val reqElement = try {
            json.parseToJsonElement(trimmed)
        } catch (_: Throwable) {
            throw JsonRpcException(-32700, "Parse error")
        }
        val req = reqElement as? JsonObject
            ?: throw JsonRpcException(-32600, "Invalid Request: expected JSON object")

        val (reqId, hasId) = validateReqId(req)
        val method = extractMethod(req, hasId, reqId) ?: return null
        val params = extractParams(req, hasId, reqId) ?: return null

        return JsonRpcRequest(reqId, hasId, method, params)
    }

    private fun handleInitialize(reqId: JsonPrimitive?): JsonObject {
        return jsonRpcResult(reqId) {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {
                putJsonObject("tools") {}
            }
            putJsonObject("serverInfo") {
                put("name", "atlas")
                put("version", "0.7.0")
            }
        }
    }

    private fun handleToolsList(reqId: JsonPrimitive?): JsonObject {
        return jsonRpcResult(reqId) {
            put("tools", json.encodeToJsonElement(toolDefinitions()))
        }
    }

    private fun buildToolCallSuccess(reqId: JsonPrimitive?, out: JsonObject): JsonObject {
        val isToolErr = out.containsKey("error")
        return jsonRpcResult(reqId) {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    val rawText = out["text"]?.jsonPrimitive?.contentOrNull
                    put("text", rawText ?: json.encodeToString(out))
                }
            }
            if (isToolErr) {
                put("isError", true)
            }
        }
    }

    private fun buildToolCallFailure(reqId: JsonPrimitive?, e: Throwable): JsonObject {
        return jsonRpcResult(reqId) {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", json.encodeToString(buildJsonObject {
                        put("error", e.message ?: "Unknown error")
                    }))
                }
            }
            put("isError", true)
        }
    }

    private fun extractToolName(params: JsonObject): String? {
        val primitive = params["name"] as? JsonPrimitive
        return if (primitive != null && primitive.isString) primitive.content else null
    }

    private fun extractToolArgs(params: JsonObject): Pair<JsonObject?, Boolean> {
        val args = params["arguments"] ?: return Pair(buildJsonObject {}, true)
        val obj = args as? JsonObject
        return Pair(obj, obj != null)
    }

    private fun executeToolCall(tName: String, tArgs: JsonObject, reqId: JsonPrimitive?): String {
        return try {
            val out = callTool(tName, tArgs)
            json.encodeToString(buildToolCallSuccess(reqId, out))
        } catch (e: Throwable) {
            json.encodeToString(buildToolCallFailure(reqId, e))
        }
    }

    private fun handleToolsCall(reqId: JsonPrimitive?, params: JsonObject, hasId: Boolean): String? {
        val tName = extractToolName(params)
        if (tName == null) {
            if (!hasId) return null
            return jsonRpcError(reqId, -32602, "Invalid params: 'name' must be a string")
        }

        val (tArgs, isValidArgs) = extractToolArgs(params)
        if (!isValidArgs || tArgs == null) {
            if (!hasId) return null
            return jsonRpcError(reqId, -32602, "Invalid params: 'arguments' must be an object")
        }

        if (!hasId) {
            try { callTool(tName, tArgs) } catch (_: Throwable) {}
            return null
        }

        return executeToolCall(tName, tArgs, reqId)
    }

    private fun dispatchJsonRpcMethod(req: JsonRpcRequest): String? {
        if (!req.hasId) {
            if (req.method == "tools/call") {
                handleToolsCall(null, req.params, hasId = false)
            }
            return null
        }

        return when (req.method) {
            "initialize" -> json.encodeToString(handleInitialize(req.id))
            "ping" -> json.encodeToString(jsonRpcResult(req.id) {})
            "notifications/initialized", "notifications/cancelled" -> null
            "tools/list" -> json.encodeToString(handleToolsList(req.id))
            "tools/call" -> handleToolsCall(req.id, req.params, hasId = true)
            else -> jsonRpcError(req.id, -32601, "Method not found: ${req.method}")
        }
    }

    fun processJsonRpcMessage(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        return try {
            val req = parseJsonRpcRequest(trimmed) ?: return null
            dispatchJsonRpcMethod(req)
        } catch (e: JsonRpcException) {
            jsonRpcError(e.id, e.code, e.message)
        } catch (e: Throwable) {
            jsonRpcError(null, -32603, "Internal error: ${e.message ?: "Unexpected error"}")
        }
    }

    fun serveStdio() {
        while (true) {
            val line = try {
                readlnOrNull() ?: break
            } catch (_: Throwable) {
                break
            }
            try {
                val resp = processJsonRpcMessage(line)
                if (resp != null) {
                    println(resp)
                    fflush(stdout)
                }
            } catch (e: Throwable) {
                try {
                    val err = json.encodeToString(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", JsonNull)
                        putJsonObject("error") {
                            put("code", -32603)
                            put("message", "Internal error: ${e.message ?: "Unexpected error"}")
                        }
                    })
                    println(err)
                    fflush(stdout)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
