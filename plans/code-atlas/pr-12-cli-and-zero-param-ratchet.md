---
contract:
  id: code-atlas-pr-12-cli-and-zero-param-ratchet
  version: 1
  project: code-atlas
  task: pr-12-cli-and-zero-param-ratchet
  objective: Encapsulate remaining 14 HIGH COMPLEXITY-002-PARAMS functions in AtlasCli and CodegraphCompatibilityEngine, add --param-high-ratchet CLI flag and baseline persistence, and ratchet param_high_ratchet to 0 in complexity-baseline.json.

status: approved

size:
  complexity: medium
  expected_files: 8
  expected_modules: 1
  expected_tests: 2

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-11-engine-and-db-remediation
  blockers: []

parallelizable: false

scope:
  files:
    - complexity-baseline.json
    - plans/code-atlas/pr-11-engine-and-db-remediation.md
    - plans/code-atlas/pr-12-cli-and-zero-param-ratchet.md
    - src/macosArm64Main/kotlin/atlas/cli/AtlasCli.kt
    - src/macosArm64Main/kotlin/atlas/cli/CliArgs.kt
    - src/macosArm64Main/kotlin/atlas/engine/CodegraphCompatibilityEngine.kt
    - src/macosArm64Main/kotlin/atlas/mcp/AtlasMcpServer.kt
    - src/macosArm64Test/kotlin/atlas/engine/ComplexityRatchetTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - CLI & MCP: Zero HIGH COMPLEXITY-002-PARAMS ratchet (0/0), NodeQueryRequest encapsulation, and --param-high-ratchet CLI support

acceptance_criteria:
  - All 14 remaining HIGH COMPLEXITY-002-PARAMS functions in AtlasCli and CodegraphCompatibilityEngine are encapsulated to <= 6 parameters
  - complexity-baseline.json ratchets param_high_ratchet from 14 down to 0 (achieving 0 across all complexity ratchets)
  - CliArgs supports --param-high-ratchet and AtlasCli --save-baseline persists param_high_ratchet into complexity-baseline.json
  - ComplexityRatchetTest verifies param_high_ratchet enforcement and CLI flag parsing

tests:
  commands:
    - ./gradlew check
---

# Plan Contract: PR 12 - CLI/MCP Encapsulation & Zero High-Parameter Ratchet

## 1. Context & Objective
Encapsulates the final 14 `HIGH` `COMPLEXITY-002-PARAMS` functions in `AtlasCli` and `CodegraphCompatibilityEngine` (`NodeQueryRequest`), adds `--param-high-ratchet` to `CliArgs`, persists `param_high_ratchet` in `AtlasCli.saveComplexityBaselineFile`, and ratchets `param_high_ratchet` to `0` in `complexity-baseline.json`.
