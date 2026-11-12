---
contract:
  id: code-atlas-pr-28-reactive-watch-stream
  version: 1
  project: code-atlas
  task: pr-28-reactive-watch-stream
  objective: Implement AtlasWatchSession providing filesystem polling, incremental graph reindexing, and GraphDelta change events for reactive daemon and tooling integration.

status: merged

size:
  complexity: low
  expected_files: 3
  expected_loc: 500
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
    - code-atlas-pr-27-architecture-rule-dsl
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-28-reactive-watch-stream.md
    - src/macosArm64Main/kotlin/atlas/watch/AtlasWatchSession.kt
    - src/macosArm64Test/kotlin/atlas/watch/AtlasWatchSessionTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Reactive Watch Session (WATCH-001): Detect filesystem mutations (created, modified, deleted source files), trigger incremental indexing, and compute structured GraphDelta change sets (added nodes, removed nodes, stats).
    - Event Dispatch Pattern: Provide listener registration and callback dispatch for real-time daemon integration.
    - Single Declaration (BOOST-003): Encapsulate GraphDelta and listener logic inside AtlasWatchSession.

acceptance_criteria:
  - AtlasWatchSession accurately detects when files have changed vs when no change occurred
  - pollOnce() triggers incremental indexing and computes exact added and removed nodes in GraphDelta
  - registerListener() invokes callbacks on detected changes
  - Unit tests verify change detection, node addition, node removal, and listener dispatch
  - All tests pass (./gradlew test)
  - review-engine diff clears with score >= 90/100 and 0 Critical/High defects

tests:
  commands:
    - ./gradlew test
    - ~/.local/bin/review-engine diff --base origin/main --project code-atlas --test-suite-passed --plan plans/code-atlas/pr-28-reactive-watch-stream.md
---

# PR 28: Reactive File Watcher Stream & Incremental Delta Notification

## Context & Objectives
To enable IDE extensions, continuous testing runners, and autonomous agent loops to react instantly to codebase changes, Code Atlas needs a reactive watch session.
1. `AtlasWatchSession`: Wraps `AtlasClient` to track file records, disk modifications, and deletions.
2. `pollOnce` / `triggerCheck`: Checks disk against cached file records, triggers incremental reindex, and computes `GraphDelta`.
3. `GraphDelta`: Structured report containing changed files, added nodes, removed node IDs, and updated stats.
4. Listener registration for event-driven workflows.
