# Independent final SDK96 evidence review

Read-only review; no source/harness changes, builds, JVMs, IDE/Driver/Docker runs, measurements or commits. Frozen candidate HEAD8d853ea08d92150c127efe43d807771542ef84d6 includes the f65 production correction. All96 raw files match the report SHA256 references. Report has774 summaries,511 with any paired increase,40 >20% investigation flags and64 summaries increased in all six pairs. Six fresh paired fixture JVMs remain the comparison units; medians/p95 are within each JVM, never pooled. Duplicated median/p95/resource summaries are not independent defects, and40 versus prior37 flags is not three newly established defects. Thresholds and raw attempts are unchanged.

See [compact raw-count audit](independent-summary.json) for exact cancellation, writer/native overlap, refusal censoring, bounded GC and unsupported/null counts. Integrity and completed execution do not establish performance acceptance. This review does not independently re-run the full source/class inventory audit; raw hash checks bind the observed records to the frozen reporter inputs.

## Repeated client-path costs

Actual unchanged all-components caret callback remains40→288B (+248B,7.2x) and2.26116x median (+18.281us), all six pairs. Prior SDK01 was680B/+640B and2.77282x, so the correction reduces measured allocation but does not attain baseline parity. Tokens callback remains +128B over zero baseline,1.94086x median (+4.844us), also six pairs. Neither small absolute duration nor retained markup identity erases repeated synchronous work. The adapters invoke actual caretMoved once and markup enumeration is outside the interval; narrow adapter/assertion costs and headless absence of paint remain limitations. Do not infer a real display-frame regression or exact allocation-class cause from ratios alone.

Execution inherited async allocation remains all1.02509x (+93,278B), tokens1.03240x (+115,696B), six pairs. Tokens read-hold median1.13357x (+9.020us), summed read-hold1.07894x (+26.011us) are consistent increases below20%, not automatic exemptions. Construction all1.54748x (+61.917us)/+4,376B, tokens1.79672x (+66.250us)/+2,336B; candidate fixture constructs a factory per trial whereas application production shares its root factory, and baseline attaches part of UI during request. These are scoped composition costs, not isolated production per-editor construction. Close includes synchronous disposal and allocation traversal/scope differences; it excludes later async unwind and cannot be added to async allocation as whole-session cost.

Direct repair still adds73,136B(exact256) and71,072B(refusal257),1.214/1.21583x in all six pairs. Lazy token-adapter initialization reduces unused repair construction, but candidate per-read observer requests/bodies/maps remain counted while baseline repair observer is absent. No estimated constant is subtracted and the entire delta is not labelled production-only. Ordinary repair also increases8.55% allocation/+2,064B and10.57% wall/+45.448us in every pair; long-prefix allocation adds3,496/3,480B at about6%. Pure-core savings are not a substitute for actual SDK scope costs.

Edit async allocations ordinary2.67564x(+64,030B), exact327682.63213x(+117,422B), refusal327693.02245x(+118,774B) remain repeated. Candidate includes owned immediate repair and full refresh, with every owned job retained until unwind. Baseline MAIN daemon full refresh is outside this isolated fixture; both baseline refusal corpora remain censored, not zero restoration latency. Candidate successful restoration is origin-unknown, not proof repair won. The actual product responsibility change and measurement scope asymmetry both matter: neither a whole-product3x regression nor parity/automatic exemption is established. Exact256/refusal257 edit allocation decreases are retained in the report, not generalized to all edits.

## Raw-count findings

- Cancellation:900 trials per side, all900 observe a first read. Baseline0 and candidate1 completed before cancellation request; the candidate trial remains counted.
- Writer:900 per side all observe a body and unfinished analysis at actual write request. Actual inside-body overlap occurs514 baseline/497 candidate, so386/403 are retained overlap misses, not successful in-read collisions.
- Native late traversal:900 triggers per side, actual observed-phase/read overlap877 baseline/870 candidate;23/30 misses retained. Late lazy lexer:360 triggers and360 overlaps per side. No resolutionFailureClass was recorded in these result rows. No-writer rows have no trigger by design.
- Payload release:30 resource observations per side,30 released, zero survivors/timeouts. Capture release:36 per side;18 Java release,18 XML timeout with one captured string survivor each, but zero surviving batches/arrays. XML survivors are shared evidence on both implementations, not candidate-only proof of leak;5s collection limitation remains.
- Edit refusal257 and refusal32769 each have180 baseline no-owned-restoration versus180 candidate correct-guide observations. Reporter null hide latency totals540 baseline (360 refused plus180 synchronous same-line) and180 candidate (same-line). These are semantically distinct nulls;14 side/job/metric groups have null/unsupported values retained in the compact audit, not silently filled.

## Observation completeness and limits

The compact audit retains completed-before-cancel trials and whether first read was actually observed; cancellation request-to-join/read-unwind is an upper bound including scheduling, not earliest cooperative check latency. No missing overlap trial is retried or discarded. Writer queue delay is distinct from actual WriteAction request-to-acquisition; only raw writerRequestedInsideBody or native phase/read overlap records support an overlap claim. Native external dispatch gaps are not read hold time or algorithm CPU. Failure/cancellation records remain in the counts.

Payload and capture-release have one resource replicate per corpus/JVM. A duplicated median/p95 does not create30 resource trials. Bounded weak-reference survivors and null/unsupported metrics remain visible in the JSON audit; collection within5s is not a retained-heap proof, and a survivor is not automatically a leak. Reachable primitive arrays exclude object headers, reference width and SDK graph; opaque/unobserved edges limit total-memory claims. Baseline repair's empty read arrays are missing observation, not no SDK reads. Censored baseline refusal outcomes and missing restoration metrics cannot support population p95 equivalence.

The40 investigation flags must be considered with actual units and six paired values. Their causes are not established by flag count. All64 consistently positive summaries, including those below20% and undefined ratios over zero baseline, are reproduced below so no smaller repeated cost is hidden. The prior measurement-review.md and SDK01 main-assessment correctly rejected blanket parity; the final improvement does not invalidate their earlier recorded observations. Formal execution/evidence can pass while performance judgment remains unresolved or qualified. No blanket performance pass is asserted here.

## All six-pair positive summaries

Ratios are paired-median C/B; deltas retain the metric's native units (ns or bytes). Undefined means a zero/unsupported baseline ratio, not missing delta.

| Exact metric | Ratio | Delta |
|---|---:|---:|
| analysis / sample / Whitespace.java /  / allocation.coroutineAllocatedBytes / median | 1.0146 | 10664 |
| analysis / sample / Whitespace.java /  / allocation.coroutineAllocatedBytes / observedSubsetP95 | 1.0134 | 9844 |
| edit-restoration / edit-restoration / 32769-character-refusal /  / asyncAllocatedBytes / median | 3.0225 | 118774 |
| edit-restoration / edit-restoration / 32769-character-refusal /  / asyncAllocatedBytes / observedSubsetP95 | 3.0307 | 119260 |
| edit-restoration / edit-restoration / 32769-character-refusal /  / maximumObservedPollGapNs / median | 1.012 | 19270.2 |
| edit-restoration / edit-restoration / 32769-character-refusal /  / mutationAndCommitNs / median | 1.048 | 129459 |
| edit-restoration / edit-restoration / 32769-character-refusal /  / mutationAndCommitNs / observedSubsetP95 | 1.0426 | 131834 |
| edit-restoration / edit-restoration / exact-32768-character-budget /  / asyncAllocatedBytes / median | 2.6321 | 117422 |
| edit-restoration / edit-restoration / exact-32768-character-budget /  / asyncAllocatedBytes / observedSubsetP95 | 2.6409 | 118076 |
| edit-restoration / edit-restoration / exact-32768-character-budget /  / hideToObservedCorrectGuideNs / median | 1.0217 | 61228.8 |
| edit-restoration / edit-restoration / exact-32768-character-budget /  / maximumObservedPollGapNs / median | 1.0119 | 18990 |
| edit-restoration / edit-restoration / exact-32768-character-budget /  / mutationAndCommitNs / median | 1.036 | 101511 |
| edit-restoration / edit-restoration / ordinary-small-body /  / asyncAllocatedBytes / median | 2.6756 | 64030 |
| edit-restoration / edit-restoration / ordinary-small-body /  / asyncAllocatedBytes / observedSubsetP95 | 2.7657 | 67520 |
| edit-restoration / edit-restoration / same-line-special-case /  / asyncAllocatedBytes / median | undefined | 35896 |
| edit-restoration / edit-restoration / same-line-special-case /  / asyncAllocatedBytes / observedSubsetP95 | undefined | 36084 |
| execution / execution-lifecycle / all /  / close.edtAllocatedBytes / median | 1.0038 | 15992 |
| execution / execution-lifecycle / all /  / construction.edtAllocatedBytes / median | 1.3145 | 4376 |
| execution / execution-lifecycle / all /  / construction.edtAllocatedBytes / observedSubsetP95 | 1.3035 | 4288 |
| execution / execution-lifecycle / all /  / construction.wallNs / median | 1.5475 | 61916.5 |
| execution / execution-lifecycle / all /  / construction.wallNs / observedSubsetP95 | 1.3773 | 61583.5 |
| execution / execution-lifecycle / tokens /  / close.edtAllocatedBytes / median | 1.0009 | 3616 |
| execution / execution-lifecycle / tokens /  / close.edtAllocatedBytes / observedSubsetP95 | 1.0009 | 3680 |
| execution / execution-lifecycle / tokens /  / close.wallNs / median | 1.069 | 75104.2 |
| execution / execution-lifecycle / tokens /  / construction.edtAllocatedBytes / median | 1.1465 | 2336 |
| execution / execution-lifecycle / tokens /  / construction.edtAllocatedBytes / observedSubsetP95 | 1.1431 | 2312 |
| execution / execution-lifecycle / tokens /  / construction.wallNs / median | 1.7967 | 66250.2 |
| execution / execution / all /  / allocation.coroutineAllocatedBytes / median | 1.0251 | 93278 |
| execution / execution / all /  / allocation.coroutineAllocatedBytes / observedSubsetP95 | 1.0251 | 93616 |
| execution / execution / all /  / unchangedPresentationCallback.edtAllocatedBytes / median | 7.2 | 248 |
| execution / execution / all /  / unchangedPresentationCallback.edtAllocatedBytes / observedSubsetP95 | 7.2 | 248 |
| execution / execution / all /  / unchangedPresentationCallback.wallNs / median | 2.2612 | 18281.2 |
| execution / execution / all /  / unchangedPresentationCallback.wallNs / observedSubsetP95 | 1.9756 | 22708.5 |
| execution / execution / tokens /  / allocation.coroutineAllocatedBytes / median | 1.0324 | 115696 |
| execution / execution / tokens /  / allocation.coroutineAllocatedBytes / observedSubsetP95 | 1.0325 | 116068 |
| execution / execution / tokens /  / maximumObservedReadHoldNs / median | 1.1336 | 9020.25 |
| execution / execution / tokens /  / sumObservedReadHoldNs / median | 1.0789 | 26011 |
| execution / execution / tokens /  / sumObservedReadHoldNs / observedSubsetP95 | 1.088 | 34354.5 |
| execution / execution / tokens /  / unchangedPresentationCallback.edtAllocatedBytes / median | undefined | 128 |
| execution / execution / tokens /  / unchangedPresentationCallback.edtAllocatedBytes / observedSubsetP95 | undefined | 128 |
| execution / execution / tokens /  / unchangedPresentationCallback.wallNs / median | 1.9409 | 4843.75 |
| native / sample / large-java:direct / none / maximumObservedRequestToEnterNs / median | 1.1951 | 2437.5 |
| payload / sample / Closers.java /  / allocation.coroutineAllocatedBytes / median | 1.0063 | 23768 |
| payload / sample / Closers.java /  / allocation.coroutineAllocatedBytes / observedSubsetP95 | 1.0063 | 23768 |
| payload / sample / Whitespace.java /  / allocation.coroutineAllocatedBytes / median | 1.019 | 13904 |
| payload / sample / Whitespace.java /  / allocation.coroutineAllocatedBytes / observedSubsetP95 | 1.019 | 13904 |
| repair / sample / 257-line-refusal /  / allocation.coroutineAllocatedBytes / median | 1.2158 | 71072 |
| repair / sample / 257-line-refusal /  / allocation.coroutineAllocatedBytes / observedSubsetP95 | 1.2158 | 71072 |
| repair / sample / 32769-character-refusal /  / allocation.coroutineAllocatedBytes / median | 1.0598 | 3480 |
| repair / sample / 32769-character-refusal /  / allocation.coroutineAllocatedBytes / observedSubsetP95 | 1.0607 | 3528 |
| repair / sample / exact-256-line-budget /  / allocation.coroutineAllocatedBytes / median | 1.214 | 73136 |
| repair / sample / exact-256-line-budget /  / allocation.coroutineAllocatedBytes / observedSubsetP95 | 1.2321 | 79328 |
| repair / sample / exact-256-line-budget /  / wallNs / observedSubsetP95 | 1.3847 | 470500 |
| repair / sample / exact-32768-character-budget /  / allocation.coroutineAllocatedBytes / median | 1.06 | 3496 |
| repair / sample / exact-32768-character-budget /  / allocation.coroutineAllocatedBytes / observedSubsetP95 | 1.06 | 3496 |
| repair / sample / ordinary-small-body /  / allocation.coroutineAllocatedBytes / median | 1.0855 | 2064 |
| repair / sample / ordinary-small-body /  / allocation.coroutineAllocatedBytes / observedSubsetP95 | 1.0855 | 2064 |
| repair / sample / ordinary-small-body /  / wallNs / median | 1.1057 | 45447.8 |
| repair / sample / same-line-special-case /  / allocation.coroutineAllocatedBytes / median | 1.0524 | 792 |
| repair / sample / same-line-special-case /  / allocation.coroutineAllocatedBytes / observedSubsetP95 | 1.0567 | 852 |
| write-wait / write-wait / Nested.xml /  / maximumObservedReadHoldNs / median | 1.0499 | 3353.75 |
| write-wait / write-wait / Nested.xml /  / queueDelayNs / median | 1.0364 | 5291.25 |
| write-wait / write-wait / Nested.xml /  / queueDelayNs / observedSubsetP95 | 1.0611 | 11167.5 |
| write-wait / write-wait / Ordinary.java /  / waitNs / median | 1.1232 | 4812.5 |
