---
contract:
  id: code-atlas-pr-1-skill-graph-extraction
  version: 1
  project: code-atlas
  task: pr-1-skill-graph-extraction
  objective: Deepen skill graph extraction in VaultPlanBridge to parse companion markdown links, script references, and binary executions, emitting typed edges and tracking unresolvable references.

status: merged

size:
  complexity: small
  expected_files: 2
  expected_modules: 1
  expected_tests: 4

predictability:
  confidence: high
  unknowns: []

risk:
  architectural: low
  regression: low
  external_dependencies: low

dependencies:
  prerequisites: []
  blockers: []

parallelizable: false

scope:
  files:
    - src/macosArm64Main/kotlin/atlas/bridge/VaultPlanBridge.kt
    - src/macosArm64Test/kotlin/atlas/EngineAndMcpTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Syntactic: Markdown link and backtick regex extraction in VaultPlanBridge
    - Semantic: GraphEdge emissions for REFERENCES_FILE, EXECUTES_SCRIPT, DEPENDS_ON_SKILL
    - Pragmatic: Test assertions in EngineAndMcpTest

acceptance_criteria:
  - Markdown links in SKILL.md to relative companion files (e.g. ./references/playbook.md) emit REFERENCES_FILE edges to indexed file nodes or vault notes
  - Backtick and code block references to scripts (e.g. scripts/run_regression.py or bin/tool.kexe) emit EXECUTES_SCRIPT edges when matching workspace paths
  - Relative links to non-existent local files emit edges with an unresolvable marker or are tracked in doc nodes/metadata for audit consumption
  - Existing skill slash command extraction and DEPENDS_ON_SKILL edges remain fully backwards compatible
  - All existing and new tests in EngineAndMcpTest pass cleanly

tests:
  commands:
    - ./gradlew macosArm64Test
---

# Plan Contract: PR 1 - Skill Graph Extraction & References in VaultPlanBridge

## Objective
Enable Atlas to understand the concrete dependency graph of agent skills by parsing relative companion markdown links, script invocations, and executable references inside `SKILL.md` files.

## Target Changes
1. `src/macosArm64Main/kotlin/atlas/bridge/VaultPlanBridge.kt`:
   - Detect markdown links pointing to companion documentation (e.g. `./references/...`, `../references/...`, `./resources/...`).
   - Resolve relative paths against the skill's directory location.
   - Detect script and binary invocations in backticks or code blocks (e.g. `.py`, `.sh`, `.kexe`, `.bash`, `.rb`, `.ts`, `.js`, or paths starting with `scripts/` or `bin/`).
   - Emit typed edges: `REFERENCES_FILE`, `EXECUTES_SCRIPT`, `DEPENDS_ON_SKILL`.
2. `src/macosArm64Test/kotlin/atlas/EngineAndMcpTest.kt`:
   - Add unit tests verifying that skill companion links emit `REFERENCES_FILE` edges.
   - Add unit tests verifying that script invocations emit `EXECUTES_SCRIPT` edges.
   - Add unit tests verifying unresolvable relative links emit identifiable edge metadata for downstream audits.
