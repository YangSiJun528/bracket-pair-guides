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

Five production modules are implemented on the exact open PR96 head. Committed sibling history through a8e0bc1 was independently reviewed and reused; none of that sibling's uncommitted user changes was imported or modified. Local commits restore query range validation, harden compiler-path/probe guards and exact packaged-byte verification, and preserve reproducible validation runners.

Final-source root check, SDK-free clean tests, Driver, Qodana and packaging passed. Fresh paired fixture measurements (42 accepted commands) and full JMH (46 cases per side plus two predetermined ascending follow-up pairs) completed. Repeated fixture latency/allocation signals remain unresolved: measurement completeness is not a no-regression pass. See `current_validation.md` and the independent performance reviews.

The final archive `83d4c0a2…` passed the complete 13-target strict matrix and latest-IU 411-test suite in `current-verification/runs/20261007T112724798906Z/` (actual executor exit0). No pending targets remain. Initial old-archive results are not used as a substitute. Production source remains at d6226f3; performance froze f7599fa. Final consistency verifies all241 measured inputs,517 compiled class hashes and the same ZIP; original checkout remains clean.

Implementation and requested local verification execution are complete. Final report: `current_validation.md`; fixture/JMH assessments retain unresolved latency/allocation signals and do not claim performance equivalence. No cause for the measured shifts was established; no speculative production change was made to make measurements pass. No benchmark thresholds, test coverage or screenshot baselines were weakened.

`reference_validation.md` and `validation-summary.json` are imported historical evidence. This run's current/final artifacts are authoritative. The original checkout and dirty sibling remain untouched; no external submission occurred.

## Final source before performance freeze

- Local d6226f3 expresses token range validation as `startOffset in 0..endOffset`, resolving the sole Qodana finding without changing valid/invalid range behavior. Emitted bytecode contains primitive comparisons and no IntRange construction.
- Final root check/structure/configuration passed; 55 compiler controls, 517 byte-matched packaged classes and current 518 XML cases are in final-check/.
- Final fresh SDK-free clean/offline run executed all23 tasks,107 tests and16 compiler controls successfully.
- Final Qodana actual exit0 and0 findings, recommended profile/failThreshold0,112 source/build inputs matched. Prior failed scan retained.
- Final Driver13 passed;11 PNGs byte-identical; original baseline hashes unchanged.
- Final ZIP83d4c0a23cdd245b933fb6c663bdddb944a29d1342d01f0d73e72df4631c0da8. Initial13-target matrix/411latest fixtures checked oldf07ede40 archive; rerun final matrix AFTER performance assessment to avoid claiming old bytes cover new bytes.
- Completed measurement freeze: candidate f7599fa39be7d8e93cc339da719b14012ab1a1dd versus baseline84d1a43141b1933c81e027ef01d1579fc4679e6e.42 accepted fixture JVM commands; full46-case JMH each side;4 additional predeclared ascending invocations. Original/rejected evidence retained.
- One fixture attempt was rejected when an otherwise idle daemon exceeded the coordination limit. Exact daemon33144 identity and independent IDLE state were verified before graceful SIGTERM; worker33413 exited. All84 accepted observations recorded both absent. No file/cache deletion or benchmark threshold relaxation was used.
- Independent fixture and JMH reviews are complete; repeated fixture signals remain, and original29.43% JMH sort increase did not reproduce in two balanced repetitions(+1.44%/−0.48%). Do not describe overall performance as a pass.

## Completion evidence

- Final matrix13/13 strict passes, all8failurelevels; actual latestIU263.6259.32 fixture411/411 with exact testcase parity and selected bundled JBR.
- Final root518 cases, SDK-free107 cases, compiler55 controls,13 genuine bypass injections, packaging517 classes, Driver13 cases/11 exact PNGs, Qodana0 findings.
- Seven fixture workloads ×3 paired fresh JVMs=42 accepted commands; full46-case JMH on each side plus two fixed ascending follow-up pairs. Provenance and completeness confirmed; no overall performance pass claimed.
- Raw evidence retained locally with SHA256 inventory. Local commits only; original main checkout and dirty sibling work preserved. No remaining local verification task.
