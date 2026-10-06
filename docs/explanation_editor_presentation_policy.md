# Editor presentation policy

Bracket analysis and visible presentation have different lifetimes. Moving focus
away from an editor should remove its active guide without discarding a valid
bracket snapshot. A secondary code viewer may need token colors without needing
an active-pair index or custom guide geometry at all.

## Execution safety precedes surface policy

`EditorEffectGuard` checks the originating execution context before scheduling
UI work or creating a session. Intention preview computation uses a copied PSI
file and a mock editor on a background thread. It must produce no plugin UI
effects, including token markup and `invokeLater` calls. A real editor displaying
the resulting preview is a separate surface and can receive token colors.

`EditorSurfaceClassifier` accepts factory-owned `EditorEx` instances and reads
their editor kind and one-line mode. It does not probe support by attempting
rendering or swallowing unsupported-operation exceptions. Read-only status does
not disqualify a main editor: a viewer can still have an interactive caret.

## Stable support and transient activity

`EditorPresentationPolicy` is a pure function of capabilities, persisted
preferences, and captured activity. Its `EditorPlan` contains two results:

- Analysis coverage depends on capabilities and preferences, independent of
  focus and visibility.
- Presentation policy additionally depends on current visibility and whether
  the editor owns focus, directly or through a child popup.

Main editors support token colors, active endpoint emphasis, and both guide
directions. One-line main editors support horizontal guides only. Preview, diff,
console, and untyped editors use a conservative colors-only policy. These are
plugin defaults, not platform prohibitions. An unsupported or calculation-only
editor has no presentation capability.

`EditorActivitySource` reads Swing visibility, focus, and associated IntelliJ
popup ownership on EDT. Background analysis never reads those UI facts. Each
session applies a transient preference view derived from its presentation
policy; that view is never persisted or shared with another editor.

## Session transitions

`EditorGuideSession` owns the policy and the accepted analysis for one editor.
Two views of the same document can therefore have different presentation and
analysis requirements.

Losing focus removes the active guide and endpoint emphasis while preserving
token colors. Hiding an editor removes its markup. Regaining activity reuses a
current snapshot; it does not invalidate analysis just because focus changed.
Results arriving from a background pass are checked against current document,
lexer, settings, and coverage, then displayed according to the current policy.
An old request cannot revive a guide in an inactive editor.

A document edit adjusts tracked endpoint ranges and hides an affected guide
before the callback returns on EDT. `GuidePositionFallback` uses only geometry:
a same-line pair has column zero, and an edit outside the tracked pair can reuse
its guide. Neither `guideAfterChange` nor `guideFor` scans document text, PSI, or
tokens.

Missing indentation starts an immediate `GuideRepairExecution` job, independent
of full analysis. It captures immutable line prefixes in short cancellable read
actions and computes indentation after releasing read access. Post-edit repair
preserves the 256-line and 32,768-consumed-character bounds, earliest-line tie
rule, and zero-column early stop. A refused exact repair leaves the guide hidden.
Ordinary provisional repair preserves its closing-line, previous-anchor, then
forward candidate order; its bounded approximation is not an authoritative
snapshot.

The session owns the repair job and the tracked-pair generation. An edit, caret,
settings, tab-layout, or lifecycle change cancels superseded work. EDT publication
also validates the captured source stamp and the exact session/pair ownership.
An authoritative snapshot cancels repair and wins. Adjusted endpoint emphasis
can remain visible while the affected guide is hidden. The trade-off is recorded
in [Hide affected guides before background repair](adr/0001-hide-affected-guides-before-background-repair.md).

## Secondary-editor lifecycle

Normal editors request work through IntelliJ's highlighting pass; secondary
editors request it through `SecondaryEditorAnalysis` because some code viewers
never receive daemon passes. Both are adapters to `EditorAnalysisExecution`,
which owns capture, background calculation, and EDT publication. A pass may run
under the daemon's read lock, but its independent worker begins on
`Dispatchers.Default` without inheriting that lock.

Full analysis for secondary editors has a 75 ms debounce. Semantic supersession
cancels the previous request immediately, before that delay. Compatible requests
can share a pending richer calculation; current accepted coverage can also be
reused. The delay does not apply to main-editor full analysis or guide repair.

The scheduler observes existing and newly created editors. It requests analysis
when a secondary editor becomes visible, its document or highlighter changes,
or relevant settings change. Hidden editors defer new work until shown. Editor
release cancels pending work and removes listeners and session-owned markup.

Recognition still requires a compatible language lexer and brace matcher. This
policy does not add support for HTML/JCEF code blocks or independent terminal
renderers, and does not introduce time-based animation.

## Verification boundaries

Pure tests cover capabilities, settings, activity, and independent guide
directions. Platform fixtures cover real editor kinds, read-only main editors,
shared documents, delayed results, visibility transitions, and secondary-editor
refresh without a daemon pass. Repair fixtures assert immediate hiding, eventual
geometry, cancellation, stale-result rejection, and EDT writes while calculation
is paused outside read access. Existing Intention preview tests also verify
that the pass factory and event initialization produce no effects during preview
computation.

The Driver suite checks that moving focus to Settings removes active
presentation while preserving token colors, then restores the existing exact
editor baseline after returning. Headless fixture tests supply activity
explicitly; production eligibility does not special-case unit-test mode.

[Analysis execution](explanation_analysis_execution.md) explains the host and
pure calculation seams and their extension points. The
[performance reference](reference_performance_limits.md) lists capture and
repair bounds.
