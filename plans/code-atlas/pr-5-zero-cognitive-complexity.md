---
contract:
  id: code-atlas-pr-5-zero-cognitive-complexity
  version: 1
  project: code-atlas
  task: pr-5-zero-cognitive-complexity
  objective: Decompose the remaining 8 COMPLEXITY-001 cognitive/cyclomatic complexity warning functions and ratchet complexity-baseline.json to 0.

status: merged # planned | ready | queued | executing | review_pending | changes_requested | blocked | approved | merged | failed | cancelled

size:
  complexity: medium
  expected_files: 7
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
    - code-atlas-pr-4-lexical-scope-call-disambiguation
  blockers: []

parallelizable: false

scope:
  files:
    - src/macosArm64Main/kotlin/atlas/util/Sha256.kt
    - src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt
    - src/macosArm64Main/kotlin/atlas/extract/ComposeStabilityExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/FigmaSnapshotExtractor.kt
    - src/macosArm64Main/kotlin/atlas/engine/FigmaDesignAuditor.kt
    - src/macosArm64Main/kotlin/atlas/engine/ArchitectureAuditor.kt
    - src/macosArm64Main/kotlin/atlas/engine/ScopeSynthesizer.kt
    - complexity-baseline.json
    - plans/code-atlas/pr-5-zero-cognitive-complexity.md
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Engine & Storage: Cognitive and cyclomatic complexity decomposition across Sha256, SqliteGraphStore, ComposeStabilityExtractor, FigmaSnapshotExtractor, and FigmaDesignAuditor

acceptance_criteria:
  - All 8 COMPLEXITY-001 warning functions are decomposed to cognitive_complexity <= 10 and cyclomatic_complexity <= 15
  - complexity-baseline.json is ratcheted to ratchet=0 and high_ratchet=0
  - atlas audit --complexity --project code-atlas passes with 0 COMPLEXITY-001 violations
  - All existing native tests pass under ./gradlew check

tests:
  commands:
    - ./gradlew check
---

# Plan Contract: PR 5 - Zero Cognitive Complexity Ratchet

## 1. Context & Objective
Following PR #154 (`2749de9`), `code-atlas` has 0 `HIGH` cognitive complexity violations and only 8 `WARNING` (`COMPLEXITY-001`) functions remaining above the threshold (`cognitive > 10` or `cyclomatic > 15`). Decomposing these 8 functions allows ratcheting `complexity-baseline.json` to `0`, establishing a permanent zero-tolerance gate for cognitive and cyclomatic complexity across the entire codebase.

## 2. Scope
1. `src/macosArm64Main/kotlin/atlas/util/Sha256.kt`:
   - Decompose `Sha256.digest` (Cognitive: 14 -> <= 6) into `padMessage`, `expandMessageSchedule`, and `formatDigestHex`.
2. `src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt`:
   - Decompose `getImpactRadius` (Cognitive: 14 -> <= 6) into `collectImpactedNodeIds` and `fetchNodesByChunks`.
   - Decompose `getShortestPath` (Cognitive: 12 -> <= 6) into `stepBfsNeighbors`.
   - Decompose `bindFigmaNodeRow` (Cognitive: 13 -> <= 5) into `bindFigmaNodeGeometry` and `bindFigmaNodeFlagsAndContrast`.
   - Decompose `queryUnboundTokenViolations` (Cognitive: 11 -> <= 6) into `countUnboundFigmaNodes` and `mapUnboundTokenRow`.
3. `src/macosArm64Main/kotlin/atlas/extract/ComposeStabilityExtractor.kt`:
   - Decompose `inferTypeStability` (Cognitive: 12 -> <= 6) into `extractTopLevelGenericArgs` and `inferFallbackStability`.
4. `src/macosArm64Main/kotlin/atlas/engine/FigmaDesignAuditor.kt`:
   - Decompose `evaluateGate1Maturity` (Cognitive: 11, Cyclomatic: 17 -> <= 6) using a static `RULE_DEDUCTION_WEIGHTS` map and `computeUnboundTokenDeduction`.
5. `src/macosArm64Main/kotlin/atlas/extract/FigmaSnapshotExtractor.kt`:
   - Decompose `isEmojiCodePoint` (Cognitive: 13, Cyclomatic: 14 -> <= 4) using a static `EMOJI_CODE_POINT_RANGES` array lookup.
6. `complexity-baseline.json`:
   - Ratchet `ratchet`, `total_violations`, and `warning_count` from `8` to `0`.

## 3. Out of Scope
- Modifying `COMPLEXITY-002-PARAMS` parameter counts (deferred to PR 6).
- Splitting `SqliteGraphStore` or `PolyglotAstExtractor` into separate files (deferred to PRs 7 and 8).

## 4. Acceptance Criteria
- [ ] `Sha256.digest`, `SqliteGraphStore.getImpactRadius`, `SqliteGraphStore.getShortestPath`, `ComposeStabilityExtractor.inferTypeStability`, `SqliteGraphStore.bindFigmaNodeRow`, `SqliteGraphStore.queryUnboundTokenViolations`, `FigmaDesignAuditor.evaluateGate1Maturity`, and `FigmaSnapshotExtractor.isEmojiCodePoint` all report `cognitive_complexity <= 10` and `cyclomatic_complexity <= 15`.
- [ ] `complexity-baseline.json` has `"ratchet": 0, "high_ratchet": 0, "total_violations": 0, "warning_count": 0`.
- [ ] `./gradlew check` and `atlas audit --complexity --project code-atlas` pass with 0 violations.
