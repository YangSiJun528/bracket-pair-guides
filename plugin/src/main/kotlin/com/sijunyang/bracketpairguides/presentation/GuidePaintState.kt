package com.sijunyang.bracketpairguides.presentation

import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Key
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import java.awt.Color
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
public data class GuideRenderOptions(
    public val showVertical: Boolean,
    public val showHorizontal: Boolean,
    public val lineWidth: Int,
    public val opacityPercent: Int,
)

@ApiStatus.Internal
public data class GuidePaintState(
    public val guide: BracketGuide,
    public val options: GuideRenderOptions,
    public val color: Color,
)

private val GUIDE_PAINT_STATE_KEY: Key<GuidePaintState> =
    Key.create("bracket.pair.guides.paint.state")

@ApiStatus.Internal
public fun RangeHighlighter.guidePaintState(): GuidePaintState? =
    getUserData(GUIDE_PAINT_STATE_KEY)

@ApiStatus.Internal
public fun RangeHighlighter.putGuidePaintState(state: GuidePaintState): Unit {
    putUserData(GUIDE_PAINT_STATE_KEY, state)
}
