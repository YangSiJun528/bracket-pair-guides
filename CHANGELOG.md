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
- Production code now lives in one `plugin` Gradle module. The former `engine`
  module, module-composition wiring, and cross-module public facade were removed;
  `benchmarks` remains a measurement-only consumer of compiled plugin classes.
- `analysis.intellij.BracketAnalysis` is a final application light service
  instead of an interface/implementation pair and XML service descriptor.
  Settings reads the installed-family projection from `BraceLanguageCatalog`
  instead of maintaining a separate language-inventory service.
- Production packages form an enforced one-way DAG across four broad zones:
  IntelliJ host, editor workbench, configuration state, and analysis policy.
  ArchUnit 1.5.0 checks that inward direction, package-slice cycles, and IntelliJ
  neutrality as part of the plugin test suite; the custom `buildSrc` source
  scanner was removed.
- Preferences, persisted settings, editor events, sessions, and presentation
  now have separate package ownership. `ActiveGuidePresentation` owns the
  tracked active pair, its markup, and revision-consistent synchronous guide
  geometry for one editor session.
- Analysis now returns `AnalysisOutcome.Complete`, `Limited`, or `Unavailable`.
  Pair and pending-open exhaustion publish no capped prefix. Guide exhaustion
  publishes exact token and active-pair facets without an approximate guide;
  each refusal remembers the exact attempted stamp to prevent a retry loop.
- The highlighting pass now honors IntelliJ's IDE-managed code-insight
  file-size policy before invoking bracket recognition. Independent adversarial
  bounds are 100,000 completed pairs and 50,000 unmatched open tokens.
- Equivalent analyses of split editors share an immutable `BracketIndexes`
  payload only after active/full pair geometry or the complete token-only query
  sequence agrees. Token-only payloads retain no source pair table. Each
  `BracketSnapshot` keeps its own stamp and active-pair memo, and weak,
  revision-scoped canonical entries do not retain documents or editors.
- Project-owned analysis, editor, presentation, and settings types now use
  domain concepts instead of actor-style `Engine`, `Builder`, `Resolver`,
  `Manager`, `Factory`, and `Renderer` names; snapshot assembly, settings
  transition, daemon refresh, analysis state, pass registration, visual-column
  arithmetic, and markup lifecycles have explicit owners.
- Production code no longer exposes `@TestOnly` constructors, state getters,
  fixture conversions, policy controls, or convenience overloads. Tests use
  product inputs and results, actual editor markup, production policy objects,
  or fixtures located under test sources.
- AssertJ 3.27.7 now provides the assertion vocabulary across Kotlin and Java
  tests; JUnit 4 remains only the test lifecycle and IntelliJ fixture boundary.
- Structural results are cached so caret movement uses an interval-index lookup
  and updates at most one guide and two active-symbol ranges. If no current
  snapshot exists, the editor waits for the background pass instead of running
  token recognition on the EDT; an already tracked pair still refreshes or
  clears its current geometry synchronously on every document edit.
- Editor markup remains EDT-confined; background pass deduplication reads one
  atomically published immutable acceptance value, and active presentation is
  applied before viewport token decoration.
- The Settings page now uses platform `BoundConfigurable`, Kotlin UI DSL
  bindings, integer spinners, and standard color selectors instead of custom
  draft, table, splitter, and preview infrastructure.
- Platform-neutral pairing state and primitive pair storage live in
  `analysis.pairing.core`. IntelliJ matcher adaptation and snapshot assembly
  remain separate package responsibilities inside the single plugin artifact.
- Analysis coverage is compiled into an index layout before recognition and one
  snapshot assembly builds active/token/guide artifacts; options are not checked
  in the token loop.
- Pairing tokens now use typed `OPEN`, `CLOSE`, and `TOGGLE` roles instead of a
  transient bitmask, and analysis internals are grouped by responsibility.
- Analysis inputs, outcomes, snapshots, and token windows are internal product
  types in their owning packages. The plugin no longer maintains a library-style
  ABI baseline; Java pairing-core bytecode remains public only for package and
  JMH implementation access.
- Document-bracket recognition, snapshot assembly, pairing, sort, and index
  implementations stay behind concrete internal snapshot queries.
- Token coloring now follows oversized reported viewports even when the caret is
  off-screen and caps synchronous EDT decorations at 2,048 ranges.
- Dense token-window refreshes now coalesce by editor on a fixed 16 ms delay;
  capped windows also follow caret-only movement without waiting for a scroll.
- Disabling token colors now clears capped-window refresh state, and disabling
  active-pair presentation skips caret-side active-index work.
- Large token and active-pair index sorts now check cancellation between bounded
  sort and merge work instead of holding a stale background pass to one sort.
- The redundant final recognized-pair object sort was removed because both
  downstream indexes sort input-independent primitive endpoints. At the current
  pair cap this removes about 600,000 comparisons, 1.2 million reference
  writes, and roughly 0.4 MiB of compressed-reference merge storage.
- Multiline-result probes check cancellation every 256 pairs.
- Guide-position indexing now scans and retains only the multiline-pair query
  envelope. Its exact blocked index stores per-line indentation plus a tree of
  256-line block minima, supports up to 1,032,192 indexed lines within a 4 MiB
  retained payload, and omits only the guide facet above that boundary. A
  two-indexed-line envelope needs two line reads and 24 bytes of primitive
  payload instead of a full-document scan.
- Fully populated active-pair segment arrays are retained directly, avoiding a
  roughly 1.5 MiB transient copy at the current pair cap.
- Building the active index before retaining the token index lowers the common
  live-array peak by about 1.5 MiB at the current pair cap.
- Invalidated primitive pair/index snapshots are released immediately after
  edits. RangeMarker-backed endpoints and bounded guide geometry are then
  synchronously updated for the current revision, or the stale guide is removed,
  preventing stale proportional storage from overlapping replacement analysis.
- Disabling every pair feature now releases the complete per-editor snapshot
  instead of retaining proportional indexes for invisible presentation.
- Token-only snapshots now detach compact primitive token metadata from the
  temporary pair table and then release all fields not needed for token lookup;
  switching from full active analysis keeps visible token markup while
  rebuilding this compact snapshot in the background.
- Detached token metadata is copied after endpoint sorting, avoiding about
  1.1 MiB of live-array overlap with the sort workspace at the current pair cap.
- Capped token-decoration slices recenter while scrolling inside a cached
  viewport instead of remaining fixed at the previous focus.
- The `TEXT` matcher family is labeled **Custom file types** and explains that
  raw plain text is not scanned.
- Guide-only and active-pair-only setting changes reuse the current token
  window; palette and theme changes update its attributes in place.
- Theme-only refreshes update existing presentation without bracket analysis.
- Plugin verification now pins build 241, covers the recommended compatibility
  matrix plus IntelliJ IDEA 2026.2, and fails on additional invalid API/dependency states.

### Fixed

- Every insertion, replacement, and deletion now updates each surviving tracked
  active pair in the same EDT document-event turn. RangeMarker-adjusted
  endpoints are never painted with guide geometry from an older document
  revision; the guide is removed when bounded exact recomputation cannot finish.
- Guide opacity now remains uniform where horizontal and vertical segments
  overlap.
- New active-pair identity still changes only from an authoritative indexed
  snapshot. The already tracked pair is synchronously adjusted or removed, but
  it is not treated as a newly recognized pair while background analysis runs.
- Contextual, layered-language, symmetric, shared-closer, language-gate, and
  structural pairing semantics are covered at the full-document grammar
  boundary after removal of the synchronous caret-recognition path.
- Matcher families without a standalone file type now appear in Languages, so
  embedded and injected language support can be disabled explicitly.
- Re-enabling active presentation requests active-index coverage and waits for
  its background result when the cached snapshot omitted that facet; it never
  invokes synchronous bracket recognition.
- A rejected stale highlighting pass can no longer replace the current
  session's analysis or visible-range functions.
- Malformed-input recovery now honors official structural-brace priority and
  prevents regular pairs from crossing a structural scope boundary.
- Malformed pair ranges are rejected consistently by indexes, active
  presentation, and painting before offset arithmetic or highlighter creation.
- Extreme tab sizes and pair line numbers no longer overflow guide columns
  or line-selection arithmetic.
- Replacing an editor highlighter now removes presentation from the previous
  language semantics and waits for analysis under the replacement highlighter.
- Background recognition now evaluates language gates from the same immutable
  disabled-language set recorded in its analysis stamp.
- Exact guide positioning uses the same earliest-line tie break for block-tree
  and partial-block queries, including all-whitespace ranges.
- Secondary-caret movement no longer repeats primary-caret resolution or dense
  token-window requests.
- Token-only and pair-only analysis no longer invalidates on tab-size changes;
  tab width remains a dependency only when guide positioning is requested.
- Editors with active guides and pair emphasis disabled omit active-pair index
  coverage entirely.
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
