# Independent fresh fixture review

The accepted campaign has complete provenance and expected fixture invariants. It does **not** establish performance equivalence or absence of allocation regression: several candidate latency statistics increase in all three matched JVM pairs, and ordinary analysis allocation shifts substantially in one pair. JMH was not reviewed here and must be assessed separately.

## Scope and integrity

Candidate: `f7599fa39be7d8e93cc339da719b14012ab1a1dd`. Baseline: `84d1a43141b1933c81e027ef01d1579fc4679e6e` (PR #96 head). Seven workload families, three fresh JVMs per side, 42 accepted commands, alternating side order. Warmups are excluded. Medians and nearest-rank p95 are calculated within each JVM; values below summarize those three independent statistics, without pooling samples. Paired directions and magnitudes remain necessary because the median of the JVM statistics is not a paired effect estimate. With 30 samples, p95 is the 29th sorted observation; repair has only 15 measured samples per tab/space variant, so p95 is the maximum. Three pairs cannot establish a tight equivalence bound or causality.

I independently checked all 42 accepted attempts against the state: each frozen source revision/fingerprint matches its side and expected SHA, and actual recorded JVM arguments contain the corresponding `-Dissue93.measure.revision` flag. Both source manifests match Git commit bytes and live worktree/archive bytes: candidate 241 files, baseline 225, zero mismatches. Fingerprints are candidate `15b6fa14444d66132846a71db2ed2b73804bc8f72545137a7f4c26061214905f`, baseline `7f275d88edb4a0cc7accc44672ba57c1ad9f6f6cdc54fab0e492c7a00e507142`. This checks manifest scope, not every machine/SDK/cache input. The comparator checks retained raw/log identities, environment/JVM/power matching and fixture grouping; `all42CommandsMatched` and `allInvariantGroupsComplete` are true. `complete_descriptive_comparison` means integrity/completeness, not performance acceptance.

There are 43 retained attempts, including the rejected first `analysis-candidate-1` attempt: preserved daemon PID 33144 reached CPU 0.8% and violated coordination policy. It is not silently discarded or used as accepted data. `idle-daemon-quiescence.json` records an independently IDLE daemon, graceful SIGTERM to PID 33144 and exit of its idle worker 33413; both were absent in all 84 accepted before/after observations. This supersedes earlier planning text claiming no pre-existing process would be terminated. Accepted commands on both sides ran after that exit. Snapshot guards bound coordination observations; they cannot prove there was no transient unrelated interference between snapshots. No benchmark threshold was lowered.

## Calculation, cancellation and writer responsiveness

Analysis wall median, in milliseconds, moves ordinary 3.970→4.399 (+10.8%, two pairs higher), nested 9.219→10.288 (+11.6%, two), close-only 17.522→15.848 (−9.6%, one), whitespace 1.922→1.936 (+0.7%, two), XML 9.989→9.661 (−3.3%, one). Nested p95 increases 9.819→11.385 ms (+15.9%, two pairs higher). These mixed changes do not support a universal latency improvement or a blanket no-regression claim.

Nested writer-wait p95 is a repeated increase: 127.042→153.125 µs in the median of per-JVM p95 (+20.5%); paired changes are +21.45%, +45.72%, +1.05%. Ordinary writer p95 falls 392.875→245.125 µs (−37.6%) with one pair higher. The earlier ordinary writer slowdown does not consistently reproduce in this campaign; that does not prove it absent. Whitespace writer p95 rises 101.542→108.792 µs (+7.1%, two pairs higher). All 450 writer trials per side complete and request writes during analysis; read-body inventories match. Writer wait, read acquisition, queue delay and analysis wall are different clocks and should not be substituted for each other.

All 450 cancellation trials per side report cancellation and none requests cancellation after analysis has returned. Ordinary cancellation-unwind median is 150.521→164.083 µs (+9.0%) with all three pairs higher (+9.18%, +2.84%, +8.58%); its p95 is mixed and the median of p95 falls 6.1%. XML unwind median rises 20.8% in two pairs, whitespace p95 rises 28.7% in two. Cancellation behavior is observed intact, while these diagnostic latency differences remain visible.

## Execution and geometry repair

Main-editor request-to-observed-acceptance median rises 4.973→5.362 ms (+7.8%) in all three pairs (+0.36%, +8.68%, +7.06%). This includes scheduling and pump observation, not solely calculation or keyboard latency. Main analysis median rises 2.321→2.789 ms (+20.2%), but paired changes are −60.37%, +227.29%, +0.66%; that strong fresh-JVM variation prevents a stable effect estimate. Secondary observed acceptance median is 80.234→80.822 ms (+0.7%, two pairs higher), with p95 89.062→88.346 ms (−0.8%, all pairs lower). The existing 75 ms debounce remains part of these observations. All 180 measured execution trials per side complete.

Repair has 1,080 samples per side, half from `independent_geometry_repair` (production) and half from `frozen_whole_presentation_reference` (diagnostic reference). Reference-path changes are not evidence that production uses full presentation computation. All 540 production worker traces per side close, and expected acceptance/refusal, anchors and columns are covered by invariant groups. For production 257-line refusal/space, UI callback median rises 58.916→61.458 µs (+4.3%, all pairs higher); request/schedule p95 rises 55.000→82.625 µs (+50.2%, all pairs higher), and UI callback p95 rises 93.916→137.292 µs (+46.2%, two). Ordinary/tab worker capture-compute p95 rises 202.875→262.459 µs (+29.4%, all pairs higher). Same-line callback increases are hundreds of nanoseconds in medians, not millisecond stalls; those small values are retained rather than dismissed or presented without units. The repeated production repair signals are listed below.

## Native input capture and invalidation

All 1,080 native samples per side satisfy expected outcomes: 450 complete without writers and 630 are empty or invalidated under writers; all 630 writer trials trigger before resolution has finished. Requests are observed inside a read body in 627/630 baseline and 626/630 candidate trials, so claiming every writer request occurs inside a body would be incorrect. Read-body counts match. Native allocation is not measured by this fixture.

Large-Java/direct/late-traversal writer p95 rises 131.500→157.709 µs (+19.9%): all pairs increase (+17.15%, +3.77%, +80.83%). Maximum read-body p95 also rises 300.250→419.041 µs (+39.6%, all pairs higher), and capture EDT p95 26.584→31.792 µs (+19.6%, all pairs higher). This is a repeated responsiveness signal, even though source-level scheduling logic was preserved. Large-Java-lazy/scope/late-lazy-lexer writer-wait median rises 104.271→107.875 µs (+3.5%, all pairs higher), and queue-delay p95 273.083→298.209 µs (+9.2%, all pairs higher). Native pump gaps also have repeated increases and large outliers; they help characterize harness/scheduling variability without explaining away writer/read-body results.

## Allocation and retention

All 450 calculation allocation traces per side complete. Ordinary direct-coroutine allocation median changes 2,526,696→2,622,584 bytes (+95,888 bytes, +3.8%), driven by one pair (+3.85%; other pairs +0.02% and −0.03%). Ordinary p95 aggregate is nearly unchanged (+96 bytes), with paired shifts +0.004%, −3.65%, +3.82%. This distribution shift must not be reported as allocation equivalence. Nested median falls approximately 3,472 bytes (−0.045%), close-only aggregate is unchanged, whitespace median rises 20 bytes, XML falls 96 bytes. Production ordinary/tab repair worker allocation rises in all pairs by 24/24/48 bytes; aggregate median 21,200→21,248 bytes (+0.23%). Trace allocations measure named phases/coroutine execution, not all process or UI heap allocation.

All 15 payload inventories per side have identical paired owned-object counts, primitive-array counts and primitive bytes; all 15 tracked payload-release observations per side release everything without deadline timeout. Those observations are not whole-process heap size or a precise GC latency measurement.

Capture-release has 18 observations per side. All nine Java cases clear observed references. All nine XML cases time out with one classifier context still reachable at the five-second deadline, while token batches and primitive arrays clear (zero survivors). The same limitation exists on both sides; eventual classifier-context release is not established. A single retention observation per JVM/language/scale is not a leak-proof guarantee or a useful p95 latency sample.

## Interpretation

Fixture correctness and provenance are complete within their tested scenarios. Repeated higher nested writer p95, native large-Java writer/read-body p95, main observed acceptance, ordinary cancellation unwind and production repair statistics remain unresolved performance signals. Unchanged algorithms or mixed metrics cannot erase them. Allocation/payload evidence is narrower than total heap behavior, and XML classifier retention remains a bounded observation. Assess the separately executed JMH comparison and unchanged repository thresholds before any overall acceptance decision. This review does not claim that physical module isolation proves absence of threading or algorithm bugs.

## Repeated production repair and native timing signals

The table includes selected primary timing statistics that increase in all three pairs. Baseline/candidate are medians of per-JVM statistics in µs; paired percentages are pair 1/2/3. Diagnostic pump/reference measurements remain in `comparison.json` but are not mislabeled as production computation below.

| Workload/group | Metric/statistic | Baseline → candidate (µs) | Paired changes (%) |
|---|---|---:|---|
| native: large-java/direct/late-traversal | captureEdtNs/p95 | 26.584 → 31.792 | +53.12, +19.59, +14.56 |
| native: large-java/direct/late-traversal | readBodyMaximumNs/p95 | 300.250 → 419.041 | +23.62, +28.62, +90.23 |
| native: large-java/direct/late-traversal | writeWaitNs/p95 | 131.500 → 157.709 | +17.15, +3.77, +80.83 |
| native: large-java-lazy/scope/late-lazy-lexer | captureEdtNs/median | 10.749 → 10.813 | +5.04, +0.39, +3.37 |
| native: large-java-lazy/scope/late-lazy-lexer | writeQueueDelayNs/p95 | 273.083 → 298.209 | +6.35, +10.79, +15.42 |
| native: large-java-lazy/scope/late-lazy-lexer | writeWaitNs/median | 104.271 → 107.875 | +6.84, +2.10, +5.57 |
| repair: 257-line-refusal/space | requestAndScheduleWallNs/p95 | 55.000 → 82.625 | +487.23, +47.87, +8.33 |
| repair: 257-line-refusal/space | uiCallbackWallNs/median | 58.916 → 61.458 | +12.53, +9.58, +0.64 |
| repair: exact-256-line-budget/tab | workerCaptureComputeWallNs/median | 1276.834 → 1333.625 | +5.04, +4.62, +4.45 |
| repair: exact-256-line-budget/tab | workerFinishFromCallbackStartNs/median | 1391.166 → 1431.458 | +5.34, +2.73, +2.90 |
| repair: ordinary-small-body/space | requestAndScheduleWallNs/median | 47.875 → 56.375 | +13.03, +28.06, +6.88 |
| repair: ordinary-small-body/space | requestAndScheduleWallNs/p95 | 73.625 → 93.833 | +4.76, +27.45, +63.78 |
| repair: ordinary-small-body/space | uiCallbackWallNs/p95 | 130.833 → 159.000 | +15.07, +21.53, +38.66 |
| repair: ordinary-small-body/tab | requestAndScheduleWallNs/median | 45.709 → 49.584 | +19.87, +10.49, +1.73 |
| repair: ordinary-small-body/tab | workerCaptureComputeWallNs/p95 | 202.875 → 262.459 | +29.37, +37.70, +1.22 |
| repair: ordinary-small-body/tab | workerFinishFromCallbackStartNs/p95 | 348.333 → 448.250 | +0.14, +45.05, +6.71 |
| repair: same-line-special-case/space | uiCallbackWallNs/median | 1.916 → 2.500 | +15.37, +97.76, +19.62 |
| repair: same-line-special-case/space | uiCallbackWallNs/p95 | 9.625 → 11.708 | +12.55, +53.31, +22.70 |
| repair: same-line-special-case/tab | requestAndScheduleWallNs/p95 | 0.250 → 0.417 | +22.13, +149.70, +16.40 |
| repair: same-line-special-case/tab | uiCallbackWallNs/median | 2.042 → 2.500 | +17.65, +37.00, +4.06 |
