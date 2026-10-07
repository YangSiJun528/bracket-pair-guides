# Issue 97 implementation record

## Objective

Enforce production module access using Gradle compiler classpaths: platform-neutral model/core, UI with read-only results and request/cancel interfaces, runtime owning execution, and plugin assembly without direct core access. Preserve PR 96 behavior, ADRs and all verification thresholds.

## Starting state

- PR 96 checked via GitHub on 2026-10-07 (Asia/Seoul): OPEN, latest head `84d1a43141b1933c81e027ef01d1579fc4679e6e`.
- Base: that exact head; branch `codex/issue-97-module-isolation` in isolated Codex worktree.
- Original main checkout was clean and remains untouched.
- Issues 93/97 and PR 96 read. No push, PR creation, merge, or external upload authorized.

## Coordination

- Main: design decisions, integration, commits, serialized builds and measurements.
- core_design: read-only model/core investigation; output core-investigation.md.
- runtime_design: read-only UI/runtime investigation; output runtime-investigation.md.
- build_validation: read-only build/verification investigation; output build-investigation.md.
- Workers requested as gpt-6.1-sol / medium. No concurrent Gradle/build/performance jobs.

## Current status

Five production modules are implemented on the exact open PR96 head. Committed sibling history through a8e0bc1 was independently reviewed and reused; none of that sibling's uncommitted user changes was imported or modified. Current local commits restore query range validation, strengthen compiler probes and source-path guards, add reproducible verification runners, and verify packaged class bytes against compiled owner outputs.

Current implementation and deterministic validation are complete. The frozen plugin ZIP is undergoing a fresh serialized 13-target strict compatibility matrix. After that, execute Linux Driver, local Qodana, final root check, then fresh fixture/JMH performance campaigns. Do not run builds, IDEs or measurements concurrently.

Fresh baseline archive and exact base revision are recorded in baseline-location.json. Docker is running and the Linux Driver/Qodana images are ready. No fresh performance measurements have started. reference_validation.md and validation-summary.json describe imported historical results only, including unresolved latency signals; they are not current evidence.

## Validation evidence

- Fresh uncached model/core/plugin tests: 518 passed (6/101/411), no failures, errors or skips. Tests XML is frozen in current-check.
- Fresh SDK-free clean/offline check: 107 pure tests and 16 compiler controls passed. IntelliJ Gradle settings tooling remains configured, but no SDK bytecode reaches either pure compiler.
- Actual Kotlin/Java classpath checks: 55 probes passed, including 34 negative references, 10 generic positive controls and 11 exact implementation owner controls.
- All 13 actual Gradle contamination cases were rejected; clean final graph passed. This includes transitive dependencies, shared output/source roots and typed Java compiler paths.
- Full root check/formatting/plugin structure/configuration passed. Final root check will repeat after the packaging byte-identity guard update.
- Packaging: five ordinary owner jars, 517 classes; every archived class equals its compiled owner bytes. ZIP SHA256 f07ede40f0dce7d34bf6a092780c9349437f50664ed29510c5a640ed30ef3777 (authoritative full hash is in current-check/archive-identity.json).
- Support tools: 21 compiler audit tests, 10 packaging tests, 52 Bencher tests, 5 visual reporter tests passed.
- Current serial verifier raw results are in current-verification/runs/20261007T085741209814Z. Latest IU263.6259.32 actual fixture suite passed all 411 cases with minimum-suite identity parity. Do not infer remaining matrix passes from this progress text.

Compiler restrictions establish structural access boundaries. They do not establish absence of every algorithm or threading bug.


## Current validation update

- Fresh uncached model/core/plugin tests: 518 passed, zero failures/errors/skips (6 + 101 + 411). `current-check/` retains XML; `tests-current.log` command output.
- Local commit `0b452df`: restore nonnegative/ordered range validation lost when TextRange was replaced by primitive offsets.
- Bencher tooling: 52 passed; visual reporter: 5 passed.
- Guard hardening: Java-positive implementation owner controls; explicit empty production Java sourcepath; typed source/bootstrap/processor paths audited; actual source-only archive control. SDK companion sources carry audited bytecode and cannot be recompiled through the empty sourcepath.
- Historical reference_validation.md and validation-summary.json are imported prior-run evidence, not this worktree's current results. Current report will use current-* artifacts.

## Final source before performance freeze

- Local d6226f3 expresses token range validation as `startOffset in 0..endOffset`, resolving the sole Qodana finding without changing valid/invalid range behavior. Emitted bytecode contains primitive comparisons and no IntRange construction.
- Final root check/structure/configuration passed; 55 compiler controls, 517 byte-matched packaged classes and current 518 XML cases are in final-check/.
- Final fresh SDK-free clean/offline run executed all23 tasks,107 tests and16 compiler controls successfully.
- Final Qodana actual exit0 and0 findings, recommended profile/failThreshold0,112 source/build inputs matched. Prior failed scan retained.
- Final Driver13 passed;11 PNGs byte-identical; original baseline hashes unchanged.
- Final ZIP83d4c0a23cdd245b933fb6c663bdddb944a29d1342d01f0d73e72df4631c0da8. Initial13-target matrix/411latest fixtures checked oldf07ede40 archive; rerun final matrix AFTER performance assessment to avoid claiming old bytes cover new bytes.
- Next: freeze candidate fullSHA, run42 real fixture JVM measurements and full46-case JMH for both sides serially. Keep all tracked files and HEAD unchanged during campaigns; ongoing state goes only in untracked artifacts. Then independently assess, finish final compatibility, report and commit.
