---
contract:
  id: code-atlas-pr-27-architecture-rule-dsl
  version: 1
  project: code-atlas
  task: pr-27-architecture-rule-dsl
  objective: Implement declarative ArchitectureRules DSL for polyglot architectural testing and custom layer constraint evaluation.

status: ready_for_review

size:
  complexity: low
  expected_files: 3
  expected_loc: 550
  expected_modules: 1
  expected_tests: 10

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-26-graph-query-dsl
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-27-architecture-rule-dsl.md
    - src/macosArm64Main/kotlin/atlas/rule/ArchitectureRules.kt
    - src/macosArm64Test/kotlin/atlas/rule/ArchitectureRulesTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Polyglot ArchUnit DSL (RULE-001): Provide fluent in-code DSL for defining architectural layers, forbidden dependencies (shouldNotDependOn), allowed dependency boundaries (mayOnlyDependOn), and custom node/edge predicates.
    - Zero Bytecode Reflection: Evaluates constraints natively against GraphReader AST nodes and edges across Kotlin, Swift, Python, TypeScript, Rust, and C++.
    - Single Declaration (BOOST-003): Encapsulate DSL Builder, Severity, RuleViolation, and EvaluationReport inside ArchitectureRules.

acceptance_criteria:
  - ArchitectureRules.define { ... } compiles with layer definitions and dependency restrictions
  - shouldNotDependOn flags invalid inter-layer directed edges
  - mayOnlyDependOn flags dependencies outside the permitted layer whitelist
  - Custom node and edge rules evaluate accurately with source file/line reporting
  - EvaluationReport reports pass/fail based on error severity violations
  - Full unit test coverage in ArchitectureRulesTest
  - All tests pass (./gradlew test)
  - review-engine diff clears with score >= 90/100 and 0 Critical/High defects

tests:
  commands:
    - ./gradlew test
    - ~/.local/bin/review-engine diff --base origin/main --project code-atlas --test-suite-passed --plan plans/code-atlas/pr-27-architecture-rule-dsl.md
---

# PR 27: Declarative Architecture Rule DSL (Polyglot ArchUnit)

## Context & Objectives
To enable CI and unit test assertions that enforce clean architectural boundaries in code (without requiring JVM bytecode or language-specific plugins), provide `ArchitectureRules`.
1. Fluent layer specification by path regex/substring or node predicate.
2. In-code declarative constraints: `domain shouldNotDependOn data`, `presentation mayOnlyDependOn setOf(...)`.
3. Custom AST predicates on node complexity, degrees, or naming conventions.
4. Comprehensive evaluation and structured diagnostic reporting.
