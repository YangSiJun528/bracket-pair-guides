# SDK evidence pump API exception

One local `@Suppress("UnstableApiUsage")` is retained on the `dispatched` declaration in the test-only shared `SdkEvidencePump.await`. It covers only `com.intellij.concurrency.resetThreadContext()`; no production or Qodana configuration suppression was added. Candidate and baseline use identical source.

Read-only `javap -c com.intellij.testFramework.PlatformTestUtil` inspection of the actual frozen IC-2024.1.7 `lib/testFramework.jar` establishes:

- `dispatchAllEventsInIdeEventQueue()` calls `ThreadContext.resetThreadContext()`, dispatches one event via `dispatchNextEventIfAny()`, closes the reset token, and repeats until the queue is empty.
- `dispatchNextEventIfAny()` itself does not reset the inherited thread context.
- `waitWithEventsDispatching(Supplier, BooleanSupplier, int, Runnable)` drains the queue through that bulk helper and then calls `Thread.sleep(10)` before its next completion check.
- `dispatchAllInvocationEventsInIdeEventQueue()` resets context but drains the queue, dispatching invocation events only.

No stable equivalent of one-event context-reset dispatch was found in these SDK test helpers. Adopting a bulk-drain helper or its fixed 10 ms polling loop would change writer/poll interleaving, completion-check fairness, and the explicitly recorded event-pump cadence. The harness therefore retains the same official reset-before-dispatch/close ordering, but performs one dispatch between completion/watchdog checks and records actual cadence. The experimental API is confined to a pinned-minimum-SDK measurement harness, and the runtime fixture asserts the exact SDK version. Supported production API verification does not validate this test-only API; actual minimum-SDK smoke must rerun after the source change.

The bytecode inspection was read-only, not a build or test. Temporary disassembly was written to `/private/tmp/issue97-platform-testutil.javap`. This exception documents a known test API stability limitation; it does not claim future SDK compatibility.
