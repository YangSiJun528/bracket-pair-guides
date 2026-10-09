package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex

/** Immutable, editor-independent payload shared by equivalent snapshot views. */
internal class BracketIndexes(
    internal val pairs: PairTable,
    internal val tokens: BracketTokenIndex,
    internal val activePairs: ActiveBracketPairIndex,
    internal val guidePositions: GuidePositionIndex?,
)
