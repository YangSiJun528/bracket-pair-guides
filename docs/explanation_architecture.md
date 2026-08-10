# Architecture

Bracket Pair Guides uses a one-way dependency graph from IntelliJ entry points
toward stable analysis contracts and small policy objects. Structural knowledge
is produced only by an IntelliJ highlighting pass; editor events consume an
immutable accepted result and never run a brace matcher synchronously.

This document explains the current design. Exact capacity values, memory
layouts, and complexity formulas belong to the
[performance and capacity reference](reference_performance_limits.md).

## Architecture at a glance

```mermaid
flowchart TB
    subgraph Plugin["plugin"]
        PE["Entry adapters<br/>editor.highlighting · settings.ui"]
        EV["editor.events"]
        ED["editor sessions"]
        PR["presentation"]
        ST["settings"]
        PF["preferences"]
    end

    subgraph Engine["engine"]
        IJ["analysis.intellij<br/>composition"]
        SN["analysis.snapshot"]
        PA["analysis.pairing · analysis.guide"]
        IX["analysis.active · analysis.token"]
        API["analysis contracts"]
        CORE["pairing.core · sorting"]
    end

    PE --> EV
    PE --> ED
    PE --> ST
    EV --> ED
    EV --> ST
    ED --> PR
    ED --> PF
    PR --> PF
    ST --> PF
    PF --> API

    IJ --> SN
    IJ --> PA
    SN --> PA
    SN --> IX
    PA --> API
    PA --> CORE
    IX --> CORE
```

Arrows show the representative dependency direction, not every permitted
import. This is a directed acyclic graph (DAG), not a strict tree. Shared stable
nodes such as analysis contracts, preferences, and the primitive core
legitimately have multiple incoming edges; no edge points back toward a caller.
The executable edge list in
`buildSrc/src/main/kotlin/architecture/ProjectArchitecture.kt` is authoritative.

The package groups have these responsibilities:

| Package group | Responsibility | Stable dependencies |
|---|---|---|
| `analysis` | Module-crossing inputs, outcomes, snapshots, and service contracts | None of the project's implementation packages |
| `analysis.intellij` | IntelliJ composition and host adapters | Contracts, recognition, snapshot policy, guide policy |
| `analysis.snapshot` | Coverage layout, outcome policy, immutable indexes, canonical sharing | Contracts and the feature indexes it assembles |
| `analysis.pairing`, `analysis.guide` | Token recognition and guide-position policy | Contracts and primitive pairing data |
| `analysis.active`, `analysis.token` | Query indexes | Primitive pairing and sorting |
| `analysis.pairing.core`, `analysis.sorting` | Platform-neutral primitive mechanisms | No project packages |
| `preferences`, `settings` | Immutable choices and their persisted IntelliJ state | Contracts or preferences, never editor presentation |
| `presentation` | Per-editor markers, markup, drawing, and token decoration | Contracts and preferences |
| `editor` | Analysis acceptance and one editor session's orchestration | Contracts, preferences, and presentation |
| `editor.events` | IntelliJ event and committed-settings propagation | Editor sessions, settings, and preferences |
| `editor.highlighting`, `settings.ui` | Host entry points | The inward application boundaries required by each entry point |

The runtime path follows the same direction. Each row names the module, thread,
and state owner at that step:

| Stage | Thread | Module and owner | State or output |
|---|---|---|---|
| Structural or coverage change | EDT/platform daemon | `plugin/editor.events`: `EditorGuideEvents` or `GuideSettingsChange` | Invalidate per-editor analysis and schedule background work |
| Collect | Background highlighting read action | `plugin/editor.highlighting`: `BracketGuideHighlightingPass` | One `AnalysisInput`, attempted stamp, and cancellable analysis call |
| Recognize | Same background action | `engine/analysis.intellij`: `IntellijBracketAnalysis` → `DocumentBrackets` → `PairingMachine` | Local matcher groups and pairing state become a primitive pair table |
| Assemble | Same background action | `engine/analysis.snapshot`: `SnapshotAssembly.outcome()` | One immutable `Complete`, `Limited`, or `Unavailable` outcome |
| Publish | EDT | `plugin`: `EditorGuideSession.accept(AnalysisOutcome)` | `EditorAnalysisState` publishes one volatile immutable `AnalysisAcceptance` containing snapshot, completion, and refusal |
| Present and paint | EDT/paint callback | `plugin/presentation`: `ActiveGuidePresentation`, `VisibleTokenDecorations` | Per-editor markers and plugin-owned highlighters; paint owns no structural state |

Caret, viewport, theme, and appearance events take the short path through the
existing editor session. With no current snapshot, they may adjust already
tracked range markers, but they cannot discover a new pair. A background pass
uses `EditorGuideSessions.canSkipAnalysis` only to avoid repeating an already
published attempt; it does not mutate EDT-owned state.

The Gradle dependency direction is `plugin -> engine`; `benchmarks` also
depends on the compiled engine artifact for isolated JMH probes. Engine owns
analysis and immutable results, plugin owns editor lifetime and presentation,
and benchmarks own no production code. The engine artifact is composed into the
plugin's single JAR, so this build boundary adds no runtime classloader boundary.

The `BracketAnalysis` call is synchronous. The concrete IntelliJ composition
uses the pass-provided `ProgressIndicator` and creates no executor or coroutine
scope. Third-party matcher callbacks are confined to this cancellable
background path and are never invoked by an EDT event.

Sharing stops at immutable analysis payloads. Each split editor retains its own
caret memo, range markers, viewport, and markup; shared indexes retain no
editor. Weak document generations prevent the canonical store from extending a
document or editor lifetime.

## Engine boundary

The root `analysis` package contains the contracts that cross the module
boundary. Its two application-service interfaces have separate reasons to
change:

- `BracketAnalysis` analyzes one `AnalysisInput` and returns an
  `AnalysisOutcome`;
- `BraceLanguageInventory` lists the installed brace-matcher language families
  used by Settings.

`plugin.xml` binds those interfaces to `IntellijBracketAnalysis` and
`IntellijBraceLanguageInventory` in `analysis.intellij`. The plugin therefore
depends on contracts, while the IntelliJ-specific composition owns concrete
recognition, snapshot assembly, canonical index storage, guide-position access,
and cancellation wiring. The highlighting factory resolves `BracketAnalysis`
and passes the analysis function into each pass; the pass does not locate its
own service.

The contracts deliberately retain the IntelliJ concepts required by the use
case. `AnalysisInput` carries the actual `Editor` and `FileType`, because token
roles depend on the editor highlighter, installed `LanguageBraceMatching`
extensions, document revision, file type, and tab settings. Neutral copies of
those host concepts would form a second, potentially inconsistent token model.

The policy core is neutral instead. Java code under
`analysis.pairing.core` uses only the Java standard library. A
`PairingMachine` receives normalized token roles, deterministic matching rules,
cancellation, and an output sink. It owns group-isolated stacks, malformed
recovery, nesting depth, and completed-pair ordering. It reads no editor,
document, clock, executor, service, or global state.

`DocumentBrackets` is the recognition boundary inside `analysis.pairing`. It
reads the editor's token stream and resolves only the token language's
`com.intellij.lang.braceMatcher` registration. A `PairedBraceMatcher` is
wrapped with the platform adapter; a matcher that already implements
`BraceMatcher` keeps its contextual behavior. The adapter emits explicit open,
close, toggle, strict-context, and structural roles for the neutral machine.

There is no raw-character fallback, legacy file-type-only matcher fallback, or
product-specific backend. Layered languages remain isolated, while comment and
string behavior follows the host lexer and matcher.

IDE compatibility is a separate startup boundary.
`IdeCompatibilityStartupActivity` checks that the required language brace
matching extension point exists, and `UnsupportedIdeWarning` owns the
application-wide once-only **Unsupported IDE** notification. It does not create
an alternate recognition path.

## Outcome boundary

Every analysis attempt returns one of three authoritative states:

| Outcome | Meaning | Published data |
|---|---|---|
| `Complete` | Every requested facet is exact | A `BracketSnapshot` |
| `Limited` | One optional facet exceeded its independent capacity | An exact lower-coverage snapshot plus the attempted stamp and limit |
| `Unavailable` | Authoritative recognition could not complete | The attempted stamp and limit; no snapshot or capped prefix |

Only guide capacity can produce `Limited`: token and active-pair indexes stay
exact while the guide is omitted. File-size, completed-pair, and pending-open
admission failures produce `Unavailable`.

`AnalysisStamp` records the facts that make a result current: document
identity and revision, highlighter-dependent semantics, file type, requested
coverage, disabled matcher families, and guide-relevant tab settings. A stale
pass is rejected as a unit. Completed stamps and engine-capacity refusals
prevent an unchanged request from entering a background retry loop. The IDE
file-size predicate is rechecked on every pass because the saved or in-memory
length can change independently of the analysis stamp.

`BracketSnapshot` exposes queries for the active pair, guide, and visible token
window. It does not expose assembly objects or concrete index implementations.

## Index assembly and sharing

`AnalysisCoverage` is compiled into one internal index layout before
recognition. `SnapshotAssembly` then builds only the active-pair, token, and
guide facets needed by that layout. Assembly order minimizes overlap between
temporary and retained primitive arrays; details belong to the
[performance and capacity reference](reference_performance_limits.md).

`SnapshotAssembly` receives recognition, cancellation, guide creation, and
canonicalization as values. It decides outcome and index policy without
locating IntelliJ services or retaining a `ProgressIndicator`.
`DocumentGuidePositions` in `analysis.intellij` translates `Document` access
and cancellation into the line data consumed by `GuidePositionIndex`. This
split keeps host I/O in the composition layer and deterministic guide queries
in the policy layer.

The active-pair index resolves crossing intervals deterministically and returns
the innermost containing pair. Malformed recovery honors each platform
`BracePair.isStructural` flag: structural pairs may recover past unmatched
regular openers, while regular pairs cannot cross a structural scope boundary.

`DocumentBracketIndexes` canonicalizes equivalent immutable payloads for the
same document revision and analysis identity. Content hashes are only
prefilters; equality compares the complete observable primitive content.
Token-only payloads do not retain the source pair table. Canonical entries use
weak document, file-type, pair-table, and index references, and a new document
revision replaces the previous generation.

This is result sharing, not global single-flight recognition. Two split editors
may independently collect results because their highlighters cannot be assumed
equivalent before comparison.

## Editor integration

`BracketGuideHighlighting` registers one `TextEditorHighlightingPass`.
Collection happens in the daemon's background read action and application
happens on the EDT. Before recognition, the pass delegates large-file admission
to IntelliJ's code-insight predicate. Unsaved content is checked using the
current document length; saved content uses the virtual file length.

The apply phase accepts a result only if its stamp still matches current
coverage, language selection, file type, document revision, and highlighter
semantics. An admission refusal still reaches the apply phase so stale
plugin-owned markup can be cleared.

`EditorGuideEvents` in `editor.events` routes primary-caret, document, viewport,
editor-lifetime, and color-scheme events to the owning session. It never
performs token iteration. A document change releases stale proportional
analysis structures; existing `RangeMarker` presentation may remain coherent
only for the previously known pair while replacement analysis is pending.

`EditorGuideSession` owns analysis acceptance, requested coverage, lifecycle,
and viewport orchestration. It delegates the active pair's range markers,
guide markup, and bounded provisional behavior to one
`ActiveGuidePresentation`. That aggregate contains `TrackedBracketPair` and
`ActivePairMarkup`, so those objects cannot drift into different lifecycles.
`VisibleTokenDecorations` remains separate because viewport token coloring has
a different state and invalidation cadence.

When exact guide coverage is pending for an already tracked multiline pair,
`GuidePositionFallback` may inspect a bounded amount of leading whitespace to
keep the provisional drawing usable. It does not recognize brackets, and an
authoritative background guide always replaces it. The exact bound is recorded
in the [performance and capacity reference](reference_performance_limits.md).

## Rendering and settings

`BracketGuideDrawing` owns one guide's geometry and appearance. It resolves
visual coordinates with public editor APIs at paint time, paints only within
the current graphics clip, and performs no PSI work or read action.

The plugin retains and disposes only its own highlighters. It never removes all
editor highlighters or deletes markup by a shared layer number.

`BracketGuideSettingsPage` uses the platform `BoundConfigurable` lifecycle and
depends on `BraceLanguageInventory`, not on the concrete matcher catalog.
Persisted language choices are disabled matcher-family IDs rather than a static
allowlist, so an installed language plugin can add a supported family without a
Bracket Pair Guides release. A dialect inheriting its base matcher's capability
shares the same family ID.

`GuideSettingsChange` in `editor.events` applies committed preferences to live
sessions. It asks the IntelliJ daemon for background reanalysis only when
coverage or language selection changes. Appearance-only changes update existing
presentation and do not invoke recognition. Immutable preference values live in
`preferences`; IntelliJ persistence lives in `settings`, preventing the editor
and presentation packages from depending on the storage service.

## ABI boundary

Both Kotlin modules use explicit API mode. Implementation contracts stay
`private` or `internal`. The root `analysis` contracts consumed across the
module boundary are public only for JVM linkage and are annotated
`@ApiStatus.Internal`; they are not a supported consumer API.

Committed ABI dumps are verified during each module's `check` task. The engine
package guard rejects public Kotlin ABI outside the root contracts and rejects
implementation types leaking through them. The deployable plugin's ABI dump
remains empty. A separate engine check rejects `com.intellij` references from
`analysis.pairing.core`, making the neutral-core rule executable rather than
documentary.

The root `checkArchitecture` task also verifies exact Gradle module edges,
registered production packages, permitted project imports, and absence of
module or package cycles. Root and subproject `check` tasks depend on it, so the
DAG is a build invariant rather than a diagram convention. See
[Contributing](../CONTRIBUTING.md#change-an-architecture-boundary) before
changing an edge or ABI baseline.

## Known limitations

- An edit that may alter later nesting requires a new full token recognition.
- Only the primary caret selects an active pair.
- Complete active-scope background shading is intentionally not provided.
- Folded endpoints are not painted.
- The host pass does not traverse separate injected documents on its own.
- An executing third-party matcher callback cannot be forcibly interrupted.
- In malformed input, an unmatched structural opener may conservatively hide a
  regular pair that would be valid only if that opener never closes.
- Split-editor result sharing starts after independently collected results are
  proved equivalent; transient analysis work is not shared.
- Languages without `com.intellij.lang.braceMatcher` are not analyzed, and a
  legacy `com.intellij.braceMatcher` registration alone is insufficient.
- Capacity boundaries may omit guides or make an analysis unavailable. Current
  values and exact fallback behavior are defined only in the
  [performance and capacity reference](reference_performance_limits.md).

## Related documentation

- [Performance and capacity reference](reference_performance_limits.md)
- [IDE and language support reference](reference_language_support.md)
- [Configuration and conflict handling](guide_configuration.md)
- [Run the performance benchmarks](../benchmarks/guide_benchmarking.md)
- [Contributing](../CONTRIBUTING.md)

The historical
[object-design](explanation_object_design.md) and
[test-boundary](explanation_test_boundaries.md) reports record completed
refactoring work. They are evidence for past decisions, not current onboarding
or the source of current limits.

## Platform references

- [Syntax and error highlighting](https://plugins.jetbrains.com/docs/intellij/syntax-highlighting-and-error-highlighting.html)
- [Brace matching](https://plugins.jetbrains.com/docs/intellij/additional-minor-features.html)
- [Services](https://plugins.jetbrains.com/docs/intellij/plugin-services.html)
- [Disposer and plugin unload](https://plugins.jetbrains.com/docs/intellij/disposers.html)
- [Color scheme management](https://plugins.jetbrains.com/docs/intellij/color-scheme-management.html)

## Design references

The refactoring criteria were reviewed against the following lectures from
Inflearn's *클린 코더스: 실전 객체 지향 프로그래밍과 TDD 마스터 클래스*,
taught by **즐거운 학습**. These lectures informed the dependency and
test-boundary decisions; they are not a certification of this implementation.

- [Architecture](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279449): expose use cases and defer framework detail.
- [Single Responsibility Principle](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279452): define responsibility by source of change and keep package dependencies one-way.
- [Dependency Inversion Principle](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279456): direct source dependencies toward policy and stable contracts.
- [TDD 1](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279445): use tests as low-level design feedback and refactor after behavior is green.
- [Split Phase](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279465): separate host I/O from deterministic policy through explicit intermediate values.
