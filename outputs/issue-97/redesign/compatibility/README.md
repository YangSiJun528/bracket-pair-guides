# Verify the redesigned release

These files prepare and execute compatibility verification only. They do not run
benchmarks or reuse previous fixture counts, reports, matrices, or archives.
Only the main agent executes Gradle and acquires IDEs.

After final production/test sources are frozen, build the release ZIP and run the
existing official `:plugin:minimumSdkTests` and `:plugin:currentSdkTests`. Preserve
both passing XML sets. The current expected suite is 26 unique cases per SDK;
the exact identities must agree. The identity contract records the actual
IC-241.19416.15/JBR and IU-263.6259.32/JBR, rather than treating configured task
properties as evidence of execution.

From the worktree root, prepare fresh metadata and case identities:

```sh
./gradlew --no-daemon --no-parallel --max-workers=2 --no-configuration-cache \
  --init-script outputs/issue-97/redesign/compatibility/serial-verifier.init.gradle \
  :plugin:recordSerialVerifierMatrix
python3 outputs/issue-97/redesign/compatibility/run_serial_verifiers.py --freeze-testcases
python3 outputs/issue-97/redesign/compatibility/run_serial_verifiers.py
```

The init script skips included `build-logic` and validates the six root projects
(five production modules and benchmarks). It freezes fresh `recommended()` plus
all four explicit declarations, eight strict failure levels, source hashes and
the current `buildPlugin` ZIP hash. A recommendation count change is recorded
without dropping targets: review the entire fresh selection, then explicitly set
`--expected-target-count` to its reviewed count. The default is 13. Existing
metadata is never overwritten; use a new `-PverifierMatrixPath`/`--matrix` and
`--testcases` path if a previously frozen source/archive changes.

After reviewing the plan, execute serially:

```sh
python3 outputs/issue-97/redesign/compatibility/run_serial_verifiers.py \
  --execute --cleanup-created-ides
```

Execution runs the existing official minimum/current SDK tasks once more against
these frozen sources, checks all 26 identities and actual IDE/JBR evidence, then
runs the official `verifyPlugin` task on every frozen target. Each verifier task
receives exactly one existing, product/version-validated selected IDE path. No
new runtime test harness or alternate classpath is installed. All commands,
raw logs, XML, input copies/hashes and verdicts remain under `runs/`. Any failed,
skipped, incomplete or mismatched suite prevents acceptance. The ZIP and source
hashes must remain unchanged across acquisition and every task.

Acquisition is sequential and uses the existing official platform resolver. With
the cleanup option, only the exact selected transform and target installers that
were absent immediately before that acquisition are eligible for deletion.
Pre-existing cache entries, SDK-task acquisitions, unclassified new roots and
failed verification artifacts are preserved. Active consumers preserve eligible
artifacts too; process command lines are inspected in memory and never recorded.
A successful verifier verdict does not replace runtime, Driver or performance
validation. Do not run other Gradle/IDE consumers while this runner owns acquisition
and cleanup. The runner also holds `redesign/verification.lock` against another
instance of itself.
