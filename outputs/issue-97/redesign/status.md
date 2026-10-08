# Deep-module redesign status

## Baseline and authorization

- Baseline: `072533f` on `codex/issue-97-module-isolation`.
- New branch: `codex/issue-97-deep-redesign`.
- PR 96: OPEN, head `84d1a43141b1933c81e027ef01d1579fc4679e6e`, confirmed 2026-10-08.
- User authorized full implementation of the reviewed plan, replacement of all existing tests, and local commits. No push, PR, merge, or external upload.
- Main owns contract integration and verification. Existing gpt-6.1-sol / medium agents own implementation.

## Current stage

Implementation checkpoint `5767d6a` is committed; the next local checkpoint records reviewed post-checkpoint fixes. Integration07 passed the actual compiler-input audit, measurement and Driver source compilation, release packaging and all26 minimum-IDE contracts (zero failed/skipped), including actual XML persistence/reload. SDK identity is IC-241.19416.15 with bundled JBR17.0.12. Evidence: integration-checks-07.json and checkpoint-03-contracts.json.

Actual Kotlin/Java UI core/runtime negatives and owner/model positive controls passed in the first full TestKit run. The full run had26/32 passes; targeted harness repairs left one actual compiler-plugin audit gap. Diagnostic05 proved the gap using a real serialization plugin. The guard now audits effective serialized compiler inputs with resolved artifact identity/hash pins; the normal input audit and real plugin mutation both passed07. The entire32-case suite still requires rerun.

Independent SDK-free check passed43 model/core contracts; Packaging9+BMF9 passed targeted02. Eight pure benchmark semantic fingerprints matched. New common SDK measurement sources are byte-identical across the candidate and baseline. Baseline production remains unchanged. No timed comparison has been run.

Driver capture01 failed the advisory observation despite a visible actual balloon. It also exposed static service lookup and persistence warnings, now fixed. Updated Driver compiles but must run again. No new baseline image has been accepted. Full root check, current SDK, passing Driver, visual-archive separation, full verifier matrix, Qodana and paired performance remain incomplete.

CI configuration authorization resolved: the user explicitly approved “로컬 CI 설정 반영만 승인” for the reviewed proposal. Installing the local workflow with its existing Bencher/upload/PR-check settings is authorized. Actual remote execution, uploads, project writes, push and PR creation remain unauthorized. The exact approved proposal and validation limitations remain recorded; no live remote gate pass is claimed.

## Ownership

| Owner | Current files and responsibility |
|---|---|
| core_design | model/core (now stable), baseline additive adapters, identical shared actual-SDK performance fixture/workloads installed in both checkouts |
| runtime_design | UI/runtime production and SDK contracts (stable), plugin main composition, UI test-only measurement bridge; Driver bridge/tests/visual docs; verification-only compatibility runner inputs |
| build_validation | Gradle/settings/build-logic/CI/tools/JMH; candidate runtime test-only performance adapter; proposed CI policy and validators |
| main | design integration, all actual build/test/IDE/measurement execution and result review; outputs except explicitly delegated proposal/compatibility inputs |

Delegations use gpt-6.1-sol / medium. No concurrent shared Gradle execution or performance measurement. All measurements are serialized and separated from other heavy tasks. Agents request checks from main. Before local commits, pause every writer because the configured pre-commit hook stashes unstaged tracked changes. No remote action is authorized.

## Required final evidence

- Actual Kotlin/Java compile isolation, positive controls, transitive/source/output/compiler-input bypass rejection.
- SDK/tooling-free model/core build and new pure contracts.
- Real SDK thread/read/cancel/stale/disposal/guide behavior.
- Fresh module-owned tests, lint, Driver, packaging and supported IDE verification.
- Same-condition baseline/candidate latency, allocation, writer and lifetime comparison.
- No old suite silently retained; no missing/NO-SOURCE checks counted as passed.

## Existing unresolved evidence

Previous timing/allocation signals and bounded XML classifier retention remain unresolved; redesign is not assumed to fix them. Historical results are not evidence of new code correctness.
