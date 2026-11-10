---
contract:
  id: code-atlas-pr-13-db-concurrency-and-incremental-indexing
  version: 1
  project: code-atlas
  task: pr-13-db-concurrency-and-incremental-indexing
  objective: Wrap SQLite schema initialization in immediate transactions to eliminate cold-start lock collisions, remove duplicate FTS migrations, and implement scoped cross-file edge invalidation in WorkspaceIndexer.

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
  prerequisites: []
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-13-db-concurrency-and-incremental-indexing.md
    - src/macosArm64Main/kotlin/atlas/db/GraphSchemaMigrator.kt
    - src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt
    - src/macosArm64Main/kotlin/atlas/engine/WorkspaceIndexer.kt
    - src/macosArm64Test/kotlin/atlas/SqliteGraphStoreTest.kt
    - src/macosArm64Test/kotlin/atlas/engine/WorkspaceIndexerTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Database & Indexer: Concurrency hardening with BEGIN IMMEDIATE and O(1) scoped incremental edge resolution.

acceptance_criteria:
  - GraphSchemaMigrator wraps schema initialization in BEGIN IMMEDIATE and deduplicates migrateNodesFtsSchema call
  - SqliteGraphStore eliminates redundant FTS5 full-table scan deletes during file re-indexing
  - WorkspaceIndexer scopes code edge invalidation and resolution to symbols declared in or referenced by touched files
  - Full test suite passes without regressions

tests:
  commands:
    - ./gradlew macosArm64Test
    - ~/.local/bin/atlas audit --project code-atlas
---

# Plan Contract: PR 13 - Database Concurrency & Scoped Incremental Indexing

## 1. Context & Objective
Hardens SQLite cold-start migrations against concurrent agent multi-threading collisions (`SQLITE_BUSY`), cleans up redundant virtual table scans in `SqliteGraphStore`, and scopes cross-file code edge resolution in `WorkspaceIndexer` to preserve unaffected edges during single-file updates.
