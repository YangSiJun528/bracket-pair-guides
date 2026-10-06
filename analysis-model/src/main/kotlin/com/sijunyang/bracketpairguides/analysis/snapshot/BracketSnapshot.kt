package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair

/** Read-only stamped result; calculation and storage implementations remain outside this module. */
abstract class BracketSnapshot(val stamp: AnalysisStamp, val matcherAvailability: BraceMatcherAvailability) {
    /** Returns the innermost pair containing [caretOffset], in O(log pairCount). */
    abstract fun activePairAt(caretOffset: Int): BracketPair?

    /** Returns an indexed guide, or null when guide coverage was intentionally omitted. */
    abstract fun guideFor(pair: BracketPair): BracketGuide?

    /** Returns a capped, allocation-light token window near the supplied offset range. */
    abstract fun visibleTokens(startOffset: Int, endOffset: Int, focusOffset: Int, limit: Int): TokenWindow
}

/** Read-only bounded token metadata, without access to proportional storage or builders. */
interface TokenWindow {
    val size: Int
    val isCapped: Boolean
    val stableFocusStartOffset: Int
    val stableFocusEndOffset: Int

    fun offsetAt(index: Int): Int

    fun lengthAt(index: Int): Int

    fun depthAt(index: Int): Int
}
