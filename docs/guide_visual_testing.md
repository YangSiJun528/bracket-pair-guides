# Maintain Visual-Test Scenarios

Use this guide to add, replace, remove, or intentionally re-record an IntelliJ
Driver visual scenario. Read the
[visual-testing reference](reference_visual_testing.md) first; it defines the
supported coverage boundary, exact scenario catalog, deterministic pins, and
security contract.

## Prepare the change

1. Confirm that the change creates a distinct user-visible editor result that
   an ordinary test or an existing baseline cannot prove.
2. Choose a behavior-based kebab-case identifier and one existing feature
   group.
3. Preserve unrelated working-tree changes and inspect the nearest ordinary
   tests, especially native ownership and restoration coverage.

## Update a scenario

1. Reset the full scenario boundary, then define a complete group-base
   preference snapshot with only the intended scenario delta.
2. Send every preference through production `applySettings(...)`; keep the
   Driver bridge limited to primitive/String transport, deterministic setup,
   caret refresh, and observable queries.
3. Add the capture to the end-of-session mismatch collection. Use bounded
   readiness polling and two consecutive exact stable crops.
4. Update the producer uploads, reporter allowlists and galleries, both baseline
   catalogs, and the reference in the same change.
5. For removal, delete both operating-system baselines and every consumer of
   the identifier. Preserve any distinct transition assertion that reused the
   removed image.

## Record and review baselines

Run the exact comparison first:

```bash
./gradlew visualTest
```

Create only missing baselines after the behavior and pins are stable:

```bash
./gradlew :plugin:recordVisualTestBaseline
```

On Linux, use the required environment and display:

```bash
VISUAL_TEST_ENVIRONMENT=ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1 \
  xvfb-run --auto-servernum \
  --server-args="-screen 0 1920x1080x24 -dpi 96 -nolisten tcp -ac" \
  ./gradlew :plugin:recordVisualTestBaseline
```

Use `-PforceVisualBaselineOverwrite=true` only when an intentional visual or
pinned-environment change must replace existing files. Inspect every changed
macOS and Linux PNG, verify the directory contains exactly the catalog's 11
files, and rerun the comparison task on both platforms. Never record or accept
a baseline in CI.

## Prove failure sensitivity

Temporarily remove or disable the corresponding production rendering behavior,
or invert the scenario's requested visual state while keeping readiness valid.
Run `./gradlew visualTest` and confirm that the named readiness or exact-
baseline assertion fails. A mutation that reaches a valid capture must also
emit its baseline/current pair. Revert the temporary mutation completely,
rerun the exact comparison, and retain no sabotage code or generated mismatch
artifact.

## Validate the complete change

Run the ordinary suite so non-visual ownership, restoration, override, and
notification contracts remain intact:

```bash
./gradlew check
```

Validate workflow syntax and embedded reporter JavaScript, inspect the final
baseline inventory and PNG dimensions, and review the producer/reporter diff
for read-only pull-request execution and final pre-publication revalidation.
