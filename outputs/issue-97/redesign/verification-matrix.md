# Redesign verification ledger

This ledger records observed results, not intended coverage. Historical issue-97 evidence does not validate the redesigned code.

| Obligation | Current evidence | Status |
|---|---|---|
| SDK-free model/core compilation | pure-compile-01.log; independent tools/pure-build settings | Passed for recorded source snapshot |
| Fresh pure contracts | pure-tests-02.log; checkpoint-01.json; model 4 + core 39, zero failed/skipped | Passed; later additions await rerun |
| Packaging policy mutation tests | packaging-policy-tests-01.log; 3 tests, zero failed/skipped | Passed for initial policy tests; more mutations pending |
| Full integration compilation | integration-compile-03.log and checkpoint-checks-01/03.log | Passed |
| Actual UI/plugin Kotlin and Java isolation | new Gradle TestKit probes and positive controls | Not run |
| Transitive/source/output/compiler-input contamination | new Gradle TestKit mutations | Not run |
| Actual final compiler-input inventory | checkpoint-checks-01.log and checkpoint-01-compiler-inputs.txt | Passed for current snapshot |
| SDK runtime thread/read/cancel/stale/lifetime/render behavior | 15 actual minimum SDK contracts; 3 native authority unit contracts | Passed for covered cases; in-flight/secondary additions pending |
| Minimum/current actual IDE execution and identity | IC-241.19416.15 + JBR 17.0.12 confirmed | Minimum passed; current not run |
| Formatting, bytecode architecture and complete check | Spotless and 4 ArchUnit rules passed | Full root check pending |
| Final release archive and visual bridge separation | actual compiled/instrumented inputs, composed archive and ZIP identity passed | Visual bridge delta pending |
| Supported IDE compatibility matrix | official IntelliJ Plugin Verifier, unchanged eight failure categories | Not run |
| Driver pixel contracts and state transitions | new Starter/Driver suite; original environment and exact comparison policy | Not run |
| Paired latency/allocation/resource comparison | baseline 072533f vs redesign, same workloads/JVM/environment | Not run |
| Qodana inspection | zero-failure gate, local-only execution | Not run |

## Findings being resolved

- Public demand configuration must defensively own disabled-language values.
- Final accepted-result commit must not call SDK methods under its lifecycle lock.
- Document-local reuse generations must remain monotonic independently of mutable SDK stamps.
- Coverage/repair result precedence must preserve useful valid results.
- Cancellation verification must reach late sorting/copying/guide sealing and cache-lock waits.
- Malformed input batches must not produce a seemingly complete partial calculation.
- Compiler and archive guards require intentional contamination tests and positive controls.

## Execution discipline

Only main runs shared builds and measurements. Implementation agents own disjoint files. No simultaneous performance measurements. Before local commits, pause every writer because the configured pre-commit hook stashes unstaged tracked changes. No remote push, pull request, merge, or upload is authorized.
