# Changelog

## 0.0.4

- Preserved configured bracket colors in visible IntelliJ Sticky Lines while
  scrolling beyond the ordinary viewport token window.

## 0.0.3

- Moved the language-family settings to the bottom of the settings page.
- Added enable-all and disable-all language actions.
- Added an animated bracket-guide demo to the README and Marketplace media.
- Added support for legacy file-type brace matchers.
- Added a file-level warning when no IntelliJ brace matcher is available;
  Rider/CLion warnings link to the
  [ReSharper backend support request](https://github.com/YangSiJun528/bracket-pair-guides/issues/19).
- Preserved guide thickness at the left edge of the editor.
- Removed per-brace structural wrapper allocations from document analysis.
- Clarified that other highlighting plugins can affect bracket colors.

## 0.0.2

Initial public release.

- Color matched bracket tokens with a six-level repeating palette.
- Trace the innermost pair containing the caret with configurable horizontal
  and vertical guide segments.
- Optionally emphasize active endpoints with a border or background.
- Suppress IntelliJ's native matched-brace foreground and background by default,
  with an option to restore the native behavior.
- Discover supported language families from installed IntelliJ brace matchers.
- Avoid deprecated IntelliJ API bridges during dynamic plugin unload.

## 0.0.1

Pre-release Marketplace upload.

- Color matched bracket tokens with a six-level repeating palette.
- Trace the innermost pair containing the caret with configurable horizontal
  and vertical guide segments.
- Optionally emphasize active endpoints with a border or background.
- Suppress IntelliJ's native matched-brace foreground and background by default,
  with an option to restore the native behavior.
- Discover supported language families from installed IntelliJ brace matchers.
- Configure palette colors, component overrides, guide geometry, and language
  families under Editor settings.
