package com.sijunyang.bracketpairguides.analysis

import org.jetbrains.annotations.ApiStatus

/** The analysis facets available in a [BracketSnapshot]. */
@ApiStatus.Internal
public data class AnalysisCoverage(
    public val tokens: Boolean,
    public val activePair: Boolean,
    public val guidePosition: Boolean,
) {
    init {
        require(!guidePosition || activePair) {
            "Guide-position analysis requires active-pair analysis"
        }
    }

    public val pairs: Boolean
        get() = tokens || activePair

    internal fun includes(required: AnalysisCoverage): Boolean =
        (!required.tokens || tokens) &&
            (!required.activePair || activePair) &&
            (!required.guidePosition || guidePosition)
}
