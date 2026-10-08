# Redesign verification ledger

This ledger records observed execution. A previous snapshot, skipped task or setup smoke is not a current full pass. See individual raw failures and commands rather than discarding earlier attempts.

| Obligation | Latest evidence | Status |
|---|---|---|
| SDK-free model/core | final-check-04/pure.log, pure-results, source.json; fresh isolated output check/build | PASS45, zero failed/skipped |
| Pure contracts | final-check-04 XML; model4+core41, including malformed token/prefix rejection and valid reuse afterwards | PASS45 |
| Actual Kotlin/Java compile restriction and positive controls | full-check-03 and final-check-04 CompilationBoundaryTest32 | PASS32; actual UI core/runtime negatives, plugin-core and pure-SDK negatives included |
| Transitive/source/output/compiler-input bypass rejection | same32 suite and effective actual serialized plugin-input audit | PASS; original real serialization-plugin bypass/failure retained in earlier evidence |
| Packaging and benchmark-report policy | final-check-04 PackagedOwnerTest9 + BenchmarkReportTest9 | PASS18 |
| Module policy/bytecode architecture | final-check-04 UI3+runtime3+plugin architecture4 | PASS10 |
| Actual runtime/read/cancel/stale/lifetime/markup | final-check-04 minimumSdkTests; IC241.19416.15/JBR17 | PASS29; includes two actual editors sharing one document with independent close/publication |
| Complete root check and formatting | final-check-04 check.log, format.log, summary.json | PASS134 represented cases; unchanged task reuse is recorded |
| Release/visual archive separation | final-check-04/visual-archive-verification.json | PASS; 451 release classes, exact compiled bridge delta only |
| Current actual IDE execution | official currentSdkTests, expected29 identities | RUNNING current-sdk-01 |
| Supported IDE compatibility | fresh official recommended+explicit matrix, eight strict failure categories | NOT RUN |
| Driver rendering/state transitions | compare06 exact12ARGB and PNG hashes on earlier source | Earlier PASS; final-source compare07 and mutation08 NOT RUN |
| Local static inspection | qodana run01 FAILED74; run02 FAILED2; both remaining sites fixed | Fresh rerun PENDING; unchanged failThreshold0 |
| Same-condition performance/allocation | pure semantic fingerprint01; earlier SDK smoke04 all16; post-reuse execution smoke | Formal NOT RUN; final common/jar proof and setup refresh required |

## Preserved failed evidence

- Initial compilation-boundary run:26/32, followed by targeted fixes and actual serialization-plugin injection proof; full32 subsequently passed.
- Driver capture01/advisory, capture02/settings, capture03/focus and compare05/native-markup failures remain preserved; compare06 passed after real SDK native-key readiness. This does not prove universal race freedom.
- Isolated SDK smoke03 rejected original descriptors still visible through test resources. Descriptor-only overlays byte-verify all other resources; smoke04 passed16 commands.
- Endpoint reuse test01 failed due a fixture colorscheme clone; test02 passed28 and added unchanged markup/range-marker identity plus independent theme/range coverage.
- Qodana run01 failed74. Full-check03 separately failed SDK comparison compilation because K2 inspection had called K1-required assertions redundant; stable local owners compile in final-check04.
- Qodana-fixes02 root check exposed a wrong included-build identity before execution; root dependency now resolves `build-logic`.

## Interpretation

Fresh unit and SDK contracts replace old suites. Structural, runtime, visual, resource and performance claims remain separate. The minimum SDK uses actual host classes/bootstrap; current/matrix remain unexecuted. Baseline production is immutable, helpers additive, common source equality is enforced. No timings from setup are accepted as formal performance. No tolerances, thresholds, trial counts or failure categories were reduced.

Only main executes shared builds and measurements; implementation agents own disjoint files. All remote publication, uploads and jobs remain prohibited. Local CI file installation alone was explicitly approved.
