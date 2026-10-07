# Issue 97 current implementation and verification

This report describes the isolated `codex/issue-97-module-isolation` worktree. It supersedes the imported historical `reference_validation.md` / `validation-summary.json` for current-run claims. Status: verification in progress; unexecuted checks below are not passes.

The first Qodana run found one range-check style issue. The equivalent Kotlin range expression is now used and final root check passed. This changes one class and the archive SHA to `83d4c0a23cdd245b933fb6c663bdddb944a29d1342d01f0d73e72df4631c0da8`. The first 13-target matrix and Driver result below describe the previous archive; Driver has now passed again for the final source; the strict matrix will be rerun after performance assessment freezes the implementation. See final-check for updated compiler/packaging/test evidence.

## Base and preservation

PR #96 was OPEN at task start. This branch starts from its then-latest head `84d1a43141b1933c81e027ef01d1579fc4679e6e`. The committed five-module implementation through `a8e0bc1` was independently reviewed and reused. The original main checkout and another dirty issue-97 worktree were not modified. All changes are local; no push, PR, merge, submission or source upload was performed.

## Production dependency boundary

| Module | Permitted project compile dependencies | Responsibility |
| --- | --- | --- |
| analysis-model | none | Platform-free values, opaque identity stamps and read-only result contracts |
| analysis-core | analysis-model | Pairing, immutable index construction and guide calculations |
| editor-ui | analysis-model | Editor lifecycle/state, presentation and request/cancel contracts |
| analysis-runtime | analysis-model, analysis-core, editor-ui | IntelliJ capture, execution, cancellation, validation and publication coordination |
| plugin | analysis-model, editor-ui, analysis-runtime | Descriptor registration, resources and packaging |

Core is an implementation dependency of runtime and is absent from plugin compilation. Both core and runtime implementations are absent from actual UI Kotlin/Java compiler libraries. The read-only `BracketSnapshot` / `TokenWindow` contract exposes indexed result queries without builders, concrete indexes or capture/calculation entry points. Lightweight UI identity checks preserve stale-result rejection; proportional input capture remains runtime-owned. Test-only friend paths and benchmark access are explicitly separated from production compilation.

The final distribution uses five ordinary `lib/` owner jars for the minimum 241 classloader. Plugin itself has no production classes; descriptor classes are resolved from the owner jars. New local fixes restore invalid token-range rejection and strengthen verification of actual compiler source paths and packaged class bytes.

## Current deterministic evidence

| Verification | Current result | Evidence |
| --- | --- | --- |
| Fresh model/core/plugin tests | 518 passed; 0 failure/error/skip | check-current-final.log; final-check/tests-summary.json and XML |
| SDK-free clean offline build/tests | 107 passed; 16 compiler controls; fresh execution | sdk-free-final-source.log; sdk-free-final-source/ |
| Actual compiler probes | 55 passed: 34 forbidden references, 10 generic positives, 11 owner positives | final-check/results.json; classpaths.json |
| Actual Gradle bypass injections | All 13 rejected with intended reason; clean graph passed | current-contamination/run-20261007T085251Z/results.json |
| Packaging | Five jars; 517 classes exactly once; all archived class bytes match compiled owners | final-check/packaging.json |
| Strict compatibility | Initial archive 13/13 passed; final archive rerun pending | current-verification/runs/20261007T085741209814Z/summary.json |
| Latest IU runtime | Initial archive 411 passed with case parity; final archive rerun pending | matrix run runtime-fixtures/IU-263.6259.32/summary.json |
| Driver visual test | 13 passed; 11 actual PNGs byte-identical; baseline hashes unchanged | current-verification/driver-result-final.json |
| Python support tests | 21 compiler audit + 10 packaging + 52 Bencher passed | current-check/independent-review.md; bencher-tests-current.log |
| Visual reporter tests | 5 passed | reporter-tests-current.log |
| Final root check after packaging/range guard | Passed | check-current-final.log; final-check/ |
| Qodana recommended / threshold 0 | Passed: actual exit 0; 0 findings; 112 source/build inputs matched | current-verification/qodana-result-final.json |

Initial release ZIP SHA256: `f07ede40f0dce7d34bf6a092780c9349437f50664ed29510c5a640ed30ef3777`. Every compatibility target checked this exact archive before and after. Final deterministic review: final-check/independent-review.md. The initial matrix review is current-verification/matrix-independent-review.md and remains scoped to its old archive hash.

The compiler guard checks resolved archives and actual Kotlin/Java libraries, typed source/bootstrap/processor paths, source/output roots, compiler options and friend paths. Negative experiments include direct/transitive dependencies, shared outputs/sources, source-only archives and alternate compiler source/module paths. Owner-positive controls prevent missing or renamed forbidden symbols from falsely proving isolation.

## Performance and allocations

Fresh comparison is pending. The exact PR96 archive is recorded in baseline-location.json. Both sides will use the same existing incremental fixtures, JVM settings, corpus, warmup/sample counts and power settings. Seven fixture workloads each receive three fresh alternating JVM pairs. JMH uses the existing full 46-case profile, two forks, GC allocation profiling and unchanged 240-second per-job gate. No historical measurement will be relabeled as current.

## Interpretation limits

Compiler restrictions prove the tested structural access boundary, not the absence of all threading, cancellation or algorithm bugs. The ordinary fixture suite includes disabled-by-default measurement tests; their successful ordinary execution alone does not prove performance. Runtime tests and Driver checks cover recorded scenarios rather than every supported IDE's complete UI behavior. Compatibility verification is static; actual runtime execution is separately recorded for minimum fixtures, pinned Linux Driver and latest IU fixtures.

The pure profile compiles/tests without IntelliJ SDK bytecode. The settings script still loads IntelliJ Gradle settings tooling. Packaging resource checks cover required resources and descriptor rules, while class identity is checked byte-for-byte. Remote CI and Bencher submissions are not executed; external submission was not authorized.
