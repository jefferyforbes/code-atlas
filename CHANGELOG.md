# Changelog

All notable changes to Atlas (`code-atlas`) are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.7.0] - 2026-10-20

### Added
- **Agent Skill Graph Extraction (`REFERENCES_FILE`, `EXECUTES_SCRIPT`)**: `VaultPlanBridge` now resolves relative companion documentation links (e.g. `./references/playbook.md`, `../../knowledge/...`) from `SKILL.md` files and emits `REFERENCES_FILE` edges targeting file/note nodes in the graph. Script and binary invocations (`.py`, `.sh`, `.kexe`, `scripts/`, `bin/`) in inline backticks and fenced code blocks are extracted as `EXECUTES_SCRIPT` edges with line coordinates and confidence scores (#145).
- **Relative Path Resolution for Skill Dependencies**: `DEPENDS_ON_SKILL` edges now resolve via full relative path normalization (e.g. `../plan/SKILL.md`), not just suffix matching, closing a structural gap in the agent skill dependency graph (#145).
- **Unresolvable Reference Tracking**: Local file references that do not match any indexed graph node are still emitted as `EXTRACTED_LOCAL_FILE_REF` edges (confidence 0.8) targeting synthetic node IDs, providing a foundation for `SKILL-001` broken-reference auditing in Phase 2 (#145).

## [0.6.0] - 2026-10-19

### Added
- **Complexity Ratchet Gate & CI Quality Enforcement (`atlas audit --complexity`)**: Implemented persistent complexity baselines (`--baseline <file>`, `--save-baseline <file>`) preventing cyclomatic/cognitive complexity regressions, paired with automated CI quality gate enforcement in GitHub Actions (#48, #46).
- **Vault Knowledge Graph Expansion**: Linear token parsing and indexing for Obsidian vault tags, skills, and slash commands in `VaultPlanBridge`, materializing `VAULT_TAG`, `SKILL`, and `COMMAND` nodes with incoming/outgoing reference edges (#43, #44).
- **High-Precision Benchmark Script (`scripts/benchmark.sh`)**: Microsecond-accurate native benchmark suite measuring cold start (~3ms), warm query, full index latency, and MCP roundtrip (#49).
- **Client MCP Configuration Templates**: Ready-to-use client configurations for Claude Desktop (`claude_desktop_config.json`) and Cursor (`cursor_mcp.json`) (#49).

### Changed
- **Core Architecture & Naming Alignment**: Renamed `CodeGraphCli` and `CodeGraphMcpServer` to `AtlasCli` and `AtlasMcpServer` across the entire codebase and test suites (#42).
- **CI/CD Security Hardening**: Secured GitHub Actions runner execution against malicious fork PRs with least-privilege token permissions (#46).
- **Kotlin Badge**: Updated Kotlin/Native status badge to 2.4.21 (#49).

### Removed
- **Legacy MCP Tools**: Completely removed deprecated `codegraph_query` and `codegraph_audit` tools from the MCP server in favor of canonical `atlas_query` and `atlas_audit` (#41).

### Fixed
- **JSON-RPC Stdio Reliability & Panic Safety**: Hardened JSON-RPC parsing with standard error codes, prevented stdio write panics on broken pipes (EPIPE), and enforced strict single-line NDJSON protocol responses (#45).
- **Vault Graph Edge Protection**: Protected vault skill and tag edges during incremental re-indexing in `deleteAllResolvedCodeEdges` and pruned dead functions (#44).
- **Security Boundary Protection**: Prevented path traversal vulnerabilities in baseline file path resolution (#48).

---

## [0.5.2] - 2026-10-18

### Added
- **Git Hook Automation (`atlas init`)**: Configure and install automated pre-commit and pre-push hooks directly into `.git/hooks/` with POSIX executable permissions (`0755`), conflict protection (`--force`), dry-run preview mode (`--dry-run`), and configurable cognitive/cyclomatic complexity ceilings (`--threshold`).
- **Tool Migration Engine (`atlas migrate-tools`)**: Automated detection and in-place migration of legacy `codegraph_*` calls across code, plans, and prompts with `--path`, `--apply`, `--json`, and MCP `atlas_audit(action='migrate_tools')`.
- **Primary MCP Tools**: Promoted `atlas_query` and `atlas_audit` as canonical, top-level Model Context Protocol tools.
- **Audit & Fix Tooling Flags**: Added `--legacy-tools` flag to `atlas audit` (fails with exit code 1 if unmigrated tool references exist) and `atlas fix` (automatically migrates in place).
- **Polyglot Parser Expansion**: Added full syntax coverage for C++, Go, Rust, Python, Swift, TypeScript, and Kotlin.
- **Dedicated Changelog**: Separated release history from `README.md` into this structured document adhering to Keep a Changelog.

### Changed
- **MCP Response Envelope**: Injected structured `_deprecation` metadata when legacy `codegraph_query` or `codegraph_audit` tools are invoked, pointing AI agents to migrate to `atlas_query` and `atlas_audit`.
- **Degree Recomputation**: Optimized SQLite degree recalculation via composite indexing on `edges` (`source_node_id`, `target_node_id`, `project_id`).
- **Documentation Restructuring**: Streamlined `README.md` to a lean product-first layout with hero performance benchmarks, quick start, categorized command matrix, and collapsible deep invariant catalogs.

### Deprecated
- MCP tools `codegraph_query` and `codegraph_audit` are deprecated in favor of `atlas_query` and `atlas_audit` (preserved indefinitely for backwards compatibility).

### Fixed
- **Maturity Calibration**: Aligned 100-point maturity calibration with strict sub-100 ceilings and normalized POSIX exit codes.
- **Complexity Guard Scanner**: Applied absolute complexity floor and resilient guard handling.
- **Dead Code Filtering**: Filtered container-level false positives and disambiguated semantic receivers.
- **Dead Code Pruning**: Removed unused functions (`PlatformFiles.size`, `writeBytes`, `FigmaSpatialBounds.intersects`).

---

## [0.5.1] - 2026-10-18

### Added
- **Compose Stability Scope Isolation (`BIT_STAB_002`)**: Isolates broken `@Immutable`/`@Stable` contract violations strictly to composables that reference unstable types, preventing workspace-wide false positive contamination.
- **Deterministic Compose Doctor (`atlas compose doctor --apply`)**: Synthesizes AST source replacements directly from disk for real-world verified file rewrites.
- **Cross-File Edge Resolution & Import Wildcards**: Added support for wildcard imports (`import foo.*`) restoring affinity weighting, coupled with class-qualified method `nodeId`s across Python, Swift, TypeScript, Rust, and C++.

### Changed
- **Native SQLite FTS5 Indexing & Deletion Hardening**: Eliminates $O(N^2)$ full-table scans during batch deletion and indexing by indexing `node_id` and `project_id` in `nodes_fts`, with composite indexes on `edges` (`source_node_id`, `target_node_id`, `project_id`) accelerating degree recomputations.

### Fixed
- **Polyglot Complexity Accuracy**: Properly counts Rust `match` arms (`=>`), restricts Kotlin `when` arrow branches to switch-like scopes (`parenDepth == 0`), and avoids false positives on lambdas, function types, return types, and C++ pointer dereferences.
- **CI/CD & Platform Resilience**: Modernized GitHub Actions workflow to valid upstream actions, prevented test cache directory leaks, and resolved macOS `/tmp` symlinks consistently.

---

## [0.5.0] - 2026-10-18

### Added
- **URL & Figma Deep-Link Knowledge Graph (`atlas query --url`)**: Unifies code, documentation, endpoints, and design into an interconnected graph. Automatically extracts polyglot HTTP routes (Spring, Ktor, Express, FastAPI, Flask) into canonical `ENDPOINT` nodes (`endpoint:<METHOD>:<path>`) with producer/consumer edges (`PRODUCES_ENDPOINT`, `CALLS_ENDPOINT`). Resolves Figma canvas deep links (`https://www.figma.com/design/.../?node-id=...`) to Figma nodes and code tokens. Resolves code location links (`file://...#L<line>`, GitHub/GitLab `#L<line>`) to innermost enclosing AST declaration/call spans. Maps Markdown docs and vault plans (`url_ref` edges). Supported via `--url <url>` and MCP `atlas_query` (`action: "url"`).
- **Polyglot Code Complexity Engine (`atlas complexity`)**: Computes cyclomatic complexity, cognitive complexity, max nesting depth, and function length across Kotlin, Swift, TypeScript/JavaScript, Python, Rust, Go, and C++. Includes Gate 1 complexity audits (`atlas complexity audit`) and actionable doctor remediation prescriptions (`atlas complexity doctor`).
- **Autonomous Architecture Remediation Engine (`atlas fix`)**: Plans, verifies, and applies automated AST file-splitting diffs for `BOOST-003` Single Declaration Principle compliance, converts unreferenced secondary declarations to private (`--make-private`), corrects layer classification inversions, and verifies test-command rollback guards.
- **Top-Level Description Audit (`atlas audit --check-descriptions`)**: Extracts docstrings, KDoc, and JSDoc comments for top-level declarations, persisting coverage to SQLite with actionable suggestions.
- **Autonomous Compose Doctor Remediation (`atlas compose doctor --apply`)**: Automated remediation of Compose stability anti-patterns with write-site safety verification and batched Figma graph traversal.

---

## [0.4.0] - 2026-10-17

### Added
- **Jetpack Compose & KMP Stability (`atlas compose`)**: Analyzes Kotlin `@Composable` functions via single-pass AST tokenization, infers parameter stability across a 4-state join-semilattice (`STABLE < RUNTIME < UNCERTAIN < UNSTABLE`), evaluates 12 deterministic Compose rules, and performs the Kotlin 2.0+ Strong Skipping Reality Check (`SILENT_WASTE` detection).
- **Compose Doctor**: Run write-site verified Compose Doctor remediation previews.
- **Dead Code Detection (`atlas dead-code`)**: Detects unreferenced top-level functions, classes, and components.
- **Path-Scoped Auditing**: Added `--paths` flag for targeted, incremental audits on staged files and diff sets.

---

## [0.3.0] - 2026-10-16

### Added
- **Figma Atlas (`atlas figma`)**: Indexes Figma document trees and W3C Design Tokens (DTCG).
- **2D Spatial Layout Queries**: Queries 2D spatial layouts via SQLite R*Tree bounding boxes.
- **12 SQL Design Audits**: Evaluates contrast, auto-layout compliance, touch target bounds, component instance usage, and token parity.
- **Design 3D Concurrency Scopes**: Synthesizes 3D Concurrency Scopes for design agents to prevent layout collisions.

---

## [0.2.0] - 2026-10-16

### Added
- **3D Concurrency Scope Synthesis (`atlas synthesize-scope`)**: Computes exact file, module, and architectural layer boundaries for autonomous multi-agent task swarms (`/teamwork`, `/boost`).
- **Work Memory Store**: Episodic agent working memory persistence with exponential half-life decay.
- **Graph Shortest Path**: Breadth-first search (BFS) shortest path query between any two nodes in the code graph.

---

## [0.1.0] - 2026-10-15

### Added
- Initial release of **Atlas** (`code-atlas`).
- Sovereign Polyglot AST and SQLite knowledge graph engine compiled ahead-of-time (AOT) to standalone native binary (`macosArm64`).
- Fast SQLite backing with WAL, FTS5 full-text search, and WITHOUT ROWID tables.
- Model Context Protocol (MCP) server implementation for AI agent pair-programming.
- Deterministic Clean Architecture layer classification and `BOOST-003` Single Declaration Principle audits.

---

[0.6.0]: https://github.com/jefferyforbes/code-atlas/compare/v0.5.2...v0.6.0
[0.5.2]: https://github.com/jefferyforbes/code-atlas/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/jefferyforbes/code-atlas/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/jefferyforbes/code-atlas/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/jefferyforbes/code-atlas/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/jefferyforbes/code-atlas/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/jefferyforbes/code-atlas/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/jefferyforbes/code-atlas/releases/tag/v0.1.0
