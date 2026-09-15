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

The module depends on the compiled `plugin` project and invokes the production
pairing, cancellable sorting, and persisted-preference normalization
implementations. It neither copies those implementations nor registers
production source directories as benchmark roots, so a benchmark cannot drift
from the shipped classes or confuse IDE module ownership.

This is an intentional privileged implementation probe. The sort and preference
normalization helper remain Kotlin `internal`, but the Java JMH harness can call
their JVM methods from the benchmark-only module. This JVM visibility is not a
supported product API. `:benchmarks:jmhJar` in CI detects changes that break the
probe.

## Run a smoke benchmark

Use a short run to verify that JMH compiles and starts:

```shell
./gradlew :benchmarks:jmh -PbenchmarkSmoke=true
```

Smoke results are not suitable for making implementation decisions.

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
