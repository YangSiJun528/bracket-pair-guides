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

After the runner finishes, create the descriptive comparison from this campaign's state:

```sh
python3 -B outputs/issue-97/current-performance/compare_current.py \
  --state outputs/issue-97/current-performance/performance-run-state.json \
  --output outputs/issue-97/current-performance/comparison.json
```

This command reads raw artifacts and writes a report; it starts no build or measurement. It verifies raw/log hashes, unique run IDs, paired fixture/JVM/power/input identities, expected scenario/sample counts, emitted read-body counts, and selected completion/geometry/failure flags. `complete_descriptive_comparison`, `all42CommandsMatched`, and `allInvariantGroupsComplete` describe those evidence checks. They are not performance acceptance, verification of every raw Boolean, or proof that every threading or algorithm defect is absent.

The report retains every recorded attempt in `attemptInventory`, lists missing/failed/invalid attempts under each workload, and includes per-JVM sample counts, Boolean outcomes, allocation trace completeness, cancellation results and release observations. Only the latest validated completed attempt for a command contributes to numbered paired comparisons. Inspect `unmatchedOrInvalid` before using any comparison. Raw files remain authoritative for individual sample traces; the report does not delete or rewrite them.

Use the per-JVM median and nearest-rank p95, their numbered paired changes, and the median of per-JVM summaries. Do not pool samples across JVMs or treat the summary of three p95 values as the p95 of a combined sample population. Repair's frozen reference and production calculation, tab/space variants, warmup and measured rows remain separate. Cancellation success-conditioned unwind values need the unsuccessful attempt counts alongside them. Preserve payload/release timeouts and surviving context observations even when both implementations show them.

Source revision/fingerprint provenance comes from the runner state and its frozen-source checks. The comparator validates original raw/log artifacts but does not independently re-read source manifests or Git source bytes; keep the per-file manifests, recorded final SHA, runner state and runner/init-script hashes with the report. Some fixture headers omit revision; the runner records the actual JVM revision property and exact source identity for those fixtures.

Assess timing and allocation changes separately from evidence integrity and correctness checks. Repeated latency increases remain regression signals requiring investigation; mixed directions or noisy intervals do not establish equivalence. Run the existing performance gates with their original thresholds and report their scope separately. This comparator introduces no threshold and grants no performance pass.


The coordination allowance preserves only the two exact pre-task Gradle identities. Worker PID 33413 still requires reported CPU 0.0%. Daemon PID 33144 may report up to 0.1% CPU, accounting for its observed idle housekeeping, only when its independently parsed last Gradle daemon-log transition says IDLE. The parser reads at most a 4 MiB tail from `/Users/sijun-yang/.gradle/daemon/9.8.0/daemon-33144.out.log`; snapshots record the validated timestamp/state, path, bounded-byte hash and file metadata, without exposing raw log content. Busy, missing, ambiguous, unreadable or changing log evidence rejects the allowance. All other workload/process checks remain active, and no preserved process is terminated.

These are before/after coordination observations. They do not prove continuous inactivity during a measurement interval, and the Gradle IDLE marker describes daemon build ownership rather than absence of background housekeeping. Earlier rejected empty preflight states and coordination observations remain preserved. This classification changes no benchmark threshold, timing fixture, sample count, comparison statistic or performance acceptance rule.
