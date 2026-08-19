package com.sijunyang.bracketpairguides.analysis.snapshot

import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex

/** Immutable, editor-independent payload shared by equivalent snapshot views. */
internal class BracketIndexes(
    internal val pairs: PairTable,
    internal val tokens: BracketTokenIndex,
    internal val activePairs: ActiveBracketPairIndex,
    internal val guidePositions: GuidePositionIndex?,
)

/** Editor-specific snapshot view over immutable [BracketIndexes]. */
internal class BracketSnapshot(
    val stamp: AnalysisStamp,
    val matcherAvailability: BraceMatcherAvailability,
    private val indexes: BracketIndexes,
) {
    /** One-entry memoization preserves allocation-free movement inside one active pair. */
    @Volatile
    private var cachedActivePair: CachedPair? = null

    /** Returns the innermost pair containing [caretOffset], in O(log pairCount). */
    fun activePairAt(caretOffset: Int): BracketPair? {
        val pairIndex = indexes.activePairs.activePairIndex(caretOffset)
        if (pairIndex < 0) return null
        cachedActivePair?.takeIf { cached -> cached.index == pairIndex }?.let { cached ->
            return cached.pair
        }
        return indexes.pairs.bracketPairAt(pairIndex).also { pair ->
            cachedActivePair = CachedPair(pairIndex, pair)
        }
    }

    /** Returns an indexed guide, or null when that index was intentionally omitted. */
    fun guideFor(pair: BracketPair): BracketGuide? =
        indexes.guidePositions?.guideForOrNull(pair)

    /** Returns a capped, allocation-light token window near [range]. */
    fun visibleTokens(
        range: TextRange,
        focusOffset: Int,
        limit: Int,
    ): TokenWindow {
        require(limit > 0) { "Visible token limit must be positive" }

        val tokenIndex = indexes.tokens
        val firstCandidate = tokenIndex.firstIndexInRange(range.startOffset)
        val lastCandidate = tokenIndex.firstIndexAtOrAfter(range.endOffset)
        val candidateCount = lastCandidate - firstCandidate
        if (candidateCount <= limit) {
            return TokenWindow(
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
        return TokenWindow(
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

private data class CachedPair(val index: Int, val pair: BracketPair)

internal class TokenWindow internal constructor(
    private val tokenIndex: BracketTokenIndex,
    private val firstIndex: Int,
    private val afterLastIndex: Int,
    val isCapped: Boolean,
    val stableFocusStartOffset: Int,
    val stableFocusEndOffset: Int,
) {
    val size: Int
        get() = afterLastIndex - firstIndex

    fun offsetAt(index: Int): Int =
        tokenIndex.offsetAt(globalIndex(index))

    fun lengthAt(index: Int): Int =
        tokenIndex.lengthAt(globalIndex(index))

    fun depthAt(index: Int): Int =
        tokenIndex.depthAt(globalIndex(index))

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
