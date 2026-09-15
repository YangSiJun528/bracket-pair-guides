# Visual test baselines

This directory contains the reviewed PNG oracle for the 11 scenarios defined in
the [visual testing reference](../../../../../docs/reference_visual_testing.md).
Each supported environment directory must contain exactly one
`<scenario>.png` for every name below.

| Group | Baseline names |
|---|---|
| Rendering components | `horizontal-only`, `vertical-only`, `pair-border-only`, `pair-background-only`, `all-components`, `bracket-colorization-off` |
| Settings application and native visuals | `plugin-disabled`, `native-visuals-unmanaged`, `native-highlight-suppressed` |
| Colors | `default-palette`, `custom-palette` |

The two supported rendering environments are:

- `ideaIC-2024.2.6/macos-aarch64-darcula-scale1`
- `ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1`

The environment key includes the pinned IDE, operating system, architecture,
Darcula theme, DPI where applicable, and scale. A mismatch never falls back to
another directory. Linux recording is valid only under the pinned 96 DPI Xvfb
environment with `VISUAL_TEST_ENVIRONMENT` set to the Linux key.

Every baseline is a `220 x 239` PNG from the fixed editor source rectangle
`(x=0, y=1, width=220, height=239)`. The crop omits only the former top boundary
row, where the selected tab border can vary with IDE focus. Every retained
pixel is compared exactly. Both environment sets were migrated by cropping the
committed `220 x 240` images without changing their remaining decoded pixels;
this migration does not replace comparison on the corresponding platform.

Generate missing IntelliJ IDEA 2024.2.6 baselines explicitly from the repository
root with:

```bash
./gradlew :plugin:recordVisualTestBaseline
```

Recording creates missing files and refuses to replace an existing PNG. For an
intentional user-visible or pinned-environment change, explicitly add
`-PforceVisualBaselineOverwrite=true`, then inspect every changed image on both
operating systems and rerun `./gradlew visualTest`. Never overwrite a baseline
only to clear an unexplained failure.

`visualTest` captures all later scenarios even after a mismatch and never
updates these files. Baseline recording is refused when `CI=true`; CI may only
compare committed baselines. Restoration and re-enable transitions reuse
`native-visuals-unmanaged` and `all-components` rather than adding visually
duplicate PNGs.

The test writes `ui-geometry.json` and the complete Driver Swing hierarchy as
`plugin/build/visual-test-artifacts/ui-hierarchy.html`. Each scenario's full
editor screenshot is retained as `<scenario>-editor.png` in the same directory
for diagnostics, outside the comparison and gallery. Starter also retains its
own diagnostics when a UI operation fails.
