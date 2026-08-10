package com.sijunyang.bracketpairguides.presentation

import com.sijunyang.bracketpairguides.analysis.BracketSnapshot
import com.sijunyang.bracketpairguides.analysis.TokenWindow
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.StoredColorFormat
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange

/** EDT-owned token markup and the viewport window it represents. */
internal class VisibleTokenDecorations(
    private val editor: Editor,
) {
    private var windowStartOffset = 0
    private var windowEndOffset = 0
    private var entries: List<VisibleTokenEntry> = emptyList()
    private var stableFocusStartOffset = 0
    private var stableFocusEndOffset = 0

    var isCapped = false
        private set

    fun replace(
        analysis: BracketSnapshot,
        reportedVisibleRange: TextRange,
        options: BracketGuidePreferences,
    ) {
        val visibleRange = normalizedVisibleRange(reportedVisibleRange)
        val window = desiredWindow(visibleRange)
        val reusable = PreviousTokenMarks(entries)
        val selection = if (options.enabled && options.colorBracketTokens) {
            createEntries(
                analysis.visibleTokens(
                    range = window,
                    focusOffset = decorationFocusOffset(visibleRange),
                    limit = MAX_VISIBLE_TOKEN_DECORATIONS,
                ),
                window,
                reusable,
                options,
            )
        } else {
            EntrySelection.EMPTY
        }
        reusable.disposeRemaining()
        windowStartOffset = window.startOffset
        windowEndOffset = window.endOffset
        entries = selection.entries
        stableFocusStartOffset = selection.stableFocusStartOffset ?: window.startOffset
        stableFocusEndOffset = selection.stableFocusEndOffset ?: window.endOffset
        isCapped = selection.isCapped
    }

    fun replaceIfOutsideWindow(
        analysis: BracketSnapshot,
        reportedVisibleRange: TextRange,
        options: BracketGuidePreferences,
    ): Boolean {
        val visibleRange = normalizedVisibleRange(reportedVisibleRange)
        val focusOffset = decorationFocusOffset(visibleRange)
        if (canReuseFor(visibleRange, focusOffset)) return false
        replace(analysis, visibleRange, options)
        return true
    }

    fun updateAttributes(options: BracketGuidePreferences) {
        if (!options.enabled || !options.colorBracketTokens) {
            disposeEntries(entries)
            entries = emptyList()
            stableFocusStartOffset = windowStartOffset
            stableFocusEndOffset = windowEndOffset
            isCapped = false
            return
        }

        val palette = TokenPalette(options)
        entries.forEach { entry ->
            val attributes = palette.attributes[entry.levelIndex]
            if (entry.highlighter.isValid && entry.attributes != attributes) {
                applyPresentation(entry.highlighter, entry.colorKey, attributes)
                entry.attributes = attributes
            }
        }
    }

    fun dispose() {
        disposeEntries(entries)
        windowStartOffset = 0
        windowEndOffset = 0
        entries = emptyList()
        stableFocusStartOffset = 0
        stableFocusEndOffset = 0
        isCapped = false
    }

    /** A capped token slice must follow scrolling even inside its padded window. */
    private fun canReuseFor(range: TextRange, focusOffset: Int): Boolean {
        if (windowStartOffset > range.startOffset || windowEndOffset < range.endOffset) {
            return false
        }
        return !isCapped || focusOffset in stableFocusStartOffset..stableFocusEndOffset
    }

    private fun createEntries(
        tokens: TokenWindow,
        window: TextRange,
        reusable: PreviousTokenMarks,
        options: BracketGuidePreferences,
    ): EntrySelection {
        val palette = TokenPalette(options)
        val entries = ArrayList<VisibleTokenEntry>(tokens.size)
        var index = 0
        while (index < tokens.size) {
            val startOffset = tokens.offsetAt(index)
            val endOffset = startOffset.toLong() + tokens.lengthAt(index)
            if (endOffset > window.startOffset && endOffset <= editor.document.textLength) {
                val levelIndex = BracketColorPalette.levelIndex(tokens.depthAt(index))
                entries += applyToken(
                    reusable,
                    startOffset,
                    endOffset.toInt(),
                    levelIndex,
                    palette,
                )
            }
            index++
        }
        return EntrySelection(
            entries = entries,
            stableFocusStartOffset = tokens.stableFocusStartOffset,
            stableFocusEndOffset = tokens.stableFocusEndOffset,
            isCapped = tokens.isCapped,
        )
    }

    private fun applyToken(
        reusable: PreviousTokenMarks,
        startOffset: Int,
        endOffset: Int,
        levelIndex: Int,
        palette: TokenPalette,
    ): VisibleTokenEntry {
        val colorKey = BracketColorPalette.levelKey(levelIndex)
        val attributes = palette.attributes[levelIndex]
        val previous = reusable.take(startOffset, endOffset)
        val highlighter = previous?.highlighter ?: addHighlighter(
            colorKey,
            startOffset,
            endOffset,
            attributes,
        )
        if (previous != null &&
            (previous.colorKey !== colorKey || previous.attributes != attributes)
        ) {
            applyPresentation(highlighter, colorKey, attributes)
        }
        highlighter.customRenderer = null
        return VisibleTokenEntry(highlighter, colorKey, levelIndex, attributes)
    }

    private fun addHighlighter(
        colorKey: TextAttributesKey,
        startOffset: Int,
        endOffset: Int,
        attributes: TextAttributes,
    ): RangeHighlighter {
        val markup = editor.markupModel
        return if (markup is MarkupModelEx) {
            markup.addRangeHighlighterAndChangeAttributes(
                colorKey,
                startOffset,
                endOffset,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                HighlighterTargetArea.EXACT_RANGE,
                false,
            ) { highlighter ->
                highlighter.textAttributes = attributes
            }
        } else {
            markup.addRangeHighlighter(
                colorKey,
                startOffset,
                endOffset,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                HighlighterTargetArea.EXACT_RANGE,
            ).also { highlighter ->
                applyPresentation(highlighter, colorKey, attributes)
            }
        }
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun applyPresentation(
        highlighter: RangeHighlighter,
        colorKey: TextAttributesKey,
        attributes: TextAttributes,
    ) {
        val rangeHighlighter = highlighter as? RangeHighlighterEx
        val markup = editor.markupModel as? MarkupModelEx
        if (rangeHighlighter != null && markup != null) {
            markup.changeAttributesInBatch(rangeHighlighter) {
                it.setTextAttributesKey(colorKey)
                it.textAttributes = attributes
            }
        } else {
            highlighter.setTextAttributesKey(colorKey)
            rangeHighlighter?.textAttributes = attributes
        }
    }

    private fun normalizedVisibleRange(reported: TextRange): TextRange {
        val documentLength = editor.document.textLength
        val caretOffset = editor.caretModel.primaryCaret.offset.coerceIn(0, documentLength)
        var startOffset = reported.startOffset.coerceIn(0, documentLength)
        var endOffset = reported.endOffset.coerceIn(startOffset, documentLength)
        if (startOffset == endOffset) {
            startOffset = minOf(startOffset, caretOffset)
            endOffset = maxOf(
                endOffset,
                (caretOffset.toLong() + 1)
                    .coerceAtMost(documentLength.toLong())
                    .toInt(),
            )
        }
        if (endOffset - startOffset > MAX_REPORTED_VISIBLE_CHARACTERS) {
            val anchorOffset = if (caretOffset in startOffset..endOffset) {
                caretOffset
            } else {
                (startOffset.toLong() + (endOffset - startOffset) / 2)
                    .coerceAtMost(documentLength.toLong())
                    .toInt()
            }
            startOffset = (anchorOffset - MAX_REPORTED_VISIBLE_CHARACTERS / 2)
                .coerceAtLeast(0)
            endOffset = (startOffset.toLong() + MAX_REPORTED_VISIBLE_CHARACTERS)
                .coerceAtMost(documentLength.toLong())
                .toInt()
            startOffset = (endOffset - MAX_REPORTED_VISIBLE_CHARACTERS).coerceAtLeast(0)
        }
        return TextRange(startOffset, endOffset)
    }

    private fun decorationFocusOffset(visible: TextRange): Int {
        val caretOffset = editor.caretModel.primaryCaret.offset
            .coerceIn(0, editor.document.textLength)
        return if (caretOffset in visible.startOffset..visible.endOffset) {
            caretOffset
        } else {
            visible.startOffset + visible.length / 2
        }
    }

    private fun desiredWindow(visible: TextRange): TextRange {
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
            val highlighter = entry.highlighter
            if (highlighter.isValid) highlighter.dispose()
        }
    }

    private class VisibleTokenEntry(
        val highlighter: RangeHighlighter,
        val colorKey: TextAttributesKey,
        val levelIndex: Int,
        var attributes: TextAttributes,
    )

    private inner class TokenPalette(options: BracketGuidePreferences) {
        val attributes = Array(StoredColorFormat.COLOR_COUNT) { level ->
            BracketColorPalette.bracketTextAttributes(editor.colorsScheme, options, level)
        }
    }

    private data class EntrySelection(
        val entries: List<VisibleTokenEntry>,
        val stableFocusStartOffset: Int? = null,
        val stableFocusEndOffset: Int? = null,
        val isCapped: Boolean = false,
    ) {
        companion object {
            val EMPTY = EntrySelection(emptyList())
        }
    }

    private class PreviousTokenMarks(entries: List<VisibleTokenEntry>) {
        private val previous = entries
        private var index = 0

        fun take(startOffset: Int, endOffset: Int): VisibleTokenEntry? {
            while (index < previous.size) {
                val entry = previous[index]
                val highlighter = entry.highlighter
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
                return entry
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

    private companion object {
        const val MAX_VISIBLE_TOKEN_DECORATIONS = 2_048
        const val MIN_TOKEN_WINDOW_PADDING = 256
        const val MAX_TOKEN_WINDOW_PADDING = 4_096
        const val MAX_REPORTED_VISIBLE_CHARACTERS = 16_384
    }
}
