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
  overrides, language controls, and guide geometry.
- Documentation showcase examples for audited CSS-family and SQL registrations.
- An audited IDE and language capability reference, including Rust and the
  official build/config/template matcher registrations found in installed
  JetBrains language plugins.
- A release checklist covering the first manual Marketplace upload, listing
  metadata, verification, signing, and the later automated update path.
- Per-language matcher-family controls; newly installed supported languages
  remain enabled by default.
- Java, Kotlin, Kotlin script, JSON, contextual matcher, unsupported-language,
  pinned real-world source, large-input, and seeded index parity regression
  coverage.
- Light and dark plugin icons and the MIT License.
- A startup compatibility guard that reports one **Unsupported IDE** error when
  `com.intellij.lang.braceMatcher` is unavailable.

### Changed

- New installations show bracket-token colors and the active guide while
  leaving optional active-symbol borders and backgrounds disabled.
- Bracket recognition uses only each token language's
  `com.intellij.lang.braceMatcher`; product backends, legacy file-type matcher
  fallbacks, and raw-character fallbacks are intentionally absent.
- Context-sensitive `BraceMatcher` implementations registered through the
  language extension are preserved instead of being reduced to static pairs.
- Recognition and decoration are separated by the `BracketAnalysis`
  Application Service with immutable analysis inputs, caret contexts, snapshots,
  and bounded active-pair knowledge.
- Project-owned analysis, editor, presentation, and settings types now use
  domain concepts instead of actor-style `Engine`, `Builder`, `Resolver`,
  `Manager`, `Factory`, and `Renderer` names; snapshot assembly, settings
  transition, daemon refresh, analysis state, and markup lifecycles have
  explicit owners.
- Production code no longer exposes `@TestOnly` constructors, state getters,
  fixture conversions, policy controls, or convenience overloads. Tests use
  product inputs and results, actual editor markup, production policy objects,
  or fixtures located under test sources.
- Structural results are cached so caret movement uses an interval-index lookup
  and updates at most one guide and two active-symbol ranges.
- Editor results and markup remain EDT-confined; background pass deduplication
  reads only an immutable analysis stamp, and active presentation is applied before
  viewport token decoration.
- The Settings page now uses platform `BoundConfigurable`, Kotlin UI DSL
  bindings, integer spinners, and standard color selectors instead of custom
  draft, table, splitter, and preview infrastructure.
- Platform-neutral pairing state and primitive pair storage now live in the
  engine's `analysis.pairing.core` package. The engine also adapts IntelliJ
  matchers and builds indexes, and is composed into the existing single-JAR
  plugin distribution.
- Analysis coverage is compiled into an index layout before recognition and one
  snapshot assembly builds active/token/guide artifacts; options are not checked
  in the token loop.
- Pairing tokens now use typed `OPEN`, `CLOSE`, and `TOGGLE` roles instead of a
  transient bitmask, and engine internals are grouped by analysis feature.
- Committed Kotlin ABI baselines now contain only the root `analysis` facade.
  Module checks fail on unreviewed ABI
  changes and reject public Kotlin engine classes outside that package.
- Document-bracket recognition, snapshot assembly, pairing, sort, and index
  implementations are hidden behind snapshot queries instead of being exposed
  to the plugin module.
- Token coloring now follows oversized reported viewports even when the caret is
  off-screen and caps synchronous EDT decorations at 2,048 ranges.
- Dense token-window refreshes now coalesce by editor on a fixed 16 ms delay;
  capped windows also follow caret-only movement without waiting for a scroll.
- Disabling token colors now clears capped-window refresh state, and disabling
  active-pair presentation skips caret-side active-index work.
- Large token and active-pair index sorts now check cancellation between bounded
  sort and merge work instead of holding a stale background pass to one sort.
- The redundant final recognized-pair object sort was removed because both
  downstream indexes sort input-independent primitive endpoints. At one million
  pairs this removes about six million comparisons, twelve million reference
  writes, and roughly 3.8 MiB of compressed-reference merge storage.
- Multiline-result probes check cancellation every 256 pairs.
- Guide-position indexing now scans and retains only the multiline-pair query
  envelope, caps that segment-tree span at 16 MiB, and uses the bounded
  guide-position fallback above 1,048,576 indexed lines. A two-indexed-line envelope in a
  million-line document now needs two line reads and 32 bytes of tree payload
  instead of a full-document scan and 16 MiB tree.
- Fully populated active-pair segment arrays are retained directly, avoiding a
  16 MiB transient copy and four million copied integers at one million pairs.
- Building the active index before retaining the token index lowers the common
  live-array peak by about 12.4 MiB at one million valid pairs.
- Invalidated primitive pair/index snapshots are released immediately after
  edits while RangeMarker-backed decoration stays visible, preventing stale
  proportional storage from overlapping replacement analysis.
- Disabling every pair feature now releases the complete per-editor snapshot
  instead of retaining proportional indexes for invisible presentation.
- Token-only snapshots now detach compact primitive token metadata from the
  temporary pair table and then release all fields not needed for token lookup;
  switching from full active analysis keeps visible token markup while
  rebuilding this compact snapshot in the background.
- Detached token metadata is copied after endpoint sorting, avoiding about
  11.4 MiB of live-array overlap with the sort workspace at one million pairs.
- Capped token-decoration slices recenter while scrolling inside a cached
  viewport instead of remaining fixed at the previous focus.
- The `TEXT` matcher family is labeled **Custom file types** and explains that
  raw plain text is not scanned.
- Guide-only and active-pair-only setting changes reuse the current token
  window; palette and theme changes update its attributes in place.
- Theme-only refreshes update existing presentation without running bounded
  caret search across every open editor.
- Plugin verification now pins build 241, covers the recommended compatibility
  matrix plus IntelliJ IDEA 2026.2, and fails on additional invalid API/dependency states.

### Fixed

- Guide opacity now remains uniform where horizontal and vertical segments
  overlap.
- The active pair is revalidated immediately before the first full analysis,
  after edits, and when the caret moves between nested scopes in a stale file.
- Provisional guide-column lookup now recomputes rematched pairs and caps EDT
  indentation work at 256 lines and 32,768 characters.
- Stale-snapshot active-pair lookup now caps search-controlled iterator work
  at 512 transitions and uses a best-effort 4 ms deadline before deferring to
  background analysis.
- Full and immediate recognition now share contextual, layered-language,
  symmetric, shared-closer, language-gate, and structural pairing semantics.
- Matcher families without a standalone file type now appear in Languages, so
  embedded and injected language support can be disabled explicitly.
- Re-enabling active presentation now resolves the current pair immediately
  even when the cached snapshot was collected with active analysis disabled.
- A rejected stale highlighting pass can no longer replace the current
  session's analysis or visible-range functions.
- Malformed-input recovery now honors official structural-brace priority and
  prevents regular pairs from crossing a structural scope boundary.
- Ambiguous malformed fast-path matches remain provisional when proving them
  would require structural context before the bounded scan.
- Malformed pair ranges are rejected consistently by indexes, active
  presentation, and painting before offset arithmetic or highlighter creation.
- Extreme tab sizes and pair line numbers no longer overflow guide columns
  or line-selection arithmetic.
- Replacing an editor highlighter now removes presentation from the previous
  language semantics without invoking its stale caret search.
- Background recognition now evaluates language gates from the same immutable
  disabled-language set recorded in its analysis stamp.
- Immediate guide positioning now uses the same earliest-line tie break as the
  complete index, including all-whitespace ranges.
- Split editors no longer each spend the bounded immediate search budget for
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
- Publishing a stable or prerelease draft now triggers the release workflow
  through GitHub's `published` event.
- Draft releases now target the exact commit that completed CI instead of a
  potentially newer default-branch revision.
- Draft preparation now replaces only the current version's prior draft instead
  of deleting every unrelated draft in the repository.
- GitHub releases now prefer a verified signed ZIP when signing credentials are
  configured and upload it before optional Marketplace publishing, so a
  Marketplace failure does not also suppress the GitHub artifact or block a
  safe workflow retry.
- Platform custom file types now route only their official syntax-table bracket
  tokens through the `TEXT` matcher, while raw plain text remains unsupported.
- A full source-editor result already in flight can no longer repopulate
  proportional indexes after every pair feature has been disabled.
- Full-to-token-only transitions retain an unaccepted scrolling fallback until
  compact analysis arrives, and a late full result can no longer erase an
  already accepted compact token index. Reversing the transition before
  compaction re-accepts the retained full snapshot without recollecting pairs.
