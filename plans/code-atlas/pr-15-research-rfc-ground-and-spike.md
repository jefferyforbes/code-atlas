---
contract:
  id: code-atlas-pr-15-research-rfc-ground-and-spike
  version: 1
  project: code-atlas
  task: pr-15-research-rfc-ground-and-spike
  objective: Implement atlas rfc-ground and atlas spike CLI subcommands and MCP actions to fulfill research/SKILL.md grounding mandates.

status: completed

size:
  complexity: medium
  expected_files: 6
  expected_modules: 1
  expected_tests: 3

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-13-db-concurrency-and-incremental-indexing
  blockers: []

parallelizable: true

scope:
  files:
    - plans/code-atlas/pr-15-research-rfc-ground-and-spike.md
    - src/macosArm64Main/kotlin/atlas/model/RfcGroundResult.kt
    - src/macosArm64Main/kotlin/atlas/model/SpikeAnalysisResult.kt
    - src/macosArm64Main/kotlin/atlas/engine/RfcGroundEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/SpikeAnalysisEngine.kt
    - src/macosArm64Main/kotlin/atlas/cli/AtlasCli.kt
    - src/macosArm64Main/kotlin/atlas/cli/CliArgs.kt
    - src/macosArm64Main/kotlin/atlas/mcp/AtlasMcpServer.kt
    - src/macosArm64Main/kotlin/atlas/mcp/McpToolSchemas.kt
    - src/macosArm64Test/kotlin/atlas/engine/RfcGroundEngineTest.kt
    - src/macosArm64Test/kotlin/atlas/engine/SpikeAnalysisEngineTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Engine & CLI: Subsystem discovery table extraction (<= 800B) and BFS decoupling spike hypothesis testing.

acceptance_criteria:
  - atlas rfc-ground extracts subsystem entry points, domain entities, and dependency boundaries in <= 800B markdown
  - atlas spike validates decoupling (BFS shortest path) and clean architecture layer boundaries
  - MCP actions rfc_ground and spike exposed through AtlasMcpServer
  - Full test suite passes without regressions

tests:
  commands:
    - ./gradlew macosArm64Test
    - ~/.local/bin/atlas audit --project code-atlas
---

# Plan Contract: PR 15 - Research RFC-Ground and Spike Analysis

## 1. Context & Objective
Fulfills `research/SKILL.md` mandates by introducing `atlas rfc-ground` (dense subsystem contract and entry point extraction $\le 800$B) and `atlas spike` (topological hypothesis testing across decoupling and Clean Architecture boundaries).
