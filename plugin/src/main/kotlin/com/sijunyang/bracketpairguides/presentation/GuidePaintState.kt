package com.sijunyang.bracketpairguides.presentation

import com.intellij.openapi.util.Key
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.sijunyang.bracketpairguides.analysis.BracketGuide
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

private val GUIDE_PAINT_STATE_KEY: Key<GuidePaintState> =
    Key.create("bracket.pair.guides.paint.state")

internal fun RangeHighlighter.guidePaintState(): GuidePaintState? =
    getUserData(GUIDE_PAINT_STATE_KEY)

internal fun RangeHighlighter.putGuidePaintState(state: GuidePaintState) {
    putUserData(GUIDE_PAINT_STATE_KEY, state)
}
