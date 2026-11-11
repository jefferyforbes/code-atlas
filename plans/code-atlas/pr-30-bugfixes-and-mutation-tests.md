---
contract:
  id: code-atlas-pr-30-bugfixes-and-mutation-tests
  version: 1
  project: code-atlas
  task: pr-30-bugfixes-and-mutation-tests
  objective: Fix subtle graph topology degree derivation bugs, DOT blank node labels, SARIF L-prefix line spans, sub-second watch mtime hashing, and expand Pitest-equivalent mutation test patterns 9 through 13.

status: ready_for_review

size:
  complexity: low
  expected_files: 7
  expected_loc: 450
  expected_modules: 1
  expected_tests: 12

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-29-native-library-packaging
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-30-bugfixes-and-mutation-tests.md
    - src/macosArm64Main/kotlin/atlas/query/GraphQueryEngine.kt
    - src/macosArm64Main/kotlin/atlas/export/GraphVisualExporter.kt
    - src/macosArm64Main/kotlin/atlas/export/SarifExporter.kt
    - src/macosArm64Main/kotlin/atlas/watch/AtlasWatchSession.kt
    - src/macosArm64Test/kotlin/atlas/integration/AtlasMutationVerificationTest.kt
    - src/macosArm64Test/kotlin/atlas/query/GraphQueryEngineTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Dynamic Topology Derivation (BUG-01): GraphQueryEngine calculates effective in/out degrees dynamically from edges when uncomputed on GraphNode, avoiding false island classifications.
    - Robust Visual Fallbacks (BUG-02): GraphVisualExporter DOT nodes fall back to nodeId when name is blank.
    - SARIF Flexible Line Parsing (BUG-03): SarifExporter supports linter-formatted line spans with L prefix (e.g. L42-L50).
    - Sub-Second Watch Integrity (BUG-04): AtlasWatchSession verifies content SHA-256 hash when mtime is identical to catch fast file mutations.
    - Pitest Mutation Verification (MUT-002): Add mutant killer patterns 9-13 covering cycle detection boundaries, ArchitectureRules whitelist gates, delta computation, and SARIF rule integrity.

acceptance_criteria:
  - GraphQueryEngine correctly identifies islands, roots, and leaves even when node degree fields are 0
  - GraphVisualExporter renders non-empty node labels when node name is blank
  - SarifExporter parses L-prefixed line numbers accurately
  - AtlasWatchSession detects modifications occurring within the same second timestamp
  - Mutation verification test suite expands with 5 new Pitest-equivalent patterns (9-13)
  - All tests pass (./gradlew test)
  - review-engine diff clears with score >= 90/100 and 0 Critical/High defects

tests:
  commands:
    - ./gradlew test
    - ~/.local/bin/review-engine diff --base origin/main --project code-atlas --test-suite-passed --plan plans/code-atlas/pr-30-bugfixes-and-mutation-tests.md
---

# PR 30: Bug Fixes & Pitest Mutation Testing Expansion

## Context & Objectives
Based on a systematic audit of the newly introduced library modules:
1. `GraphQueryEngine`: Derive in/out degrees dynamically from edges when stored degrees are 0 to prevent false island classifications on freshly inserted test nodes or unindexed stores.
2. `GraphVisualExporter`: DOT format should fall back to `node.name.ifBlank { node.nodeId }` to prevent empty labels.
3. `SarifExporter`: Support `L` prefix in line spans (e.g. `"L12-L24"`).
4. `AtlasWatchSession`: Add content hash check when `currentMtime == cachedMeta.second` for sub-second file modifications.
5. `AtlasMutationVerificationTest`: Implement Patterns 9 through 13 of Pitest-equivalent mutant killers.
