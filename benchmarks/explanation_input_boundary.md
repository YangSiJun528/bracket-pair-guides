# Why the recorded prefix boundary stays out of line

The calculation benchmark uses a synchronous recorded input to stand in for IDE
capture. `RecordedInput.initialPrefix` can be inlined into the repair loop, where
HotSpot can remove the returned `PrefixChunk` and the repair continuation. Real
`EditorSource` capture goes through an IntelliJ `readAction`; this benchmark does
not measure that capture implementation or its scheduling cost.

Inlining this unusually simple substitute made allocation history depend on a
JIT decision. In PR #110, the main and PR benchmark JARs were byte-identical
(SHA-256 `09d7432a9df815b02962b59d688a6d977706529bebbfddce9bd498f2e22a43cb`).
They contain no editor UI code. Nevertheless, `repair(sparse, 64)` changed from
5,180.02 to 6,776.02 B/op and tripped the 20% history threshold. The preceding
PR #109 had already measured 6,796.02 B/op on the same runner as the low main
measurement, so neither the UI change nor runner identity explains the split.

## Diagnostic evidence

The archived JAR was run with the pinned CI JDK image, two fresh JVMs, the original
two one-second warmups, and twelve one-second measurements per JVM. Local runs
used amd64 emulation on an ARM host; their latency is not native performance
acceptance evidence.

| Condition | Allocation in both JVMs | JIT log |
| --- | ---: | --- |
| Original inlining policy | about 5,160 B/op | Prefix chunk and repair continuation eliminated |
| Only `RecordedInput.initialPrefix` kept out of line | about 6,816 B/op | Neither allocation eliminated in repair |

Instrumentation measured 24 bytes per prefix chunk and 56 bytes per repair
continuation. This input visits 65 lines: `65 × 24 + 56 = 1,616 B/op`. That explains
the major split. A separate 40 B/op fork variation is not attributed to those
objects; the observed allocation levels include both 5,160/5,200 and 6,776/6,816.
The original CI runs have no compilation log, so the diagnostic establishes a
reproduced mechanism rather than identifying their exact compiled methods.

The final shared settings also completed all eight repair cases at the original
CI geometry. A separate six-fork check without compilation logging measured
6,816.03, 6,816.03, 6,776.03, 6,776.03, 6,816.03 and 6,776.03 B/op
(fork means, rounded). The maximum/minimum difference was 0.59%, rather than
the historical approximately 31% split. This is local stability evidence; the
remote main/PR comparison is still required.

## Measurement contract

`runner/jvm.args` keeps only the recorded `initialPrefix` entry point out of line
using HotSpot's `CompileCommand=dontinline`. This applies to every workload and
size calling that method, without changing its work, returned objects, result
consumption, or production code. Other compiler optimizations remain enabled.
The same arguments are used by Gradle, the local CI fallback and the remote
guest. Both converters reject old or overridden JVM arguments.

This intentionally changes the measurement model; the larger allocation is not
a production regression or a performance improvement. The new testbed
`bencher-intel-v1-jdk17-input-v2` separates its history from the old testbed.
Main must be independently measured under these settings before a PR can have a
valid comparison. Old history and failures remain available. Thresholds stay at
20%, latest sample size stays at one, and the 240-second per-job limit is unchanged.
Missing history still fails the gate.

Compiler control is supported by the [Java 17 launcher](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#advanced-jit-compiler-options-for-java)
and demonstrated in [JMH's compiler-control sample](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_16_CompilerControl.java).
It defines this synthetic input boundary; it does not make the benchmark an IDE
latency or rendering benchmark.
