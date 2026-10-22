# Editor presentation policy

Calculation answers which bracket pairs and guide geometry exist. Presentation determines which of those answers an editor should show. Focus, visibility, and rendering ownership can change without changing the document's bracket structure.

## Support and activity

`EditorEffectGuard` checks the originating execution context before session creation or work scheduling. Intention-preview computation with copied PSI and a mock editor must create no plugin effects. A real editor displaying preview output is a separate supported surface.

`EditorSurfaceClassifier` classifies factory-owned `EditorEx` instances by editor kind and one-line mode. Main editors support token colors, active endpoint emphasis, and both guide directions. One-line main editors support horizontal guides only. Preview, diff, console, and untyped editors use a conservative colors-only policy. A read-only main editor can still have an interactive caret. Unsupported or calculation-only editors have no presentation capability.

`EditorPresentationPolicy` resolves capabilities, persisted preferences, and captured activity into an `EditorPlan`. Analysis coverage follows supported features and preferences, independently of focus. Presentation additionally requires visibility; guides and active endpoint emphasis require activity. `EditorActivitySource` captures Swing visibility, focus, and associated popup ownership on EDT. Background calculation does not read those facts. The resulting transient preference view is never persisted or shared between editors.

## One presentation owner

`ui.editor.EditorGuide` owns visible token windows, tracked pair markers, guide geometry, presentation revisions, and markup for one editor. It retains a read-only `BracketView` for rendering, while runtime owns source identity and accepted-result authority. UI owns no calculation job or coroutine scope.

`GuideDemand` is the UI-owned desired-state contract. `EditorGuide` submits one complete demand when content, configuration, or presentation changes. It does not sequence full-analysis cancellation and repair scheduling. `GuideWork` hides that ordering; `GuideView` receives result and repair updates on EDT.

Losing focus removes active guides and endpoint emphasis while token colors and a current view remain available in the visible editor. Regaining activity can reuse the current result. Hiding removes markup and revokes background work and publication authority; UI releases its view and runtime releases the accepted result. Returning to a visible editor requests fresh background analysis, so guides appear when current results arrive.

On becoming visible, runtime validates the suspended result against current document, highlighter, file type, tab size, requested coverage, language selection, semantic environment and file-size eligibility. Only runtime can approve synchronous EDT reapplication with the new demand revision. UI does not retain or independently trust a hidden `BracketView`. Content or disabled-language changes, no pair-dependent coverage, and close discard the suspended entry. Ordinary writes to another document do not alone invalidate a result. Reentrant SDK rendering still requires post-callback validation before acceptance. A late result must satisfy current demand and presentation ownership before it can display anything.

## Rendering can reenter

IntelliJ markup calls can synchronously invoke listeners. `RenderFrames` assigns each render an authority frame, and nested rendering or close makes the outer frame obsolete. Presentation helpers check that authority around SDK effects. Each frame tracks the marks it creates; rollback disposes only marks still owned by that frame, leaving marks adopted by newer rendering intact.

`EditorGuide.applyAnalysis` and `applyRepair` return `ViewApplication.APPLIED` only after current rendering commits. An obsolete frame returns `OBSOLETE`. Rendering failures clean up owned effects and propagate rather than claiming success. Runtime checks validity again after the callback before recording acceptance. Runtime lifecycle locking never encloses SDK rendering.

This design localizes ownership rules; it does not make synchronous listener behavior harmless by assumption. Reentrant close, document/configuration changes, failed highlighter creation, and newer-frame adoption are separate contract cases to validate. Supported `afterAdded` callbacks can reenter rendering, but IntelliJ forbids adding range markers from a `beforeRemoved` callback. Frame cleanup preserves resources owned by newer rendering within supported SDK operations; it cannot guarantee rollback after arbitrary listener exceptions or forbidden nested SDK mutations.

## Edits and immediate repair

A document callback updates tracked endpoints synchronously before submitting one content demand. A surviving tracked pair can retain its current renderer while background work calculates the edited guide position. This applies to document changes regardless of whether they came from spaces, tabs, replacement, or a multiline paste. No input-specific cache or indentation scan is added to presentation.

`GuidePositionFallback` still proves unchanged geometry for same-line pairs, edits outside a tracked pair, and admitted horizontal-whitespace changes strictly to the right of the existing guide column. The existing 256-character event-fragment and 4,096-character coordinate-prefix admission limits remain. If the proof fails, presentation replays the last complete visible guide drawing while tracked endpoints and repair interest continue to update. It does not connect an old guide column to newly moved endpoints. A current repair or authoritative guide replaces the frozen drawing in one paint; until then the whole line can remain at its former position.

The renderer retains one vector outline for the visible area, generated independently of a partial repaint clip. It stores no screen bitmap and performs no extra indentation analysis. Without a prior valid drawing it waits for current geometry. Scrolling, viewport resizing, font/scale or soft-wrap setting changes, folding and inlay changes discard that drawing; normal rendering resumes when current geometry arrives. The snapshot is cleared with its owning markup.

Visible geometry and repair interest are independent. Once geometry becomes provisional, later edits and caret callbacks cannot certify it as current merely because a renderer remains present. The demand continues to include a `RepairIntent` until a valid current repair or authoritative guide replaces it. Provisional pixels do not produce native-conflict evidence. A repair updates the existing highlighter and renderer rather than clearing it first.

Runtime starts repair independently of full analysis. Core calculates after bounded prefix capture releases read access; the 256-line and 32,768-consumed-character budgets, earliest-line tie rule, zero-column early stop, cancellation and source-revision checks remain unchanged. Exact repair may refuse, leaving provisional geometry until authoritative analysis arrives. No fixed upper bound on that display interval is promised.

A changed or deleted tracked bracket, invalid markers, caret departure, unsupported layout/source transition, lost presentation ownership, disabling, and close still remove affected markup immediately. This retention describes the already tracked pair and does not certify current lexical pairing. Stale repairs cannot replace newer document state. This revises the immediate-hiding policy recorded in [ADR 0001](adr/0001-hide-affected-guides-before-background-repair.md).

## Event and native adapters

`ui.editor.events.EditorGuideEvents` observes existing and new editor surfaces, settings, visibility, and source changes. Normal daemon passes reach `GuideWork.refresh` through the plugin highlighting-pass adapter. Secondary surfaces submit demands even when the daemon does not supply a pass. Runtime starts an independent worker, so a daemon read lock is not inherited.

Native paint evidence crosses `GuideWork.observeNativeGuide` only as displayed geometry and revision. Runtime owns inspection and proof validity; `NativeGuideAdvisory` owns notification episodes and suppression. A stale native proof cannot be reinterpreted as evidence for a newly displayed guide.

Recognition still depends on installed language lexers and brace matchers. Surface policy does not add support for HTML/JCEF code blocks or independent terminal renderers, and introduces no time-based animation.

## Verification boundaries

Policy contracts cover supported features, activity, and independent guide directions. Headless SDK contracts use explicit activity inputs and actual editor markup to cover editor kinds, independent publication and close lifetimes for two editors sharing a document, reentrant or failing markup, provisional retention, invalid-pair removal, stale-result rejection, and lifetime release. Driver contracts separately exercise genuine Swing focus and visibility transitions, Settings Apply, and rendered colors and geometry in actual editor surfaces. Pure calculation tests establish neither Swing rendering nor IDE compatibility; visual checks alone establish neither source validity nor cancellation.

Current validation records identify completed and unexecuted checks. [Analysis execution](explanation_analysis_execution.md) explains worker/source authority, and [Module architecture](explanation_module_architecture.md) explains why UI cannot import calculation implementations.

## Repair publication on current platforms

From the 2025.3 minimum, bounded repair publishes editor markup through
`Dispatchers.UI`. The final background read validates file/type identity, and
the publication checks that no write has begun since that validation, plus
current editor content, highlighter, tab size and request ownership. An invalid
result is retried rather than painted. Full analysis and native-advisory paths
still use `Dispatchers.EDT` because their callbacks access the platform model.
No scheduler, input batching or repaint-area optimization is added.
