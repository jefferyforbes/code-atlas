---
contract:
  id: code-atlas-pr-22-graph-store-interface-and-fakes
  version: 1
  project: code-atlas
  task: pr-22-graph-store-interface-and-fakes
  objective: Invert domain engine dependencies on SQLite via GraphReader and GraphStore interfaces, implement reusable FakeGraphStore test double, and eliminate redundant pass 2 text buffers in WorkspaceIndexer.

status: completed

size:
  complexity: medium
  expected_files: 8
  expected_modules: 1
  expected_tests: 8

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-21-polyglot-extractors-and-dead-code
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-22-graph-store-interface-and-fakes.md
    - src/macosArm64Main/kotlin/atlas/model/FileRecordUpsert.kt
    - src/macosArm64Main/kotlin/atlas/model/GraphReader.kt
    - src/macosArm64Main/kotlin/atlas/model/GraphStore.kt
    - src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt
    - src/macosArm64Main/kotlin/atlas/engine/WorkspaceIndexer.kt
    - src/macosArm64Main/kotlin/atlas/engine/ArchitectureAuditor.kt
    - src/macosArm64Main/kotlin/atlas/engine/ArchitectContextEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/ScopeSynthesizer.kt
    - src/macosArm64Main/kotlin/atlas/engine/RfcGroundEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/SpikeAnalysisEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/CodegraphCompatibilityEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/ComposeStabilityEngine.kt
    - src/macosArm64Main/kotlin/atlas/engine/ComplexityDoctorEngine.kt
    - src/macosArm64Test/kotlin/atlas/testdoubles/FakeGraphStore.kt
    - src/macosArm64Test/kotlin/atlas/engine/WorkspaceIndexerTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Dependency Inversion Principle (DIP): Decouple domain engines (ArchitectureAuditor, ArchitectContextEngine, ScopeSynthesizer, etc.) from concrete SqliteGraphStore by introducing GraphReader and GraphStore interfaces.
    - Memory Optimization (MEM-OPT-001): In WorkspaceIndexer, avoid buffering full sourceText and maskedText in memory across pass 1 and pass 2; store file path references and filter Compose candidates on-demand.
    - Standalone Test Doubles (TEST-FAKES-001): Provide FakeGraphStore implementing GraphStore in common test fixtures to enable fast, zero-disk testing.

acceptance_criteria:
  - GraphReader interface abstracts all read/query graph operations
  - GraphStore interface extends GraphReader and abstracts write/indexing operations
  - SqliteGraphStore implements GraphStore
  - Domain engines depend on GraphReader / GraphStore rather than concrete SqliteGraphStore
  - WorkspaceIndexer does not retain full raw and masked source text in PendingKotlinComposeFile
  - FakeGraphStore implements GraphStore for fast in-memory testing
  - All existing unit tests and new FakeGraphStore tests pass
  - atlas audit passes with 0 violations (BOOST-003, complexity, layer checks)

tests:
  - atlas.testdoubles.FakeGraphStoreTest: verify in-memory node/edge/file queries and mutations on FakeGraphStore
  - atlas.engine.WorkspaceIndexerTest: verify memory optimization and indexing behavior
---
