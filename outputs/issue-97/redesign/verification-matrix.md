# Redesign verification ledger

This ledger records observed results, not intended coverage. Historical issue-97 evidence does not validate the redesigned code.

| Obligation | Current evidence | Status |
|---|---|---|
| SDK-free model/core compilation | pure-final-04.log + pure-final-04/summary.json; fresh independent tools/pure-build check/build, 43 contracts | Passed for recorded source snapshot |
| Fresh pure contracts | pure-tests-02.log; checkpoint-01.json; model 4 + core 39, zero failed/skipped | Passed; later additions await rerun |
| Packaging/BMF policy tests | compiler-targeted-02.log; Packaging9+BMF9 | 18 passed for recorded snapshot; full check pending |
| Full integration compilation | integration-compile-03.log and checkpoint-checks-01/03.log | Passed |
| Actual UI/plugin Kotlin and Java isolation | compilation-boundaries-01 XML: UI10 forbidden symbols in Kotlin/Java + owner/model positive controls, plugin-core negative, pure-SDK negative | Passed |
| Transitive/source/output/compiler-input contamination | compilation-boundaries-01:32tests/26pass/6fail; targeted02:23/24pass; diagnostic05 proves effective serialization compiler plugin bypass | Single real compiler-plugin mutation passed probe07 after effective-argument fix; full32 rerun pending |
| Actual final compiler-input inventory | actual-inputs-02.log, UI test compiler input audit | integration-compile-07 passed after effective compiler-plugin gap fix |
| SDK runtime thread/read/cancel/stale/lifetime/render behavior | sdk-contracts-10.log + sdk-driver-packaging-11.log:25 actual minimum SDK contracts; checkpoint-02 archived XML | integration-compile-07:26 passed including actual XML reload; current SDK pending |
| Minimum/current actual IDE execution and identity | IC-241.19416.15 + JBR 17.0.12 confirmed | Minimum passed; current not run |
| Formatting, bytecode architecture and complete check | Spotless and 4 ArchUnit rules passed | Full root check pending |
| Final release archive and visual bridge separation | actual compiled/instrumented inputs, composed archive and ZIP identity passed | Visual bridge delta pending |
| Supported IDE compatibility matrix | official IntelliJ Plugin Verifier, unchanged eight failure categories | Not run |
| Driver pixel contracts and state transitions | driver/capture-04 passed and12images visually reviewed; compare05 failed A/B/A native rendering; compare06 passed exact12ARGB/image-byte equality after actual native-markup readiness | Passed for recorded source; endpoint reuse follow-up and paint-removal mutation pending |
| Paired latency/allocation/resource comparison | fingerprint-01 exact8input/results match; performance-plan.json preregistered6 AB/BA pairs; sdk-isolated-smoke-04 all16commands passed with loaded descriptor/resource isolation + cleanup proof | Formal timing/allocation not run; same-caret endpoint churn found and being fixed |
| Qodana inspection | zero-failure gate, local-only execution | Not run |

## Findings being resolved

- Public demand configuration must defensively own disabled-language values.
- Final accepted-result commit must not call SDK methods under its lifecycle lock.
- Document-local reuse generations must remain monotonic independently of mutable SDK stamps.
- Coverage/repair result precedence must preserve useful valid results.
- Cancellation verification must reach late sorting/copying/guide sealing and cache-lock waits.
- Malformed input batches must not produce a seemingly complete partial calculation.
- Compiler and archive guards require intentional contamination tests and positive controls.

## Execution discipline

Only main runs shared builds and measurements. Implementation agents own disjoint files. No simultaneous performance measurements. Before local commits, pause every writer because the configured pre-commit hook stashes unstaged tracked changes. No remote push, pull request, merge, or upload is authorized.

## Latest local integration checkpoint (2026-10-08)

- User explicitly approved local CI configuration installation only; approved proposal installed. No remote job, upload, push or PR was executed. See `ci-local-authorization.json` and `benchmark-workflow-local-installation.json`.
- `compiler-plugin-diagnostic-05` failed as intended evidence of an audit gap: KGP legacy getter returned null although actual `-Xplugin` contained serialization. Guard now reads actual serialized inputs with pinned resolved artifact identity and byte hashes. This fix is not yet marked passed.
- `sdk-common-baseline-compile-04` passed; candidate composite compilation04 failed on test-only Balloon visibility API. A concrete SDK241/242 test adapter fix is installed, unverified.
- `format-06` failed on new resource helper filename convention; integration/probe06 did not run because the serial queue stopped. No lint rule was weakened.

- Follow-up integration07 passed actual compiler-input audit, SDK26 (zero failed/skipped), all new measurement/Driver compilation and packaging. Real serializer injection regression probe07 passed. `checkpoint-03-contracts.json` binds XML hashes. These runs do not replace the remaining full check/Driver/current-SDK/performance checks.
