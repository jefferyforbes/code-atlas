---
contract:
  id: code-atlas-pr-16-grill-and-boost-integration
  version: 1
  project: code-atlas
  task: pr-16-grill-and-boost-integration
  objective: Implement interview_context tool action for zero-bloat grounding in /grill-me, and extend architect_context with SemioticSizing for /boost 3-band velocity gating.

status: completed

size:
  complexity: medium
  expected_files: 5
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
    - code-atlas-pr-15a-research-rfc-ground
    - code-atlas-pr-15b-research-spike-analysis
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-16-grill-and-boost-integration.md
    - src/macosArm64Main/kotlin/atlas/model/SemioticSizing.kt
    - src/macosArm64Main/kotlin/atlas/model/ArchitectContextResult.kt
    - src/macosArm64Main/kotlin/atlas/engine/CodegraphCompatibilityEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/ArchitectContextEngine.kt
    - src/macosArm64Main/kotlin/atlas/mcp/AtlasMcpServer.kt
    - src/macosArm64Main/kotlin/atlas/mcp/McpToolSchemas.kt
    - src/macosArm64Test/kotlin/atlas/engine/ArchitectContextEngineTest.kt
    - src/macosArm64Test/kotlin/atlas/mcp/AtlasMcpServerTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - MCP & Engine: Token-bounded (<= 400B) interview_context action and unified SemioticSizing computation for 3-band velocity gates.

acceptance_criteria:
  - interview_context returns <= 400 bytes dense summary of domain models, sealed hierarchies, and error handling conventions
  - architect_context computes SemioticSizing with code_aci, vsc_score, unified_aci, and recommended 3-band execution pipeline
  - Full test suite passes without regressions

tests:
  commands:
    - ./gradlew macosArm64Test
    - ~/.local/bin/atlas audit --project code-atlas
---

# Plan Contract: PR 16 - Grill & Boost Workflow Integration

## 1. Context & Objective
Integrates `atlas` directly with `/grill-me` (dense $\le 400$B summary of domain models, sealed hierarchies, and error conventions) and `/boost` (unified `SemioticSizing` with code ACI, visual complexity VSC, and 3-band velocity gate recommendations).
