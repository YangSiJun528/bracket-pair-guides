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
- Java, Kotlin, Kotlin script, JSON, XML, and Markdown regression coverage,
  including pinned real-world JetBrains source files and large inputs.
- Light and dark plugin icons and the MIT License.

### Changed

- New installations show bracket-token colors and the active guide while
  leaving optional active-symbol borders and backgrounds disabled.
- Bracket recognition uses the brace matchers registered by the IDE and
  language plugins instead of scanning raw characters.
- Recognition and decoration are separated behind mockable interfaces.
- Structural results are cached so caret movement uses an interval-index lookup
  and updates at most one guide and two active-symbol ranges.
- Preview recognition is debounced, cancellable, and isolated from persisted
  settings and source editors.
