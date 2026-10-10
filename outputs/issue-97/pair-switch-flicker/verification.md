# Active pair transition investigation

Status: user-reported flicker is not yet reproduced. This change adds missing transition coverage; it does not claim a production fix. Production remains the tab-return implementation at `743ad4a` on `codex/prepare-0.1.0`.

## Findings

A warm accepted `BracketView` supplies the next active pair and geometry synchronously. `EditorGuide.caretMoved` updates the current guide renderer on EDT; runtime does not discard its accepted result or schedule full analysis solely for this presentation demand. The full-document guide highlighter is reused. Endpoint highlighters are replaced within the same EDT turn. Their remove/add callbacks alone do not establish a painted blank interval.

Separate paths can legitimately remove a guide: an edit invalidates affected geometry (ADR 0001), missing geometry needs repair, a highlighter/layout change invalidates the view, lost activity hides active markup, and an empty same-line pair may have no nonempty line to paint. Which path occurs in the reported QA case remains unconfirmed. Questions about caret-only versus typing/deletion and the affected decoration are pending.

## Added contracts

- UI-owned SDK tests move between two multiline pairs using real editor coordinates and markup with an injected read-only result. They check the same renderer/highlighter, current endpoint ranges, preserved token resources and immediate nonempty paint of the current geometry. A second test checks nested caret reentry from a supported SDK `afterAdded` callback.
- Driver uses actual caret events for inner multiline A / outer multiline B / A / original empty same-line C / A. Each move returns with the original valid guide mark, exactly the new endpoints, unchanged token ranges, document stamp, editor, visibility and focus. No explicit plugin request, event pump or analysis wait precedes the assertions.
- Existing native readiness, all twelve exact image baselines, tab-return, edit hiding, Settings Apply and other Driver contracts remain in place. Native brace counts are recorded at each transition and checked separately at the existing readiness boundary.

## Executed verification

| Check | Result | Evidence |
|---|---|---|
| Actual IC 2024.1.7 SDK | 52 passed, 0 failed/error/skipped | `ide-check/minimumSdkTests/`, `ide-check/summary.json` |
| Actual IU 263.6259.32 SDK | 52 passed, 0 failed/error/skipped | `ide-check/currentSdkTests/`, `ide-check/summary.json` |
| Pinned IC 2024.2.6 Linux Driver | Passed; immediate A/B/A/C/A state checked | `driver-current/review.json`, `plugin/build/visual-test-artifacts/caret-cycle-synchronous.txt` below that directory |
| Image comparisons | 12/12 reviewed PNGs byte-identical; after-caret and after-tab images also byte-identical to all-components | `driver-current/review.json` |
| Production identity | 451/451 Driver release classes byte-identical to the previously delivered tab-fix ZIP | `driver-current/review.json` |
| Delayed-presentation mutation | Expected failure at the new immediate endpoint assertion; outer caret 105 still had old inner endpoints 66/99 instead of 45/105 | `driver-delayed-mutation-02/review.json`, original XML and observations |

The first mutation attempt stopped before testing because the container image has no Python. Its exit 127 and runner are preserved in `driver-delayed-mutation/`; it is not evidence that the assertion detects a defect. The second runner copied a prepared mutated source into the disposable container only. It queued caret presentation to the next EDT turn and failed specifically because the new endpoints were absent when the caret move returned. The host production file remains byte-identical to HEAD. This establishes sensitivity to delayed presentation; the mutation is not the user-reported bug or an old production version.

## Limits and next decision

The SDK paint comparison verifies the current renderer geometry, using the same renderer implementation for the expected image; it is not an independent drawing algorithm oracle. Driver checks returned SDK state plus settled images, not a time-series proof that every intermediate frame on every platform is flicker-free. This Java fixture does not reproduce the user's particular file, Mac rendering sequence or typing pattern. No conclusion is drawn about all native/highlighter races or runtime capture counts from these tests.

No production code, dependency, threshold, baseline PNG or release version changed. No new install ZIP is required for test-only changes. Full root `check`, JMH/allocation measurements, Qodana and the complete IDE verifier matrix were not rerun for this test-only investigation. Earlier results are not counted as new runs. No remote push, PR, upload or publication was performed.

For a reproduced caret-only blank interval, correct the presentation owner so valid new geometry replaces the old presentation in the current EDT turn without a blank asynchronous handoff. For edit-triggered hiding, preserve ADR 0001 until a narrower invalidation rule proves a particular edit cannot affect the displayed geometry. Retaining a known-stale line or restoring core calculations to UI is not an acceptable workaround.
