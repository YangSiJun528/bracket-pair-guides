# Issue 97 current implementation and verification

This report describes the isolated `codex/issue-97-module-isolation` worktree. It supersedes the imported historical `reference_validation.md` / `validation-summary.json` for current-run claims. Implementation and the requested local verification runs are complete. Structural, correctness, packaging, compatibility and visual checks passed. Performance measurements are complete, but absence of performance regression is not established.

The final production change is `d6226f3`; measurements froze candidate `f7599fa39be7d8e93cc339da719b14012ab1a1dd`. Later report/tooling commits do not change that measured production source. Final ZIP SHA256 is `83d4c0a23cdd245b933fb6c663bdddb944a29d1342d01f0d73e72df4631c0da8`. The initial Qodana finding was fixed and all final-source checks rerun. The final ZIP passed all 13 strict IDE targets and the latest-IU runtime suite under `current-verification/runs/20261007T112724798906Z/`. The earlier matrix remains historical evidence for its earlier ZIP.

## Base and preservation

PR #96 was OPEN at task start. This branch starts from its then-latest head `84d1a43141b1933c81e027ef01d1579fc4679e6e`. The committed five-module implementation through `a8e0bc1` was independently reviewed and reused. The original main checkout and another dirty issue-97 worktree were not modified. All changes are local; no push, PR, merge, submission or source upload was performed.

## Production dependency boundary

| Module | Permitted project compile dependencies | Responsibility |
| --- | --- | --- |
| analysis-model | none | Platform-free values, opaque identity stamps and read-only result contracts |
| analysis-core | analysis-model | Pairing, immutable index construction and guide calculations |
| editor-ui | analysis-model | Editor lifecycle/state, presentation and request/cancel contracts |
| analysis-runtime | analysis-model, analysis-core, editor-ui | IntelliJ capture, execution, cancellation, validation and publication coordination |
| plugin | analysis-model, editor-ui, analysis-runtime | Descriptor registration, resources and packaging |

Core is an implementation dependency of runtime and is absent from plugin compilation. Both core and runtime implementations are absent from actual UI Kotlin/Java compiler libraries. The read-only `BracketSnapshot` / `TokenWindow` contract exposes indexed result queries without builders, concrete indexes or capture/calculation entry points. Lightweight UI identity checks preserve stale-result rejection; proportional input capture remains runtime-owned. Test-only friend paths and benchmark access are explicitly separated from production compilation.

The final distribution uses five ordinary `lib/` owner jars for the minimum 241 classloader. Plugin itself has no production classes; descriptor classes are resolved from the owner jars. New local fixes restore invalid token-range rejection and strengthen verification of actual compiler source paths and packaged class bytes.

## Current deterministic evidence

| Verification | Current result | Evidence |
| --- | --- | --- |
| Fresh model/core/plugin tests | 518 passed; 0 failure/error/skip | check-current-final.log; final-check/tests-summary.json and XML |
| SDK-free clean offline build/tests | 107 passed; 16 compiler controls; fresh execution | sdk-free-final-source.log; sdk-free-final-source/ |
| Actual compiler probes | 55 passed: 34 forbidden references, 10 generic positives, 11 owner positives | final-check/results.json; classpaths.json |
| Actual Gradle bypass injections | All 13 rejected with intended reason; clean graph passed | current-contamination/run-20261007T085251Z/results.json |
| Packaging | Five jars; 517 classes exactly once; all archived class bytes match compiled owners | final-check/packaging.json |
| Strict compatibility | Final ZIP: 13/13 passed, all eight failure levels preserved | current-verification/runs/20261007T112724798906Z/summary.json |
| Latest IU runtime | Final source: 411 passed with exact case parity and actual selected IDE/JBR | current-verification/runs/20261007T112724798906Z/runtime-fixtures/IU-263.6259.32/summary.json |
| Driver visual test | 13 passed; 11 actual PNGs byte-identical; baseline hashes unchanged | current-verification/driver-result-final.json |
| Python support tests | 21 compiler audit + 10 packaging + 52 Bencher passed | current-check/independent-review.md; bencher-tests-current.log |
| Visual reporter tests | 5 passed | reporter-tests-current.log |
| Final root check after packaging/range guard | Passed | check-current-final.log; final-check/ |
| Qodana recommended / threshold 0 | Passed: actual exit 0; 0 findings; 112 source/build inputs matched | current-verification/qodana-result-final.json |

Every final compatibility target checked ZIP `83d4c0a2…` before and after its invocation. The matrix selected IC 2024.1.7 through 2025.2.6.3 and IU 2025.3 through the frozen 263 builds; exact identities and verdicts are retained in the run summary and per-target records. Final independent reviews: `final-check/independent-review.md` and `current-verification/matrix-final-independent-review.md`. The initial matrix and its independent review remain scoped to the earlier `f07ede40…` archive.

Existing execution/presentation behavior is covered by the actual IntelliJ fixtures, including `EditorAnalysisExecutionTest`, `BackgroundAnalysisLifecycleTest`, `EditorGuideSessionLifecycleTest`, `GuideRepairExecutionTest` and `AnalysisStampTest`. They exercise worker/read-access ownership, cancellation, stale-result refusal, disposal and guide publication/presentation. ADR 0001's affected-guide hiding and ADR 0002's preference for editor writes remain in place. All 503 original test declarations were conserved through relocation; 15 were added. This is declaration conservation, not a claim that no test body needed an adapter change.

Root `check`, Spotless, CI's pure-module build and report collection, benchmark dependencies/change detection, and contributor/benchmark documentation cover the new module paths. Existing strict verifier settings, Driver image oracle and performance thresholds were preserved. Python support tests, visual reporter tests and full benchmark coverage were executed locally; remote CI/submission is outside the authorized scope.

The compiler guard checks resolved archives and actual Kotlin/Java libraries, typed source/bootstrap/processor paths, source/output roots, compiler options and friend paths. Negative experiments include direct/transitive dependencies, shared outputs/sources, source-only archives and alternate compiler source/module paths. Owner-positive controls prevent missing or renamed forbidden symbols from falsely proving isolation.

## Performance and allocations

Comparison execution is complete, but **performance equivalence / absence of regression is not established**. Baseline is the exact PR96 head; candidate is the frozen revision above. Original raw results, rejected attempts, source manifests, actual JVM revision flags and environment observations remain in this worktree. No test, timing boundary or screenshot baseline was weakened.

Seven opt-in IntelliJ workloads ran in three fresh matched JVM pairs per workload (42 accepted commands, alternating order). One earlier attempt was rejected for coordination interference and retained. A pre-existing Gradle daemon was verified by exact identity and independent IDLE state, then gracefully terminated; its idle worker exited too. Files and caches were preserved, and both processes were absent in all 84 accepted before/after observations. The two sides used the same JVM/SDK/corpus/heap/power configuration and existing warmup/sample counts. See `current-performance/independent-current-review.md`, `comparison.json` and `idle-daemon-quiescence.json`.

Selected results below are medians of the three per-JVM statistics, not pooled samples or paired effect estimates. The first five timing rows increased in all three pairs; the ordinary allocation shift occurred mainly in one pair.

| Measurement | Baseline → candidate | Interpretation |
| --- | --- | --- |
| Nested analysis writer wait p95 | 127.042 → 153.125 µs (+20.5%) | Repeated responsiveness signal |
| Native large-Java/direct/late-traversal writer p95 | 131.500 → 157.709 µs (+19.9%) | Repeated signal; read-body p95 also +39.6% |
| Production ordinary/tab repair worker p95 | 202.875 → 262.459 µs (+29.4%) | Repeated signal; 15 samples per variant means p95 is the maximum |
| Main request-to-observed-acceptance median | 4.973 → 5.362 ms (+7.8%) | Includes scheduler and harness observation, not keyboard latency |
| Ordinary cancellation unwind median | 150.521 → 164.083 µs (+9.0%) | Cancellation behavior passed; latency uncertainty remains |
| Ordinary calculation allocation median | 2,526,696 → 2,622,584 B (+3.8%) | Distribution shift mainly in one pair; p95 nearly unchanged |
| Production ordinary/tab repair allocation | 21,200 → 21,248 B (+0.23%) | Paired increases of 24/24/48 B; cause not established |

All measured cancellation and execution invariants completed; 450 cancellation trials per side cancelled before return, all 180 execution trials per side completed, and all 540 production repair worker traces per side closed. Native writers triggered before resolution finished in all 630 trials per side; 627 baseline / 626 candidate triggers occurred inside a read body, so this is not claimed for every trial. Payload object/array/primitive-byte inventories were identical in 15 paired observations, and all tracked payload releases completed. In capture release, all nine Java observations per side cleared; all nine XML observations per side retained one classifier context at the five-second deadline while token batches/primitive arrays cleared. Eventual XML classifier release is unproved on both revisions.

JMH ran the unchanged full 46-case profile on each revision (seven jobs, two forks, two warmups and three measurements of one second, GC B/op profiler, 2 GiB heap, JDK17.0.17). Every job stayed within the 240-second limit; full-case coverage passed. One initial ascending-sort case (32,768 elements) rose **29.43%**, with a conservative latency delta interval wholly above zero. Two predetermined balanced follow-up pairs, each covering all eight ascending cases, measured **+1.44% and −0.48%** for that case with intervals spanning zero. The initial signal was not reproduced and has not been deleted or replaced. No interval-separated B/op increase was detected across the full JMH results or these follow-ups; that is not a proof of allocation equivalence. See `current-jmh/final-independent-review.md` and the untouched full run in `current-jmh/results/`.

Source/bytecode review did not establish a cause for the timing or allocation differences. Some paths are byte-identical after relocation; a new bounded duplicate stamp comparison is a possible constant cost, not a proven explanation. Measurements are complete and reported honestly; no speculative production change or repeat-until-pass run was used. The external historical Bencher gate was not submitted or executed.

## Interpretation limits

Compiler restrictions prove the tested structural access boundary, not the absence of all threading, cancellation or algorithm bugs. The ordinary fixture suite includes disabled-by-default measurement tests; their successful ordinary execution alone does not prove performance. Runtime tests and Driver checks cover recorded scenarios rather than every supported IDE's complete UI behavior. Compatibility verification is static; actual runtime execution is separately recorded for minimum fixtures, pinned Linux Driver and latest IU fixtures.

The pure profile compiles/tests without IntelliJ SDK bytecode. The settings script still loads IntelliJ Gradle settings tooling. Packaging resource checks cover required resources and descriptor rules, while class identity is checked byte-for-byte. Remote CI and Bencher submissions are not executed; external submission was not authorized.

## Final state and local evidence

The final consistency check confirms all 241 measured source/build/fixture inputs still match the frozen candidate, all 517 compiled owner class hashes still match the audited package, and the ZIP hash is unchanged after the entire matrix. The original checkout is clean. See `final-consistency.json`.

Implementation and verification changes are preserved as separate local commits on `codex/issue-97-module-isolation`. The final production source change is d6226f3; measured candidate f7599fa includes only subsequent verification tooling. Documentation/evidence commits after measurement do not change production inputs. Full raw logs, JSONL and immutable benchmark bundles remain under this worktree's `outputs/issue-97/`; `local-evidence-index.json` records their SHA256 identities. Compact results and reports are committed. No push, PR, merge or external source/result upload was performed.

Remaining limits are the unresolved performance/allocation signals, XML classifier retention at the bounded deadline on both revisions, finite runtime/visual coverage, SDK tooling distinction and the unexecuted external historical Bencher/remote CI runs. No requested local verification remains unexecuted. These limits are not converted into passes.
