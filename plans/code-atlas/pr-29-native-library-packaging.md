---
contract:
  id: code-atlas-pr-29-native-library-packaging
  version: 1
  project: code-atlas
  task: pr-29-native-library-packaging
  objective: Configure native library packaging in build.gradle.kts (Apple Framework AtlasKit and C dynamic library libatlas) and provide AtlasNativeInterop bridge for Swift, Objective-C, and native C consumers.

status: merged

size:
  complexity: low
  expected_files: 4
  expected_loc: 400
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
    - code-atlas-pr-28-reactive-watch-stream
  blockers: []

parallelizable: false

scope:
  files:
    - plans/code-atlas/pr-29-native-library-packaging.md
    - build.gradle.kts
    - src/macosArm64Main/kotlin/atlas/interop/AtlasNativeInterop.kt
    - src/macosArm64Test/kotlin/atlas/interop/AtlasNativeInteropTest.kt
  modules:
    - :macosArm64Main
    - :macosArm64Test
  architecture:
    - Native Multi-Target Packaging (PKG-001): Configure Kotlin/Native binary targets for Apple Framework (AtlasKit.framework for Swift/Obj-C) and shared library (libatlas.dylib for C/C++ FFI) linking system sqlite3.
    - Native Interop Gateway (PKG-002): Provide AtlasNativeInterop entry point for foreign callers with version metadata, client lifecycle management, and clean bridge methods.
    - Single Declaration (BOOST-003): Declare exactly 1 public top-level object AtlasNativeInterop.

acceptance_criteria:
  - build.gradle.kts defines framework (AtlasKit) and sharedLib (atlas) binary targets
  - AtlasNativeInterop provides version, openInMemory, openPersistent, and closeClient methods
  - Unit tests verify AtlasNativeInterop bridge behavior and lifecycle
  - All tests pass (./gradlew test)
  - review-engine diff clears with score >= 90/100 and 0 Critical/High defects

tests:
  commands:
    - ./gradlew test
    - ~/.local/bin/review-engine diff --base origin/main --project code-atlas --test-suite-passed --plan plans/code-atlas/pr-29-native-library-packaging.md
---

# PR 29: Native Library Packaging & Foreign Interop Gateway

## Context & Objectives
To enable Code Atlas to be embedded into macOS GUI applications (Swift/SwiftUI), native CLI utilities (C/C++), and IDE plugins without requiring a JVM or CLI process execution:
1. Binary packaging: Configure `framework { baseName = "AtlasKit" }` and `sharedLib { baseName = "atlas" }` in `build.gradle.kts`.
2. Interop gateway: `AtlasNativeInterop` providing clean lifecycle and client instantiation for foreign language runtimes.
3. Verification: Unit tests verifying client lifecycle via the interop gateway.
