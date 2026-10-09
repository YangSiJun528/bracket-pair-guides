# Independent UI/runtime investigation

Reviewed PR #96 base 84d1a431 and committed five-module implementation a8e0bc1 using Git object reads. No builds or tests were run by this investigator. The sibling dirty implementation checkout was not modified. Read the codebase-design skill, execution/presentation explanation documents, and ADRs 0001/0002.

## Findings

No concrete source-level behavior regression was found in the UI/runtime changes. The moved scheduling, repair, capture, native traversal and native notification logic remains unchanged apart from imports, interface implementation/service registration, index facades and visibility needed to compile across modules.

The dependency direction is coherent: UI consumes model, runtime consumes UI/model/core, and plugin consumes UI/runtime/model. UI requests analysis through its own EditorAnalysisRequests interface. The implementation is registered by plugin.xml. The settings listing likewise uses InstalledBraceLanguages, so settings cannot access the matcher catalog. Existing GuideRepairScheduler remains a UI-owned callback seam injected by runtime during session installation. Runtime implementation is never looked up directly from UI.

A Git grep of editor-ui production sources found no references to runtime capture/execution classes, analysis-core pairing/builders/concrete indexes, AnalysisInput, DocumentBracketIndexes or SnapshotCalculation. This is source inspection, not proof of resolved compiler classpaths; main's separate compiler audit must establish the physical restriction.

## Lightweight stamp capture

EditorAnalysisStamp reads only modificationStamp, highlighter identity, file-type identity and tab size, then creates the model stamp. These are bounded editor revision/configuration facts. It does not read document text, inspect PSI/tokens, copy prefixes, build indexes, pair brackets or calculate guide geometry. Keeping this adapter in UI preserves UI's responsibility for accepted-result validity and repair ownership while removing its dependency on AnalysisInput.

AnalysisStamp is SDK-free and accepts opaque source identities as Any. It does not invoke platform behavior through those identities. It retains highlighter/file-type identity as the previous stamp did, so this does not add source retention. Its constructor defensively copies disabled-language IDs; AnalysisInput reuses the stamp's immutable copy, and withCoverage reuses that same immutable set through a private constructor. UI session/state construction therefore removes an unnecessary AnalysisInput object without dropping immutability.

The final committed host adapter preserves the stale-document short circuit before reading the highlighter, and only reads tab settings if guide coverage needs them. This was corrected in the prior implementation rather than left as an eager-read regression.

## Query preservation and visibility

Model BracketSnapshot exposes only activePairAt, guideFor and bounded visibleTokens. Model TokenWindow exposes read-only metadata/accessors. The concrete implementations stay core-owned, keep the previous per-snapshot active-pair memo, and preserve canonical shared storage. Switching TextRange to integer range endpoints avoids the SDK dependency and creates no extra range wrapper. No proportional collection/list is introduced in UI queries.

Cross-module UI session/source/policy/settings types were made public only where runtime needs them, while presentation implementation, EditorAnalysisState, daemon adapters and secondary observer remain internal. EditorGuideSession.documentChanged is explicitly internal to prevent its internal DocumentChange type leaking through the public class. Some members of public facades could be narrowed further as maintenance cleanup, but this does not reopen UI -> core/runtime compiler access. Core implementation visibility also cannot reopen the forbidden reachability without its jar entering the UI classpath.

## Behavior retained

- Independent Dispatchers.Default execution avoids inheriting daemon read access.
- Immediate supersession, compatible coalescing, old-guide-tab cancellation and secondary-only 75 ms debounce remain unchanged.
- Live file-size gate, exact refusal coverage, accepted snapshot/dependency ownership and EDT publication checks remain unchanged.
- Immediate affected-guide hiding, independent repair, cancellation/job and tracked-pair ownership, authoritative result precedence remain unchanged.
- Repair budgets/candidate order and native cooperative traversal/preparation bounds remain unchanged.
- Capture/read-epoch validation, fresh retry state, editor/project/application lifetime and intention-preview effect guards remain unchanged.

Existing runtime fixture tests need to stay included after source relocation. Relevant suites are IncrementalAnalysisTest, EditorAnalysisExecutionTest, BackgroundAnalysisLifecycleTest, GuideRepairExecutionTest, ProvisionalGuideTest, SecondaryEditorAnalysisTest, NativeBoundedInspectionTest/NativeBoundedTraversalTest and native marker/context tests. They exercise real worker/read/EDT behavior, stale-result rejection, cancellation, edits, lifetime, visibility and result precedence. This investigator did not execute them.

## Performance assessment

The committed prior validation report explicitly records unresolved repeated latency signals. Final analysis medians increased 6.37% for nested and 5.65% for close-only inputs; writer p95 increased 35.32% for ordinary and 22.70% for nested. Ordinary writer tails increased across all three campaigns. Native direct writer p95 increased 25.0%, and lazy direct writer p95 increased 38.7%. These observations must not be relabeled as a no-regression pass.

Plausible code-shape changes are abstract snapshot/interface window dispatch, module/jar/class layout affecting JIT profiles, and extracted stamp helper calls. The host matchesCurrent adapter first preflights source identity and then invokes pure matchesCurrent, which repeats the source identity comparison; the source reads themselves are not repeated except identity values are passed into the pure function. This is a small number of primitive/reference comparisons without allocation, loop, lock or read action. It cannot by inspection explain measured writer/native latency increases. Query implementations are single concrete types so JIT may inline/devirtualize, but no such optimization was established by this review. Native traversal source is unchanged, which also does not establish latency equivalence.

Preserve exact test/visual/performance thresholds and compare serially under the same conditions. Compiler barriers prove compile-time reachability restrictions, not absence of all threading or algorithm bugs. Prior report entries refer to different production revisions and archives; reruns and preserved artifact hashes must distinguish them.
