# Measure calculation and editor contracts

Build first, then measure without concurrent Gradle, Driver, Qodana or verifier
work. Keep raw results, failed runs and the exact source/environment identity.

## Run JMH

```sh
./gradlew :benchmarks:jmhJar
./gradlew :benchmarks:jmh -PbenchmarkJob=analyzeCold
```

The five jobs are `analyzeCold`, `analyzeReuse`, `visibleQuery`, `repair` and
`cancelledAttempt`. Each has eight inputs: 64/4096 pairs and sibling/nested/sparse/
malformed distributions. They call public use cases through a benchmark-only
recorded-input adapter. Cold analysis includes a new calculator; reuse analysis
keeps one calculator. Query setup and result construction stay outside query
measurement. `runBlocking` bridge overhead is included in suspend use cases and
must be equal in baseline/candidate adapters.

Full defaults are two forks, two 1-second warmup iterations, three 1-second
measurement iterations, one thread, 2GiB fixed heap and the GC profiler. Preserve
ns/op and `gc.alloc.rate.norm` B/op secondary metrics. `-PbenchmarkSmoke=true`
checks executable setup only and cannot establish performance acceptance.

CI compiles before its per-job `timeout ... 240s java -jar ...` measurement.
The limit is unchanged. JMH JSON is the authoritative raw result; standard
Bencher JMH import is not assumed to import allocations automatically.

## Compare the redesign

Freeze `072533f` as the immediate baseline and the completed source as candidate.
Use the same workload meanings, inputs, result consumption, JDK/IDE, heap/GC,
forks/warmup and measurement machine. The former internal API need not survive;
put any compatibility adapter in a separate baseline comparison worktree only.
Do not retain old test code in the redesigned executable suite.

Run at least five independent fresh-JVM pairs with balanced AB/BA order. Report
pair-level ratios and all raw samples; iterations inside a JVM are not
independent JVM samples. Predeclare primary metrics, effect-size criteria and
any extra-pair policy before looking at candidate results. Preserve the existing
20% history alert and all hard resource budgets; this history threshold alone is
not a same-condition or tail-latency proof.

Measure actual IDE capture/read occupancy, writer wait, cancellation unwind,
full execution, hide-to-repair, native proof and payload lifetime separately.
Use the same actual SDK task/JBR and narrow runtime observation adapter in both
revisions. A configured dispatcher or unit-test duration cannot establish those
properties. Record actual operation counts and the p95 definition.

The old evidence contains 42 IntelliJ measurement commands (7 workloads × 3 JVM
pairs × 2 revisions), separately from 46 old JMH cases/7 jobs. Those are historical
anchors, not equivalent new measurements. Existing writer/repair/allocation and
XML-retention signals remain unresolved until specifically measured and fixed.

## Measure unchanged guide retention during indentation

The opt-in SDK `indentation` workload uses the existing comparison fixture and
actual Tab/Unindent editor actions in a warmed main editor. Run both revisions
with the same `CandidateComparisonHost` and harness; it records whether geometry
survives each action without requiring either behavior as a baseline assertion.
Both revisions must produce the declared 12→16→12 body indentation, restore the
original text and guide geometry, and close their actual owned work and markup.

Record per-action EDT duration/allocation separately from inherited coroutine
allocation and read observations. Action timing includes document callbacks but
excludes subsequent commit/inspection; the scopes must not be added into a total.
The fixture is headless and explicitly active, so Driver remains the rendering
and focus check. Existing `edit-restoration` corpora retain exact settled geometry and refusal
checks; immediate presence/removal is observed for either presentation policy. The local indentation comparison predeclares six balanced AB/BA
fresh-JVM pairs, five warmup round-trips and thirty measured round-trips per JVM,
with per-JVM medians as primary latency/allocation metrics and nearest-rank p95
also reported. Flag a paired primary median ratio above 1.20; keep every raw run
and any failures. See `outputs/issue-97/indentation-flicker/performance-plan.json`
for the recorded comparison plan.

## Compare provisional guide retention

The opt-in `guide-retention` SDK workload replays closing whitespace, tabs at
IDE tab sizes 2/4/8, multiline paste, and body replacement through real document
writes and production listeners. It records immediate renderer presence and
identity without requiring either baseline behavior. Both sides must settle to
the same declared exact guide and source after every forward/restore edit.

The 2026-10-21 comparison uses main `9871336` and a candidate production-source
manifest, an identical harness, six balanced AB/BA fresh-JVM pairs, five warmup
round-trips and thirty measured round-trips per case. EDT write-command time and
allocation are primary; a paired median ratio above 1.20 is flagged. The recorded
return-to-correct interval includes polling and owned-worker quiescence and is
an upper bound on correction, not actual painted stale duration. Inherited
coroutine allocation remains a separate, non-additive scope. See
`outputs/guide-retention-20261021/performance-plan.json` and
`published-summary.json` for the plan and aggregate results. Full captures and
diagnostic logs remain local. A
separate six-pair `guide-retention-scale` comparison covers closing edits with
64 and 4,096 additional body lines, including bounded-repair refusal.

## Track history with Bencher

[The Bencher guide](guide_bencher.md) describes optional standard latency import.
A missing project/token, missing history or changed workload is unavailable
regression evidence, not a pass. No external upload is required for local
validation or authorized by these instructions.
