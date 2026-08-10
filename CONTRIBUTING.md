# Contributing

Use this guide to choose the correct module and run the same verification
boundaries enforced by the repository.

## Prerequisites

- Run Gradle with JDK 21, matching CI.
- Use the committed `./gradlew` wrapper rather than a system Gradle install.
- Allow the Foojay resolver to provision the configured compiler toolchain when
  it is not installed locally.

Production bytecode targets Java 17. Kotlin source is restricted to language
and API version 1.9 because the minimum supported IntelliJ Platform bundles
Kotlin 1.9. Changing the Gradle runtime, JVM toolchain, Kotlin language version,
or minimum IDE build is a compatibility change and must be reviewed together.

## Choose a module

| Change | Module | Primary verification |
|---|---|---|
| Analysis facade, matcher adaptation, pairing, snapshots, indexes | `engine` | `./gradlew :engine:check` |
| Highlighting passes, editor sessions, presentation, settings, plugin metadata | `plugin` | `./gradlew :plugin:check` |
| Pairing or sorting performance experiment | `benchmarks` | `./gradlew :benchmarks:jmhJar` |
| Build coordination or CI | Repository root | Run all affected module checks |

Keep one recognition implementation in `engine`. The `plugin` module should
translate editor events and settings into engine inputs and presentation, not
reimplement bracket semantics.

## Run tests

The repository uses JUnit 4.13.2. IntelliJ-bound tests also use the IntelliJ
Platform test framework and bundled Java/Kotlin test plugins configured by each
module.

Run both production test suites and compile the benchmark harness:

```shell
./gradlew :engine:check :plugin:check :benchmarks:jmhJar
```

Run only the module affected by a small change:

```shell
./gradlew :engine:check
./gradlew :plugin:check
```

Run one test class or method with Gradle's test filter:

```shell
./gradlew :engine:test \
  --tests '<fully-qualified-engine-test>'

./gradlew :plugin:test \
  --tests '<fully-qualified-plugin-test>'

./gradlew :plugin:test \
  --tests '<fully-qualified-plugin-test>.<method-name>'
```

Engine tests cover facade outcomes, matcher adaptation, platform-neutral
pairing, and indexes. Plugin tests cover highlighting-pass lifecycle, outcome
publication, settings transitions, visible-token viewport behavior, background
lifecycle, provisional guides, rendering, and persistence. Share fixtures from
test source sets; do not add production getters, defaults, or annotations only
to expose implementation state to tests.

## Run the plugin

Build the distributable ZIP:

```shell
./gradlew :plugin:buildPlugin
```

Start a sandboxed IntelliJ IDEA with the plugin installed:

```shell
./gradlew :plugin:runIde
```

The sandbox uses the target configured by the IntelliJ Platform Gradle plugin.
Use it for behavior that unit and fixture tests cannot establish, including
painting, theme changes, scrolling, split editors, and large-file interaction.

## ABI changes

Each module's `check` task verifies its committed Kotlin ABI baseline. The
engine also verifies that public Kotlin declarations stay in the root
`analysis` facade and that the platform-neutral Java pairing core has no
IntelliJ dependency.

Run only the two engine boundary guards with:

```shell
./gradlew \
  :engine:checkEngineApiPackages \
  :engine:checkPairingCorePlatformNeutrality
```

Do not update an ABI baseline merely to make a failing build green. First check
whether the declaration can remain `private` or `internal`. For an intentional
module-boundary change, review the source and ABI diff, then run:

```shell
./gradlew :engine:updateLegacyAbi :plugin:updateLegacyAbi
./gradlew :engine:check :plugin:check
```

`@ApiStatus.Internal` documents that the public JVM bridge is not a supported
consumer API; it does not provide JVM access control. The deployable `plugin`
module should continue to expose no Kotlin library API.

## Verify IntelliJ compatibility

Run fast descriptor and project-configuration checks after changing Gradle,
plugin metadata, extensions, services, dependencies, or the supported IDE
range:

```shell
./gradlew \
  :plugin:verifyPluginProjectConfiguration \
  :plugin:verifyPluginStructure
```

Run the full Plugin Verifier matrix for compatibility-sensitive changes:

```shell
./gradlew :plugin:verifyPlugin
```

The full task downloads the configured IDE matrix and is slower than module
tests. It checks binary compatibility and disallowed IntelliJ API usage; it
does not replace behavior tests or Qodana.

## Lint and static analysis

Qodana is the repository's only lint and static-analysis gate. The GitHub
Actions **Inspect Code** job runs the JVM Community linter and the
`qodana.recommended` inspection profile configured in `qodana.yml`. Its
`failThreshold` is zero, so a reported problem fails the gate unless the
inspection profile or exclusion is intentionally changed.

Qodana is a separate CI boundary, not a dependency of `:engine:check` or
`:plugin:check`. A green local Gradle build therefore does not imply a green
inspection job. Review Qodana findings on the pull request and reproduce them
with the corresponding IntelliJ inspection when possible.

There is no repository-wide auto-format gate. Use the IDE formatter for touched
code and avoid unrelated formatting changes. Do not assume that formatting
alone resolves a Qodana inspection; Qodana also checks semantic and structural
problems.

## Before requesting review

1. Run `./gradlew :engine:check :plugin:check :benchmarks:jmhJar`.
2. Run `./gradlew :plugin:buildPlugin` when the deployable plugin changed.
3. Run the relevant Plugin Verifier tasks for platform or descriptor changes.
4. Confirm intentional ABI changes are reflected in reviewed baselines.
5. Confirm the CI **Inspect Code** Qodana job passes.

For release-specific signing and publication steps, use the
[release procedure](docs/how_to_release.md).
