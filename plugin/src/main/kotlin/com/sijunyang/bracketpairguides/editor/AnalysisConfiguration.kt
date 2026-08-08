package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.analysis.AnalysisCapabilities
import com.sijunyang.bracketpairguides.settings.PluginOptions
import org.jetbrains.annotations.ApiStatus

/** Maps persisted options to the analysis work required by editor sessions. */
@ApiStatus.Internal
internal fun PluginOptions.analysisCapabilities(): AnalysisCapabilities {
    val activePair = enabled && (showsGuide || showsActivePair)
    return AnalysisCapabilities(
        tokens = enabled && colorBracketTokens,
        activePair = activePair,
        guidePosition = activePair && showsGuide,
    )
}
