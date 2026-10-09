## Fresh full-run signal and predetermined follow-up

Current full JMH evidence at `current-jmh/results` identifies candidate `f7599fa39be7d8e93cc339da719b14012ab1a1dd` against exact PR96 baseline. Independent review passed 46-case evidence integrity. `productionCancellableSort`, ascending size 32,768, increased 29.4287%; conservative delta interval is [0.00339894, 0.01110576] ms/op. One latency case increased beyond reported intervals, two decreased, and 43 remained unresolved. All 46 B/op directions remain unresolved. This signal requires reproducibility assessment; identical implementation alone cannot dismiss it.

Read-only immutable jar inspection found byte-identical `LongArraySortBenchmark.class` and `LongArraySamples.class` across sides. `CancellableLongArraySortKt.class` SHA256 differs, but `javap -c -p` disassembly is identical. This is consistent with Kotlin/module metadata differences; it does not prove equivalent JIT behavior or invalidate timing evidence.

Prepared `run_ascending_followup.py`: exactly two predetermined paired full sort-ascending jobs, candidate→baseline then baseline→candidate. Each of the four fresh invocations retains all eight ascending cases, original full profile, actual JDK17, 2 GiB launcher/fork heap, GC allocation profiler and 240-second duration limit. It imports the existing task-wide lock, source inventory, exclusive-workload, machine/power and fixed-fork guards, verifies fixed revisions and immutable original bundle hashes, and never builds. A fresh separate output directory is required; original results remain immutable.

```sh
python3 outputs/issue-97/current-jmh/run_ascending_followup.py --output outputs/issue-97/current-jmh/ascending-followup-1
python3 outputs/issue-97/current-jmh/run_ascending_followup.py --output outputs/issue-97/current-jmh/ascending-followup-1 --execute
```

The first invocation prints/verifies the plan only. Main owns measurement execution. Report both pair results together with the original; do not repeat until passing, replace the original, or pool unlike parameter cases.
