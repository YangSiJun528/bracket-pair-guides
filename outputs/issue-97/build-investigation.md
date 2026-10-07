# Issue 97 build audit

Scope: read-only audit of PR96 head 84d1a431 and committed prior implementation a8e0bc1. No build, measurements, production edits, Docker start, or external upload executed. Existing dirty sibling work is preserved.

## Committed implementation assessment

Five production modules use java-library compile variants correctly: core api(model); UI api(model); runtime api(model), implementation(core), implementation(UI); plugin implementation(UI/runtime/model), with test-only core access. Actual compile dependencies are audited in gradle/production-modules.gradle, rather than trusting declarations. Production Java/Kotlin classpaths, actual sources, output roots, resolved projects, compiler arguments, effective friend paths and compiler plugin classpaths are exported. Source ownership/shared output checks and class inventories catch renamed foreign jar/shared-output leaks. Arbitrary visibility compiler arguments fail closed, allowing only diagnostics options. Root check includes model/core/UI/runtime/plugin checks, physical access probes, verification tooling unit tests, jmhJar, Spotless and release packaging.

Existing probe suite has 44 Kotlin/Java probes. UI tests all core/runtime entries for unavailable symbols, plugin tests core entries, model/core test IntelliJ unavailable; every module has a positive general control. Compiler diagnostics reject internal/nonpublic visibility failure to distinguish physical absence. Package audit requires five classic lib owner jars exactly once, no unknown third-party jar, exact current class-name inventory per owner, no IDE/Kotlin/coroutine/test/Driver classes, single plugin descriptor in plugin jar, minimum241, registered target class existence and required icons/licenses. It preserves standard packaging compatible with minimum IDE classloader rather than modular plugin descriptor layout.

Root settings pureBuild selects model/core only. No separate standalone settings file exists. It still applies org.jetbrains.intellij.platform.settings and configures platform repositories, but model/core modules apply Java/Kotlin only, use compileOnly stdlib and testImplementation stdlib, and no IntelliJ SDK dependencies. Thus the committed profile proves compilation without SDK bytecode and configuring host modules; it does not prove build configuration without IntelliJ Gradle tooling/plugin resolution. Clarify intended SDK-free claim or add standalone pure settings for stronger evidence. Build evidence should rerun tests, not count UP-TO-DATE logs as fresh execution.

## Concrete audit gaps to fix

1. Forbidden implementation symbols have no positive owner control. compile_probe only positively compiles model/kotlin.Unit, so deletion/rename of a target could produce negative pass without proving a real restricted symbol exists. Assert each target class exists in its intended owner outputs (Companion maps to JVM dollar class), and compile a Java owner-visible positive control. Java visibility bypass means owner Java control is appropriate even for Kotlin internal targets; do not require public Kotlin access to core internals.
2. Source-bearing classpath rejection handles directories only. javac default sourcepath also reads Java source entries from jars; classes(path) returns class entries only. Reject Java/Kotlin source entries in classpath jars or explicitly export/check actual Java sourcepath and demonstrate source-only jar contamination failure. Add an integration mutation with the real compile task, not only synthetic manifest unit tests.
3. Manifest completeness should fail closed: audit({}) currently accepts an empty module dictionary. Validate exact expected full/pure module set supplied independently from the manifest to avoid an accidentally omitted module skipping verification.
4. Packaging validates class names, but not byte equality between current compiled classes and ZIP owner jars; a stale archive with unchanged names passes audit. Compare class hashes/bytes, descriptor/icon/license content and duplicate resource counts where required, or clearly keep archive/source hash and build freshness provenance as a separate verified step. Existing historical archive hashes are not fresh validation of the new tree.

## CI and benchmark updates in committed implementation

Spotless roots cover all production modules. CI Build/Test still runs root check, now preceded by pureBuild checks. Existing verifier commands and eight strict failure levels stay unchanged. Test report upload currently targets plugin only; include model/core/UI/runtime report directories if failures should remain diagnosable. JMH directly depends core/UI as privileged probes and gate relocated measured paths are tested; preserve full 46-case, seven-job partition with two forks, two warmup and three measurement iterations, 1s and GC allocation profiling. Smoke is startup evidence only. Driver scripts preserve pinned Linux x64 IC2024.2.6/Starter242.26775.15, Java21, Xvfb96, environment key and exact screenshot oracle; release bridge stays test-only. Never record baselines for this structural migration.

## Safe local commands (main executor only; not executed here)

- ./gradlew -PpureBuild=true :analysis-model:build :analysis-core:build verifyProductionModules --rerun-tasks
- ./gradlew check
- python3 -B -m unittest discover -s benchmarks/bencher -p 'test_*.py'
- node --test .github/tests/visual-test-report.test.cjs
- ./gradlew :plugin:buildPlugin :plugin:verifyPluginProjectConfiguration :plugin:verifyPluginStructure :plugin:verifyPlugin
- ./scripts/visual-test-background.sh (Docker daemon required; pinned Linux comparison only)
- ./gradlew :benchmarks:jmh --rerun (full profile unchanged; baseline/candidate serial, idle host)

Preserve baseline84d1a431 source archive; use same JDK/SDK/heap/power for both sides. Seven opt-in IDE measurements: analysis, write-wait, editor execution, guide repair, native conflict, payload and capture release. Forward measurement properties to Test JVM using init script; ordinary test success does not execute default-disabled measurements. Three fresh JVMs per side/workload and warmups/samples from guide; keep raw JSONL/environment, per-JVM medians/p95 and GC-affected samples. No concurrent Gradle/Driver/Qodana/measurements. Analyze unresolved latency regressions honestly without weakening tests/thresholds.

## Current environment

Docker binary /usr/local/bin/docker exists but daemon unavailable: docker info failed due missing ~/.docker/run/docker.sock. Cached JBR21.0.10 arm64 and Temurin17.0.17+10; Gradle9.7.1/9.8.0. Cached macOS ARM IDE transforms IC2024.1.7/build241.19416.15, IC2024.2.6/build242.26775.15, IC2025.2.6.3/build252.28539.97. Verifier CLI1.410-all.jar cached; ~/.cache/pluginVerifier empty. Mac IDE cache is not Linux Driver evidence.

## Prior work / reusable runner review

Prior sibling worktree issue-97-physical-modules has committed a8e0bc1 plus dirty user refactors (including editor-workflow). Do not reset/reuse dirty checkout. Prior state describes completed validation with unresolved latency signals; historical passes cannot be relabeled as current. outputs/issue-97/verification-plan has serial-verifier.init.gradle, runtime-fixtures.init.gradle and run-final-serial-verifiers.py (untracked prior files). Serial init freezes current recommended+four explicit matrix, checks eight failure levels, selects one extracted verified local IDE at a time and writes distinct reports; no version weakening. Runtime init registers testIde with chosen product/version, bundled Java/Kotlin and independent framework; leaves Gradle-generated classpath/launcher intact. Final runner hashes archive each invocation, optionally cleans only newly created IDEs; hardcodes13 targets/411 fixture tests so update assertions to fresh current matrix/test inventory before reuse. Do not execute cleanup options while another build consumer runs. Historical Driver image logs show Docker cached ubuntu24/amd64 image existed during prior run, but current daemon availability is separate. Reuse prior Linux cache only as dependency cache through VISUAL_TEST_CACHE_DIR; never reuse outputs as fresh results.

## Implementation update

Fixed two compiler audit gaps in tools/verify_production_modules.py and its tests: every restricted core/runtime symbol must exist in its intended owner output and compile through an owner Java positive control (11 additional full-profile controls); pureBuild intentionally runs core controls only. Java/Kotlin source-only archives now fail the implicit-sourcepath audit. Added 5 tests including real javac reproduction of source-only jar bypass. Python unit suite: 18 tests passed, 0 skipped, in0.506s. No Gradle/probe integration execution here; main executor must execute fresh real-classpath verification. Prepared outputs/issue-97/current-contamination/run_checks.py with 10 genuine Gradle injections (original8 plus shared Java source roots and source-only archive). Default mode writes scripts/plan only; --execute runs failures serially and cleans UI outputs before final ordinary audit. No contamination measurement executed by this agent.

## Effective javac path update

Full integration audit found SDK app-client.jar contains codeVisionProviders/*/preview.java snippets. These declare Scope/Thing/Foo without the entry package/type and are resources rather than implicit Java declarations. Archive source detection now inspects Java source package/type against entry path; it does not whitelist SDK jars. Kotlin source resources in jars are not implicitly compiled by Kotlin. All production compileJava tasks now have explicit empty options.sourcepath, exported as specified+entries and reproduced in javac probes. Audit rejects nonempty effective typed sourcepath/bootstrapClasspath/annotationProcessorPath, and unspecified sourcepath. Added tests reproducing javac source-jar compilation and its failure with explicit empty sourcepath, distinguishing SDK templates, and rejecting typed paths. Support suite now20 tests passed,0 skipped in0.928s. Contamination runner now13 prepared real Gradle cases including three typed option overrides. No Gradle/contamination execution by this agent.

## SDK source companions

lib-client.jar embeds Rhino Java source alongside matching compiled class entries. Such companions are now allowed without SDK jar whitelisting: class inventory still audits compiled ownership, and mandatory explicit empty javac sourcepath prevents source recompilation. Source-only compilable Java entries remain rejected. Added regression covering same-class source acceptance with explicit empty sourcepath and rejection when sourcepath is unspecified or nonempty. Bootstrap contamination must fail for the recorded audit reason; an unrelated --release/bootstrap compiler incompatibility never counts as a pass. No Gradle run by this investigator.

Latest support verification: Python unittest suite21 passed,0 skipped in0.819s; no Gradle run.
