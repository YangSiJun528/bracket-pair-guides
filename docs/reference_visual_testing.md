# Visual contract reference

The redesigned suite preserves eleven distinct rendered contracts and adds one
edited-geometry contract in a single real IntelliJ session. Each has its own
independently reviewed image and exact pixel comparison.

| Scenario | Expected visible contract |
|---|---|
| `horizontal-only` | Horizontal guide segments, without the vertical segment or pair overlays. |
| `vertical-only` | Vertical guide segment, without horizontal segments or pair overlays. |
| `pair-border-only` | Pair endpoint borders without guides or background overlays. |
| `pair-background-only` | Pair endpoint backgrounds without guides or borders. |
| `all-components` | All guide segments, pair borders and backgrounds together. |
| `bracket-colorization-off` | Guides and pair overlays remain; plugin bracket token colors are absent. |
| `plugin-disabled` | Plugin markup is absent and previously owned native settings are restored. |
| `native-visuals-unmanaged` | Original native brace/scope highlighting remains alongside plugin visuals. |
| `native-highlight-suppressed` | Native brace gate is suppressed, with regular indent guides retained. |
| `default-palette` | All components use the default level palette, width 3 and background opacity 45%. |
| `custom-palette` | The same components, width and opacity use independent custom token, guide, border and background colors. |
| `edited-geometry` | Closing indentation changes from column 8 to 10; the repaired active guide follows column 10. |

The same session observes actual Swing focus loss/restoration and component
visibility loss/restoration. Focus loss removes active guide markup while
retaining token colors; hidden editors remove all plugin markup and visible
editors resume it. A warmed caret cycle moves between inner and outer multiline
pairs and the original empty same-line pair, returning to the inner pair after
each transition (A/B/A/C/A). Each actual caret move must return with the same
valid guide highlighter, the new pair's exact endpoint ranges and unchanged token
ranges in the same EDT callback. It issues no explicit analysis request, event
pump or analysis wait. Editor identity, stamp, showing and focus remain unchanged;
`caret-cycle-synchronous.txt` retains each observation and native brace counts.
This checks immediate SDK presentation state, not every intermediate painted frame
or the absence of native highlights throughout the transition. An empty same-line
pair need not paint a nonempty guide segment. The cycle must settle to the exact
original `all-components` pixels. Before capturing that settled state, the suite also
requires managed native settings and no actual matched/unmatched brace
highlighters, identified by the keys used by IntelliJ's brace handler. Editor,
settings, focus and SDK markup diagnostics are retained around the cycle; two
unchanged frames alone do not establish native readiness.
This does not prove a particular stale background task
entered or completed. Runtime authority unit tests cover stale proof rejection.
The Driver edit transport also asserts that the affected guide highlighter is
absent inside the write command immediately after the document edit returns.
The UI-owned IDE contract checks the corresponding synchronous content-demand
boundary. A later screenshot alone cannot prove hiding timing.

A real FileEditorManager tab cycle first warms `Contract.java` and `TabContract.java`
through normal editor events. Each warmed tab return checks the selected editor's
identity, unchanged document stamp, actual Swing showing state, and the full valid
plugin token range/depth-key list inside the same EDT callback immediately after
selection returns. There is no analysis wait, event pump, or explicit plugin request
before that assertion. Hidden tabs must have no plugin markup. Focus is deferred,
so active-guide restoration is checked separately after the synchronous token
contract. Closing the other tab and restoring focus must reproduce the existing
`all-components` pixels exactly. This adds an interaction contract, not a new PNG
baseline. `tab-switch-observed.txt` retains both synchronous observations and
failure diagnostics; it does not prove that a painted frame never flickers under
all event interleavings.

The registered Settings dialog is opened through the public SDK. Driver clicks
the real integration checkbox and Apply control. The draft toggle must leave
persisted options and native settings unchanged until Apply. Apply must restore
the native values; closing the dialog and restoring focus, without moving the
caret or requesting analysis, must reproduce the unmanaged scenario pixels.

In the unmanaged scenario, the suite observes a new notification from the native
conflict group through the public project notification bus and its actual balloon
visibility. The pinned Driver adapter observes `BalloonImpl.isVisible` and its Swing
component showing state; the interface exposes animation history rather than visibility.
The pinned Linux run observed `isVisible=true`, `isShowing=true`, and
`wasFadedIn=false` for a displayed advisory, so animation completion is not the
visibility oracle. Mutating bridge commands use non-modal write-safe dispatch;
only read-only state observations use arbitrary modality while Settings is open. Production delivers that notification only after a painted vertical guide
requests runtime proof and the proof is accepted. This checks the connected paint,
proof and advisory path, without reading private tickets or substituting evidence.
It does not identify which stale task entered/completed or prove every native IDE
rendering case. The balloon is then dismissed through its public SDK operation to
keep later captures deterministic.

Scenario setup uses the production settings command; the Settings transaction
uses the actual configurable controls. A test-only bridge transports primitives,
sets deterministic appearance (including disabled caret painting and zero scroll),
opens the real dialog, edits the fixture, and observes SDK markup/notifications. It is packaged only in the Driver archive.

## Rendering identity

| Property | Pin |
|---|---|
| IDE | IC 2024.2.6 / build 242.26775.15 |
| Starter/Driver | 242.26775.15 |
| Test JVM | Java 21 |
| Production target | Java 17, Kotlin language/API 1.9 |
| Host | Linux x86-64, Xvfb, DPI 96 |
| Theme/UI | Darcula, New UI |
| Font | JetBrains Mono 14, line spacing 1.0 |
| Scale | `sun.java2d.uiScale=1`, `ide.ui.scale=1` |
| Text AA | `awt.useSystemAAFontSettings=on`, `swing.aatext=true` |
| Frame | `(100,100)` / `1280x900` |
| Crop | editor `(0,1,220,239)` |
| Locale/time zone | en_US / UTC |
| Chrome | sticky lines, Code Vision and intention bulb disabled |
| Fixture | `plugin/src/visualTest/testData/Contract.java` |

Exact equality of dimensions and every ARGB pixel is the only image oracle.
There are no tolerances, percentage deltas or previous-run acceptance. Initial
setup uses Driver's standard background-indicator/stable-smart-mode wait before
waiting for file analysis. This includes asynchronously discovered JDK roots;
one highlighted file does not establish completion of project indexing.
Before and after every screenshot, the selected editor must match, indexing must
be inactive, the document must be committed, and all daemon dirty scopes must be
complete at the same document stamp. Two successive exact captures then establish
stability within the unchanged 30-second capture budget. Setup and per-capture
readiness, stamps, document highlighters and editor markup are retained, including
on failure. Missing
reviewed baselines fail the comparison. Candidate capture never writes into
`src/visualTest/resources/baselines` and is prohibited in CI.

Original test implementations and the eleven old PNGs were removed under the
approved redesign. Historical raw evidence remains in Git/outputs; it is not a
pass for the new suite. New images require review against these behavior
contracts, rather than accepting whatever the candidate happens to paint.

Three initial redesign oracles were deliberately corrected after establishing
settled SDK state. The two native scenarios had retained identifier-usage fills
from a previous caret location; the edited image omitted a recalculated native
indent-guide segment. Unchanged production at `072533f`, using the same twelve
scenarios and readiness policy, produced images identical in every ARGB pixel and
PNG byte to the redesigned implementation. Nine initial images were unchanged;
the two native images differed by 2,218 pixels each and the edited image by 20.
This counterfactual capture is diagnostic evidence, not a pass against the old
oracles. Independent review selected the baseline-production captures for those
three corrections; no production behavior, tolerance, crop or pixel comparison
was changed. The previous images and decision are preserved in
`outputs/issue-97/redesign/driver/settled-oracle-review/`, and the cross-production
comparison is in `driver/baseline-counterfactual-01/cross-production-comparison.json`
under the same redesign output root. This correction does not waive fresh exact
comparison or a rendering-removal mutation.

CI uploads standard test/image artifacts with read-only repository permission.
There is no privileged PR-comment or external-image reporter.
