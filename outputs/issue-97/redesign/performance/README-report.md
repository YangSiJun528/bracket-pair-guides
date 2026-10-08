# Generate a local comparison evidence report

Status: formal report generation has not run. The main agent checked existing smoke-result schemas, nearest-rank percentile behavior and missing-value handling; evidence is retained in `reporter-schema-preflight.json`. That preflight is setup evidence, not a completed formal campaign or performance verdict. The subsequent clarification below changes metadata and wording only.

## Prerequisites

Use a complete `run_local_comparison.py` campaign with status `completed-execution-only-statistical-assessment-pending`. Keep its manifest, per-invocation `command.json`, raw JMH JSON or SDK JSONL, existing converted BMF, and copied SDK evidence together. Failed, timed-out, incomplete or selectively resumed campaigns are rejected. Retain failed predecessor campaigns separately in the final assessment.

## Generate one report per phase

Run from the redesign worktree, replacing the two paths with the completed campaign and a fresh report directory:

```sh
python3 outputs/issue-97/redesign/performance/report_local_comparison.py \
  outputs/issue-97/redesign/performance/COMPLETED-CAMPAIGN \
  --output outputs/issue-97/redesign/performance/FRESH-REPORT
```

Repeat for the other phase. The helper reads local evidence and writes `report.json` and `report.md`; it launches no Gradle, JVM, IDE, Docker, measurement or remote process. It refuses to overwrite a report directory. It does not replace `metrics.jq`, the Gradle BMF converter, JMH, or Bencher.

## Inspect the evidence

Check the report's source paths and SHA256 provenance against the retained campaign. Each phase must contain all six AB/BA comparison pairs in the frozen order: paired JMH invocations for pure computation, and paired fresh fixture JVMs for SDK workloads. JMH must contain all forty cases, with its existing BMF values matching the raw scores; raw fork observations, score error and confidence remain available in JSON. SDK repeated cells must have indices 0–29 without duplicates, cancellation trials 0–29, and resource cells exactly one replicate with index 0 per JVM. Native writer coverage must retain every attempt and show the fixture's required actual overlap.

For JMH, each case/run estimate is the JMH mean score from two forks × three measurement iterations. It is not an individual JVM median; the internal `median` slot holds that mean score and `estimateKind` identifies it explicitly. The independent comparison units are six paired invocations, and forks or measurement iterations are not additional independent pairs.

For SDK workloads, use each of the six paired fresh fixture JVMs’ 30-trial median and nearest-rank observed p95, then inspect all six candidate/baseline ratios and absolute deltas. With thirty fully observed trials the p95 rank is 29. The helper does not pool trials or forks across comparison pairs. Ratios are unavailable for a zero baseline or missing observation; inspect absolute differences instead. Every observed increase appears in the JSON increase list, including increases below 20%. Paired median ratios above 1.20 enter the investigation list. These are review flags, not a performance verdict.

Null and unsupported counters remain missing. Censored edit restorations, completion before cancellation, missed writer/read overlap and weak-reference GC deadline observations remain explicit. A percentile of only observed restorations cannot establish the population p95 when trials are censored. Read-phase body distributions are diagnostic and cannot be treated as independent JVM samples or automatically matched across architectures. Synchronous EDT, inherited coroutine, and read-body allocation scopes overlap and must never be summed.

Resource payload values cover reachable primitive-array bytes and reference slots, not complete retained heap. Capture diagnostic inspection wall time includes traversal and is not raw-capture throughput. Edit restoration is a headless actual-SDK observation upper bound with unknown worker origin; it does not establish real paint latency or exclusive repair duration. Cancellation's architecture-specific checkpoint comparison does not establish equal-work throughput.

The main agent must assess stability, all regressions, censored observations, failures and remaining limitations. The report always leaves `performancePass` unset. Local paired results do not substitute for historical Bencher bare-metal regression evidence.
