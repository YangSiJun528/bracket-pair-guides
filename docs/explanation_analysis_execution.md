# Analysis execution

Editor requests are small because `EditorAnalysisExecution` owns scheduling,
source admission, capture, calculation, and publication. The daemon pass and
`SecondaryEditorAnalysis` request work for an editor; they do not choose chunk
sizes, iterate tokens, build indexes, or publish partially assembled results.
This module keeps execution details local while the calculation seam remains
immutable and platform-free.

## Physical production modules

Production sources have five owners. Their compile dependencies form this DAG;
each row lists direct production dependencies:

| Module | Responsibility | Compile dependencies |
|---|---|---|
| `analysis-model` | Platform-free values, captured identity, outcomes, and read-only result contracts | None |
| `analysis-core` | Pairing, recognition, indexing, and guide calculation | `analysis-model` |
| `editor-ui` | Editor sessions, presentation policy, markup, settings, and request interfaces | `analysis-model` |
| `analysis-runtime` | IntelliJ capture, execution, cancellation, source validation, and publication | `analysis-model`, `analysis-core`, `editor-ui` |
| `plugin` | Descriptor registration and packaging of the production modules | `analysis-model`, `editor-ui`, `analysis-runtime` |

`editor-ui` has no compile access to core or runtime implementation. The plugin
assembly also has no core compile access. Runtime keeps its core dependency as
an implementation dependency, so packaging the core does not export it to the
assembly's compiler. Production source roots and outputs belong to one module;
shared directories, production friend paths, and Kotlin `internal` visibility
are not substitutes for this dependency structure.

The model's abstract `BracketSnapshot` exposes stamped active-pair, guide, and
bounded token-window queries. Offset ranges and immutable values cross this
seam; IntelliJ ranges and index builders do not. Core's
`IndexedBracketSnapshot` implements those queries and owns each editor's
active-pair memo. Runtime canonicalizes equivalent immutable index storage and
calls `BracketIndexes.newSnapshot` to create that implementation directly,
without a separate result wrapper allocation.

UI adapters request or cancel editor analysis through `EditorAnalysisRequests`.
Runtime's `EditorAnalysisExecution` implements that small interface and retains
scheduling, admission, calculation, and EDT publication. Settings obtains
installed matcher capability families through `InstalledBraceLanguages`;
runtime implements the listing while retaining matcher resolution and pairing
adapters. The plugin descriptor registers both interfaces with their runtime
implementations. Guide repair retains the session-owned scheduler, cancellation
job, and publication callbacks. Native inspection and notification execution
also belong to runtime.

`AnalysisStamp` holds platform-free revision, facet, language, and opaque source
identities. Lightweight editor-ui adapters capture those identity facts and
compare current editor state; they do not capture calculation input.
`AnalysisInput`, token/text capture, read epochs, and matcher interaction remain
runtime-owned. Captured language IDs are copied once, with an empty-set fast
path; narrowed stamps and their input share that immutable set. Current-source
checks read tab layout only when guide coverage requires it.

The production module verification audits the actual Java and Kotlin compile
classpaths, compiler options, source ownership, and outputs, then compiles
negative Java and Kotlin visibility probes. Positive owner controls first verify
that every forbidden implementation symbol exists and can compile in its owner;
a renamed or missing symbol cannot make the negative proof pass. Production
Java tasks use an explicit empty source path. The audit rejects added source,
bootstrap and annotation-processor paths as well as shared outputs and compiler
visibility overrides. It checks that the declared DAG is also enforced by
compiler visibility. The pure-build profile includes only
`analysis-model` and `analysis-core`, allowing their builds and the applicable
module audit to run without configuring the IntelliJ host modules. These checks
do not establish algorithm correctness or thread-lifecycle correctness; their
fixtures and execution contracts remain separate verification.

Plugin fixture tests intentionally compose the modules with a test-only direct
core dependency and Kotlin friend paths to implementation owners. Those
privileges do not enter production compile tasks. Benchmarks likewise depend
directly on model, core, and UI owners to probe their shipped implementations.

## Host adapters and pure calculation

`BracketAnalysis.analyzeInBackground(AnalysisInput)` is the host analysis entry
point. It returns a stamped `AnalysisOutcome`, or `null` when its captured input
has become stale. It must start off EDT without an enclosing read lock. The
execution module dispatches an independent `Default` job even when the request
originates inside a daemon read action. There is no synchronous production
analysis entry point.

`IncrementalAnalysis` owns one sequential capture/calculation attempt.
`BracketTokenCapture` confines editor highlighters, language definitions, and
platform token types to read access. Each capture visits at most 512 lexer
tokens and returns immutable `CapturedBracketTokens`. Its token storage starts
with capacity 32, then uses the previous successful chunk's captured-token
density to choose the next seed; dense chunks can grow to the 512-token visit
bound. This reduces sparse-chunk allocation without changing capture admission.
`TokenKind` is an opaque
platform-free identity; neither `IElementType` nor a highlighter iterator crosses
into pairing. When `PairingMachine` needs a compatibility answer, it yields a
rule request and resumes with the Boolean captured from the host matcher.
Only demanded rules are evaluated. Bounded caches retain at most 2,048 rule
answers and 1,024 strongly cached platform token kinds per attempt.

`SnapshotCalculation.prepare` receives coverage, immutable recognition,
document length, line count, and cancellation. It handles capacity and facet
policy, builds active indexes before detached token metadata, and exposes the
admitted guide-line envelope. Guide capture copies prefixes for up to 128 lines
with up to 128 characters each. An unresolved whitespace prefix continues in
chunks of at most 4,096 characters. `LineIndentation` consumes those strings
without read access; `GuidePositionIndex` builds admitted final storage once
and seals it against further mutation. `finish` receives the exact required
guide index and returns `CalculatedAnalysis` without an editor, input, or stamp.
The host stamps the result and canonicalizes equivalent immutable indexes.

A missing compatible token source produces empty undetermined recognition,
not a partial pair prefix. Pair/open-stack refusal discards partial results.
Guide-capacity refusal preserves exact token and active-pair facets in a limited
outcome. Empty coverage skips recognition. These distinctions allow publication
to preserve richer accepted results rather than replace them with a poorer
compatible result.

## Revision, cancellation, and ownership

An `AnalysisStamp` describes the source and requested facets, including document
revision, highlighter identity, settings/language selection, and relevant tab
layout. It answers whether a result is current and covers a request. A request
generation alone cannot answer that question: identical requests and compatible
narrower coverage can reuse richer work. A pending guide calculation with a
superseded tab size must be canceled even when the new request asks only for
tokens, because the old calculation still validates its own guide input.

Every capture validates source identity before and after reading. Full analysis
also validates layout at admission and completion. `AnalysisReadEpoch` advances
before platform writes, file-type changes, and plugin load/unload; an intervening
change invalidates chunk consistency. An
unrelated write restarts the attempt with a fresh capture adapter and pairing
state. A changed document, highlighter, or relevant layout returns stale `null`.
A cancellable read-action lambda can be retried by the platform; iterators are
created inside capture and pure pairing advances only after capture completes.

Before publication, EDT checks current dependencies, stamp, and the live host
file-size predicate. This last size gate also applies to cache hits. Complete,
limited, and unavailable results retain their different coverage requirements;
a late result cannot downgrade richer accepted coverage or revive stale markup.

The application service receives the platform lifetime scope and uses a
supervised child scope. Each editor has independently owned work. Semantic
supersession cancels immediately; compatible requests coalesce. Only secondary
full analysis waits 75 ms, and hidden secondary editors defer work until shown.
Editor release, project disposal where applicable, application-scope cancellation,
and service disposal prevent publication and release registry ownership.
Null-project and default-project editors use application lifetime ownership.
The originating thread checks `EditorEffectGuard` before service lookup or
scheduling so intention-preview computation creates no plugin effects.

## Immediate repair and native context

The session's UI callbacks read bounded geometry and range-marker state. They
reuse safe geometry or hide an affected guide synchronously. The host supplies
a narrow repair scheduler to the session; the session owns its returned job and
tracked-pair generation. `GuideRepairExecution` starts immediately, independent
of secondary full-analysis debounce. It captures immutable prefixes in short
read actions that validate document, highlighter, and tab stamp every time.
`GuideRepairCalculation` computes outside read access and preserves the old
line/consumed-character admission and candidate-order semantics. Publication
runs on EDT and requires both the captured source and the same session/pair
ownership. Authoritative snapshot acceptance cancels repair and wins.

Native conflict inspection has a different host seam. EDT captures small UI
facts; `NativeMarkerInspection` resolves the platform's real brace/navigation
context against committed PSI in separate cancellable read actions. Admission,
lazy-source preparation, direct classification and traversal, optional scope
classification and structural/forward traversal, and final validation are
separate phases. Traversal admits at most 8,192 owned state-machine operations
per body, with a cooperative 2 ms deadline checked alongside cancellation every
32 operations. The coroutine yields between unfinished traversal bodies.

`NativeBraceMatching` keeps its matcher stacks and continuation state private
to one inspection. `NativeCursor` retains an exact token bookmark, including
range, token-type identity, and end-boundary position; it restores a fresh
iterator inside each read action. No live iterator or document text crosses a
read-action boundary. Matcher callback order and post-callback cursor movement
are preserved. Each body validates document revision, highlighter, read epoch,
caret, block-cursor setting, and editor/project lifetime before and after work.
A source change or platform retry invalidates the whole proof, returning no
marker evidence; partially advanced continuation state is never replayed. Any
later inspection starts a fresh proof.

The traversal budget is cooperative, not a wall-clock guarantee. Lazy syntax
factory work, copying a lazy range, and lexer `setText` remain in a separate
preparation body with an unbounded preparation floor. Classification and matcher
callbacks also retain their read-access contract; one callback cannot be
preempted safely. `NativeCaptureObserver` distinguishes phase request/body
intervals and owned operations for opt-in evidence; its suspension hook runs
only after read access is released. These deterministic bounds do not establish
native p95 latency.

`NativeGuideConflictPolicy.isConflict` evaluates immutable facts after read
access is released. Its facts and rules live in `editor.policy`, where the
architecture checks prohibit IntelliJ dependencies; the host detector owns
capture and source resolution. EDT validates current UI/source ownership before
acting on its answer.

## Extending the real seams

| Change | Owning seam | Verification |
|---|---|---|
| Language/token support or matcher interaction | Host `BracketTokenCapture` and language definitions | `BracketTokenCaptureTest`, language integration through the suspend pipeline; no platform types in pure tokens |
| Pairing rules or pending/completed capacity | Pure `PairingMachine` and recognition values | Pure pairing/capacity tests, then `IncrementalAnalysisTest` for host capture parity |
| Coverage, index shape, or guide admission | Pure `SnapshotCalculation` and index builders | `SnapshotCalculationTest`, guide/index tests; preserve refusal precedence and allocation order |
| Capture scheduling or revision checks | Host `IncrementalAnalysis` and `AnalysisReadEpoch` | Actual `Default`/read-action fixtures covering cancellation, stale capture, retry, and EDT write progress |
| Editor eligibility, coalescing, or publication | `EditorAnalysisExecution` with pass/secondary adapters | `EditorAnalysisExecutionTest`, `BackgroundAnalysisLifecycleTest`, `EditorSurfacePolicyTest`, `SecondaryEditorAnalysisTest` |
| Provisional geometry or indentation repair | Presentation ownership plus pure `GuideRepairCalculation` and host `GuideRepairExecution` | Pure order/budget tests; `ProvisionalGuideTest` and `GuideRepairExecutionTest` for immediate hiding, eventual geometry, and late-result rejection |
| Native brace context or conflict policy | Host native context adapter plus pure conflict facts | `NativeBraceContextTest`, `NativeBoundedTraversalTest`, `NativeBoundedInspectionTest`, `NativeGuideMarkerSourceTest`, `NativeGuideConflictPolicyTest`, notification lifecycle fixtures |

Platform fixtures own and cancel their background scopes and pump EDT events
while awaiting completion. They exercise actual `Default` work and EDT
publication; synchronous collect/apply calls cannot establish that execution
contract. Performance measurements are opt-in evidence with explicit timing
and allocation scopes, not correctness-test timing thresholds.

See [Editor presentation policy](explanation_editor_presentation_policy.md) for
surface behavior and [Performance and capacity reference](reference_performance_limits.md)
for current bounds.

The accepted responsiveness/analysis-cost tradeoff is recorded in
[Prefer editor write responsiveness](adr/0002-prefer-editor-write-responsiveness.md).
