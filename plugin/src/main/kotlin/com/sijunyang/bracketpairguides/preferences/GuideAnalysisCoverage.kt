package com.sijunyang.bracketpairguides.preferences

import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage

/** Maps persisted options to the analysis work required by editor sessions. */
internal fun BracketGuidePreferences.analysisCoverage(): AnalysisCoverage {
    val activePair = enabled && (showsGuide || showsActivePair)
    return AnalysisCoverage(
        tokens = enabled && colorBracketTokens,
        activePair = activePair,
        guidePosition = activePair && showsGuide,
    )
}
