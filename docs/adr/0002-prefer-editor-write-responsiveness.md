# Prefer editor write responsiveness

On 2026-10-06, the user accepted the measured cost of short, cancellable platform
capture and immutable calculation outside read access for issue #93, preserving
the responsiveness benefit over synchronous analysis.
Across three warmed IDE 241 JVM runs with the same corpora and current shared
classifier/index code, ordinary Java analysis increased from 2.62 to 3.85 ms
with 456 KB more direct coroutine allocation, and XML from 8.37 to 9.69 ms with
1.41 MB more allocation; in the separate actual-write fixture, write-wait p95
fell from 2.67 to 0.16 ms and 8.74 to 0.13 ms respectively.
The decision also accepts the reported repair/copy costs and observed native
preparation intervals above 5 ms: one platform lexer or extension callback
remains indivisible, so traversal's cooperative deadline is not a hard lock-time
guarantee, and existing CI thresholds, supported behavior, and correctness
checks remain unchanged.
