package atlas.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema definitions for Atlas and Codegraph MCP tools.
 */
internal object McpToolSchemas {

    fun toolDefinitions(enableCodegraphTools: Boolean): List<JsonObject> {
        val baseTools = listOf(
            buildJsonObject {
                put("name", "atlas_query")
                put("description", "Query indexed polyglot AST symbols, structural skeletons, explore semantics, callers, callees, blast radius, or Compose stability.")
                put("inputSchema", querySchema())
            },
            buildJsonObject {
                put("name", "atlas_audit")
                put("description", "Audit architecture layers, Compose stability, complexity doctor, synthesize 3D scopes, or assemble architect context.")
                put("inputSchema", auditSchema())
            },
            buildJsonObject {
                put("name", "figma_audit")
                put("description", "Run 12 deterministic design invariant audits and evaluate Gate 1 maturity compliance.")
                put("inputSchema", figmaAuditSchema())
            },
            buildJsonObject {
                put("name", "figma_scope")
                put("description", "Synthesize Figma 3D Concurrency Scope (spatial canvas bounds, shared components, token layers).")
                put("inputSchema", figmaScopeSchema())
            },
            buildJsonObject {
                put("name", "atlas_outline")
                put("description", "Extract compact structural outline (classes, functions, headings) with line numbers bounded strictly to 400 bytes.")
                put("inputSchema", outlineSchema())
            }
        )

        if (!enableCodegraphTools) {
            return baseTools
        }

        return baseTools + codegraphToolDefinitions()
    }

    private fun querySchema(): JsonObject = buildJsonObject {
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
                    add("rfc_ground")
                    add("rfc-ground")
                    add("interview_context")
                    add("interview-context")
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

    private fun auditSchema(): JsonObject = buildJsonObject {
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
                    add("compose_scope")
                    add("verify_remediation")
                    add("audit_complexity")
                    add("complexity_doctor")
                    add("migrate_tools")
                    add("architect_context")
                    add("audit_skills")
                    add("spike")
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
            putJsonObject("param_high_ratchet") {
                put("type", "integer")
                put("description", "Maximum allowed HIGH severity parameter count violations (COMPLEXITY-002-PARAMS) for audit_complexity.")
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
            putJsonObject("hypothesis") {
                put("type", "string")
                putJsonArray("enum") {
                    add("decoupling")
                    add("clean-arch")
                    add("blast-ceiling")
                }
                put("description", "Topological spike hypothesis to evaluate (decoupling, clean-arch, blast-ceiling; default: decoupling).")
            }
            putJsonObject("from") {
                put("type", "string")
                put("description", "Source symbol, module, or file identifier for decoupling hypothesis.")
            }
            putJsonObject("to") {
                put("type", "string")
                put("description", "Target symbol, module, or file identifier for decoupling hypothesis.")
            }
            putJsonObject("forbidden_layer") {
                put("type", "string")
                put("description", "Forbidden layer or tier identifier for clean-arch spike (e.g. 'pragmatic:presentation').")
            }
            putJsonObject("max_files") {
                put("type", "integer")
                put("description", "Maximum blast-radius file ceiling for blast-ceiling spike (default: 20).")
            }
        }
        putJsonArray("required") {
            add("action")
            add("project")
        }
    }

    private fun figmaAuditSchema(): JsonObject = buildJsonObject {
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

    private fun figmaScopeSchema(): JsonObject = buildJsonObject {
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

    private fun outlineSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("file") {
                put("type", "string")
                put("description", "Relative or absolute file path to inspect and outline.")
            }
            putJsonObject("project") {
                put("type", "string")
                put("description", "Optional project slug or workspace identifier (default: organisation).")
            }
            putJsonObject("workspace") {
                put("type", "string")
                put("description", "Optional absolute workspace root path to resolve target file.")
            }
        }
        putJsonArray("required") {
            add("file")
        }
    }

    private fun codegraphExploreSchema(): JsonObject = buildJsonObject {
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

    private fun codegraphNodeSchema(): JsonObject = buildJsonObject {
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

    private fun codegraphSearchSchema(): JsonObject = buildJsonObject {
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

    private fun codegraphCallersSchema(): JsonObject = buildJsonObject {
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

    private fun codegraphCalleesSchema(): JsonObject = buildJsonObject {
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

    private fun codegraphImpactSchema(): JsonObject = buildJsonObject {
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

    private fun codegraphStatusSchema(): JsonObject = buildJsonObject {
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

    private fun codegraphFilesSchema(): JsonObject = buildJsonObject {
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

    private fun codegraphToolDefinitions(): List<JsonObject> = listOf(
        buildJsonObject {
            put("name", "codegraph_explore")
            put("description", "Explore relevant code symbols, live line-numbered source, and caller relationships in one round-trip.")
            put("inputSchema", codegraphExploreSchema())
        },
        buildJsonObject {
            put("name", "codegraph_node")
            put("description", "Read whole file with line numbers or inspect symbol details, body, callers, and callees.")
            put("inputSchema", codegraphNodeSchema())
        },
        buildJsonObject {
            put("name", "codegraph_search")
            put("description", "Quick symbol search by name and kind. Returns locations and signatures.")
            put("inputSchema", codegraphSearchSchema())
        },
        buildJsonObject {
            put("name", "codegraph_callers")
            put("description", "List functions and methods that call a symbol.")
            put("inputSchema", codegraphCallersSchema())
        },
        buildJsonObject {
            put("name", "codegraph_callees")
            put("description", "List functions and methods that a symbol calls.")
            put("inputSchema", codegraphCalleesSchema())
        },
        buildJsonObject {
            put("name", "codegraph_impact")
            put("description", "List symbols affected by changing a symbol before refactoring.")
            put("inputSchema", codegraphImpactSchema())
        },
        buildJsonObject {
            put("name", "codegraph_status")
            put("description", "Index health check reporting indexed files, nodes, edges, and database location.")
            put("inputSchema", codegraphStatusSchema())
        },
        buildJsonObject {
            put("name", "codegraph_files")
            put("description", "Indexed file tree with pattern and directory filtering.")
            put("inputSchema", codegraphFilesSchema())
        }
    )
}
