# Visual test baselines

Generate the pinned IntelliJ IDEA 2024.2.6 baselines explicitly with:

```bash
./gradlew :plugin:recordVisualTestBaseline
```

Baselines are stored separately for the two supported rendering environments:

- `ideaIC-2024.2.6/macos-aarch64-darcula-scale1`
- `ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1`

On Linux, set
`VISUAL_TEST_ENVIRONMENT=ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1`
only when the test runs in the pinned 96 DPI Xvfb environment. The environment
key deliberately includes the Darcula theme; mismatched OS, architecture,
theme, DPI, or scale never falls back to another baseline.

The visual test process uses Java 21. The plugin's production compile target and
toolchain remain Java 17.

`visualTest` never updates these files. It captures both candidates before it
fails with a recording command when either baseline is missing.

Recording is refused in CI and will not replace an existing PNG unless the
operator explicitly adds `-PforceVisualBaselineOverwrite=true`.

The test writes `ui-geometry.json` and the complete Driver Swing hierarchy as
`plugin/build/visual-test-artifacts/ui-hierarchy.html`. Starter also retains its
own diagnostics when a UI operation fails.
