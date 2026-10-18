# Run the contract tests

Run commands from the repository root. Build, Driver, compatibility checks and
performance measurements share resources; run them serially.

## Run local checks

```sh
./gradlew :check
```

This runs Spotless, ordinary owner tests, and ArchUnit package rules. It does not
launch IDE fixtures, copy the repository into test builds, build the distribution
ZIP, or compile benchmarks. The pre-push hook uses this same command. The leading
colon selects the root aggregate without
selecting the benchmark module's own `check` task.

To test the two platform-free modules without configuring IntelliJ:

```sh
./gradlew -p tools/pure-build check
```

The independent build uses Maven Central and ordinary JVM plugins. Sources and
owner build scripts are shared; outputs and the project cache stay under
`tools/pure-build`.

## Run the CI suite

```sh
./gradlew ciCheck
```

The CI **Build** job runs this command after compiling the SDK-free modules with
`./gradlew -p tools/pure-build :analysis-model:assemble :analysis-core:assemble`.
Their unit tests run once, in `ciCheck`. It includes `check`,
minimum/current IDE fixtures, existing benchmark-report tests, JMH compilation,
the distribution ZIP, and the official JetBrains `verifyPluginProjectConfiguration`
and `verifyPluginStructure` tasks. The required **Test** status follows the same
job's success without rebuilding. Benchmark execution remains separate.

## Run real IDE contracts

```sh
./gradlew :plugin:minimumSdkTests
./gradlew :plugin:currentSdkTests
```

Classes ending `IdeContractTest` belong to their production module's `src/test`
but execute through the official plugin test sandbox. Ordinary owner `test`
tasks exclude those classes. The UI test compile classpath excludes core and
runtime implementations; collecting compiled test outputs in the real IDE
runner does not change compile access. The CI `ciCheck` task includes both IDE
versions. Local execution evidence must still show the actual selected platform
and executed cases. The session contract also supplies an EDT parent dispatcher
and verifies that reads still run in the
background while publication runs on EDT.

Inspect the XML test results and `ACTUAL_IDE`/`ACTUAL_JAVA` output. A configured
IDE version alone does not establish which platform executed the test.

## Verify the distribution

```sh
./gradlew :plugin:buildPlugin
./gradlew :plugin:verifyPluginProjectConfiguration :plugin:verifyPluginStructure :plugin:verifyPlugin
```

Keep the same final ZIP for compatibility, actual execution and visual checks.
Record its SHA-256 with `shasum -a 256 plugin/build/distributions/*.zip`.
The official [JetBrains verification tasks](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#verifypluginstructure)
check project configuration, plugin descriptors, archive structure, and IDE
compatibility. The previous project-specific jar-count and byte-identity audit
has been removed; these are not additional requirements on the official tasks.

## Inspect code and visuals

Run the existing Qodana zero-problem gate. Do not add a baseline or exclude new
tests/build logic to hide a new finding. Follow [the visual guide](guide_visual_testing.md)
for Driver execution. Follow [the benchmark guide](../benchmarks/guide_benchmarking.md)
for performance evidence; test duration is not a responsiveness benchmark.
