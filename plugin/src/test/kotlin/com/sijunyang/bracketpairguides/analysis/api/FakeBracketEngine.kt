package com.sijunyang.bracketpairguides.analysis.api

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.TextRange
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Test-only engine implementation that exercises only the public analysis contract. */
internal class FakeBracketEngine(
    private val pairProvider: (AnalyzeRequest, ProgressIndicator) -> List<BracketPair> =
        { _, _ -> emptyList() },
    private val activePairProvider: (ActivePairRequest) -> ActivePairResult =
        { ActivePairResult.Complete(null) },
    private val guideProvider: (AnalyzeRequest, BracketPair) -> BracketGuide? =
        { _, _ -> null },
    private val languageProvider: () -> List<BraceLanguageFamily> = { emptyList() },
    private val resultProvider: ((AnalyzeRequest, ProgressIndicator) -> AnalysisResult)? = null,
) : BracketEngine {
    private val analyzeCounter = AtomicInteger()
    private val activePairCounter = AtomicInteger()
    private val recordedAnalyzeRequests = CopyOnWriteArrayList<AnalyzeRequest>()
    private val recordedActivePairRequests = CopyOnWriteArrayList<ActivePairRequest>()

    internal val analyzeCallCount: Int
        get() = analyzeCounter.get()

    internal val activePairCallCount: Int
        get() = activePairCounter.get()

    internal val analyzeRequests: List<AnalyzeRequest>
        get() = recordedAnalyzeRequests.toList()

    internal val activePairRequests: List<ActivePairRequest>
        get() = recordedActivePairRequests.toList()

    override fun analyze(
        request: AnalyzeRequest,
        progress: ProgressIndicator,
    ): AnalysisResult {
        analyzeCounter.incrementAndGet()
        recordedAnalyzeRequests += request
        resultProvider?.let { provider -> return provider(request, progress) }

        val pairs = if (request.capabilities.pairs) {
            pairProvider(request, progress)
        } else {
            emptyList()
        }
        return FakeAnalysisResult.fromPairs(
            revision = request.revision,
            pairs = pairs,
            guideProvider = { pair -> guideProvider(request, pair) },
        )
    }

    override fun resolveActivePair(request: ActivePairRequest): ActivePairResult {
        activePairCounter.incrementAndGet()
        recordedActivePairRequests += request
        return activePairProvider(request)
    }

    override fun installedLanguages(): List<BraceLanguageFamily> = languageProvider()
}

/** Lambda-backed result for tests that need to control one query independently. */
internal class FakeAnalysisResult(
    override val revision: AnalysisRevision,
    private val activePairProvider: (Int) -> BracketPair? = { null },
    private val guideProvider: (BracketPair) -> BracketGuide? = { null },
    private val visibleTokenProvider: (TextRange, Int, Int) -> VisibleTokens =
        { range, _, _ -> FakeVisibleTokens.empty(range) },
) : AnalysisResult {
    override fun activePairAt(caretOffset: Int): BracketPair? =
        activePairProvider(caretOffset)

    override fun guideFor(pair: BracketPair): BracketGuide? = guideProvider(pair)

    override fun visibleTokens(
        range: TextRange,
        focusOffset: Int,
        limit: Int,
    ): VisibleTokens = visibleTokenProvider(range, focusOffset, limit)

    internal companion object {
        internal fun fromPairs(
            revision: AnalysisRevision,
            pairs: List<BracketPair>,
            guideProvider: (BracketPair) -> BracketGuide? = { null },
        ): FakeAnalysisResult {
            val stablePairs = pairs.toList()
            val tokens = tokensFrom(stablePairs)
            return FakeAnalysisResult(
                revision = revision,
                activePairProvider = { caretOffset ->
                    if (revision.capabilities.activePair) {
                        innermostPairAt(stablePairs, caretOffset)
                    } else {
                        null
                    }
                },
                guideProvider = { pair ->
                    if (revision.capabilities.guidePosition) guideProvider(pair) else null
                },
                visibleTokenProvider = { range, focusOffset, limit ->
                    if (revision.capabilities.tokens) {
                        FakeVisibleTokens.select(tokens, range, focusOffset, limit)
                    } else {
                        FakeVisibleTokens.empty(range)
                    }
                },
            )
        }
    }
}

internal data class FakeBracketToken(
    val offset: Int,
    val length: Int,
    val depth: Int,
)

internal class FakeVisibleTokens private constructor(
    private val tokens: List<FakeBracketToken>,
    override val isCapped: Boolean,
    override val stableFocusStartOffset: Int,
    override val stableFocusEndOffset: Int,
) : VisibleTokens {
    override val size: Int
        get() = tokens.size

    override fun offsetAt(index: Int): Int = tokens[index].offset

    override fun lengthAt(index: Int): Int = tokens[index].length

    override fun depthAt(index: Int): Int = tokens[index].depth

    internal companion object {
        internal fun empty(range: TextRange): FakeVisibleTokens = FakeVisibleTokens(
            tokens = emptyList(),
            isCapped = false,
            stableFocusStartOffset = range.startOffset,
            stableFocusEndOffset = range.endOffset,
        )

        internal fun of(
            tokens: List<FakeBracketToken>,
            stableFocusStartOffset: Int,
            stableFocusEndOffset: Int,
            isCapped: Boolean = false,
        ): FakeVisibleTokens = FakeVisibleTokens(
            tokens = tokens.toList(),
            isCapped = isCapped,
            stableFocusStartOffset = stableFocusStartOffset,
            stableFocusEndOffset = stableFocusEndOffset,
        )

        internal fun select(
            sortedTokens: List<FakeBracketToken>,
            range: TextRange,
            focusOffset: Int,
            limit: Int,
        ): FakeVisibleTokens {
            require(limit > 0) { "limit must be positive" }
            val candidates = sortedTokens.filter { token ->
                token.offset < range.endOffset &&
                    token.offset.toLong() + token.length > range.startOffset
            }
            if (candidates.size <= limit) {
                return FakeVisibleTokens(
                    tokens = candidates,
                    isCapped = false,
                    stableFocusStartOffset = range.startOffset,
                    stableFocusEndOffset = range.endOffset,
                )
            }

            val focusIndex = candidates.lowerBound(focusOffset)
                .coerceIn(0, candidates.size)
            var firstSelected = (focusIndex - limit / 2).coerceAtLeast(0)
            var lastSelected = (firstSelected + limit).coerceAtMost(candidates.size)
            firstSelected = (lastSelected - limit).coerceAtLeast(0)

            val selectedFocusIndex = focusIndex.coerceIn(firstSelected, lastSelected - 1)
            val tolerance = limit / 4
            val stableFirstIndex = (selectedFocusIndex - tolerance)
                .coerceAtLeast(firstSelected)
            val stableAfterLastIndex = (selectedFocusIndex + tolerance + 1)
                .coerceAtMost(lastSelected)
            return FakeVisibleTokens(
                tokens = candidates.subList(firstSelected, lastSelected),
                isCapped = true,
                stableFocusStartOffset = if (stableFirstIndex == 0) {
                    range.startOffset
                } else {
                    candidates[stableFirstIndex].offset
                },
                stableFocusEndOffset = if (stableAfterLastIndex == candidates.size) {
                    range.endOffset
                } else {
                    candidates[stableAfterLastIndex].offset
                },
            )
        }
    }
}

private fun tokensFrom(pairs: List<BracketPair>): List<FakeBracketToken> = buildList {
    for (pair in pairs) {
        if (!pair.hasWellFormedTokenRange()) continue
        add(FakeBracketToken(pair.openOffset, pair.openTokenLength, pair.depth))
        add(FakeBracketToken(pair.closeOffset, pair.closeTokenLength, pair.depth))
    }
}.sortedBy(FakeBracketToken::offset)

private fun innermostPairAt(
    pairs: List<BracketPair>,
    caretOffset: Int,
): BracketPair? {
    var winner: BracketPair? = null
    var winnerIndex = Int.MAX_VALUE
    for ((index, pair) in pairs.withIndex()) {
        if (!pair.hasWellFormedTokenRange() ||
            caretOffset <= pair.openOffset ||
            caretOffset >= pair.closeOffset.toLong() + pair.closeTokenLength
        ) {
            continue
        }
        val current = winner
        val pairEnd = pair.closeOffset.toLong() + pair.closeTokenLength
        val currentEnd = current?.let { value ->
            value.closeOffset.toLong() + value.closeTokenLength
        }
        if (current == null ||
            pair.openOffset > current.openOffset ||
            pair.openOffset == current.openOffset && pairEnd < checkNotNull(currentEnd) ||
            pair.openOffset == current.openOffset && pairEnd == currentEnd &&
            index < winnerIndex
        ) {
            winner = pair
            winnerIndex = index
        }
    }
    return winner
}

private fun List<FakeBracketToken>.lowerBound(offset: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (this[middle].offset < offset) {
            low = middle + 1
        } else {
            high = middle
        }
    }
    return low
}
