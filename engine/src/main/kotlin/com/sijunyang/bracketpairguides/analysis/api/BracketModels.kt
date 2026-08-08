package com.sijunyang.bracketpairguides.analysis.api

import org.jetbrains.annotations.ApiStatus

/** Immutable bracket pair shared by analysis results and editor presentation. */
@ApiStatus.Internal
public data class BracketPair(
    public val openOffset: Int,
    public val openTokenLength: Int,
    public val closeOffset: Int,
    public val closeTokenLength: Int,
    public val depth: Int,
    public val openLine: Int,
    public val closeLine: Int,
) {
    /** Overflow-safe validation for presentation and index boundaries. */
    public fun hasWellFormedTokenRange(
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
}

@ApiStatus.Internal
public data class BracketGuide(
    public val pair: BracketPair,
    public val guideColumn: Int,
    public val anchorLine: Int = pair.openLine,
)

/** UI-ready description of one installed brace-matcher family. */
@ApiStatus.Internal
public data class BraceLanguageFamily(
    public val id: String,
    public val displayName: String,
    public val memberDisplayNames: List<String>,
)
