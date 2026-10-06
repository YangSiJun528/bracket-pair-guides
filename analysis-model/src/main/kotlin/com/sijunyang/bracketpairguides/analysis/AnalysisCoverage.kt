package com.sijunyang.bracketpairguides.analysis

/** Facets requested from one bracket analysis. */
data class AnalysisCoverage(val tokens: Boolean, val activePair: Boolean, val guidePosition: Boolean) {
    init {
        require(!guidePosition || activePair) {
            "Guide-position analysis requires active-pair analysis"
        }
    }

    val pairs: Boolean
        get() = tokens || activePair

    fun includes(required: AnalysisCoverage): Boolean = (!required.tokens || tokens) &&
        (!required.activePair || activePair) &&
        (!required.guidePosition || guidePosition)

    fun withoutGuidePosition(): AnalysisCoverage = if (guidePosition) copy(guidePosition = false) else this
}
