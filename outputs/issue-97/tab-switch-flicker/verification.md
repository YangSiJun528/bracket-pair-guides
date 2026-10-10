# Verify warm tab restoration

The local 0.1.0 QA report exposed a visibility-return gap: hiding discarded the accepted result, and returning waited for asynchronous analysis even with unchanged source. Runtime now retains at most one JDK SoftReference result per editor and validates source, coverage, semantic environment, file-size eligibility and lifetime before synchronous EDT reuse. SDK preflight and rendering reentry are fenced before acceptance. UI still clears hidden markup; edit-time hiding and work cancellation remain active.

The five modules and public signatures are unchanged. The cache and source checks remain inside analysis-runtime. No new cache registry, UI computation access or delay was added.

## Completed validation

- `spotlessCheck`, `:plugin:buildPlugin`, `verifyPluginPackaging`, `:plugin:verifyPluginProjectConfiguration` and `:plugin:verifyPluginStructure` passed.
- Actual IC 241.19416.15 and IU 263.6259.32 each executed 50 SDK contracts without failures/errors/skips. Tests cover synchronous reuse with no new capture, hidden edits/stamp reset, environment/layout/highlighter/language/coverage invalidation, memory-pressure reclamation and fallback, cancelled hidden work, close and rendering reentry.
- Complete `check` passed in 10m45s, including actual Kotlin/Java compiler and packaging policy tests. The unchanged tasks eligible for Gradle up-to-date reuse were not forced to rerun.
- `analysis-model:test`: 4 tests, no failures/errors/skips.
- `analysis-core:test`: 47 tests, no failures/errors/skips.
- `editor-ui:test`: 4 tests, no failures/errors/skips.
- `analysis-runtime:test`: 3 tests, no failures/errors/skips.
- `plugin:test`: 4 tests, no failures/errors/skips.
- `plugin:minimumSdkTests`: 50 tests, no failures/errors/skips.
- `build-logic:test`: 52 tests, no failures/errors/skips.
- Pinned Linux IC 2024.2.6 Driver passed. Each of three warm tab returns restored the exact 16 token ranges inside the selection EDT turn; hidden editors had no plugin markup. Focus settlement was checked separately.
- All 12 reviewed PNGs and the post-tab-cycle all-components image were byte-identical to their expected images. Baselines and thresholds were not changed.
- The identical Driver with only EditorAnalysisSession restored from `064f8e4` failed the new synchronous-return assertion: zero token ranges on return, then 16 after asynchronous completion. This expected failure demonstrates regression detection; it is not counted as a positive suite pass.
- All 451 production classes in the Driver release were byte-identical to the host delivery archive. The ZIP contains exactly five owner jars and no Driver/test/SDK classes; CRC and descriptor version 0.1.0 checks passed.

## Artifact

Delivery: `/Users/sijun-yang/Documents/GitHub/bracket-pair-guides/build/distributions/0.1.0-tab-switch-fix/bracket-pair-guides-0.1.0.zip`

SHA-256: `a1539f4dad1bd5736acc7c2abf6bfb710bf8d07f6561b989f5ea967a6592e91b`

The earlier 0.1.0 ZIP is preserved in its original delivery directory. Raw logs, XML, source hashes, original source, PNGs and observations are retained beside this report under `outputs/issue-97/tab-switch-flicker/`.

## Limits

Soft references may survive ordinary GC and may be reclaimed under memory pressure. One entry per editor is not a total-memory byte cap. Cold tabs, changed/invalid source and reclaimed caches require fresh background analysis. Focus-based active-guide visibility still applies. Same-turn markup and settled pixels do not prove the absence of every possible painted-frame interleaving or concurrency defect.

This follow-up did not rerun JMH/SDK latency-allocation benchmarks, Qodana or the full 13-IDE compatibility verifier matrix. Previously recorded performance regressions remain unresolved. Dynamic plugin unload/load and file-size-threshold mutation during rendering were not newly exercised end to end. No remote push, PR, publication or upload was performed.

Initial formatting failures were fixed with Spotless. The first minimum-SDK run exposed a test helper issuing file-type events outside a write action; the helper was corrected to the SDK contract and both full SDK suites rerun. Original failure logs/XML remain available.
