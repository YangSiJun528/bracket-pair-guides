# Architecture and performance

This document explains the recognition, caching, and rendering design. It also
records why the plugin does not recompute bracket structure on every caret
movement.

## Platform integration

`GuideLineHighlightingPassFactory` registers a
`TextEditorHighlightingPass`. The platform collects immutable descriptors under
its background read and cancellation lifecycle, then applies editor markup on
the event dispatch thread. This follows the lifecycle of IntelliJ's built-in
indent-guide pass.

Recognition and decoration are separated by `BracketPairProvider`. Production
uses `BracketPairAnalyzer`; highlighting tests inject fixed pair descriptors
without a lexer or language plugin.

Bracket discovery reads the editor's existing token stream. For each token
language, `LanguageBraceMatching` supplies a registered `PairedBraceMatcher`.
When it has none, the public `BraceMatcher` extension and `FileTypeExtension`
resolve the file-type matcher used by XML, Markdown, and older language
plugins. `XmlAwareBraceMatcher` tag-name and case-sensitivity rules are
preserved. The plugin does not scan raw characters, so it follows the host
lexer instead of treating brackets in comments and strings as code.

Each pair retains two editor ranges plus one plain descriptor:

1. The opening bracket color.
2. The closing bracket color.
3. A `BracketGuide` value containing the pair and computed guide column.

There is at most one guide range and two active-symbol ranges per editor. Every
range is tagged as plugin-owned and the plugin disposes only the ranges stored
in its production editor state. It never removes foreign highlighters or calls
`removeAllHighlighters` in a source editor. The Settings preview follows the
same ownership rule for its isolated editor.

## Caret activation

After recognition, `ActiveBracketPairIndex` converts pair intervals into
segments whose winner is the innermost containing pair. A sweep line handles
crossing intervals from layered languages deterministically: the later opener
wins, then the earlier closer, then input order.

The active interval is strict at the outside edges:

```text
opening offset < caret offset < closing offset + closing token length
```

This includes a caret on a closing token and the position between an empty
pair, but excludes the position before the opener and after the closer.

An application-level `CaretListener`, owned by a disposable light service,
reads only the primary caret. A move performs a binary search, disposes the old
guide/symbol ranges, and creates at most three new ranges for the next pair. It
does not read PSI, run a lexer, restart the daemon, or scan the pair list.

Keeping dormant guide ranges was considered and rejected. Deep nesting makes
many long ranges overlap the same viewport even when their custom renderer is
null. Caching descriptors while retaining only the active presentation trades
up to three markup insertions on a pair transition for lower persistent markup
memory and bounded viewport range queries.

## Cost model

Let `T` be token count, `L` line count, and `P` recognized pair count.

| Event | Work |
|---|---|
| Initial analysis or structural edit | Token recognition `O(T)`, line index `O(L)`, guide positions `O(P log L)`, active index `O(P log P)` |
| Caret move inside the same pair | `O(log P)` lookup and no markup change |
| Caret move to another pair | `O(log P)` plus replacement of at most one guide and two symbol ranges |
| Paint | Geometry for the one active renderer and visible soft-wrap fragments only; no PSI or pair scan |
| Theme or palette change | Refresh token and active presentation attributes; no recognition |
| Global disable | Skip recognition and clear owned markup |

The active index stores at most `2P` segment boundaries. Markup stores two token
ranges per pair plus at most one active guide and two active-symbol ranges. This
design moves structural cost to document changes, where bracket structure can
actually change, and keeps ordinary navigation independent of file length
apart from a binary search and three range replacements.

Three alternatives were considered:

| Strategy | Advantage | Problem |
|---|---|---|
| Recompute at every caret | Minimal cache | Repeats token/PSI work during navigation and can block the UI |
| Cache pairs and linearly scan them | Simple | Caret cost grows with every pair in the file |
| Cache pairs and an interval index | `O(log P)` caret lookup | Additional `O(P)` memory and index rebuild after edits |

The third strategy is used. A truly incremental bracket tree could reduce edit
cost, but the IntelliJ Platform does not expose a stable public cross-language
incremental brace tree for plugins.

## Pairing, positioning, and paint

Open brackets retain every expected closing token allowed by
`PairedBraceMatcher`. Stacks are isolated per token language. File-type
matchers use a contextual stack keyed by matcher class and brace group, so XML
can require equal tag names while Markdown and non-strict matchers use token
relationships only.

The common top-of-stack close path is constant time. Expected-token and
context counts reject unrelated closers without walking the stack. Malformed
input can recover to a matching outer opener, with cancellation checks in long
loops.

For multiline pairs, the guide column is the minimum non-blank indentation
between the line after the opener and the closing line. A tab-aware
range-minimum index is built once:

- Build: `O(L)`
- One guide-column query: `O(log L)`
- All guide positions: `O(P log L)`

The custom renderer resolves visual coordinates at paint time with public
editor mapping APIs. `LinePainter2D` supplies HiDPI-aware lines. Soft-wrap and
fold tests cover the cases where logical and visual lines differ. Soft-wrap
enumeration is limited to the current graphics clip instead of walking every
wrap in a long active range. The renderer does not perform a read action or PSI
lookup while painting.

## Unified palette and layer isolation

The Settings UI is one Kotlin UI DSL `Configurable` under **Editor**. The standard
`ColorSettingsPage` was removed because its public contract accepts color and
text-attribute descriptors but cannot host the feature switches, geometry
controls, and linked-color mode required here.

One compact table shows Base, Guide, Border, and Background swatches for six
levels. The table uses the public `ColorChooserService`; dependent rows and
cells are disabled from the same feature switches that control rendering.
Platform DSL comments replace unconstrained Swing labels, so help text keeps a
readable preferred width in a wide Settings window.

Six plugin-owned `TextAttributesKey` entries provide light/dark theme defaults.
Each depth has one resolved base color. In normal mode that same color supplies:

1. Bracket-token foreground.
2. Active guide line.
3. Active Pair border.
4. Active Pair background source color.

The Pair background source is blended with the editor background by the
configured opacity before it becomes a token background. Advanced mode can
store independent per-level Line, Pair border, and Pair background source
colors. Unset values continue to resolve from the base color. Disabled
advanced controls retain their stored values, while an explicit palette reset
returns them to automatic Base-derived colors.

Theme defaults remain in `additionalTextAttributes`. Explicit user colors are
plugin settings; automatic entries keep following the current editor scheme.
Changing colors updates existing token attributes and the active presentation
without rerunning pair recognition.

## Editable Settings preview

The Settings page places its controls and Preview in a horizontal platform
splitter. The right pane owns one editable `EditorKind.PREVIEW` editor and
installs the `EditorHighlighter` for the selected file type. Available examples
are resolved from the IDE's registered Java, Kotlin, JSON, XML, and Markdown
file types, so an example is omitted when its language plugin is unavailable.

Each example has a session-only buffer for text and caret offset. Switching
formats preserves that buffer; **Reset** replaces only the current buffer with
its boilerplate. Neither the buffers nor the selected example are written to
`PluginSettings` or a source file.

The editable preview uses `BracketPairAnalyzer` against the selected editor
highlighter. It therefore consumes the same token stream,
`PairedBraceMatcher`, `BraceMatcher`, and strict XML tag rules as a production
editor. `PreviewPairProviderFactory` keeps this recognition step injectable,
while `PreviewDecorationController` accepts an immutable result containing
pairs, guide descriptors, and an `ActiveBracketPairIndex`.

Document edits clear stale decoration and schedule recognition after a 150ms
Swing alarm. Recognition runs in a coalesced IntelliJ non-blocking read action;
a generation number, document modification stamp, file type, and disposable
lifetime cancel obsolete work and prevent stale results from being applied.
Restored buffers over 10,000 characters also use this background path instead
of blocking the EDT. Input over 100,000 characters pauses preview recognition
rather than scheduling unbounded work.

Caret movement performs only an indexed lookup and redraws the active
presentation when its pair changes. Appearance-control changes rebuild
decoration from the cached recognition result without lexing again. Selecting
another example or resetting its boilerplate performs one immediate
recognition so the newly selected content does not briefly display a stale
result.

Preview token, guide, border, and background ranges are stored by the
`PreviewDecorationController`, tagged with the plugin ownership key, and
disposed individually. The controller never clears the editor markup model or
foreign ranges. Closing the Settings page disposes those owned ranges, cancels
pending analysis, removes listeners, and releases the preview editor.

Token coloring is capped at the first 500 recognized pairs, or 1,000 token
highlighters. The full pair/index snapshot remains available for caret-active
guide decoration anywhere in the example. Guide-only or active-pair appearance
changes reuse existing token highlighters; they are rebuilt only when
recognition, token visibility, base colors, or the editor color scheme changes.

Draft appearance state is copied into the Preview and remains independent of
the application-level persisted state. **Apply** or **OK** persists only the
Settings controls and then refreshes open editors; editable preview text is
never included.

The active opening and closing tokens are two `EXACT_RANGE` highlighters at
`HighlighterLayer.ELEMENT_UNDER_CARET`. They override ordinary syntax and
diagnostic styling on those two symbols, while editor selection remains above
them. JetBrains' own matched-brace highlighter uses a still higher layer, so it
can win at a brace boundary instead of being hidden.

The active token attributes intentionally have no foreground or font override.
The lower six-level bracket color or the language's original brace color
therefore remains visible. Only the explicitly named Pair border and Pair
background components are added.

At 0% Pair background opacity, no background attribute or background-only
highlighter is created. This preserves lower-layer backgrounds from diffs,
inspections, and other plugins.

The plugin never changes `MATCHED_BRACE_ATTRIBUTES`, indent-guide colors, or
another plugin's keys. Independent switches let users disable only the
overlapping token, guide, or active-symbol feature.

## Comparison with existing plugins

[Rainbow Brackets](https://plugins.jetbrains.com/plugin/10080-rainbow-brackets)
and its open-source derivatives use the daemon highlighting lifecycle for token
colors and a fork of IntelliJ's indent-guide implementation for guides. That
gives them broad functionality, but inspected public versions also depend on
internal daemon/editor classes and may perform blocking PSI work in a paint
path.

[Colored Brackets](https://plugins.jetbrains.com/plugin/25980-colored-brackets)
improves repeated PSI-parent checks with an analysis-local cache. Its scope
highlighting is action-triggered in the inspected source rather than continuous
caret tracking.

[Color Brackets](https://plugins.jetbrains.com/plugin/24560-color-brackets)
tracks caret movement, but the inspected Marketplace binary uses delayed
whole-document text processing rather than the host brace matcher pipeline.

This plugin adopts the useful shared principle—compute structure on document
analysis and keep caret presentation cheap—while staying on public
`PairedBraceMatcher`, `BraceMatcher`, highlighting-pass, editor-event, color,
and markup APIs.

## Known limits

- Any edit that may change later nesting still causes full token recognition.
- The primary caret alone selects the one active pair.
- Active scope-wide background shading is intentionally not provided.
- Folded endpoints are not painted; partially folded interiors use current
  public visual-position mappings.
- Layered tokens exposed by the editor highlighter are supported. Separate PSI
  injections are analyzed when the platform supplies their injected
  editor/document; the host pass does not traverse injections itself.
- Rider C# needs a separately verified provider because Rider language analysis
  is backed by ReSharper. The plugin must not assume ordinary IntelliJ PSI or
  matcher registration for C#.

## Platform references

- [IntelliJ Platform: Syntax Highlighting and Error Highlighting](https://plugins.jetbrains.com/docs/intellij/syntax-highlighting-and-error-highlighting.html)
- [IntelliJ Platform: Color Scheme Management](https://plugins.jetbrains.com/docs/intellij/color-scheme-management.html)
- [IntelliJ Platform: Brace Matching](https://plugins.jetbrains.com/docs/intellij/additional-minor-features.html)
- [IntelliJ Platform: Disposer and plugin unload](https://plugins.jetbrains.com/docs/intellij/disposers.html)
- [IntelliJ IDEA: Customize editor appearance](https://www.jetbrains.com/help/idea/customize-editor.html)
- [IntelliJ IDEA: Indent guides](https://www.jetbrains.com/help/idea/indentation.html)
