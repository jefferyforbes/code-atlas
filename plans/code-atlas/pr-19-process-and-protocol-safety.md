---
contract:
  id: code-atlas-pr-19-process-and-protocol-safety
  version: 1
  project: code-atlas
  task: pr-19-process-and-protocol-safety
  objective: Eliminate child process stdout/stderr pollution in MCP verify_remediation, harden POSIX child fork execution, add top-level CLI error boundaries, and fix SQLite post-close & in-memory delays.

status: completed

size:
  complexity: small
  expected_files: 5
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
    - code-atlas-pr-18-pitest-and-e2e-suite-expansion
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-19-process-and-protocol-safety.md
    - src/macosArm64Main/kotlin/atlas/engine/AutonomousRemediationVerifier.kt
    - src/macosArm64Main/kotlin/atlas/Main.kt
    - src/macosArm64Main/kotlin/atlas/cli/AtlasCli.kt
    - src/macosArm64Main/kotlin/atlas/db/NativeSqliteDatabase.kt
    - src/macosArm64Main/kotlin/atlas/db/GraphSchemaMigrator.kt
    - src/macosArm64Test/kotlin/atlas/engine/AutonomousRemediationVerifierTest.kt
    - src/macosArm64Test/kotlin/atlas/db/NativeSqliteStatementTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Process Supervision & Safety: Redirect child process stdout/stderr to /dev/null to protect MCP framing, eliminate heap allocations between fork and execv, guard signal cleanup, wrap CLI entrypoint in error boundary, add requireDb() guard in NativeSqliteDatabase, and bypass 500ms WAL spinlock on :memory: databases.

acceptance_criteria:
  - executeShellInWorkspace redirects child stdout and stderr to /dev/null preventing JSON-RPC 2.0 framing corruption
  - C strings and argument buffers are allocated prior to fork() to maintain async-signal safety
  - Process termination signals in finally block are guarded so already-terminated processes do not trigger SIGKILL on recycled PIDs
  - Main.kt and AtlasCli.run wrap execution in top-level try/catch, outputting structured error messages and returning non-zero exit codes instead of native SIGABRT
  - NativeSqliteDatabase.exec and prepare assert checkNotNull(dbPointer) via requireDb() throwing descriptive IllegalStateException instead of OS SIGSEGV
  - GraphSchemaMigrator.setWalModeSafely returns immediately for :memory: databases
  - 100% of unit tests pass on macosArm64 without regressions

tests:
  commands:
    - ./gradlew macosArm64Test
    - ~/.local/bin/atlas audit --project code-atlas
---

# Plan Contract: PR 19 — Process & Protocol Safety

## 1. Context & Objective
Resolves critical findings from the whole-tenant adversarial audit:
1. **MCP Protocol Framing Integrity (`MCP-PROTO-CORRUPT`)**: When `verify_remediation` executes shell tests via MCP, child processes write output directly to stdout, corrupting JSON-RPC 2.0 frames.
2. **POSIX Fork Safety (`POSIX-FORK-SAFETY`)**: Kotlin heap allocations in `memScoped` between `fork` and `execv` violate async-signal safety, and unconditional `kill(-pid, SIGKILL)` risks signaling recycled process groups.
3. **CLI Panic Defense (`CLI-UNCAUGHT-PANIC`)**: Top-level entrypoints lack error boundaries, causing native `SIGABRT` crashes on unhandled errors.
4. **SQLite Post-Close Safety (`DB-NIL-DEREF-POSTCLOSE`)**: Calling `exec()` or `prepare()` after `close()` dereferences a null pointer in C FFI, causing OS `SIGSEGV`.
5. **In-Memory SQLite Delay (`DB-MEMORY-WAL-DELAY`)**: In-memory databases incur an unavoidable 500ms thread sleep penalty during schema initialization attempting to set WAL mode.
