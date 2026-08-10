package com.sijunyang.bracketpairguides.analysis.pairing

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable

internal fun PairTable.bracketPairAt(index: Int): BracketPair = BracketPair(
    openOffset = openOffsetAt(index),
    openTokenLength = openTokenLengthAt(index),
    closeOffset = closeOffsetAt(index),
    closeTokenLength = closeTokenLengthAt(index),
    depth = depthAt(index),
    openLine = openLineAt(index),
    closeLine = closeLineAt(index),
)
