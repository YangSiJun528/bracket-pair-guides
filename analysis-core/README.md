# Bracket calculation core

`core.api.BracketCalculator` owns a complete analysis or guide-repair attempt. Its two suspend methods hide pairing, index construction, and guide calculation. The calculator is stateless: each attempt owns its calculation storage and each result has its own query memo. The module has no IntelliJ SDK dependency; results expose only `model.result.BracketView`.

The host implements `core.input.BracketInput` or `LineInput` and `CalculationControl`. These are dependency ports for bounded immutable input and cancellation, rather than interfaces mirroring implementation classes. A token capture visits at most 512 lexer tokens, an initial prefix read covers at most 128 lines with 128 characters per line, and a continuation contains at most 4096 characters. `TokenBatch.capture` transfers primitive storage and revokes its collector when capture ends. Opaque token identities are scoped to an attempt and contain no platform object.

The calculator starts a fresh capture epoch on each attempt, asks for compatibility only when needed, and validates the source after calculation. `RetryCapture` means an unrelated host write interrupted a read: the calculator discards partial state, checks cancellation, yields, and starts again. Source changes and cancellation propagate to the runtime, which owns task lifetime and final acceptance. An adapter must enforce its source epoch at every read; the core cannot inspect host stamps or read locks.

`DocumentFacts` contains document length, line count, and tab size. Runtime owns content revisions and decides whether an accepted result can satisfy a new request. The core does not compare or retain results across attempts.

All algorithm types live in `core.internal`: Kotlin declarations are internal and Java helpers are package-private. Consumers use the calculator and input contracts; they do not assemble pairing sessions or concrete indexes. The UI has no compile dependency on this module.

The new tests exercise result contracts, independent nesting expectations, token semantics, bounded input, capacities, real coroutine cancellation, retries, guide repair, and independent concurrent attempts. Run `:analysis-core:test` from the repository build, or use `tools/pure-build` for SDK-free verification. Performance is measured through the public calculator contract by the repository benchmark tools.
