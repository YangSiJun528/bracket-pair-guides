package com.sijunyang.bracketpairguides.analysis

/** Immutable bracket pair shared by analysis indexes and editor presentation. */
internal data class BracketPair(
    val openOffset: Int,
    val openTokenLength: Int,
    val closeOffset: Int,
    val closeTokenLength: Int,
    val depth: Int,
    val openLine: Int,
    val closeLine: Int,
)

internal data class BracketGuide(
    val pair: BracketPair,
    val guideColumn: Int,
    val anchorLine: Int = pair.openLine,
)

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
