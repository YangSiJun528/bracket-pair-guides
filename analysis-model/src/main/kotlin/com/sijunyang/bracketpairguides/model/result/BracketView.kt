package com.sijunyang.bracketpairguides.model.result

import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.AnalysisLimit
import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.model.BracketGuide
import com.sijunyang.bracketpairguides.model.BracketPair
import com.sijunyang.bracketpairguides.model.OffsetRange

/** Immutable semantic queries; storage, computation, and host identities remain hidden. */
interface BracketView {
    fun activePairAt(offset: Int): BracketPair?
    fun guideFor(pair: BracketPair): BracketGuide?
    fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow
}

/** Bounded indexed metadata; no mutable backing storage is exposed. */
interface TokenWindow {
    val size: Int
    val isCapped: Boolean
    val stableFocusStartOffset: Int
    val stableFocusEndOffset: Int
    fun offsetAt(index: Int): Int
    fun lengthAt(index: Int): Int
    fun depthAt(index: Int): Int
}

sealed interface AnalysisResult {
    val coverage: AnalysisCoverage
    val matcherAvailability: BraceMatcherAvailability

    data class Available(
        val view: BracketView,
        override val coverage: AnalysisCoverage,
        override val matcherAvailability: BraceMatcherAvailability,
        val limit: AnalysisLimit? = null,
    ) : AnalysisResult {
        init {
            require(limit == null || limit == AnalysisLimit.GUIDE_CAPACITY)
            require(limit == null || !coverage.guidePosition)
        }
    }

    data class Unavailable(
        override val coverage: AnalysisCoverage,
        val limit: AnalysisLimit,
        override val matcherAvailability: BraceMatcherAvailability = BraceMatcherAvailability.UNDETERMINED,
    ) : AnalysisResult {
        init {
            require(limit != AnalysisLimit.GUIDE_CAPACITY)
        }
    }
}
