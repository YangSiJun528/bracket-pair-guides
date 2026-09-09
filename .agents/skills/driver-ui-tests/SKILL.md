---
name: driver-ui-tests
description: Add or maintain IntelliJ IDEA Driver visual UI tests for this repository. Use for end-to-end editor rendering, screenshot, and UI interaction coverage; use the ordinary test suite for non-visual unit and fixture tests.
---

# Driver UI Tests

Use the `visualTest` source set for end-to-end editor rendering. Read the
[visual testing reference](../../../docs/reference_visual_testing.md) before
changing the harness, baselines, artifact producer, or trusted reporter. It is
the source of truth for coverage, deterministic pins, scenario taxonomy,
security boundaries, and baseline policy. Follow the
[maintenance guide](../../../docs/guide_visual_testing.md) for the full change,
recording, and validation procedure.

## Local workflow

1. Inspect the existing scenario and its nearest ordinary tests. Add screenshot
   coverage only for a distinct user-visible result; avoid preference Cartesian
   products and duplicate images.
2. Keep all scenarios in the existing one-session `visualTest` harness. Reset
   scenario state, apply plugin preferences through production
   `applySettings(...)`, and use the bridge only for deterministic setup,
   primitive transport, and observable queries.
3. Open fixtures directly and use bounded Driver waits for readiness, applied
   state, and stable screenshots. Do not use fixed sleeps or timing-only delays.
4. Run `./gradlew visualTest` from the repository root. Confirm a new visual
   assertion fails when its production rendering is removed or disabled.
5. Record locally with `./gradlew :plugin:recordVisualTestBaseline` only for an
   intentional change. Review both supported OS baselines; CI never records or
   accepts them.
6. When the scenario set changes, update the harness, both baseline sets,
   producer, reporter, reference, and baseline README together.

## Upstream reference

For generic IDE Starter/UI Driver selectors, waits, page objects, and diagnostics, consult [references/jetbrains-SKILL.md](references/jetbrains-SKILL.md). It is an unmodified snapshot of JetBrains' IntelliJ monorepo skill, so its module paths, imports, annotations, output paths, TestOps conventions, and `tests.cmd` examples are reference material—not commands or requirements for this repository. See [references/UPSTREAM.md](references/UPSTREAM.md) for provenance and licensing.
