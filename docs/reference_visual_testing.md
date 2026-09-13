# Visual Testing Reference

This document is the maintained contract for the IntelliJ Driver visual-test
harness, committed baselines, GitHub Actions producer, and trusted pull-request
reporter. The suite protects a small set of user-visible editor outcomes. It is
not an exhaustive preference matrix.

## Coverage boundary

Use Driver screenshot coverage when a change affects pixels that a user sees
in the editor and an ordinary test cannot establish the complete rendered
result. This includes guide geometry, active-pair layers, bracket-token colors,
live settings transitions, and the visible interaction between plugin guides
and IntelliJ's native guides.

Keep state-machine branches, ownership bookkeeping, restoration edge cases,
external overrides, and notification state transitions in the ordinary test
suite unless they introduce a new visual result. Settings pages, notification
balloons, Classic UI, Remote Development, split editors, third-party plugins,
additional IDE products, and product- or language-specific lanes are outside
the current screenshot contract.

Do not form a Cartesian product of persisted preferences. Each scenario must
represent one meaningfully different visual responsibility and, within a
comparison group, change only the setting named by the scenario. If a state is
pixel-identical to an existing scenario, reuse that baseline as an assertion or
cover the state in an ordinary test; do not add a duplicate gallery image.

Exact equality with the committed baseline is the only visual pass/fail oracle.
There are no tolerances, numerical pixel deltas, or previous-run comparisons.

## Scenario catalog

Scenario identifiers use lowercase kebab case and describe user-visible
behavior rather than implementation details. The identifier is stable across
the harness, baseline filename, artifacts, reporter allowlist, and gallery.
Scenarios are grouped by the feature a reviewer is evaluating.

| Group | Scenario | Visual contract |
|---|---|---|
| Rendering components | `horizontal-only` | Horizontal active-guide segments are visible; the vertical segment, pair border, and pair background are absent. |
| Rendering components | `vertical-only` | The vertical active-guide segment is visible; horizontal segments, pair border, and pair background are absent. |
| Rendering components | `pair-border-only` | The active brackets have a border; guide segments and pair background are absent. |
| Rendering components | `pair-background-only` | The active brackets have a background; guide segments and pair border are absent. |
| Rendering components | `all-components` | Horizontal and vertical guides, pair border, and pair background are visible together. |
| Rendering components | `bracket-colorization-off` | The guide remains visible while bracket tokens use IntelliJ syntax colors. |
| Settings application and native visuals | `plugin-disabled` | A previously decorated editor loses plugin-owned rendering after the plugin is disabled through production settings application. |
| Settings application and native visuals | `native-visuals-unmanaged` | Matched-brace highlighting, Current scope, and regular indent guides coexist visibly with the plugin guide while the integration gate leaves them unmanaged. |
| Settings application and native visuals | `native-highlight-suppressed` | The default native-highlight suppression is applied while the regular IntelliJ indent guide remains visible. |
| Colors | `default-palette` | Several nesting levels use the built-in palette. |
| Colors | `custom-palette` | Clearly distinct bracket, guide, border, and background colors update the live editor. |

Transition assertions do not create additional captures for the pull-request
gallery. Disabling the plugin after a decorated, native-managed state must
restore the original native values and produce `plugin-disabled`; the harness
keeps the pre-disable decorated capture in memory, and re-enabling must exactly
reproduce it. Disabling only the integration gate after it has managed native
settings must restore the original values and exactly reproduce
`native-visuals-unmanaged`.

## Pinned rendering environment

Every value below is part of the baseline identity. A change to one of these
pins requires intentional baseline review on both supported operating systems.

| Property | Pinned value |
|---|---|
| IDE | IntelliJ IDEA Community 2024.2.6 |
| Driver/Starter test framework | build `242.26775.15` |
| Visual-test Java runtime | Java 21 |
| Production compile target and toolchain | Java 17 |
| Theme | Darcula |
| IntelliJ UI mode | New UI; Classic UI is outside this contract |
| Editor font | JetBrains Mono, 14 pt, line spacing 1.0 |
| Editor chrome | sticky lines, Code Vision, and the intention bulb disabled |
| Text antialiasing | `awt.useSystemAAFontSettings=on` and `swing.aatext=true` |
| UI scale | `sun.java2d.uiScale=1` and `ide.ui.scale=1` |
| IDE frame | origin `(100, 100)`, size `1280 x 900` |
| Stable editor crop | top-left `220 x 240` pixels, excluding the scrollbar |
| Fixture source | `plugin/src/visualTest/testData/guide-project/src/Sample.java` |
| Runtime fixture | `src/Sample.java` in the generated visual-test project |
| Rendering, palette, and plugin-disabled caret | one-based line 7, column 20 (`total += inner`) |
| Native-visual caret | one-based line 6, column 62 (the repeated-line conflict position from #27) |
| Locale and time zone | English (US), UTC |

The committed baseline path is
`plugin/src/visualTest/resources/baselines/<environment>/<scenario>.png`.
Exactly two environment keys are supported:

| Environment key | Required host |
|---|---|
| `ideaIC-2024.2.6/macos-aarch64-darcula-scale1` | macOS on Apple silicon |
| `ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1` | Linux x86-64 under the pinned 96 DPI Xvfb display |

The environment key must match the actual operating system and architecture.
Linux requires the explicit `VISUAL_TEST_ENVIRONMENT` value and an Xvfb screen
of `1920x1080x24` at 96 DPI. A missing or mismatched key fails; the harness
never falls back to another operating system, architecture, theme, DPI, or
scale.

## Harness execution model

`visualTest` starts the IDE once. All scenarios run sequentially in that one
Driver session so that transitions and cleanup are tested against real prior
state. The Gradle task has one test fork and must not parallelize scenarios.

Before every scenario, the harness restores a complete deterministic boundary.
It first disables the plugin through production `applySettings(...)` so that
plugin rendering and native-setting ownership are released. It then restores
matched-brace highlighting and Current scope, sets the global regular-indent-
guide preference to the scenario's initial fixture value, refreshes open
editors, and verifies the effective value. Rendering and color scenarios start
with regular indent guides disabled so their captures isolate plugin-owned
pixels; native-coexistence scenarios and `plugin-disabled` start with them
enabled. This is deterministic IntelliJ fixture setup, not a plugin preference.
Finally, the harness removes secondary carets and selections, places the primary
caret at the scenario's pinned position, expands all folds, and scrolls the
viewport to horizontal and vertical offset zero before applying the scenario
preferences.

It then applies a complete preference value derived from the scenario group's
explicit base snapshot, with only the intended scenario delta inside that
group. The `plugin-disabled` scenario must first reach a decorated state and
then disable the plugin; a fresh disabled startup does not satisfy the
contract. Its pre-disable decorated capture and the native-coexistence scenarios
start with matched-brace highlighting, Current scope, and regular indent guides
enabled.

A baseline mismatch is collected instead of aborting the session. The harness
continues capturing later scenarios and reports all mismatches after the final
comparison. A setup failure that prevents a valid capture still fails with
bounded diagnostics.

## Production and test-only boundaries

Every committed plugin preference, including enabled state, component flags,
native-integration choices, and palette values, passes through
`BracketGuideSettingsController.applySettings(...)`. This is the same
production boundary used by the Settings UI and is required for live-editor
refresh, ownership, and restoration behavior to participate in the test.

The test-only Driver bridge may provide only:

- primitive or `String` transport across the remote boundary;
- deterministic IDE setup, including the theme, frame, editor appearance,
  fixture state, caret, selection, folding, viewport, and initial IntelliJ
  native values; and
- observable state queries used by bounded waits and assertions.

The bridge must not reproduce production drawing or layout logic, directly
edit persisted plugin options, bypass `applySettings(...)`, manufacture
highlighters, or define a separate visual state machine. The Driver API remains
outside the production plugin classpath.

## Readiness and screenshot stabilization

Open the fixture directly with Driver. Wait for the project to open, background
indicators to finish, the fixture to be indexed, code analysis to complete,
settings to be observed in the live editor, and the expected rendering state
to become visible. All waits use bounded Driver polling against observable
conditions. Fixed sleeps, coroutine delays, and timing-only readiness checks
are forbidden.

Before capture, hide the blinking caret and intention bulb, verify that the
editor is showing, repaint and synchronize the UI, and restore the pinned crop.
The screenshot is stable only after two consecutive cropped images are exactly
equal. Screenshot stabilization is bounded; failure produces diagnostics rather
than accepting the last frame.

## Baseline task contract

| Operation | Invocation | Contract |
|---|---|---|
| Compare | `./gradlew visualTest` | Captures every scenario and compares decoded pixels exactly; never modifies baselines. |
| Record missing | `./gradlew :plugin:recordVisualTestBaseline` | Creates missing files after a complete capture; existing files remain immutable. |
| Replace intentionally | add `-PforceVisualBaselineOverwrite=true` to the record task | Replaces existing files only for an intentional visual or pinned-environment change. |
| Record on Linux | set the Linux environment key and run the record task under the pinned 96 DPI Xvfb command | Rejects an absent or mismatched platform key. |

Each environment contains exactly one reviewed PNG for each catalog entry: 11
macOS images and 11 Linux images. Recording is rejected when `CI=true`; CI
cannot create, overwrite, accept, or download a replacement baseline. The
[visual-test maintenance guide](guide_visual_testing.md) defines the recording,
review, negative-proof, and replacement procedure.

## Pull-request workflows

### Untrusted producer

`Visual Test Scenarios` runs pull-request code with read-only repository
permission. It validates and checks out the exact requested head SHA without
persisting credentials, executes the visual test under the pinned Linux Xvfb
environment, and uploads captures plus bounded diagnostics. It cannot write
repository contents or pull-request comments.

For pull requests, the producer runs only when all of these conditions hold:

- the pull request is open for testing and is not a draft;
- it has the `visual-test` label;
- it either targets `main` directly or is the top pull request in an official
  GitHub Stack rooted at `main`; and
- the event is the qualifying label addition, synchronization, reopening, or
  transition to ready for review.

Adding an unrelated label does not run the suite. Producer concurrency is per
pull request and cancels an older in-progress run when a newer head is queued.

Manual dispatch accepts a full 40-character lowercase commit SHA and tests that
exact commit. Manual runs have independent concurrency and do not create a
pull-request report because they have no trusted pull-request association.

### Trusted reporter

`Visual Test Report` subscribes to `Visual Test Scenarios` through
`workflow_run` and executes only trusted default-branch workflow code. It has
the write permissions needed to publish images and update the pull request, but
it never checks out, imports, or executes pull-request code.

Before any publication, the reporter fetches current GitHub state and
revalidates that there is exactly one associated same-repository pull request,
it is open and non-draft, the `visual-test` label remains present, the tested
SHA is still the current head, and it targets `main` directly or is still the
top of an official Stack rooted at `main`. Stale or ineligible runs are not
published.

Reporter publication is serialized without cancelling an older publication in
progress. The sticky-comment run/attempt marker prevents an older completed run
from replacing a newer report.

## Artifact and report contract

Producer artifacts are untrusted input. The reporter accepts only allowlisted
artifact names, rejects duplicates and oversized artifacts, verifies the
scenario association and fixed workflow/image schema, and validates downloaded
files as regular, bounded `220 x 240` RGBA PNGs. Unexpected paths, symbolic
links, malformed images, and inconsistent artifact IDs fail reporter
validation. On a rerun, artifacts are restricted to the current attempt's
validated visual-test job time window before names or IDs are accepted. The
producer workflow supplies the pinned Linux environment; there is no metrics or
manifest artifact whose environment value the reporter trusts.

Every scenario produces `<scenario>-actual.png`. On mismatch, the harness also
copies the committed expectation to `<scenario>-baseline.png`; passing
scenarios do not publish a redundant baseline artifact.

Validated images are re-encoded and published to the `visual-test-results`
branch under a pull-request number and tested-head path. The comment uses URLs
pinned to the immutable publication commit, never a moving branch URL.

The reporter maintains one bot-owned sticky comment per pull request. It
contains the status, tested commit, workflow run, and collapsed galleries
grouped as **Rendering components**, **Settings application and native
visuals**, and **Colors**. A successful scenario shows only its current capture. A failed
scenario shows its committed baseline and current capture together. The normal
report does not contain changed-pixel counts, channel deltas, mean deltas, a
diff image, or Metrics JSON.

Bounded diagnostics include the Gradle log, JUnit reports and results, Driver
artifacts, UI hierarchy, geometry, and available Starter diagnostics. They are
linked from the sticky comment and retained for 14 days. A successful run with
missing required captures, a baseline without its current capture, rejected or
inconsistent supplied artifacts, or failed image publication makes the
reporting job fail. A producer run that already failed during setup may report
validated mismatch pairs if any exist, but normally links diagnostics only; the
absence of a complete capture set does not add a second reporter failure.

## Scenario maintenance contract

Catalog changes are atomic across the harness, both baseline directories,
producer uploads, reporter validation and galleries, this reference, and the
baseline README. An addition requires a distinct user-visible responsibility,
a behavior-based kebab-case name, an existing feature group, and negative proof
that the corresponding production behavior affects the exact assertion.

A baseline may be replaced only for an intentional rendering or pinned-
environment change. A scenario may be removed only when its user-visible
behavior no longer exists or another named scenario proves the same contract;
distinct transition assertions remain even when they reuse a gallery baseline.
The [visual-test maintenance guide](guide_visual_testing.md) gives the complete
add, replace, and remove procedure.
