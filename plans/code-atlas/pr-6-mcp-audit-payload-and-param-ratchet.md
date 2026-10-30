---
contract:
  id: code-atlas-pr-6-mcp-audit-payload-and-param-ratchet
  version: 1
  project: code-atlas
  task: pr-6-mcp-audit-payload-and-param-ratchet
  objective: Bound MCP atlas_audit complexity violation payloads to prevent 32KB spillovers, encapsulate 11-15 parameter functions, and enforce param_high_ratchet in complexity-baseline.json.

status: planned # planned | ready | queued | executing | review_pending | changes_requested | blocked | approved | merged | failed | cancelled

size:
  complexity: medium
  expected_files: 8
  expected_modules: 1
  expected_tests: 4

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-5-zero-cognitive-complexity
  blockers: []

parallelizable: false

scope:
  files:
    - src/macosArm64Main/kotlin/atlas/mcp/AtlasMcpServer.kt
    - src/macosArm64Main/kotlin/atlas/cli/AtlasCli.kt
    - src/macosArm64Main/kotlin/atlas/extract/ComposeStabilityExtractor.kt
    - src/macosArm64Main/kotlin/atlas/engine/ArchitectureRemediationEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/AutonomousRemediationVerifier.kt
    - src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt
    - complexity-baseline.json
    - src/macosArm64Test/kotlin/atlas/EngineAndMcpTest.kt
    - plans/code-atlas/pr-6-mcp-audit-payload-and-param-ratchet.md
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - MCP & Engine: Token-budgeted audit serialization in AtlasMcpServer and parameter object encapsulation across extractors and engines

acceptance_criteria:
  - MCP atlas_audit (verify_architecture and audit_complexity) caps serialized complexity violations by limit (default 50, prioritized by COMPLEXITY-001 and risk_index) so responses stay under 32KB without spilling to disk
  - All functions with >= 11 parameters in ComposeStabilityExtractor, ArchitectureRemediationEngine, AutonomousRemediationVerifier, and SqliteGraphStore are encapsulated to <= 5 parameters
  - complexity-baseline.json tracks param_high_ratchet and fails audit when HIGH COMPLEXITY-002-PARAMS count regresses
  - All existing and new MCP payload/ratchet tests pass under ./gradlew check

tests:
  commands:
    - ./gradlew check
---

# Plan Contract: PR 6 - MCP Audit Payload Bounding & Parameter Complexity Ratchet

## 1. Context & Objective
Calling `atlas_audit` (`verify_architecture` or `audit_complexity`) over MCP currently serializes all 236 `COMPLEXITY-002-PARAMS` violations into a ~96 KB JSON response, exceeding the 32 KB MCP ceiling and triggering disk spillover. Additionally, `COMPLEXITY-002-PARAMS` is not gated by `complexity-baseline.json`, leaving 11–15 parameter methods unratcheted. This PR bounds MCP violation serialization to `limit` (default 50), encapsulates all $\ge 11$-parameter functions, and introduces `param_high_ratchet`.

## 2. Scope
1. `src/macosArm64Main/kotlin/atlas/mcp/AtlasMcpServer.kt`:
   - Bound `complexityViolations` in `handleAuditVerifyArchitecture` and `violations` in `buildComplexityAuditResponse` to `limit` (default 50, sorted with `COMPLEXITY-001` first, then `HIGH` before `WARNING`, descending by `risk_index`), while reporting exact total counts (`total_complexity_violations`, `advisory_count`, `param_high_count`).
2. Parameter Encapsulation ($\ge 11$ params $\to \le 5$ params):
   - `ComposeStabilityExtractor`: Encapsulate `parseSingleComposableDecl` (15 params), `buildComposeNodeRecord` (13 params), `computeComposableViolationMask` (12 params), and `extractComposableSemanticEdges` (11 params).
   - `ArchitectureRemediationEngine`: Encapsulate `executeAstSplitRemediation` (13 params).
   - `AutonomousRemediationVerifier`: Encapsulate `verifyAndApply` (11 params).
   - `SqliteGraphStore`: Encapsulate `upsertFileRecord` (11 params).
3. `complexity-baseline.json`, `AtlasCli.kt`, and `AtlasMcpServer.kt`:
   - Add `param_high_ratchet` to `complexity-baseline.json` and enforce it during `atlas audit --complexity`.
4. `src/macosArm64Test/kotlin/atlas/EngineAndMcpTest.kt`:
   - Add unit tests verifying MCP `atlas_audit` payload stays under 32 KB (`spillover` absent) and `param_high_ratchet` enforcement.

## 3. Out of Scope
- DAO decomposition of `SqliteGraphStore` (PR 7) and language extractor split of `PolyglotAstExtractor` (PR 8).

## 4. Acceptance Criteria
- [ ] `atlas_audit` (`verify_architecture` and `audit_complexity`) MCP responses on `code-atlas` return directly in-context ($< 32\text{ KB}$, no spillover envelope).
- [ ] Zero functions in `code-atlas` have $> 10$ parameters unless annotated `@Composable`.
- [ ] `complexity-baseline.json` enforces `param_high_ratchet`.
- [ ] `./gradlew check` and `atlas audit` pass with 0 violations.
