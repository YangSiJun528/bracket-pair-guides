# Final independent JMH review

Conclusion: **do not report “no regression passed.”** The original +29.4287% latency increase remains valid evidence. It did not reproduce in the two predetermined balanced repetitions; this reduces confidence that the increase is persistent but does not prove performance equivalence or identify its cause. Report execution/coverage success and the original signal with the nonreproducing follow-up separately.

## Evidence identity and integrity

- Candidate: `f7599fa39be7d8e93cc339da719b14012ab1a1dd`; baseline: `84d1a43141b1933c81e027ef01d1579fc4679e6e`.
- Original complete run: `current-jmh/results`; follow-up: `current-jmh/ascending-followup-1`. Original evidence remains untouched.
- Four predetermined invocations completed, each with all eight ascending cases and exit zero. Runtime durations were 83.546, 83.585, 83.547 and 83.535 seconds, each within the unchanged 240-second limit.
- Independently verified every retained raw artifact hash and original immutable bundle copy, actual JDK 17.0.17 fork executable and 2 GiB heap, 2 forks/2 warmups/3 measurements at one second, B/op profiler, machine/external-work guard success, exact eight-case identities and summary recomputation from raw metrics.
- Follow-up script validates complete recorded source/build fingerprints before each invocation and afterward. The original full-run independent review passed 46-case source provenance/coverage checks.

## Original signal and each additional pair

`productionCancellableSort`, distribution ascending, size 32,768. Latency and delta intervals below are in ms/op. B/op uses normalized direct allocation, not MB/sec.

| Run and fixed order | Baseline latency (reported CI) | Candidate latency (reported CI) | Change | Conservative delta interval | B/op delta (interval) |
|---|---|---|---|---|---|
| Original full suite | 0.02464378 [0.02356892, 0.02571864] | 0.03189613 [0.02911758, 0.03467468] | **+29.4287%** | **[0.00339894, 0.01110576]** | +0.073320 [-0.038255, 0.184895] |
| Pair 1: candidate→baseline | 0.02343664 [0.02236795, 0.02450533] | 0.02377409 [0.02314484, 0.02440334] | +1.4398% | [-0.00136048, 0.00203539] | -0.001642 [-0.107791, 0.104507] |
| Pair 2: baseline→candidate | 0.02347592 [0.02216894, 0.02478290] | 0.02336419 [0.02255123, 0.02417715] | -0.4759% | [-0.00223167, 0.00200822] | -0.003589 [-0.118685, 0.111507] |

Both additional pairs have latency intervals containing zero for the original flagged case, at +1.4398% and −0.4759%. Normalized allocation directions also remain unresolved. Across each pair, all eight latency and all eight allocation delta intervals contain zero. Largest observed positive latency changes across the eight cases were +1.4398% in pair 1 and +3.2415% in pair 2; neither exceeds the existing 20% Bencher latency boundary. These local comparisons do not execute Bencher’s external historical gate.

## Complete ascending follow-up case review

Each row retains its own pair and parameter case; no pooled timing or averaging across cases is used. Interval subtraction is a conservative direction aid, not a new equivalence or hypothesis test.

| Pair | Method | Size | Latency change | Latency delta interval (ms/op) | B/op delta | B/op delta interval |
|---|---|---:|---:|---|---:|---|
| 1 | jdkSort | 1000000 | +0.0115% | [-0.025237058, 0.025279958] | +0.01379297 | [-3.689227, 3.716813] |
| 1 | jdkSort | 200000 | -0.0965% | [-0.0012826833, 0.0012133113] | -0.002077357 | [-0.1992655, 0.1951108] |
| 1 | jdkSort | 2000000 | -0.0106% | [-0.0018704307, 0.0017906912] | -0.01093887 | [-0.4631325, 0.4412548] |
| 1 | jdkSort | 32768 | -0.0144% | [-5.143569e-05, 4.9741529e-05] | +0.0003235799 | [-0.006480371, 0.007127531] |
| 1 | productionCancellableSort | 1000000 | +0.1525% | [-0.049592268, 0.057748852] | +0.02835289 | [-0.9402806, 0.9969864] |
| 1 | productionCancellableSort | 200000 | -0.1395% | [-0.02099591, 0.019919603] | -0.009430269 | [-0.1154122, 0.09655167] |
| 1 | productionCancellableSort | 2000000 | -0.1645% | [-0.60036868, 0.58346325] | +0.094248 | [-3.74959, 3.938086] |
| 1 | productionCancellableSort | 32768 | +1.4398% | [-0.0013604844, 0.0020353892] | -0.001642029 | [-0.107791, 0.104507] |
| 2 | jdkSort | 1000000 | -0.1362% | [-0.0248029, 0.024293948] | +0.002317541 | [-3.709106, 3.713741] |
| 2 | jdkSort | 200000 | -0.0025% | [-0.0010003563, 0.00099853294] | -0.004105152 | [-0.225257, 0.2170467] |
| 2 | jdkSort | 2000000 | -0.0922% | [-0.0036418524, 0.0029497086] | -0.02891778 | [-0.2666433, 0.2088078] |
| 2 | jdkSort | 32768 | +0.3257% | [-7.5155615e-05, 0.00011342307] | -0.001895653 | [-0.01161571, 0.007824407] |
| 2 | productionCancellableSort | 1000000 | -17.5128% | [-1.929679, 0.99042614] | -2.762643 | [-11.54641, 6.021121] |
| 2 | productionCancellableSort | 200000 | +0.2589% | [-0.012410437, 0.014408406] | +0.003342716 | [-0.1368173, 0.1435028] |
| 2 | productionCancellableSort | 2000000 | +3.2415% | [-0.64309273, 0.96785305] | +1.273206 | [-3.771757, 6.318169] |
| 2 | productionCancellableSort | 32768 | -0.4759% | [-0.0022316709, 0.0020082156] | -0.003588711 | [-0.1186845, 0.1115071] |

Pair 2’s production sort at size 1,000,000 has a wide candidate latency interval [0.79285194, 3.63112970] ms/op around a 2.21199082 point estimate. Its −17.5128% point change must not be advertised as a reliable improvement.

## Attribution and limits

Original immutable jar inspection found identical benchmark and sample generator class bytes. Production sorter class bytes differ, but `javap -c -p` method disassembly is identical. This narrows plausible implementation causes but does not exclude module metadata, JIT compilation, GC, execution history or environmental variation. No cause was established, and identical disassembly does not cancel the original signal.

The two balanced repetitions were fixed before execution, not repeated until passing. They provide reproducibility evidence only for the eight ascending sort cases. Other full-suite cases still have one suite per revision. Each suite’s two JVM forks are not additional independently scheduled suites. Confidence intervals containing zero establish neither equality nor absence of smaller regressions.

JMH covers pairing, primitive sorting, cancellation and preference normalization. It excludes IntelliJ capture/read locks, native inspection, guide repair, coroutine/publication work, indexed snapshot queries, rendering and end-to-end IDE latency. Separate current IDE fixture, allocation, Driver and runtime evidence remains required. Structural module access restrictions are not evidence that all algorithm/thread bugs are absent.

Suggested final wording: “Full JMH execution and case coverage passed. An initial ascending-sort case increased 29.43%; two predetermined balanced repetitions measured +1.44% and −0.48% with intervals spanning zero, so the original increase was not reproduced. No interval-separated B/op increase was detected in these runs. This does not establish performance equivalence.”
