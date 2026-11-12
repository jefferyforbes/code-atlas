---
contract:
  id: code-atlas-pr-23-pitest-mutation-and-test-expansion
  version: 1
  project: code-atlas
  task: pr-23-pitest-mutation-and-test-expansion
  objective: Expand the Pitest-equivalent mutation testing verification suite across polyglot boundaries, transaction rollback, database lifecycle guards, and spike hypothesis analysis.

status: merged

size:
  complexity: low
  expected_files: 3
  expected_modules: 1
  expected_tests: 8

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-22-graph-store-interface-and-fakes
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-23-pitest-mutation-and-test-expansion.md
    - src/macosArm64Main/kotlin/atlas/db/NativeSqliteDatabase.kt
    - src/macosArm64Test/kotlin/atlas/testdoubles/FakeGraphStore.kt
    - src/macosArm64Test/kotlin/atlas/integration/AtlasMutationVerificationTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Mutation Testing Expansion (MUT-002): Extend AtlasMutationVerificationTest with 5 new mutant killer patterns targeting INVERT_NEGS, CONDITIONALS_BOUNDARY, VOID_METHOD_CALLS, and RETURNS mutants.
    - Transaction Rollback Fidelity (TEST-FAKES-002): Implement snapshot and rollback mechanics in FakeGraphStore.withImmediateTransaction and withSavepoint to mirror SQLite transaction semantics.
    - Gate 1 & Gate 2 Test Audit: Maintain score=100/100 and active coverage across all 5 test patterns in audit_test_suite.kexe.

acceptance_criteria:
  - AtlasMutationVerificationTest kills polyglot AST extractor boundary & negation mutants (backtick decls, trailing lambda, multi-imports, C++ ctor init lists)
  - FakeGraphStore snapshots in-memory state and guarantees 100% rollback when transaction aborts with exception
  - NativeSqliteDatabase.requireDb() kills void call mutants across execute, query, and transaction on closed DB handles
  - SpikeAnalysisEngine decoupling hypothesis kills boundary mutants on maxHops traversal (hops <= maxHops)
  - FakeGraphStore multi-project query isolation kills empty and null return replacement mutants
  - audit_test_suite.kexe score >= 90/100 (Passes Gate 1 and Gate 2)
  - Full test suite passes without regressions (./gradlew test)
  - atlas audit --project code-atlas passes with 0 violations

tests:
  commands:
    - ./gradlew test
    - ~/.gemini/config/skills/engineering/validation/testing/bin/audit_test_suite.kexe src/macosArm64Test/kotlin --gate 80
    - ~/.local/bin/atlas audit --project code-atlas
---

# PR 23: Pitest Mutation Testing & Test Suite Expansion

## Context & Objectives
Expand test suite coverage and Pitest-equivalent mutation verification across the capabilities introduced in PR 19-22:
1. **Polyglot Boundary & Negation Mutator Killers**: Verify backtick declaration extraction, trailing lambda calls, TypeScript type/wildcard imports, Python multi-imports, and C++ constructor member initializer lists.
2. **Transactional Rollback Mutator Killers**: Introduce in-memory snapshot and rollback mechanics in `FakeGraphStore` to guarantee test double fidelity with real SQLite transactions.
3. **Database Lifecycle Void Call Killers**: Verify `NativeSqliteDatabase.requireDb()` prevents operations post-closure without memory corruption.
4. **Spike Hypothesis Boundary Mutator Killers**: Verify exact boundary behavior on `maxHops` topological path checks.
5. **Multi-Project Scoping Return Killers**: Verify cross-project isolation in `GraphStore`.
