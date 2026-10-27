---
contract:
  id: code-atlas-pr-7-sqlite-graph-store-dao-decomposition
  version: 1
  project: code-atlas
  task: pr-7-sqlite-graph-store-dao-decomposition
  objective: Decompose the 4,568-line SqliteGraphStore into cohesive internal domain DAOs (FigmaGraphStoreDao, ComposeGraphStoreDao, GraphTraversalDao) while preserving the public SqliteGraphStore API and BOOST-003 compliance.

status: executing # planned | ready | queued | executing | review_pending | changes_requested | blocked | approved | merged | failed | cancelled

size:
  complexity: medium
  expected_files: 6
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
    - code-atlas-pr-6-mcp-audit-payload-and-param-ratchet
  blockers: []

parallelizable: false

scope:
  files:
    - src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt
    - src/macosArm64Main/kotlin/atlas/db/FigmaGraphStoreDao.kt
    - src/macosArm64Main/kotlin/atlas/db/ComposeGraphStoreDao.kt
    - src/macosArm64Main/kotlin/atlas/db/GraphTraversalDao.kt
    - src/macosArm64Main/kotlin/atlas/extract/PolyglotAstExtractor.kt
    - src/macosArm64Test/kotlin/atlas/SqliteGraphStoreTest.kt
    - plans/code-atlas/pr-6-mcp-audit-payload-and-param-ratchet.md
    - plans/code-atlas/pr-7-sqlite-graph-store-dao-decomposition.md
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Data & Storage: Internal DAO delegation pattern under atlas.db preserving SqliteGraphStore facade

acceptance_criteria:
  - FigmaGraphStoreDao, ComposeGraphStoreDao, and GraphTraversalDao are extracted as internal classes in atlas.db with zero BOOST-003 or cognitive complexity violations
  - SqliteGraphStore.kt line count is reduced by at least 1,800 lines while maintaining 100% public API compatibility
  - All existing and new SqliteGraphStore tests pass under ./gradlew check

tests:
  commands:
    - ./gradlew check
---

# Plan Contract: PR 7 - SqliteGraphStore Domain DAO Decomposition

## 1. Context & Objective
`SqliteGraphStore.kt` has grown to 4,568 lines, mixing core AST node/edge persistence, graph traversal algorithms, Jetpack Compose stability tables, and 12 Figma SQL design audit queries in a single class. Extracting cohesive `internal` domain DAOs (`FigmaGraphStoreDao`, `ComposeGraphStoreDao`, `GraphTraversalDao`) reduces `SqliteGraphStore.kt` by >1,800 lines while keeping `SqliteGraphStore` as the single public facade (`BOOST-003` compliant).

## 2. Scope
1. `src/macosArm64Main/kotlin/atlas/db/FigmaGraphStoreDao.kt` (`internal class`):
   - Encapsulate Figma node, spatial R-tree/bounds, design token persistence, and all 12 Figma SQL design audit queries.
2. `src/macosArm64Main/kotlin/atlas/db/ComposeGraphStoreDao.kt` (`internal class`):
   - Encapsulate Compose stability node/parameter persistence, stability cascade queries, and runtime trace telemetry.
3. `src/macosArm64Main/kotlin/atlas/db/GraphTraversalDao.kt` (`internal class`):
   - Encapsulate `getCallers`, `getCallees`, `getImpactRadius`, `getGodNodes`, `getShortestPath`, and `sliceLiveSource`.
4. `src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt`:
   - Delegate Figma, Compose, and Traversal methods to the internal DAOs.

## 3. Out of Scope
- Changing SQLite table schemas or breaking public method signatures on `SqliteGraphStore`.

## 4. Acceptance Criteria
- [ ] `SqliteGraphStore.kt` is reduced below 2,750 lines.
- [ ] `atlas audit --project code-atlas` reports 0 `BOOST-003`, layer inversion, or complexity violations.
- [ ] `./gradlew check` passes all unit and integration tests.
