# Deep-module redesign status

## Baseline and authorization

- Baseline: `072533f` on `codex/issue-97-module-isolation`.
- New branch: `codex/issue-97-deep-redesign`.
- PR 96: OPEN, head `84d1a43141b1933c81e027ef01d1579fc4679e6e`, confirmed 2026-10-08.
- User authorized full implementation of the reviewed plan, replacement of all existing tests, and local commits. No push, PR, merge, or external upload.
- Main owns contract integration and verification. Existing gpt-6.1-sol / medium agents own implementation.

## Current stage

First implementation checkpoint is ready. 43 model/core contracts, 3 UI pure contracts, 3 native authority contracts, 4 bytecode architecture contracts and 15 actual minimum-IDE contracts passed (68 total, zero skipped/failed). The actual IDE was IC-241.19416.15 with bundled JBR 17.0.12. SDK-free pure execution separately passed before formatting; the independent pure root check remains to be run. Actual production compiler input isolation and final archive byte/resource identity passed. Spotless passed. See checkpoint-01.json and checkpoint-checks-03.log.

Not complete: TestKit intentional compile failures/contamination mutations, expanded packaging/BMF policy tests, full root check, current IDE, Driver, full verifier matrix, Qodana and paired performance are pending. Driver currently has a known pair-only readiness issue. Follow-up UI rendering ownership and in-flight/secondary repair tests are prepared but not yet applied.

## Ownership

| Owner | Files |
|---|---|
| core_design | analysis-model and analysis-core production/test sources and module READMEs; no Gradle edits |
| runtime_design | editor-ui and analysis-runtime production/new tests; plugin/src/main and module READMEs; no Gradle edits |
| build_validation | Gradle/settings/build-logic/CI/tools/benchmarks; delete plugin legacy tests; new plugin integration/Driver tests and testing docs |
| main | outputs/issue-97/redesign, cross-module contract decisions, integration review and all shared build/measurement runs |

No concurrent shared Gradle execution or performance measurement. Agents request checks from main. No agent commits without a coordinated checkpoint.

## Required final evidence

- Actual Kotlin/Java compile isolation, positive controls, transitive/source/output/compiler-input bypass rejection.
- SDK/tooling-free model/core build and new pure contracts.
- Real SDK thread/read/cancel/stale/disposal/guide behavior.
- Fresh module-owned tests, lint, Driver, packaging and supported IDE verification.
- Same-condition baseline/candidate latency, allocation, writer and lifetime comparison.
- No old suite silently retained; no missing/NO-SOURCE checks counted as passed.

## Existing unresolved evidence

Previous timing/allocation signals and bounded XML classifier retention remain unresolved; redesign is not assumed to fix them. Historical results are not evidence of new code correctness.
