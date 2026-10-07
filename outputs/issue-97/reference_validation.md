# Issue 97 validation reference

Historical record imported from `codex/issue-97-physical-modules` at `a8e0bc1`.
This file describes that earlier worktree and archive; current validation is recorded
in `progress.md` and the `current-*` evidence directories beside this file.

Status: implementation, deterministic checks, and comparative performance assessment are complete. Repeated latency increases remain unresolved; this is not a no-regression result.

The implementation is on `codex/issue-97-physical-modules`, based on PR #96 head `84d1a43141b1933c81e027ef01d1579fc4679e6e`. PR #96 was open when the base was selected. The final production commit is `c918a9657843fbcdb0c944f674c4706908987511`, including the guard expression correction requested by static analysis. The original checkout was preserved. All work and evidence remain local; nothing was pushed or uploaded.

## Production ownership

| Module | Responsibility | Project dependencies visible to its production compiler |
| --- | --- | --- |
| `analysis-model` | Immutable values and read-only result queries | None |
| `analysis-core` | Pairing, canonical indexes, guide computation and query implementation | model |
| `editor-ui` | Editor state, presentation, settings, request/cancel contracts | model |
| `analysis-runtime` | IntelliJ capture, execution, cancellation, validation and publication | model, core, UI |
| `plugin` | Descriptor registrations, resources and distribution assembly | model, UI, runtime |

The UI compiler cannot resolve core/runtime calculation entry points, builders, capture implementations or concrete indexes. The plugin production compiler cannot resolve core. The runtime's `implementation` dependencies do not export core to consumers. Positive and negative Kotlin and Java probes use the actual production compiler inputs. Production friend paths, shared outputs and alternate class/source/module paths are audited. Integration tests intentionally have broader test-only access.

The result contract returns bounded queries and model values. Its core implementation is the snapshot object itself, preserving shared canonical index storage and the per-editor query memo without adding a wrapper. UI/runtime use ordinary library jars in `lib/`, including on minimum IDE build 241. `plugin` owns no production classes.

## Completed deterministic checks

| Check | Result | Evidence |
| --- | --- | --- |
| Final `check`, formatting, plugin structure/project configuration | 516 tests: model 6, core 99, plugin 411; zero failures/errors/skips after expression correction | [check 4](check-4-evidence/summary.json), [log](check-4.log) |
| Actual production Kotlin/Java compiler probes | 44 positive/negative probes passed | [results](check-4-evidence/results.json), [classpaths](check-4-evidence/classpaths.json) |
| Genuine build contamination | Eight injected violations rejected; clean final check passed | [results](contamination-2/results.json) |
| SDK-free model/core build | 105 tests and eight pure-owner compilation probes passed | [results](sdk-free/results.json), [log](pure-build-4.log) |
| Audit/packaging support tests | 20 Python tests passed | [full check log](check-4.log) |
| Existing benchmark/visual reporting tools | 52 Python and five Node tests passed | [state](state.json) |
| Final packaging | 517 classes, each once, across five owner jars; descriptor references, icons and licenses retained; no IDE, coroutine, Kotlin-stdlib or test classes bundled | [review](release-packaging/review.json), [audit](release-packaging/packaging.json) |
| 13-target strict verifier | All 13 passed for final archive `c5e5a1f`; eight original strict failure levels applied | [release matrix](verifier-release-result.json) |
| Final latest-263 runtime fixture suite | All 411 tests passed at `c918a96`; testcase identities match the default suite; zero failures/errors/skips | [release runtime result](latest-runtime-release-result.json) |
| Final Driver visual suite | 13 tests/11 screenshots passed at `c918a96`; baseline hashes unchanged; all screenshots byte-identical to previous guarded run | [release Driver evidence](driver-release-result.json) |
| Final Qodana | Zero findings, exit 0, original fail threshold 0; earlier RedundantIf finding corrected without suppression | [release Qodana evidence](qodana-release-result.json) |

All original 503 test/rule declarations were conserved. New tests exercise the module seams and source identity adapters. The ADR decisions, performance limits and visual baselines remain unchanged. Existing tests cover EDT/read access, background execution, cancellation, stale-result rejection, editor release, and guide visibility. These tests and compile barriers are not proof that every threading or algorithmic defect is absent.

During review, the extracted SDK adapter was found to read the highlighter eagerly for an already stale document. Commit `89a4198` restores PR #96's short-circuit order. Two new tests fail against the preserved pre-fix bytecode and pass after the fix; a third confirms current/replaced highlighter identity behavior. See [red evidence](stale-source-red-result.json) and [final source review](final-review-model-current.json).

Current corrected archive: `plugin/build/distributions/bracket-pair-guides-0.0.6.zip`, SHA-256 `c5e5a1f2c60564af6aa9c0e01cd843cf73a224cd73dd73b8aa3a629663ab4db0`. The previous guarded archive was `0d38f3bdb34eb594d9d2bce30c5dcea264e19caf6dbf44ad19b853a2a6f0c22a`; its results must not be relabeled as new-archive runs.

## Performance evidence and limits

The comparison baseline is the exact PR #96 Git archive. Measured production, build and fixture files were checked against its Git blob identities. Workloads run serially with fixed launcher/JVM settings, unchanged fixtures, and recorded power/load snapshots. Analysis, write-wait, execution and native timing suites use 100 warmups and 30 samples per group. Repair uses 100 warmups and 30 measured samples per corpus/implementation/JVM, split into tab/space variants with 50 warmups and 15 samples each. Payload and capture-release use separate single observations per corpus/scale/JVM. Results retain the actual revision, process command, raw measurements and hashes. No failed or unfavorable campaign was discarded.

| Campaign | Scope | Status |
| --- | --- | --- |
| Primary fixture comparison at `75ef1e3` | Seven workloads, three fresh matched JVMs per side, 42 commands, baseline then candidate | Complete; [analysis](analysis-comparison.json), [execution/repair/native/memory](execution-comparison.json) |
| Reverse confirmation at `75ef1e3` | Analysis/write-wait/native, three pairs per workload, 18 commands, candidate then baseline | Complete; [analysis](analysis-confirmation-comparison.json), [write/native](execution-confirmation-comparison.json) |
| Final corrected source at `c918a96` | Analysis/write-wait/native, six pairs per workload, 36 commands, alternating pair order | Complete; [analysis](analysis-final-comparison.json), [write/native](execution-final-comparison.json) |
| Full JMH at `75ef1e3` | 46 cases per side, two forks, two warmups, three measurements, one-second iterations, GC profiler, fixed JDK 17/2 GiB, original 240-second job limit | Complete; [summary](jmh-1/summary.json), [review](jmh-review.json) |

Final values below are medians across six JVM summaries. Analysis latency uses each JVM's median; writer tails use each JVM's nearest-rank p95. Samples from different JVMs are not pooled. Allocation covers the measured coroutine segments, not all IDE allocations.

| Input | Analysis median, baseline → candidate (ms) | Change | Direct allocation median change | Writer p95, baseline → candidate (µs) | Change |
| --- | ---: | ---: | ---: | ---: | ---: |
| ordinary | 3.893 → 3.924 | +0.81% | -1.860% | 152.8 → 206.8 | +35.32% |
| nested | 9.113 → 9.693 | +6.37% | -0.004% | 78.9 → 96.9 | +22.70% |
| close-only | 14.958 → 15.802 | +5.65% | -0.072% | 99.5 → 99.6 | +0.08% |
| large-whitespace | 1.746 → 1.831 | +4.90% | -0.251% | 55.1 → 61.4 | +11.33% |
| xml | 9.794 → 9.934 | +1.43% | +0.014% | 89.2 → 101.1 | +13.43% |

Ordinary-input writer p95 increased in all three campaigns: +22.3% initially, +16.9% in reverse order, and +35.3% in the final balanced campaign. The final increase occurs in five of six pairs; nested writer p95 increases in all six pairs (+22.7% across JVM summaries). These repeated increases remain an unresolved regression signal. No timing threshold was invented or relaxed to label them a pass.

The final large-Java direct native inspection with a late traversal writer has p95 +25.0% (154.0 → 192.5 µs), after +70.5% and +79.3% in earlier campaigns. Late lazy-lexer direct inspection is +38.7% (155.3 → 215.4 µs); the earlier lazy-scope concern instead falls by 26.4% in the final campaign. The seven principal native capture/traversal files are unchanged from PR #96. Neither unchanged source nor variable pair/order results establishes equivalence or dismisses the observed tails.

The earlier ~10–12% nested/close-only analysis median increases fell in the reversed campaign, but the final campaign still measures +6.37%/+5.65%. Direct coroutine allocation medians show no corresponding increase above 0.015% in the final corpus summaries. This is not a total-heap or all-path allocation guarantee.

First-pair setup limitation: an idle task-owned Gradle daemon (CPU 0.0%) was retired during the first baseline analysis command, after candidate 1 completed. All six pairs remain the primary result; the separate five-pair sensitivity still shows nested/close-only medians +4.96%/+3.52%. It has an unequal pair-order split and is a sensitivity check, not a replacement campaign. Write-wait and native runs started after this cleanup. See the [setup record](performance-release-preparation-cleanup.json), [analysis review](performance-release-analysis-review.json) and [provenance review](performance-release-provenance-review.json).

All 900 analysis cancellation attempts per side completed as canceled, with no request recorded after return. Ordinary-input request-to-unwind median increased from 137.5 to 153.4 µs (+11.57%); its p95 increased 7.34%. This measures observed unwind after a scheduled request; it does not isolate individual cancellation-check latency.

In the earlier `75ef1e3` production execution comparison, main analysis median/p95 increased 9.76%/8.11%, while end-to-end observed acceptance changed +2.64%/−2.75%; one baseline JVM had an unexplained bimodal analysis distribution. Secondary analysis median increased 3.83%, with debounce-dominated acceptance nearly flat. The production repair 256-line tab case had worker p95 +5.01%; both tab/space allocation medians were +1.92%, with p95 allocation approximately flat. These execution/repair measurements were not repeated at `c918a96` and must not be presented as final-revision executions. See [primary execution/repair review](performance-ui-initial-review.json).

Final raw invariants: all 900 writer samples per side completed; native tests recorded 2160 samples per side (900 complete, 1260 invalidated by the writer), with no writer/resolution failure and all 1260 writers triggered before resolution finished. Request timing fell inside an observed read-body interval in 1259/1260 cases per side, so the evidence does not establish that every request occurred inside such an interval. See the [final write/native review](performance-release-write-native-review.json).

JMH mean latency changes range from −1.69% to +3.90%; every conservative interval for a difference contains zero. This does not prove equivalence. Every benchmark-reachable production class checked by the conservative class/descriptor closure is byte-identical in the final release (26 production dependencies); only the unrelated SDK stamp adapter changed. Thus these measurements remain scoped algorithm evidence, not a JMH run of the final revision or proof about dynamically discovered dependencies. See [provenance](release-jmh-provenance.json).

In the primary `75ef1e3` campaign, primitive index payload sizes, object counts and capture batch capacities match the baseline. All measured result/index storage and nonstatic arrays were released. One final XML context remains observed live at the five-second timeout in both baseline and candidate; its later release was not established. The capture batches and arrays cleared. This existing limitation remains. Native measurements have no allocation metric. See [memory/native review](performance-memory-native-review.json).

## Local provenance

Implementation commits: `1d51e9e`, `5fdf369`, `dedf706`, `d2e6b67`, `75ef1e3`, `89a4198`, `c918a96`. The final report commit records this reference and [compact validation summary](validation-summary.json), with no production changes.

Detailed reviews, runners, raw logs, XML, screenshots, benchmark JSONL and immutable attempts are preserved locally under this directory as untracked evidence. They are not included in the report commit; the compact summary records evidence paths and SHA-256 digests. A checkout of the Git branch alone does not contain those raw artifacts. Older verifier and fixture evidence explicitly describes its older archive; final results must cite the new archive above. Download cleanup is limited to exact IDE artifacts acquired by this task. Pre-existing caches and the original checkout are preserved.
