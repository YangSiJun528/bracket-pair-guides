package com.sijunyang.bracketpairguides.renderer

import com.intellij.openapi.util.Key
import com.sijunyang.bracketpairguides.analysis.index.BracketGuide
import java.awt.Color

internal data class GuideRenderOptions(
    val showVertical: Boolean,
    val showHorizontal: Boolean,
    val lineWidth: Int,
    val opacityPercent: Int,
)

internal data class GuidePaintState(
    val guide: BracketGuide,
    val options: GuideRenderOptions,
    val color: Color,
)

internal val GUIDE_PAINT_STATE_KEY: Key<GuidePaintState> =
    Key.create("bracket.pair.guides.paint.state")
