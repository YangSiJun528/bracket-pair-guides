package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair

/** Compact, offset-sorted lookup for bracket tokens near the editor viewport. */
internal class BracketTokenIndex private constructor(
    private val pairs: List<BracketPair>,
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
        val pair = pairAt(index)
        return if (isClosingToken(index)) pair.closeTokenLength else pair.openTokenLength
    }

    fun depthAt(index: Int): Int = pairAt(index).depth

    private fun pairAt(index: Int): BracketPair {
        return pairs[tokenReferenceAt(index) ushr TOKEN_KIND_BITS]
    }

    private fun isClosingToken(index: Int): Boolean {
        return tokenReferenceAt(index) and CLOSING_TOKEN != 0
    }

    private fun tokenReferenceAt(index: Int): Int = encodedTokens[index].toInt()

    companion object {
        fun build(
            pairs: List<BracketPair>,
            checkCanceled: () -> Unit = {},
        ): BracketTokenIndex {
            if (pairs.isEmpty()) return EMPTY
            require(pairs.size <= Int.MAX_VALUE / TOKENS_PER_PAIR)

            val encoded = LongArray(pairs.size * TOKENS_PER_PAIR)
            var tokenCount = 0
            var maximumLength = 0
            for (pairIndex in pairs.indices) {
                if (pairIndex and CANCELLATION_MASK == 0) checkCanceled()
                val pair = pairs[pairIndex]
                if (pair.openOffset >= 0 && pair.openTokenLength > 0) {
                    encoded[tokenCount++] = encode(pair.openOffset, pairIndex, closing = false)
                    maximumLength = maxOf(maximumLength, pair.openTokenLength)
                }
                if (pair.closeOffset >= 0 && pair.closeTokenLength > 0) {
                    encoded[tokenCount++] = encode(pair.closeOffset, pairIndex, closing = true)
                    maximumLength = maxOf(maximumLength, pair.closeTokenLength)
                }
            }
            checkCanceled()

            val sorted = if (tokenCount == encoded.size) encoded else encoded.copyOf(tokenCount)
            sorted.sort()
            checkCanceled()
            return BracketTokenIndex(pairs, sorted, maximumLength)
        }

        private fun encode(offset: Int, pairIndex: Int, closing: Boolean): Long {
            val tokenReference = (pairIndex shl TOKEN_KIND_BITS) or
                if (closing) CLOSING_TOKEN else 0
            return (offset.toLong() shl OFFSET_SHIFT) or
                (tokenReference.toLong() and TOKEN_REFERENCE_MASK)
        }

        private val EMPTY = BracketTokenIndex(emptyList(), LongArray(0), 0)
        private const val TOKENS_PER_PAIR = 2
        private const val TOKEN_KIND_BITS = 1
        private const val CLOSING_TOKEN = 1
        private const val OFFSET_SHIFT = 32
        private const val TOKEN_REFERENCE_MASK = 0xFFFF_FFFFL
        private const val CANCELLATION_MASK = 0xFF
    }
}
