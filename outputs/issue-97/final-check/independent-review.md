# Independent final-source evidence review

Reviewed available deterministic evidence after final production change d6226f3 (equivalent token-range predicate), without running builds, Docker or performance measurements. Only this review file written.

## Confirmed final-source results

- check-current-final.log records a successful root check (49 tasks:17 executed,32 up-to-date). The changed core's101 tests and plugin's411 tests executed; model6 tests were up-to-date in that invocation and were subsequently freshly executed in the clean pure run. Independently summing the final-check copied XML gives518 tests,0 failures/errors/skips, matching tests-summary.json. Formatting, packaging, physical compiler audit and verification-support tasks are included in the successful root check. This is not described as49 fresh task executions.
- final-check/results.json contains55 probes:34 forbidden Kotlin/Java symbols unavailable,10 general positive controls and11 exact owner Java controls available. Actual final compile project graphs restrict UI to model and exclude core/runtime; plugin compile projects include model/UI/runtime and exclude core. Every manifest uses explicitly specified empty javac sourcepath with empty typed bootstrap/processor paths. Ownership/classpath audit passed.
- sdk-free-final-source.log records BUILD SUCCESSFUL with23 tasks executed after clean. Independently summed pure XML has107 tests (model6/core101),0 failures/errors/skips. Its manifest contains only model/core;16 probes pass, including8 owner controls and4 IntelliJ-unavailable references. This proves SDK-free pure compilation/test inputs; root settings still use IntelliJ Gradle settings tooling and repositories, so it does not assert the complete absence of IntelliJ build tooling.
- final-check/packaging.json confirms5 ordinary owner jars,517 classes, exact single ownership and compiledClassBytesMatch=true using SHA256. The517 stored per-owner class hashes cover model13/core72/UI258/runtime174/plugin0. Descriptor/icon/license policies and minimum241 remain preserved. Final ZIP identity is83d4c0a23cdd245b933fb6c663bdddb944a29d1342d01f0d73e72df4631c0da8.
- current-verification/qodana-result-final.json reports actual exit0,0 findings, recommended profile and threshold0. Independently reading its SARIF confirms successful invocation exit0 with0 results; recorded SARIF SHA256 matches its file. All112 recorded production input hashes match current source bytes, including the changed core predicate. Thus the corrected-source Qodana result is not the previous1-finding failure. Supplied five-module source scope is verified; zero-result SARIF does not enumerate every inspected file.

## Results that must remain pending

The initial13-target matrix and411 latest-IDE runtime fixtures passed only for archive f07ede40f0dce7d34bf6a092780c9349437f50664ed29510c5a640ed30ef3777. They cannot establish final archive83d4c0a2 compatibility, despite the source predicate being equivalent. Final13-target strict matrix/latest runtime revalidation is pending at this review.

Final Driver rerun is in progress; no final-source Driver pass is claimed here. Current performance work must use its own final-source provenance, raw observations and assessment; ordinary test results or old sibling performance results are not final performance evidence. Earlier13 genuine Gradle contamination injections prove the unchanged compiler-audit build configuration and are retained under their original run/source context rather than mislabeled new executions.

No remaining deterministic implementation gap identified from the available evidence. Compiler isolation, packaging and tests do not prove absence of all threading, cancellation or algorithm defects, and do not replace outstanding final Driver/matrix/performance validation.

## Dated final Driver confirmation —2026-10-07 UTC

The earlier pending-Driver paragraph records the review state before completion. Final Driver is now independently confirmed from build/visual-test-background/run.xAD2OD and current-verification/driver-result-final.json. Re-read actual exit-code.txt (0), JUnit XML (13 tests,0 failures/errors/skips), production coreVisualScenariosMatchExactBaselinesInOneIdeSession execution, geometry/Starter/environment records and the successful Gradle log. All11 actual scenario PNG hashes match committed baselines byte-for-byte; committed baseline hashes match the preserved pre-run snapshot. Actual environment remains pinned Linuxx86_64/IC2024.2.6-build242.26775.15/Java21/Darcula/crop220x239. This is final-source Driver evidence, not the earlier sibling or pre-range-change run.

Final archive83d4c0a2 strict matrix is currently in progress under current-verification/runs/20261007T112724798906Z. Its independent partial review is current-verification/matrix-final-independent-review.md;2/13 completed targets confirmed at this snapshot,11 pending. No final full-matrix or latest-runtime411 pass is claimed before completion.

## Final matrix completion —2026-10-07 UTC

All earlier pending-matrix statements above are historical snapshots. Final run current-verification/runs/20261007T112724798906Z is now complete:13/13 strict Compatible reports for unchanged final83d4c0a2 archive and unchanged8 failure levels; latest IU263.6259.32 actual selected IDE/JBR fixtures411 pass with exact case parity. All70 frozen input hashes and owned-only20 acquired artifact cleanup records were independently checked; all final summary pending lists are empty. Full final independent review: current-verification/matrix-final-independent-review.md. This does not change the separate unresolved performance signals documented by the performance reviewers.
