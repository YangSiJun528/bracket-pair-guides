# Measure calculation and editor contracts

Build first, then measure without concurrent Gradle, Driver, Qodana or verifier
work. Keep raw results, failed runs and the exact source/environment identity.

## Run JMH

```sh
./gradlew :benchmarks:jmhJar
./gradlew :benchmarks:jmh -PbenchmarkJob=analyzeCold
```

The five jobs are `analyzeCold`, `analyzeReuse`, `visibleQuery`, `repair` and
`cancelledAttempt`. Each has eight inputs: 64/4096 pairs and sibling/nested/sparse/
malformed distributions. They call public use cases through a benchmark-only
recorded-input adapter. Cold analysis includes a new calculator; reuse analysis
keeps one calculator. Query setup and result construction stay outside query
measurement. `runBlocking` bridge overhead is included in suspend use cases and
must be equal in baseline/candidate adapters.

Full defaults are two forks, two 1-second warmup iterations, three 1-second
measurement iterations, one thread, 2GiB fixed heap and the GC profiler. Preserve
ns/op and `gc.alloc.rate.norm` B/op secondary metrics. `-PbenchmarkSmoke=true`
checks executable setup only and cannot establish performance acceptance.

CI compiles before its per-job `timeout ... 240s java -jar ...` measurement.
The limit is unchanged. JMH JSON is the authoritative raw result; standard
Bencher JMH import is not assumed to import allocations automatically.

## Compare the redesign

Freeze `072533f` as the immediate baseline and the completed source as candidate.
Use the same workload meanings, inputs, result consumption, JDK/IDE, heap/GC,
forks/warmup and measurement machine. The former internal API need not survive;
put any compatibility adapter in a separate baseline comparison worktree only.
Do not retain old test code in the redesigned executable suite.

Run at least five independent fresh-JVM pairs with balanced AB/BA order. Report
pair-level ratios and all raw samples; iterations inside a JVM are not
independent JVM samples. Predeclare primary metrics, effect-size criteria and
any extra-pair policy before looking at candidate results. Preserve the existing
20% history alert and all hard resource budgets; this history threshold alone is
not a same-condition or tail-latency proof.

Measure actual IDE capture/read occupancy, writer wait, cancellation unwind,
full execution, hide-to-repair, native proof and payload lifetime separately.
Use the same actual SDK task/JBR and narrow runtime observation adapter in both
revisions. A configured dispatcher or unit-test duration cannot establish those
properties. Record actual operation counts and the p95 definition.

The old evidence contains 42 IntelliJ measurement commands (7 workloads × 3 JVM
pairs × 2 revisions), separately from 46 old JMH cases/7 jobs. Those are historical
anchors, not equivalent new measurements. Existing writer/repair/allocation and
XML-retention signals remain unresolved until specifically measured and fixed.

## Track history with Bencher

[The Bencher guide](guide_bencher.md) describes optional standard latency import.
A missing project/token, missing history or changed workload is unavailable
regression evidence, not a pass. No external upload is required for local
validation or authorized by these instructions.
