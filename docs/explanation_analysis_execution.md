# Analysis execution

A presentation request describes desired state. Runtime turns that request into independently cancellable work, and core owns a complete calculation attempt. This division keeps SDK scheduling and result acceptance out of calculation while keeping pairing and index construction out of presentation and runtime callers.

The [module architecture](explanation_module_architecture.md) explains compiler dependencies and contract ownership. This document explains execution and result lifetime.

## Desired state and work ownership

`ui.work.GuideWorkFactory.attach` connects one editor view to one `GuideWork`. `EditorGuide` submits a frozen `GuideDemand` through `reconcile`; it does not start coroutines or sequence worker cancellation. The demand contains coverage, language selection, visibility, presentation revisions, repair intent, and native interest. A daemon pass calls `refresh()` as an independent wake-up.

`RuntimeGuideWorkFactory` creates `EditorAnalysisSession`, which owns the accepted result and three independently revocable work lanes: full analysis, repair, and native inspection. Reconciliation revokes obsolete work before scheduling replacements. Compatible richer work can satisfy a narrower demand. Each editor retains its own accepted-result authority even when split editors share a document calculator.

Work starts on `Dispatchers.Default` without inheriting a caller's read action. Full analysis starts without a debounce delay for every editor kind; semantic supersession cancels immediately. Repair starts immediately and has a separate ticket. Hidden demands revoke all work lanes and publication authority and release the accepted result. Editor/project/application lifetime and explicit close revoke publication and release attachments.

Visibility return requests fresh background analysis through the normal scheduling path. While visible, an accepted result can satisfy another request when content revision, highlighter/file type, tab size, coverage, language selection, semantic-environment generation and current file-size eligibility still match. Source and lifecycle authority are checked again after potentially reentrant SDK effects.

The global write epoch used to restart bounded capture is separate from the semantic-environment generation used for result reuse. A write to an unrelated document can require a capture retry but does not by itself make an accepted result obsolete. Document changes and relevant language/plugin environment changes invalidate reuse.

## One complete core attempt

Runtime calls the public final `core.api.BracketCalculator` through `analyze` or `repair`. These are complete use cases. Runtime supplies a bounded input adapter and cancellation control; it never assembles pairing sessions, prepares index builders, or supplies concrete indexes for publication.

`EditorSource` implements core-owned `BracketInput` and `LineInput`. An attempt begins with platform-free document facts: length, line count, tab size, and reuse revision. Each token capture visits at most 512 lexer tokens; the bound counts tokens, not characters. A single long syntax token may span many characters. Captures return immutable primitive columns through `TokenBatch.capture`, whose collector is revoked at completion. Ordered nonoverlapping tokens must fit before the next capture boundary, and the final boundary must equal the document length. An adapter cannot turn a prematurely ended prefix into an authoritative result.

The host retains highlighters, iterators, language definitions, and platform token types. Core receives opaque token identities and asks the input adapter for compatibility only when the pairing calculation needs an answer. The demanded-answer cache is bounded at 2,048; the host strongly caches at most 1,024 platform token kinds per attempt. No live iterator or host object enters model results.

Core builds active-pair indexes before retaining token metadata and guide storage. Guide admission computes the required multiline line envelope first. Initial prefix capture covers at most 128 lines with at most 128 characters per line; unresolved whitespace continues in chunks of at most 4,096 characters. Indentation scanning, sorting, index building, and guide queries execute after read access is released. Builders seal final arrays once and cannot mutate the returned result.

Pair or pending-open refusal discards the partial pairing result. A missing compatible token source also discards an earlier recognized prefix and returns empty undetermined recognition. Guide-capacity refusal retains exact token and active-pair coverage and marks guide coverage absent. `AnalysisResult` expresses those differences; a bounded token window is never a substitute for a complete analysis result.

## Capture validity, retry, and cancellation

`SourceIdentity` is runtime-private. It captures document content revision, file type, highlighter identity, language selection, and tab layout. Every SDK read validates current source identity and cancellation before and after capture. Admission and final validation also check layout. A save-state change to `modificationStamp` alone does not invalidate the source or a completed result. `AnalysisReadEpoch` changes around relevant platform writes and plugin/file-type changes to invalidate a multi-read attempt.

Core owns the retry decision for `RetryCapture`, which means an unrelated host write invalidated chunk consistency. It discards the whole attempt, checks cancellation, yields, and starts a fresh capture epoch. A changed source produces `SourceChanged`; the runtime session rejects the old work and schedules against the current source. Publication-time invalidation follows the same rule, without waiting for an edit or daemon wake-up. Platform retries create fresh read-action-local iterators, while pure pairing advances only after successful capture.

Completion releases a lane's pending request only while its ticket and source generation still belong to that task. An obsolete completion cannot clear or restart newer work. Identical demands coalesce against valid accepted results, active work, or completed repair targets; failed work does not permanently satisfy them. Repair deduplication includes content and environment validity. A repair retry may reuse its pair coordinates only while content is unchanged; otherwise full analysis discovers the current pairs. Unexpected failures are logged and remain retryable by a later demand, without an automatic failure loop.

`CalculationControl` combines coroutine cancellation, platform cancellation, task tickets, and editor lifetime. Pure loops check cancellation during recognition, sorting/merging/copying, and guide scanning/sealing. These are cooperative checks: one JVM array copy, allocation, lexer operation, or extension callback is not preempted halfway through its operation.

## Reuse and final approval

`DocumentCalculation` shares one calculator and monotone content revision between views of the same document. The revision advances before each document change, so every `documentChanged` listener sees the new value regardless of registration order. Updating and comparing it takes constant time; no whole-document comparison or hash is needed. Capture, accepted-result reuse, and publication use this same revision. Its last session releases the document listener, and reacquisition after an unobserved interval advances the revision conservatively. A matching content revision still requires the environment, demand, and lifetime checks before display.

Each full calculation builds and returns its own immutable indexes. Equivalent split-editor results retain separate index storage, and each `BracketView` owns its query memo. The stateless calculator holds no result references between attempts.

Full results return to EDT for source, demand, ticket, lifetime, and live file-size approval. The size predicate also gates accepted-result reuse. Runtime first asks `GuideView.applyAnalysis` to apply the update. Rendering can synchronously reenter through SDK listeners or fail. Runtime commits acceptance only after `ViewApplication.APPLIED`, another validity check, and a final lifecycle/ticket check. SDK rendering does not run under the lifecycle lock. Closing immediately revokes authority and clears editor/view/scope references; in-flight lexical captures unwind before their local references become collectible.

Repair uses the same source and cancellation discipline but returns geometry for an already tracked pair. It preserves the 256-line and 32,768-consumed-character budgets, earliest-line tie rule, and exact/provisional scan order. A refused exact repair leaves existing provisional geometry in place, or no guide when no tracked geometry exists. Final repair application also requires the current guide revision and tracked-pair ownership. Authoritative full analysis revokes competing repair before application.

## Native evidence

Paint callbacks submit cheap displayed-guide evidence through `observeNativeGuide`; native input inspection runs later. `runtime.nativeproof` resolves committed PSI and actual platform brace/navigation context in cancellable read actions. The native traversal owns its matcher stacks and exact cursor bookmarks, recreating iterators within read access. A source change or platform retry invalidates the proof rather than replaying a partially advanced continuation.

Each traversal body admits at most 8,192 owned operations and checks a cooperative 2 ms deadline alongside cancellation every 32 operations. Work yields between unfinished bodies. Lazy preparation and one extension callback remain indivisible, so these bounds are not a hard read-lock latency guarantee. `NativeEvidenceGate` separately rejects obsolete source/caret/episode and A→B→A observations by proof identity. UI owns notification episodes, suppression, and whether current evidence should produce a notification.

## Evidence boundaries

Pure calculation contracts exercise the public use cases with SDK-free input adapters. SDK contracts must separately establish actual worker dispatch, read access, retries, cancellation, stale-result rejection, and editor lifetime. Native A→B→A admission contracts exercise proof identity separately from Driver observations of actual native settings and painted markup; neither substitutes for a deterministic delayed native-SDK completion test. View contracts must exercise rendering failure and reentrancy; packaging and supported IDE behavior require their own checks. Performance evidence distinguishes calculation cost, allocation, writer wait, and native preparation rather than treating one timing scope as all four.

The physical dependency restrictions do not establish the absence of thread or algorithm bugs. See the repository validation records for what was actually executed. The accepted responsiveness trade-off remains [ADR 0002](adr/0002-prefer-editor-write-responsiveness.md); the revised provisional-retention trade-off is recorded in [ADR 0001](adr/0001-hide-affected-guides-before-background-repair.md).

See [Editor presentation policy](explanation_editor_presentation_policy.md) for surface behavior and [Performance and capacity reference](reference_performance_limits.md) for admitted bounds.
