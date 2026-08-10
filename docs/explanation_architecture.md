# Architecture and Performance

Bracket Pair Guides separates bracket recognition from editor decoration and
caches the structural result. Caret movement against a current snapshot uses
only the active-pair index; an absent or stale snapshot uses a bounded
`CaretBracketSearch` and never starts a full synchronous document scan.

## Module and package boundaries

The repository has three Gradle modules. `engine` owns bracket analysis,
including the platform-neutral pairing machinery; `plugin` owns editor
integration and the deployable IntelliJ plugin; and `benchmarks` runs selected
compiled engine primitives through an isolated JMH harness.

| Module and package | Responsibility |
|---|---|
| `engine`: `analysis` | The `BracketAnalysis` entry point, immutable inputs, stamps, snapshots, and presentation DTOs |
| `engine`: `analysis.pairing` | Brace-language catalog, document grammar, token classification, and full-document pair recognition |
| `engine`: `analysis.pairing.core` | Host-independent token roles, pairing rules and state, explicit cancellation/output ports, and immutable primitive pair storage |
| `engine`: `analysis.active`, `analysis.token`, `analysis.guide` | Active-pair lookup, visible-token lookup, and guide-position analysis respectively |
| `engine`: `analysis.pipeline`, `analysis.sorting` | Coverage-selected index layout, snapshot assembly, and shared primitive sorting |
| `plugin`: `editor`, `editor.highlighting` | Editor events, session lifetime, settings propagation, immediate resolution, and IntelliJ highlighting passes |
| `plugin`: `presentation` | Token and active-pair decorations, palette resolution, and guide painting |
| `plugin`: `settings` | Immutable persisted options and stable stored-value normalization |
| `plugin`: `settings.ui` | Platform Settings controls, binding, Apply, and Reset entry points |

The Java code in `analysis.pairing.core` uses only the Java standard library. It
reads no document, clock, executor, service, or global state. A `PairingMachine`
session receives normalized token roles and deterministic matching rules
explicitly, mutates only single-owner local state, and emits completed geometry
through a primitive callback. The group-to-rules function is fixed before scanning and
its first result is cached per matcher group, so one group's semantics cannot
change midway through a session. `PairTable.Draft` freezes the output into
immutable parallel arrays. This keeps the observable result deterministic
without allocating a persistent state graph for every token transition.

The engine does not depend on plugin editor-session, presentation, or settings
types. The plugin layer translates persisted options into an `AnalysisInput` or
`CaretContext` and consumes only `BracketSnapshot` queries. The IntelliJ
adapter, index layout, snapshot assembly, sort, and index types stay internal to
the engine. Both
the bounded immediate path and the background highlighting pass therefore share
the same core state machine without exposing its data structures.

The Gradle dependency direction is `plugin -> engine`; benchmarks also depend
on the compiled engine artifact. Engine cannot reference plugin editor-session,
presentation, or settings code. The Kotlin engine and plugin modules use
explicit API mode. File-local implementation is `private`, module-local Kotlin
contracts are `internal`, and the root contracts consumed across the module
boundary are `public` with JetBrains' `@ApiStatus.Internal` marker. The
deployable plugin module has no supported Kotlin library API. The committed
`engine/api/engine.api` dump lists the entire plugin-to-engine bridge, while the
empty `plugin/api/plugin.api` dump asserts that the deployable module exposes no
Kotlin API. Validation rejects public Kotlin engine ABI outside the root
`analysis` package and rejects pairing implementation types in that facade.

Kotlin module metadata enforces `internal` access during compilation.
`@ApiStatus.Internal` additionally tells IDE inspections and Plugin Verifier
that the unavoidable public engine bridge is not a consumer API; it is not JVM
access control. Kotlin ABI validation compares both dumps during each module's
`check` task, so adding or changing public declarations requires an explicit
baseline review. The Java pairing package is implementation code rather than a
published consumer API. The engine artifact is composed into the plugin's
existing single JAR, so the package split does not opt into the experimental
Plugin Model v2 or add a runtime classloader boundary.

At the outer plugin boundary, `IdeCompatibility` represents whether the required
language brace-matching extension point exists. `IdeCompatibilityStartupActivity`
performs the IntelliJ registry lookup, and `UnsupportedIdeWarning` owns the
application-wide once-only guard and error text. An IDE without
`com.intellij.lang.braceMatcher` therefore receives one explicit
**Unsupported IDE** notification.

## Recognition boundary

`BracketAnalysis` is a concrete IntelliJ Application Service registered directly
by its implementation class. It is the only operational plugin-to-engine entry
point:

- `analyze(AnalysisInput, ProgressIndicator)` returns an immutable
  `BracketSnapshot`;
- `resolveActivePair(CaretContext)` returns a bounded `ActivePairKnowledge`;
- `installedLanguages()` returns UI-ready matcher-family DTOs.

`BracketSnapshot` exposes queries for the active pair, exact guide, and a capped
primitive visible-token view. It never exposes `SnapshotAssembly`,
`DocumentBrackets`, or the active, guide-position, and token indexes. Plugin
collaborators receive bound analysis and active-resolution functions, so tests
replace those functions without subclassing or reimplementing `BracketAnalysis`.
Engine tests cover the concrete entry point and internal snapshot assembly.

`DocumentBrackets` reads the editor's token stream and resolves only the token
language's `com.intellij.lang.braceMatcher` registration through
`LanguageBraceMatching`. A plain `PairedBraceMatcher` is wrapped with the
platform's `PairedBraceMatcherAdapter`; a matcher that already implements
`BraceMatcher` is used unchanged. The latter preserves contextual decisions
such as treating the same token type as a brace only at selected positions.
The adapter converts those decisions into explicit `OPEN`, `CLOSE`, or `TOGGLE`
roles plus strict-context and structural roles. The platform-neutral
`PairingMachine` owns group-isolated
stacks, malformed recovery, depth assignment, and completed-pair ordering. It
uses the matcher behind an abstract rule port, so extracting the state machine
does not replace contextual matcher behavior with static character mappings.

An `AnalysisInput` compiles its coverage into one `IndexLayout` before pair
collection. `SnapshotAssembly` then builds the active, token, and guide
artifacts directly in peak-memory order. Options are checked only at those three
assembly phases, never by every token. Full recognition writes one primitive
`PairTable`; token-only layouts detach the smaller token metadata before releasing
that table.

There is no product backend, legacy file-type matcher fallback, or raw-character
fallback. Languages are isolated for layered highlighters, and comments and
strings follow the host language's lexer. If a language-registered matcher also
provides strict tag context, that context remains part of pairing.

Language settings persist disabled matcher-family IDs. A dialect that inherits
the same matcher as its base language shares that base capability ID, so the UI
and token-level analysis cannot disagree about whether the family is enabled.
The disabled-ID set is part of the analysis stamp; changing it invalidates both
the background snapshot and the bounded caret search.
Each production input defensively captures the same set used by its
`DocumentBrackets`. A settings sequence such as A→B→A during collection therefore
cannot produce B-filtered tokens carrying an A stamp.
The platform `TEXT` registration is presented as **Custom file types** because
its matcher consumes the eight official custom syntax-table bracket token
types. Only platform `UserFileType` tokens take this narrow mapping; other
`Language.ANY` tokens and raw plain text are not scanned.

`BracketGuideHighlighting` registers a `TextEditorHighlightingPass`.
The pass collects immutable results under the platform's background read and
cancellation lifecycle, then updates editor markup on the event dispatch
thread. `BracketAnalysis` is synchronous and uses the pass-provided
`ProgressIndicator`; it does not create an executor or coroutine scope. Session
results, range markers, and highlighters are EDT-confined;
background pass deduplication reads only a separately published immutable
analysis stamp. Applying a result updates the active presentation before it
refreshes the larger viewport token window. A pass may replace the session's
analysis dependency and visible-range function only while its collected stamp
still matches the current document, highlighter, language selection, and
coverage requirements; a late stale pass is rejected as one unit.
The session also records the highlighter identity that supplied its caret
context semantics. If the editor changes file type or highlighter semantics,
the old token and active presentation is discarded before any old context can
run.

`EditorGuideSession` coordinates the editor lifetime rather than storing every
detail directly. `EditorAnalysisState` owns accepted stamps and snapshots;
`TrackedBracketPair` owns the pair plus edit-following endpoint and anchor
markers; `ActivePairMarkup` owns guide and endpoint highlighters; and
`VisibleTokenDecorations` owns the reusable viewport token marks.
`EditorGuideSessions` separately owns the editor user-data registry and session
lifecycle entry points. These objects keep analysis freshness, range tracking,
and markup disposal rules at their respective state boundaries.

The bounded `CaretBracketSearch` used before the first snapshot and while a
snapshot is stale reuses the full recognition path's token-pairing core. It searches
opening candidates backward, then evaluates each candidate forward with the
same language and token-group isolation, matcher-family gate, contextual,
symmetric, shared-closer, and structural rules. Consequently an edit cannot
temporarily introduce a pair that the next full snapshot rejects.
`ActivePairKnowledge.Unknown` preserves the RangeMarker-adjusted provisional pair;
authoritative `Known(null)` removes it. Backward and forward work share at most
512 search-controlled iterator transitions and a best-effort 4 ms elapsed
deadline, reserving most of a 16 ms frame for input and painting. A single
third-party matcher callback remains synchronous, so that one callback can
still overrun the elapsed deadline.
If malformed input exposes a structural closer whose opening context lies
before a candidate scan, the bounded path reports `Unknown` instead of
publishing a pair it cannot prove. The adjusted previous pair can remain until
the authoritative background pass resolves that ambiguity.
The guide-column fallback applies the complete index's `(column, earliest
line)` ordering whenever its bounded scan finishes. Finding column zero can
stop the scan only after every earlier candidate has been considered.

## Caret activation and caching

`ActiveBracketPairIndex` converts recognized intervals into segments whose
winner is the innermost containing pair. Crossing intervals from layered
languages are resolved deterministically. A caret lookup is a binary search.

Malformed-input recovery uses each official `BracePair.isStructural` flag.
Structural pairs can recover past unmatched regular openers, while a regular
pair is not allowed to cross a structural scope boundary. An unmatched
structural opener is treated conservatively as a boundary until the next
authoritative pass.

The active interval is strict at its outer edges:

```text
opening offset < caret offset < closing offset + closing token length
```

This includes a caret on the closing token and the position inside an empty
pair, but excludes positions before the opener and after the closer.

Only the primary caret selects an active pair. Secondary-caret movement is
ignored at the event boundary. Moving the primary caret within the same pair
changes no markup; moving to another pair replaces at most one guide and two
active-symbol ranges.

For multiple editor views of one document, a document event chooses at most one
focused, selected, or showing view for the bounded search. Other views keep a
RangeMarker-adjusted provisional pair until background analysis completes. If
active guide, border, and background presentation are all disabled, the
immediate search is not invoked.

## Cost model

Let `T` be token count, `L` document line count, `G` the line span from the
earliest multiline-pair body line to the latest closing line, `W` the leading
whitespace characters scanned in that span, and `P` recognized pair count. Brace
definitions per language are bounded by the registered matcher.

| Event | Work |
|---|---|
| Initial analysis or structural edit | One token pass `O(T)`; token and active endpoint indexes are each `O(P log P)` when requested; finding the multiline envelope is `O(P)`, its guide-position tree is `O(G + W)`, and one current-guide query is `O(log G)` |
| Caret move inside the same pair | `O(log P)` lookup; no markup change |
| Caret move to another pair | `O(log P)` lookup; replace at most three ranges |
| Caret move or edit with no current snapshot | At most 512 shared search-controlled backward/forward token transitions and a best-effort 4 ms deadline; indentation work is capped at 256 lines and 32,768 characters; full analysis remains asynchronous |
| Theme or palette change | Refresh attributes; no pair recognition |
| Global disable | Skip recognition and clear plugin-owned markup |

The active index stores at most `2P` segment boundaries. Token markup follows a
bounded visible-range window rather than all `2P` token ranges, and one EDT
refresh creates at most 2,048 token decorations around the caret or viewport
center. The active presentation adds at most three ranges and is applied first.
When the 2,048-range cap is active, a smaller stable focus interval determines
cache reuse, so scrolling within the wider character padding still recenters
the decorated token slice. Visible-area events are coalesced by editor into a
fixed 16 ms batch, limiting a burst of distant scroll events to at most one
token-window mutation per interval without starving continuous scrolling. In a
dense capped viewport, primary-caret movement requests the same batch so the
colored slice follows the caret even when the viewport itself does not move.
Structural work is paid on document changes, while ordinary navigation with a
current snapshot remains independent of file length apart from the binary
search. A one-entry active-pair memo avoids rematerializing its DTO while the
caret stays in the same pair. Token and active indexes sort their own primitive
endpoint arrays in
cancellable 16,384-entry chunks and merge passes, with checks at most every
4,096 merge/copy operations. `DocumentBrackets` stores recognized geometry in seven
parallel `IntArray` equivalents rather than a `List<BracketPair>` object graph,
and it does not additionally sort pair records: both downstream indexes are
input-order independent. At one million pairs this also avoids about six million
comparator calls, twelve million reference writes, and roughly 3.8 MiB of
compressed-reference merge storage.
The larger active index is built before the token index. This avoids retaining
16 bytes per pair of completed token payload during the active build and lowers
the common live-array peak by about 12.4 MiB at one million valid pairs.
When every active-index result slot is used, the index construction returns its populated
arrays directly. At one million pairs this avoids a transient copy of roughly
16 MiB, or four million integer elements.

After an edit invalidates a snapshot, proportional pair and index structures
are released immediately while their existing RangeMarker-backed presentation
stays visible until the replacement pass. This prevents a stale primitive pair
table and its indexes from overlapping the replacement analysis. Token-only
analysis also ignores tab-size changes because tab width affects only guide positioning.
When token, guide, border, and background features are all disabled, the
session releases the complete snapshot instead of retaining proportional pair
indexes for an invisible feature. Re-enabling analysis starts a new background
pass. A full result that was already in flight is reduced to a compact inactive
stamp instead of repopulating the released indexes.

When token colors are the only enabled pair feature, `BracketTokenIndex` copies
the two token lengths and full nesting depth out of the temporary pair table.
The sorted endpoint array plus detached metadata uses 28 bytes per pair, and the
seven-field pair table is then released. Turning off active presentation marks a
previously full snapshot unaccepted while
retaining it temporarily as a scrolling fallback; the daemon replaces it with
the compact token-only snapshot in the background. Once that compact result is
accepted, a late full-coverage snapshot cannot overwrite it or suppress future
viewport refresh. Detached metadata is copied only after endpoint
sorting returns, so its 12 bytes per pair do not overlap the sort merge
workspace; this lowers the live-array peak by about 11.4 MiB at one million
pairs compared with allocating it before the sort. In the reverse token-only to
active transition, viewport refresh continues using the current compact token
subset while active indexes are rebuilt. If the user reverses a full-to-token-only
transition before compaction completes, the retained full snapshot is accepted
again instead of collecting the same pairs twice.

For multiline pairs, `GuidePositionIndex` builds a tab-aware range-minimum
indentation index once. It covers only the bounding line span queried by the
recognized multiline pairs, not unrelated lines before or after them. Its build
cost is `O(G + W)` and each guide-column query is `O(log G)`. A two-indexed-line
query envelope in a million-line document therefore scans two lines and retains
32 bytes of tree payload instead of scanning the full document and retaining
16 MiB.
The segment-tree payload remains capped at 16 MiB (1,048,576 indexed lines).
Above that span boundary the snapshot omits this proportional-size index and
active guides use the same 256-line/32,768-character bounded on-demand fallback
as the stale path. That accepted snapshot does not currently schedule a second,
exact scan. `GuidePositionIndex` construction reads line offsets directly from `Document`,
avoiding two additional `IntArray` copies.

Primitive pair storage validates ranges through
`PairTable.hasWellFormedTokenRangeAt`, while DTO and presentation consumers use
`BracketPair.hasWellFormedTokenRange`. Both apply the same overflow-safe rule
before index or markup access. Extreme computed indent columns saturate at
`Int.MAX_VALUE - 1`, leaving the `Int.MAX_VALUE` sentinel reserved for blank
lines.

## Rendering

Each `BracketGuideDrawing` owns one guide, its appearance, and its color, then
resolves visual coordinates at paint time with public editor mapping APIs. It
accumulates aligned centerlines, creates one centered square-cap stroke outline,
and fills the combined shape once. This preserves HiDPI geometry without
applying translucent color twice at segment joints.
Soft-wrap enumeration is limited to the current graphics clip. Painting does
not run PSI, perform a read action, or scan all recognized pairs.

The plugin keeps references to its own ranges and removes only those ranges.
It does not call `removeAllHighlighters` in source editors or remove markup by
layer number. This keeps built-in and third-party highlighting isolated.

## Settings

The single `BracketGuideSettingsPage` extends the platform `BoundConfigurable`
and uses Kotlin UI DSL bindings for checkboxes, integer spinners, and the
standard `ColorPanel`. Installed matcher-backed languages are grouped by capability
owner and bound directly to stable disabled-family IDs. One six-level Base
palette supplies token, guide, border, and background colors by default;
component overrides remain available. Theme defaults live in
`additionalTextAttributes`, while explicit overrides live in plugin settings.

There is no settings-only editor, recognition pipeline, or preview state. Apply,
Reset, and modified-state tracking use the platform binding lifecycle.

`GuideSettingsChange` applies a committed preference transition to live editor
sessions and asks `DaemonRefresh` for background reanalysis only when coverage or
language selection changed. It performs bounded immediate resolution for at most
one focused, selected, or showing source editor; other open editors wait for the
daemon background pass. Guide-only and active-pair-only appearance changes do not
rebuild the token window, while palette or theme changes update existing token
attributes in place.

## Known limits

- An edit that may change later nesting triggers full token recognition.
- Only the primary caret selects the active pair.
- Complete active-scope background shading is intentionally not provided.
- Folded endpoints are not painted.
- The host pass does not traverse separate injected documents on its own.
- In malformed input, an unmatched structural opener can conservatively suppress
  a regular pair that would become matchable only if that opener never closes.
- Multiline-pair query spans above 1,048,576 lines use a bounded approximate
  indentation scan, so a guide can miss a lower indentation more than 256 lines
  inside its pair and is not automatically refined by that accepted snapshot.
  A larger document with a smaller pair span remains indexed.
- Structural snapshots remain proportional to recognized pair count and are
  owned per editor view. Extremely dense generated files and many split views
  can therefore consume substantial memory; a hard pair cap is not applied
  because a partial snapshot would incorrectly become authoritative.
- Languages without `com.intellij.lang.braceMatcher` are not analyzed.
- A legacy `com.intellij.braceMatcher` registration by itself is intentionally
  insufficient.

## Platform references

- [Syntax and error highlighting](https://plugins.jetbrains.com/docs/intellij/syntax-highlighting-and-error-highlighting.html)
- [Color scheme management](https://plugins.jetbrains.com/docs/intellij/color-scheme-management.html)
- [Brace matching](https://plugins.jetbrains.com/docs/intellij/additional-minor-features.html)
- [Services](https://plugins.jetbrains.com/docs/intellij/plugin-services.html)
- [Disposer and plugin unload](https://plugins.jetbrains.com/docs/intellij/disposers.html)
