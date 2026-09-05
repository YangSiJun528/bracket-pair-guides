---
name: driver-ui-tests
description: Add or maintain IntelliJ IDEA Driver visual UI tests for this repository. Use for end-to-end editor rendering, screenshot, and UI interaction coverage; use the ordinary test suite for non-visual unit and fixture tests.
---

# Driver UI Tests

Use the `visualTest` source set for UI Driver and screenshot coverage. Keep ordinary tests on the existing IntelliJ Community 2024.1.7/JUnit 4 stack; visual tests run with JUnit 5 against IntelliJ Community 2024.2.6 (build 242.26775.15).

Run the visual test process on Java 21 while keeping the production compile target/toolchain at Java 17. Pin Darcula and select only the baseline matching the actual supported OS and architecture.

## Local workflow

- Inspect the existing visual harness and nearby tests before extending it.
- Run visual tests from the repository root with `./gradlew visualTest`.
- Open fixtures directly with `openFile`; do not navigate through dialogs or keyboard search when a direct open is available.
- Synchronize on project/index readiness and observable UI conditions. Use Driver waits and bounded polling; never use `Thread.sleep()` or coroutine `delay()`.
- Exercise the production rendering primitive through the narrow remote bridge. Do not reimplement production drawing or layout logic in test code.
- Make assertions on stable UI state and capture screenshot artifacts that make failures diagnosable. Verify a new assertion fails when the behavior under test is removed or disabled.
- Treat baseline changes as reviewed local artifacts. CI must compare existing baselines and must never update or accept them.

## Upstream reference

For generic IDE Starter/UI Driver selectors, waits, page objects, and diagnostics, consult [references/jetbrains-SKILL.md](references/jetbrains-SKILL.md). It is an unmodified snapshot of JetBrains' IntelliJ monorepo skill, so its module paths, imports, annotations, output paths, TestOps conventions, and `tests.cmd` examples are reference material—not commands or requirements for this repository. See [references/UPSTREAM.md](references/UPSTREAM.md) for provenance and licensing.
