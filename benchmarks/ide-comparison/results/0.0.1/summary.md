# Exploratory performance summary — 3 complete blocks

> **Exploratory only:** n=3 complete blocks. This record may be cited to explain the release sanity check, but it does not support a promotional performance claim or ranking.

Lower is better for every metric. Heap is whole-IDE JVM post-full-GC used heap, not plugin-retained heap or RSS; idle CPU is whole IDE process CPU over 30 seconds.
Caret timers cover synchronous `%goto` command completion; deferred visual updates were not fenced, so these are not direct paint or user-perceived latency measurements.

- Protocol: `primary-stress-v1`
- Source: `raw-runs.csv`
- Selected complete blocks: block 1 / attempt 1, block 2 / attempt 1, block 3r / attempt 1
- Complete block definition: exactly one valid row for each of baseline, BPG, Rainbow, and Color in the same attempt.

## Median [min, max]

| Variant | Heap used MiB | Idle CPU s/30s | Near 1k sync commands ms | Far 1k sync commands ms |
|---|---:|---:|---:|---:|
| baseline | 166.995 [153.304, 167.353] | 2.365 [2.102, 4.058] | 502 [454, 1253] | 699 [464, 701] |
| bpg | 167.042 [154.479, 167.084] | 1.845 [1.635, 2.487] | 1002 [694, 1038] | 930 [748, 1010] |
| rainbow | 188.674 [177.118, 189.501] | 2.082 [1.962, 2.133] | 442 [441, 537] | 547 [475, 695] |
| color | 170.804 [158.870, 171.960] | 3.694 [2.857, 3.805] | 458 [450, 494] | 531 [417, 562] |

## Paired per-block contrasts

Each cell is `BPG − competitor / BPG ÷ competitor`. Negative deltas and ratios below 1 favor BPG.

| Block / attempt | Competitor | Heap Δ MiB / ratio | Idle CPU Δ s / ratio | Near Δ ms / ratio | Far Δ ms / ratio |
|---|---|---:|---:|---:|---:|
| 1 / 1 | rainbow | -21.589 / 0.886x | -0.288 / 0.865x | +501 / 1.933x | +315 / 1.453x |
| 1 / 1 | color | -3.720 / 0.978x | -1.012 / 0.646x | +588 / 2.307x | +448 / 1.797x |
| 2 / 1 | rainbow | -22.459 / 0.881x | -0.446 / 0.786x | +561 / 2.272x | +383 / 1.700x |
| 2 / 1 | color | -4.918 / 0.971x | -2.059 / 0.443x | +508 / 2.028x | +399 / 1.751x |
| 3r / 1 | rainbow | -22.639 / 0.872x | +0.525 / 1.268x | +252 / 1.570x | +273 / 1.575x |
| 3r / 1 | color | -4.391 / 0.972x | -1.317 / 0.654x | +236 / 1.515x | +331 / 1.794x |

## Invalid and log-unclean rows

Audit scope: every data row in the source CSV, including failed attempts that were not selected.

### Valid rows excluded with an incomplete block (2)

| Block | Attempt | Slot | Variant | Run ID | Reason |
|---|---:|---:|---|---|---|
| 3 | 1 | 1 | color | `primary-b03-a01-s01` | incomplete four-variant block |
| 3 | 1 | 2 | bpg | `primary-b03-a01-s02` | incomplete four-variant block |

### Invalid rows (10)

| Block | Attempt | Slot | Variant | Run ID | Exclusion reason |
|---:|---:|---:|---|---|---|
| 3 | 1 | 3 | baseline | `primary-b03-a01-s03` | runner_exit_21;java_exit_NA;helper_not_loaded;output_not_verified;native_highlight_not_false;artifact_freeze_mismatch;metric_count_mismatch;invalid_heap;invalid_idle;invalid_caret |
| 3 | 1 | 4 | rainbow | `primary-b03-a01-s04` | runner_exit_21;java_exit_NA;target_not_loaded;helper_not_loaded;output_not_verified;native_highlight_not_false;artifact_freeze_mismatch;metric_count_mismatch;invalid_heap;invalid_idle;invalid_caret |
| 3 | 2 | 1 | color | `primary-b03-a02-s01` | runner_exit_21;java_exit_NA;target_not_loaded;helper_not_loaded;output_not_verified;native_highlight_not_false;artifact_freeze_mismatch;metric_count_mismatch;invalid_heap;invalid_idle;invalid_caret |
| 3 | 2 | 2 | bpg | `primary-b03-a02-s02` | runner_exit_21;java_exit_NA;target_not_loaded;helper_not_loaded;output_not_verified;native_highlight_not_false;artifact_freeze_mismatch;metric_count_mismatch;invalid_heap;invalid_idle;invalid_caret |
| 3 | 2 | 3 | baseline | `primary-b03-a02-s03` | runner_exit_21;java_exit_NA;helper_not_loaded;output_not_verified;native_highlight_not_false;artifact_freeze_mismatch;metric_count_mismatch;invalid_heap;invalid_idle;invalid_caret |
| 3 | 2 | 4 | rainbow | `primary-b03-a02-s04` | runner_exit_21;java_exit_NA;target_not_loaded;helper_not_loaded;output_not_verified;native_highlight_not_false;artifact_freeze_mismatch;metric_count_mismatch;invalid_heap;invalid_idle;invalid_caret |
| 3 | 3 | 1 | color | `primary-b03-a03-s01` | runner_exit_21;java_exit_NA;target_not_loaded;helper_not_loaded;output_not_verified;native_highlight_not_false;artifact_freeze_mismatch;metric_count_mismatch;invalid_heap;invalid_idle;invalid_caret |
| 3 | 3 | 2 | bpg | `primary-b03-a03-s02` | runner_exit_21;java_exit_NA;target_not_loaded;helper_not_loaded;output_not_verified;native_highlight_not_false;artifact_freeze_mismatch;metric_count_mismatch;invalid_heap;invalid_idle;invalid_caret |
| 3 | 3 | 3 | baseline | `primary-b03-a03-s03` | runner_exit_21;java_exit_NA;helper_not_loaded;output_not_verified;native_highlight_not_false;artifact_freeze_mismatch;metric_count_mismatch;invalid_heap;invalid_idle;invalid_caret |
| 3 | 3 | 4 | rainbow | `primary-b03-a03-s04` | runner_exit_21;java_exit_NA;target_not_loaded;helper_not_loaded;output_not_verified;native_highlight_not_false;artifact_freeze_mismatch;metric_count_mismatch;invalid_heap;invalid_idle;invalid_caret |

### Log-unclean rows (4)

| Block | Attempt | Slot | Variant | Run ID | SEVERE | ERROR | Plugin blame | Row valid |
|---:|---:|---:|---|---|---:|---:|---:|---|
| 1 | 1 | 4 | color | `primary-b01-a01-s04` | 5 | 0 | 1 | true |
| 2 | 1 | 3 | color | `primary-b02-a01-s03` | 5 | 0 | 1 | true |
| 3 | 1 | 1 | color | `primary-b03-a01-s01` | 5 | 0 | 1 | true |
| 3r | 1 | 1 | color | `primary-b03r-a01-s01` | 5 | 0 | 1 | true |
