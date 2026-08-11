# Bracket Pair Guides

Colorizes matching brackets by nesting level and shows an active guide for the
innermost pair containing the caret.

## Features

- Six repeating nesting-level colors for matching bracket tokens.
- One caret-activated guide for the innermost containing pair, with independent
  horizontal and vertical segments.
- Optional border and background emphasis on the active opening and closing
  symbols.
- One Settings page for languages, colors, guide geometry, and active-pair
  styling.
- Language-aware matching through each token language's
  `com.intellij.lang.braceMatcher`.
- Cancellable background recognition and indexed caret queries; editor events
  never start bracket recognition on the event dispatch thread.
- IntelliJ's code-insight file-size policy plus independent structural and
  retained-index capacity guards. A failed analysis never publishes a capped
  pair prefix.

## IDE and language support

The plugin requires IntelliJ Platform build 241 (2024.1) or newer and depends
only on the platform-wide `platform` and `lang` modules. The Plugin Verifier
matrix covers representative IntelliJ IDEA versions; other standalone JetBrains
IDEs are load-compatible but are not all runtime-tested.

Language support is capability-based, not a hard-coded allowlist. An installed
token language is supported when its plugin registers
`com.intellij.lang.braceMatcher`. If the IDE does not provide that extension
point, Bracket Pair Guides reports one **Unsupported IDE** error at startup
instead of silently showing an empty language list.

See the [IDE and language support reference](docs/reference_language_support.md)
for the audited compatibility matrix and the
[language showcase](docs/example_language_showcase.md) for representative
source examples.

## Install a local build

1. Run `./gradlew :plugin:buildPlugin`.
2. Open **Settings | Plugins** in the target IDE.
3. Select **Install Plugin from Disk** from the gear menu.
4. Choose the ZIP in `plugin/build/distributions/`.

Open **Settings | Editor | Bracket Pair Guides** to configure the plugin. See
[Configuration and conflict handling](docs/guide_configuration.md) for all
options and coexistence guidance.

## Develop

The repository has one production Gradle module, `plugin`. The `benchmarks`
module is a measurement harness and contributes no production code.

Run the normal verification with:

```shell
./gradlew :plugin:check :benchmarks:jmhJar
```

`plugin:check` includes the behavior tests, ABI check, and ArchUnit package
rules. Qodana is the separate lint and static-analysis gate in CI; the
repository does not add a second Spotless, ktlint, detekt, or Checkstyle gate.

See [Contributing](CONTRIBUTING.md) for individual tests, `runIde`, architecture
changes, Plugin Verifier, and the complete pre-review workflow.

## Documentation

- [Current architecture](docs/explanation_architecture.md)
- [Current design and refactoring report](docs/explanation_design.md)
- [Performance and capacity reference](docs/reference_performance_limits.md)
- [IDE and language support reference](docs/reference_language_support.md)
- [Run the performance benchmarks](benchmarks/guide_benchmarking.md)
- [Release procedure](docs/how_to_release.md)
- [Historical implementation reports](docs/history/README.md)

## License

Copyright (c) 2026 sijun-yang. Distributed under the [MIT License](LICENSE).
