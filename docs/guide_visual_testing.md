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

## Run without interrupting the desktop

Start Docker, then run from the repository root:

```bash
./scripts/visual-test-background.sh
```

The runner tests the current working tree, including uncommitted changes, in a
Linux container with its own Xvfb display. IDE focus and mouse actions stay
inside that display. The terminal remains attached to show progress; here,
"background" means that the test does not take over the host desktop.

Read the log and reports in the printed
`build/visual-test-background/run.XXXXXX` directory, where the suffix is unique
to each run. This command compares the existing Linux baselines only. Run the
native macOS comparison separately when macOS validation is required.

The first run downloads the container image, IDE, and build dependencies and
can require several gigabytes. The runner reuses
`build/visual-test-background/cache`; set `VISUAL_TEST_CACHE_DIR` to an absolute
directory to use another dedicated Linux cache. Apple silicon runs the x86-64
container through emulation, so it can take longer than native tests.

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

For a crop-only contract change, crop the committed baselines on both operating
systems with the same fixed rectangle used by the harness. Verify that every
retained decoded pixel matches the original baseline and that only pixels
outside the visual contract were removed. For the current contract, the source
rectangle is `(0, 1, 220, 239)` within the former `220 x 240` crop. Update the
trusted reporter's required image dimensions in the same change, inspect the
full editor screenshots in diagnostics for clipping, and rerun comparison on
both platforms. Mechanical migration preserves prior rendering expectations;
it does not replace a successful comparison run.

When the image schema changes, give the producer a new workflow name and update
the reporter's `workflow_run` subscription and name validations in the same
change. Preserve the producer path checks and require only the new dimensions.
For the current `Visual Test Scenarios v2` rollout, inspect the introducing pull
request's uploaded captures and diagnostics directly: its producer runs, but
the previous default-branch reporter does not consume v2 runs. The updated
reporter handles eligible v2 completions after merge. Do not record baselines
in CI or loosen the reporter to accept both schemas during the transition.

When comparison fails, inspect the baseline/current pair and full editor
screenshot before identifying a product regression. A mismatch reports
different pixels and still fails the exact assertion; its cause may also be an
intended change or a difference in the pinned rendering environment. Keep the
comparison strict within the fixed crop instead of adding a whole-image
tolerance.

## Prove failure sensitivity

Temporarily remove or disable the corresponding production rendering behavior,
or invert the scenario's requested visual state while keeping readiness valid.
Run `./gradlew visualTest` and confirm that the named readiness or exact-
baseline assertion fails. A mutation that reaches a valid capture must also
emit its baseline/current pair. Revert the temporary mutation completely,
rerun the exact comparison, and retain no sabotage code or generated mismatch
artifact.

For a crop change, also verify that removing a guide or shifting it by one pixel
inside the retained area still fails exact comparison. Excluding the editor tab
boundary must not reduce sensitivity to plugin rendering.

## Validate the complete change

Run the ordinary suite so non-visual ownership, restoration, override, and
notification contracts remain intact:

```bash
./gradlew check
```

Validate workflow syntax and embedded reporter JavaScript, inspect the final
baseline inventory and PNG dimensions, and review the producer/reporter diff
for read-only pull-request execution and final pre-publication revalidation.
