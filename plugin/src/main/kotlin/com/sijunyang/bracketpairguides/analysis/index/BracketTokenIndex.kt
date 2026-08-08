package com.sijunyang.bracketpairguides.analysis.index

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.hasWellFormedTokenRange

/** Compact, offset-sorted lookup for bracket tokens near the editor viewport. */
internal class BracketTokenIndex private constructor(
    private val pairs: List<BracketPair>?,
    private val detachedTokenLengths: LongArray?,
    private val detachedDepths: IntArray?,
    private val encodedTokens: LongArray,
    private val maximumTokenLength: Int,
) {
    val size: Int
        get() = encodedTokens.size

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

    fun countIn(startOffset: Int, endOffset: Int): Int {
        var index = firstIndexInRange(startOffset)
        var count = 0
        while (index < encodedTokens.size) {
            val tokenOffset = offsetAt(index)
            if (tokenOffset >= endOffset) break
            if (tokenOffset.toLong() + lengthAt(index) > startOffset) count++
            index++
        }
        return count
    }

    fun offsetAt(index: Int): Int = (encodedTokens[index] ushr OFFSET_SHIFT).toInt()

    fun lengthAt(index: Int): Int {
        val tokenReference = tokenReferenceAt(index)
        val pairIndex = tokenReference ushr TOKEN_KIND_BITS
        val closing = tokenReference and CLOSING_TOKEN != 0
        pairs?.get(pairIndex)?.let { pair ->
            return if (closing) pair.closeTokenLength else pair.openTokenLength
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
        return pairs?.get(pairIndex)?.depth
            ?: checkNotNull(detachedDepths)[pairIndex]
    }

    private fun tokenReferenceAt(index: Int): Int = encodedTokens[index].toInt()

    companion object {
        fun build(
            pairs: List<BracketPair>,
            checkCanceled: () -> Unit = {},
        ): BracketTokenIndex = build(
            pairs = pairs,
            checkCanceled = checkCanceled,
            detachPairMetadata = false,
        )

        /** Copies only token metadata so the source pair graph can be released. */
        fun buildDetached(
            pairs: List<BracketPair>,
            checkCanceled: () -> Unit = {},
        ): BracketTokenIndex = build(
            pairs = pairs,
            checkCanceled = checkCanceled,
            detachPairMetadata = true,
        )

        private fun build(
            pairs: List<BracketPair>,
            checkCanceled: () -> Unit,
            detachPairMetadata: Boolean,
        ): BracketTokenIndex {
            if (pairs.isEmpty()) return EMPTY
            require(pairs.size <= Int.MAX_VALUE / TOKENS_PER_PAIR)

            val encoded = LongArray(pairs.size * TOKENS_PER_PAIR)
            var tokenCount = 0
            var maximumLength = 0
            for (pairIndex in pairs.indices) {
                if (pairIndex and CANCELLATION_MASK == 0) checkCanceled()
                val pair = pairs[pairIndex]
                if (!pair.hasWellFormedTokenRange()) continue

                encoded[tokenCount++] = encode(pair.openOffset, pairIndex, closing = false)
                encoded[tokenCount++] = encode(pair.closeOffset, pairIndex, closing = true)
                maximumLength = maxOf(
                    maximumLength,
                    pair.openTokenLength,
                    pair.closeTokenLength,
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
            )
        }

        /** Runs after sorting so these arrays do not overlap its merge workspace. */
        private fun copyDetachedMetadata(
            pairs: List<BracketPair>,
            checkCanceled: () -> Unit,
        ): DetachedMetadata {
            checkCanceled()
            val lengths = LongArray(pairs.size)
            checkCanceled()
            val depths = IntArray(pairs.size)
            for (pairIndex in pairs.indices) {
                if (pairIndex != 0 && (pairIndex and CANCELLATION_MASK) == 0) {
                    checkCanceled()
                }
                val pair = pairs[pairIndex]
                if (!pair.hasWellFormedTokenRange()) continue
                lengths[pairIndex] = packLengths(
                    pair.openTokenLength,
                    pair.closeTokenLength,
                )
                depths[pairIndex] = pair.depth
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

        private data class DetachedMetadata(
            val lengths: LongArray,
            val depths: IntArray,
        )

        private val EMPTY = BracketTokenIndex(
            pairs = emptyList(),
            detachedTokenLengths = null,
            detachedDepths = null,
            encodedTokens = LongArray(0),
            maximumTokenLength = 0,
        )
        private const val TOKENS_PER_PAIR = 2
        private const val TOKEN_KIND_BITS = 1
        private const val CLOSING_TOKEN = 1
        private const val OFFSET_SHIFT = 32
        private const val TOKEN_REFERENCE_MASK = 0xFFFF_FFFFL
        private const val CANCELLATION_MASK = 0xFF
    }
}
