---
contract:
  id: code-atlas-pr-8-polyglot-extractor-lang-decomposition
  version: 1
  project: code-atlas
  task: pr-8-polyglot-extractor-lang-decomposition
  objective: Decompose the 3,792-line PolyglotAstExtractor into dedicated internal language extractors (KotlinAstExtractor, TypeScriptAstExtractor, PythonAstExtractor, NativeSystemsAstExtractor) while preserving PolyglotAstExtractor as the public facade.

status: merged # planned | ready | queued | executing | review_pending | changes_requested | blocked | approved | merged | failed | cancelled

size:
  complexity: medium
  expected_files: 9
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
    - code-atlas-pr-7-sqlite-graph-store-dao-decomposition
  blockers: []

parallelizable: false

scope:
  files:
    - src/macosArm64Main/kotlin/atlas/extract/PolyglotAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/KotlinAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/TypeScriptAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/PythonAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/NativeSystemsAstExtractor.kt
    - src/macosArm64Test/kotlin/atlas/PolyglotExtractorTest.kt
    - complexity-baseline.json
    - plans/code-atlas/pr-7-sqlite-graph-store-dao-decomposition.md
    - plans/code-atlas/pr-8-polyglot-extractor-lang-decomposition.md
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Syntactic & Extraction: Modular internal language extractors under atlas.extract orchestrated by PolyglotAstExtractor

acceptance_criteria:
  - KotlinAstExtractor, TypeScriptAstExtractor, PythonAstExtractor, and NativeSystemsAstExtractor are extracted as internal objects/classes in atlas.extract
  - PolyglotAstExtractor.kt line count is reduced by at least 2,000 lines while preserving all public entry points
  - Zero BOOST-003, layer inversion, dead code, or cognitive complexity violations under atlas audit
  - All polyglot extraction tests pass under ./gradlew check

tests:
  commands:
    - ./gradlew check
---

# Plan Contract: PR 8 - PolyglotAstExtractor Language Decomposition

## 1. Context & Objective
`PolyglotAstExtractor.kt` is 3,792 lines long because Kotlin, TypeScript/JS, Python, Swift, Rust, and C/C++ regex grammars and AST scanning routines all reside inside a single companion object. Extracting cohesive `internal` language extractors (`KotlinAstExtractor`, `TypeScriptAstExtractor`, `PythonAstExtractor`, `NativeSystemsAstExtractor`) isolates language-specific parsing logic while keeping `PolyglotAstExtractor` as the public entry point and call-edge disambiguation coordinator.

## 2. Scope
1. `src/macosArm64Main/kotlin/atlas/extract/KotlinAstExtractor.kt` (`internal object`):
   - Kotlin package/import/declaration/call extraction and brace-depth tracking.
2. `src/macosArm64Main/kotlin/atlas/extract/TypeScriptAstExtractor.kt` (`internal object`):
   - TypeScript/JavaScript functions, classes, interfaces, arrow functions, and imports.
3. `src/macosArm64Main/kotlin/atlas/extract/PythonAstExtractor.kt` (`internal object`):
   - `PythonBlockScanner` and Python class/def/import extraction.
4. `src/macosArm64Main/kotlin/atlas/extract/NativeSystemsAstExtractor.kt` (`internal object`):
   - Swift, Rust, and C/C++ AST symbol and include/use extraction.
5. `src/macosArm64Main/kotlin/atlas/extract/PolyglotAstExtractor.kt`:
   - Delegate language extraction to the internal extractors while retaining shared call-edge disambiguation and complexity scoring integration.

## 3. Out of Scope
- Changing extracted node IDs, edge semantics, or semiotic layer classifications.

## 4. Acceptance Criteria
- [ ] `PolyglotAstExtractor.kt` is reduced below 1,800 lines.
- [ ] `atlas audit --project code-atlas` reports 0 violations across all rules (`BOOST-003`, layers, dead code, cognitive complexity).
- [ ] `./gradlew check` passes all unit tests.
