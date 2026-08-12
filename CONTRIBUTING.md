# Contributing

Use this guide to choose a source area and run the repository's verification
boundaries.

## Prerequisites

- Run Gradle with JDK 21, matching CI.
- Use the committed `./gradlew` wrapper.
- Allow the Foojay resolver to provision the configured compiler toolchain when
  it is not installed locally.

Production bytecode targets Java 17. Kotlin source uses language and API version
1.9 because the minimum supported IntelliJ Platform bundles Kotlin 1.9.
Changing the Gradle runtime, JVM toolchain, Kotlin version, or minimum IDE build
is one compatibility change and must be reviewed together.

## Choose a source area

The repository has one deployable production module. Package boundaries inside
`plugin` preserve separate reasons to change without creating artifacts that
have no independent consumer.

| Change | Source area | Primary verification |
|---|---|---|
| Recognition, snapshot, indexes, or analysis values | `plugin/src/main/.../analysis` | `./gradlew :plugin:check` |
| Editor lifetime, highlighting, presentation, settings, or compatibility | Other `plugin/src/main` packages | `./gradlew :plugin:check` |
| Pairing or sorting measurement | `benchmarks/src/jmh` | `./gradlew :benchmarks:jmhJar` |
| Build coordination or CI | Repository root | `./gradlew :plugin:check :benchmarks:jmhJar` |

Keep one implementation of bracket semantics in production source. Editor and
settings code should consume analysis outcomes and queries instead of
reimplementing recognition or index behavior.

## Change an architecture boundary

Production code is grouped into four broad zones: IntelliJ host adapters, the
editor workbench, configuration state, and analysis policy. Dependencies point
inward in that order and may skip an intermediate zone. Packages inside a zone
may cooperate, but the complete production package graph must remain acyclic.

To move a responsibility or add a production package:

1. Identify the use case and the actor or policy that can require the change.
2. Prefer an existing package with that responsibility. Do not add an interface,
   package, or Gradle module only to make the diagram deeper.
3. Direct imports toward values and lower-level policy. IntelliJ entry points may
   compose analysis objects; neutral policy packages must not locate services or
   import outward editor packages.
4. Update the executable rule in
   [`ArchitectureTest.kt`](plugin/src/test/kotlin/com/sijunyang/bracketpairguides/architecture/ArchitectureTest.kt)
   only when a responsibility moves between the four zones. An internal package
   dependency that remains inward and cycle-free does not need a new allow-list
   entry.
5. Run the architecture tests and then the affected behavior tests.
6. Update the runtime DOT diagrams when an IntelliJ entry point, thread handoff,
   outcome path, or editor ownership boundary changes.

```shell
./gradlew :plugin:test \
  --tests 'com.sijunyang.bracketpairguides.architecture.ArchitectureTest'
./gradlew :plugin:check :benchmarks:jmhJar
```

ArchUnit imports compiled Kotlin and Java production classes. Its four-zone rule
checks the inward direction, its slice rule rejects cycles, and its neutral-policy
rule rejects IntelliJ dependencies in the packages named by that rule. A separate
dependency rule keeps editor event adapters unaware of analysis types. A
method-call rule also checks return descriptors, which ArchUnit's class dependency
set does not model for every Kotlin call shape. The test is the authoritative
boundary definition; documentation deliberately does not copy package-level
edges.

Regenerate the committed SVG diagrams after editing their DOT sources:

```shell
dot -Tsvg docs/diagrams/runtime_roles.dot \
  -o docs/diagrams/runtime_roles.svg
dot -Tsvg docs/diagrams/background_analysis_flow.dot \
  -o docs/diagrams/background_analysis_flow.svg
dot -Tsvg docs/diagrams/initial_render_sequence.dot \
  -o docs/diagrams/initial_render_sequence.svg
dot -Tsvg docs/diagrams/document_edit_sequence.dot \
  -o docs/diagrams/document_edit_sequence.svg
```

Keep each `.dot` source and generated `.svg` in the same change. The runtime
walkthrough explains diagram meaning; compiled ArchUnit rules remain the
authority for dependency constraints.

## Run tests

JUnit 4.13.2 runs the tests, while AssertJ 3.27.7 is the assertion API.
IntelliJ-bound tests also use the IntelliJ Platform test framework and bundled
Java and Kotlin test plugins. Keep JUnit annotations, runners, rules, and
`BasePlatformTestCase`; do not use `org.junit.Assert` or inherited JUnit
assertion helpers.

Run the production suite and compile the benchmark harness:

```shell
./gradlew :plugin:check :benchmarks:jmhJar
```

Run only the production suite:

```shell
./gradlew :plugin:check
```

Run one test class or method:

```shell
./gradlew :plugin:test \
  --tests '<fully-qualified-test>'

./gradlew :plugin:test \
  --tests '<fully-qualified-test>.<method-name>'
```

Test observable behavior through product inputs and outcomes. Reuse production
snapshot and policy objects from the same module when their internal visibility
is sufficient. Keep scenario setup and call recording under `plugin/src/test`.
Do not add a production getter, convenience overload, fake hierarchy, or
`@TestOnly` declaration solely to expose implementation state.

Prefer assertions that name the observable contract: collection contents and
order, numeric bounds, object identity, and exception type or message. Avoid
soft assertions and unrestricted recursive comparison; both can hide the first
broken invariant or couple a test to implementation fields.

Document-edit presentation tests must assert immediately after the write action,
before another daemon or highlighting pass runs. Cover insertion, replacement,
deletion, and equal-length space/tab replacement. A surviving tracked pair must
already have current endpoints and guide geometry; if bounded exact geometry is
unavailable, the guide must already be absent.

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

## Check lint and static analysis

Qodana is the repository's lint and static-analysis gate. The GitHub Actions
**Inspect Code** job runs the JVM Community linter and the
`qodana.recommended` profile from `qodana.yml`. Its `failThreshold` is zero.

Qodana is separate from Gradle `check`. A green local build does not imply a
green inspection job. Review Qodana findings on the pull request and reproduce
them with the corresponding IntelliJ inspection when possible.

There is no repository-wide auto-format gate. Use the IDE formatter for touched
code and avoid unrelated formatting changes.

## Before requesting review

1. Run `./gradlew :plugin:check :benchmarks:jmhJar`.
2. Run `./gradlew :plugin:buildPlugin` when production code or resources changed.
3. Run the relevant Plugin Verifier tasks for platform or descriptor changes.
4. Confirm the CI **Inspect Code** job passes.
