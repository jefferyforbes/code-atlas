---
contract:
  id: code-atlas-pr-25-sarif-and-visual-exporters
  version: 1
  project: code-atlas
  task: pr-25-sarif-and-visual-exporters
  objective: Export Code Atlas audit findings to standard SARIF 2.1.0 JSON format for GitHub Code Scanning and CI pipelines, and export graph topologies to standard visual representations (Mermaid flowchart, Graphviz DOT, Cytoscape JSON).

status: ready_for_review

size:
  complexity: low
  expected_files: 4
  expected_loc: 600
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
    - code-atlas-pr-24-unified-library-facade
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-25-sarif-and-visual-exporters.md
    - src/macosArm64Main/kotlin/atlas/export/SarifExporter.kt
    - src/macosArm64Main/kotlin/atlas/export/GraphVisualExporter.kt
    - src/macosArm64Test/kotlin/atlas/export/SarifAndVisualExporterTest.kt
    - src/macosArm64Test/kotlin/atlas/AtlasFacadeTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Standard CI Static Analysis Interchange (SARIF-001): Map AuditReport violations to official OASIS SARIF 2.1.0 schema with physical locations, rule IDs, and severity levels.
    - Polyglot Visualization Exporters (VIS-001): Export knowledge graph nodes and edges to Mermaid flowchart, Graphviz DOT, and Cytoscape JSON with layer/module clustering.
    - Zero External Dependencies: Pure Kotlin/Native JSON generation and string rendering.

acceptance_criteria:
  - SarifExporter renders valid SARIF 2.1.0 JSON containing rules, runs, and results for single-decl, layer inversions, complexity, plan collisions, and skill violations
  - GraphVisualExporter renders valid Mermaid flowchart TD/LR syntax with sanitized node IDs
  - GraphVisualExporter renders valid Graphviz DOT directed graph syntax
  - GraphVisualExporter renders valid Cytoscape elements JSON
  - Full unit test coverage in SarifAndVisualExporterTest
  - All tests pass (./gradlew test)
  - review-engine diff clears with score >= 90/100 and 0 Critical/High defects

tests:
  commands:
    - ./gradlew test
    - ~/.local/bin/review-engine diff --base origin/main --project code-atlas --test-suite-passed --plan plans/code-atlas/pr-25-sarif-and-visual-exporters.md
---

# PR 25: SARIF & Standard Graph Exporters

## Context & Objectives
To enable seamless integration into CI/CD pipelines (e.g., GitHub Actions Code Scanning, SonarQube, security dashboards) and visualization tooling, Code Atlas needs standard exporter capabilities.
1. `SarifExporter`: Ingests `AuditReport` and outputs standard SARIF 2.1.0 JSON format.
2. `GraphVisualExporter`: Converts a graph slice (or whole graph) into Mermaid flowchart, Graphviz DOT, and Cytoscape JSON.
