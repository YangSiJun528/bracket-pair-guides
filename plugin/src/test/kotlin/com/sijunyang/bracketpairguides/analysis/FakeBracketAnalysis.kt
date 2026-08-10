package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.TextRange
import java.util.concurrent.atomic.AtomicInteger

/** Test-only analysis fixture backed by supplied bracket pairs. */
internal class FakeBracketAnalysis(
    private val pairs: (AnalysisInput, ProgressIndicator) -> List<BracketPair> =
        { _, _ -> emptyList() },
) {
    private val analyzeCounter = AtomicInteger()

    internal val analyzeCallCount: Int
        get() = analyzeCounter.get()

    fun analyze(
        input: AnalysisInput,
        progress: ProgressIndicator,
    ): AnalysisOutcome {
        analyzeCounter.incrementAndGet()
        val pairs = if (input.coverage.pairs) {
            pairs(input, progress)
        } else {
            emptyList()
        }
        return AnalysisOutcome.Complete(
            FakeBracketSnapshot.fromPairs(
                stamp = input.stamp,
                pairs = pairs,
            ),
        )
    }
}

/** Lambda-backed result for tests that need to control one query independently. */
internal class FakeBracketSnapshot(
    override val stamp: AnalysisStamp,
    private val activePair: (Int) -> BracketPair? = { null },
    private val guide: (BracketPair) -> BracketGuide? = { null },
    private val tokenWindow: (TextRange, Int, Int) -> TokenWindow =
        { range, _, _ -> FakeTokenWindow.empty(range) },
) : BracketSnapshot {
    override fun activePairAt(caretOffset: Int): BracketPair? =
        activePair(caretOffset)

    override fun guideFor(pair: BracketPair): BracketGuide? = guide(pair)

    override fun visibleTokens(
        range: TextRange,
        focusOffset: Int,
        limit: Int,
    ): TokenWindow = tokenWindow(range, focusOffset, limit)

    internal companion object {
        internal fun fromPairs(
            stamp: AnalysisStamp,
            pairs: List<BracketPair>,
            guide: (BracketPair) -> BracketGuide? = { null },
        ): FakeBracketSnapshot {
            val stablePairs = pairs.toList()
            val tokens = tokensFrom(stablePairs)
            return FakeBracketSnapshot(
                stamp = stamp,
                activePair = { caretOffset ->
                    if (stamp.coverage.activePair) {
                        innermostPairAt(stablePairs, caretOffset)
                    } else {
                        null
                    }
                },
                guide = { pair ->
                    if (stamp.coverage.guidePosition) guide(pair) else null
                },
                tokenWindow = { range, focusOffset, limit ->
                    if (stamp.coverage.tokens) {
                        FakeTokenWindow.select(tokens, range, focusOffset, limit)
                    } else {
                        FakeTokenWindow.empty(range)
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

internal class FakeTokenWindow private constructor(
    private val tokens: List<FakeBracketToken>,
    override val isCapped: Boolean,
    override val stableFocusStartOffset: Int,
    override val stableFocusEndOffset: Int,
) : TokenWindow {
    override val size: Int
        get() = tokens.size

    override fun offsetAt(index: Int): Int = tokens[index].offset

    override fun lengthAt(index: Int): Int = tokens[index].length

    override fun depthAt(index: Int): Int = tokens[index].depth

    internal companion object {
        internal fun empty(range: TextRange): FakeTokenWindow = FakeTokenWindow(
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
        ): FakeTokenWindow = FakeTokenWindow(
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
        ): FakeTokenWindow {
            require(limit > 0) { "limit must be positive" }
            val candidates = sortedTokens.filter { token ->
                token.offset < range.endOffset &&
                    token.offset.toLong() + token.length > range.startOffset
            }
            if (candidates.size <= limit) {
                return FakeTokenWindow(
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
            return FakeTokenWindow(
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
        if (!pair.hasWellFormedTokenRange(Int.MAX_VALUE)) continue
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
        if (!pair.hasWellFormedTokenRange(Int.MAX_VALUE) ||
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
