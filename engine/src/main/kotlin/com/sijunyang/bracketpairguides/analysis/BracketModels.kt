package com.sijunyang.bracketpairguides.analysis

import org.jetbrains.annotations.ApiStatus
import java.util.ArrayList
import java.util.Collections

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
public class BraceLanguageFamily(
    public val id: String,
    public val displayName: String,
    memberDisplayNames: List<String>,
) {
    public val memberDisplayNames: List<String> =
        Collections.unmodifiableList(ArrayList(memberDisplayNames))

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is BraceLanguageFamily &&
            id == other.id &&
            displayName == other.displayName &&
            memberDisplayNames == other.memberDisplayNames

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + memberDisplayNames.hashCode()
        return result
    }

    override fun toString(): String =
        "BraceLanguageFamily(id=$id, displayName=$displayName, " +
            "memberDisplayNames=$memberDisplayNames)"
}
