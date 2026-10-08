# Deep-module redesign status

## Current source and authorization

- Worktree: `issue-97-deep-redesign/bracket-pair-guides`, branch `codex/issue-97-deep-redesign`.
- Baseline: `072533fa3e223d53f937a15d50fe8637add8b2cb`. Baseline production remains unchanged; comparison helpers are additive.
- PR96 was OPEN at task start, head `84d1a43141b1933c81e027ef01d1579fc4679e6e`, included in the chosen history. Original checkout/user changes are preserved.
- Latest local commit: `a89e9731c5a3b40ec8bcccac40b9617f4101fc10`. Current uncommitted follow-ups fix Qodana findings, reject malformed line-prefix bounds, add shared-document editor lifetime coverage, and declare actual TestKit fixture inputs.
- User authorized full redesign, replacement tests, local commits and local CI configuration installation. No push, pull request, merge, upload, or remote job is authorized or performed.

## Verified current snapshot

`final-check-04/source.json` freezes the current production/build/test sources.

- Full root `check` passed (660s): 134 cases represented by matching XML, zero failures/errors/skips. This includes model4+core41, UI policy3, runtime policy3, architecture4, actual minimum SDK29, compilation-boundary32, packaging9, benchmark-report9. Unchanged tasks may be up-to-date; the complete build-policy50 and actual SDK29 ran again.
- Independent SDK-free build/check used `--rerun-tasks`: model4+core41 passed, separate outputs and no IntelliJ compiler/test dependencies.
- SDK measurement/Driver source compilation and visual archive build passed. An earlier K1 nullable compilation failure is retained in `full-check-03`; stable locals resolve the K1/K2 inspection disagreement.
- Actual release/visual archive audit passed. Every release entry is byte-identical in the visual archive; the only extra file is the separately compiled Driver bridge JAR. Release SHA256: `b1442ca6dfa508738e6cb12c93cf8f4757d2e7f9bce3181c4a73dd9c943b507e`.
- Evidence: `final-check-04/commands.json`, `summary.json`, XML copies, `visual-archive-verification.json`.

## Running and remaining validation

- Local Qodana run01 FAILED74 at failThreshold0. Source fixes were reviewed; run02 FAILED2 under `final-check-04/qodana.log` (`qodana/local/results.ZZhaw4`). Both remaining local style findings were corrected; run03 remains pending.
- Driver compare06 passed twelve exact pixel/image contracts on the earlier source. Final-source compare07 and isolated paint-removal mutation08 are prepared but not executed. Accepted baseline images are unchanged.
- Current IDE actual SDK29 execution is running under `current-sdk-01`; the fresh strict supported-IDE verifier matrix is not yet run. Compatibility scripts expect29 cases.
- All16 isolated SDK setup commands passed in smoke04 on an earlier source. Endpoint reuse follow-up smoke passed all MAIN1545/1545 and PREVIEW1542/1542 unchanged callback identities. Final shared helper changes still need fresh both-side setup execution.
- Formal performance comparison has NOT begun. Six AB/BA JVM pairs, five JMH jobs, eight actual SDK workloads, original warmup/repeat/budgets and raw censoring remain preregistered in `performance-plan.json`. Final jars/fingerprints/common sources/classes must be rebuilt and frozen first.
- Final architecture/evidence report is a draft: `final-report.md`.

## Ownership and execution discipline

Main owns integration, every Gradle/IDE/Qodana/measurement execution, evidence review and commits. Implementation agents are gpt-6.1-sol / medium: core_design owns model/core+shared additive comparison helpers; runtime_design owns UI/runtime/Driver+SDK contracts; build_validation owns build/CI/JMH/verification wrappers. All agents are currently frozen.

Only main runs shared builds, serially. No benchmark overlaps a build, Driver, Qodana or verifier. Before commits every writer must be frozen because the configured pre-commit hook stashes unstaged changes. No remote actions.

## Limits that must remain explicit

Compiler boundaries prevent forbidden direct references under the checked build policy; they are not a proof of all algorithm/thread bugs or a security boundary against editing the policy/reflection. Headless SDK activity is distinct from real Swing focus/paint. Native A/B/A has gate-unit and Driver evidence, not a deliberately delayed native-worker integration completion. Setup timings are not formal results. Bounded GC/reachable-array observations are not full retained-heap proofs. Baseline isolated refusal restoration may be censored because its daemon orchestration is outside that fixture; never treat missing restoration as zero.
