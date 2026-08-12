# Run the performance benchmarks

Use this module to compare an optimized implementation with a simpler JDK or
library alternative without adding benchmark dependencies to the published
plugin.

For the separate whole-IDE black-box comparison against Rainbow Brackets and
Color Brackets, see [Record an IDE plugin comparison](guide_ide_comparison.md).
That workflow measures the complete IDE process and must not be interpreted as
an isolated JMH component benchmark.

Benchmark parameters are measurement inputs, not product limits. See the
[performance and capacity reference](../docs/reference_performance_limits.md)
for the current production boundaries and memory rationale.

## Prerequisites

- JDK 17
- An otherwise idle machine
- The same JDK, heap settings, and power mode for every comparison

The module depends on the compiled `plugin` project and invokes the production
`PairingMachine` and `CancellableLongArraySort.kt` implementations. It neither
copies those implementations nor registers production source directories as
benchmark roots, so a benchmark cannot drift from the shipped classes or
confuse IDE module ownership.

This is an intentional privileged implementation probe. The sort remains Kotlin
`internal`, but the Java JMH harness can call its JVM method from the
benchmark-only module. This JVM visibility is not a supported product API.
`:benchmarks:jmhJar` in CI detects changes that break the probe.

## Run a smoke benchmark

Use a short run to verify that JMH compiles and starts:

```shell
./gradlew :benchmarks:jmh -PbenchmarkSmoke=true
```

Smoke results are not suitable for making implementation decisions.

## Run the complete benchmark

```shell
./gradlew :benchmarks:jmh
```

The complete run covers:

- platform-neutral fully nested and sequential-token pairing with primitive
  `PairTable` construction;
- JDK `Arrays.sort(long[])` with the production cancellable sort;
- realistic encoded pair events, random input, and ordered inputs;
- 32,768 through 2,000,000 endpoints, with two endpoints per bracket pair;
- normal completion and a cancellation request issued after 1 ms.

JMH writes readable output to `benchmarks/build/reports/jmh/human.txt` and
machine-readable results to `benchmarks/build/reports/jmh/results.json`.
The enabled GC profiler also reports allocation rate and allocated bytes per
operation. Invocation setup clones the same input for both alternatives, so
compare the alternatives rather than treating either allocation value as the
sort's isolated payload.

The release-facing sequential pairing baseline is sanitized under
[`benchmarks/results/`](results/) with absolute paths removed and the measured
source and JMH artifact hashes recorded. Update that snapshot only after a full
configured run, not a smoke benchmark.

## Run one benchmark class

Pass a regular expression matching the benchmark class:

```shell
./gradlew :benchmarks:jmh \
  -PbenchmarkInclude='.*LongArraySortCancellationBenchmark'
```

Use `.*PairingMachineBenchmark` to isolate the pairing state machine.

## Interpret the results

Use `LongArraySortBenchmark` to compare completed-sort time. Its input clone is
performed in invocation setup and is not included in the measured operation.

Use `LongArraySortCancellationBenchmark` to compare the time until the call
returns after a cancellation request. The canceller and scheduling overhead are
present in both alternatives. Treat the result as a relative comparison, not as
an exact IDE input-latency measurement.

For `PairingMachineBenchmark`, the sequential 100,000-pair case stays within
both production pairing limits. Fully nested inputs above 50,000 pairs exceed
the production pending-opener limit and are isolated core scalability probes;
the 200,000-pair cases also exceed the completed-pair limit. The GC profiler's
bytes per operation are temporary allocations made during analysis, not the
retained size of the resulting pair and query indexes.

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

## Reproduce the retained-graph probe

The 0.0.1 retained-model measurement is a separate JOL probe, not a JMH
benchmark. Its harness is kept in the benchmark-only `retainedGraphProbe` source
set at `benchmarks/probes/RetainedGraphProbe.java`; it is not part of the
published plugin or the normal verification task graph.

Run it from the repository root. Gradle resolves the pinned JOL and Kotlin
runtime dependencies and uses the configured JDK 17 toolchain:

```shell
./gradlew :benchmarks:retainedGraphProbe
```

The published Apple M1 Pro/JDK 17.0.17 run reported:

```text
Bracket Pair Guides  14 objects/arrays       6513896 bytes
```

JOL may warn that it cannot attach an instrumentation or serviceability agent.
The published values are therefore described as graph-layout estimates. Keep
the fixed JVM reference-compression and alignment options and JOL version when
comparing a future release. This probe measures a Bracket Pair Guides internal
model; it is not a total-heap or third-party-plugin comparison.
