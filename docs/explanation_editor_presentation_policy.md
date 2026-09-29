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

Document edits retain the synchronous repair contract: the document callback
repairs or removes stale active geometry before it returns on EDT. A later
background pass can discover new pairs, but cannot be required to fix stale
pixels left by the edit.

## Secondary-editor lifecycle

Normal editors continue using IntelliJ's highlighting pass. Secondary editors
also have a coalesced, cancellable read-action scheduler because some code
viewers never receive daemon passes. It reuses the same highlighting-pass
analysis and publication path rather than introducing another renderer.

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
refresh without a daemon pass. Existing Intention preview tests also verify
that the pass factory and event initialization produce no effects during preview
computation.

The Driver suite checks that moving focus to Settings removes active
presentation while preserving token colors, then restores the existing exact
editor baseline after returning. Headless fixture tests supply activity
explicitly; production eligibility does not special-case unit-test mode.
