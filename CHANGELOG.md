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
- Per-language matcher-family controls with individual and bulk enable/disable
  actions; newly installed supported languages remain enabled by default.
- Java, Kotlin, Kotlin script, JSON, contextual matcher, unsupported-language,
  pinned real-world source, and large-input regression coverage.
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
- Preview recognition is debounced, cancellable, and isolated from persisted
  settings and source editors.

### Fixed

- Guide opacity now remains uniform where horizontal and vertical segments
  overlap.
- The active pair is revalidated immediately before the first full analysis,
  after edits, and when the caret moves between nested scopes in a stale file.
- Provisional guide-column lookup now recomputes rematched pairs and caps EDT
  indentation work at 256 lines and 32,768 characters.
- Malformed-input recovery now honors official structural-brace priority and
  prevents regular pairs from crossing a structural scope boundary.
