# Why calculation measurements control JIT compilation

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

The input-v2 settings also completed all eight repair cases at the original
CI geometry. A separate six-fork check without compilation logging measured
6,816.03, 6,816.03, 6,776.03, 6,776.03, 6,816.03 and 6,776.03 B/op
(fork means, rounded). The maximum/minimum difference was 0.59%, rather than
the historical approximately 31% split. The native comparison below checks this
measurement policy independently.

## Compilation scheduling and warmup

The input-v2 native comparison fixed the original allocation alert:
`repair(sparse,64)` measured 6,776.0193 versus 6,776.0195 B/op and 3,262 versus
3,278 ns/op. All eighty metric baselines matched the independently measured
main values. However, `analyzeReuse(sparse,4096)` latency was 509.07 versus
649.84 microseconds, exceeding the unchanged threshold. On the same runner,
one candidate JVM measured about 750 microseconds and the other about 500.
Allocation was nearly unchanged. Earlier runs of the identical JAR also had
different speeds between JVMs.

A six-JVM local diagnostic retained the split after twelve measurement seconds;
more warmup alone did not remove it. Keeping every recorded-input method out of
line reduced but did not remove the spread, so that broader control was not
adopted. Keeping the narrow prefix boundary and adding `-Xbatch` removed the
persistent split in the local diagnostic. This option waits for JIT compilation
instead of compiling concurrently with measured work. Logs showed C2 compilation
of `CalculationAttempt.analyze` finishing around 5.9–6.5 seconds after JVM start,
beyond the original two warmups. Compilation still contaminated the early
measurement iterations, so input-v3 uses six one-second warmups.

With that final policy and no diagnostic logging, six new JVMs measured
556,355, 552,327, 557,948, 579,097, 576,694 and 562,506 ns/op (fork means, rounded),
a 4.85% maximum/minimum spread. Allocation was about 529,619 B/op in all six.
These are emulated diagnostic observations, not proof of the original native
JIT decisions or native performance acceptance. The new policy requires another
independent native main/PR comparison. Input-v2 failures remain recorded.
The complete eight-case repair job also finished in 2 minutes 50 seconds under
the original 240-second limit, with matching allocation levels in both forks
for every case.

## Measurement contract

`runner/jvm.args` keeps only the recorded `initialPrefix` entry point out of line
using HotSpot's `CompileCommand=dontinline`. This applies to every workload and
size calling that method, without changing its work, returned objects, result
consumption, or production code. Other compiler optimizations remain enabled.
The same arguments, including `-Xbatch`, are used by Gradle, the local CI fallback
and the remote guest. Both converters reject old or overridden JVM arguments
and insufficient warmup. This measures warmed calculation work; it does not
measure application startup or background-compiler contention in an IDE.

This intentionally changes the measurement model; the larger allocation is not
a production regression or a performance improvement. The new testbed
`bencher-intel-v1-jdk17-input-v3` separates its history from both earlier testbeds.
Main must be independently measured under these settings before a PR can have a
valid comparison. Old history and failures remain available. Thresholds stay at
20%, latest sample size stays at one, and the 240-second per-job limit is unchanged.
Missing history still fails the gate.

Compiler control is supported by the [Java 17 launcher](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#advanced-jit-compiler-options-for-java)
and demonstrated in [JMH's compiler-control sample](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_16_CompilerControl.java).
It defines this synthetic input boundary; it does not make the benchmark an IDE
latency or rendering benchmark.
