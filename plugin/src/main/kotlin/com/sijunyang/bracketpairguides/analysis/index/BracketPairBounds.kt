package com.sijunyang.bracketpairguides.analysis.index

import com.sijunyang.bracketpairguides.analysis.BracketPair

/** Overflow-safe validation shared by indexes and every presentation boundary. */
internal fun BracketPair.hasWellFormedTokenRange(
    maximumEndOffset: Int = Int.MAX_VALUE,
): Boolean {
    if (maximumEndOffset < 0 || openOffset < 0 || closeOffset < 0 ||
        openTokenLength <= 0 || closeTokenLength <= 0
    ) {
        return false
    }

    val openEnd = openOffset.toLong() + openTokenLength
    val closeEnd = closeOffset.toLong() + closeTokenLength
    return openEnd <= closeOffset && closeEnd <= maximumEndOffset
}
