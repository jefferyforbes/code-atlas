---
contract:
  id: code-atlas-pr-15b-research-spike-analysis
  version: 1
  project: code-atlas
  task: pr-15b-research-spike-analysis
  objective: Implement atlas spike CLI subcommand and MCP action for topological hypothesis testing.

status: completed

size:
  complexity: medium
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
    - code-atlas-pr-15a-research-rfc-ground
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-15b-research-spike-analysis.md
    - src/macosArm64Main/kotlin/atlas/model/SpikeAnalysisResult.kt
    - src/macosArm64Main/kotlin/atlas/engine/SpikeAnalysisEngine.kt
    - src/macosArm64Main/kotlin/atlas/cli/AtlasCli.kt
    - src/macosArm64Main/kotlin/atlas/cli/CliArgs.kt
    - src/macosArm64Main/kotlin/atlas/mcp/AtlasMcpServer.kt
    - src/macosArm64Main/kotlin/atlas/mcp/McpToolSchemas.kt
    - src/macosArm64Test/kotlin/atlas/engine/SpikeAnalysisEngineTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Engine & CLI: Topological hypothesis testing across decoupling and Clean Architecture boundaries.

acceptance_criteria:
  - atlas spike validates decoupling (BFS shortest path) and clean architecture layer boundaries
  - MCP action spike exposed through AtlasMcpServer
  - Full test suite passes without regressions

tests:
  commands:
    - ./gradlew macosArm64Test
    - ~/.local/bin/atlas audit --project code-atlas
---

# Plan Contract: PR 15b - Research Spike Analysis
Fulfills `research/SKILL.md` mandates by introducing `atlas spike` (topological hypothesis testing across decoupling and Clean Architecture boundaries).
