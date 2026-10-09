package com.sijunyang.bracketpairguides.core.internal

import com.sijunyang.bracketpairguides.model.BracketGuide
import com.sijunyang.bracketpairguides.model.BracketPair
import com.sijunyang.bracketpairguides.model.OffsetRange
import com.sijunyang.bracketpairguides.model.result.BracketView
import com.sijunyang.bracketpairguides.model.result.TokenWindow

/** Editor-specific query view over immutable shared indexes. */
internal class IndexedBracketView(private val indexes: BracketIndexes) : BracketView {
    /** One-entry memoization preserves allocation-free movement inside one active pair. */
    @Volatile
    private var cachedActivePair: CachedPair? = null

    /** Returns the innermost pair containing [offset], in O(log pairCount). */
    override fun activePairAt(offset: Int): BracketPair? {
        val pairIndex = indexes.activePairs.activePairIndex(offset)
        if (pairIndex < 0) return null
        cachedActivePair?.takeIf { cached -> cached.index == pairIndex }?.let { cached ->
            return cached.pair
        }
        return indexes.pairs.bracketPairAt(pairIndex).also { pair ->
            cachedActivePair = CachedPair(pairIndex, pair)
        }
    }

    /** Returns an indexed guide, or null when that index was intentionally omitted. */
    override fun guideFor(pair: BracketPair): BracketGuide? = indexes.guidePositions?.guideForOrNull(pair)

    /** Returns a capped, allocation-light token window near the supplied offset range. */
    override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow {
        val startOffset = range.startOffset
        val endOffset = range.endOffset
        require(startOffset in 0..endOffset) {
            "Visible token range must be nonnegative and ordered"
        }
        require(maximum > 0) { "Visible token limit must be positive" }

        val tokenIndex = indexes.tokens
        val firstCandidate = tokenIndex.firstIndexInRange(startOffset)
        val lastCandidate = tokenIndex.firstIndexAtOrAfter(endOffset)
        val candidateCount = lastCandidate - firstCandidate
        if (candidateCount <= maximum) {
            return IndexedTokenWindow(
                tokenIndex = tokenIndex,
                firstIndex = firstCandidate,
                afterLastIndex = lastCandidate,
                isCapped = false,
                stableFocusStartOffset = startOffset,
                stableFocusEndOffset = endOffset,
            )
        }

        val focusIndex =
            tokenIndex
                .firstIndexAtOrAfter(focus)
                .coerceIn(firstCandidate, lastCandidate)
        var firstSelected = (focusIndex - maximum / 2).coerceAtLeast(firstCandidate)
        val lastSelected =
            minOf(
                firstSelected.toLong() + maximum,
                lastCandidate.toLong(),
            ).toInt()
        firstSelected = (lastSelected - maximum).coerceAtLeast(firstCandidate)

        val selectedFocusIndex = focusIndex.coerceIn(firstSelected, lastSelected - 1)
        val tolerance = maximum / 4
        val stableFirstIndex =
            (selectedFocusIndex - tolerance)
                .coerceAtLeast(firstSelected)
        val stableAfterLastIndex =
            minOf(
                selectedFocusIndex.toLong() + tolerance + 1L,
                lastSelected.toLong(),
            ).toInt()
        return IndexedTokenWindow(
            tokenIndex = tokenIndex,
            firstIndex = firstSelected,
            afterLastIndex = lastSelected,
            isCapped = true,
            stableFocusStartOffset =
            if (stableFirstIndex == firstCandidate) {
                startOffset
            } else {
                tokenIndex.offsetAt(stableFirstIndex)
            },
            stableFocusEndOffset =
            if (stableAfterLastIndex == lastCandidate) {
                endOffset
            } else {
                tokenIndex.offsetAt(stableAfterLastIndex)
            },
        )
    }
}

private data class CachedPair(val index: Int, val pair: BracketPair)

private class IndexedTokenWindow(
    private val tokenIndex: BracketTokenIndex,
    private val firstIndex: Int,
    private val afterLastIndex: Int,
    override val isCapped: Boolean,
    override val stableFocusStartOffset: Int,
    override val stableFocusEndOffset: Int,
) : TokenWindow {
    override val size: Int
        get() = afterLastIndex - firstIndex

    override fun offsetAt(index: Int): Int = tokenIndex.offsetAt(globalIndex(index))

    override fun lengthAt(index: Int): Int = tokenIndex.lengthAt(globalIndex(index))

    override fun depthAt(index: Int): Int = tokenIndex.depthAt(globalIndex(index))

    private fun globalIndex(index: Int): Int {
        if (index !in 0 until size) {
            throw IndexOutOfBoundsException("Token index $index is outside 0 until $size")
        }
        return firstIndex + index
    }
}

private fun PairTable.bracketPairAt(index: Int): BracketPair = BracketPair(
    openOffset = openOffsetAt(index),
    openTokenLength = openTokenLengthAt(index),
    closeOffset = closeOffsetAt(index),
    closeTokenLength = closeTokenLengthAt(index),
    depth = depthAt(index),
    openLine = openLineAt(index),
    closeLine = closeLineAt(index),
)
