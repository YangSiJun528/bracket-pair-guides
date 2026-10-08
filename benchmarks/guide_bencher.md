# Report parameterized JMH metrics with Bencher

Keep raw JMH JSON and same-condition comparisons locally. The user approved local installation of the restored CI
workflow in `.github/workflows/benchmark-jobs.yml`. The reviewed proposal remains
in `outputs/issue-97/redesign/benchmark-workflow.proposal.yaml.txt`. This guide does not authorize a
project lookup, registry push, remote measurement or result upload.

Export complete production measurements without changing their values:

```shell
./gradlew exportBenchmarkMetrics \
  -PbenchmarkResults=results/all-jmh.json -PbenchmarkBmf=results/all-bmf.json
```

The Gradle adapter requires forty unique method/distribution/size combinations
(or eight for a selected job), the exact CalculationContractBenchmark owner,
JMH1.37/JVM17, two forks, one thread, two one-second warmups, three one-second
measurements, 2GiB initial/maximum heap, and two by three raw samples. Smoke
runs cannot satisfy this report contract. Average-time `ns/op` and normalized
GC allocation `B/op` map to `latency` and custom `allocation_bop`; parameter
identities and values survive conversion. The standard `java_jmh` adapter is
not assumed to import allocation or preserve these identities automatically.

The installed CI configuration preserves the existing distinction: configured trusted
same-repository PRs and regular pushes use the original Intel-v1 Bencher
bare-metal environment and require a history comparison; forks and
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
recorded as `comparative:false`. The old default branch lacks the new forty-case
adapter; selecting it currently fails before publishing or submitting. A reviewed
baseline adapter and explicitly authorized seed run are required before the
first regular comparison can pass. No baseline/history is manufactured from a
candidate result. Live Bencher report execution remains unverified locally.

References: [BMF custom measures](https://bencher.dev/docs/how-to/track-custom-benchmarks/),
[reports](https://bencher.dev/docs/api/projects/reports/),
[pinned measure definitions](https://github.com/bencherdev/bencher/blob/v0.6.12/lib/bencher_json/src/project/measure/built_in.rs),
[pinned report schema](https://github.com/bencherdev/bencher/blob/v0.6.12/lib/bencher_json/src/project/report.rs).
