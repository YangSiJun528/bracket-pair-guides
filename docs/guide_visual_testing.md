# Run and review visual contracts

Read [the rendering reference](reference_visual_testing.md) before changing
Driver setup, fixture, scenarios or reviewed images. Use Linux x86-64; native
macOS captures are not compatible with these baselines.

## Compare against reviewed baselines

```sh
export VISUAL_TEST_ENVIRONMENT=ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1
export LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC
xvfb-run -a --auth-file "$HOME/.Xauthority" -s '-screen 0 1920x1080x24 -dpi 96 -nolisten tcp' ./gradlew :plugin:visualTest
```

The pinned Starter removes `XAUTHORITY` from the IDE child environment. Store the
Xvfb cookie in the inherited user home's default `.Xauthority` file so X11
authentication remains enabled without that variable. Keep TCP listening disabled.

Inspect `plugin/build/reports/tests/visualTest` and the actual images in
`plugin/build/visual-test-artifacts`. A missing image, missing baseline or failed
IDE startup is a failure, not a skipped visual assertion. Inspect
`setup-daemon-observed.txt` and each `*-daemon-observed.txt` as well. Initial
setup waits for standard Driver background indicators and stable smart mode,
then file analysis. Each capture separately requires committed, non-indexing,
completed daemon state before and after screenshots at the same stamp, followed
by two identical images. The 30-second capture budget remains unchanged; do not
replace these observations with sleeps or forced daemon/caret events.

Also inspect `caret-cycle-synchronous.txt`. The warmed A/B/A/C/A sequence must
show the new pair endpoints immediately after each real caret move while retaining
the guide highlighter and token window. A later stable screenshot cannot satisfy
this contract. Preserve a controlled delayed-presentation mutation failure when
establishing the assertion; do not interpret same-EDT markup callbacks as proof of
an actual painted blank frame.

Also inspect `tab-switch-observed.txt`. The real warmed-tab A/B/A contract must
restore valid token markup in the selection EDT turn; waiting for later analysis
cannot satisfy it. Focus and active-guide settlement happen afterwards, followed
by exact comparison with the existing `all-components` image. No new baseline is
needed. When verifying this fix, preserve a failure from the pre-fix runtime or a
controlled reverse mutation; a passing settled screenshot alone is insufficient.

Inspect `indentation-cycle-synchronous.txt` for the actual Tab/Shift+Tab body
round-trip. The guide must survive both commands before asynchronous publication
can run, the event coordinates must correspond to real indentation edits, and
restored text/pixels must match the original fixture. Preserve the failure with
the pre-fix production code; do not replace the actions with direct text insertion
or relax the existing closing-line immediate-hide assertion.

## Prepare new baseline candidates

Run only for an intentional suite or rendering change, outside CI:

```sh
xvfb-run -a --auth-file "$HOME/.Xauthority" -s '-screen 0 1920x1080x24 -dpi 96 -nolisten tcp' ./gradlew :plugin:captureVisualTestCandidates
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

When a difference appears to be a native SDK decoration, establish causality
before revising an oracle. The redesign's three settled-state corrections used
unchanged baseline production `072533f` with the identical Driver suite and
rendering environment. All twelve baseline/candidate images matched exactly;
the previous nine unaffected oracles also remained exact. The evidence is in
`outputs/issue-97/redesign/driver/baseline-counterfactual-01/cross-production-comparison.json`;
independent review and the preserved three previous images are in
`outputs/issue-97/redesign/driver/settled-oracle-review/`. Diagnostic capture alone
is not an old-oracle comparison pass. Preserve failures, inspect the semantic
SDK state, and independently review any intentional correction before rerunning
exact comparison and the paint-removal mutation. Never force stale SDK markup
to reproduce an intermediate image.
