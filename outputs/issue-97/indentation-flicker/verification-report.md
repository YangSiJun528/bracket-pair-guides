# Indentation guide continuity verification

The reported Tab/Shift+Tab guide-only flicker was reproduced with actual editor actions and corrected for edits whose existing guide minimum is provably unchanged. Production revision: `60305549e301681222ef2783c953ae2d616d5cd6`; comparison baseline: `c9be822bfc0d74e7785406d3b664fa72ade0714a`.

## Change and correctness boundary

Only three production editor-ui files changed. `DocumentChange.from` records a bounded space/tab-only fact from the SDK event without copying or retaining fragments. `GuidePositionFallback` retains tracked geometry when the edit begins strictly after the current guide column, using SDK logical coordinates. The combined fragment limit is 256 characters; coordinate lookup is admitted only within a physical prefix of 4,096 characters. These are conservative admission bounds, not constant-time guarantees for SDK mapping.

The unchanged prefix proves that an edited line cannot become or cease being the minimum/earliest anchor. Existing range markers update endpoints. Token overlap, changed layout, new minimum, newline/non-whitespace, excessive fragments/prefixes, and absent prior geometry retain the existing synchronous hide-and-repair behavior. Every content edit still drops the accepted read-only view, invalidates source/work/native authority and schedules full analysis. Retained provisional geometry does not certify lexical pairing; whitespace can change language semantics. No core/runtime/model code or public module dependency changed.

## Executed verification

| Verification | Result |
|---|---|
| Pre-fix production + identical Driver harness | Expected failure: real Tab changed body column 12 to 16 and removed guide at column 8 synchronously. |
| Candidate actual Driver Tab/Shift+Tab | Passed: 12→16→12 with the same valid guide mark before either write command returned, updated endpoints and all token correspondences. |
| Existing Driver contracts | Passed: all 12 baseline PNG files byte-identical, plus tab/caret cycles, focus/visibility, settings/native behavior and closing-line hide/repair. No baselines changed. |
| Minimum SDK 2024.1.7 | 58 tests, zero failures/skips. |
| Current SDK 263.6259.32 | 58 tests, zero failures/skips. |
| Full root `check` | Passed in 10m31s; includes 52 build-logic tests with actual Kotlin/Java compiler failures and transitive/shared-output/source contamination rejection. Ordinary model/core/UI/runtime/plugin tests and JMH compilation also passed or were valid up-to-date results. |
| SDK-free standalone build, `--rerun-tasks` | All 14 tasks executed: model 4 tests and core 47 tests passed, with independent output directories. |
| Existing edit-restoration workload | Six unchanged corpora, one warmup and two measured samples each passed; 18 cleanup/editor-release records. Correct restoration's winning lane remains unidentified. |
| Packaging and structure | Passed Gradle checks; five production jars, 452 unique classes, no measurement/Driver/test classes. All 452 class bytes match the release archive exercised by Driver. The only resource difference is host Build-JVM/Build-OS manifest metadata. |
| Independent Sol code review | No actionable defect found; proof/authority/cost/paint limitations retained. |

The first three SDK attempts preserved real failures: an already-owned global registry in the fixture, then inconsistent tab-output settings. Tests now use scoped listeners through the UI port and set/restore actual editor tab settings; actual tabs 3→4→3, expanded columns 12→16→12 and synchronous paint/renderer assertions remain required. Production registry routing is separately covered by Driver. The first edit-restoration smoke invocation failed before measurements due to a malformed host class-name argument; its XML/log is retained and the corrected invocation passed. These failures were not counted as passes.

## Performance comparison

Six fresh-JVM pairs used balanced AB/BA order, five warmup and thirty measured round-trips per JVM. Both trees used the identical eleven-file SDK harness and `CandidateComparisonHost`, IC-241.19416.15 / JBR 17.0.12+1-b1207.37 / macOS aarch64 / 2 GiB test heap. Other agent-owned builds, Driver and IDE work did not overlap the measurements. Baseline production/build source hashes were checked against the declared Git revision after measurement.

Table times/allocations are medians of six JVM medians. The paired ratio is the median of six corresponding candidate/baseline ratios, so it need not equal the quotient of the two displayed aggregates.

| Actual action metric | Before | After | Median paired ratio |
|---|---:|---:|---:|
| Tab EDT duration | 2.113 ms | 1.898 ms | 0.8983 |
| Tab EDT allocation | 92.262 KiB | 83.207 KiB | 0.9023 |
| Shift+Tab EDT duration | 1.767 ms | 1.614 ms | 0.9027 |
| Shift+Tab EDT allocation | 91.062 KiB | 89.547 KiB | 0.9834 |

All four primary ratios were below the predeclared 1.20 regression flag. Secondary paired p95 ratios were also below 1.0. The candidate retained geometry in all 360 measured actions; baseline geometry was absent after all 360 actions. These are observations of SDK state before async publication, not screen-frame counts. Median paired inherited-coroutine allocation ratio was 0.4926; that scope must not be added to EDT allocation. Every run and sample was kept; no reruns were used to discard regressions.

This is one warmed Java indentation workload, not a universal performance or whole-IDE allocation guarantee. Long-prefix worst-case mapping and JMH latency/allocation benchmarks were not newly measured. JMH code compiled as part of `check`.

## Delivery and remaining limits

Local QA ZIP (version 0.1.0): `/Users/sijun-yang/Documents/GitHub/bracket-pair-guides/build/distributions/0.1.0-indentation-fix/bracket-pair-guides-0.1.0.zip`.

SHA-256: `ed735c744f9f94ba69b243c00dd6e01bdd5b8b8e77079a0a328994572a949776`.

A geometry-changing closing/minimum indentation edit, or an unproven edit such as whole-prefix tabs-to-spaces normalization, can still hide the guide until repair. These intentional limits preserve ADR 0001/0002. Actual Driver runs use pinned Linux IC 2024.2.6; the user's exact macOS GUI reproduction has not been manually rechecked. The full multi-product Plugin Verifier matrix and Qodana were not rerun for this narrow fix. No claim is made that structural compile restrictions eliminate all thread/algorithm bugs or that all paint interleavings are flicker-free.

No push, PR, merge, release or external upload was performed for this fix. Prior QA archives remain intact. Raw logs/XML/PNGs/JSONL remain locally under this evidence directory; compact summaries and reproducibility scripts are committed separately.

## Evidence

- `driver-before/review.json`, `driver-fixed/review.json` and their raw Driver diagnostics.
- `ide-check-01` through `ide-check-04`, `ide-check-04/summary.json` and corresponding logs.
- `full-check.log`, `full-check-summary.json`, `full-check-results/`.
- `sdk-free-check.log`, `sdk-free-summary.json`, `sdk-free-results/`.
- `performance-plan.json`, `performance-harness-source-sha256.json`, `baseline-production-source-sha256.json`.
- `performance/runs.json`, `performance/summary.json`, and twelve per-run raw `sdk.jsonl`/commands/XML/logs.
- `edit-restoration-smoke-01/`, `edit-restoration-smoke-02/summary.json` and raw records.
- `packaging-review.json`, `delivery.json`.
