# Architecture and Performance

Bracket Pair Guides separates bracket recognition from editor decoration and
caches the structural result. Caret movement against a current snapshot uses
only the active-pair index; an absent or stale snapshot uses a bounded local
resolver and never starts a full synchronous document scan.

## Recognition boundary

`BracketPairProvider` is the boundary between recognition and highlighting.
Production uses `BracketPairAnalyzer`; renderer tests inject fixed pair
descriptors without requiring a lexer or language plugin.

The analyzer reads the editor's token stream and resolves only the token
language's `com.intellij.lang.braceMatcher` registration through
`LanguageBraceMatching`. A plain `PairedBraceMatcher` is wrapped with the
platform's `PairedBraceMatcherAdapter`; a matcher that already implements
`BraceMatcher` is used unchanged. The latter preserves contextual decisions
such as treating the same token type as a brace only at selected positions.

There is no product backend, legacy file-type matcher fallback, or raw-character
fallback. Languages are isolated for layered highlighters, and comments and
strings follow the host language's lexer. If a language-registered matcher also
provides strict tag context, that context remains part of pairing.

Language settings persist disabled matcher-family IDs. A dialect that inherits
the same matcher as its base language shares that base capability ID, so the UI
and token-level analysis cannot disagree about whether the family is enabled.
The disabled-ID set is part of the analysis stamp; changing it invalidates both
the background snapshot and the bounded active-pair resolver.
Each production pass constructs its analyzer from the same captured set stored
in that stamp. A settings sequence such as A→B→A during collection therefore
cannot produce B-filtered tokens carrying an A stamp.
The platform `TEXT` registration is presented as **Custom file types** because
its matcher consumes the eight official custom syntax-table bracket token
types. Only platform `UserFileType` tokens take this narrow mapping; other
`Language.ANY` tokens and raw plain text are not scanned.

`GuideLineHighlightingPassFactory` registers a `TextEditorHighlightingPass`.
The pass collects immutable results under the platform's background read and
cancellation lifecycle, then updates editor markup on the event dispatch
thread. Session snapshots, range markers, and highlighters are EDT-confined;
background pass deduplication reads only a separately published immutable
analysis stamp. Applying a snapshot updates the active presentation before it
refreshes the larger viewport token window. A pass may replace the session's
active resolver and visible-range provider only while its collected stamp still
satisfies the current document, highlighter, language selection, and capability
requirements; a late stale pass is rejected as one unit.
The session also records the highlighter identity that supplied its active
resolver. If the editor changes file type or highlighter semantics, the old
token and active presentation is discarded before any old resolver can run.

The bounded synchronous resolver used before the first snapshot and while a
snapshot is stale reuses the full analyzer's token-pairing core. It searches
opening candidates backward, then evaluates each candidate forward with the
same language and token-group isolation, matcher-family gate, contextual,
symmetric, shared-closer, and structural rules. Consequently an edit cannot
temporarily introduce a pair that the next full snapshot rejects. An incomplete
lookup preserves the RangeMarker-adjusted provisional pair; an authoritative
no-pair result removes it. Backward and forward work share at most 512
resolver-controlled iterator transitions and a best-effort 4 ms elapsed
deadline, reserving most of a 16 ms frame for input and painting. A single
third-party matcher callback remains synchronous, so that one callback can
still overrun the elapsed deadline.
If malformed input exposes a structural closer whose opening context lies
before a candidate scan, the bounded path reports `Incomplete` instead of
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
focused, selected, or showing view for the bounded resolver. Other views keep a
RangeMarker-adjusted provisional pair until background analysis completes. If
active guide, border, and background presentation are all disabled, the
immediate resolver is not invoked.

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
| Caret move or edit with no current snapshot | At most 512 shared resolver-controlled backward/forward token transitions and a best-effort 4 ms deadline; indentation work is capped at 256 lines and 32,768 characters; full analysis remains asynchronous |
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
search. Token and active indexes sort their own primitive endpoint arrays in
cancellable 16,384-entry chunks and merge passes, with checks at most every
4,096 merge/copy operations. The analyzer does not additionally sort its pair
objects: both downstream indexes are input-order independent. At one million
pairs this removes about six million comparator calls, twelve million reference
writes, and roughly 3.8 MiB of compressed-reference merge storage.
The larger active index is built before the token index. This avoids retaining
16 bytes per pair of completed token payload during the active build and lowers
the common live-array peak by about 12.4 MiB at one million valid pairs.
When every active-index result slot is used, the builder returns its populated
arrays directly. At one million pairs this avoids a transient copy of roughly
16 MiB, or four million integer elements.

After an edit invalidates a snapshot, proportional pair and index structures
are released immediately while their existing RangeMarker-backed presentation
stays visible until the replacement pass. For a default one-million-pair
snapshot this avoids overlapping roughly 70–90 MiB of stale structures per
editor with the new analysis, depending on interval shape. Token-only analysis
also ignores tab-size changes because tab width affects only guide positioning.
When token, guide, border, and background features are all disabled, the
session releases the complete snapshot instead of retaining proportional pair
indexes for an invisible feature. Re-enabling analysis starts a new background
pass. A full result that was already in flight is reduced to a compact inactive
stamp instead of repopulating the released indexes.

When token colors are the only enabled pair feature, `BracketTokenIndex` copies
the two token lengths and full nesting depth into primitive arrays instead of
retaining the recognized `BracketPair` object graph. The sorted endpoint array
plus detached metadata uses 28 bytes per pair. On a typical compressed-reference
JVM, this reduces retained heap by about 30.5 MiB at one million pairs. Turning
off active presentation marks a previously full snapshot unaccepted while
retaining it temporarily as a scrolling fallback; the daemon replaces it with
the compact token-only snapshot in the background. Once that compact result is
accepted, a late full-capability result cannot overwrite it or suppress future
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
exact scan. The production builder reads line offsets directly from `Document`,
avoiding two additional `IntArray` copies.

Provider output crosses one shared overflow-safe token-range validation boundary
before entering token/active indexes or creating presentation. Extreme computed
indent columns saturate at `Int.MAX_VALUE - 1`, leaving the `Int.MAX_VALUE`
sentinel reserved for blank lines.

## Rendering

`BracketGuideRenderer` resolves visual coordinates at paint time with public
editor mapping APIs. It accumulates aligned centerlines, creates one centered
square-cap stroke outline, and fills the combined shape once. This preserves
HiDPI geometry without applying translucent color twice at segment joints.
Soft-wrap enumeration is limited to the current graphics clip. Painting does
not run PSI, perform a read action, or scan all recognized pairs.

The plugin keeps references to its own ranges and removes only those ranges.
It does not call `removeAllHighlighters` in source editors or remove markup by
layer number. This keeps built-in and third-party highlighting isolated.

## Settings and Preview

The single Settings page uses the JetBrains Kotlin UI DSL. Installed
matcher-backed languages are grouped by capability owner and can be enabled
individually or in bulk. One six-level Base palette supplies token, guide,
border, and background colors by default; advanced mode can override individual
components. Theme defaults live in `additionalTextAttributes`, while explicit
overrides live in plugin settings.

The Preview uses an isolated `EditorKind.PREVIEW` editor. `PreviewRecognition`
owns recognition and `PreviewDecorationController` owns markup, preserving the
same mockable recognition/rendering split as production. After the initial
small bundled Java sample, document edits, example switches, Reset, and language
changes cancel and replace one coroutine owned by an application service scope.
Each job enters a write-allowing `readAction`, then applies the current result on
the EDT; edits retain the 150 ms debounce while replacement actions submit with
zero delay. Caret and appearance changes reuse the latest result. Previews above
10,000 characters
show an analyzing status until the background result is visible. Existing valid
decoration remains while ordinary debounced edits wait for replacement. Above
100,000 characters, recognition and example switching
pause with a higher-priority explicit status; the editor remains editable and
reducing or resetting the text resumes work.
Each example buffer also retains its caret and horizontal/vertical scroll
position. Valid spinner edits commit to their model before focus changes, and
disposed controls reject delayed preview or color-chooser callbacks.
Long analysis states use a compact visible label; the full recovery action is
available in both the tooltip and accessibility description.
If tab size or highlighter semantics change while a Preview result is in
flight, the stale stamp is rejected and recognition is resubmitted immediately.

Applying settings performs bounded immediate resolution for at most one
focused, selected, or showing source editor; other open editors wait for the
daemon background pass. Guide-only and active-pair-only appearance changes do
not rebuild the token window, while palette or theme changes update existing
token attributes in place.

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
- [Disposer and plugin unload](https://plugins.jetbrains.com/docs/intellij/disposers.html)
