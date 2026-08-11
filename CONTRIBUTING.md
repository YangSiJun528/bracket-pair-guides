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

The production package graph is a directed acyclic graph. Multiple packages may
depend on the same stable value or primitive policy, but a dependency must not
point back toward its caller.

To move a responsibility or add a production package:

1. Identify the use case and the actor or policy that can require the change.
2. Prefer an existing package with that responsibility. Do not add an interface,
   package, or Gradle module only to make the diagram deeper.
3. Direct imports toward values and lower-level policy. IntelliJ entry points may
   compose analysis objects; neutral policy packages must not locate services or
   import outward editor packages.
4. Update the executable rule in
   [`ArchitectureTest.java`](plugin/src/test/java/com/sijunyang/bracketpairguides/architecture/ArchitectureTest.java)
   only when the new direction is intentional. Add the narrow dependency the use
   case requires; do not permit a reverse edge merely to silence a failure.
5. Run the architecture tests and then the affected behavior tests.

```shell
./gradlew :plugin:test \
  --tests 'com.sijunyang.bracketpairguides.architecture.ArchitectureTest'
./gradlew :plugin:check :benchmarks:jmhJar
```

ArchUnit imports compiled Kotlin and Java production classes. Its layered rule
checks the permitted package direction, its slice rule rejects cycles, and its
neutral-policy rule rejects IntelliJ dependencies in the packages named by that
rule. A separate dependency rule keeps editor event adapters unaware of analysis
types. A method-call rule also checks return descriptors, which ArchUnit's class
dependency set does not model for every Kotlin call shape. The test is the
authoritative edge definition; documentation deliberately does not copy the
complete edge list.

## Run tests

The project uses JUnit 4.13.2. IntelliJ-bound tests also use the IntelliJ
Platform test framework and bundled Java and Kotlin test plugins.

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

## Review ABI changes

The plugin is not a library. Kotlin explicit API mode keeps cross-file
implementation explicit, while product types should remain `private` or
`internal`. `:plugin:check` verifies the committed ABI baseline.

Do not update the baseline merely to make a failing build green. First decide
whether the declaration can remain non-public. For an intentional public JVM
surface change, review the source and ABI diff, then run:

```shell
./gradlew :plugin:updateLegacyAbi
./gradlew :plugin:check
```

The baseline intentionally contains the Java pairing core. Java has no
module-wide `internal` visibility, so sibling production packages and the JMH
harness require those declarations to be JVM-public. They are implementation,
not a supported external plugin API. Kotlin product declarations should not be
added to the baseline without a concrete consumer and an explicit review.

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
4. Review any intentional ABI change.
5. Confirm the CI **Inspect Code** job passes.

For signing and publication, follow the
[release procedure](docs/how_to_release.md).
