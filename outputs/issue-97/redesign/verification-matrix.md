# Redesign verification ledger

Current HEAD: `8ea07aa86cf939d9a5e945bec180079ecc28eb60`. The listener/session
ownership fix passed current actual minimum/current SDK29, root check06 and
Qodana04 and Driver12. Fresh whole13IDE matrix02 passed13/13 strict targets; formal performance
has not run. Each outcome remains scoped to its own retained source manifest.

This ledger records observed execution. A previous snapshot, skipped task or setup smoke is not a current full pass. See individual raw failures and commands rather than discarding earlier attempts.

| Obligation | Latest evidence | Status |
|---|---|---|
| SDK-free model/core | final-check-04/pure.log fresh45; final-check-06/pure-source-parity.json exact selected-file parity | PASS45, zero failed/skipped |
| Pure contracts | final-check-06 matching XML; model4+core41, including malformed token/prefix rejection and valid reuse afterwards | PASS45 |
| Actual Kotlin/Java compile restriction and positive controls | final-check-06 CompilationBoundaryTest32 | PASS32; actual UI core/runtime negatives, plugin-core and pure-SDK negatives included |
| Transitive/source/output/compiler-input bypass rejection | same32 suite and effective actual serialized plugin-input audit | PASS; original real serialization-plugin bypass/failure retained in earlier evidence |
| Packaging and benchmark-report policy | final-check-06 PackagedOwnerTest9 + BenchmarkReportTest9 | PASS18 |
| Module policy/bytecode architecture | final-check-06 UI3+runtime3+plugin architecture4 | PASS10 |
| Actual runtime/read/cancel/stale/lifetime/markup | listener-lifetime-01 actual minimumSdkTests; IC241.19416.15/JBR17 | PASS29; includes two actual editors sharing one document with independent close/publication |
| Complete root check and formatting | final-check-06 check.log/summary.json; current source | PASS134;583.4669s/zero fail-error-skip/sourceUnchanged=true; task reuse recorded |
| Release/visual archive separation | listener-lifetime-01 package audit | PASS451 classes; no duplicate owner classes or test leakage; current release609f09d3.../visual1bbb6cb4... |
| Current actual IDE execution | official currentSdkTests, expected29 identities | PASS29 listener-lifetime-01; zero fail/error/skip and same minimum identities |
| First supported IDE compatibility matrix | compatibility/runs/20261008T115340939459Z, old be4f620/ZIP b1442ca6... | FAILED exit1/1490.48s: IC5 strict PASS, IU4 same deprecated listener FAILED, remaining4 disk-guard NOT RUN |
| Supported listener fix | compatibility/document-listener-fix/proposed-session-ownership.patch applied exactly to three production files | Applied; actual min/current29 +check06 +Qodana04 PASS; fresh entire official13IDE matrix02 PASS13/13 strict (`20261008T125349781364Z`) |
| Task-owned cache retirement | compatibility/owned-cache-retirement-01; reports/product information/hashes preserved | Task-created IDE3/installers3 removed only; pre-existing none deleted;23.34GiB recovered |
| Driver rendering/state transitions | driver/compare-12/verification.json and command.json | PASS12 exact ARGB/PNG bytes and functional assertions;239.8648s/sourceUnchanged=true; unchanged pixel criteria |
| Driver production equivalence | driver/compare-12/production-equivalence.json | All production class/resource bytes equal; whole host/Linux ZIPs differ only through plugin JAR manifest Build-JVM/Build-OS metadata |
| Visual negative control | driver/mutation-08/verification.json, earlier source | Intentional exit1/JUnit failure: nine expected paint comparisons detect removal; not a passing mutation suite |
| Fresh entire supported IDE matrix02 | compatibility/runs/20261008T125349781364Z/summary.json; compatibility/serial-command-02.json | PASS13/13 strict on exact release609f09d3.../source8ea07aa; exit0/2690.359957s; pendingTargets=[] |
| Actual SDK rerun inside matrix02 | same run summary.json and retained SDK XML | PASS minimum29/current29, identical identities; sdkTasksPassed=true/minimumCurrentSameCases=true |
| Local static inspection | qodana/run-04/summary.json; earlier failures preserved | PASS0 findings/358.0148s/sourceUnchanged=true/failThreshold0 |
| Same-condition performance/allocation | pure semantic fingerprint01; earlier SDK smoke04 all16; post-reuse execution smoke | Formal NOT RUN; final common/jar proof and setup refresh required |

## Preserved failed evidence

- First official compatibility run20261008T115340939459Z failed on four IU deprecated API usages after five IC strict passes; four further targets were blocked by the disk guard. Original reports remain preserved. The new fix has SDK/root/static passes but is not yet a passing official compatibility matrix result.

- Initial compilation-boundary run:26/32, followed by targeted fixes and actual serialization-plugin injection proof; full32 subsequently passed.
- Driver capture01/advisory, capture02/settings, capture03/focus and compare05/native-markup failures remain preserved; compare06 passed after real SDK native-key readiness. This does not prove universal race freedom.
- Isolated SDK smoke03 rejected original descriptors still visible through test resources. Descriptor-only overlays byte-verify all other resources; smoke04 passed16 commands.
- Endpoint reuse test01 failed due a fixture colorscheme clone; test02 passed28 and added unchanged markup/range-marker identity plus independent theme/range coverage.
- Driver compare07 failed horizontal semantic text pixels; compare09 failed readiness during delayed JDK indexing; compare10 failed three old SDK-decoration oracles. Unchanged baseline072533f with the same suite produced all12 images exactly equal to candidate10. Only three oracles were deliberately corrected after independent review, with old images preserved; nine were unchanged. Compare11 passed exact12. Mutation08 deliberately failed nine expected paint comparisons, preserving all functional assertions.
- Qodana run01 failed74. Full-check03 separately failed SDK comparison compilation because K2 inspection had called K1-required assertions redundant; stable local owners compile in final-check04.
- Qodana-fixes02 root check exposed a wrong included-build identity before execution; root dependency now resolves `build-logic`.

## Interpretation

Fresh unit and SDK contracts replace old suites. Structural, runtime, visual, resource and performance claims remain separate. Current root snapshot: `8ea07aa86cf939d9a5e945bec180079ecc28eb60`; check06 and current SDK/static/Driver evidence apply to their retained manifests. The minimum and current SDK suites use actual host classes/bootstrap and passed29 identical case identities; the first official13-target matrix failed/incomplete as recorded above, and the fresh entire matrix02 for the three-file fix passed all13 strict targets on release609f09d3..., with separate actual minimum/current29 reruns and identical identities. Baseline production is immutable, helpers additive, common source equality is enforced. Formal performance, its analysis and the final report remain outstanding. No timings from setup are accepted as formal performance. No tolerances, thresholds, trial counts or failure categories were reduced.

Only main executes shared builds and measurements; implementation agents own disjoint files. All remote publication, uploads and jobs remain prohibited. Local CI file installation alone was explicitly approved.
