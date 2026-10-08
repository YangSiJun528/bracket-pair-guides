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

## Validation continuation — 2026-10-08

- Local CI configuration approval was applied; no external action performed.
- Checkpoint cba8b1169de112552414409fc7a7a884c9ce31d0 records effective compiler boundaries and SDK contracts.
- SDK setup smoke02 passed twelve baseline/candidate commands for analysis, write-wait, execution, repair, native, capture-release; payload smoke01 passed both. These are setup checks, not performance results.
- New edit-restoration smoke01: baseline passed, candidate failed an exclusive composition precondition. Investigating genuine fixture automatic plugin attachment and possible unrelated workers before any formal comparison.
- Driver capture02 passed actual advisory visibility and failed Settings Apply; real UI click fix installed. Capture03 passed Apply/Cancel then failed focus-only restoration. Capture04 now records actual focus ownership and supported focus-settlement request. No visual baseline is accepted yet.
- Formal comparison plan now names all eight SDK workloads and six independent AB/BA JVM pairs. No threshold, repeat or warmup count reduced.

## Isolated fixtures and reviewed Driver baseline

- `pure-final-04` reran standalone SDK-free check/build: model4+core39, zero failed/skipped.
- Driver `capture-04` passed; main inspected all12images and accepted baselines with image/source hashes. `compare-05` failed A/B/A equality (native brace colors remained). Stronger readiness now observes actual native brace-key highlighters and managed native flags. `compare-06` passed all12 exactARGB comparisons and all12 PNG byte hashes match. Prior failure is preserved; universal race freedom is not claimed.
- `sdk-isolated-smoke-03` rejected original descriptors still visible in official test-resource roots. Measurement-only overlays now replace only those descriptors, byte-verify every other resource and preserve ordinary test outputs. `sdk-isolated-smoke-04` all16commands pass: both actual IC241/JBR17/2GiB, equal common source hashes, actual loaded isolated descriptors, successful editor/session cleanup. See independent audit.md. No smoke timing is a formal performance result.
- The smoke uncovered two unnecessary active endpoint highlighter replacements on unchanged MAIN callbacks (1543/1545 identities vs baseline1545/1545). Runtime agent is implementing local resource reuse and fresh SDK contracts before final correctness/performance runs. Production code until that follow-up remains cba8b11.
