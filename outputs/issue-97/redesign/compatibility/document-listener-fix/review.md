# Document listener lifetime fix — proposal, not applied

## Failure and supported API

The strict IU263.6259.32 verifier reports the one-argument
`Document.addDocumentListener(DocumentListener)` call in `DocumentCalculation.acquire`.
Original report: `../runs/20261008T115340939459Z/reports/IU-263.6259.32/IU-263.6259.32/plugins/com.sijunyang.bracketpairguides/0.0.6/deprecated-usages.txt`.
No category, threshold or suppression should change.

Read-only `javap` verified both overloads on IC241.19416.15 and IU263.6259.32.
263 `javap -v` marks only the one-argument overload `Deprecated: true`;
the `(DocumentListener, Disposable)` overload is public and not deprecated.
`Disposer.newDisposable(String)` and `Disposer.dispose(Disposable)` are public
on both selected SDKs. No reflection or version-dependent dispatch is needed.

SDK paths inspected:

- 241: `~/.gradle/caches/9.8.0/transforms/93fc9839df86027cfeccd2e2392df3f6/transformed/ideaIC-2024.1.7-aarch64/lib/*`.
- 263: `~/.gradle/caches/9.8.0/transforms/52c2ec041b5c3a081c9ec8fd5633e3a9/transformed/idea-263.6259.32-aarch64/lib/*`.

## Proposed ownership

Only `DocumentCalculation.kt` changes. `proposed.patch` is the exact proposed
diff; `DocumentCalculation.kt.proposed` is its complete result.

One private parent disposable belongs to the active document-owner interval.
The zero-to-one transition registers the existing listener through the supported
two-argument API. Additional editors increment the same owner count without
creating another registration. The one-to-zero transition disposes the private
parent, which removes the SDK listener. Reacquiring after a dormant interval
creates a fresh disposable and increments revision as before.

The weak document reference remains weak. There is no editor, document or parent
scope captured in a new plugin lambda. Actual SDK241 bytecode shows its listener
disposable retains the listener collection and listener, not an explicit Document
field, and its `dispose()` only removes that listener from the collection.
Regardless of SDK implementation details, disposal at final release is mandatory;
weak references do not substitute for unregistering the listener.

Acquire/release remain serialized by the existing calculation monitor. Disposal
stays inside this short monitor so a zero-owner reacquire cannot overlap an old
registration with the same listener identity. Otherwise the SDK rejects duplicate
listeners or old cleanup might interfere with the new interval. The parent has
only the SDK-created removal child; no plugin user callback or rendering is
registered under it. Runtime already calls calculation release outside its
publication lifecycle lock. No read action, write action, EDT hop or coroutine
job is added; document edits keep their normal SDK write/read rules.

The owner count now increments after successful registration, avoiding a count
leak if registration throws. A failed registration disposes the fresh parent and
propagates the failure; this does not promise recovery from arbitrary partially
failed SDK operations. The successful path adds one disposable per active-owner
interval, not per demand, capture or result; it changes no calculation algorithm,
cancellation ticket or presentation authority. Allocation/timing evidence from
before this production change must not automatically be relabeled as final.

## Required validation after internal integration approval and application

1. Main formats and compiles against minimum241; no deprecated-call suppression.
2. Official minimum/current actual SDK suites each run all29 cases with zero
   failure/error/skip and identical identities. In particular:
   `testSharedDocumentEditorsKeepIndependentPublicationAndCloseLifetimes`
   proves A close/disposal leaves B able to publish after an actual shared write;
   `testDocumentGenerationSurvivesStampResetAndDormantReacquire` exercises a
   completed lease then reacquire; closed-work GC and in-flight close contracts
   cover cancellation/release paths.
3. Root check and packaging boundaries run on the new source/release; no test
   seam or resource owner is added. SDK-free45 remain independent and unchanged.
4. Rebuild the release and rerun strict official13-IDE verification, including
   the failing IU263 target and minimum241. Keep this failed matrix and original
   report; do not reuse its passing rows as a new-release matrix pass.
5. Freeze new source/release hashes before formal performance. Record this
   lifetime-only allocation explicitly rather than claiming old measurements
   establish the final implementation. Visual behavior is not intentionally
   changed; final verification must retain its normal source provenance.

Factory/root cancellation uses the existing session completion hook to call
idempotent close and release the calculator owner. The current session
constructor performs field initialization and installs this hook; it invokes no
user callback or SDK lookup before that hook. No ordinary constructor-failure
path requiring a new lease protocol was identified. Recovering arbitrary OOM or
partially failed completion cleanup is outside this narrow supported-API fix;
no broad factory/session rewrite is proposed.

Approval here means the main agent's internal integration instruction after the
currently running matrix ends. User authorization for this repair already exists;
this proposal does not request another user approval.

No production file was changed and no build, IDE or measurement was run while
preparing this proposal.
