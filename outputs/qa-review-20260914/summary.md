# QA revalidation — 2026-09-14

Code: `82a9e03c445339914cc3deea046745eb87e71e14`, based on `main` at `a1ab8f3`.
These are local results on macOS arm64, not a GitHub Actions run.

## Verified

| Check | Result |
|---|---|
| `./gradlew check` | Passed: 354 tests in 50 classes; no failures or skips. Includes formatting and benchmark compilation checks. |
| `:plugin:buildPlugin` | Passed; version 0.0.5 archive rebuilt from this code. |
| `:plugin:verifyPluginProjectConfiguration` | Passed. |
| `:plugin:verifyPluginStructure` | Passed. |
| `:plugin:verifyPlugin` | Passed against all 8 configured IDE builds. |
| Workflow YAML | All 3 workflow files parsed. |
| Reporter JavaScript | Both embedded GitHub Script blocks compiled for syntax validation; not executed. |
| Baseline inventory | Both OS directories contain 11 PNGs at 220 × 240. No baseline changes. |
| Automated test selection | `visualTest` and `recordVisualTestBaseline` each discover only the 4 automatic tests; `ManualQaLauncher` is excluded. Dry-run only, not visual validation. |

Verified IDEs: IC-241.19416.15, IC-242.26775.15, IC-243.28141.41,
IC-251.29188.72, IC-252.28539.97, IU-253.28294.334, IU-261.22158.277,
IU-262.8665.258. The verifier still reports deprecated and experimental API
usage in the test bridge, but no configured failure-level violations.

## Not yet verified

The final `:plugin:visualTest` attempt failed while waiting for project
indicators during initialization. The diagnostic screenshot was black;
macOS reported `CGSSessionScreenIsLocked=Yes`. Sleep/wake events also occurred
during the run. This is not a valid rendering comparison and does not prove
either a rendering regression or a pass. The new foreground check was not
reached in that attempt.

Before the foreground check was added, the shared manual launcher verified
native settings enabled, a plugin guide present, and 18 bracket-token
highlighters. Its screen capture showed another app, so it was not accepted as
visual proof. That QA IDE was closed before running the automated suite. Final
manual QA startup remains pending an unlocked desktop.

The [earlier gallery](../visual-qa-20260914.JUO6jt/index.html) is retained as
historical evidence only. It has 4 exact matches and 7 mismatches at the top
capture boundary; those mismatches remain unresolved. Baselines and the exact
comparison policy have not been weakened or replaced.

Linux/Xvfb visual execution, Qodana inspection, and the remote GitHub Actions
pipeline have not been run for this commit. Workflow syntax and local Gradle
checks alone do not establish that remote CI is green.

## Resume

1. Unlock the macOS desktop and keep it awake for screen capture.
2. Run `./gradlew :plugin:visualTest` and inspect every actual/baseline pair.
3. Run `./gradlew :plugin:runManualQa` after the automated IDE closes. Confirm
   the editor capture and `MANUAL QA READY` before using the
   [manual checklist](../../docs/guide_manual_qa.md).

## Archive

The rebuilt [0.0.5 ZIP](../bracket-pair-guides-0.0.5.zip) is a QA build, not a
visual-test-approved release. SHA-256:
`fc92eb55c8c8adf653e1c664f3bb9b71a48923ce960757273e4e4ddaf939d99f`.

This artifact commit contains the rebuilt ZIP, the earlier review gallery and
its images/geometry, and this sanitized summary. IDE caches, personal settings,
raw environment-bearing logs, unrelated application screenshots, and other
tasks' artifacts are excluded and left locally untouched.
