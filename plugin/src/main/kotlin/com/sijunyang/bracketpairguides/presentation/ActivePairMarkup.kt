package com.sijunyang.bracketpairguides.presentation

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences

/** All active-pair markup owned by one editor session. */
internal class ActivePairMarkup(private val editor: Editor) {
    private var guideMark: RangeHighlighter? = null

    private var pairMarks: List<RangeHighlighter> = emptyList()

    val guide: BracketGuide?
        get() = guideMark
            ?.takeIf(RangeHighlighter::isValid)
            ?.customRenderer
            ?.let { it as? BracketGuideDrawing }
            ?.guide

    val isVisible: Boolean
        get() = guideMark != null || pairMarks.isNotEmpty()

    fun showGuide(guide: BracketGuide?, preferences: BracketGuidePreferences) {
        if (guide == null || !canShow(guide, preferences)) {
            clearGuide()
            return
        }

        val highlighter = guideMark?.takeIf(RangeHighlighter::isValid)
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
        guideMark = highlighter.also {
            it.customRenderer = BracketGuideDrawing(
                guide = guide,
                appearance = GuideAppearance(
                    showVertical = preferences.showVerticalGuide,
                    showHorizontal = preferences.showHorizontalGuides,
                    lineWidth = preferences.guideLineWidth.coerceIn(
                        BracketGuidePreferences.MIN_GUIDE_LINE_WIDTH,
                        BracketGuidePreferences.MAX_GUIDE_LINE_WIDTH,
                    ),
                    opacityPercent = preferences.guideOpacityPercent.coerceIn(
                        BracketGuidePreferences.MIN_GUIDE_OPACITY_PERCENT,
                        BracketGuidePreferences.MAX_GUIDE_OPACITY_PERCENT,
                    ),
                ),
                color = BracketColorPalette.guideLineColor(
                    preferences,
                    guide.pair.depth,
                ),
            )
        }
    }

    fun showPair(pair: BracketPair, preferences: BracketGuidePreferences) {
        clearPair()
        if (!pair.hasWellFormedTokenRange(editor.document.textLength)) return
        val hasVisibleBackground = BracketColorPalette.hasVisiblePairBackground(preferences)
        if (!preferences.enabled ||
            (!preferences.showActivePairBorder && !hasVisibleBackground)
        ) {
            return
        }
        val attributes = BracketColorPalette.activePairTextAttributes(
            editor.colorsScheme,
            preferences,
            pair.depth,
        )
        pairMarks = listOf(
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

    fun clear(preserveGuide: Boolean) {
        clearPair()
        if (!preserveGuide) clearGuide()
    }

    fun clearGuide() {
        guideMark?.dispose()
        guideMark = null
    }

    private fun clearPair() {
        for (mark in pairMarks) {
            if (mark.isValid) mark.dispose()
        }
        pairMarks = emptyList()
    }

    private fun canShow(
        guide: BracketGuide,
        preferences: BracketGuidePreferences,
    ): Boolean {
        val hasSegment = if (guide.pair.openLine == guide.pair.closeLine) {
            preferences.showHorizontalGuides
        } else {
            preferences.showVerticalGuide || preferences.showHorizontalGuides
        }
        return preferences.enabled && preferences.showActiveGuide && hasSegment
    }

    private companion object {
        private val EMPTY_ATTRIBUTES_KEY = TextAttributesKey.createTextAttributesKey(
            "BRACKET_PAIR_GUIDES_EMPTY_ATTRIBUTES",
        )
        private const val GUIDE_LAYER = HighlighterLayer.ADDITIONAL_SYNTAX
        private const val ACTIVE_PAIR_LAYER = HighlighterLayer.ELEMENT_UNDER_CARET
    }
}
