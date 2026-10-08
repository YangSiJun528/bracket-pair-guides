# Deep-module redesign status

## Current source and authorization

- Worktree: `issue-97-deep-redesign/bracket-pair-guides`, branch `codex/issue-97-deep-redesign`.
- Baseline: `072533fa3e223d53f937a15d50fe8637add8b2cb`. Baseline production remains unchanged; comparison helpers are additive.
- PR96 was OPEN at task start, head `84d1a43141b1933c81e027ef01d1579fc4679e6e`, included in the chosen history. Original checkout/user changes are preserved.
- Latest local commit: `be4f6201556710d2dc3f4895e7555f55fa9a8727`. Final root check05 records this revision and unchanged source manifest `9d2af2e3ebd59bb709f10776165fc8b16fd69ba14ed247e11a2a025cfb002e68`.
- User authorized full redesign, replacement tests, local commits and local CI configuration installation. No push, pull request, merge, upload, or remote job is authorized or performed.

## Current unapplied-to-evidence fix

The reviewed three-file supported-listener patch is now applied but has not been
revalidated. `DocumentCalculation` uses the parent-disposable overload and disposes
it at last owner release; session init owns acquire and close owns release; factory
no longer pre-acquires. No public interface, threshold or test was changed.
Check05, Qodana03 and Driver11 below belong to their recorded pre-fix sources;
they are not automatic passes for these three new production changes. Main is
preparing source-check06.

## Verified pre-fix snapshot

`final-check-05/source.json` freezes the latest complete root-check snapshot.

- Full root `check` PASS: exit0, 618.2745s, 134 represented XML cases, zero failures/errors/skips, `sourceUnchanged=true`. Cases: model4+core41, UI3, runtime3, architecture4, actual minimum SDK29, compilation-boundary32, packaging9, benchmark-report9. Unchanged module tasks may be up-to-date; the build-policy50 and actual minimumSDK29 execution outcomes are retained in the log.
- Independent SDK-free `--rerun-tasks` build/check passed model4+core41 on the final-check04 pure snapshot. `final-check-05/pure-source-parity.json` confirms all selected source/build files are exactly unchanged. This is a fresh45 pass plus exact parity, not another standalone execution under check05.
- Current SDK29 PASS under `current-sdk-01`, zero failures/errors/skips, exactly the same case identities as minimum SDK. Its own manifest is retained; the final official matrix must verify the selected supported IDEs and frozen release.
- SDK measurement/Driver source compilation and visual archive build passed. Earlier compile failures remain preserved.
- Earlier release/visual archive audit under final-check04 passed: each release entry byte-identical, with only the separately compiled Driver bridge JAR added. Its release hash is `b1442ca6dfa508738e6cb12c93cf8f4757d2e7f9bce3181c4a73dd9c943b507e`; the upcoming verifier matrix must record its actual selected release hash rather than assume this older snapshot's hash.
- Evidence: `final-check-05/summary.json`, `check.log`, copied XML and `pure-source-parity.json`; `current-sdk-01/summary.json`; original archive evidence under final-check04.

## Running and remaining validation

- Local Qodana run01 FAILED74 at failThreshold0. Source fixes were reviewed; run02 FAILED2 under `final-check-04/qodana.log` (`qodana/local/results.ZZhaw4`). Both remaining local style findings were corrected; run03 PASSED with0 findings at the unchanged failThreshold0 (423.7s), scoped to its retained source manifest.
- Driver compare06 passed twelve exact pixel/image contracts on the earlier source. Compare07 FAILED only horizontal-only:569 text pixels differ (Contract/run semantic highlighting); the other11 baselines are exact. Per-capture actual daemon readiness was added without changing baseline pixels, timeout or stability rules. Compare09 FAILED capture readiness while asynchronous JDK indexing continued; setup now uses standard Driver indicators/smart-mode readiness. Compare10 FAILED3 exact images: two old identifier usage backgrounds absent at brace caret and20 SDK indent pixels after edit;9 other baselines exact. The unchanged072533f baseline counterfactual PASSED all functional assertions and produced12 PNGs byte-identical to candidate10. Main independently reviewed and corrected only3 stale SDK-state oracles from baseline-produced captures, preserving previous images and failures. Compare11 PASSED12 exact ARGB/PNG images and all functional assertions. Paint-removal mutation08 deliberately produced a failing test (exit1, one JUnit failure): the final nine expected pixel comparisons detected removed paint; three no-guide scenarios remained exact. This is successful negative-control detection, not a passing mutation test suite. See individual verification.json and settled-oracle-review/. Nine original redesign baseline images are unchanged; three were deliberately corrected from unchanged-production settled captures, with the previous images preserved. Exact pixel criteria were not weakened.
- Current IDE actual SDK29 PASSED under `current-sdk-01` with exactly the minimum-SDK case identities on its pre-fix snapshot. Compatibility scripts expect29 cases; the three-file fix requires rerun.
- First official compatibility run `20261008T115340939459Z` FAILED: exit1, 1490.48s, old `be4f620` source and release SHA256 `b1442ca6dfa508738e6cb12c93cf8f4757d2e7f9bce3181c4a73dd9c943b507e`. Five IC targets strictly passed; four IU targets failed the same deprecated one-argument `Document.addDocumentListener` call; four remaining targets were NOT RUN because the disk guard stopped acquisition. This is not a complete13-target pass. Reports remain in `compatibility/runs/20261008T115340939459Z/`.
- `compatibility/owned-cache-retirement-01` retired only this task's three created IDE installations and three created installers after preserving original reports, product information and hashes. No pre-existing cache artifact was deleted; 23.34GiB was recovered. A fresh entire official matrix remains pending after new-source validation.
- All16 isolated SDK setup commands passed in smoke04 on an earlier source. Endpoint reuse follow-up smoke passed all MAIN1545/1545 and PREVIEW1542/1542 unchanged callback identities. Smoke05 baseline edit passed; candidate setup rejected an invalid decorated SHA before measurements. Smoke06 PASSED all16 with exact committed40-character revision; actual pair environment/corpus and raw completion/XML checked. Fresh final JMH jars match all8 semantic fingerprints (fingerprint02); formal timing not begun.
- Formal performance comparison has NOT begun. Six AB/BA JVM pairs, five JMH jobs, eight actual SDK workloads, original warmup/repeat/budgets and raw censoring remain preregistered in `performance-plan.json`. Final measurement provenance, jars/fingerprints/common sources/classes must be frozen before formal execution.
- Source-check06 and a fresh complete official13IDE matrix are pending; formal performance is NOT RUN. Final architecture/evidence report remains a draft: `final-report.md`.

## Ownership and execution discipline

Main owns integration, every Gradle/IDE/Qodana/measurement execution, evidence review and commits. Implementation agents are gpt-6.1-sol / medium: core_design owns model/core+shared additive comparison helpers; runtime_design owns UI/runtime/Driver+SDK contracts; build_validation owns build/CI/JMH/verification wrappers. All agents are currently frozen.

Only main runs shared builds, serially. No benchmark overlaps a build, Driver, Qodana or verifier. Before commits every writer must be frozen because the configured pre-commit hook stashes unstaged changes. No remote actions.

## Limits that must remain explicit

Compiler boundaries prevent forbidden direct references under the checked build policy; they are not a proof of all algorithm/thread bugs or a security boundary against editing the policy/reflection. Headless SDK activity is distinct from real Swing focus/paint. Native A/B/A has gate-unit and Driver evidence, not a deliberately delayed native-worker integration completion. Setup timings are not formal results. Bounded GC/reachable-array observations are not full retained-heap proofs. Baseline isolated refusal restoration may be censored because its daemon orchestration is outside that fixture; never treat missing restoration as zero.
