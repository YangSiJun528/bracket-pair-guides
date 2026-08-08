package com.sijunyang.bracketpairguides.analysis

import org.jetbrains.annotations.ApiStatus

/** Immutable bracket pair shared by analysis indexes and editor presentation. */
@ApiStatus.Internal
public data class BracketPair(
    public val openOffset: Int,
    public val openTokenLength: Int,
    public val closeOffset: Int,
    public val closeTokenLength: Int,
    public val depth: Int,
    public val openLine: Int,
    public val closeLine: Int,
)

@ApiStatus.Internal
public data class BracketGuide(
    public val pair: BracketPair,
    public val guideColumn: Int,
    public val anchorLine: Int = pair.openLine,
)

/** Overflow-safe validation shared by indexes and every presentation boundary. */
@ApiStatus.Internal
public fun BracketPair.hasWellFormedTokenRange(
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
