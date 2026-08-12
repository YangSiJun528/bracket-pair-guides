# Language and IDE Support Reference

This page explains the current support rule. The installed language plugins are
authoritative; Bracket Pair Guides does not maintain a separate language
allowlist.

## Platform compatibility

The plugin requires `com.intellij.modules.platform` and
`com.intellij.modules.lang`. Its generated descriptor has `since-build="241"`
and no product-specific dependency or upper build limit. Standalone IntelliJ
Platform products based on build 241 or newer can load the plugin when those two
modules are present.

Load compatibility does not establish language support. An IDE can satisfy the
module dependencies while its primary language remains unsupported by the
matching rule below.

## Runtime capability rule

Recognition normally uses the language of each editor-highlighter token:

1. Resolve `LanguageBraceMatching.INSTANCE.forLanguage(tokenLanguage)`.
2. Use the registered `BraceMatcher`, or adapt its `PairedBraceMatcher`.
3. Ignore the token when no language matcher resolves.

The narrow exception is a platform `UserFileType`: its official
`CustomHighlighterTokenType` bracket tokens resolve through the registered
`TEXT` matcher. Other `Language.ANY` tokens and ordinary plain text are ignored.

There is no raw-character scanner, product-specific backend, or fallback to the
legacy file-type `com.intellij.braceMatcher`. Layered files can therefore be
partially supported: an embedded language region can work while its host
template language remains unsupported.

## Support boundaries

| Language or file kind | Support |
|---|---|
| Java, Kotlin, Kotlin script, and JSON | Covered by repository runtime regressions and the installed matcher |
| Other installed languages | Supported when their token language resolves `com.intellij.lang.braceMatcher` |
| Derived and embedded languages | Supported only for tokens that resolve an inherited or embedded-language matcher |
| Platform custom file types | Syntax-table bracket tokens are supported through the platform `TEXT` matcher |
| Raw plain text | Unsupported; characters are never treated as brackets without matcher tokens |
| Languages with only the legacy file-type matcher | Unsupported by the current recognition gate |

The **Languages** list in Settings is the runtime source of truth. Bracket Pair
Guides automatically discovers installed matcher families and requires no
product-specific integration. Installing, removing, or updating a language
plugin can therefore change the available families and exact bracket pairs.

## Verification boundary

Repository tests exercise matcher discovery, contextual matching, unsupported
tokens, language-family settings, and representative Java, Kotlin, Kotlin
script, JSON, and custom-file-type inputs. The configured Plugin Verifier tasks
check binary compatibility and IntelliJ API usage; they do not launch every IDE
or exercise every installed language plugin.

Run the relevant checks described in
[Contributing](../CONTRIBUTING.md#verify-intellij-compatibility) after changing
the supported IDE range, dependencies, plugin descriptor, or IntelliJ APIs.

Related JetBrains documentation:

- [Brace matching extension](https://plugins.jetbrains.com/docs/intellij/additional-minor-features.html)
- [Plugin compatibility](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html)
