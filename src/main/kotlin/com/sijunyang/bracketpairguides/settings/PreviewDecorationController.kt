package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.renderer.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.renderer.ActivePairDecoration
import com.sijunyang.bracketpairguides.renderer.GuideLineHighlightingPass
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter

/** Applies a recognition snapshot using explicit draft settings only. */
internal class PreviewDecorationController(
    private val editor: Editor,
) {
    private var settings: PluginSettings.State? = null
    private var recognition = PreviewRecognitionResult.EMPTY
    private var activePairIndex = ActiveBracketPairIndex.NO_PAIR
    private var tokenPresentation: TokenPresentation? = null
    private val tokenHighlighters = ArrayList<RangeHighlighter>()
    private var activeGuide: RangeHighlighter? = null
    private val activePairHighlighters = ArrayList<RangeHighlighter>()

    fun updateSettings(nextSettings: PluginSettings.State) {
        val next = nextSettings.copyForPreview()
        val nextTokenPresentation = tokenPresentation(next)
        settings = next
        if (tokenPresentation != nextTokenPresentation) {
            tokenPresentation = nextTokenPresentation
            rebuildTokenHighlights()
        }
        refreshActivePresentation(force = true)
    }

    fun updateRecognition(nextRecognition: PreviewRecognitionResult) {
        recognition = nextRecognition
        rebuildTokenHighlights()
        refreshActivePresentation(force = true)
    }

    fun caretMoved() {
        refreshActivePresentation(force = false)
    }

    fun dispose() {
        clearTokenHighlights()
        clearActivePresentation()
        recognition = PreviewRecognitionResult.EMPTY
        settings = null
        tokenPresentation = null
    }

    private fun rebuildTokenHighlights() {
        clearTokenHighlights()
        val draft = settings ?: return
        if (!draft.enabled || !draft.colorBracketTokens) return

        recognition.pairs.take(MAX_TOKEN_PAIR_HIGHLIGHTS).forEach { pair ->
            val attributes = BracketColorPalette.bracketTextAttributes(
                editor.colorsScheme,
                draft,
                pair.depth,
            )
            listOf(
                pair.openOffset to pair.openTokenLength,
                pair.closeOffset to pair.closeTokenLength,
            ).forEach { (offset, length) ->
                val endOffset = offset.toLong() + length
                if (offset < 0 || length <= 0 || endOffset > editor.document.textLength) {
                    return@forEach
                }
                tokenHighlighters += editor.markupModel.addRangeHighlighter(
                    offset,
                    endOffset.toInt(),
                    HighlighterLayer.ADDITIONAL_SYNTAX,
                    attributes,
                    HighlighterTargetArea.EXACT_RANGE,
                ).also { highlighter ->
                    highlighter.putUserData(
                        GuideLineHighlightingPass.OWNED_HIGHLIGHTER_KEY,
                        true,
                    )
                }
            }
        }
    }

    private fun refreshActivePresentation(force: Boolean) {
        val draft = settings ?: return
        val nextIndex = recognition.activeIndex.activePairIndex(
            editor.caretModel.primaryCaret.offset,
        ).takeIf { recognition.guides.getOrNull(it) != null }
            ?: ActiveBracketPairIndex.NO_PAIR
        if (!force && activePairIndex == nextIndex) return

        clearActivePresentation()
        activePairIndex = nextIndex
        val guide = recognition.guides.getOrNull(nextIndex) ?: return
        activeGuide = ActivePairDecoration.addGuide(editor, guide, draft)
        activePairHighlighters += ActivePairDecoration.addPairHighlights(
            editor,
            guide,
            draft,
        )
        editor.contentComponent.repaint()
    }

    private fun clearTokenHighlights() {
        tokenHighlighters.forEach { highlighter ->
            if (highlighter.isValid) highlighter.dispose()
        }
        tokenHighlighters.clear()
    }

    private fun clearActivePresentation() {
        activeGuide?.takeIf(RangeHighlighter::isValid)?.dispose()
        activeGuide = null
        activePairHighlighters.forEach { highlighter ->
            if (highlighter.isValid) highlighter.dispose()
        }
        activePairHighlighters.clear()
        activePairIndex = ActiveBracketPairIndex.NO_PAIR
    }

    private fun tokenPresentation(
        settings: PluginSettings.State,
    ): TokenPresentation = TokenPresentation(
        enabled = settings.enabled && settings.colorBracketTokens,
        levelColors = List(BracketColorPalette.COLOR_COUNT) { level ->
            BracketColorPalette.baseColor(editor.colorsScheme, settings, level).rgb
        },
    )

    private fun PluginSettings.State.copyForPreview(): PluginSettings.State = copy(
        levelBaseColors = levelBaseColors.toMutableList(),
        guideLineColors = guideLineColors.toMutableList(),
        pairBorderColors = pairBorderColors.toMutableList(),
        pairBackgroundColors = pairBackgroundColors.toMutableList(),
    )

    private data class TokenPresentation(
        val enabled: Boolean,
        val levelColors: List<Int>,
    )

    companion object {
        internal const val MAX_TOKEN_PAIR_HIGHLIGHTS = 500
    }
}
