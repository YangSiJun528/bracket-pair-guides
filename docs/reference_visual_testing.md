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
editors resume it. An A/B/A caret cycle must settle to the exact original
`all-components` pixels. This does not prove a particular stale background task
entered or completed. Runtime authority unit tests cover stale proof rejection.
The Driver edit transport also asserts that the affected guide highlighter is
absent inside the write command immediately after the document edit returns.
The UI-owned IDE contract checks the corresponding synchronous content-demand
boundary. A later screenshot alone cannot prove hiding timing.

The registered Settings dialog is opened through the public SDK. Driver activates
the real integration checkbox and Apply control. The draft toggle must leave
persisted options and native settings unchanged until Apply. Apply must restore
the native values; closing the dialog and restoring focus, without moving the
caret or requesting analysis, must reproduce the unmanaged scenario pixels.

In the unmanaged scenario, the suite observes a new notification from the native
conflict group through the public project notification bus and its actual balloon
visibility. The pinned Driver adapter observes `BalloonImpl.isVisible` and its Swing
component showing state; the interface exposes animation history rather than visibility. Production delivers that notification only after a painted vertical guide
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
There are no tolerances, percentage deltas or previous-run acceptance. Two
successive exact captures establish stability before comparison. Missing
reviewed baselines fail the comparison. Candidate capture never writes into
`src/visualTest/resources/baselines` and is prohibited in CI.

Original test implementations and the eleven old PNGs were removed under the
approved redesign. Historical raw evidence remains in Git/outputs; it is not a
pass for the new suite. New images require review against these behavior
contracts, rather than accepting whatever the candidate happens to paint.

CI uploads standard test/image artifacts with read-only repository permission.
There is no privileged PR-comment or external-image reporter.
