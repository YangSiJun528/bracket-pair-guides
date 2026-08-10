package com.sijunyang.bracketpairguides.analysis.pairing

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable

internal fun Iterable<BracketPair>.toPairTable(): PairTable {
    val draft = PairTable.draft()
    for (pair in this) {
        draft.accept(
            pair.openOffset,
            pair.openTokenLength,
            pair.closeOffset,
            pair.closeTokenLength,
            pair.depth,
            pair.openLine,
            pair.closeLine,
        )
    }
    return draft.freeze()
}

internal fun PairTable.toBracketPairs(): List<BracketPair> = List(size()) { index ->
    BracketPair(
        openOffset = openOffsetAt(index),
        openTokenLength = openTokenLengthAt(index),
        closeOffset = closeOffsetAt(index),
        closeTokenLength = closeTokenLengthAt(index),
        depth = depthAt(index),
        openLine = openLineAt(index),
        closeLine = closeLineAt(index),
    )
}

internal fun DocumentBracketRecognition.completeTable(): PairTable = when (this) {
    is DocumentBracketRecognition.Complete -> pairs
    is DocumentBracketRecognition.Unavailable -> error("Expected complete pairs, got $limit")
}
