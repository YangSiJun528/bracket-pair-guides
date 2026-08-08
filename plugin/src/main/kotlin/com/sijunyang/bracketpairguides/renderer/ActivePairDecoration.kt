package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.index.BracketGuide
import com.sijunyang.bracketpairguides.analysis.index.hasWellFormedTokenRange
import com.sijunyang.bracketpairguides.settings.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter

/** Adds and updates the active-pair presentation owned by a source editor session. */
internal object ActivePairDecoration {
    fun addGuide(
        editor: Editor,
        guide: BracketGuide,
        settings: PluginOptions,
        reusable: RangeHighlighter? = null,
    ): RangeHighlighter? {
        val hasRenderableSegment = if (guide.pair.openLine == guide.pair.closeLine) {
            settings.showHorizontalGuides
        } else {
            settings.showVerticalGuide || settings.showHorizontalGuides
        }
        if (!settings.enabled || !settings.showActiveGuide || !hasRenderableSegment) {
            reusable?.dispose()
            return null
        }
        val highlighter = reusable?.takeIf(RangeHighlighter::isValid)
            ?: editor.markupModel.addRangeHighlighter(
                EMPTY_ATTRIBUTES_KEY,
                0,
                editor.document.textLength,
                GUIDE_LAYER,
                HighlighterTargetArea.EXACT_RANGE,
            ).also {
                it.isGreedyToLeft = true
                it.isGreedyToRight = true
            }
        return highlighter.also {
            it.customRenderer = BracketGuideRenderer
            it.putUserData(
                GUIDE_PAINT_STATE_KEY,
                GuidePaintState(
                    guide = guide,
                    options = GuideRenderOptions(
                        showVertical = settings.showVerticalGuide,
                        showHorizontal = settings.showHorizontalGuides,
                        lineWidth = settings.guideLineWidth.coerceIn(
                            PluginSettings.MIN_GUIDE_LINE_WIDTH,
                            PluginSettings.MAX_GUIDE_LINE_WIDTH,
                        ),
                        opacityPercent = settings.guideOpacityPercent.coerceIn(
                            PluginSettings.MIN_GUIDE_OPACITY_PERCENT,
                            PluginSettings.MAX_GUIDE_OPACITY_PERCENT,
                        ),
                    ),
                    color = BracketColorPalette.guideLineColor(
                        editor.colorsScheme,
                        settings,
                        guide.pair.depth,
                    ),
                ),
            )
        }
    }

    fun addPairHighlights(
        editor: Editor,
        pair: BracketPair,
        settings: PluginOptions,
    ): List<RangeHighlighter> {
        if (!pair.hasWellFormedTokenRange(editor.document.textLength)) return emptyList()
        val hasVisibleBackground = BracketColorPalette.hasVisiblePairBackground(settings)
        if (!settings.enabled ||
            (!settings.showActivePairBorder && !hasVisibleBackground)
        ) {
            return emptyList()
        }
        val attributes = BracketColorPalette.activePairTextAttributes(
            editor.colorsScheme,
            settings,
            pair.depth,
        )
        return listOf(
            pair.openOffset to pair.openTokenLength,
            pair.closeOffset to pair.closeTokenLength,
        ).map { (startOffset, tokenLength) ->
            editor.markupModel.addRangeHighlighter(
                startOffset,
                startOffset + tokenLength,
                ACTIVE_PAIR_LAYER,
                attributes,
                HighlighterTargetArea.EXACT_RANGE,
            ).also {
                it.isGreedyToLeft = false
                it.isGreedyToRight = false
            }
        }
    }

    private val EMPTY_ATTRIBUTES_KEY = TextAttributesKey.createTextAttributesKey(
        "BRACKET_PAIR_GUIDES_EMPTY_ATTRIBUTES",
    )
    private const val GUIDE_LAYER = HighlighterLayer.ADDITIONAL_SYNTAX
    private const val ACTIVE_PAIR_LAYER = HighlighterLayer.ELEMENT_UNDER_CARET

}
