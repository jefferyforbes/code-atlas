---
contract:
  id: code-atlas-pr-11-engine-and-db-remediation
  version: 1
  project: code-atlas
  task: pr-11-engine-and-db-remediation
  objective: Fix logarithmic architectural risk ranking in GraphTraversalDao, fix kind-filtered node search truncation in SqliteGraphStore, preserve full nodeId seed identity in ArchitectContextEngine, eliminate force-unwraps in AutonomousRemediationVerifier, and encapsulate 17 HIGH-parameter engine functions.

status: approved

size:
  complexity: medium
  expected_files: 11
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
    - code-atlas-pr-10-extractor-and-lexer-fixes
  blockers: []

parallelizable: false

scope:
  files:
    - complexity-baseline.json
    - plans/code-atlas/pr-10-extractor-and-lexer-fixes.md
    - plans/code-atlas/pr-11-engine-and-db-remediation.md
    - src/macosArm64Main/kotlin/atlas/db/GraphTraversalDao.kt
    - src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt
    - src/macosArm64Main/kotlin/atlas/engine/ArchitectContextEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/ArchitectureRemediationEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/AutonomousRemediationVerifier.kt
    - src/macosArm64Main/kotlin/atlas/engine/ComposeStabilityEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/ScopeSynthesizer.kt
    - src/macosArm64Main/kotlin/atlas/engine/WorkspaceIndexer.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Engine & Storage: Exact logarithmic risk ranking, kind-filtered SQL search completeness, nodeId seed preservation, and engine parameter encapsulation

acceptance_criteria:
  - GraphTraversalDao.queryArchitecturalRiskNodes sorts by exact logarithmic riskIndex without premature linear SQL truncation and delegates grade/risk math to ComplexityExtractor
  - SqliteGraphStore.loadRankedNodesMap applies kind filtering in SQL so kind-filtered searches do not drop matches outside the top 50 unfiltered results
  - ArchitectContextEngine passes resolved nodeIds to ScopeSynthesizer and AutonomousRemediationVerifier eliminates force-unwrap (!!) usages
  - 17 HIGH COMPLEXITY-002-PARAMS functions across engine classes are encapsulated to <= 6 parameters (param_high_ratchet: 31 -> 14)

tests:
  commands:
    - ./gradlew check
---

# Plan Contract: PR 11 - Engine & Graph Store Remediation

## 1. Context & Objective
Fixes premature SQL truncation in `GraphTraversalDao.queryArchitecturalRiskNodes` and `SqliteGraphStore.searchNodes`, preserves `nodeId` seed identity in `ArchitectContextEngine`, removes `!!` in `AutonomousRemediationVerifier`, and encapsulates 19 `HIGH` `COMPLEXITY-002-PARAMS` functions across `ArchitectureRemediationEngine`, `ComposeStabilityEngine`, and `WorkspaceIndexer`.
