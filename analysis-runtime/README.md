# Analysis execution ownership

`runtime.bootstrap.RuntimeGuideWorkFactory` is the composition entry point. It
exports only the UI-owned `GuideWorkFactory` contract. One runtime session owns a
single editor's source generation, accepted result, refusal, and independent full,
repair, and native inspection tickets. Reconciliation hides the ordering of work
revocation and replacement from UI callers.

`runtime.capture.EditorSource` implements the public core input contracts using
bounded SDK read actions. Core calculation starts off EDT and outside read access.
Core retries unrelated writes; a changed editor source aborts the old work and
the session schedules against the current source. A private document holder
shares a calculator and monotone content revision between split editors.
Capture, cache reuse, and publication use that revision, so a save-state stamp
reset alone does not discard valid results. Its last session releases the
document listener; reacquisition invalidates any unobserved content interval.

Hidden demands cancel work and revoke publication immediately. A visibility-only
hide moves the accepted result into one per-session JDK SoftReference, permitting
synchronous EDT restoration on a valid unchanged return. Memory pressure can
clear it; a miss schedules normal full analysis. UI markup/view remain released.
Content/language invalidation, no-facet configuration and close clear this dormant
cache. Source identity, coverage, layout, file-size eligibility and a separate
file-type/plugin environment generation guard reuse; unrelated document writes
still invalidate in-flight reads but do not alone discard completed dormant results.
The document calculator retains only weak index references; there is no second
cache registry or extra lease policy. Closing drops the editor, view, calculator,
and coroutine-context attachment; in-flight lexical captures unwind before their
references can be collected.

EDT publication checks source, demand, lifetime, and the lane ticket. The view may
reenter or fail during rendering. Acceptance is committed only after a successful
view result and another validity check, under the same narrow lifecycle lock used
by close. SDK rendering never runs while that lock is held. Secondary editors
retain a 75 ms delay; repair starts immediately.
Invalidated work reschedules only while it still owns its lane ticket, so late
completion cannot cancel newer work. Failed requests become retryable; repair
deduplication also validates its source and environment.

`runtime.nativeproof` collects and validates bounded native marker evidence. Its
per-editor evidence gate rejects stale source, caret, episode, and A→B→A
observations by proof identity, independently of SDK admission conditions.
Settings ownership, notification suppression, and display decisions belong to UI.
No runtime implementation imports UI types outside `ui.work`.

Tests ending in `IdeContractTest` use the actual SDK dispatcher, read actions, and
editor lifetime. `AnalysisCaptureObserver` is an internal coroutine-context
measurement observer; it is neither a service nor a mutable production setter.
