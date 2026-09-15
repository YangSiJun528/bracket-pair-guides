# 0.0.5 release QA — 2026-09-15

**Both visual environments and build CI passed. All 46 performance cases
completed, with one timing increase reproduced in a targeted recheck.**
Sequential pairing of 100,000 pairs remained 11.3% slower than the earlier
same-day measurement. Its cause is unresolved; this report does not establish
the absence of performance regressions or measure complete IDE input latency.

Tested commit: `d09210cee640c308a97e42b7eae601434a501e51`, after
[PR #63](https://github.com/YangSiJun528/bracket-pair-guides/pull/63) merged.
The performance comparison uses the earlier same-day results from `3e31ea7`.
Production and benchmark source are identical between those commits.

## Automated verification

| Check | Result |
|---|---|
| macOS visual suite | 11/11 scenarios exactly matched; 7/7 JUnit tests passed in a 58-second Gradle run |
| Linux visual suite | 11/11 scenarios exactly matched; 7/7 JUnit tests passed in a 3-minute 7-second Gradle run |
| Build CI | Build, Test, Inspect Code, Verify Plugin, and Qodana for JVM succeeded |
| Full JMH run | 46/46 cases completed in 8 minutes, exit 0; no missing cases or non-finite time/allocation scores |
| Targeted JMH recheck | Sequential 100,000-pair case completed in 6 fresh JVM forks, 18 measurements, exit 0 |

Each visual suite contains one Driver test covering all 11 scenarios and six
crop/comparison helper tests. There were no failures, errors, or skipped tests.
The runs used IntelliJ IDEA Community 2024.2.6, New UI, Darcula, and scale 1:
macOS arm64 locally and Ubuntu 24.04 x64 with Xvfb at 96 DPI in GitHub Actions.

- [Linux visual run](https://github.com/YangSiJun528/bracket-pair-guides/actions/runs/34943747587)
- [Build, test, inspection, and compatibility run](https://github.com/YangSiJun528/bracket-pair-guides/actions/runs/34943693567)

These automated results supersede the automated status in the
[September 14 QA record](https://github.com/YangSiJun528/bracket-pair-guides/blob/d09210cee640c308a97e42b7eae601434a501e51/outputs/qa-review-20260914/summary.md),
which remains a historical record of its own commit and environment.

## Visual comparison boundary

The earlier mismatch was confined to the top row of the editor capture, where
the tab boundary changed with focus. The test now compares the fixed rectangle
`(x=0, y=1, width=220, height=239)`. The 22 existing OS-specific baselines were
cropped to that rectangle without changing any retained pixel. Comparison
inside the rectangle remains exact, and full editor captures are retained as
diagnostics.

All 22 new actual images were independently decoded and compared with their
committed OS baselines; every retained pixel matched. The SHA-256 digests of
all 12 Linux artifacts, comprising 11 images and one diagnostics archive, were
also verified. The raw diagnostic images were not used as comparison baselines.

## Performance measurements

Host: Apple M1 Pro, 32 GiB RAM, macOS 26.5.2 arm64, AC power, low-power mode off.
User applications were running, so the host was not an otherwise idle,
controlled benchmark environment.

Both full runs used JMH 1.37 and JDK 17.0.17 with identical JVM options, one
thread, two forks, two 1-second warmup iterations and three 1-second measurement
iterations per fork, and the GC profiler. The targeted recheck kept those
settings and increased the fork count to six. The complete suite contains 32
sorting, six cancellation, six pairing, and two preference-normalization cases.

| Operation | Earlier full run | Post-merge full run |
|---|---:|---:|
| Production sort, pair-events, 200,000 endpoints | 3.294 ms/op | 3.334 ms/op |
| Production sort, random, 200,000 endpoints | 10.545 ms/op | 10.640 ms/op |
| Production cancellation call, 200,000 endpoints, 1 ms request delay | 1.408 ms/op | 1.392 ms/op |
| Sequential pairing, 100,000 pairs | 8.075 ms/op | 9.269 ms/op |
| Persisted preference snapshot reuse | 0.634 ns/op | 0.638 ns/op |

The pair-events sort and cancellation inputs are fully nested synthetic data.
Their 200,000 endpoints correspond to the completed-pair count limit, but their
100,000 nested openers exceed the 50,000 pending-opener limit. They measure
sorting primitives at that array length, not processing an accepted file of
that shape. Sequential pairing at 100,000 pairs is within both pairing limits.

### Sequential 100,000-pair recheck

The full-run increase was 14.8%. All six post-merge samples were slower than
all six earlier samples, so this was not dismissed as a single outlier.

| Measurement | Earlier full run | Post-merge full run | Targeted recheck |
|---|---:|---:|---:|
| Mean, ms/op | 8.074665 | 9.268627 | 8.990079 |
| JMH confidence interval, ms/op | 7.976474–8.172856 | 8.439859–10.097395 | 8.556031–9.424126 |
| Forks / measured samples | 2 / 6 | 2 / 6 | 6 / 18 |
| Allocation, B/op | 19,544,208 | 19,544,215 | 19,544,213 |

The recheck mean was **11.3% above the earlier result**. Its six fork means
were 8.389, 9.377, 8.371, 9.135, 9.156, and 9.512 ms/op. Individual forks were
largely stable after their second warmup, without a sustained downward trend
that would require extending warmup to interpret this result. Both the full
run and recheck are reported; the recheck does not replace the original result.

Allocation was effectively unchanged. Product and benchmark source were also
unchanged, so the timing increase cannot be attributed to this merge's code
changes. CPU state, scheduling, and JIT behavior were not isolated. The slower
measurement was reproduced, while its cause remains unresolved. No arbitrary
performance pass/fail threshold was applied.

These microbenchmarks exclude IntelliJ token recognition, full index assembly,
EDT contention, and complete editing/scrolling workloads. JMH used JDK 17;
the visual-test IDE used JBR 21. Cancellation timing includes the 1 ms request
delay and scheduling overhead. Sort allocation includes input cloning and does
not represent retained heap.

## Package identity

The locally built and tracked `bracket-pair-guides-0.0.5.zip` archives both have
SHA-256:

`fc92eb55c8c8adf653e1c664f3bb9b71a48923ce960757273e4e4ddaf939d99f`

The visual-test changes did not alter this product package. This document
records automated QA outcomes; it does not record a Marketplace publication.
