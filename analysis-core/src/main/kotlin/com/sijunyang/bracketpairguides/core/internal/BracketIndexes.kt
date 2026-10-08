package com.sijunyang.bracketpairguides.core.internal

internal class BracketIndexes(
    val pairs: PairTable,
    val tokens: BracketTokenIndex,
    val activePairs: ActiveBracketPairIndex,
    val guidePositions: GuidePositionIndex?,
)
