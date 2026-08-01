<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# bracket-pair-guides Changelog

## [Unreleased]

### Added

- Light and dark 40×40 plugin logos for IDE and Marketplace presentation.
- The MIT License under the `sijun-yang` copyright name.

### Changed

- Finalized the pre-release Plugin ID and Kotlin namespace as
  `com.sijunyang.bracketpairguides` and the vendor display name as
  `sijun-yang`.
- Resolved all 15 Qodana style, visibility, simplification, and unused-code
  findings without changing plugin behavior.

## [0.3.2] - 2026-08-01

### Added

- An editable Preview sidebar that reflects unapplied guide width, opacity,
  pair styling, visibility, and palette changes.
- Selectable Java, Kotlin, JSON, XML, and Markdown boilerplates for installed
  language file types, with temporary per-format edit and caret retention plus
  a Reset action.

### Changed

- Rebuilt Settings with the JetBrains Kotlin UI DSL so dependent rows disable
  with their controlling switches and help text wraps at the platform's
  readable width.
- Replaced 24 hexadecimal `ColorPanel` fields with one compact six-row swatch
  table backed by the public platform color chooser.
- Removed the Preview diagnostic footer so the sidebar contains only the
  example controls and editable editor.
- Unified Base, Guide, Border, and Background colors in one table; component
  cells remain inherited and read-only until separate colors are enabled.
- Routed editable Preview recognition through the production
  `BracketPairAnalyzer` token and brace-matcher pipeline.
- Debounced preview document analysis by 150ms and moved it to a coalesced,
  non-blocking read action. Caret and appearance changes reuse the cached
  recognition result.
- Capped preview token decoration at the first 500 recognized pairs and moved
  restored examples over 10,000 characters to background recognition.
- Shared active-pair decoration creation between production editors and the
  preview while keeping preview text and draft settings isolated from
  persisted settings.
- Preserved advanced component colors while their parent switch is off, and
  made guide geometry unavailable when no guide segment can be drawn.
- Limited preview cleanup to its owned highlighters and released its editor
  with the Settings page.
- Made 0% Pair background add no background attribute, avoiding overlap with
  foreign editor highlights.

## [0.3.1] - 2026-08-01

### Changed

- Reduced Settings minimum width with compact color controls, a two-row Base
  palette, non-stretching advanced color columns, and responsive description
  wrapping.

## [0.3.0] - 2026-07-31

### Added

- One unified Settings page under **Editor** for behavior, geometry, and colors.
- Six level-specific base colors that drive bracket tokens, guide lines, Pair
  borders, and Pair backgrounds by default.
- Optional per-level Line, Pair border, and Pair background color overrides.
- Independent Pair border and Pair background switches, border style, and
  background opacity.
- Migration and regression tests for linked colors, advanced overrides, and the
  single-page UI structure.

### Changed

- Renamed the plugin to **Bracket Pair Guides**, the project to
  `bracket-pair-guides`, and the Plugin ID to
  `com.sijunyang.bracketpairguides`.
- Guide, border, and background colors now resolve from the same level base
  color unless advanced component colors are enabled.
- Palette changes update existing presentation without pair re-recognition.
- Active Pair attributes no longer change foreground or font style.

### Removed

- The former plugin-specific page under **Editor | Color Scheme**.
- The common-style and inherited active-pair-style model from 0.2.0.

## [0.2.0] - 2026-07-31

### Added

- A common active-pair symbol style using the standard JetBrains foreground,
  background, effect/border, and font controls.
- Six optional level-specific active-pair styles that inherit from the common
  style.
- Independent vertical and horizontal guide controls.
- Guide width and opacity controls.
- Regression tests for style inheritance, symbol-only activation, guide
  geometry settings, and persisted numeric option normalization.

### Changed

- Active-pair emphasis now creates two token-sized highlighters instead of one
  range-sized background highlighter.
- Active symbol styling uses `HighlighterLayer.ELEMENT_UNDER_CARET`, below
  selection and JetBrains' boundary-triggered matched-brace highlight.
- Caret transitions replace at most one guide and two symbol highlighters;
  movement within the same pair changes no markup.

### Removed

- Scope-wide active bracket-pair background shading and its six background
  color keys.

## [0.1.1] - 2026-07-31

### Added

- Caret-only activation for the innermost containing bracket pair.
- An `O(log P)` active-pair interval index built with document analysis.
- Six transparent, level-specific active-scope background colors.
- Independent master, token-color, active-guide, and scope-background toggles.
- Configuration and overlap-resolution guidance for built-in IDE features and
  other bracket plugins.
- Caret movement, multi-caret, theme color, toggle, 50,000-pair index, and
  disabled-mode performance regression tests.

### Changed

- Cached guide descriptors while retaining only one active guide highlighter,
  avoiding dormant overlapping ranges in deeply nested files.
- Kept caret paint paths free of lexer, PSI, read-action, and pair-list scans.
- Limited soft-wrap paint work to the current viewport clip.
- Placed scope backgrounds just above syntax and below caret-row, diagnostics,
  brace matching, and selection layers.
- Made global disable skip later pair recognition.

## [0.1.0] - 2026-07-31

### Added

- Nesting-depth colors for matching bracket tokens.
- Vertical, opening, closing, and single-line bracket-pair guides.
- Light and dark color-scheme defaults with six customizable depth colors.
- Unit tests and lexer integration tests for Java, Kotlin, Kotlin script, JSON,
  XML, and Markdown.
- Pinned real-world JetBrains source fixtures and long, malformed, cancellation,
  determinism, and recognition-to-highlighting regression tests.
- Pixel-level soft-wrap and folding renderer tests.

### Changed

- Replaced raw brace matching with language-provided `PairedBraceMatcher` and
  file-type `BraceMatcher` definitions, including strict XML tag names.
- Separated pair recognition behind a mockable `BracketPairProvider` from the
  editor-highlighting pass.
- Reused range highlighters instead of deleting and recreating all editor markup.
- Replaced per-pair interior scans with a range-minimum indentation index.
- Matched the IntelliJ built-in indent-guide highlighting and HiDPI paint lifecycle.
- Added cancellation, empty-file fast paths, bulk markup updates, and
  soft-wrap-aware horizontal guide segments.

### Removed

- Unimplemented settings, active-scope tracking, and custom bracket-background options.
