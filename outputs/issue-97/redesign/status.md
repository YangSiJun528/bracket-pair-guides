# Deep-module redesign status

## Current source and authorization

- Worktree: `issue-97-deep-redesign/bracket-pair-guides`, branch `codex/issue-97-deep-redesign`.
- Baseline: `072533fa3e223d53f937a15d50fe8637add8b2cb`. Baseline production remains unchanged; comparison helpers are additive.
- PR96 was OPEN at task start, head `84d1a43141b1933c81e027ef01d1579fc4679e6e`, included in the chosen history. Original checkout/user changes are preserved.
- Latest local commit: `8ea07aa86cf939d9a5e945bec180079ecc28eb60`. Listener lifetime/source-check06 and Qodana04 outcomes below are retained with their own source manifests.
- User authorized full redesign, replacement tests, local commits and local CI configuration installation. No push, pull request, merge, upload, or remote job is authorized or performed.

## Current verified snapshot and remaining source-specific evidence

The supported-listener three-file fix is applied and validated by the current
minimum/current SDK suites, root check06 and Qodana04. `DocumentCalculation`
registers the private parent disposable and disposes it at last-owner release;
session init owns acquire and close owns release. No public interface, threshold
or test was changed. Earlier check05/Qodana03/Driver11 remain separate pre-fix
evidence, not automatic current-source passes.

- `listener-lifetime-01`: actual minimum/current SDK each29 PASS, identical case identities, zero failures/errors/skips. Package audit:451 classes, no duplicate owner classes and zero test leakage.
- Current release SHA256: `609f09d3db8c14cf66aafc77b1f24bdfa9062a874d877f42013e406436f161ef`; visual archive SHA256: `1bbb6cb4162255d973a5f3c34b99da54e4dc272173488db1060a4d8778b64b77`.
- `final-check-06`: full root check PASS134 represented cases,583.4669s, zero failure/error/skip, `sourceUnchanged=true`; unchanged task reuse remains recorded.
- Independent SDK-free45 remains the fresh `--rerun-tasks` check04 run plus check06 exact selected-source/build parity, not a new standalone execution under check06.
- `qodana/run-04`: PASS0 findings,358.0148s, `sourceUnchanged=true`, unchanged failThreshold0.
- Driver compare12 PASS on the current source:12 exact ARGB and PNG-byte comparisons, all functional assertions,239.8648s, `sourceUnchanged=true`. Pixel criteria and independently corrected settled oracles are unchanged. See `driver/compare-12/verification.json` and `command.json`.
- Driver host/Linux whole release ZIPs differ. `driver/compare-12/production-equivalence.json` confirms every production class and resource is identical; only plugin JAR manifest `Build-JVM`/`Build-OS` fields differ. The exact host release609f09d3... remains the compatibility input; whole-ZIP identity is not claimed.

## Running and remaining validation

- Local Qodana run01 FAILED74 at failThreshold0. Source fixes were reviewed; run02 FAILED2 under `final-check-04/qodana.log` (`qodana/local/results.ZZhaw4`). Both remaining local style findings were corrected; run03 PASSED with0 findings at the unchanged failThreshold0 (423.7s), scoped to its retained source manifest.
- Driver compare06 passed twelve exact pixel/image contracts on the earlier source. Compare07 FAILED only horizontal-only:569 text pixels differ (Contract/run semantic highlighting); the other11 baselines are exact. Per-capture actual daemon readiness was added without changing baseline pixels, timeout or stability rules. Compare09 FAILED capture readiness while asynchronous JDK indexing continued; setup now uses standard Driver indicators/smart-mode readiness. Compare10 FAILED3 exact images: two old identifier usage backgrounds absent at brace caret and20 SDK indent pixels after edit;9 other baselines exact. The unchanged072533f baseline counterfactual PASSED all functional assertions and produced12 PNGs byte-identical to candidate10. Main independently reviewed and corrected only3 stale SDK-state oracles from baseline-produced captures, preserving previous images and failures. Compare11 PASSED12 exact ARGB/PNG images and all functional assertions. Paint-removal mutation08 deliberately produced a failing test (exit1, one JUnit failure): the final nine expected pixel comparisons detected removed paint; three no-guide scenarios remained exact. This is successful negative-control detection, not a passing mutation test suite. See individual verification.json and settled-oracle-review/. Nine original redesign baseline images are unchanged; three were deliberately corrected from unchanged-production settled captures, with the previous images preserved. Exact pixel criteria were not weakened.
- The pre-fix current-sdk01 evidence is preserved. Current listener-lifetime-01 reran actual minimum/current29 with identical identities and passed; compatibility scripts continue to expect29 cases.
- First official compatibility run `20261008T115340939459Z` FAILED: exit1, 1490.48s, old `be4f620` source and release SHA256 `b1442ca6dfa508738e6cb12c93cf8f4757d2e7f9bce3181c4a73dd9c943b507e`. Five IC targets strictly passed; four IU targets failed the same deprecated one-argument `Document.addDocumentListener` call; four remaining targets were NOT RUN because the disk guard stopped acquisition. This is not a complete13-target pass. Reports remain in `compatibility/runs/20261008T115340939459Z/`.
- `compatibility/owned-cache-retirement-01` retired only this task's three created IDE installations and three created installers after preserving original reports, product information and hashes. No pre-existing cache artifact was deleted; 23.34GiB was recovered. Fresh entire official matrix02 run `20261008T125349781364Z` PASS:13/13 strict targets on exact release609f09d3..., pendingTargets=[]; minimum/current SDK each29 reran and passed with identical identities. Outer `compatibility/serial-command-02.json`: exit0,2690.359957s, source8ea07aa. See run summary.json.
- All16 isolated SDK setup commands passed in smoke04 on an earlier source. Endpoint reuse follow-up smoke passed all MAIN1545/1545 and PREVIEW1542/1542 unchanged callback identities. Smoke05 baseline edit passed; candidate setup rejected an invalid decorated SHA before measurements. Smoke06 PASSED all16 with exact committed40-character revision; actual pair environment/corpus and raw completion/XML checked. Fresh final JMH jars match all8 semantic fingerprints (fingerprint02); formal timing not begun.
- Formal performance comparison has NOT begun. Six AB/BA JVM pairs, five JMH jobs, eight actual SDK workloads, original warmup/repeat/budgets and raw censoring remain preregistered in `performance-plan.json`. Final measurement provenance, jars/fingerprints/common sources/classes must be frozen before formal execution.
- Source-check06, Qodana04 and current Driver12 passed. Fresh complete official13IDE matrix02 run `20261008T125349781364Z` passed13/13 strict targets and reran minimum/current29 each with identical identities. Formal performance is NOT RUN; remaining work is formal comparison, analysis and the final report. Final architecture/evidence report remains a draft: `final-report.md`.

## Ownership and execution discipline

Main owns integration, every Gradle/IDE/Qodana/measurement execution, evidence review and commits. Implementation agents are gpt-6.1-sol / medium: core_design owns model/core+shared additive comparison helpers; runtime_design owns UI/runtime/Driver+SDK contracts; build_validation owns build/CI/JMH/verification wrappers. Implementation writers are frozen outside their explicitly assigned ledger updates; main alone owns official compatibility and formal performance execution.

Only main runs shared builds, serially. No benchmark overlaps a build, Driver, Qodana or verifier. Before commits every writer must be frozen because the configured pre-commit hook stashes unstaged changes. No remote actions.

## Limits that must remain explicit

Compiler boundaries prevent forbidden direct references under the checked build policy; they are not a proof of all algorithm/thread bugs or a security boundary against editing the policy/reflection. Headless SDK activity is distinct from real Swing focus/paint. Native A/B/A has gate-unit and Driver evidence, not a deliberately delayed native-worker integration completion. Setup timings are not formal results. Bounded GC/reachable-array observations are not full retained-heap proofs. Baseline isolated refusal restoration may be censored because its daemon orchestration is outside that fixture; never treat missing restoration as zero.
