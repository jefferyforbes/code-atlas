---
contract:
  id: code-atlas-pr-3-dead-code-and-complexity-ratchet
  version: 1
  project: code-atlas
  task: pr-3-dead-code-and-complexity-ratchet
  objective: Eliminate unreferenced dead code (PlatformFiles.sanitizePathSegment), decompose DDL table migration routines in SqliteGraphStore, and ratchet down cognitive complexity warnings from 23 to 22.

status: review_pending # planned | ready | queued | executing | review_pending | changes_requested | blocked | approved | merged | failed | cancelled

size:
  complexity: small
  expected_files: 3
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
    - code-atlas-pr-2-skill-audit-invariants
  blockers: []

parallelizable: false

scope:
  files:
    - src/macosArm64Main/kotlin/atlas/util/PlatformFiles.kt
    - src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt
    - complexity-baseline.json
  modules:
    - :macosArm64Main
  architecture:
    - Pragmatic: Utility cleanup in PlatformFiles
    - Syntactic: Helper extraction and DDL execution encapsulation in SqliteGraphStore
    - Governance: Ratchet threshold update in complexity-baseline.json

acceptance_criteria:
  - PlatformFiles.sanitizePathSegment is removed and no longer flagged by atlas dead-code
  - SqliteGraphStore.migrateNodesSchema cognitive complexity drops below Gate 1 threshold of 10
  - SqliteGraphStore table migrations for nodes and compose_nodes remain 100% functional and backward-compatible
  - complexity-baseline.json ratchets warning count down from 23 to 22 with 0 HIGH severity violations
  - All existing native tests, atlasAudit, and atlasComplexityAudit pass cleanly under ./gradlew check

tests:
  commands:
    - ./gradlew check
---

# Plan Contract: PR 3 - Dead Code Cleanup & Complexity Ratchet Optimization

## Objective
Remove unused dead code identified during the Atlas audit, decompose repetitive SQLite column migration logic in `SqliteGraphStore`, and tighten the repository's cognitive complexity baseline ratchet.

## Target Changes
1. `src/macosArm64Main/kotlin/atlas/util/PlatformFiles.kt`:
   - Remove unused function `sanitizePathSegment`.
2. `src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt`:
   - Extract `queryTableColumns(tableName: String): Set<String>` to encapsulate PRAGMA table info statement handling.
   - Extract `addMissingColumn(tableName: String, cols: Set<String>, column: String, definition: String)`.
   - Simplify `migrateNodesSchema()` and `migrateComposeSchema()`, reducing cognitive complexity to $\le 3$.
3. `complexity-baseline.json`:
   - Ratchet down `warning_count`, `ratchet`, and `total_violations` from 23 to 22.
