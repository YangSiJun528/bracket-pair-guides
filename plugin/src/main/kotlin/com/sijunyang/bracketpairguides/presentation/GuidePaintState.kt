package com.sijunyang.bracketpairguides.presentation

import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Key
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import org.jetbrains.annotations.ApiStatus
import java.awt.Color

@ApiStatus.Internal
internal data class GuideRenderOptions(
    public val showVertical: Boolean,
    public val showHorizontal: Boolean,
    public val lineWidth: Int,
    public val opacityPercent: Int,
)

@ApiStatus.Internal
internal data class GuidePaintState(
    public val guide: BracketGuide,
    public val options: GuideRenderOptions,
    public val color: Color,
)

private val GUIDE_PAINT_STATE_KEY: Key<GuidePaintState> =
    Key.create("bracket.pair.guides.paint.state")

@ApiStatus.Internal
internal fun RangeHighlighter.guidePaintState(): GuidePaintState? =
    getUserData(GUIDE_PAINT_STATE_KEY)

@ApiStatus.Internal
internal fun RangeHighlighter.putGuidePaintState(state: GuidePaintState): Unit {
    putUserData(GUIDE_PAINT_STATE_KEY, state)
}
