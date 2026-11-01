---
contract:
  id: code-atlas-pr-10-extractor-and-lexer-fixes
  version: 1
  project: code-atlas
  task: pr-10-extractor-and-lexer-fixes
  objective: Harden polyglot lexer string interpolation, scope complexity branch keywords by language, make cross-file symbol disambiguation deterministic, eliminate prefix substring allocations, and encapsulate 17 HIGH-parameter extractor functions.

status: merged

size:
  complexity: medium
  expected_files: 14
  expected_modules: 1
  expected_tests: 2

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-9-param-encapsulation-and-codesign
  blockers: []

parallelizable: false

scope:
  files:
    - complexity-baseline.json
    - plans/code-atlas/pr-10-extractor-and-lexer-fixes.md
    - src/macosArm64Main/kotlin/atlas/bridge/VaultPlanBridge.kt
    - src/macosArm64Main/kotlin/atlas/extract/ComplexityExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/ComposeStabilityExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/FigmaSnapshotExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/KotlinAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/ModuleBoundaryDetector.kt
    - src/macosArm64Main/kotlin/atlas/extract/NativeSystemsAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/PolyglotAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/PythonAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/TypeScriptAstExtractor.kt
    - src/macosArm64Main/kotlin/atlas/extract/UrlTargetResolver.kt
    - src/macosArm64Test/kotlin/atlas/extract/ComplexityExtractorTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Extraction & Bridge: Polyglot lexer interpolation safety, language-scoped complexity keywords, deterministic cross-file symbol disambiguation, and extractor parameter encapsulation

acceptance_criteria:
  - PolyglotAstExtractor masks char literals, nested strings, and comments inside Kotlin/Swift/JS string interpolations without corrupting brace depth
  - ComplexityExtractor scopes when/match/switch/case by language and excludes member calls and declaration identifiers
  - PolyglotAstExtractor.selectCandidatePool narrows unimported symbols by same package/directory -> module -> archLayer with deterministic tie-breaking
  - 14 HIGH COMPLEXITY-002-PARAMS functions in extractors and bridge are encapsulated to <= 6 parameters (param_high_ratchet: 45 -> 31)

tests:
  commands:
    - ./gradlew check
---

# Plan Contract: PR 10 - Polyglot Lexer, Complexity Scoping & Extractor Encapsulation

## 1. Context & Objective
Fixes string-interpolation brace corruption in `PolyglotAstExtractor`, false-positive control keywords in `ComplexityExtractor`, non-deterministic incremental symbol disambiguation, prefix substring allocations for line counting, and encapsulates 17 `HIGH` `COMPLEXITY-002-PARAMS` functions across `atlas/extract` and `atlas/bridge`.
