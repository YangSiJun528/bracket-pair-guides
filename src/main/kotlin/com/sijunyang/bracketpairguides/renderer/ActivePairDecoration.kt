package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.settings.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter

/**
 * Shared active-pair presentation used by production editors and settings preview.
 * The caller supplies an explicit state, so a preview never mutates persisted settings.
 */
internal object ActivePairDecoration {
    fun addGuide(
        editor: Editor,
        guide: BracketGuide,
        settings: PluginSettings.State,
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
            it.putUserData(GuideLineHighlightingPass.GUIDE_KEY, guide)
            it.putUserData(
                GuideLineHighlightingPass.GUIDE_RENDER_OPTIONS_KEY,
                GuideRenderOptions(
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
            )
            it.putUserData(
                GuideLineHighlightingPass.GUIDE_COLOR_KEY,
                BracketColorPalette.guideLineColor(
                    editor.colorsScheme,
                    settings,
                    guide.pair.depth,
                ),
            )
            it.putUserData(GuideLineHighlightingPass.OWNED_HIGHLIGHTER_KEY, true)
        }
    }

    fun addPairHighlights(
        editor: Editor,
        guide: BracketGuide,
        settings: PluginSettings.State,
    ): List<RangeHighlighter> {
        val hasVisibleBackground = BracketColorPalette.hasVisiblePairBackground(settings)
        if (!settings.enabled ||
            (!settings.showActivePairBorder && !hasVisibleBackground)
        ) {
            return emptyList()
        }
        val attributes = BracketColorPalette.activePairTextAttributes(
            editor.colorsScheme,
            settings,
            guide.pair.depth,
        )
        return listOf(
            guide.pair.openOffset to guide.pair.openTokenLength,
            guide.pair.closeOffset to guide.pair.closeTokenLength,
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
                it.putUserData(GuideLineHighlightingPass.OWNED_HIGHLIGHTER_KEY, true)
                it.putUserData(GuideLineHighlightingPass.ACTIVE_PAIR_HIGHLIGHT_KEY, true)
            }
        }
    }

    private val EMPTY_ATTRIBUTES_KEY = TextAttributesKey.createTextAttributesKey(
        "BRACKET_PAIR_GUIDES_EMPTY_ATTRIBUTES",
    )
    private const val GUIDE_LAYER = HighlighterLayer.ADDITIONAL_SYNTAX
    private const val ACTIVE_PAIR_LAYER = HighlighterLayer.ELEMENT_UNDER_CARET
}
