---
contract:
  id: code-atlas-pr-17-pitest-and-e2e-integration-tests
  version: 1
  project: code-atlas
  task: pr-17-pitest-and-e2e-integration-tests
  objective: Implement end-to-end integration and conformance tests, satisfy audit_test_suite.kexe 5 essential testing patterns and pitest mutation invariants, and remediate Gate 0 live network cap.

status: completed

size:
  complexity: medium
  expected_files: 9
  expected_modules: 1
  expected_tests: 12

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites:
    - code-atlas-pr-16-grill-and-boost-integration
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-17-pitest-and-e2e-integration-tests.md
    - src/macosArm64Test/kotlin/atlas/integration/FakeStoreSink.kt
    - src/macosArm64Test/kotlin/atlas/integration/AtlasEndToEndIntegrationTest.kt
    - src/macosArm64Test/kotlin/atlas/integration/AtlasConformanceContractTest.kt
    - src/macosArm64Test/kotlin/atlas/FigmaSnapshotExtractorTest.kt
    - src/macosArm64Test/kotlin/atlas/FigmaStoreTest.kt
    - src/macosArm64Test/kotlin/atlas/engine/UrlGraphIndexingAndQueryTest.kt
    - src/macosArm64Test/kotlin/atlas/engine/WorkspaceIndexerIsolationTest.kt
    - src/macosArm64Test/kotlin/atlas/extract/ParamComplexityExtractorTest.kt
    - src/macosArm64Test/kotlin/atlas/extract/UrlTargetResolverTest.kt
    - src/macosArm64Test/kotlin/atlas/AtlasNativeTest.kt
    - src/macosArm64Test/kotlin/atlas/EngineAndMcpTest.kt
    - src/macosArm64Test/kotlin/atlas/FigmaDesignAuditorTest.kt
    - src/macosArm64Test/kotlin/atlas/PolyglotExtractorTest.kt
    - src/macosArm64Test/kotlin/atlas/db/ComplexitySqliteStoreTest.kt
    - src/macosArm64Test/kotlin/atlas/engine/ComplexityDoctorTest.kt
    - src/macosArm64Test/kotlin/atlas/engine/WorkspaceInitEngineTest.kt
    - src/macosArm64Test/kotlin/atlas/extract/ComplexityExtractorTest.kt
    - src/macosArm64Test/kotlin/atlas/mcp/AtlasMcpServerTest.kt
    - src/macosArm64Test/kotlin/atlas/util/PlatformFilesTest.kt
  modules:
    - :macosArm64Test
  architecture:
    - Test & Quality: End-to-end integration test covering workspace indexing, querying, auditing, and remediation lifecycle.
    - Test & Quality: Conformance contract test suite verifying GraphStore implementations.
    - Test & Quality: Boundary value analysis (BVA) parameterized testing table.
    - Test & Quality: Hermetic test seam annotation and mock domain remediation for audit_test_suite.kexe Gate 0 / Gate 1 / Gate 2.

acceptance_criteria:
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

# PR 17: Pitest Mutation Testing & End-to-End Integration Tests

## Context & Objectives
Add end-to-end integration tests and conformance contract verification for `code-atlas`.
Elevate the test suite to satisfy the Five Essential Testing Patterns, Anti-Tautology & Mutation checks, and Seam Purity standards verified by `audit_test_suite.kexe`.

## Key Deliverables
1. **Gate 0 Hermetic Seam Remediation**:
   - Annotate and configure mock/fake seams for offline URL resolver and figma tests to eliminate unmocked external live URL warnings.
2. **End-to-End Integration Suite (`AtlasEndToEndIntegrationTest.kt`)**:
   - Full lifecycle test: hermetic workspace initialization -> multi-language AST extraction -> graph store queries (`explore`, `symbol`, `impact`, `interview_context`) -> architecture & spike audits -> doctor diagnosis & verified rollback.
   - Parameterized boundary value analysis (`boundaryCases` table) testing CLI and graph store limits (empty strings, zero depths, negative limits, deep graphs).
3. **Conformance Contract Suite (`AtlasConformanceContractTest.kt`)**:
   - Reusable contract verification suite asserting standard node and edge CRUD, idempotency, foreign key integrity, and isolated transactions across GraphStore implementations.
4. **Pattern 2 Sad Path & Rollback Counters**:
   - Explicit negative assertions and rollback state validations (`assertEquals(0, fake.callCount)`, `rollback`).
