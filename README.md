# Bracket Pair Guides

Colorizes matching brackets by nesting level and shows an active guide for the
innermost pair containing the caret.

## Preview

| Dark theme | Light theme |
|---|---|
| ![Bracket Pair Guides settings and preview in a dark theme](docs/images/settings-preview-dark.png) | ![Bracket Pair Guides settings and preview in a light theme](docs/images/settings-preview-light.png) |

The screenshots demonstrate the optional active-symbol border and background.
Both are off by default; the default active presentation draws only the guide.

## Features

- Six repeating nesting-level colors for matching bracket tokens.
- One caret-activated guide for the innermost containing pair.
- Optional border and background emphasis on the active opening and closing symbols.
- One Settings page for colors, guide geometry, active-pair styling, and an editable preview.
- Language-aware matching through each token language's
  `com.intellij.lang.braceMatcher`.

## Requirements

- IntelliJ Platform 2024.1 or newer.
- An active token language that registers `com.intellij.lang.braceMatcher`.

Support is capability-based rather than tied to an IDE product list. A
language works in any JetBrains IDE where its plugin registers that extension.
Legacy file-type-only `com.intellij.braceMatcher` registrations are not used.

## Install a local build

1. Run `./gradlew buildPlugin`.
2. Open **Settings | Plugins** in the target IDE.
3. Select **Install Plugin from Disk** from the gear menu.
4. Choose the ZIP in `build/distributions/`.

Open **Settings | Editor | Bracket Pair Guides** to configure the plugin. See
[Configuration and conflict handling](docs/guide_configuration.md) for all
options and coexistence guidance.

## Develop and verify

```shell
./gradlew check
./gradlew buildPlugin
./gradlew verifyPlugin
./gradlew runIde
```

`runIde` opens a sandboxed IntelliJ IDEA instance with the plugin installed.
The regression suite covers Java, Kotlin, Kotlin script, JSON, contextual
language matchers, unsupported legacy-only file types, and large inputs.

For implementation details, see
[Architecture and performance](docs/explanation_architecture.md).

## License

Copyright (c) 2026 sijun-yang. Distributed under the [MIT License](LICENSE).
