package com.sijunyang.bracketpairguides.analysis.token

import com.sijunyang.bracketpairguides.analysis.sorting.sortCancellable
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable

/** Compact, offset-sorted lookup for bracket tokens near the editor viewport. */
internal class BracketTokenIndex private constructor(
    private val pairs: PairTable?,
    private val detachedTokenLengths: LongArray?,
    private val detachedDepths: IntArray?,
    private val encodedTokens: LongArray,
    private val maximumTokenLength: Int,
    checkCanceled: () -> Unit,
) {
    private val semanticHash: Int = calculateSemanticHash(checkCanceled)

    /** Exact equality of every value observable through the token-index query API. */
    internal fun hasSameContent(
        other: BracketTokenIndex,
        checkCanceled: () -> Unit,
    ): Boolean {
        checkCanceled()
        if (this === other) return true
        if (maximumTokenLength != other.maximumTokenLength ||
            encodedTokens.size != other.encodedTokens.size ||
            semanticHash != other.semanticHash
        ) {
            return false
        }
        for (index in encodedTokens.indices) {
            if (index and CANCELLATION_MASK == 0) checkCanceled()
            if (offsetAt(index) != other.offsetAt(index) ||
                lengthAt(index) != other.lengthAt(index) ||
                depthAt(index) != other.depthAt(index)
            ) {
                return false
            }
        }
        return true
    }

    private fun calculateSemanticHash(checkCanceled: () -> Unit): Int {
        var hash = 31 + maximumTokenLength
        for (index in encodedTokens.indices) {
            if (index and CANCELLATION_MASK == 0) checkCanceled()
            hash = 31 * hash + offsetAt(index)
            hash = 31 * hash + lengthAt(index)
            hash = 31 * hash + depthAt(index)
        }
        return hash
    }

    fun firstIndexInRange(startOffset: Int): Int {
        val firstPossibleStart = (startOffset.toLong() - maximumTokenLength)
            .coerceAtLeast(0)
            .toInt()
        var low = 0
        var high = encodedTokens.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (offsetAt(middle) < firstPossibleStart) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    fun firstIndexAtOrAfter(offset: Int): Int {
        var low = 0
        var high = encodedTokens.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (offsetAt(middle) < offset) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    fun offsetAt(index: Int): Int = (encodedTokens[index] ushr OFFSET_SHIFT).toInt()

    fun lengthAt(index: Int): Int {
        val tokenReference = tokenReferenceAt(index)
        val pairIndex = tokenReference ushr TOKEN_KIND_BITS
        val closing = tokenReference and CLOSING_TOKEN != 0
        pairs?.let { table ->
            return if (closing) {
                table.closeTokenLengthAt(pairIndex)
            } else {
                table.openTokenLengthAt(pairIndex)
            }
        }
        val lengths = checkNotNull(detachedTokenLengths)
        val packedLengths = lengths[pairIndex]
        return if (closing) {
            packedLengths.toInt()
        } else {
            (packedLengths ushr OFFSET_SHIFT).toInt()
        }
    }

    fun depthAt(index: Int): Int {
        val pairIndex = tokenReferenceAt(index) ushr TOKEN_KIND_BITS
        return pairs?.depthAt(pairIndex)
            ?: checkNotNull(detachedDepths)[pairIndex]
    }

    private fun tokenReferenceAt(index: Int): Int = encodedTokens[index].toInt()

    companion object {
        internal fun build(
            pairs: PairTable,
            checkCanceled: () -> Unit,
        ): BracketTokenIndex = build(
            pairs = pairs,
            checkCanceled = checkCanceled,
            detachPairMetadata = false,
        )

        internal fun buildDetached(
            pairs: PairTable,
            checkCanceled: () -> Unit,
        ): BracketTokenIndex = build(
            pairs = pairs,
            checkCanceled = checkCanceled,
            detachPairMetadata = true,
        )

        private fun build(
            pairs: PairTable,
            checkCanceled: () -> Unit,
            detachPairMetadata: Boolean,
        ): BracketTokenIndex {
            if (pairs.isEmpty) return EMPTY
            require(pairs.size() <= Int.MAX_VALUE / TOKENS_PER_PAIR)

            val encoded = LongArray(pairs.size() * TOKENS_PER_PAIR)
            var tokenCount = 0
            var maximumLength = 0
            for (pairIndex in 0 until pairs.size()) {
                if (pairIndex and CANCELLATION_MASK == 0) checkCanceled()
                if (!pairs.hasWellFormedTokenRangeAt(pairIndex)) continue

                encoded[tokenCount++] = encode(
                    pairs.openOffsetAt(pairIndex),
                    pairIndex,
                    closing = false,
                )
                encoded[tokenCount++] = encode(
                    pairs.closeOffsetAt(pairIndex),
                    pairIndex,
                    closing = true,
                )
                maximumLength = maxOf(
                    maximumLength,
                    pairs.openTokenLengthAt(pairIndex),
                    pairs.closeTokenLengthAt(pairIndex),
                )
            }
            if (tokenCount == 0) {
                checkCanceled()
                return EMPTY
            }
            val sorted = if (tokenCount == encoded.size) encoded else encoded.copyOf(tokenCount)
            sorted.sortCancellable(checkCanceled)
            val detachedMetadata = if (detachPairMetadata) {
                copyDetachedMetadata(pairs, checkCanceled)
            } else {
                null
            }
            return BracketTokenIndex(
                pairs = pairs.takeUnless { detachPairMetadata },
                detachedTokenLengths = detachedMetadata?.lengths,
                detachedDepths = detachedMetadata?.depths,
                encodedTokens = sorted,
                maximumTokenLength = maximumLength,
                checkCanceled = checkCanceled,
            )
        }

        /** Runs after sorting so these arrays do not overlap its merge workspace. */
        private fun copyDetachedMetadata(
            pairs: PairTable,
            checkCanceled: () -> Unit,
        ): DetachedMetadata {
            checkCanceled()
            val lengths = LongArray(pairs.size())
            checkCanceled()
            val depths = IntArray(pairs.size())
            for (pairIndex in 0 until pairs.size()) {
                if (pairIndex != 0 && (pairIndex and CANCELLATION_MASK) == 0) {
                    checkCanceled()
                }
                if (!pairs.hasWellFormedTokenRangeAt(pairIndex)) continue
                lengths[pairIndex] = packLengths(
                    pairs.openTokenLengthAt(pairIndex),
                    pairs.closeTokenLengthAt(pairIndex),
                )
                depths[pairIndex] = pairs.depthAt(pairIndex)
            }
            checkCanceled()
            return DetachedMetadata(lengths, depths)
        }

        private fun encode(offset: Int, pairIndex: Int, closing: Boolean): Long {
            val tokenReference = (pairIndex shl TOKEN_KIND_BITS) or
                if (closing) CLOSING_TOKEN else 0
            return (offset.toLong() shl OFFSET_SHIFT) or
                (tokenReference.toLong() and TOKEN_REFERENCE_MASK)
        }

        private fun packLengths(openLength: Int, closeLength: Int): Long =
            (openLength.toLong() shl OFFSET_SHIFT) or
                (closeLength.toLong() and TOKEN_REFERENCE_MASK)

        private class DetachedMetadata(
            val lengths: LongArray,
            val depths: IntArray,
        )

        private val EMPTY = BracketTokenIndex(
            pairs = null,
            detachedTokenLengths = null,
            detachedDepths = null,
            encodedTokens = LongArray(0),
            maximumTokenLength = 0,
            checkCanceled = {},
        )
        private const val TOKENS_PER_PAIR = 2
        private const val TOKEN_KIND_BITS = 1
        private const val CLOSING_TOKEN = 1
        private const val OFFSET_SHIFT = 32
        private const val TOKEN_REFERENCE_MASK = 0xFFFF_FFFFL
        private const val CANCELLATION_MASK = 0xFF
    }
}
