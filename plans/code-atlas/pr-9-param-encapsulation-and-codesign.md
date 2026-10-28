---
contract:
  id: code-atlas-pr-9-param-encapsulation-and-codesign
  version: 1
  project: code-atlas
  task: pr-9-param-encapsulation-and-codesign
  objective: Remove legacy 11-parameter overloads on AutonomousRemediationVerifier and SqliteGraphStore, encapsulate high-parameter verification and complexity helpers, and enforce ad-hoc codesigning in installToDist and installToLocalBin.

status: merged # planned | ready | queued | executing | review_pending | changes_requested | blocked | approved | merged | failed | cancelled

size:
  complexity: medium
  expected_files: 19
  expected_modules: 1
  expected_tests: 5

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-8-polyglot-extractor-lang-decomposition
  blockers: []

parallelizable: false

scope:
  files:
    - build.gradle.kts
    - complexity-baseline.json
    - src/macosArm64Main/kotlin/atlas/cli/AtlasCli.kt
    - src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt
    - src/macosArm64Main/kotlin/atlas/engine/ArchitectureRemediationEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/AutonomousRemediationVerifier.kt
    - src/macosArm64Main/kotlin/atlas/engine/WorkspaceIndexer.kt
    - src/macosArm64Main/kotlin/atlas/extract/KotlinAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/NativeSystemsAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/PolyglotAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/PythonAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/TypeScriptAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/mcp/AtlasMcpServer.kt
    - src/macosArm64Test/kotlin/atlas/AtlasNativeTest.kt
    - src/macosArm64Test/kotlin/atlas/EngineAndMcpTest.kt
    - src/macosArm64Test/kotlin/atlas/db/NativeSqliteStatementTest.kt
    - src/macosArm64Test/kotlin/atlas/engine/ArchitectureRemediationEngineTest.kt
    - src/macosArm64Test/kotlin/atlas/engine/AutonomousRemediationVerifierTest.kt
    - plans/code-atlas/pr-8-polyglot-extractor-lang-decomposition.md
    - plans/code-atlas/pr-9-param-encapsulation-and-codesign.md
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Engine, Storage & Build: Request-object parameter encapsulation across remediation/extraction engines and macOS arm64 binary codesigning

acceptance_criteria:
  - AutonomousRemediationVerifier.verifyAndApply and SqliteGraphStore.upsertFileRecord 11-parameter overloads are removed in favor of VerificationRequest and FileRecordUpsert
  - PolyglotAstExtractor.computeComplexityMetrics and ArchitectureRemediationEngine.planAndRemediate are encapsulated to <= 3 parameters
  - complexity-baseline.json ratchets param_high_ratchet down from 55 to 45
  - installToDist and installToLocalBin execute ad-hoc codesign (-s - -f) so installed macOS arm64 binaries never fail with SIGKILL (exit code 137)

tests:
  commands:
    - ./gradlew check
---

# Plan Contract: PR 9 - High-Parameter Encapsulation & Binary Codesigning

## 1. Context & Objective
Following PR 8, `AutonomousRemediationVerifier.verifyAndApply` (11 params), `SqliteGraphStore.upsertFileRecord` (11 params), `PolyglotAstExtractor.computeComplexityMetrics` (8 params), `ArchitectureRemediationEngine.planAndRemediate` (8 params), and `AutonomousRemediationVerifier.failResult` (9 params) remained the highest-risk `COMPLEXITY-002-PARAMS` functions in `code-atlas`. Additionally, overwriting `~/.local/bin/atlas` on macOS arm64 without re-signing triggered `SIGKILL` (`137`) from the kernel code-signing cache.

## 2. Scope
1. Remove 11-parameter forwarding overloads in `AutonomousRemediationVerifier` and `SqliteGraphStore`, migrating all callers to `VerificationRequest` and `FileRecordUpsert`.
2. Encapsulate internal helpers in `AutonomousRemediationVerifier`, `ArchitectureRemediationEngine` (`RemediationPlanRequest`), and `PolyglotAstExtractor` (`DeclMetricSpan`).
3. Update `complexity-baseline.json` (`param_high_ratchet`: `55` $\to$ `45`).
4. Add `codesign -s - -f` to `installToDist` and `installToLocalBin` in `build.gradle.kts`.
