package com.sijunyang.bracketpairguides.presentation

import com.sijunyang.bracketpairguides.analysis.BracketPair

/**
 * Minimal immutable coordinates for one already-applied document change.
 * [offset] and [oldLength] belong to the document state before the change;
 * [newLength] describes the replacement now present in the document.
 */
internal data class DocumentChange(val offset: Int, val oldLength: Int, val newLength: Int) {
    init {
        require(offset >= 0) { "offset must not be negative" }
        require(oldLength >= 0) { "oldLength must not be negative" }
        require(newLength >= 0) { "newLength must not be negative" }
    }

    /** Whether the old changed range intersects either tracked bracket token. */
    fun altersToken(pair: BracketPair): Boolean {
        val oldEnd = offset.toLong() + oldLength
        if (oldLength > 0) {
            return overlaps(offset.toLong(), oldEnd, pair.openOffset, pair.openTokenLength) ||
                overlaps(offset.toLong(), oldEnd, pair.closeOffset, pair.closeTokenLength)
        }

        // Boundary insertion leaves the token intact and is followed by its
        // non-greedy RangeMarker. Only insertion inside a multi-character token
        // destroys the identity of that token.
        return isInsideToken(offset, pair.openOffset, pair.openTokenLength) ||
            isInsideToken(offset, pair.closeOffset, pair.closeTokenLength)
    }

    /** Whether this change cannot have changed content inside the old pair. */
    fun isOutside(pair: BracketPair): Boolean {
        val oldEnd = offset.toLong() + oldLength
        val pairEnd = pair.closeOffset.toLong() + pair.closeTokenLength
        return oldEnd <= pair.openOffset.toLong() || offset.toLong() >= pairEnd
    }

    private fun overlaps(changeStart: Long, changeEnd: Long, tokenStart: Int, tokenLength: Int): Boolean {
        val tokenEnd = tokenStart.toLong() + tokenLength
        return changeStart < tokenEnd && changeEnd > tokenStart.toLong()
    }

    private fun isInsideToken(position: Int, tokenStart: Int, tokenLength: Int): Boolean =
        position > tokenStart && position.toLong() < tokenStart.toLong() + tokenLength
}
