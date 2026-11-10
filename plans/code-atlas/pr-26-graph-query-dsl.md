---
contract:
  id: code-atlas-pr-26-graph-query-dsl
  version: 1
  project: code-atlas
  task: pr-26-graph-query-dsl
  objective: Provide GraphQueryEngine with fluent topological traversal, circular dependency cycle detection, Kahn topological sort, island/root/leaf node identification, and all-paths search.

status: ready_for_review

size:
  complexity: low
  expected_files: 3
  expected_loc: 650
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
    - code-atlas-pr-25-sarif-and-visual-exporters
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-26-graph-query-dsl.md
    - src/macosArm64Main/kotlin/atlas/query/GraphQueryEngine.kt
    - src/macosArm64Test/kotlin/atlas/query/GraphQueryEngineTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Topological Graph Engine (QUERY-001): Implement high-performance graph traversal algorithms including Tarjan/DFS cycle detection, Kahn's topological sort, island/root/leaf discovery, and bounded all-paths search.
    - Subgraph Slicing: Extract targeted upstream/downstream neighborhood slices (GraphSlice) for visualization or localized analysis.
    - Zero External Dependencies: Pure Kotlin algorithms over GraphReader interface.

acceptance_criteria:
  - GraphQueryEngine.findCycles() detects directed dependency cycles and reports exact cycle path
  - GraphQueryEngine.topologicalSort() returns valid topological ordering for DAGs and null when cycles exist
  - GraphQueryEngine.findIslands() identifies isolated nodes with zero degree
  - GraphQueryEngine.findRoots() identifies entrypoint nodes (in-degree 0, out-degree > 0)
  - GraphQueryEngine.findLeaves() identifies terminal nodes (out-degree 0, in-degree > 0)
  - GraphQueryEngine.findPathsBetween() computes all simple directed paths between two nodes up to maxHops
  - GraphQueryEngine.subgraph() extracts upstream, downstream, or bidirectional neighborhood slices
  - Full unit test coverage in GraphQueryEngineTest
  - All tests pass (./gradlew test)
  - review-engine diff clears with score >= 90/100 and 0 Critical/High defects

tests:
  commands:
    - ./gradlew test
    - ~/.local/bin/review-engine diff --base origin/main --project code-atlas --test-suite-passed --plan plans/code-atlas/pr-26-graph-query-dsl.md
---

# PR 26: Graph Query & Topological Traversal Engine

## Context & Objectives
To enable sophisticated architectural analysis, Code Atlas needs high-level topological query capabilities beyond basic 1-hop lookups.
1. `findCycles`: Fast DFS back-edge cycle detection reporting circular dependency chains.
2. `topologicalSort`: Kahn's algorithm ordering dependencies for build order or layer validation.
3. `findIslands`, `findRoots`, `findLeaves`: Topology shape classification.
4. `findPathsBetween`: Multi-hop path enumeration between any two symbols.
5. `subgraph`: Neighborhood extraction for scoped visualization and impact slicing.
