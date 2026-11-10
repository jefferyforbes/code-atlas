---
contract:
  id: code-atlas-pr-21-polyglot-extractors-and-dead-code
  version: 1
  project: code-atlas
  task: pr-21-polyglot-extractors-and-dead-code
  objective: Enhance AST extractor fidelity across Kotlin, TypeScript, Python, and C++, support backtick-quoted function names and trailing lambdas, prune dead code in SearchAndVocabDao, and replace static telemetry epochs with dynamic POSIX time.

status: completed

size:
  complexity: low
  expected_files: 7
  expected_modules: 1
  expected_tests: 6

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-20-complexity-ratchet-and-params
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-21-polyglot-extractors-and-dead-code.md
    - src/macosArm64Main/kotlin/atlas/extract/KotlinAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/PolyglotAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/NativeSystemsAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/TypeScriptAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/PythonAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/db/SearchAndVocabDao.kt
    - src/macosArm64Main/kotlin/atlas/extract/ComposeTelemetryParser.kt
    - src/macosArm64Test/kotlin/atlas/PolyglotExtractorTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Extractor Fidelity & Call Graph Coverage: Expand regex patterns and parser routines to capture backtick identifiers, trailing lambda call invocations, C++ constructor member initializers, full TypeScript import varieties (default, named, namespace, combined), and Python imported symbols without degrading performance or cognitive complexity limits.

acceptance_criteria:
  - KotlinAstExtractor extracts backtick-quoted function declarations correctly
  - PolyglotAstExtractor.CALL_RE matches trailing lambda calls (e.g. .use { ... })
  - SearchAndVocabDao.scoreCandidate is removed without breaking any callers or tests
  - NativeSystemsAstExtractor captures C++ constructors with member initializer lists
  - TypeScriptAstExtractor captures default, named, and namespace imports
  - PythonAstExtractor extracts individual imported symbol tokens from 'from ... import'
  - ComposeTelemetryParser uses dynamic POSIX timestamp instead of hardcoded 2024 epoch
  - All existing and new unit tests pass cleanly
  - atlas audit passes with 0 violations

tests:
  commands:
    - ./gradlew test
---

# PR 21 — Polyglot Extractor Fidelity, Backtick Identifiers & Dead Code Pruning

## Summary
Implements Wave 3 remediation across AST extractors and database DAOs:
1. Kotlin backtick identifier support in `KotlinAstExtractor.kt`.
2. Trailing lambda detection in `PolyglotAstExtractor.kt` (`CALL_RE`).
3. Pruning orphaned `scoreCandidate` in `SearchAndVocabDao.kt`.
4. C++ constructor member initializer support in `NativeSystemsAstExtractor.kt`.
5. TypeScript namespace and combined import support in `TypeScriptAstExtractor.kt`.
6. Python imported symbols extraction in `PythonAstExtractor.kt`.
7. Dynamic POSIX timestamps in `ComposeTelemetryParser.kt`.
