---
contract:
  id: code-atlas-pr-15a-research-rfc-ground
  version: 1
  project: code-atlas
  task: pr-15a-research-rfc-ground
  objective: Implement atlas rfc-ground CLI subcommand and MCP action to fulfill research/SKILL.md grounding mandates.

status: completed

size:
  complexity: low
  expected_files: 6
  expected_modules: 1
  expected_tests: 1

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
    - plans/code-atlas/pr-15a-research-rfc-ground.md
    - src/macosArm64Main/kotlin/atlas/model/RfcGroundResult.kt
    - src/macosArm64Main/kotlin/atlas/engine/RfcGroundEngine.kt
    - src/macosArm64Main/kotlin/atlas/cli/AtlasCli.kt
    - src/macosArm64Main/kotlin/atlas/cli/CliArgs.kt
    - src/macosArm64Main/kotlin/atlas/mcp/AtlasMcpServer.kt
    - src/macosArm64Main/kotlin/atlas/mcp/McpToolSchemas.kt
    - src/macosArm64Test/kotlin/atlas/engine/RfcGroundEngineTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Engine & CLI: Subsystem discovery table extraction (<= 800B) for RFC-0 research grounding.

acceptance_criteria:
  - atlas rfc-ground extracts subsystem entry points, domain entities, and dependency boundaries in <= 800B markdown
  - MCP action rfc_ground exposed through AtlasMcpServer
  - Full test suite passes without regressions

tests:
  commands:
    - ./gradlew macosArm64Test
    - ~/.local/bin/atlas audit --project code-atlas
---

# Plan Contract: PR 15a - Research RFC-Ground
Fulfills `research/SKILL.md` mandates by introducing `atlas rfc-ground` (dense subsystem contract and entry point extraction $\le 800$B).
