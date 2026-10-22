# Contributing

Use this guide to choose a source area and run the repository's verification
boundaries.

## Prerequisites

- Run Gradle with JDK 21, matching CI.
- Use the committed `./gradlew` wrapper.
- Allow the Foojay resolver to provision the configured compiler toolchain when
  it is not installed locally.

Platform-facing modules target Java 21 and Kotlin language/API 2.2, matching
the minimum supported IntelliJ Platform 2025.3. Pure analysis and benchmark
modules retain their independent Java 17 target.
Changing the Gradle runtime, JVM toolchain, Kotlin version, or minimum IDE build
is one compatibility change and must be reviewed together.

## Enable local checks

Install [prek](https://prek.j178.dev/installation/), then enable the repository's
pre-commit and pre-push hooks from the repository root:

```shell
prek install
```

The committed `.pre-commit-config.yaml` installs both hook stages. Keep JDK 21
available to Git's environment, including when committing or pushing from an IDE.

| Stage | Command | When it runs |
|---|---|---|
| Pre-commit | `./gradlew spotlessCheck` | Staged files include a configured Spotless target |
| Pre-push | `./gradlew :check` (unit tests and package rules) | Pushed changes include files outside the configured documentation and media exclusions |

Each hook runs its Gradle command once for the whole project; the file filters
only decide whether to start it. Documentation-only changes in the excluded
paths skip both hooks. Regression fixtures remain eligible for pre-push tests,
even though Spotless does not format them.

Run either stage without committing or pushing:

```shell
prek run --all-files
prek run --stage pre-push --all-files
```

The hooks report failures without applying fixes. For a formatting failure,
run `./gradlew spotlessApply`, review and stage the changes, then retry the
commit. For a test failure, fix the reported issue and rerun the pre-push stage.
With partially staged files, prek temporarily hides unstaged changes during
pre-commit and restores them afterward. Pre-push checks the current working
tree; the required CI jobs remain the verification of the pushed revision.

The local `check` task does not start IDE fixtures, build the distribution, or
compile benchmarks. CI runs these through `ciCheck`. Qodana, Plugin Verifier,
visual tests, and performance measurements remain separate CI jobs.

## Choose a source area

The repository ships one plugin assembled from five production modules.
`analysis-model` and `analysis-core` have no IntelliJ SDK dependency. UI compiles
against model contracts; runtime composes UI and core; plugin registers and
packages the modules. See [Analysis execution](docs/explanation_analysis_execution.md)
for the module dependencies and result contracts.

| Change | Source area | Primary verification |
|---|---|---|
| Platform-free values and read-only result contracts | `analysis-model/src/main` | `./gradlew -p tools/pure-build :analysis-model:check` |
| Pairing, indexes, and guide calculation | `analysis-core/src/main` | `./gradlew -p tools/pure-build :analysis-core:check` |
| Editor lifetime, presentation, settings, and work requests | `editor-ui/src/main` | `./gradlew :check :plugin:minimumSdkTests` |
| IntelliJ capture, execution, cancellation, and publication | `analysis-runtime/src/main` | `./gradlew :check :plugin:minimumSdkTests` |
| Registration and release packaging | `plugin/src/main/resources` | `./gradlew :plugin:buildPlugin :plugin:verifyPluginStructure` |
| Pairing or sorting measurement | `benchmarks/src/jmh` | `./gradlew :benchmarks:jmhJar` |
| Build coordination or CI | Repository root | `./gradlew ciCheck` |

Keep one implementation of bracket semantics in production source. Editor and
settings code should consume analysis outcomes and queries instead of
reimplementing recognition or index behavior.

## Change a module or package boundary

Run the local checks after changing module dependencies or package calls:

```shell
./gradlew :check
```

Gradle's existing `api` and `implementation` declarations control compiler
visibility. ArchUnit checks actual package references. There is no custom
module-audit plugin or second dependency allow-list. To compile and test the two
platform-free modules without configuring IntelliJ, use
`./gradlew -p tools/pure-build check`.

The package rules in
[`ResponsibilityBoundaryTest.kt`](plugin/src/test/kotlin/com/sijunyang/bracketpairguides/architecture/ResponsibilityBoundaryTest.kt)
check compiled production references. UI can request work through `ui.work` and
read model results. Runtime enters UI only through those work contracts; plugin
enters runtime only through `runtime.bootstrap`. Only `runtime.session` calls
calculation entry points from outside core, and `core.internal` stays private to
core. UI cannot use
coroutines to schedule its own calculation. See
[Module architecture](docs/explanation_module_architecture.md) for the dependency graph.

To move a responsibility, update the relevant package rule and run the affected
behavior tests. Do not add rules for every private helper or method. Run only the
architecture suite during iteration with:

```shell
./gradlew :plugin:test \
  --tests 'com.sijunyang.bracketpairguides.architecture.ResponsibilityBoundaryTest'
```

Package rules constrain static references; they cannot determine which thread
executes a method. Preserve the background dispatcher in runtime sessions, the
EDT/read-access guard in `EditorSource.beginAttempt`, and the IDE thread tests.
Run `./gradlew :plugin:minimumSdkTests` after changing scheduling or SDK access.

## Run tests

JUnit 4.13.2 runs the tests, while AssertJ 3.27.7 is the assertion API.
IntelliJ-bound tests also use the IntelliJ Platform test framework and bundled
Java and Kotlin test plugins. Keep JUnit annotations, runners, rules, and
`BasePlatformTestCase`; do not use `org.junit.Assert` or inherited JUnit
assertion helpers.

Run formatting, ordinary owner tests, and package rules:

```shell
./gradlew :check
```

Run the full CI verification locally when needed:

```shell
./gradlew ciCheck
```

This includes `check`, both IDE fixture versions, benchmark-report tests,
JetBrains project/structure verification, the distribution ZIP, and benchmark
harness compilation. It does not run
Qodana, Plugin Verifier, visual tests, or performance measurements.

Run the IDE fixtures separately:

```shell
./gradlew :plugin:minimumSdkTests :plugin:currentSdkTests
```

Run one test class or method:

```shell
./gradlew :plugin:minimumSdkTests \
  --tests '<fully-qualified-IdeContractTest>'

./gradlew :plugin:minimumSdkTests \
  --tests '<fully-qualified-IdeContractTest>.<method-name>'
```

Use `:analysis-model:test` or `:analysis-core:test` for a test owned by a pure
module. IntelliJ integration fixtures live in their production owner's `src/test`
and run through the plugin's official IDE sandbox. Ordinary `test` tasks exclude
classes ending in `IdeContractTest`.

Test observable behavior through product inputs and outcomes. Reuse production
snapshot and policy objects from the same module when their internal visibility
is sufficient. Keep pure scenario setup in its owning module's test sources and
IntelliJ integration setup and call recording in the relevant owner's `src/test`.
Do not add a production getter, convenience overload, fake hierarchy, or
`@TestOnly` declaration solely to expose implementation state.

Prefer assertions that name the observable contract: collection contents and
order, numeric bounds, object identity, and exception type or message. Avoid
soft assertions and unrestricted recursive comparison; both can hide the first
broken invariant or couple a test to implementation fields.

Document-edit presentation tests must assert immediately after the write action,
before another daemon or highlighting pass runs. Cover insertion, replacement,
deletion, and equal-length space/tab replacement. A surviving tracked pair must
already have current endpoint emphasis. If current guide geometry is unavailable,
replay the last complete visible guide drawing while repair remains pending.
Verify unchanged pixels across edits and partial repaints, atomic replacement
after repair, and invalidation when the viewport or editor layout changes. Invalid endpoints
or lost presentation ownership must clear immediately; provisional pixels must
not certify current geometry or native-highlight evidence.

## Run the plugin

Build the distributable ZIP:

```shell
./gradlew :plugin:buildPlugin
```

Start a sandboxed IntelliJ IDEA with the plugin installed:

```shell
./gradlew :plugin:runIde
```

Use the sandbox for behavior that tests cannot establish reliably, including
painting, theme changes, scrolling, split editors, and large-file interaction.

For native highlighting and warning checks with IntelliJ's native settings
enabled, open the visual-test fixture for manual QA:

```shell
./gradlew :plugin:runManualQa
```

See [the manual QA guide](docs/guide_manual_qa.md) for its starting state and
minimum checklist.

## Review implementation visibility

The plugin is not a library and does not maintain a supported public API or ABI
baseline. Keep Kotlin implementation `private` or `internal` unless a concrete
runtime consumer requires wider visibility. The Java pairing core remains
JVM-public because sibling packages and the separate JMH module compile against
it; this is implementation access, not a compatibility promise.

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

The full task downloads the configured IDE matrix. It checks binary
compatibility and disallowed IntelliJ API usage; it does not replace behavior
tests, ArchUnit, or Qodana.

## Format and inspect code

Spotless is the repository-wide format gate. It uses ktlint for Kotlin source
and Gradle Kotlin DSL, Google Java Format's four-space AOSP style for Java, and
built-in whitespace rules for repository configuration files. Formatter and
plugin versions are pinned in the Gradle build.

Apply formatting after changing source or build files:

```shell
./gradlew spotlessApply
```

Check formatting without changing files:

```shell
./gradlew spotlessCheck
```

The root `check` task includes `spotlessCheck`. Upstream regression fixtures
under `plugin/src/test/testData` are excluded so their recorded contents remain
unchanged.

Qodana is the repository's lint and static-analysis gate. The GitHub Actions
**Inspect Code** job runs the JVM Community linter and the
`qodana.recommended` profile from `qodana.yml`. Its `failThreshold` is zero.

Qodana is separate from Gradle `check`. A green local build does not imply a
green inspection job. Review Qodana findings on the pull request and reproduce
them with the corresponding IntelliJ inspection when possible.

## Before requesting review

1. Run `./gradlew :check`.
2. Run `./gradlew ciCheck` for the full test and packaging suite, or confirm the CI **Build** job passes. The required **Test** status follows that job.
3. Run the relevant Plugin Verifier tasks for platform or descriptor changes.
4. Confirm the CI **Inspect Code** job passes.

The visual suite runs automatically for non-draft pull requests to `main`
without `skip-ci`, except for documentation-only changes. Official GitHub
Stacks run it on the top pull request. Review its CI result before merging.
Before a release, run the visual suite against the exact release candidate.
Follow the
[visual testing guide](docs/guide_visual_testing.md#choose-when-and-what-to-validate) for
execution and result recording.
