# Independent current JMH assessment

Reviewer prepared before current performance execution. No historical values are reported as current, and no measurements or builds were run by this review.

After the current runner completes, run this read-only assessment:

```sh
python3 outputs/issue-97/current-jmh/review_results.py --run outputs/issue-97/current-jmh/full --candidate-revision FINAL_FULL_40_CHARACTER_SHA > outputs/issue-97/current-jmh/full/independent-review.json
```

Replace `full` with main's actual fresh run directory and `FINAL_FULL_40_CHARACTER_SHA` with the exact final measurement commit recorded in that run's `environment.json`. The reviewer requires a full SHA and exact equality; it never defaults to an earlier candidate. Exit zero means evidence integrity checks passed, not that every performance metric improved or that regression absence was proved. Review `latencyAbove20PercentCases`, positive interval-separated changes, missing/unbounded intervals, and all byte/op changes. Keep raw per-case values and intervals in the final report; do not pool nanoseconds and milliseconds or average percentage changes across dissimilar parameter cases.

The reviewer verifies the exact PR96 baseline revision, candidate commit, every recorded source SHA256 against that commit's Git blobs, the seven jobs, 46 unique matching full-profile cases per side, canonical coverage check success, actual JDK17 patch/executable, heap arguments, 2 forks/2 warmups/3 measurement iterations at one second each, finite latency and B/op, and the unchanged 240-second execution gate. The smoke is coverage evidence and excluded from comparison.

Reported interval subtraction `[candidateLower-baselineUpper, candidateUpper-baselineLower]` is a conservative range for direction discussion. An interval containing zero does not prove equivalence or absence of regression. A positive range warrants investigation even when below the existing Bencher 20 percent upper boundary. The 20 percent flag identifies the existing latency boundary for local review; this reviewer does not submit or execute Bencher's historical comparison. Allocation uses `gc.alloc.rate.norm` B/op, rather than MB/sec, whose value also changes with throughput. An absolute allocation delta remains meaningful when baseline B/op is near zero.

Provenance: the benchmark runner preserves hashed production source/build inputs and immutable JMH jars/argument bundles for each side. The final candidate measurement SHA has not yet been frozen. Main must supply its full SHA after final source changes; the reviewed preliminary `a0033ca` state is not measurement evidence. Beyond reused `a8e0bc1`, token-query range validation is one new production change; reassess the final commit diff before attributing current performance changes. Pairing, sorting, cancellation and preference benchmark source profiles remain unchanged. The source audit found core algorithms byte-identical apart from visibility and read-only query extraction. This supports comparability but cannot replace measurements.

True scope: the 46 cases exercise pure pairing, primitive sorting, cancellation checkpoints and preference normalization. They do not measure IntelliJ input capture/read-lock durations, coroutine execution/publication, native brace inspection, guide repair, indexed snapshot/token queries, editor rendering or end-to-end latency. The new query range check is outside these JMH cases. Fresh IDE timing/allocation fixtures, pure query tests, Driver tests and runtime checks remain independently required. One suite per revision with two JVM forks is not multiple independent suite evidence.
