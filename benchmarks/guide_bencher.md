# Report parameterized JMH metrics with Bencher

Keep raw JMH JSON and same-condition comparisons locally. The workflow in
`.github/workflows/benchmark-jobs.yml` runs the configured CI comparisons. This
guide does not itself authorize a registry push, remote measurement or result upload.

Export complete production measurements without changing their values:

```shell
./gradlew exportBenchmarkMetrics \
  -PbenchmarkResults=results/all-jmh.json -PbenchmarkBmf=results/all-bmf.json
```

The Gradle adapter requires forty unique method/distribution/size combinations
(or eight for a selected job), the exact CalculationContractBenchmark owner,
JMH1.37/JVM17, two forks, one thread, two one-second warmups, three one-second
measurements, the exact JVM arguments in `runner/jvm.args`, and two by three raw samples. Smoke
runs cannot satisfy this report contract. Average-time `ns/op` and normalized
GC allocation `B/op` map to `latency` and custom `allocation_bop`; parameter
identities and values survive conversion. The standard `java_jmh` adapter is
not assumed to import allocation or preserve these identities automatically.

The installed CI configuration preserves the existing distinction: configured trusted
same-repository PRs and regular pushes use Intel-v1 Bencher bare metal with the
`bencher-intel-v1-jdk17-input-v2` measurement contract and require a history comparison; forks and
unconfigured repositories establish execution and forty-case coverage only.
Both measures retain percentage upper boundary 0.20 and latest sample size 1.
The 240-second measurement budget excludes compilation and conversion.

The local runner checks the actual terminal job UUID, exit status and original
raw artifacts. It resolves measure resource slugs to UUIDs because Bencher's
built-in display name is `Latency`. Each expanded server report must match its
original report/job/project/branch/testbed, eight submitted benchmark identities,
sixteen measure UUIDs and metric values. Every metric needs a computed finite
baseline and the strict threshold boundary. Empty alerts with absent history
fail; all partition jobs finish before reporting genuine regression alerts.

Explicit default-branch seeding is a separate, noncomparative action and is
recorded as `comparative:false`. After reviewing and authorizing a measurement
contract migration, run `Benchmark Jobs` on the reviewed ref with
`seed_main_baseline=true`. This builds the actual current default-branch source,
records its commit hash, and uses the selected workflow ref's JVM settings.
Only after all five seed jobs finish, run the PR comparison against that main
hash. No baseline is manufactured from a candidate result; an ordinary PR run
with missing history must fail. Keep the original testbed and results.

The input-v2 migration preserves the workload JAR and changes only the recorded
prefix inlining boundary. See [the diagnosis and contract](explanation_input_boundary.md).

References: [BMF custom measures](https://bencher.dev/docs/how-to/track-custom-benchmarks/),
[reports](https://bencher.dev/docs/api/projects/reports/),
[pinned measure definitions](https://github.com/bencherdev/bencher/blob/v0.6.12/lib/bencher_json/src/project/measure/built_in.rs),
[pinned report schema](https://github.com/bencherdev/bencher/blob/v0.6.12/lib/bencher_json/src/project/report.rs).
