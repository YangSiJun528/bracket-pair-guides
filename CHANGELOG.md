<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Bracket Pair Guides Changelog

## [Unreleased]

### Added

- Six repeating nesting-level colors for matched bracket tokens.
- One caret-activated guide for the innermost containing pair, with independent
  vertical and horizontal segments.
- Optional border and background emphasis on the active opening and closing
  symbols.
- One Settings page with theme-aware Base colors, optional per-component color
  overrides, guide geometry, and an editable Preview.
- Capability-filtered Preview examples for Java, Kotlin, JSON, JavaScript,
  TypeScript, Python, Go, Rust, YAML, Shell Script, and TOML.
- Documentation showcase examples for audited CSS-family and SQL registrations.
- An audited IDE and language capability reference, including Rust and the
  official build/config/template matcher registrations found in installed
  JetBrains language plugins.
- Per-language matcher-family controls with individual and bulk enable/disable
  actions; newly installed supported languages remain enabled by default.
- Java, Kotlin, Kotlin script, JSON, contextual matcher, unsupported-language,
  pinned real-world source, large-input, and seeded index parity regression
  coverage.
- Light and dark plugin icons and the MIT License.

### Changed

- New installations show bracket-token colors and the active guide while
  leaving optional active-symbol borders and backgrounds disabled.
- Bracket recognition uses only each token language's
  `com.intellij.lang.braceMatcher`; product backends, legacy file-type matcher
  fallbacks, and raw-character fallbacks are intentionally absent.
- Context-sensitive `BraceMatcher` implementations registered through the
  language extension are preserved instead of being reduced to static pairs.
- Recognition and decoration are separated behind mockable interfaces.
- Structural results are cached so caret movement uses an interval-index lookup
  and updates at most one guide and two active-symbol ranges.
- Editor snapshots and markup remain EDT-confined; background pass deduplication
  reads only an immutable stamp, and active presentation is applied before
  viewport token decoration.
- Preview recognition is debounced, cancellable, and isolated from persisted
  settings and source editors.
- Preview edits, example switches, Reset, and language changes now reanalyze in
  a coalesced background read action instead of blocking the Settings event
  thread; previews above 10,000 characters expose an explicit analyzing state.
- Long previews now move a bounded token-color window with their actual
  viewport instead of leaving colors near the initial caret.
- Theme changes now refresh automatic Settings palette cells while preserving
  explicit draft colors and the page's modified state.
- The Preview selector is associated with its mnemonic label, and the selector
  and palette expose descriptive accessibility names; the actual focusable
  editor content exposes the Preview name to assistive technology.
- Preview text above 100,000 characters now shows an explicit paused state and
  blocks costly example-buffer switching until the text is reduced or reset.
- Preview status text stays short in narrow Settings layouts while its tooltip
  and accessible description retain the complete recovery instruction.
- Token coloring now follows oversized reported viewports even when the caret is
  off-screen and caps synchronous EDT decorations at 2,048 ranges.
- Dense token-window refreshes now coalesce by editor on a fixed 16 ms delay;
  capped windows also follow caret-only movement without waiting for a scroll.
- Large token and active-pair index sorts now check cancellation between bounded
  sort and merge work instead of holding a stale background pass to one sort.
- The redundant final recognized-pair object sort was removed because both
  downstream indexes sort input-independent primitive endpoints. At one million
  pairs this removes about six million comparisons, twelve million reference
  writes, and roughly 3.8 MiB of compressed-reference merge storage.
- Multiline-result probes check cancellation every 256 pairs.
- Guide-position indexing now caps its segment-tree payload at 16 MiB and uses
  the bounded on-demand resolver above 1,048,576 lines. Its production build
  reads document line offsets directly instead of retaining two additional
  4 MiB arrays at that boundary.
- Fully populated active-pair segment arrays are retained directly, avoiding a
  16 MiB transient copy and four million copied integers at one million pairs.
- Building the active index before retaining the token index lowers the common
  live-array peak by about 12.4 MiB at one million valid pairs.
- Invalidated pair/index snapshots are released immediately after edits while
  RangeMarker-backed decoration stays visible, avoiding roughly 70–90 MiB of
  stale snapshot overlap per editor at one million pairs.
- Capped token-decoration slices recenter while scrolling inside a cached
  viewport instead of remaining fixed at the previous focus.
- Settings Preview examples preserve caret and horizontal/vertical scroll state,
  commit valid spinner input immediately, and expose clearer paused-state controls.
- The `TEXT` matcher family is labeled **Custom file types** and explains that
  raw plain text is not scanned.
- Guide-only and active-pair-only setting changes reuse the current token
  window; palette and theme changes update its attributes in place.
- Theme-only refreshes update existing presentation without running the bounded
  active-pair resolver across every open editor.
- Plugin verification now pins build 241, covers the recommended compatibility
  matrix plus IntelliJ IDEA 2026.2, and fails on additional invalid API/dependency states.

### Fixed

- Guide opacity now remains uniform where horizontal and vertical segments
  overlap.
- The active pair is revalidated immediately before the first full analysis,
  after edits, and when the caret moves between nested scopes in a stale file.
- Provisional guide-column lookup now recomputes rematched pairs and caps EDT
  indentation work at 256 lines and 32,768 characters.
- Stale-snapshot active-pair lookup now caps resolver-controlled iterator work
  at 512 transitions and uses a best-effort 4 ms deadline before deferring to
  background analysis.
- Full and immediate recognition now share contextual, layered-language,
  symmetric, shared-closer, language-gate, and structural pairing semantics.
- Matcher families without a standalone file type now appear in Languages, so
  embedded and injected language support can be disabled explicitly.
- Re-enabling active presentation now resolves the current pair immediately
  even when the cached snapshot was collected with active analysis disabled.
- A rejected stale highlighting pass can no longer replace the current
  session's active resolver or visible-range provider.
- Malformed-input recovery now honors official structural-brace priority and
  prevents regular pairs from crossing a structural scope boundary.
- Ambiguous malformed fast-path matches remain provisional when proving them
  would require structural context before the bounded scan.
- Malformed provider ranges are rejected consistently by indexes, active
  presentation, and painting before offset arithmetic or highlighter creation.
- Extreme tab sizes and provider line numbers no longer overflow guide columns
  or line-selection arithmetic.
- Replacing an editor highlighter now removes presentation from the previous
  language semantics without invoking its stale active-pair resolver.
- Disposed Settings controls can no longer run delayed preview or color-chooser
  actions against released UI resources.
- Background recognition now evaluates language gates from the same immutable
  disabled-language set recorded in its analysis stamp.
- Immediate guide positioning now uses the same earliest-line tie break as the
  complete index, including all-whitespace ranges.
- Split editors no longer each spend the bounded immediate resolver budget for
  one document edit; only the focused or selected view resolves immediately.
- Applying settings likewise resolves at most one focused, selected, or showing
  editor immediately instead of multiplying the bounded budget across all open
  editors.
- Secondary-caret movement no longer repeats primary-caret resolution or dense
  token-window requests.
- Token-only and pair-only analysis no longer invalidates on tab-size changes;
  tab width remains a dependency only when guide positioning is requested.
- Editors with active guides and pair emphasis disabled skip immediate
  active-pair resolution entirely.
- Preview decoration remains visible while a debounced edit is awaiting its
  replacement snapshot and survives example highlighter changes correctly.
- Publishing a stable or prerelease draft now triggers the release workflow
  through GitHub's `published` event.
- Platform custom file types now route only their official syntax-table bracket
  tokens through the `TEXT` matcher, while raw plain text remains unsupported.
- Failed Preview matcher runs now clear stale token and guide decoration before
  showing the recovery state.
- Preview results rejected after an in-flight tab-size or highlighter change
  are resubmitted immediately instead of being treated as a successful empty
  result.
