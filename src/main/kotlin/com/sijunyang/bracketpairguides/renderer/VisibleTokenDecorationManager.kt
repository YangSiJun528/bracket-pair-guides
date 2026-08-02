package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.settings.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.util.DocumentUtil

internal data class VisibleTokenDecorations(
    val windowStartOffset: Int,
    val windowEndOffset: Int,
    val entries: List<VisibleTokenEntry>,
) {
    fun contains(range: TextRange): Boolean {
        return windowStartOffset <= range.startOffset && windowEndOffset >= range.endOffset
    }
}

internal data class VisibleTokenEntry(
    val highlighter: RangeHighlighter,
    val colorKey: TextAttributesKey,
    val levelIndex: Int,
)

internal object VisibleTokenDecorationManager {
    fun replace(
        editor: Editor,
        previous: VisibleTokenDecorations?,
        tokenIndex: BracketTokenIndex,
        reportedVisibleRange: TextRange,
        settings: PluginSettings.State,
    ): VisibleTokenDecorations {
        val window = desiredWindow(editor, reportedVisibleRange)
        val previousEntries = previous?.entries.orEmpty()
        val previousCount = previousEntries.size.toLong()
        val nextCount = tokenIndex.countIn(window.startOffset, window.endOffset).toLong()
        var entries: List<VisibleTokenEntry> = emptyList()
        DocumentUtil.executeInBulk(
            editor.document,
            previousCount + nextCount > BULK_DECORATION_THRESHOLD,
        ) {
            entries = replaceEntries(
                editor,
                previousEntries,
                tokenIndex,
                window,
                settings,
            )
        }
        return VisibleTokenDecorations(window.startOffset, window.endOffset, entries)
    }

    fun replaceIfOutsideWindow(
        editor: Editor,
        current: VisibleTokenDecorations,
        tokenIndex: BracketTokenIndex,
        reportedVisibleRange: TextRange,
        settings: PluginSettings.State,
    ): VisibleTokenDecorations? {
        val visibleRange = normalizedVisibleRange(editor, reportedVisibleRange)
        if (current.contains(visibleRange)) return null
        return replace(editor, current, tokenIndex, visibleRange, settings)
    }

    fun updateAttributes(
        editor: Editor,
        current: VisibleTokenDecorations,
        settings: PluginSettings.State,
    ): VisibleTokenDecorations {
        if (!settings.enabled || !settings.colorBracketTokens) {
            disposeEntries(current.entries)
            return current.copy(entries = emptyList())
        }

        val palette = TokenPalette(editor, settings)
        for (entry in current.entries) {
            if (entry.highlighter.isValid) {
                applyPresentation(
                    entry.highlighter,
                    entry.colorKey,
                    palette.attributes[entry.levelIndex],
                )
            }
        }
        return current
    }

    fun dispose(decorations: VisibleTokenDecorations?) {
        decorations ?: return
        disposeEntries(decorations.entries)
    }

    private fun replaceEntries(
        editor: Editor,
        previousEntries: List<VisibleTokenEntry>,
        tokenIndex: BracketTokenIndex,
        window: TextRange,
        settings: PluginSettings.State,
    ): List<VisibleTokenEntry> {
        val reusable = ReusableHighlighters(previousEntries)
        val entries = if (settings.enabled && settings.colorBracketTokens) {
            createEntries(editor, tokenIndex, window, reusable, settings)
        } else {
            emptyList()
        }
        reusable.disposeRemaining()
        return entries
    }

    private fun createEntries(
        editor: Editor,
        tokenIndex: BracketTokenIndex,
        window: TextRange,
        reusable: ReusableHighlighters,
        settings: PluginSettings.State,
    ): List<VisibleTokenEntry> {
        val palette = TokenPalette(editor, settings)
        val entries = ArrayList<VisibleTokenEntry>(
            tokenIndex.countIn(window.startOffset, window.endOffset),
        )
        var index = tokenIndex.firstIndexInRange(window.startOffset)
        while (index < tokenIndex.size) {
            val startOffset = tokenIndex.offsetAt(index)
            if (startOffset >= window.endOffset) break
            val endOffset = startOffset.toLong() + tokenIndex.lengthAt(index)
            if (endOffset > window.startOffset && endOffset <= editor.document.textLength) {
                val levelIndex = BracketColorPalette.levelIndex(tokenIndex.depthAt(index))
                entries += applyToken(
                    editor,
                    reusable,
                    startOffset,
                    endOffset.toInt(),
                    levelIndex,
                    palette,
                )
            }
            index++
        }
        return entries
    }

    private fun applyToken(
        editor: Editor,
        reusable: ReusableHighlighters,
        startOffset: Int,
        endOffset: Int,
        levelIndex: Int,
        palette: TokenPalette,
    ): VisibleTokenEntry {
        val colorKey = BracketColorPalette.LEVEL_KEYS[levelIndex]
        val highlighter = reusable.take(startOffset, endOffset)
            ?: editor.markupModel.addRangeHighlighter(
                colorKey,
                startOffset,
                endOffset,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                HighlighterTargetArea.EXACT_RANGE,
            )
        applyPresentation(highlighter, colorKey, palette.attributes[levelIndex])
        highlighter.customRenderer = null
        highlighter.putUserData(GuideLineHighlightingPass.GUIDE_KEY, null)
        highlighter.putUserData(GuideLineHighlightingPass.OWNED_HIGHLIGHTER_KEY, true)
        return VisibleTokenEntry(highlighter, colorKey, levelIndex)
    }

    // RangeHighlighter exposes an incompatible getter/setter pair under Kotlin 1.9.
    @Suppress("UsePropertyAccessSyntax")
    private fun applyPresentation(
        highlighter: RangeHighlighter,
        colorKey: TextAttributesKey,
        attributes: TextAttributes,
    ) {
        highlighter.setTextAttributesKey(colorKey)
        (highlighter as? RangeHighlighterEx)?.textAttributes = attributes
    }

    private fun normalizedVisibleRange(editor: Editor, reported: TextRange): TextRange {
        val documentLength = editor.document.textLength
        val caretOffset = editor.caretModel.primaryCaret.offset.coerceIn(0, documentLength)
        var startOffset = reported.startOffset.coerceIn(0, documentLength)
        var endOffset = reported.endOffset.coerceIn(startOffset, documentLength)
        if (startOffset == endOffset) {
            startOffset = minOf(startOffset, caretOffset)
            endOffset = maxOf(endOffset, (caretOffset + 1).coerceAtMost(documentLength))
        }
        if (endOffset - startOffset > MAX_REPORTED_VISIBLE_CHARACTERS) {
            startOffset = (caretOffset - MAX_REPORTED_VISIBLE_CHARACTERS / 2).coerceAtLeast(0)
            endOffset = (startOffset + MAX_REPORTED_VISIBLE_CHARACTERS)
                .coerceAtMost(documentLength)
            startOffset = (endOffset - MAX_REPORTED_VISIBLE_CHARACTERS).coerceAtLeast(0)
        }
        return TextRange(startOffset, endOffset)
    }

    private fun desiredWindow(editor: Editor, reported: TextRange): TextRange {
        val visible = normalizedVisibleRange(editor, reported)
        val padding = maxOf(
            MIN_TOKEN_WINDOW_PADDING,
            minOf(visible.length, MAX_TOKEN_WINDOW_PADDING),
        )
        return TextRange(
            (visible.startOffset - padding).coerceAtLeast(0),
            (visible.endOffset.toLong() + padding)
                .coerceAtMost(editor.document.textLength.toLong())
                .toInt(),
        )
    }

    private fun disposeEntries(entries: List<VisibleTokenEntry>) {
        for (entry in entries) {
            if (entry.highlighter.isValid) entry.highlighter.dispose()
        }
    }

    private class TokenPalette(editor: Editor, settings: PluginSettings.State) {
        val attributes = Array(BracketColorPalette.COLOR_COUNT) { level ->
            BracketColorPalette.bracketTextAttributes(editor.colorsScheme, settings, level)
        }
    }

    private class ReusableHighlighters(entries: List<VisibleTokenEntry>) {
        private val previous = entries
        private var index = 0

        fun take(startOffset: Int, endOffset: Int): RangeHighlighter? {
            while (index < previous.size) {
                val highlighter = previous[index].highlighter
                if (!highlighter.isValid) {
                    highlighter.dispose()
                    index++
                    continue
                }
                val comparison = compareRange(
                    highlighter.startOffset,
                    highlighter.endOffset,
                    startOffset,
                    endOffset,
                )
                if (comparison < 0) {
                    highlighter.dispose()
                    index++
                    continue
                }
                if (comparison > 0) return null
                index++
                return highlighter
            }
            return null
        }

        fun disposeRemaining() {
            while (index < previous.size) {
                previous[index++].highlighter.dispose()
            }
        }

        private fun compareRange(
            firstStart: Int,
            firstEnd: Int,
            secondStart: Int,
            secondEnd: Int,
        ): Int {
            val startComparison = firstStart.compareTo(secondStart)
            return if (startComparison != 0) startComparison else firstEnd.compareTo(secondEnd)
        }
    }

    private const val MIN_TOKEN_WINDOW_PADDING = 256
    private const val MAX_TOKEN_WINDOW_PADDING = 4_096
    private const val MAX_REPORTED_VISIBLE_CHARACTERS = 16_384
    private const val BULK_DECORATION_THRESHOLD = 2_000L
}
