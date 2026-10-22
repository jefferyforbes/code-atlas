---
contract:
  id: code-atlas-pr-2-skill-audit-invariants
  version: 1
  project: code-atlas
  task: pr-2-skill-audit-invariants
  objective: Implement SKILL-001, SKILL-002, and SKILL-003 architectural audit invariants in ArchitectureAuditor to detect broken references, invalid frontmatter schema, and circular dependency cycles.

status: merged # planned | ready | queued | executing | review_pending | changes_requested | blocked | approved | merged | failed | cancelled

size:
  complexity: medium
  expected_files: 5
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
    - code-atlas-pr-1-skill-graph-extraction
  blockers: []

parallelizable: false

scope:
  files:
    - src/macosArm64Main/kotlin/atlas/model/SkillAuditViolation.kt
    - src/macosArm64Main/kotlin/atlas/model/AuditReport.kt
    - src/macosArm64Main/kotlin/atlas/engine/ArchitectureAuditor.kt
    - src/macosArm64Main/kotlin/atlas/cli/AtlasCli.kt
    - src/macosArm64Main/kotlin/atlas/mcp/AtlasMcpServer.kt
    - src/macosArm64Test/kotlin/atlas/EngineAndMcpTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Semantic: SkillAuditViolation model and AuditReport field extension
    - Syntactic: Graph traversal and cycle detection routines in ArchitectureAuditor
    - Pragmatic: CLI output formatting, MCP action dispatch, and EngineAndMcpTest verification

acceptance_criteria:
  - SKILL-001 flags broken companion file references and missing script targets from skill nodes
  - SKILL-002 flags skills with missing or empty names, blank descriptions, or broken YAML folding markers (> / >-)
  - SKILL-003 detects directed circular dependency cycles in DEPENDS_ON_SKILL edges and outputs readable cycle chains
  - ArchitectureAuditor.runFullAudit includes skillViolations in AuditReport and factors them into report.passed
  - atlas_audit MCP tool supports explicit "audit_skills" action returning focused skill audit results
  - Atlas CLI displays skill_violations in summary output
  - All existing code tests, audits, and cognitive complexity ratchets (<= 23 violations) pass cleanly

tests:
  commands:
    - ./gradlew check
---

# Plan Contract: PR 2 - Skill Audit Invariants (SKILL-001/002/003)

## Objective
Enable Atlas to audit agent skill repositories and workspace skills automatically, enforcing structural invariants across companion documentation references, metadata frontmatter schemas, and acyclic dependency graphs.

## Target Changes
1. `src/macosArm64Main/kotlin/atlas/model/SkillAuditViolation.kt`:
   - Introduce `SkillAuditViolation` data class with `rule`, `skillName`, `relPath`, `lineNumber`, `message`, `target`.
2. `src/macosArm64Main/kotlin/atlas/model/AuditReport.kt`:
   - Add `skillViolations: List<SkillAuditViolation> = emptyList()` to `AuditReport`.
3. `src/macosArm64Main/kotlin/atlas/engine/ArchitectureAuditor.kt`:
   - Implement `checkSkillViolations(projectId, prefixes)` running:
     - `checkBrokenSkillReferences(projectId, prefixes)` -> `SKILL-001`
     - `checkSkillSchemaFrontmatter(projectId, prefixes)` -> `SKILL-002`
     - `checkSkillDependencyCycles(projectId, prefixes)` -> `SKILL-003`
   - Factor `skillViolations.isEmpty()` into `AuditReport.passed`.
4. `src/macosArm64Main/kotlin/atlas/mcp/AtlasMcpServer.kt`:
   - Add `"audit_skills"` action to `atlas_audit` schema and dispatcher.
5. `src/macosArm64Main/kotlin/atlas/cli/AtlasCli.kt`:
   - Ingest `skill_violations` in `runAuditArchitecture` summary and pass/fail gate.
6. `src/macosArm64Test/kotlin/atlas/EngineAndMcpTest.kt`:
   - Add unit tests verifying `SKILL-001`, `SKILL-002`, and `SKILL-003` detection, diagnostics, and cycle detection.
