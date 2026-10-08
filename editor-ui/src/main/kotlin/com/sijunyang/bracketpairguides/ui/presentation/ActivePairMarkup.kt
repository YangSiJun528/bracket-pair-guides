package com.sijunyang.bracketpairguides.ui.presentation

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.sijunyang.bracketpairguides.model.BracketGuide
import com.sijunyang.bracketpairguides.model.BracketPair
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.ui.presentation.RenderFrames.Frame
import com.sijunyang.bracketpairguides.ui.presentation.RenderFrames.Mark

/** SDK effects commit only while their presentation frame still owns authority. */
internal class ActivePairMarkup(
    private val editor: Editor,
    private val onDisplayedMultilineVerticalGuide: (Editor, BracketGuide) -> Unit,
) {
    private var guideMark: Mark? = null
    private var pairMarks: List<Mark> = emptyList()
    val guide: BracketGuide? get() = guideMark?.highlighter?.takeIf(RangeHighlighter::isValid)
        ?.customRenderer?.let { it as? BracketGuideDrawing }?.guide

    /** Adopt only fully valid unchanged SDK effects; unsupported styles take the normal path. */
    fun adoptUnchanged(pair: BracketPair, preferences: BracketGuidePreferences, frame: Frame): Boolean {
        frame.check()
        val mark = guideMark ?: return false
        if (!mark.isReusable) return false
        val highlighter = mark.highlighter
        val renderer = highlighter.customRenderer as? BracketGuideDrawing ?: return false
        if (renderer.guide.pair != pair || !canShow(renderer.guide, preferences) || !renderer.matches(preferences) ||
            highlighter.startOffset != 0 || highlighter.endOffset != editor.document.textLength ||
            highlighter.layer != GUIDE_LAYER || highlighter.targetArea != HighlighterTargetArea.EXACT_RANGE ||
            highlighter.textAttributesKey != EMPTY_ATTRIBUTES_KEY ||
            !highlighter.isGreedyToLeft || !highlighter.isGreedyToRight) return false
        if (!preferences.showsActivePair) {
            if (pairMarks.isNotEmpty()) return false
        } else {
            // Background blending/theme and uncommon styles deliberately use the full render path.
            if (!preferences.showActivePairBorder || BracketColorPalette.hasVisiblePairBackground(preferences) ||
                pairMarks.size != 2) return false
            val expectedRgb = BracketColorPalette.pairBorderRgb(preferences, pair.depth) or (0xFF shl 24)
            for (index in 0..1) {
                val endpoint = pairMarks[index]
                if (!endpoint.isReusable) return false
                val range = endpoint.highlighter
                val offset = if (index == 0) pair.openOffset else pair.closeOffset
                val length = if (index == 0) pair.openTokenLength else pair.closeTokenLength
                val attributes = range.getTextAttributes(editor.colorsScheme) ?: return false
                if (range.startOffset != offset || range.endOffset != offset + length ||
                    range.layer != ACTIVE_PAIR_LAYER || range.targetArea != HighlighterTargetArea.EXACT_RANGE ||
                    range.isGreedyToLeft || range.isGreedyToRight || range.textAttributesKey != null ||
                    attributes.effectType != EffectType.BOXED || attributes.effectColor?.rgb != expectedRgb ||
                    attributes.backgroundColor != null || attributes.foregroundColor != null ||
                    attributes.errorStripeColor != null || attributes.fontType != 0 || hasAdditionalEffects(attributes)) return false
            }
        }
        frame.check()
        frame.adopt(mark)
        for (endpoint in pairMarks) frame.adopt(endpoint)
        frame.check()
        return true
    }

    private fun hasAdditionalEffects(attributes: TextAttributes): Boolean {
        var present = false
        attributes.forEachAdditionalEffect { _, _ -> present = true }
        return present
    }

    fun showGuide(guide: BracketGuide?, preferences: BracketGuidePreferences, frame: Frame) {
        frame.check()
        if (guide == null || !canShow(guide, preferences)) { clearGuide(frame); return }
        val appearance = GuideAppearance(preferences.showVerticalGuide, preferences.showHorizontalGuides,
            preferences.guideLineWidth.coerceIn(BracketGuidePreferences.MIN_GUIDE_LINE_WIDTH,
                BracketGuidePreferences.MAX_GUIDE_LINE_WIDTH),
            preferences.guideOpacityPercent.coerceIn(BracketGuidePreferences.MIN_GUIDE_OPACITY_PERCENT,
                BracketGuidePreferences.MAX_GUIDE_OPACITY_PERCENT))
        val color = BracketColorPalette.guideLineColor(preferences, guide.pair.depth)
        val existing = guideMark?.takeIf(Mark::isReusable)
        val owned = if (existing != null) frame.adopt(existing) else {
            var created: Mark? = null
            val markup = editor.markupModel
            if (markup is MarkupModelEx) {
                markup.addRangeHighlighterAndChangeAttributes(EMPTY_ATTRIBUTES_KEY, 0, editor.document.textLength,
                    GUIDE_LAYER, HighlighterTargetArea.EXACT_RANGE, false) { highlighter ->
                    created = frame.created(highlighter)
                    highlighter.isGreedyToLeft = true
                    highlighter.isGreedyToRight = true
                }
            } else {
                val highlighter = markup.addRangeHighlighter(EMPTY_ATTRIBUTES_KEY, 0, editor.document.textLength,
                    GUIDE_LAYER, HighlighterTargetArea.EXACT_RANGE)
                created = frame.created(highlighter)
                frame.check()
                highlighter.isGreedyToLeft = true
                highlighter.isGreedyToRight = true
            }
            frame.check()
            checkNotNull(created)
        }
        guideMark = owned
        val highlighter = owned.highlighter
        val renderer = highlighter.customRenderer as? BracketGuideDrawing
        if (renderer == null) highlighter.customRenderer = BracketGuideDrawing(guide, appearance, color,
            onDisplayedMultilineVerticalGuide)
        else renderer.update(guide, appearance, color)
        frame.check()
    }

    fun showPair(pair: BracketPair, preferences: BracketGuidePreferences, frame: Frame) {
        frame.check()
        if (!pair.hasWellFormedTokenRange(editor.document.textLength) || !preferences.enabled ||
            !preferences.showActivePairBorder && !BracketColorPalette.hasVisiblePairBackground(preferences)) {
            clearPair(frame)
            return
        }
        val attributes = BracketColorPalette.activePairTextAttributes(editor.colorsScheme, preferences, pair.depth)
        if (canReusePair(pair, attributes)) {
            frame.check()
            for (mark in pairMarks) frame.adopt(mark)
            frame.check()
            return
        }
        clearPair(frame)
        val marks = ArrayList<Mark>(2)
        for ((offset, length) in listOf(pair.openOffset to pair.openTokenLength, pair.closeOffset to pair.closeTokenLength)) {
            frame.check()
            var created: Mark? = null
            val markup = editor.markupModel
            if (markup is MarkupModelEx) {
                markup.addRangeHighlighterAndChangeAttributes(null, offset, offset + length, ACTIVE_PAIR_LAYER,
                    HighlighterTargetArea.EXACT_RANGE, false) { highlighter ->
                    created = frame.created(highlighter)
                    highlighter.textAttributes = attributes
                    highlighter.isGreedyToLeft = false
                    highlighter.isGreedyToRight = false
                }
            } else {
                val highlighter = markup.addRangeHighlighter(offset, offset + length, ACTIVE_PAIR_LAYER, attributes,
                    HighlighterTargetArea.EXACT_RANGE)
                created = frame.created(highlighter)
                frame.check()
                highlighter.isGreedyToLeft = false
                highlighter.isGreedyToRight = false
            }
            frame.check()
            marks += checkNotNull(created)
        }
        pairMarks = marks
    }

    private fun canReusePair(pair: BracketPair, attributes: TextAttributes): Boolean {
        if (pairMarks.size != 2) return false
        for (index in 0..1) {
            val mark = pairMarks[index]
            if (!mark.isReusable) return false
            val highlighter = mark.highlighter
            val offset = if (index == 0) pair.openOffset else pair.closeOffset
            val length = if (index == 0) pair.openTokenLength else pair.closeTokenLength
            if (highlighter.startOffset != offset || highlighter.endOffset != offset + length ||
                highlighter.layer != ACTIVE_PAIR_LAYER || highlighter.targetArea != HighlighterTargetArea.EXACT_RANGE ||
                highlighter.isGreedyToLeft || highlighter.isGreedyToRight || highlighter.textAttributesKey != null ||
                highlighter.getTextAttributes(editor.colorsScheme) != attributes) return false
        }
        return true
    }

    fun clear(preserveGuide: Boolean, frame: Frame? = null) {
        frame?.check()
        clearPair(frame)
        frame?.check()
        if (!preserveGuide) clearGuide(frame)
    }
    fun clearGuide(frame: Frame? = null) {
        frame?.check()
        val previous = guideMark
        guideMark = null
        previous?.dispose()
        frame?.check()
    }
    private fun clearPair(frame: Frame? = null) {
        frame?.check()
        val previous = pairMarks
        pairMarks = emptyList()
        // Detached resources cannot be adopted by a reentrant render; finish their cleanup.
        for (mark in previous) mark.dispose()
        frame?.check()
    }
    private fun canShow(guide: BracketGuide, preferences: BracketGuidePreferences): Boolean =
        preferences.enabled && preferences.showActiveGuide && if (guide.pair.openLine == guide.pair.closeLine)
            preferences.showHorizontalGuides else preferences.showVerticalGuide || preferences.showHorizontalGuides
    private companion object {
        val EMPTY_ATTRIBUTES_KEY = TextAttributesKey.createTextAttributesKey("BRACKET_PAIR_GUIDES_EMPTY_ATTRIBUTES")
        const val GUIDE_LAYER = HighlighterLayer.ADDITIONAL_SYNTAX
        const val ACTIVE_PAIR_LAYER = HighlighterLayer.ELEMENT_UNDER_CARET
    }
}
