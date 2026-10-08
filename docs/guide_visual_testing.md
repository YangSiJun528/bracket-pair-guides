# Run and review visual contracts

Read [the rendering reference](reference_visual_testing.md) before changing
Driver setup, fixture, scenarios or reviewed images. Use Linux x86-64; native
macOS captures are not compatible with these baselines.

## Compare against reviewed baselines

```sh
export VISUAL_TEST_ENVIRONMENT=ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1
export LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC
xvfb-run -a -s '-screen 0 1920x1080x24 -dpi 96' ./gradlew :plugin:visualTest
```

Inspect `plugin/build/reports/tests/visualTest` and the actual images in
`plugin/build/visual-test-artifacts`. A missing image, missing baseline or failed
IDE startup is a failure, not a skipped visual assertion.

## Prepare new baseline candidates

Run only for an intentional suite or rendering change, outside CI:

```sh
xvfb-run -a -s '-screen 0 1920x1080x24 -dpi 96' ./gradlew :plugin:captureVisualTestCandidates
```

This command produces candidates in the artifacts directory and does not
establish comparison success. Review all twelve images for the expected geometry,
current repair and native restoration. In particular, verify that the closing
indentation edit moves the active guide from column 8 to 10; a text-only movement
is not sufficient. Default/custom palette images must contain identical component
choices, width and opacity while showing the intended independent colors.
The same run must also pass the real Settings checkbox/Apply/focus transaction
and public native-conflict notification/balloon observations; captured images alone
do not replace those contracts. Investigate unexpected pixel changes;
do not weaken equality or automatically copy candidates to make a failure pass.
After review, copy the accepted images to
`plugin/src/visualTest/resources/baselines/ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1/`
using scenario names without the `-actual` suffix. Record the source revision,
environment and review decision, then run comparison again.

A rendering removal/disable mutation must make the relevant comparison fail.
Keep the failure evidence when establishing a newly written visual contract.
