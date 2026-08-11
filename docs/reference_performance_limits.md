# Performance and Capacity Reference

This document is the single current reference for analysis limits, retained
payload bounds, fallback bounds, and asymptotic costs. The architecture explains
why the objects are separated; the
[benchmark guide](../benchmarks/guide_benchmarking.md) explains how to measure
changes.

Numbers preserved in the changelog or historical implementation reports
describe their recorded revision. Update this reference with any production
limit change.

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
| Host code-insight file size | IntelliJ's configured `idea.max.intellisense.filesize`; default 2,500 KiB | `Unavailable(IDE_CODE_INSIGHT_FILE_SIZE)` | `BracketGuideHighlightingPass` via `SingleRootFileViewProvider` |
| Completed pairs | 100,000 | `Unavailable(PAIR_CAPACITY)` with no pair prefix | `BracketRecognitionLimits.completedPairs` |
| Pending openers | 50,000 | `Unavailable(PENDING_OPEN_CAPACITY)` before the next stack node is allocated | `BracketRecognitionLimits.pendingOpens` |
| Retained exact guide payload | 4 MiB | `Limited(GUIDE_CAPACITY)` with exact token and active-pair facets but no guide | `GuideIndexShape` |
| Exact guide span under that payload | 1,032,192 lines | Same guide-only limitation | `GuideIndexShape` |
| Synchronous document-edit guide scan | 256 lines and 32,768 inspected line-prefix characters, including each content terminator | Remove the stale guide immediately; wait for exact background analysis | `GuidePositionFallback` |
| Token highlighters per editor viewport | 2,048 | Publish a focused token slice and recenter it as the focus moves | `VisibleTokenDecorations` |
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
| Completed-pair limit | 200,000 one-character brace tokens, about 195 KiB | A minified or generated file can cross the structural limit below the byte-size limit |
| Pending-open limit | 50,000 one-character openers, about 49 KiB | Pathological nesting can grow the object-backed stack below the byte-size limit |
| Exact guide payload | 1,032,192 indexed lines | Reachable near 1 MiB only with almost empty LF lines; at 40 bytes per line the source is about 39 MiB and normally fails the IDE gate first |

The earlier 200,000-pair boundary was reachable with about 391 KiB of
one-character brace tokens. Extrapolating the repository's Java and Kotlin
bracket density placed the same count around 11–14 MiB and roughly
320,000–380,000 lines. That is uncommon in handwritten code but plausible in
generated or minified source, so byte-size and structural limits remain
independent.

## Policy comparison

JetBrains defaults code insight to 2,500 KiB and general content loading to
20,000 KiB. Bracket Pair Guides calls the code-insight predicate directly
rather than assuming a custom highlighting pass will be skipped.

The pinned Rainbow Brackets Lite source uses a more conservative 1,000-line
default for its PSI-based highlighting, while the Marketplace release notes at
the time of the audit described a 5,000-line user-facing threshold and the same
threshold for indent guides. Bracket Pair Guides does not copy either line
count: a line is not a memory unit, minified source may have one line, and this
plugin retains primitive indexes while limiting per-editor token presentation.
The comparison supports having a backstop but not reusing another plugin's
storage-independent number.

## Memory rationale

The completed-pair limit stops `PairTable` before its next geometric growth. At
the accepted boundary, each of its seven primitive columns remains below the
common 512 KiB humongous-array threshold for a 1 MiB G1 region.

Pending openers are object-backed and may also retain strict-context state.
Local probes on the supported JetBrains Runtime measured approximately
38–46 MiB for 200,000 unique strict-context openers and approximately
10–12 MiB for 50,000. These observations justify a backstop; they are not a
promise that a third-party matcher cannot allocate more.

The guide index retains one indentation `Int` per covered line plus a
power-of-two minimum tree whose leaves summarize 256-line blocks. Its combined
primitive arrays must stay within 4 MiB. The platform's own indent-guide
calculation also uses a per-line integer array, but that array is temporary;
this plugin retains its guide index in the snapshot, so it needs a separate
retained-payload bound. The value covers those primitive-array payloads; it is
not a total-heap guarantee and does not include object headers or allocations
inside a third-party matcher.

The removed 48 MiB arithmetic estimate is not a current limit. It omitted
pending-opener objects, contexts, geometric array replacement, and
collector-specific humongous-region effects, and it did not reject any layout
otherwise admitted by the real drivers. Current policy therefore bounds
observable allocation drivers instead: host file size, completed pairs,
pending openers, and exact guide-array shape.

## Work by event

| Event | Work |
|---|---|
| Initial analysis or structural edit | One token pass `O(T)`; token and active endpoint indexes are each `O(P log P)` when requested; multiline envelope discovery is `O(P)`; guide index construction is `O(G + W)` |
| Exact guide query | Scan at most two partial 256-line blocks and query intervening block minima in `O(log(G / 256))` |
| Caret movement with a current snapshot | `O(log P)` active-pair lookup; moving to another pair replaces at most one guide and two active-symbol ranges |
| Caret movement without a current snapshot | Range-marker adjustment and interval containment only; no token iteration or matcher callback on the EDT |
| Document insertion, replacement, or deletion | Adjust the tracked endpoints and perform at most the bounded exact indentation-prefix scan; remove the guide if exact current geometry is unavailable; no token iteration or matcher callback on the EDT |
| Enable a guide while exact guide coverage is pending | Bounded provisional whitespace scan for the already tracked pair; no token or matcher work |
| Theme or palette change | Refresh attributes; no pair recognition |
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
- Token presentation retains only the bounded viewport window for each editor.
- Token-only snapshots detach token lengths and nesting depth, use 28 retained
  bytes per pair, and release the seven-column pair table.
- The larger active index is built before detached token metadata is retained,
  reducing peak overlap.
- Token and active indexes sort primitive endpoints in 16,384-entry chunks and
  check cancellation at most every 4,096 merge or copy operations.
- A two-line guide envelope uses 24 bytes of retained primitive payload.
- The guide indentation value saturates at `Int.MAX_VALUE - 1`; `Int.MAX_VALUE`
  remains the blank-line sentinel.

After an edit, stale proportional pair and index structures are released. Each
surviving tracked pair is synchronously recomputed against the current document,
or its stale guide is removed before the EDT update returns. Replacement
analysis may later discover a different pair. When every pair-dependent feature
is disabled, the session retains a compact accepted stamp rather than
proportional indexes.

Equivalent split-editor results may share immutable `BracketIndexes` after full
content comparison. Each editor still owns its own snapshot stamp, active-pair
memo, range markers, viewport decorations, and markup. Recognition and
transient index construction are not single-flight.

## Cancellation and matcher behavior

Recognition and index construction call the pass-provided
`ProgressIndicator`. Cancellation can be observed between token, sorting,
copying, and line-scanning operations.

A third-party brace matcher is ordinary JVM code. One callback cannot be
preempted safely while it runs, so no per-callback time limit is claimed. The
plugin confines callbacks to the cancellable background pass and never invokes
them in caret, document, viewport, or paint handlers.

## Measurement

Use [Run the performance benchmarks](../benchmarks/guide_benchmarking.md) for
repeatable JMH comparisons of pairing and primitive sorting. Benchmark results
are comparative evidence, not exact IDE latency. Validate changes that affect
read actions, allocation, or painting in a running IDE with Java Flight
Recorder.

## Evidence sources

- [JetBrains file-size limits](https://www.jetbrains.com/help/clion/configuring-file-size-limit.html)
- [JetBrains PSI performance](https://plugins.jetbrains.com/docs/intellij/psi-performance.html)
- [JetBrains large-file predicate](https://github.com/JetBrains/intellij-community/blob/4fa6dbe6b2d453005ea4d0ac22b25e00f3c2a420/platform/core-impl/src/com/intellij/psi/SingleRootFileViewProvider.java#L167-L183)
- [JetBrains document-commit current-content check](https://github.com/JetBrains/intellij-community/blob/4fa6dbe6b2d453005ea4d0ac22b25e00f3c2a420/platform/ide-core-impl/src/com/intellij/psi/impl/DocumentCommitThread.kt#L236-L242)
- [JetBrains indent-guide calculation](https://github.com/JetBrains/intellij-community/blob/4fa6dbe6b2d453005ea4d0ac22b25e00f3c2a420/platform/lang-impl/src/com/intellij/codeInsight/daemon/impl/indentGuide/IndentGuideCalculator.java#L36-L107)
- [Rainbow Brackets large-file policy](https://github.com/izhangzhihao/intellij-rainbow-brackets/blob/c7bdbda6ce7baa7720eba436d528335b73a61e5a/src/main/kotlin/com/github/izhangzhihao/rainbow/brackets/lite/settings/RainbowSettings.kt#L23-L26)
- [Rainbow Brackets Marketplace releases](https://plugins.jetbrains.com/plugin/10080-rainbow-brackets/)
