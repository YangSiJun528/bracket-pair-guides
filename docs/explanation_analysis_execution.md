# Analysis execution

A presentation request describes desired state. Runtime turns that request into independently cancellable work, and core owns a complete calculation attempt. This division keeps SDK scheduling and result acceptance out of calculation while keeping pairing and index construction out of presentation and runtime callers.

The [module architecture](explanation_module_architecture.md) explains compiler dependencies and contract ownership. This document explains execution and result lifetime.

## Desired state and work ownership

`ui.work.GuideWorkFactory.attach` connects one editor view to one `GuideWork`. `EditorGuide` submits a frozen `GuideDemand` through `reconcile`; it does not start coroutines or sequence worker cancellation. The demand contains coverage, language selection, visibility, presentation revisions, repair intent, and native interest. A daemon pass calls `refresh()` as an independent wake-up.

`RuntimeGuideWorkFactory` creates `EditorAnalysisSession`, which owns the accepted result and three independently revocable work lanes: full analysis, repair, and native inspection. Reconciliation revokes obsolete work before scheduling replacements. Compatible richer work can satisfy a narrower demand. Each editor retains its own accepted-result authority even when split editors share a document calculator.

Work starts on `Dispatchers.Default` without inheriting a caller's read action. Main-editor full analysis starts without the secondary delay. Secondary full analysis waits 75 ms; semantic supersession still cancels immediately. Repair starts immediately and has a separate ticket. Hidden or no-facet demands revoke work and release accepted values. Editor/project/application lifetime and explicit close revoke publication and release attachments.

## One complete core attempt

Runtime calls the public final `core.api.BracketCalculator` through `analyze` or `repair`. These are complete use cases. Runtime supplies a bounded input adapter and cancellation control; it never assembles pairing sessions, prepares index builders, or supplies concrete indexes for publication.

`EditorSource` implements core-owned `BracketInput` and `LineInput`. An attempt begins with platform-free document facts: length, line count, tab size, and reuse revision. Each token capture visits at most 512 lexer tokens; the bound counts tokens, not characters. A single long syntax token may span many characters. Captures return immutable primitive columns through `TokenBatch.capture`, whose collector is revoked at completion. Ordered nonoverlapping tokens must fit before the next capture boundary, and the final boundary must equal the document length. An adapter cannot turn a prematurely ended prefix into an authoritative result.

The host retains highlighters, iterators, language definitions, and platform token types. Core receives opaque token identities and asks the input adapter for compatibility only when the pairing calculation needs an answer. The demanded-answer cache is bounded at 2,048; the host strongly caches at most 1,024 platform token kinds per attempt. No live iterator or host object enters model results.

Core builds active-pair indexes before retaining token metadata and guide storage. Guide admission computes the required multiline line envelope first. Initial prefix capture covers at most 128 lines with at most 128 characters per line; unresolved whitespace continues in chunks of at most 4,096 characters. Indentation scanning, sorting, index building, and guide queries execute after read access is released. Builders seal final arrays once and cannot mutate the returned result.

Pair or pending-open refusal discards the partial pairing result. A missing compatible token source also discards an earlier recognized prefix and returns empty undetermined recognition. Guide-capacity refusal retains exact token and active-pair coverage and marks guide coverage absent. `AnalysisResult` expresses those differences; a bounded token window is never a substitute for a complete analysis result.

## Capture validity, retry, and cancellation

`SourceIdentity` is runtime-private. It captures document stamp, file type, highlighter identity, language selection, and tab layout. Every SDK read validates current source identity and cancellation before and after capture. Admission and final validation also check layout. `AnalysisReadEpoch` changes around relevant platform writes and plugin/file-type changes to invalidate a multi-read attempt.

Core owns the retry decision for `RetryCapture`, which means an unrelated host write invalidated chunk consistency. It discards the whole attempt, checks cancellation, yields, and starts a fresh capture epoch. A changed source produces `SourceChanged`; runtime rejects the old work instead of letting core reinterpret it as a retry. Platform retries create fresh read-action-local iterators, while pure pairing advances only after successful capture.

`CalculationControl` combines coroutine cancellation, platform cancellation, task tickets, and editor lifetime. Pure loops check cancellation during recognition, sorting/merging/copying, guide scanning/sealing, and cache comparison. Cache lock acquisition checks while waiting. These are cooperative checks: one JVM array copy, allocation, lexer operation, or extension callback is not preempted halfway through its operation.

## Reuse and final approval

`DocumentCalculation` shares one calculator between views of the same document. It assigns a monotone reuse revision independently of SDK stamp values, observes document changes while owned, and invalidates reuse across dormant intervals. Its last session releases the document listener. This revision is a cache-generation rule, not the authority to display a result.

Core's weak canonical cache requires exact pair/token content and matching index layout; guide storage also requires matching tab size. A late old-generation completion cannot roll the cache backward. Equivalent results share immutable storage but each `BracketView` owns its query memo. The cache does not strongly retain its index payloads, document, or editor.

Full results return to EDT for source, demand, ticket, lifetime, and live file-size approval. The size predicate also gates accepted-result reuse. Runtime first asks `GuideView.applyAnalysis` to apply the update. Rendering can synchronously reenter through SDK listeners or fail. Runtime commits acceptance only after `ViewApplication.APPLIED`, another validity check, and a final lifecycle/ticket check. SDK rendering does not run under the lifecycle lock. Closing immediately revokes authority and clears editor/view/scope references; in-flight lexical captures unwind before their local references become collectible.

Repair uses the same source and cancellation discipline but returns geometry for an already tracked pair. It preserves the 256-line and 32,768-consumed-character budgets, earliest-line tie rule, and exact/provisional scan order. A refused exact repair leaves the guide hidden. Final repair application also requires the current guide revision and tracked-pair ownership. Authoritative full analysis revokes competing repair before application.

## Native evidence

Paint callbacks submit cheap displayed-guide evidence through `observeNativeGuide`; native input inspection runs later. `runtime.nativeproof` resolves committed PSI and actual platform brace/navigation context in cancellable read actions. The native traversal owns its matcher stacks and exact cursor bookmarks, recreating iterators within read access. A source change or platform retry invalidates the proof rather than replaying a partially advanced continuation.

Each traversal body admits at most 8,192 owned operations and checks a cooperative 2 ms deadline alongside cancellation every 32 operations. Work yields between unfinished bodies. Lazy preparation and one extension callback remain indivisible, so these bounds are not a hard read-lock latency guarantee. `NativeEvidenceGate` separately rejects obsolete source/caret/episode and A→B→A observations by proof identity. UI owns notification episodes, suppression, and whether current evidence should produce a notification.

## Evidence boundaries

Pure calculation contracts exercise the public use cases with SDK-free input adapters. SDK contracts must separately establish actual worker dispatch, read access, retries, cancellation, stale-result rejection, and editor lifetime. Native A→B→A admission contracts exercise proof identity separately from Driver observations of actual native settings and painted markup; neither substitutes for a deterministic delayed native-SDK completion test. View contracts must exercise rendering failure and reentrancy; packaging and supported IDE behavior require their own checks. Performance evidence distinguishes calculation cost, allocation, writer wait, and native preparation rather than treating one timing scope as all four.

The physical dependency restrictions do not establish the absence of thread or algorithm bugs. See the repository validation records for what was actually executed. The accepted responsiveness trade-off remains [ADR 0002](adr/0002-prefer-editor-write-responsiveness.md); immediate hiding before repair remains [ADR 0001](adr/0001-hide-affected-guides-before-background-repair.md).

See [Editor presentation policy](explanation_editor_presentation_policy.md) for surface behavior and [Performance and capacity reference](reference_performance_limits.md) for admitted bounds.
