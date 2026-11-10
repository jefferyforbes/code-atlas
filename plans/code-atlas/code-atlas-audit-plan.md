---
contract:
  id: code-atlas-whole-tenant-audit-plan
  version: 1
  project: code-atlas
  tenant: code-atlas
  task: whole-tenant-discovery-audit
  objective: Full multi-tenant adversarial audit remediation roadmap resolving critical MCP protocol corruption, cognitive complexity ratchet violations, polyglot extractor fidelity gaps, and Dependency Inversion.

status: ready_for_execution

audit_metadata:
  date: "2026-11-10T08:12:00Z"
  modality: code_modules_and_skills
  mode: WHOLE_TENANT_AUDIT
  pre_flight_score: 70
  gatekeeper_score: 72
  verdict: PLAN_REQUIRED
  ratchet_violations: 31
  ratchet_baseline: 14
  ratchet_delta: 17
  high_severity_violations: 12
  param_high_violations: 6

waves:
  - id: wave-1
    pr: pr-19-process-and-protocol-safety
    title: "Wave 1: Process & Protocol Safety (MCP Framing, POSIX Fork, Top-Level Panics, DB Post-Close)"
    aci: 6
    risk: low
    files: 4
  - id: wave-2
    pr: pr-20-complexity-ratchet-and-params
    title: "Wave 2: Complexity Ratchet & Parameter Object Remediation"
    aci: 8
    risk: medium
    files: 6
  - id: wave-3
    pr: pr-21-polyglot-extractors-and-dead-code
    title: "Wave 3: Polyglot Extractor Fidelity, Backtick Identifiers & Dead Code Pruning"
    aci: 7
    risk: low
    files: 6
  - id: wave-4
    pr: pr-22-graph-store-interface-and-fakes
    title: "Wave 4: Architecture DIP (GraphStore Interface), Memory Optimization & Reusable Fakes"
    aci: 10
    risk: medium
    files: 8

dependencies:
  prerequisites: []
  blockers: []

acceptance_criteria:
  - atlas audit --complexity passes with 0 HIGH violations, <= 14 total violations, and 0 param_high violations
  - MCP JSON-RPC 2.0 framing is completely immune to child process stdout/stderr pollution in verify_remediation
  - Top-level CLI and main entrypoints handle database/I/O exceptions gracefully with exit code 1 or 2
  - Kotlin backtick function names and trailing lambdas are extracted into AST graph correctly
  - Dead code SearchAndVocabDao.scoreCandidate is removed
  - 100% of unit tests pass on macosArm64 without regressions
---

# Code-Atlas Whole-Tenant Audit & Remediation Master Plan

## 1. Executive Summary & Audit Scorecard

A full dual-pass adversarial discovery audit (**Mode B — Whole-Tenant Audit**) was executed across the `code-atlas` repository by the Universal Review & Audit Hub, deploying pre-flight native Linters and parallel isolated Challenger subagents (`Challenger-Architecture` and `Challenger-Platform`).

```
========================================================================
             CODE-ATLAS ADVERSARIAL WHOLE-TENANT AUDIT SCORECARD
========================================================================
  1. BOOST-003 Single Declaration Principle:             100 / 100
  2. Domain Model Purity & Isolation:                    100 / 100
  3. JSON-RPC 2.0 & Spillover Compliance:                 92 / 100
  4. Platform C-Interop & SQL Injection Defense:          88 / 100
  5. CLI & Entrypoint Error Resilience:                   72 / 100
  6. Polyglot AST Extraction Completeness:                70 / 100
  7. Process & Shell Execution Safety:                    60 / 100
  8. Cognitive Complexity & Parameter Ratchet:            56 / 100
  9. Test Isolation & Double Seams:                       64 / 100
------------------------------------------------------------------------
  GATEKEEPER ARBITER COMPOSITE SCORE (POST-SUBTRACTION):  72 / 100
  GATE 2 STATUS:                                         REJECTED (Score < 90)
  OVERALL VERDICT:                                       PLAN_REQUIRED
========================================================================
```

---

## 2. Deduplicated Defect Ledger

### Critical (P0)
1. **`MCP-PROTO-CORRUPT`** (`AutonomousRemediationVerifier.kt:587-603`): Child test execution does not redirect `stdout`/`stderr`. When executed via MCP (`verify_remediation`), raw test outputs write to `stdout`, corrupting JSON-RPC 2.0 framing and severing client transport.
2. **`COMPLEXITY-001` / `RATCHET-FAIL`** (`atlas audit --complexity`): Failed with exit code 1. Current codebase contains 31 violations (+17 over baseline 14) and 12 HIGH violations, led by `NativeSystemsAstExtractor.extractRustImplTarget` (Cognitive 41, Cyclomatic 28).

### High (P1)
3. **`POSIX-FORK-SAFETY`** (`AutonomousRemediationVerifier.kt:594-601, 648`): Non-async-signal-safe `memScoped` allocations between `fork` and `execv`; unconditional `kill(-pid, SIGKILL)` in `finally` risking signal delivery to recycled process groups.
4. **`CLI-UNCAUGHT-PANIC`** (`Main.kt:9-14`, `AtlasCli.kt:56-68`): Zero top-level `try/catch` wrapping server initialization or `dispatchCommand`. Database locking or filesystem errors abort via native `SIGABRT` instead of formatted diagnostics and exit code 1.
5. **`EXTRACT-KT-BACKTICK`** (`KotlinAstExtractor.kt:25-30, 239`): `KT_DECL_RE` matches only `[A-Za-z0-9_]+`, completely dropping backtick-quoted function identifiers (e.g. behavioral unit tests).
6. **`ARCH-DIP-001`** (`src/macosArm64Main/kotlin/atlas/engine/*.kt`): Domain engines depend directly on concrete `SqliteGraphStore` rather than an inverted interface (`GraphStore`/`GraphReader`).
7. **`AST-CALL-GAP`** (`PolyglotAstExtractor.kt:38`): `CALL_RE` requires `\s*\(`, dropping Kotlin trailing lambda calls (`.use { ... }`) and generating false-positive dead-code reports on `NativeSqliteStatement.use`.
8. **`DEAD-CODE-001`** (`SearchAndVocabDao.kt:87-98`): Orphaned method `scoreCandidate` is unreferenced in production and tests.
9. **`COMPLEXITY-002-PARAMS`** (6 engine functions): Exceed parameter limit with 7–8 loose parameters.

### Medium (P2)
10. **`DB-NIL-DEREF-POSTCLOSE`** (`NativeSqliteDatabase.kt:46-73`): Lack of `requireDb()` check in `exec()` and `prepare()` risks SIGSEGV on closed database instances.
11. **`DB-MEMORY-WAL-DELAY`** (`GraphSchemaMigrator.kt:46-56`): 500ms spinlock delay attempting to set WAL mode on `:memory:` SQLite databases.
12. **`EXTRACT-CPP-CTOR`** (`NativeSystemsAstExtractor.kt:52-55`): Omission of C++ constructor member initializer lists.
13. **`EXTRACT-TS-IMPORT`** (`TypeScriptAstExtractor.kt:25`): Omission of namespace (`import * as X`) and combined default/named imports.
14. **`EXTRACT-PY-IMPORT`** (`PythonAstExtractor.kt:21`): Omission of imported symbol tokens in Python `from ... import`.
15. **`PERF-INDEX-MEMORY`** (`WorkspaceIndexer.kt:88`): Duplicate raw and masked text buffering in `pendingKotlinComposeFiles`.
16. **`ARCH-PRES-COUPLING`** (`AtlasCli.kt:56-68`): CLI invokes `AtlasMcpServer` and routes commands through MCP tool serialization.

### Low (P3)
17. **`TEST-NAMING-CONFORMANCE`** (`src/macosArm64Test/`): 306 tests use camelCase instead of plain domain backticks.
18. **`TEST-FAUX-ROLLBACK`** (`AtlasIncrementalIndexingE2ETest.kt:158`): Rollback assertions test in-memory mock sink rather than real SQLite rollback.
19. **`TELEMETRY-STATIC-TS`** (`ComposeTelemetryParser.kt:214`): Fallback timestamp hardcoded to static Oct 2024 epoch.

---

## 3. Four-Wave Stacked PR Roadmap

```text
[Wave 1: Process & Protocol Safety] ──► [Wave 2: Complexity & Params] ──► [Wave 3: Extractor Fidelity] ──► [Wave 4: DIP & Fakes]
 (pr-19-process-and-protocol-safety)    (pr-20-complexity-ratchet-and-params) (pr-21-polyglot-extractors-and-dead-code) (pr-22-graph-store-interface-and-fakes)
```

### Wave 1: PR 19 — Process & Protocol Safety (`pr-19-process-and-protocol-safety`)
- **Objective**: Eliminate JSON-RPC stdout pollution, harden POSIX child execution, add top-level CLI error boundaries, and fix SQLite post-close & in-memory delays.
- **Scope (Files)**:
  - `src/macosArm64Main/kotlin/atlas/engine/AutonomousRemediationVerifier.kt`
  - `src/macosArm64Main/kotlin/atlas/Main.kt`
  - `src/macosArm64Main/kotlin/atlas/cli/AtlasCli.kt`
  - `src/macosArm64Main/kotlin/atlas/db/NativeSqliteDatabase.kt`
  - `src/macosArm64Main/kotlin/atlas/db/GraphSchemaMigrator.kt`
  - `src/macosArm64Test/kotlin/atlas/engine/AutonomousRemediationVerifierTest.kt`
  - `src/macosArm64Test/kotlin/atlas/db/NativeSqliteDatabaseTest.kt`

### Wave 2: PR 20 — Complexity Ratchet & Parameter Object Remediation (`pr-20-complexity-ratchet-and-params`)
- **Objective**: Decompose top 7 high-complexity functions, introduce typed parameter request objects for 7–8 param signatures, and achieve full Gate 1 complexity compliance (`atlas audit --complexity`).
- **Scope (Files)**:
  - `src/macosArm64Main/kotlin/atlas/extract/NativeSystemsAstExtractor.kt`
  - `src/macosArm64Main/kotlin/atlas/engine/AutonomousRemediationVerifier.kt`
  - `src/macosArm64Main/kotlin/atlas/engine/RfcGroundEngine.kt`
  - `src/macosArm64Main/kotlin/atlas/engine/SpikeAnalysisEngine.kt`
  - `src/macosArm64Main/kotlin/atlas/engine/ArchitectureRemediationEngine.kt`
  - `complexity-baseline.json`

### Wave 3: PR 21 — Polyglot Extractor Fidelity, Backtick Identifiers & Dead Code Pruning (`pr-21-polyglot-extractors-and-dead-code`)
- **Objective**: Fix Kotlin backtick function extraction, support trailing lambdas in call resolution, safely remove orphaned `SearchAndVocabDao.scoreCandidate`, and expand C++, TS, and Python import/constructor regexes.
- **Scope (Files)**:
  - `src/macosArm64Main/kotlin/atlas/extract/KotlinAstExtractor.kt`
  - `src/macosArm64Main/kotlin/atlas/extract/PolyglotAstExtractor.kt`
  - `src/macosArm64Main/kotlin/atlas/extract/NativeSystemsAstExtractor.kt`
  - `src/macosArm64Main/kotlin/atlas/extract/TypeScriptAstExtractor.kt`
  - `src/macosArm64Main/kotlin/atlas/extract/PythonAstExtractor.kt`
  - `src/macosArm64Main/kotlin/atlas/db/SearchAndVocabDao.kt`
  - `src/macosArm64Main/kotlin/atlas/extract/ComposeTelemetryParser.kt`

### Wave 4: PR 22 — Architecture DIP (GraphStore Interface), Memory Optimization & Reusable Fakes (`pr-22-graph-store-interface-and-fakes`)
- **Objective**: Invert domain engine dependencies on SQLite via `GraphStore` / `GraphReader` interfaces, implement reusable `FakeGraphStore` test double, and eliminate redundant pass 2 text buffers in `WorkspaceIndexer`.
- **Scope (Files)**:
  - `src/macosArm64Main/kotlin/atlas/model/GraphStore.kt`
  - `src/macosArm64Main/kotlin/atlas/db/SqliteGraphStore.kt`
  - `src/macosArm64Main/kotlin/atlas/engine/WorkspaceIndexer.kt`
  - `src/macosArm64Main/kotlin/atlas/extract/ModuleBoundaryDetector.kt`
  - `src/macosArm64Test/kotlin/atlas/testdoubles/FakeGraphStore.kt`
  - `src/macosArm64Test/kotlin/atlas/engine/WorkspaceIndexerTest.kt`

---

## 4. Verification & Gate Clearance Criteria
- `./gradlew test` passes 100% of unit and integration test suites.
- `atlas audit --project code-atlas` returns 0 `BOOST-003` violations, 0 layer inversions, 0 plan collisions.
- `atlas audit --complexity --project code-atlas` returns 0 HIGH violations and exits with code 0.
- `atlas dead-code --project code-atlas` returns 0 unreferenced dead code symbols.
