package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex

/** Immutable, editor-independent payload shared by equivalent snapshot views. */
class BracketIndexes internal constructor(
    internal val pairs: PairTable,
    internal val tokens: BracketTokenIndex,
    internal val activePairs: ActiveBracketPairIndex,
    internal val guidePositions: GuidePositionIndex?,
) {
    /** One snapshot owns its memo while sharing immutable indexes with equivalent editor results. */
    fun newSnapshot(stamp: AnalysisStamp, matcherAvailability: BraceMatcherAvailability): BracketSnapshot =
        IndexedBracketSnapshot(stamp, matcherAvailability, this)

    /** Used by host canonicalization without exposing the token storage implementation. */
    fun hasSameTokenContent(other: BracketIndexes, checkCanceled: () -> Unit): Boolean =
        tokens.hasSameContent(other.tokens, checkCanceled)

    val hasGuidePositions: Boolean
        get() = guidePositions != null
}
