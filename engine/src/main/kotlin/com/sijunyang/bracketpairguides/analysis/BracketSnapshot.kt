package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.pairing.bracketPairAt
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex
import org.jetbrains.annotations.ApiStatus

/** Immutable query boundary for analyzed bracket state. */
@ApiStatus.Internal
public interface BracketSnapshot {
    public val stamp: AnalysisStamp

    public fun activePairAt(caretOffset: Int): BracketPair?

    public fun guideFor(pair: BracketPair): BracketGuide?

    public fun visibleTokens(
        range: TextRange,
        focusOffset: Int,
        limit: Int,
    ): TokenWindow
}

/** Compact indexed implementation of [BracketSnapshot]. */
internal class IndexedBracketSnapshot(
    override val stamp: AnalysisStamp,
    private val pairs: PairTable,
    private val tokenIndex: BracketTokenIndex,
    private val activeIndex: ActiveBracketPairIndex,
    private val positionIndex: GuidePositionIndex?,
) : BracketSnapshot {
    /** One-entry memoization preserves allocation-free movement inside one active pair. */
    @Volatile
    private var cachedActivePair: IndexedPair? = null

    /** Returns the innermost pair containing [caretOffset], in O(log pairCount). */
    override fun activePairAt(caretOffset: Int): BracketPair? {
        val pairIndex = activeIndex.activePairIndex(caretOffset)
        if (pairIndex < 0) return null
        cachedActivePair?.takeIf { cached -> cached.index == pairIndex }?.let { cached ->
            return cached.pair
        }
        return pairs.bracketPairAt(pairIndex).also { pair ->
            cachedActivePair = IndexedPair(pairIndex, pair)
        }
    }

    /** Returns an indexed guide, or null when that index was intentionally omitted. */
    override fun guideFor(pair: BracketPair): BracketGuide? =
        positionIndex?.guideForOrNull(pair)

    /** Returns a capped, allocation-light token window near [range]. */
    override fun visibleTokens(
        range: TextRange,
        focusOffset: Int,
        limit: Int,
    ): TokenWindow {
        require(limit > 0) { "Visible token limit must be positive" }

        val firstCandidate = tokenIndex.firstIndexInRange(range.startOffset)
        val lastCandidate = tokenIndex.firstIndexAtOrAfter(range.endOffset)
        val candidateCount = lastCandidate - firstCandidate
        if (candidateCount <= limit) {
            return IndexedTokenWindow(
                tokenIndex = tokenIndex,
                firstIndex = firstCandidate,
                afterLastIndex = lastCandidate,
                isCapped = false,
                stableFocusStartOffset = range.startOffset,
                stableFocusEndOffset = range.endOffset,
            )
        }

        val focusIndex = tokenIndex.firstIndexAtOrAfter(focusOffset)
            .coerceIn(firstCandidate, lastCandidate)
        var firstSelected = (focusIndex - limit / 2).coerceAtLeast(firstCandidate)
        val lastSelected = minOf(
            firstSelected.toLong() + limit,
            lastCandidate.toLong(),
        ).toInt()
        firstSelected = (lastSelected - limit).coerceAtLeast(firstCandidate)

        val selectedFocusIndex = focusIndex.coerceIn(firstSelected, lastSelected - 1)
        val tolerance = limit / 4
        val stableFirstIndex = (selectedFocusIndex - tolerance)
            .coerceAtLeast(firstSelected)
        val stableAfterLastIndex = minOf(
            selectedFocusIndex.toLong() + tolerance + 1L,
            lastSelected.toLong(),
        ).toInt()
        return IndexedTokenWindow(
            tokenIndex = tokenIndex,
            firstIndex = firstSelected,
            afterLastIndex = lastSelected,
            isCapped = true,
            stableFocusStartOffset = if (stableFirstIndex == firstCandidate) {
                range.startOffset
            } else {
                tokenIndex.offsetAt(stableFirstIndex)
            },
            stableFocusEndOffset = if (stableAfterLastIndex == lastCandidate) {
                range.endOffset
            } else {
                tokenIndex.offsetAt(stableAfterLastIndex)
            },
        )
    }
}

private data class IndexedPair(val index: Int, val pair: BracketPair)

/** Primitive token window that does not expose the global token index. */
@ApiStatus.Internal
public interface TokenWindow {
    public val size: Int
    public val isCapped: Boolean
    public val stableFocusStartOffset: Int
    public val stableFocusEndOffset: Int

    public fun offsetAt(index: Int): Int
    public fun lengthAt(index: Int): Int
    public fun depthAt(index: Int): Int
}

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

    override fun offsetAt(index: Int): Int =
        tokenIndex.offsetAt(globalIndex(index))

    override fun lengthAt(index: Int): Int =
        tokenIndex.lengthAt(globalIndex(index))

    override fun depthAt(index: Int): Int =
        tokenIndex.depthAt(globalIndex(index))

    private fun globalIndex(index: Int): Int {
        if (index !in 0 until size) {
            throw IndexOutOfBoundsException("Token index $index is outside 0 until $size")
        }
        return firstIndex + index
    }
}
