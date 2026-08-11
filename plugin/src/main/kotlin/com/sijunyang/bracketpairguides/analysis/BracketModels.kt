package com.sijunyang.bracketpairguides.analysis

import java.util.ArrayList
import java.util.Collections

/** Immutable bracket pair shared by analysis results and editor presentation. */
internal data class BracketPair(
    val openOffset: Int,
    val openTokenLength: Int,
    val closeOffset: Int,
    val closeTokenLength: Int,
    val depth: Int,
    val openLine: Int,
    val closeLine: Int,
) {
    /** Overflow-safe validation for presentation and index boundaries. */
    fun hasWellFormedTokenRange(
        maximumEndOffset: Int,
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

internal data class BracketGuide(
    val pair: BracketPair,
    val guideColumn: Int,
    val anchorLine: Int = pair.openLine,
)

/** UI-ready description of one installed brace-matcher family. */
internal class BraceLanguageFamily(
    val id: String,
    val displayName: String,
    memberDisplayNames: List<String>,
) {
    val memberDisplayNames: List<String> =
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
