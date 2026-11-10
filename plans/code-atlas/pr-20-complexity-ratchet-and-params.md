---
contract:
  id: code-atlas-pr-20-complexity-ratchet-and-params
  version: 1
  project: code-atlas
  task: pr-20-complexity-ratchet-and-params
  objective: Decompose the top 12 high-complexity functions and encapsulate 6 parameter-heavy method signatures into typed parameter objects to achieve 0 HIGH complexity violations, 0 parameter HIGH violations, and satisfy the complexity ratchet gate.

status: completed

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
    - code-atlas-pr-19-process-and-protocol-safety
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-20-complexity-ratchet-and-params.md
    - src/macosArm64Main/kotlin/atlas/extract/NativeSystemsAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/engine/AutonomousRemediationVerifier.kt
    - src/macosArm64Main/kotlin/atlas/engine/RfcGroundEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/SpikeAnalysisEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/ArchitectureRemediationEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/WorkspaceIndexer.kt
    - src/macosArm64Main/kotlin/atlas/engine/CodegraphCompatibilityEngine.kt
    - src/macosArm64Main/kotlin/atlas/db/GraphSchemaMigrator.kt
    - complexity-baseline.json
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Complexity Decomposition & Parameter Encapsulation: Refactor monolithic parsing, traversal, and verification algorithms into single-responsibility subroutines with cognitive complexity <= 10; replace loose 7-8 parameter functions with typed request/context data classes.

acceptance_criteria:
  - atlas audit --complexity passes with exit code 0
  - high_severity_count is reduced to 0 (high_ratchet = 0)
  - param_high_count is reduced to 0 (param_high_ratchet = 0)
  - total_violations is <= ratchet baseline
  - All existing unit and integration tests pass without behavioral regression

tests:
  commands:
    - ./gradlew test
    - ~/.local/bin/atlas audit --complexity --project code-atlas
---

# Plan Contract: PR 20 — Complexity Ratchet & Parameter Object Remediation

## 1. Context & Objective
Remediates all 12 HIGH complexity functions and 6 HIGH parameter count signatures identified during the whole-tenant audit to restore `atlas audit --complexity` Gate 1 compliance with zero regressions.
