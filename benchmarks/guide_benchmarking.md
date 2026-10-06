# Run the performance benchmarks

Use this module to compare an optimized implementation with a simpler JDK or
library alternative without adding benchmark dependencies to the published
plugin.

Benchmark parameters are measurement inputs, not product limits. See the
[performance and capacity reference](../docs/reference_performance_limits.md)
for the current production boundaries and memory rationale.

## Prerequisites

- JDK 17
- An otherwise idle machine
- The same JDK, heap settings, and power mode for every comparison

The benchmark module depends directly on the compiled `analysis-model`,
`analysis-core`, and `editor-ui` projects. It invokes core's production pairing
and cancellable sorting and UI's persisted-preference normalization. It neither
copies those implementations nor registers production source directories as
benchmark roots, so each benchmark uses the shipped class from its physical
owner.

Production sources have five owners: platform-free values and read-only result
contracts in `analysis-model`; computation in `analysis-core`; sessions,
presentation, settings, and request interfaces in `editor-ui`; IntelliJ capture,
execution, cancellation, validation, and publication in `analysis-runtime`; and
descriptor registration and packaging in `plugin`. Core and UI compile against
model; runtime also compiles against core and UI; plugin compiles against model,
UI, and runtime. UI has no core or runtime compile access, and plugin has no core
compile access. See [Analysis execution](../docs/explanation_analysis_execution.md)
for the result and host seams.

This is an intentional privileged implementation probe. The sort and preference
normalization helper remain Kotlin `internal`, but the Java JMH harness can call
their JVM methods from the benchmark-only module. This JVM visibility is not a
supported product API. `:benchmarks:jmhJar` in CI detects changes that break the
probe.

## Verify production module visibility

Build the platform-free modules and audit their physical ownership and compiler
visibility without configuring the IntelliJ host modules:

```shell
./gradlew -PpureBuild=true :analysis-model:build :analysis-core:build verifyProductionModules
```

Run the audit with all five production modules included when checking the UI and
plugin compile barriers:

```shell
./gradlew verifyProductionModules
```

The audit reads actual Java and Kotlin compile classpaths, source ownership,
outputs, and compiler options, and compiles negative Java and Kotlin visibility
probes. A successful audit establishes the checked module visibility; it does
not establish algorithm correctness or thread-lifecycle correctness.

Plugin fixture tests retain a test-only direct core dependency and Kotlin friend
paths so they can compose host and implementation seams. Production compilation
receives neither privilege. Benchmarks are separate privileged probes with the
direct dependencies described above.

## Run a smoke benchmark

Use a short run to verify that JMH compiles and starts:

```shell
./gradlew :benchmarks:jmh -PbenchmarkSmoke=true
```

The smoke run covers the whole suite, or the selected job/class when a filter
is supplied. Smoke results are not suitable for making implementation decisions.

## Run the complete benchmark

```shell
./gradlew :benchmarks:jmh --rerun
```

Use the Gradle task option `--rerun` for each fresh measurement, including repeat
runs, to prevent reuse of `UP-TO-DATE` benchmark results.

The complete run covers:

- platform-neutral fully nested and sequential-token pairing with primitive
  `PairTable` construction;
- JDK `Arrays.sort(long[])` with the production cancellable sort;
- synthetic fully nested pair events, random input, and ordered inputs;
- 32,768 through 2,000,000 endpoints, with two endpoints per bracket pair;
- normal completion and a cancellation request issued after 1 ms;
- full persisted-preference normalization and reuse of the identical immutable
  snapshot passed by caret-time native-setting reconciliation.

JMH writes readable output to `benchmarks/build/reports/jmh/human.txt` and
machine-readable results to `benchmarks/build/reports/jmh/results.json`.
The enabled GC profiler also reports allocation rate and allocated bytes per
operation. Invocation setup clones the same input for both alternatives, so
compare the alternatives rather than treating either allocation value as the
sort's isolated payload.

## Run one benchmark class

Pass a regular expression matching the benchmark class:

```shell
./gradlew :benchmarks:jmh --rerun \
  -PbenchmarkInclude='.*LongArraySortCancellationBenchmark'
```

Use `.*PairingMachineBenchmark` to isolate the pairing state machine or
`.*PreferenceNormalizationBenchmark` to isolate settings normalization.

## Run one bounded benchmark job

Select a stable job name instead of writing a benchmark regular expression:

```shell
./gradlew :benchmarks:jmh --rerun -PbenchmarkJob=sort-random
```

| Job | Workload | Cases | Nominal measurement time |
|---|---|---:|---:|
| `sort-pair-events` | Both sorts, fully nested pair events, all sizes | 8 | 80 s |
| `sort-random` | Both sorts, random input, all sizes | 8 | 80 s |
| `sort-ascending` | Both sorts, ascending input, all sizes | 8 | 80 s |
| `sort-descending` | Both sorts, descending input, all sizes | 8 | 80 s |
| `cancellation` | Both cancellation methods, all sizes | 6 | 60 s |
| `pairing` | Nested and sequential pairing, all pair counts | 6 | 60 s |
| `preferences` | Full normalization and persisted-snapshot reuse | 2 | 20 s |

The jobs partition all 46 cases without changing their input values, two forks,
two one-second warmup iterations, or three one-second measurement iterations.
Nominal times exclude JVM startup, setup, GC, and teardown. Actual time depends
on the runner. Each job keeps both implementations in the same invocation where
there is a baseline/candidate comparison.

Results go to `benchmarks/build/reports/jmh/<job>/` so jobs do not overwrite one
another. `benchmarkJob` and `benchmarkInclude` are mutually exclusive; invalid
job names fail the build. Add `-PbenchmarkSmoke=true` for a short selected-job
check. List available jobs as JSON with:

```shell
./gradlew -q :benchmarks:listBenchmarkJobs
```

## Prepare a job for an offline runner

Build and package before submitting a time-limited measurement job:

```shell
./gradlew :benchmarks:prepareBenchmarkJob -PbenchmarkJob=sort-random
cd benchmarks/build/benchmark-jobs/sort-random
java @run.args
```

The bundle contains `benchmarks.jar` and `run.args`. Copy both files to the same
directory on a runner with JDK 17 and execute the last command there. It needs
neither Gradle nor dependency downloads. `human.txt` and `results.json` are
written in that directory. Run jobs sequentially on a shared machine to avoid
measurement interference.

The argument file is generated from the same JMH configuration used by Gradle,
including the selected job's parameter filter. It uses relative paths so the
bundle can move between build and measurement machines. If preparing a smoke
bundle, pass `-PbenchmarkSmoke=true`; prepare it again without that property
before taking performance measurements.

The `Benchmark Jobs` GitHub workflow builds all bundles once and checks that
their combined results cover the unfiltered smoke suite exactly once. With a
configured public Bencher project, it runs the seven jobs sequentially on
Bencher Bare Metal with performance regression checks. Unconfigured runs, fork
PRs, and Dependabot PRs use separate GitHub runners for packaging, coverage, and
duration validation. Both paths keep a four-minute Java execution limit. Follow
[Enable Bencher performance checks](guide_bencher.md) to connect the Free plan,
establish a main-branch baseline, and validate the image locally.

## Interpret the results

Use `LongArraySortBenchmark` to compare completed-sort time. Its input clone is
performed in invocation setup and is not included in the measured operation.

The `pair-events` generator encodes fully nested pairs and also supplies the
cancellation benchmark. At 200,000 endpoints, its 100,000 pairs meet the
completed-pair limit but exceed the 50,000 pending-opener limit. Use these results
to compare primitive-array sorting; they do not measure full analysis of a file
accepted by the production limits.

Use `LongArraySortCancellationBenchmark` to compare the full benchmark-call
time, including the 1 ms delay before cancellation and canceller/scheduling
overhead. It does not isolate request-to-return latency or measure IDE input
latency.

For `PairingMachineBenchmark`, the sequential 100,000-pair case stays within
both production pairing limits. Fully nested inputs above 50,000 pairs exceed
the production pending-opener limit and are isolated core scalability probes;
the 200,000-pair cases also exceed the completed-pair limit. The GC profiler's
bytes per operation are temporary allocations made during analysis, not the
retained size of the resulting pair and query indexes.

For `PreferenceNormalizationBenchmark`, `reusePersistedSnapshot` isolates the
preference-normalization portion of the steady-state caret path. It should return
the persisted object without rebuilding the default language set or four color
lists. `normalizeDefaultSnapshot` keeps the full default storage boundary visible
as a comparison workload; it is not the caret-path target.

Keep a custom implementation only when repeated runs show a relevant benefit at
realistic input sizes or a material reduction in cancellation delay. Validate
the final choice in a running IDE with Java Flight Recorder because JMH does not
model the daemon read-action lifecycle or event-dispatch-thread contention.

## Add another implementation

1. Add the candidate dependency to `benchmarks/build.gradle.kts`, not the
   production `plugin` module.
2. Add a benchmark method using the existing input state.
3. Preserve identical setup, parameters, forks, and JVM options.
4. Run the baseline and candidate in the same JMH invocation.
5. Assign new cases to a job in `benchmarks/build.gradle.kts` and keep its
   measured duration below the four-minute CI limit. The coverage check fails
   if any case is omitted or appears in more than one job.

## Measure the IDE execution paths

The plugin test suite also contains opt-in IDE fixture measurements. These
exercise platform capture and editor execution, which JMH does not model.
Their gates are false by default: an ordinary CI test pass is not measurement
evidence, and no machine timing threshold is added to the ordinary suite.
Opt-in runs retain correctness checks and write raw JSONL with explicit scopes.

Pass measurement properties to the **test JVM**. Gradle `-D` options alone do
not automatically reach its forked test process. From the repository root,
create this temporary local init script; it forwards only the measurement
property namespace and changes no repository build file or CI threshold:

```shell
measurement_init=$(mktemp)
cat > "$measurement_init" <<'GROOVY'
gradle.projectsEvaluated {
    def properties = System.properties.findAll { key, value ->
        key == 'issue93.measure' || key.toString().startsWith('issue93.measure.')
    }
    gradle.rootProject.project(':plugin').tasks.withType(
        org.gradle.api.tasks.testing.Test
    ).configureEach {
        systemProperties(properties)
    }
}
GROOVY
measurement_output=$(mktemp -d "${TMPDIR:-/tmp}/issue93-measurement.XXXXXX")
```

For a warmed analysis comparison, run the two implementations serially in three
fresh test JVMs each, retaining separate output files:

```shell
for repetition in 1 2 3; do
  for implementation in legacy incremental; do
    ./gradlew --no-configuration-cache --init-script "$measurement_init" \
      :plugin:test --rerun --tests '*.AnalysisBaselineMeasurementTest' \
      -Dissue93.measure=true \
      -Dissue93.measure.implementation="$implementation" \
      -Dissue93.measure.warmups=100 -Dissue93.measure.repeats=30 \
      -Dissue93.measure.cancelTrials=30 \
      -Dissue93.measure.output="$measurement_output/analysis-$implementation-$repetition.jsonl"
  done
done
```

Keep the IDE/JDK, corpus hashes, heap, power mode, and other workloads matched.
`--rerun` forces fresh test execution. Output is appended, so use fresh paths.
For actual read-body/write contention, select
`*.AnalysisWriteWaitMeasurementTest` instead, set
`issue93.measure.writeWait.repeats=30`, and provide a distinct
`issue93.measure.writeWait.output`. Run each workload separately on an idle
machine; do not overlap Gradle, Driver, Qodana, or other measurements.

| Fixture class | Gate to enable | Output-path property |
|---|---|---|
| `AnalysisBaselineMeasurementTest` | `issue93.measure=true` | `issue93.measure.output` |
| `AnalysisWriteWaitMeasurementTest` | `issue93.measure=true` | `issue93.measure.writeWait.output` |
| `EditorExecutionMeasurementTest` | `issue93.measure.execution=true` | `issue93.measure.execution.output` |
| `GuideRepairMeasurementTest` | `issue93.measure.guideRepair=true` | `issue93.measure.guideRepair.output` |
| `NativeConflictMeasurementTest` | `issue93.measure.native=true` | `issue93.measure.native.output` |
| `AnalysisPayloadMeasurementTest` | `issue93.measure.payload=true` | `issue93.measure.payload.output` |
| `CaptureReleaseMeasurementTest` | `issue93.measure.captureRelease=true` | `issue93.measure.captureRelease.output` |

Select one class with `--tests`, enable its gate, and set a fresh output path
through the same init script. Other harness-specific settings and exclusions
are recorded in each JSONL environment row; inspect that row and confirm raw
samples exist before interpreting a successful test run.

The current `legacy` control shares current production classifier, pairing,
calculation, and index dependencies. It is not the frozen M0 production baseline
from an earlier revision. Preserve M0 source/revision and raw results separately;
do not relabel a current-control comparison as a before/after M0 comparison.
Use per-JVM medians and the nearest-rank p95: sort `n` samples and select the
`ceil(0.95*n)`th value, so 30 samples use the 29th sorted value (index 28).
Keep GC-affected samples visible and avoid pooling JVMs to hide run variation.

Analysis wall time is not read-lock hold time. The read/write harness records
actual read-body enter/exit separately from EDT write request-to-acquisition;
body intervals exclude read-lock acquisition/release. Editor execution records
observed acceptance latency, including event-pump cadence and secondary debounce.
Direct coroutine allocation sums execution segments across thread hops, including
trace bookkeeping but excluding waiting, EDT, and independent platform work.
Repair records its UI, worker, and acceptance phases separately. Retained-index
primitive payload, consumed capture weak-reference release, and whole-JVM
sampled heap estimates answer different memory questions; none is a complete
per-analysis heap bound. A JFR pause overlap supports interference in that
recorded run; no overlap does not prove scheduling was the cause.
