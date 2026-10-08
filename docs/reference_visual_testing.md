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
| `default-palette` | Token colors use the persisted default level palette. |
| `custom-palette` | Custom token, guide, border and background colors remain independent. |
| `edited-geometry` | An indentation edit produces current repaired guide geometry. |

The same session observes actual Swing focus loss/restoration and component
visibility loss/restoration. Focus loss removes active guide markup while
retaining token colors; hidden editors remove all plugin markup and visible
editors resume it. An A/B/A caret cycle must settle to the exact original
`all-components` pixels. This does not prove a particular stale background task
entered or completed. Runtime authority unit tests cover stale proof rejection.
Immediate hiding before the document edit callback returns is asserted by the
UI-owned IDE contract; a later Driver screenshot alone cannot prove that timing.

The production settings command applies all preference changes. A test-only
bridge transports primitives, sets deterministic appearance, edits the fixture
and observes actual markup. It is packaged only in the Driver archive.

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
