---
contract:
  id: code-atlas-pr-18-pitest-and-e2e-suite-expansion
  version: 1
  project: code-atlas
  task: pr-18-pitest-and-e2e-suite-expansion
  objective: Add incremental indexing E2E tests, CLI subprocess command conformance tests, and Pitest mutation verification suite killing boundary, math, and void call mutants.

status: completed

size:
  complexity: medium
  expected_files: 4
  expected_modules: 1
  expected_tests: 15

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-17-pitest-and-e2e-integration-tests
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-18-pitest-and-e2e-suite-expansion.md
    - src/macosArm64Test/kotlin/atlas/integration/AtlasIncrementalIndexingE2ETest.kt
    - src/macosArm64Test/kotlin/atlas/integration/AtlasCliEndToEndIntegrationTest.kt
    - src/macosArm64Test/kotlin/atlas/integration/AtlasMutationVerificationTest.kt
  modules:
    - :macosArm64Test
  architecture:
    - Test & Quality: Multi-pass incremental workspace indexing E2E test verifying cache hits, mutation updates, and edge cleanup.
    - Test & Quality: CLI end-to-end integration test exercising all core commands, JSON serialization, and exit codes.
    - Test & Quality: Pitest mutation verification test suite targeting CONDITIONALS_BOUNDARY, MATH/INCREMENTS, VOID_METHOD_CALLS, and RETURNS mutants.

acceptance_criteria:
  - Multi-pass incremental indexing verified end-to-end (add, edit, delete files, hash mismatch, cache hits, sweep of orphaned edges)
  - Full CLI command execution verified across core commands with exit codes and JSON format
  - Pitest boundary mutants (CONDITIONALS_BOUNDARY, MATH/INCREMENTS, VOID_METHOD_CALLS, RETURNS) killed via exact 3-value BVA and deep assertions
  - All 5 essential testing patterns active (happy AAA, sad chaos rollback, parameterized edge BVA, stateful seam fake, conformance contract)
  - Zero Gate 0 LiveNetworkCap70 violations
  - audit_test_suite.kexe score >= 90/100 (Passes Gate 1 and Gate 2)
  - Full test suite passes without regressions (./gradlew macosArm64Test)
  - atlas audit --project code-atlas passes with 0 BOOST-003 and 0 layer violations

tests:
  commands:
    - ./gradlew macosArm64Test
    - ~/.gemini/config/skills/engineering/validation/testing/bin/audit_test_suite.kexe src/macosArm64Test/kotlin --gate 80
    - ~/.local/bin/atlas audit --project code-atlas
---

# PR 18: Pitest Mutation Testing & End-to-End Suite Expansion

## Context & Objectives
Expand the test suite with:
1. Multi-pass incremental indexing lifecycle E2E tests (`AtlasIncrementalIndexingE2ETest.kt`).
2. Sovereign CLI command execution and JSON contract E2E tests (`AtlasCliEndToEndIntegrationTest.kt`).
3. Systematic Pitest mutation killer tests (`AtlasMutationVerificationTest.kt`) protecting boundary conditions, mathematical formulas, and side-effect guarantees.
