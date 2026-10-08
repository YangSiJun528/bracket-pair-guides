# Report JMH latency with Bencher

Keep JMH raw JSON and same-condition comparisons locally. Optional CI history
reporting uses the existing Bencher CLI, an explicitly configured project and
its API token. This guide does not authorize uploading a local run.

For an authorized reporting environment, import `results/jmh.json` with the
standard `java_jmh` adapter. Preserve `percentage`/upper boundary `0.20` and
`--error-on-alert`. New contract metrics need a meaningful baseline before an
absence of alerts can count as regression evidence.

The adapter's latency/throughput handling does not establish B/op coverage.
Inspect `gc.alloc.rate.norm` in JMH JSON; any BMF mapping must preserve metric
units and parameter identity and be separately tested. Do not normalize away
allocation increases.

References: [adapters](https://bencher.dev/docs/explanation/adapters/) and
[thresholds](https://bencher.dev/docs/explanation/thresholds/).
