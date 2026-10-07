# Independent model/core audit

Audit target: committed `a8e0bc1`, base `84d1a431`. Inspected with `git show` only; did not read or change sibling dirty checkout. No builds run by this agent.

## Findings

1. `analysis-core/.../snapshot/IndexedBracketSnapshot.kt:visibleTokens` dropped the former `TextRange` constructor's range validation when moving to primitive offsets. Reversed input such as `(startOffset=3,endOffset=0)` over the simple pair fixture can yield negative `TokenWindow.size`. Add `require(startOffset >= 0 && endOffset >= startOffset)` and pure cases for negative/reversed input while preserving valid empty ranges. Existing callers supply valid ranges, so this is contract hardening at the newly public seam rather than evidence of current editor regression.

2. `editor-ui/.../analysis/EditorAnalysisStamp.kt` retains lightweight host identity capture and validation: document modification stamp, highlighter identity, file type, tab size. It performs no token iteration, text collection, matcher callback, builder access, or core/runtime call. The requested prohibition on input collection should be explained as calculation-input collection, or these identity operations moved behind UI-owned host interface if literal exclusion of every identity read is intended. Moving unchanged AnalysisStamp/Input into runtime would reopen UI/runtime compile dependency; current platform-free model stamp plus UI adapters avoids that cycle.

## Preserved implementation

Compared every moved core production file against the exact PR96 base. Java pairing files are byte-identical. Kotlin pairing/guide/index/sorting code differs only in visibility required for runtime callers. `BracketIndexes` gains narrow helpers (`newSnapshot`, `hasSameTokenContent`, `hasGuidePositions`) while backing indexes and constructor remain internal. No proportional copy or materialized token/pair list was introduced.

The model publishes abstract `BracketSnapshot` and `TokenWindow` contracts only. Actual query selection, pair memoization and bounded token-window implementation stay in core. One snapshot owns its memo even when indexes are canonicalized across editors. SDK-free model/core builds use java-library + Kotlin JVM, Kotlin stdlib and test libraries only; neither includes the IntelliJ Gradle plugin or SDK dependency.

`analysis-runtime` declares core with implementation and model with api; `editor-ui` declares model only; plugin implementation declarations exclude core. Plugin test fixtures deliberately add core and test-only friend jars. Production Kotlin compile tasks must be checked independently because those integration fixtures cannot establish restricted UI access.

AnalysisStamp now owns the immutable disabled-language set formerly owned by AnalysisInput. Its narrowing path reuses that immutable set, preserving one-copy allocation behavior. covers/source/layout comparison logic is preserved. The query split retains active-pair memo and token capping algorithm exactly apart from TextRange-to-offset replacement.

## Test coverage assessment

Pure Java pairing, Kotlin recognition/index/cancellation/indentation/repair tests migrated. Cancellation sentinels replace SDK ProcessCanceledException in pure tests. Guide index text fixtures use pure adapter. New SDK-free IndexedBracketSnapshotTest covers per-editor memo and bounded token query access; existing richer platform BracketSnapshotTest remains as integration evidence, preserving nested-pair and sorted token metadata cases.

Performance evidence still requires main's fresh baseline/current comparison; source equality does not establish measured allocation/latency parity. Runtime execution and packaging audits belong to other reviewers/main.

## Follow-up fix

Implemented nonnegative/ordered primitive range validation and pure regression cases for negative start, both-negative offsets, reversed range, and valid empty range. Main owns serial Gradle verification; no build run by this agent.
