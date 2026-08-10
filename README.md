# Bracket Pair Guides

Colorizes matching brackets by nesting level and shows an active guide for the
innermost pair containing the caret.

## Features

- Six repeating nesting-level colors for matching bracket tokens.
- One caret-activated guide for the innermost containing pair, with independent
  horizontal and vertical segments.
- Optional border and background emphasis on the active opening and closing symbols.
- One standard Settings page for languages, colors, guide geometry, and active-pair styling.
- Language-aware matching through each token language's
  `com.intellij.lang.braceMatcher`.
- Cancellable background recognition with indexed caret queries; stale or absent
  snapshots never start bracket recognition on the event dispatch thread.
- The host IDE's code-insight file-size policy is honored before recognition;
  adversarial input is additionally capped at 100,000 completed pairs and
  50,000 pending openers without publishing a capped pair prefix.
- Exact guide indexing for spans through 1,032,192 lines within a 4 MiB retained
  payload. A larger guide is hidden while exact token and active-pair indexes
  remain available.

## IDE and language support

The plugin requires IntelliJ Platform build 241 (2024.1) or newer and only the
platform-wide `platform` and `lang` modules. It is load-compatible with
standalone JetBrains IDEs such as IntelliJ IDEA, WebStorm, PyCharm, GoLand,
RustRover, PhpStorm, RubyMine, CLion, Rider, and DataGrip. The current
Plugin Verifier matrix covers IntelliJ IDEA Community 2024.1 through 2025.2
and IntelliJ IDEA Ultimate 2026.2; the other products are load-compatible but
are not all verifier- or runtime-tested.

Language recognition is a separate capability check. A token language works
when its installed language plugin registers `com.intellij.lang.braceMatcher`.
If the IDE itself does not provide that extension point, the plugin reports one
**Unsupported IDE** error at startup instead of silently presenting an empty
language capability.

| Audit status | Languages and limits |
|---|---|
| Direct, common registrations confirmed | Java, Kotlin/KTS, JSON, JavaScript, Python, Go, Rust, Shell Script, TOML, Groovy, SQL |
| Direct, web and data registrations confirmed | CSS, LESS, SASS, SCSS, protobuf, prototext, MongoDB-JSON, MongoJS |
| Direct, build/config/template registrations confirmed | CMake, Makefile, Devicetree (DTS), Linker Script, Go Template, Go build constraints, Go modules/workspaces, HCL/Terraform, HIL, EJS |
| Direct, specialized registrations confirmed | RegExp, Mermaid, JSONPath, EditorConfig, XPath, RELAX-NG Compact, JQL, Qute, EL, JPAQL, JSP, FreeMarker, Velocity, Micronaut EL, SpEL, Dockerfile, DockerIgnore, GitHub Expression, Android Gradle Declarative, Git Ignore/Git Exclude, Hg Ignore |
| Conditional or partial | TypeScript/JSX/TSX through JavaScript; YAML flow collections; JavaScript regions in Vue/Angular; platform custom file types using syntax-table bracket tokens |
| No compatible registration found in the audit | XML, HTML, Markdown, TextMate, C/C++ |
| Not conclusively audited | PHP, Ruby, C#, and third-party language plugins |

This table is an audit snapshot, not a hard-coded allowlist. The runtime
capability check and the **Languages** list in Settings are authoritative, so
an installed language plugin can add support without a Bracket Pair Guides
release. Legacy file-type-only `com.intellij.braceMatcher` registrations are
intentionally not used. See the
[IDE and language support reference](docs/reference_language_support.md) for
the verified boundary and the [language showcase](docs/example_language_showcase.md)
for representative source examples.

## Install a local build

1. Run `./gradlew :plugin:buildPlugin`.
2. Open **Settings | Plugins** in the target IDE.
3. Select **Install Plugin from Disk** from the gear menu.
4. Choose the ZIP in `plugin/build/distributions/`.

Open **Settings | Editor | Bracket Pair Guides** to configure the plugin. See
[Configuration and conflict handling](docs/guide_configuration.md) for all
options, per-language controls, and coexistence guidance.

## Develop and verify

```shell
./gradlew :engine:check :plugin:check
./gradlew :benchmarks:jmhJar
./gradlew :plugin:buildPlugin
./gradlew :plugin:verifyPluginProjectConfiguration :plugin:verifyPluginStructure
./gradlew :plugin:verifyPlugin
./gradlew :plugin:runIde
```

The module `check` tasks run the engine's Kotlin and platform-neutral Java tests
and verify the committed ABI baselines in `engine/api/` and `plugin/api/`. The
engine baseline contains only the root `analysis` facade: the `BracketAnalysis`
entry point, its input/outcome/snapshot boundary, and related domain values. A
package check rejects public Kotlin ABI elsewhere. Only after reviewing an
intentional boundary change, update the baselines with:

```shell
./gradlew :engine:updateLegacyAbi :plugin:updateLegacyAbi
```

`:plugin:runIde` opens a sandboxed IntelliJ IDEA instance with the plugin installed.
`:plugin:verifyPlugin` resolves JetBrains' recommended cross-version matrix plus an
explicit IntelliJ IDEA 2026.2 endpoint; the minimum published build remains
pinned to 241 when the test fixture is upgraded.
The `engine` module groups platform-neutral pairing state and primitive pair
storage under `analysis.pairing.core`, adapts IntelliJ matchers, compiles
requested coverage into an index layout, assembles snapshots, and exposes one
IntelliJ-bound Application Service as the intentional adapter between editor
token semantics and the platform-neutral pairing core. Analysis publishes a
complete snapshot, an exact lower-facet snapshot when only guide capacity is
crossed, or an unavailable result carrying the attempted input stamp; it never
publishes a capped pair prefix. Equivalent split-editor results share an
immutable `BracketIndexes` payload, while each editor keeps its own snapshot
stamp, active-pair memo, and presentation state. Editor integration, settings,
and deployable plugin tasks live in `plugin`. The isolated `benchmarks` module
runs JMH against compiled engine implementations. The repository root
coordinates the Gradle build and shared release metadata.
The regression suite covers Java, Kotlin, Kotlin script, JSON, contextual and
custom-file-type matchers, unsupported legacy-only file types, and large inputs.
Performance experiments live in the isolated `benchmarks` module; see
[Run the performance benchmarks](benchmarks/guide_benchmarking.md).

For implementation details, see
[Architecture and performance](docs/explanation_architecture.md) and the
[object design review](docs/explanation_object_design.md). The
[production and test boundary review](docs/explanation_test_boundaries.md)
records why test-only production hooks were removed and what each test is
allowed to observe.
Before the first public release, follow the
[release checklist](docs/how_to_release.md); JetBrains requires the initial
Marketplace publication to be uploaded manually before Gradle-based updates.

## License

Copyright (c) 2026 sijun-yang. Distributed under the [MIT License](LICENSE).
