# Current fixture performance campaign

Prepared but not executed. The runner compares exact PR #96 archive 84d1a43141b1933c81e027ef01d1579fc4679e6e with an explicitly supplied final candidate SHA. It runs seven workloads in serial: analysis, write-wait, editor execution, repair, native conflict, payload, capture-release. Each gets three fresh matched JVM pairs, with alternating side order. The first side also rotates across workloads. Forty-two Gradle commands run in total.

Timing suites use their existing 100 warmups and 30 measured samples. The repair fixture divides these per tab/space variant according to its existing harness semantics; payload and capture-release are single inventory/retention observations, not latency sample suites. Analysis includes 30 cancellation trials per corpus. No original measurement thresholds or correctness assertions are altered.

From the candidate repository root, after deterministic checks finish and the final implementation is committed:

```sh
python3 outputs/issue-97/current-performance/run_performance.py --candidate-head FULL_40_HEX_SHA
python3 outputs/issue-97/current-performance/run_performance.py --candidate-head FULL_40_HEX_SHA --execute
```

The first command validates the fixture property names and prints the 42 commands without starting a JVM. Execution refuses dirty tracked candidate sources, a different HEAD, changed measured source fingerprints, concurrent build/IDE/Driver/verifier/JMH processes, changed machine power settings or mismatched actual JVM settings. The runner verifies baseline source/build/fixture Git blob hashes against the frozen commit, saves per-file SHA-256 manifests, records raw fixture environment and the actual Gradle Test launcher/JVM arguments, and compares normalized JVM options across each workload. Normalization removes only fixture output/revision flags and checkout-specific path prefixes.

The fixture runner shares `outputs/issue-97/performance-run.lock` with any main JMH runner. Main should use the same lock. All retries use fresh immutable UUID attempt paths and retain failed artifacts; interrupted/failed attempts require deliberate `--retry-failed`. A partial selection can use repeated `--only WORKLOAD`. Completed raw artifacts are hash checked when resuming. Run-state JSON records every attempt and source/machine/JVM provenance. No prior benchmark outputs are reused as current evidence.

Expected elapsed time with warmed Gradle/IDE caches is approximately 16–22 minutes for 42 commands, based on prior per-command durations of 14–32 seconds. A fresh archive build/download can add setup time. Full JMH is separate and must run serially after/before this campaign.

Results are evidence, not a no-regression assertion. Previous implementation measurements showed repeated unresolved writer/native latency increases; preserve all current raw results and compare per-JVM summaries instead of pooling samples across JVMs.
