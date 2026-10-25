---
contract:
  id: code-atlas-pr-4-lexical-scope-call-disambiguation
  version: 1
  project: code-atlas
  task: pr-4-lexical-scope-call-disambiguation
  objective: Disambiguate unqualified call edges using caller lexical scope, eliminating false-positive dead code detection across nested classes.

status: merged # planned | ready | queued | executing | review_pending | changes_requested | blocked | approved | merged | failed | cancelled

size:
  complexity: small
  expected_files: 3
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
    - code-atlas-pr-3-dead-code-and-complexity-ratchet
  blockers: []

parallelizable: false

scope:
  files:
    - src/macosArm64Main/kotlin/atlas/extract/PolyglotAstExtractor.kt
    - src/macosArm64Test/kotlin/atlas/PolyglotExtractorTest.kt
    - plans/code-atlas/pr-4-lexical-scope-call-disambiguation.md
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Syntactic: Lexical scope filtering in PolyglotAstExtractor
    - Verification: Native unit tests for inner and outer container scope disambiguation

acceptance_criteria:
  - Unqualified call edges in PolyglotAstExtractor resolve to candidates sharing the caller's enclosing container before falling back
  - PythonBlockScanner.processLine is properly attributed to PythonBlockScanner.scan and dead code count drops to 0
  - All existing native tests and new scope disambiguation tests pass under ./gradlew check
  - Zero architectural violations reported by atlas audit

tests:
  commands:
    - ./gradlew check
---

# Plan Contract: PR 4 - Lexical Scope Call Disambiguation

## Objective
Enhance symbol call-edge disambiguation in `PolyglotAstExtractor` by prioritizing the caller's lexical enclosing scope when resolving unqualified calls within the same source file. This resolves call misattribution between sibling classes declaring identical method names and eliminates false-positive dead code detection.

## Target Changes
1. `src/macosArm64Main/kotlin/atlas/extract/PolyglotAstExtractor.kt`:
   - Introduce `filterByLexicalScope(pool: List<GraphNode>, srcNode: GraphNode?): List<GraphNode>` evaluating exact enclosing scope, companion scope, outer enclosing scope hierarchy, and top-level declarations.
   - Encapsulate disambiguation context into `DisambiguationContext` to keep `selectCandidatePool` within 4 parameters.
2. `src/macosArm64Test/kotlin/atlas/PolyglotExtractorTest.kt`:
   - Add `testLexicalScopeCallDisambiguationInSameFile` testing sibling classes with identical method names.
   - Add `testOuterLexicalScopeCallDisambiguation` testing nested inner classes invoking outer enclosing helpers.
