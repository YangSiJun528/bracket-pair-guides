# Performance and Capacity Reference

This document is the single current reference for analysis limits, retained
payload bounds, fallback bounds, and asymptotic costs. The
[benchmark guide](../benchmarks/guide_benchmarking.md) explains how to measure
changes.

## Terms

| Symbol | Meaning |
|---|---|
| `T` | Tokens visited by full-document recognition |
| `P` | Completed bracket pairs |
| `L` | Document line count |
| `G` | Indexed line span from the earliest multiline-pair body to the latest closing line |
| `W` | Leading-whitespace characters scanned inside that span |

## Admission and presentation limits

| Boundary | Current value | Result when crossed | Owner |
|---|---:|---|---|
| Host code-insight file size | IntelliJ's configured `idea.max.intellisense.filesize`; default 2,500 KiB | `Unavailable(IDE_CODE_INSIGHT_FILE_SIZE)` | `EditorAnalysisExecution` via `SingleRootFileViewProvider` |
| Completed pairs | 100,000 | `Unavailable(PAIR_CAPACITY)` with no pair prefix | `BracketRecognitionLimits.completedPairs` |
| Pending openers | 50,000 | `Unavailable(PENDING_OPEN_CAPACITY)` before the next stack node is allocated | `BracketRecognitionLimits.MAXIMUM_PENDING_OPENS` |
| Retained exact guide payload | 4 MiB | `Limited(GUIDE_CAPACITY)` with exact token and active-pair facets but no guide | `GuideIndexShape` |
| Exact guide span under that payload | 1,032,192 lines | Same guide-only limitation | `GuideIndexShape` |
| Background post-edit guide repair | 256 lines and 32,768 consumed prefix characters, including the first non-whitespace character of each nonblank line | Leave the synchronously hidden guide hidden on exact repair refusal | `GuideRepairCalculation` |
| Token capture chunk | 512 visited lexer tokens | Resume at the next exact token boundary in another read action | `BracketTokenCapture` |
| Captured-token storage seed | Initially 32 entries; then adapt to the previous successful chunk's captured-token density, within the 512-token visit bound | Grow admitted storage when the seed is insufficient; this is not a recognition limit | `BracketTokenCapture` |
| Native traversal body | 8,192 owned state-machine operations and a cooperative 2 ms deadline, checked every 32 operations | Yield and restore the exact token cursor in another validated read action; callbacks and preparation can exceed the deadline | `NativeMarkerInspection` / `NativeBraceMatching.WorkBudget` |
| Initial full-analysis guide capture | Up to 128 lines, each with at most 128 characters | Continue unresolved whitespace prefixes separately | `IncrementalAnalysis` |
| Whitespace continuation capture | 4,096 characters per read action | Continue until indentation is resolved or repair admission refuses it | `DocumentTextCapture` callers |
| Cached demanded matcher rules | 2,048 answers per recognition attempt | Evict the oldest cached answer; recompute if demanded again | `IncrementalAnalysis` |
| Strongly cached platform token kinds | 1,024 kinds per capture adapter | Evict the oldest cache entry; live pure identities remain resolvable | `BracketTokenCapture` |
| Secondary-editor full-analysis debounce | 75 ms | Combine compatible pending requests; cancel semantic supersession immediately | `EditorAnalysisExecution` |
| Token highlighters per editor presentation | 2,048 | Reserve displayed Sticky Lines tokens, then publish a focused ordinary-viewport slice from the remaining shared budget | `VisibleTokenDecorations` |
| Reported viewport normalization | 16,384 characters | Center a bounded reported range on the caret or viewport midpoint | `VisibleTokenDecorations` |
| Token-window padding | 256 to 4,096 characters | Clamp padding to the range | `VisibleTokenDecorations` |
| Viewport refresh coalescing | 16 ms | Combine repeated events by editor identity | `EditorGuideEvents` |

Saved files use `VirtualFile.length` for the host file-size predicate. Unsaved
files use the current `Document.textLength`, matching IntelliJ's document-commit
path. The host-configured value remains authoritative; the plugin does not add
another byte-size setting.

## Smallest adversarial inputs

| Boundary | Smallest representative input | Practical interpretation |
|---|---:|---|
| Default IDE code-insight size | 2,500 KiB | Ordinary large source usually stops before recognition |
| Completed-pair limit | 200,002 one-character brace tokens, about 195 KiB | A minified or generated file can cross the structural limit below the byte-size limit |
| Pending-open limit | 50,001 one-character openers, about 49 KiB | Pathological nesting can grow the object-backed stack below the byte-size limit |
| Exact guide payload | 1,032,192 indexed lines | Reachable near 1 MiB only with almost empty LF lines; at 40 bytes per line the source is about 39 MiB and normally fails the IDE gate first |

## Host file-size policy

JetBrains defaults code insight to 2,500 KiB and general content loading to
20,000 KiB. Bracket Pair Guides calls the code-insight predicate directly
rather than assuming a custom highlighting pass will be skipped.

## Memory rationale

The completed-pair limit stops `PairTable` before its next geometric growth. At
the accepted boundary, each of its seven primitive columns remains below the
common 512 KiB humongous-array threshold for a 1 MiB G1 region.

Pending openers are object-backed and may also retain strict-context state.
The pending-opener limit keeps adversarial nesting bounded before those objects
and their matcher-provided context accumulate without limit. It is not a
total-heap guarantee and does not bound allocations inside a third-party
matcher.

The guide index retains one indentation `Int` per covered line plus a
power-of-two minimum tree whose leaves summarize 256-line blocks. Its combined
primitive arrays must stay within 4 MiB. The platform's own indent-guide
calculation also uses a per-line integer array, but that array is temporary;
this plugin retains its guide index in the snapshot, so it needs a separate
retained-payload bound. The value covers those primitive-array payloads; it is
not a total-heap guarantee and does not include object headers or allocations
inside a third-party matcher.

## Work by event

| Event | Work |
|---|---|
| Initial analysis or structural edit | One token pass `O(T)`; token and active endpoint indexes are each `O(P log P)` when requested; multiline envelope discovery is `O(P)`; guide index construction is `O(G + W)` |
| Exact guide query | Scan at most two partial 256-line blocks and query intervening block minima in `O(log(G / 256))` |
| Caret movement with a current snapshot | `O(log P)` active-pair lookup; moving to another pair replaces at most one guide and two active-symbol ranges |
| Caret movement without a current snapshot | Range-marker adjustment and interval containment only; no token iteration or matcher callback on the EDT |
| Ordinary viewport or displayed Sticky Lines change | Query bounded token ranges from the current snapshot, deduplicate overlaps, and reuse matching highlighters; no recognition or matcher callback |
| Document insertion, replacement, or deletion | Adjust tracked endpoints, remove bounded Sticky-only token decorations, hide affected guide geometry, and request immediate background repair; no text scan, token-index iteration, or matcher callback on the EDT |
| Enable a guide while exact guide coverage is pending | Geometry-only reuse or immediate bounded provisional repair for the already tracked pair; indentation scanning runs in the background |
| Theme or palette change | Refresh explicit palette attributes and theme-dependent background blending; no pair recognition |
| Global disable | Skip recognition and clear plugin-owned markup |

The active interval uses this strict boundary:

```text
opening offset < caret offset < closing offset + closing token length
```

This includes a caret on the closing token and inside an empty pair, but excludes
positions before the opener and after the closer.

## Retained and temporary structures

- `PairTable` stores seven primitive integer columns rather than one object per
  completed pair.
- The active-pair index stores at most `2P` event boundaries.
- Token presentation retains only the bounded ordinary viewport window and the
  bounded source lines currently displayed by Sticky Lines. Both share the
  2,048-highlighter cap.
- Token-only snapshots detach token lengths and nesting depth, use 28 retained
  bytes per pair, and release the seven-column pair table.
- The larger active index is built before detached token metadata is retained,
  reducing peak overlap.
- Token and active indexes sort primitive endpoints in 16,384-entry chunks and
  check cancellation at most every 4,096 merge or copy operations.
- A two-line guide envelope uses 24 bytes of retained primitive payload.
- The guide indentation value saturates at `Int.MAX_VALUE - 1`; `Int.MAX_VALUE`
  remains the blank-line sentinel.

After an edit, stale proportional pair and index structures are released.
Affected guide geometry is removed before the EDT update returns. A separate
immediate repair job computes indentation for the surviving tracked pair;
replacement full analysis may later discover a different pair. When every pair-dependent feature
is disabled, the session retains a compact accepted stamp rather than
proportional indexes.

Equivalent split-editor results may share immutable `BracketIndexes` after full
content comparison. Each editor still owns its own snapshot stamp, active-pair
memo, range markers, presentation decorations, and markup. Recognition and
transient index construction are not single-flight.

## Cancellation and matcher behavior

The shared execution module launches independent background jobs. Recognition,
index construction, and repair check coroutine cancellation and platform
cancellation between token, sorting, copying, and line-scanning operations.
Read actions capture bounded immutable data; pairing, sorting, and indentation
calculation run without read access. No synchronous production analysis API is
retained. The token-kind cache bound is not a cap on all live kind identities:
identities needed by an in-progress pairing operation remain resolvable.

Capture bounds limit visits or copied characters, not wall-clock duration. A
lexer or matcher callback can do additional work. Repair admission counts
characters consumed through the first non-whitespace character, or the complete
length of a blank line; it does not charge the unused suffix of a captured
prefix. Captured strings are owned copies from a reusable bounded character
scratch buffer. Full analysis builds active indexes before detached token
metadata and allocates final guide storage only after guide admission; it does
not retain a second document-sized indentation array.

Native brace-context resolution uses separate background read actions for
admission, lazy-source preparation, direct classification/traversal, optional
current-scope classification and structural/forward traversal, and final
validation. Each traversal body admits at most 8,192 owned state-machine
operations. Cancellation and a cooperative 2 ms deadline are checked every
32 operations; unfinished traversal yields after read access is released.
These are operation/deadline admission bounds, not verified native p95 latency
or a hard read-lock hold-time guarantee.

The host continuation retains matcher stacks and exact token bookmarks, not
live iterators or document text. Every resumed cursor checks token range and
token-type identity, including before-first/after-last boundaries. Each read
body validates the document/highlighter/read epoch, captured caret and block
cursor, and editor/project lifetime. A source change or retried read body
invalidates the entire proof instead of replaying partially mutated state;
no partial marker evidence is accepted. The pure native-conflict decision runs
after all required capture phases release read access.

Lazy syntax factory work, copying the lazy source range, and lexer `setText`
remain together in a separate preparation body. Their cost is not bounded by
the traversal budget. Classification and individual extension callbacks can
also exceed the cooperative deadline. `NativeCaptureObserver` reports phases
and owned operations separately so preparation/callback floors are not hidden
inside traversal measurements.

A third-party brace matcher is ordinary JVM code. One callback cannot be
preempted safely while it runs, so no per-callback time limit is claimed. The
plugin confines callbacks to the cancellable background capture and never invokes
them in caret, document, viewport, or paint handlers.

## Validating changes

Use [Run the performance benchmarks](../benchmarks/guide_benchmarking.md) for
repeatable JMH comparisons of pairing and primitive sorting. Benchmark results
are comparative evidence, not exact IDE latency. Validate changes that affect
read actions, allocation, or painting in a running IDE with Java Flight
Recorder. Keep timing assertions out of the unit suite; regression tests should
instead protect deterministic bounds, cancellation points, state reuse, and
markup ownership.

## Evidence sources

- [JetBrains file-size limits](https://www.jetbrains.com/help/clion/configuring-file-size-limit.html)
- [JetBrains PSI performance](https://plugins.jetbrains.com/docs/intellij/psi-performance.html)
- [JetBrains large-file predicate](https://github.com/JetBrains/intellij-community/blob/4fa6dbe6b2d453005ea4d0ac22b25e00f3c2a420/platform/core-impl/src/com/intellij/psi/SingleRootFileViewProvider.java#L167-L183)
- [JetBrains document-commit current-content check](https://github.com/JetBrains/intellij-community/blob/4fa6dbe6b2d453005ea4d0ac22b25e00f3c2a420/platform/ide-core-impl/src/com/intellij/psi/impl/DocumentCommitThread.kt#L236-L242)
- [JetBrains indent-guide calculation](https://github.com/JetBrains/intellij-community/blob/4fa6dbe6b2d453005ea4d0ac22b25e00f3c2a420/platform/lang-impl/src/com/intellij/codeInsight/daemon/impl/indentGuide/IndentGuideCalculator.java#L36-L107)
