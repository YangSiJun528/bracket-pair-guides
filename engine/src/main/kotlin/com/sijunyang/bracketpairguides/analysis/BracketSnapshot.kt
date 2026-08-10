package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.util.TextRange
import org.jetbrains.annotations.ApiStatus

/** Immutable query boundary for analyzed bracket state. */
@ApiStatus.Internal
public interface BracketSnapshot {
    public val stamp: AnalysisStamp

    public fun activePairAt(caretOffset: Int): BracketPair?

    public fun guideFor(pair: BracketPair): BracketGuide?

    public fun visibleTokens(
        range: TextRange,
        focusOffset: Int,
        limit: Int,
    ): TokenWindow
}

/** Primitive token window that does not expose the global token index. */
@ApiStatus.Internal
public interface TokenWindow {
    public val size: Int
    public val isCapped: Boolean
    public val stableFocusStartOffset: Int
    public val stableFocusEndOffset: Int

    public fun offsetAt(index: Int): Int
    public fun lengthAt(index: Int): Int
    public fun depthAt(index: Int): Int
}
