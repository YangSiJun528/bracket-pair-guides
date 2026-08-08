# Language and IDE Support Reference

This page records the verified support boundary as of 2026-08-05. IDE loading,
binary verification, runtime testing, and language recognition are separate
claims.

## Support terms

| Term | Meaning |
|---|---|
| Platform-load compatible | The plugin descriptor permits the plugin to load in that IntelliJ Platform product and build. |
| Verifier-tested | JetBrains Plugin Verifier reported the plugin compatible with that exact IDE distribution. This is not an IDE launch test. |
| Runtime-tested | The repository's tests executed against that IDE test fixture and its installed language plugins. |
| Capability-supported | The active token language resolves a `com.intellij.lang.braceMatcher` through `LanguageBraceMatching`. |

## Platform compatibility

The plugin requires only `com.intellij.modules.platform` and
`com.intellij.modules.lang`. Its generated descriptor has `since-build="241"`
and no product-specific dependency or upper build limit. It is therefore
load-compatible in standalone IntelliJ Platform products based on build 241 or
newer when those two modules are present.

Load compatibility does not establish language support. PyCharm can satisfy
the plugin's load dependencies, while Python recognition is supplied
separately by the installed Python language plugin's matcher registration. The
same distinction applies to WebStorm, GoLand, CLion, Rider, RustRover, and
other products.

### Plugin Verifier results

| Product | Versions actually verified | Result |
|---|---|---|
| IntelliJ IDEA Community | 2024.1.7, 2024.2.6, 2024.3.7.1, 2025.1.7.2, 2025.2.6.3 | Compatible |
| IntelliJ IDEA Ultimate | 2026.2 | Compatible |
| RustRover | 2026.2 | Not completed: product dependency resolution failed during the audit |
| Other products | None | Not verifier-tested |

All six completed IntelliJ IDEA verifier runs were Compatible and report no
deprecated/internal API use or required plugin-dependency or descriptor
problems. Unresolved optional dependencies shown for a target IDE are not
plugin failures. A verifier result checks the packaged plugin against that
product binary; it does not launch the IDE or exercise the product's language
runtime.

The repository runtime fixture is IntelliJ IDEA Community 2024.1.7. It runs
Java, Kotlin, Kotlin script, JSON, and custom syntax-table matcher regressions.
Official 2026.2 language-plugin distributions and the audited Go 2026.1
distribution were also inspected statically, but those products and languages
were not launched as part of that descriptor audit.

## Runtime capability rule

Recognition normally uses the language of each editor-highlighter token:

1. Resolve `LanguageBraceMatching.INSTANCE.forLanguage(tokenLanguage)`.
2. Use the registered `BraceMatcher`, or adapt its `PairedBraceMatcher`.
3. Ignore the token when no language matcher resolves.

The narrow exception is a platform `UserFileType`: its eight official
`CustomHighlighterTokenType` bracket tokens resolve through the registered
`TEXT` matcher. Other `Language.ANY` tokens and ordinary plain text are still
ignored.

There is no raw-character scanner, product-specific backend, or fallback to
the legacy file-type `com.intellij.braceMatcher`. Layered files can therefore
be partially supported: an embedded JavaScript region can work while its host
template language remains unsupported.

## Language capability matrix

| Language or file kind | Status | Verified boundary and limits |
|---|---|---|
| Java | Direct | Language matcher registered; runtime regression-tested in IC 2024.1.7. |
| Kotlin and Kotlin script | Direct | Language matcher registered; runtime regression-tested in IC 2024.1.7. |
| JSON | Direct | Language matcher registered; runtime regression-tested in IC 2024.1.7. |
| JavaScript | Direct | Language matcher registration found in the official distribution audit. |
| TypeScript, JSX, TSX | Conditional | Resolves through the JavaScript base-language relationship. Support depends on that relationship remaining present in the installed language-plugin version. |
| Python | Direct | Language matcher registration found in the official distribution audit. |
| Go | Direct | Language matcher registration found in the audited Go 2026.1 distribution. |
| Rust | Direct when installed | Rust 2026.2 registers `RsBraceMatcher`; see the Rust section below. |
| YAML | Partial | The matcher covers flow collections using `{}`, `[]`. Block indentation is not a bracket pair. |
| Shell Script | Direct | Language matcher registration found in the official distribution audit. |
| TOML | Direct | Language matcher registration found in the official distribution audit. |
| Groovy | Direct when installed | Official JetBrains source registers `GroovyBraceMatcher`. |
| SQL | Direct when installed | The official Database Tools 2026.2 descriptor registers `SqlPairedBraceMatcher` for `SQL`. |
| CMake | Direct when installed | The audited CLion 2026.2 matcher covers `()`, `${}`, and paired `foreach`/`function`/`macro`/`while`/`block` end keywords. This does not imply C or C++ source support. |
| Makefile | Direct when installed | The audited matcher covers `define`/`endef`, `${}`, and `$()` in the installed Makefile token language. |
| Devicetree (DTS) | Direct when installed | The audited CLion 2026.2 matcher covers `{}`, `[]`, `<>`, and `()`; only `{}` is structural. |
| Linker Script | Direct when installed | The audited CLion 2026.2 matcher covers `{}`, `()`, and `[]`; only `{}` is structural. |
| Go Template | Direct when installed | The audited GoLand 2026.1.4 matcher covers `{{...}}` and `()`. |
| Go build constraints (`GoBuild`) | Direct when installed | The audited GoLand 2026.1.4 matcher covers `()`. |
| Go modules/workspaces (`vgo`) | Direct when installed | The audited GoLand 2026.1.4 matcher covers `()` and `[]`. |
| HCL, HCL-Terraform | Direct when installed | The audited GoLand 2026.1.4 Terraform/HCL plugin matcher covers `{}`, `[]`, and `()`. |
| HIL | Direct when installed | The audited GoLand 2026.1.4 Terraform/HCL plugin matcher covers interpolation/template openers through `}`, plus `{}`, `()`, and `[]`. |
| EJS | Direct when installed | The audited WebStorm 2026.2 matcher pairs EJS opener variants with its close token; this does not imply general HTML support. |
| CSS, LESS, SASS, SCSS | Direct when installed | Matching registrations were found in the official 2026.2 web-language distribution. |
| protobuf, prototext | Direct when installed | Matching registrations were found in the official 2026.2 protocol-buffer plugin. |
| MongoDB-JSON, MongoJS | Direct when installed | Matching registrations were found in the official 2026.2 database distribution. |
| RegExp | Direct when installed | Official JetBrains source registers `RegExpBraceMatcher`; only tokens assigned to the RegExp language are in scope. |
| Mermaid | Direct when installed | Official JetBrains source registers `MermaidPairMatcher`. |
| JSONPath | Direct when installed | Official JetBrains source registers `JsonPathPairedBraceMatcher`. |
| EditorConfig | Direct when installed | Official JetBrains source registers `EditorConfigBraceMatcher`. |
| XPath | Direct when installed | Official JetBrains source registers `XPathPairedBraceMatcher`. |
| RELAX-NG Compact | Direct when installed | Official JetBrains source registers its compact-syntax paired matcher. |
| JQL | Direct when installed | The official Jira task plugin registers `JqlBraceMatcher`. |
| Qute, EL, JPAQL, JSP | Direct when installed | Matching registrations were found in the official 2026.2 distributions. |
| FreeMarker, Velocity | Direct when installed | Matching registrations were found in the official 2026.2 template-language plugins. |
| Micronaut EL, SpEL | Direct when installed | Matching registrations were found in the official 2026.2 framework plugins. |
| Dockerfile, DockerIgnore | Direct when installed | Matching registrations were found in the official 2026.2 Docker plugin. |
| GitHub Expression | Direct when installed | A matching registration was found in the official 2026.2 GitHub Actions plugin. |
| Android Gradle Declarative | Direct when installed | A matching registration was found in the official 2026.2 Android distribution. |
| Git Ignore, Git Exclude, Hg Ignore | Direct when installed | The official Git and Mercurial plugins register `IgnoreBraceMatcher` for these token languages. |
| Platform custom file types | Conditional, runtime-tested | The official `TEXT` registration uses `CustomFileTypeBraceMatcher`; repository tests cover syntax-table `{}`, `[]`, and `()` tokens plus the language toggle. Raw plain text is not scanned. |
| Vue and Angular templates | Partial | JavaScript expression tokens can work; the host template portion is not implied to work. |
| XML, HTML, Markdown, TextMate | Unsupported by the current gate | No compatible language matcher registration was found in the audited distributions. Any legacy file-type registration is outside this plugin's gate. |
| C and C++ | Unsupported by the current gate | No language matcher registration was found in the audited CLion 2026.2 distribution. |
| PHP, Ruby, C#, third-party languages | Not conclusively audited | Do not infer support from the IDE product. The installed matcher and Settings list are authoritative. |

"Direct" describes capability registration, not a runtime test in every IDE.
The installed language plugin remains authoritative and can change this matrix
in a later release.

## Rust support

The official Rust 2026.2 plugin descriptor contains this language extension:

```xml
<lang.braceMatcher
    language="Rust"
    implementationClass="org.rust.ide.typing.RsBraceMatcher"/>
```

That matcher declares `{}`, `()`, `[]`, and `<>`; only `{}` is marked
structural. For `<` and `>`, `RsBraceMatcher` uses lexical context to
distinguish generic delimiters from comparison operators.

Bracket Pair Guides has no hard Rust dependency. When a compatible Rust
language plugin is installed and its matcher is active, Rust support is picked
up automatically. This conclusion comes from the official descriptor and
matcher audit. RustRover 2026.2 was not runtime-tested, and its Plugin Verifier
run did not complete. The successful IntelliJ IDEA Ultimate 2026.2 verifier
result establishes platform binary compatibility only; it is not a Rust
runtime test.

## Evidence boundary

- Load compatibility: `plugin/src/main/resources/META-INF/plugin.xml`, the explicitly
  pinned `since-build="241"`, JetBrains' recommended Plugin Verifier matrix,
  and the explicit IntelliJ IDEA 2026.2 endpoint in `plugin/build.gradle.kts`.
- Runtime fixture: `plugin/build.gradle.kts` and the repository language regression
  tests.
- Binary compatibility: six Plugin Verifier reports—five IntelliJ IDEA
  Community releases from 2024.1 through 2025.2 and IntelliJ IDEA Ultimate
  2026.2—produced by the audit.
- Language capability: official JetBrains language-plugin descriptors and
  matcher declarations inspected through the 2026.2 distributions and the
  audited Go 2026.1 distribution, plus these
  open-source registrations at the audited JetBrains revision:
  [Groovy](https://github.com/JetBrains/intellij-community/blob/6377ede21f8b2d5c36c9215f1365d2fe349e9eca/plugins/groovy/resources/META-INF/plugin.xml),
  [RegExp](https://github.com/JetBrains/intellij-community/blob/6377ede21f8b2d5c36c9215f1365d2fe349e9eca/RegExpSupport/resources/intellij.regexp.xml),
  [Mermaid](https://github.com/JetBrains/intellij-community/blob/6377ede21f8b2d5c36c9215f1365d2fe349e9eca/plugins/mermaid/resources/META-INF/plugin.xml),
  [JSONPath](https://github.com/JetBrains/intellij-community/blob/6377ede21f8b2d5c36c9215f1365d2fe349e9eca/plugins/jsonpath/resources/META-INF/plugin.xml),
  [EditorConfig](https://github.com/JetBrains/intellij-community/blob/6377ede21f8b2d5c36c9215f1365d2fe349e9eca/plugins/editorconfig/common/resources/intellij.editorconfig.common.xml),
  [XPath](https://github.com/JetBrains/intellij-community/blob/6377ede21f8b2d5c36c9215f1365d2fe349e9eca/plugins/xpath/xpath-lang/resources/META-INF/plugin.xml),
  [RELAX-NG Compact](https://github.com/JetBrains/intellij-community/blob/6377ede21f8b2d5c36c9215f1365d2fe349e9eca/xml/relaxng/resources/intellij.relaxng.xml),
  [JQL](https://github.com/JetBrains/intellij-community/blob/6377ede21f8b2d5c36c9215f1365d2fe349e9eca/plugins/tasks/tasks-core/jira/resources/intellij.tasks.jira.xml),
  [Git Ignore/Exclude](https://github.com/JetBrains/intellij-community/blob/6377ede21f8b2d5c36c9215f1365d2fe349e9eca/plugins/git4idea/backend/resources/intellij.vcs.git.backend.xml),
  [Hg Ignore](https://github.com/JetBrains/intellij-community/blob/6377ede21f8b2d5c36c9215f1365d2fe349e9eca/plugins/hg4idea/resources/META-INF/plugin.xml), and
  [custom file types](https://github.com/JetBrains/intellij-community/blob/6377ede21f8b2d5c36c9215f1365d2fe349e9eca/platform/platform-resources/src/META-INF/LangExtensions.xml).
- Platform API contract:
  [JetBrains brace matching extension documentation](https://plugins.jetbrains.com/docs/intellij/additional-minor-features.html).
- Product/module compatibility model:
  [JetBrains plugin compatibility documentation](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html).
