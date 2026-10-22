# Changelog

## 0.1.0

- Improved responsiveness when editing large blocks by moving bracket analysis
  and guide recalculation into cancellable background tasks.
- Reduced guide flicker during editing by keeping the previous drawing visible
  until the updated guide is ready.
- Fixed delayed guide restoration after reverting edits to the original file
  contents.
- Fixed guide restoration when returning to an editor tab.
- Reworked the internal analysis and rendering architecture, with stronger
  validation to prevent outdated results from being applied.
- Updated build and test tooling: Gradle 9.8.1, Spotless 8.10.4,
  kotlinx.coroutines 1.11.0 for tests and benchmarks, and actions/setup-java 6.1.0.

Note: Guide positions update asynchronously and may briefly lag behind text
movement, particularly in small blocks.

Requires IntelliJ Platform 2024.1 or newer.

## 0.0.6

- Fixed editor-update failures while IntelliJ computes context-action previews.
- Fixed UI-thread access exceptions when opening refactoring previews or other
  secondary editors.
- Restricted active guides and bracket endpoint emphasis to visible main editors
  that own focus, while preserving bracket colors when focus moves away and
  restoring active presentation when focus returns.
- Added automatic refresh of bracket colors in supported preview, diff, and
  console editors, including viewers without regular highlighting passes.
- Limited one-line main editors to horizontal guides and removed plugin markup
  from hidden editors.
- Stabilized temporary Git repository cleanup in benchmark tests and visual-test
  artifact downloads after reruns.

## 0.0.5

- Added an IntelliJ Integration group for managing native guide highlighting
  while leaving regular indent-guide visibility to IntelliJ.
- Added modes to suppress matched-brace and Current scope highlighting together,
  suppress Current scope only, or leave IntelliJ highlighting unchanged.
- Preserved and restored each owned IntelliJ setting, while treating later
  external changes as the user's newer choice.
- Added an informational notification for visible native highlighting beside a
  multiline plugin guide, shown once per IDE session and each newly enabled
  conflict, with an option to suppress only the current conflict episode.
- Removed repeated preference normalization from caret moves and skipped native
  guide-conflict work when highlighting is suppressed, the notice was already
  shown in the session, or the current conflict was explicitly suppressed.

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
