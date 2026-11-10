---
contract:
  id: code-atlas-pr-14-remediation-process-supervision
  version: 1
  project: code-atlas
  task: pr-14-remediation-process-supervision
  objective: Implement process-group execution supervision with a 180-second timeout and guaranteed filesystem rollback in AutonomousRemediationVerifier.

status: completed

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
    - code-atlas-pr-13-db-concurrency-and-incremental-indexing
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-14-remediation-process-supervision.md
    - src/macosArm64Main/kotlin/atlas/engine/AutonomousRemediationVerifier.kt
    - src/macosArm64Main/kotlin/atlas/engine/ArchitectureRemediationEngine.kt
    - src/macosArm64Test/kotlin/atlas/engine/AutonomousRemediationVerifierTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Engine & Safety: Process-group execution timeout supervisor with guaranteed rollback on abort.

acceptance_criteria:
  - executeShellInWorkspace executes test commands in an isolated process group with a 180s timeout
  - On timeout, process deadlock, or termination signal, child processes are terminated via kill(-pgid, SIGKILL)
  - Filesystem modifications staged for verification are guaranteed to rollback via try/finally cleanup
  - Full test suite passes without regressions

tests:
  commands:
    - ./gradlew macosArm64Test
    - ~/.local/bin/atlas audit --project code-atlas
---

# Plan Contract: PR 14 - Remediation Process Supervision & Safety

## 1. Context & Objective
Replaces unbounded POSIX `system(cmd)` with a process-group supervisor enforcing a 180-second execution timeout, ensuring commands that hang or prompt for input cannot block the native CLI or MCP process, and guaranteeing that staged files are cleanly restored.
