# Run the contract tests

Run commands from the repository root. Build, Driver, compatibility checks and
performance measurements share resources; run them serially.

## Run fast owner tests

```sh
./gradlew -p tools/pure-build :analysis-model:build :analysis-core:build
./gradlew spotlessCheck check
```

The first command uses Maven Central and the ordinary JVM build plugins only.
Its source and owner build scripts are shared; outputs and the project cache are
under `tools/pure-build`, separate from the SDK build.

`check` runs the owner tests, actual compiler-input policy, Gradle TestKit
functional tests, ArchUnit reference rules, minimum-IDE fixtures, JMH compilation
and release packaging policy. A missing or empty required suite is not a pass.
The build-logic TestKit suite copies this checkout into isolated temporary
projects and invokes its pinned Kotlin and Java compilers.

## Run real IDE contracts

```sh
./gradlew :plugin:minimumSdkTests
./gradlew :plugin:currentSdkTests
```

Classes ending `IdeContractTest` belong to their production module's `src/test`
but execute through the official plugin test sandbox. Ordinary owner `test`
tasks exclude those classes. The UI test compile classpath excludes core and
runtime implementations; collecting compiled test outputs in the real IDE
runner does not change compile access.

Inspect the XML test results and `ACTUAL_IDE`/`ACTUAL_JAVA` output. A configured
IDE version alone does not establish which platform executed the test.

## Verify the distribution

```sh
./gradlew :plugin:buildPlugin verifyPluginPackaging
./gradlew :plugin:verifyPluginProjectConfiguration :plugin:verifyPluginStructure :plugin:verifyPlugin
```

Keep the same final ZIP for compatibility, actual execution and visual checks.
Record its SHA-256. `build/module-verification/archive-identity.txt` records
owner archives and their actual input/dependency paths, including instrumentation
when present. The release must contain exactly the five owner archives and no
extra SDK, Kotlin, coroutine, test or bridge classes.

## Inspect code and visuals

Run the existing Qodana zero-problem gate. Do not add a baseline or exclude new
tests/build logic to hide a new finding. Follow [the visual guide](guide_visual_testing.md)
for Driver execution. Follow [the benchmark guide](../benchmarks/guide_benchmarking.md)
for performance evidence; test duration is not a responsiveness benchmark.
