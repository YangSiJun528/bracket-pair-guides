package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.analysis.api.AnalysisCapabilities
import com.sijunyang.bracketpairguides.settings.PluginOptions

/** Maps persisted options to the analysis work required by editor sessions. */
internal fun PluginOptions.analysisCapabilities(): AnalysisCapabilities {
    val activePair = enabled && (showsGuide || showsActivePair)
    return AnalysisCapabilities(
        tokens = enabled && colorBracketTokens,
        activePair = activePair,
        guidePosition = activePair && showsGuide,
    )
}
