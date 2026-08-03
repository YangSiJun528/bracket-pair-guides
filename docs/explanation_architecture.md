# Architecture and Performance

Bracket Pair Guides separates bracket recognition from editor decoration and
caches the structural result. Caret movement therefore does not rescan the
document.

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

`GuideLineHighlightingPassFactory` registers a `TextEditorHighlightingPass`.
The pass collects immutable results under the platform's background read and
cancellation lifecycle, then updates editor markup on the event dispatch
thread.

The bounded synchronous resolver used while a snapshot is stale applies the
same language-registration gate before invoking the platform match routine.
Consequently a legacy file-type matcher cannot make an otherwise unsupported
language appear supported during edits.

## Caret activation and caching

`ActiveBracketPairIndex` converts recognized intervals into segments whose
winner is the innermost containing pair. Crossing intervals from layered
languages are resolved deterministically. A caret lookup is a binary search.

The active interval is strict at its outer edges:

```text
opening offset < caret offset < closing offset + closing token length
```

This includes a caret on the closing token and the position inside an empty
pair, but excludes positions before the opener and after the closer.

Only the primary caret selects an active pair. Moving within the same pair
changes no markup. Moving to another pair replaces at most one guide and two
active-symbol ranges.

## Cost model

Let `T` be token count, `L` line count, and `P` recognized pair count. Brace
definitions per language are bounded by the registered matcher.

| Event | Work |
|---|---|
| Initial analysis or structural edit | One token pass `O(T)`, line index `O(L)`, guide positions `O(P log L)`, active index `O(P log P)` |
| Caret move inside the same pair | `O(log P)` lookup; no markup change |
| Caret move to another pair | `O(log P)` lookup; replace at most three ranges |
| Theme or palette change | Refresh attributes; no pair recognition |
| Global disable | Skip recognition and clear plugin-owned markup |

The active index stores at most `2P` segment boundaries. Editor markup stores
two token ranges per pair plus at most three ranges for the active presentation.
Structural work is paid on document changes, while ordinary navigation remains
independent of file length apart from the binary search.

For multiline pairs, `GuidePositionIndex` builds a tab-aware range-minimum
indentation index once. Its build cost is `O(L)` and each guide-column query is
`O(log L)`.

## Rendering

`BracketGuideRenderer` resolves visual coordinates at paint time with public
editor mapping APIs and uses `LinePainter2D` for HiDPI-aware lines. Soft-wrap
enumeration is limited to the current graphics clip. Painting does not run PSI,
perform a read action, or scan all recognized pairs.

The plugin keeps references to its own ranges and removes only those ranges.
It does not call `removeAllHighlighters` in source editors or remove markup by
layer number. This keeps built-in and third-party highlighting isolated.

## Settings and Preview

The single Settings page uses the JetBrains Kotlin UI DSL. One six-level Base
palette supplies token, guide, border, and background colors by default;
advanced mode can override individual components. Theme defaults live in
`additionalTextAttributes`, while explicit overrides live in plugin settings.

The Preview uses an isolated `EditorKind.PREVIEW` editor. `PreviewRecognition`
owns recognition and `PreviewDecorationController` owns markup, preserving the
same mockable recognition/rendering split as production. Document edits are
debounced and analyzed in a coalesced non-blocking read action. Caret and
appearance changes reuse the latest result. Dense or unusually long examples
are bounded to protect the Settings UI.

## Known limits

- An edit that may change later nesting triggers full token recognition.
- Only the primary caret selects the active pair.
- Complete active-scope background shading is intentionally not provided.
- Folded endpoints are not painted.
- The host pass does not traverse separate injected documents on its own.
- Languages without `com.intellij.lang.braceMatcher` are not analyzed.
- A legacy `com.intellij.braceMatcher` registration by itself is intentionally
  insufficient.

## Platform references

- [Syntax and error highlighting](https://plugins.jetbrains.com/docs/intellij/syntax-highlighting-and-error-highlighting.html)
- [Color scheme management](https://plugins.jetbrains.com/docs/intellij/color-scheme-management.html)
- [Brace matching](https://plugins.jetbrains.com/docs/intellij/additional-minor-features.html)
- [Disposer and plugin unload](https://plugins.jetbrains.com/docs/intellij/disposers.html)
