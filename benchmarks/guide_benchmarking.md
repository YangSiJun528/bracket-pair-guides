# Run the performance benchmarks

Use this module to compare an optimized implementation with a simpler JDK or
library alternative without adding benchmark dependencies to the published
plugin.

## Prerequisites

- JDK 17
- An otherwise idle machine
- The same JDK, heap settings, and power mode for every comparison

The module compiles the production
`CancellableLongArraySort.kt` source directly. Do not copy that implementation
into the benchmark module because the copy can drift from the shipped code.

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

The complete run compares:

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

## Run one benchmark class

Pass a regular expression matching the benchmark class:

```shell
./gradlew :benchmarks:jmh \
  -PbenchmarkInclude='.*LongArraySortCancellationBenchmark'
```

## Interpret the results

Use `LongArraySortBenchmark` to compare completed-sort time. Its input clone is
performed in invocation setup and is not included in the measured operation.

Use `LongArraySortCancellationBenchmark` to compare the time until the call
returns after a cancellation request. The canceller and scheduling overhead are
present in both alternatives. Treat the result as a relative comparison, not as
an exact IDE input-latency measurement.

Keep a custom implementation only when repeated runs show a relevant benefit at
realistic input sizes or a material reduction in cancellation delay. Validate
the final choice in a running IDE with Java Flight Recorder because JMH does not
model the daemon read-action lifecycle or event-dispatch-thread contention.

## Add another implementation

1. Add the candidate dependency to `benchmarks/build.gradle.kts`, not the root
   plugin project.
2. Add a benchmark method using the existing input state.
3. Preserve identical setup, parameters, forks, and JVM options.
4. Run the baseline and candidate in the same JMH invocation.
