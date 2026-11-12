---
contract:
  id: code-atlas-pr-31-library-integration-test
  version: 1
  project: code-atlas
  task: pr-31-library-integration-test
  objective: Add end-to-end integration test suite uniting Atlas facade, WorkspaceIndexer, GraphQueryEngine, ArchitectureRules, SarifExporter, GraphVisualExporter, and AtlasWatchSession against real disk workspaces.

status: merged

size:
  complexity: low
  expected_files: 2
  expected_loc: 320
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
    - code-atlas-pr-30-bugfixes-and-mutation-tests
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-31-library-integration-test.md
    - src/macosArm64Test/kotlin/atlas/integration/AtlasLibraryIntegrationTest.kt
  modules:
    - :macosArm64Test
  architecture:
    - End-to-End Library Integration (TEST-004): Verify end-to-end multi-language source file creation on disk, incremental indexing via AtlasClient, topological querying via GraphQueryEngine, custom rule enforcement via ArchitectureRules, SARIF generation, and reactive watch session delta notification.
    - Zero Mocking: Execute against real temporary workspace directory and real SQLite database.

acceptance_criteria:
  - AtlasLibraryIntegrationTest indexes a polyglot workspace with Kotlin, TypeScript, and Python
  - GraphQueryEngine verifies acyclic DAG order and finds component paths
  - ArchitectureRules enforces clean layer boundaries across the indexed workspace
  - SarifExporter generates valid SARIF 2.1.0 JSON from real audit results
  - AtlasWatchSession detects live file edits and emits accurate GraphDelta
  - All tests pass (./gradlew test)
  - review-engine diff clears with score >= 90/100 and 0 Critical/High defects

tests:
  commands:
    - ./gradlew test
    - ~/.local/bin/review-engine diff --base origin/main --project code-atlas --test-suite-passed --plan plans/code-atlas/pr-31-library-integration-test.md
---

# PR 31: Atlas Library End-to-End Integration Test Suite

## Context & Objectives
To ensure all 6 recently introduced library capabilities work together in real-world conditions with zero seams:
1. Author `AtlasLibraryIntegrationTest`:
   - Builds a polyglot test project on disk (`domain/`, `data/`, `presentation/`).
   - Indexes via `Atlas.open()`.
   - Traverses call and import graphs with `GraphQueryEngine`.
   - Evaluates architectural integrity with `ArchitectureRules`.
   - Generates and verifies SARIF 2.1.0 and Mermaid visual reports.
   - Triggers `AtlasWatchSession` across real file modifications.
