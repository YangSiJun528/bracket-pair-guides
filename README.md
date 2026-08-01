# Bracket Pair Guides

Bracket pair colorization and caret-activated guides for JetBrains IDEs.

<!-- Plugin description -->
Colorizes matching brackets by nesting level and shows an active guide for the
innermost pair containing the caret.

- Colors matching bracket tokens with a six-level repeating palette.
- Shows a C-shaped vertical and horizontal guide only for the innermost pair
  containing the primary caret.
- Optionally adds a border and background only to the opening and closing
  symbols of the active pair.
- Uses the language's JetBrains `PairedBraceMatcher` or file-type
  `BraceMatcher`, including strict XML tag-name matching.
- Uses one six-level base palette for bracket tokens, guide lines, pair borders,
  and pair backgrounds by default.
- Provides optional per-level guide, border, and background overrides.
- Provides independent switches for token colors, active symbols, vertical and
  horizontal guides, plus guide width and opacity.
- Shows unapplied changes in an editable editor preview beside the Settings
  controls.
<!-- Plugin description end -->

## Preview

Dark theme:

![Bracket Pair Guides settings and editable preview in the IntelliJ dark theme](docs/images/settings-preview-dark.png)

Light theme:

![Bracket Pair Guides settings and editable preview in the IntelliJ light theme](docs/images/settings-preview-light.png)

## Requirements

- IntelliJ Platform 2024.1 or newer
- A language or file-type plugin that registers `PairedBraceMatcher` or
  `BraceMatcher`

The plugin is compiled against IntelliJ IDEA Community 2024.1.7 and Java 17 to
keep the compatibility floor at build 241.

## Installation

1. Run `./gradlew buildPlugin`.
2. In the target IDE, open **Settings | Plugins**.
3. Open the gear menu and select **Install Plugin from Disk**.
4. Choose the ZIP in `build/distributions/`.
5. Restart the IDE if requested.

Place the caret inside a matched pair. Only the innermost containing pair gets
the guide and symbol emphasis. Moving outside all pairs removes both.

Configure behavior and colors in one place: **Settings | Editor | Bracket Pair
Guides**. Click a palette swatch to open the IDE color chooser; the preview
updates before you press Apply. Its example selector offers Java, Kotlin, JSON,
XML, and Markdown when the corresponding language file types are installed.
You can edit each example directly, switch formats without losing temporary
edits, and restore the selected boilerplate with **Reset**.

See [Configuration and conflict handling](docs/guide_configuration.md) for
installation, defaults, and coexistence recipes.

## Develop and verify

```shell
./gradlew test
./gradlew buildPlugin
./gradlew verifyPlugin
./gradlew runIde
```

`runIde` opens a sandboxed IntelliJ IDEA instance with the plugin installed.
Open `demo/BracketGuideDemo.java` to inspect nested, single-line, and multiline
pairs.

The regression suite analyzes pinned real-world JetBrains source files in Java,
Kotlin, Kotlin script, JSON, XML, and Markdown. It checks deterministic
recognition, caret-only activation, settings isolation, base-color derivation,
background and border attributes, the mockable recognition-to-rendering
boundary, long files, 50,000-pair indexing, malformed nesting, cancellation,
soft wraps, folding, bulk markup updates, and editable per-format Preview
recognition, cache reuse, and disposal.

## Scope and limits

Bracket tokens remain colored throughout the document when token coloring is
enabled. The guide and active-pair symbol style are caret-activated. This
matches VS Code's `editor.guides.bracketPairs: "active"` guide behavior rather
than hiding every bracket color outside the current pair. The plugin does not
shade the complete active scope.

The plugin consumes the token stream and brace matchers supplied by the IDE. It
does not parse raw characters as a fallback. File types without a supported
matcher are left unchanged. Rider C# requires separate validation because its
language analysis is backed by ReSharper rather than ordinary IntelliJ PSI.

See [Architecture and performance](docs/explanation_architecture.md) for the API
choices, cost model, and known limits.

## License

Copyright (c) 2026 sijun-yang. Distributed under the [MIT License](LICENSE).
