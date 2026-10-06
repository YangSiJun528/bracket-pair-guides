package com.sijunyang.bracketpairguides.analysis.snapshot

import com.intellij.openapi.util.TextRange

/** Fixture convenience; production queries accept only platform-free offsets. */
internal fun BracketSnapshot.visibleTokens(range: TextRange, focusOffset: Int, limit: Int): TokenWindow =
    visibleTokens(range.startOffset, range.endOffset, focusOffset, limit)
