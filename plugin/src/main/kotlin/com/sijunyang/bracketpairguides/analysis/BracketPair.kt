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
