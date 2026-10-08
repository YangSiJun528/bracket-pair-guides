# Preferred three-file proposal: session owns acquisition and release

This proposal supersedes the integration recommendation in `review.md` while
preserving its original single-file `proposed.patch` and supported-API evidence.
Nothing has been applied to production or the frozen ZIP.

## Exact changes

`proposed-session-ownership.patch` combines three minimal changes:

1. `DocumentCalculation`: supported parent-disposable listener registration,
   one disposable per active owner interval, disposal on last owner, fresh
   registration on dormant reacquire, and owner increment after registration
   succeeds. This part is identical to the preserved single-file proposal.
2. `RuntimeGuideWorkFactory.attach`: remove only `calculation.acquire()`.
3. `EditorAnalysisSession`: after all existing fields initialize, acquire the
   calculator in `init`. If acquisition fails, cancel the session root and
   propagate the failure. After successful acquisition, install the existing
   completion hook invoking idempotent close/release.

No new interface, registry, lifetime flag, callback or test getter is introduced.
Full proposed files accompany the patch. Calculation and presentation algorithms,
EDT publication and full/repair/native ticket ordering are unchanged.

## Ownership and caller audit

The session already owns `close` and its calculator release. Owning acquisition
as well removes the factory's undocumented requirement to call acquire before
construction. The factory selects/shares the document calculator; the session
owns its use interval.

Read-only `rg` over analysis-runtime/editor-ui/plugin/benchmarks and redesign
additive output Kotlin/Gradle sources found only one production construction:
`RuntimeGuideWorkFactory.kt:40`. No test, benchmark or additive measurement helper
directly constructs `EditorAnalysisSession`; they use `GuideWorkFactory.attach`.
The only direct `DocumentCalculation.acquire` calls outside that factory are
`AnalysisSessionIdeContractTest.kt:238,249`, on a distinct explicitly constructed
calculator testing stamp reset and dormant reacquisition. Those calls remain
unchanged. Removing the factory call before adding session init acquisition
therefore preserves exactly one acquire per session and does not double-acquire.

A parent canceled during construction is handled by the existing completion
registration: after acquisition, an already completed root invokes close, which
releases once through its existing atomic closed guard. Acquisition failure
occurs before the completion handler is installed, so canceling that root does
not release an owner that was never acquired. All normal initialization state is
ready before the successful completion hook can run.

This is a lifecycle ownership improvement, not a guarantee of recovery from
arbitrary OOM, partially failed SDK registration or an exception thrown by SDK
cleanup. The ordinary acquisition/register failure path cancels its local root
and propagates; no broader session/factory recovery mechanism is claimed.

## Integration and validation

Application waits for the main agent's internal integration instruction after
the active verifier matrix ends. User authorization already exists; no renewed
user approval is requested.

The original `review.md` validation plan remains required: minimum/current actual
SDK29 with identical identities, especially actual shared-editor independence,
stamp reset/dormant reacquire, retained closed-work GC and in-flight close; fresh
root/build-policy/packaging; rebuilt strict13IDE verification including IU263
and241; new source/release provenance before formal performance. Additionally
review compiled construction to confirm no factory acquire remains and the
session completion hook is installed only after successful acquisition. Do not
create a production test seam solely to simulate constructor allocation failure.

Only this proposal directory was written. No build, IDE, Docker, benchmark,
production source modification, commit or external action was performed.
