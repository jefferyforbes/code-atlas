---
contract:
  id: code-atlas-pr-24-unified-library-facade
  version: 1
  project: code-atlas
  task: pr-24-unified-library-facade
  objective: Implement high-level Atlas and AtlasClient library facades providing clean programmatic access to in-memory, persistent, and ephemeral graph stores, workspace indexing, architectural audits, and graph queries.

status: merged

size:
  complexity: low
  expected_files: 4
  expected_loc: 450
  expected_modules: 1
  expected_tests: 10

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-23-pitest-mutation-and-test-expansion
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-24-unified-library-facade.md
    - src/macosArm64Main/kotlin/atlas/Atlas.kt
    - src/macosArm64Main/kotlin/atlas/AtlasClient.kt
    - src/macosArm64Test/kotlin/atlas/AtlasFacadeTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Library Facade Pattern (LIB-001): Provide Atlas companion factory (Atlas.inMemory(), Atlas.open(), Atlas.ephemeral()) returning an AtlasClient interface.
    - Zero Boilerplate Construction: Encapsulate SqliteGraphStore, FakeGraphStore, WorkspaceIndexer, ScopeSynthesizer, ArchitectureAuditor, and SpikeAnalysisEngine behind high-level methods.
    - Resource Lifecycle Management: AutoCloseable AtlasClient with automatic cleanup for disk/temporary database handles.

acceptance_criteria:
  - Atlas.inMemory() creates zero-disk AtlasClient backed by FakeGraphStore
  - Atlas.open(projectSlug, workspacePath) creates persistent AtlasClient backed by SqliteGraphStore
  - Atlas.ephemeral() creates temporary disk-backed AtlasClient with auto-deletion on close
  - AtlasClient provides clean indexing (index(incremental)), queries (callers, callees, impactRadius), audits (auditArchitecture), and spike hypotheses (runSpike)
  - All existing and new tests pass (./gradlew test)
  - review-engine diff clears with score >= 90/100 and 0 Critical/High defects

tests:
  commands:
    - ./gradlew test
    - ~/.local/bin/review-engine diff --base origin/main --project code-atlas --test-suite-passed --plan plans/code-atlas/pr-24-unified-library-facade.md
---

# PR 24: Unified Library Facade (Atlas & AtlasClient)

## Context & Objectives
To enable seamless embedding of Atlas as a library into other tools, daemons, and plugins, introduce a high-level `Atlas` companion entry point and `AtlasClient` interface.
1. `Atlas.inMemory()`: Instantiates zero-disk, ultra-fast `AtlasClient` using `FakeGraphStore`.
2. `Atlas.open(projectSlug, workspacePath, dbPath)`: Instantiates a persistent `AtlasClient` using `SqliteGraphStore`.
3. `Atlas.ephemeral(projectSlug, workspacePath)`: Instantiates a temporary SQLite database cleaned up automatically on `close()`.
4. High-level client API exposing indexation, graph queries, architectural audits, and spike analysis.
