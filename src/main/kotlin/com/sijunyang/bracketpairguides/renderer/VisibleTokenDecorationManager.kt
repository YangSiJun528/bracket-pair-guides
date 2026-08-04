package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.settings.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange

internal data class VisibleTokenDecorations(
    val windowStartOffset: Int,
    val windowEndOffset: Int,
    val entries: List<VisibleTokenEntry>,
    val stableFocusStartOffset: Int = windowStartOffset,
    val stableFocusEndOffset: Int = windowEndOffset,
    val isCapped: Boolean = false,
) {
    /** A capped token slice must follow scrolling even inside its padded window. */
    fun canReuseFor(range: TextRange, focusOffset: Int): Boolean {
        if (windowStartOffset > range.startOffset || windowEndOffset < range.endOffset) {
            return false
        }
        return !isCapped ||
            focusOffset in stableFocusStartOffset..stableFocusEndOffset
    }

    companion object {
        val EMPTY = VisibleTokenDecorations(0, 0, emptyList())
    }
}

internal data class VisibleTokenEntry(
    val highlighter: RangeHighlighter,
    val colorKey: TextAttributesKey,
    val levelIndex: Int,
    val attributes: TextAttributes,
)

internal const val MAX_VISIBLE_TOKEN_DECORATIONS = 2_048

internal object VisibleTokenDecorationManager {
    fun replace(
        editor: Editor,
        previous: VisibleTokenDecorations?,
        tokenIndex: BracketTokenIndex,
        reportedVisibleRange: TextRange,
        options: PluginOptions,
    ): VisibleTokenDecorations {
        val visibleRange = normalizedVisibleRange(editor, reportedVisibleRange)
        val window = desiredWindow(editor, visibleRange)
        val reusable = ReusableHighlighters(previous?.entries.orEmpty())
        val selection = if (options.enabled && options.colorBracketTokens) {
            createEntries(
                editor,
                tokenIndex,
                window,
                decorationFocusOffset(editor, visibleRange),
                reusable,
                options,
            )
        } else {
            EntrySelection.EMPTY
        }
        reusable.disposeRemaining()
        return VisibleTokenDecorations(
            windowStartOffset = window.startOffset,
            windowEndOffset = window.endOffset,
            entries = selection.entries,
            stableFocusStartOffset = selection.stableFocusStartOffset
                ?: window.startOffset,
            stableFocusEndOffset = selection.stableFocusEndOffset
                ?: window.endOffset,
            isCapped = selection.isCapped,
        )
    }

    fun replaceIfOutsideWindow(
        editor: Editor,
        current: VisibleTokenDecorations,
        tokenIndex: BracketTokenIndex,
        reportedVisibleRange: TextRange,
        options: PluginOptions,
    ): VisibleTokenDecorations? {
        val visibleRange = normalizedVisibleRange(editor, reportedVisibleRange)
        val focusOffset = decorationFocusOffset(editor, visibleRange)
        if (current.canReuseFor(visibleRange, focusOffset)) return null
        return replace(editor, current, tokenIndex, visibleRange, options)
    }

    fun updateAttributes(
        editor: Editor,
        current: VisibleTokenDecorations,
        options: PluginOptions,
    ): VisibleTokenDecorations {
        if (!options.enabled || !options.colorBracketTokens) {
            disposeEntries(current.entries)
            return current.copy(
                entries = emptyList(),
                stableFocusStartOffset = current.windowStartOffset,
                stableFocusEndOffset = current.windowEndOffset,
                isCapped = false,
            )
        }

        val palette = TokenPalette(editor, options)
        val entries = current.entries.map { entry ->
            val attributes = palette.attributes[entry.levelIndex]
            if (entry.highlighter.isValid && entry.attributes != attributes) {
                applyPresentation(editor, entry.highlighter, entry.colorKey, attributes)
                entry.copy(attributes = attributes)
            } else {
                entry
            }
        }
        return current.copy(entries = entries)
    }

    fun dispose(decorations: VisibleTokenDecorations?) {
        decorations ?: return
        disposeEntries(decorations.entries)
    }

    private fun createEntries(
        editor: Editor,
        tokenIndex: BracketTokenIndex,
        window: TextRange,
        focusOffset: Int,
        reusable: ReusableHighlighters,
        options: PluginOptions,
    ): EntrySelection {
        val palette = TokenPalette(editor, options)
        val firstCandidate = tokenIndex.firstIndexInRange(window.startOffset)
        val lastCandidate = tokenIndex.firstIndexAtOrAfter(window.endOffset)
        val candidateCount = lastCandidate - firstCandidate
        var firstSelected = firstCandidate
        var lastSelected = lastCandidate
        val isCapped = candidateCount > MAX_VISIBLE_TOKEN_DECORATIONS
        val focusIndex = if (isCapped) {
            val focusIndex = tokenIndex.firstIndexAtOrAfter(focusOffset)
                .coerceIn(firstCandidate, lastCandidate)
            firstSelected = (focusIndex - MAX_VISIBLE_TOKEN_DECORATIONS / 2)
                .coerceAtLeast(firstCandidate)
            lastSelected = (firstSelected + MAX_VISIBLE_TOKEN_DECORATIONS)
                .coerceAtMost(lastCandidate)
            firstSelected = (lastSelected - MAX_VISIBLE_TOKEN_DECORATIONS)
                .coerceAtLeast(firstCandidate)
            focusIndex
        } else {
            firstCandidate
        }
        val entries = ArrayList<VisibleTokenEntry>(lastSelected - firstSelected)
        var index = firstSelected
        while (index < lastSelected) {
            val startOffset = tokenIndex.offsetAt(index)
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
        if (!isCapped) return EntrySelection(entries)

        val selectedFocusIndex = focusIndex.coerceIn(firstSelected, lastSelected - 1)
        val tolerance = MAX_VISIBLE_TOKEN_DECORATIONS / 4
        val stableFirstIndex = (selectedFocusIndex - tolerance)
            .coerceAtLeast(firstSelected)
        val stableAfterLastIndex = (selectedFocusIndex + tolerance + 1)
            .coerceAtMost(lastSelected)
        val stableFocusStartOffset = if (stableFirstIndex == firstCandidate) {
            window.startOffset
        } else {
            tokenIndex.offsetAt(stableFirstIndex)
        }
        val stableFocusEndOffset = if (stableAfterLastIndex == lastCandidate) {
            window.endOffset
        } else {
            tokenIndex.offsetAt(stableAfterLastIndex)
        }
        return EntrySelection(
            entries = entries,
            stableFocusStartOffset = stableFocusStartOffset,
            stableFocusEndOffset = stableFocusEndOffset,
            isCapped = true,
        )
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
        val attributes = palette.attributes[levelIndex]
        val previous = reusable.take(startOffset, endOffset)
        val highlighter = previous?.highlighter ?: addHighlighter(
            editor,
            colorKey,
            startOffset,
            endOffset,
            attributes,
        )
        if (previous != null &&
            (previous.colorKey !== colorKey || previous.attributes != attributes)
        ) {
            applyPresentation(editor, highlighter, colorKey, attributes)
        }
        highlighter.customRenderer = null
        return VisibleTokenEntry(highlighter, colorKey, levelIndex, attributes)
    }

    private fun addHighlighter(
        editor: Editor,
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
                applyPresentation(editor, highlighter, colorKey, attributes)
            }
        }
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun applyPresentation(
        editor: Editor,
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

    private fun normalizedVisibleRange(editor: Editor, reported: TextRange): TextRange {
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

    private fun decorationFocusOffset(editor: Editor, visible: TextRange): Int {
        val caretOffset = editor.caretModel.primaryCaret.offset
            .coerceIn(0, editor.document.textLength)
        return if (caretOffset in visible.startOffset..visible.endOffset) {
            caretOffset
        } else {
            visible.startOffset + visible.length / 2
        }
    }

    private fun desiredWindow(editor: Editor, visible: TextRange): TextRange {
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
        for ((highlighter) in entries) {
            if (highlighter.isValid) highlighter.dispose()
        }
    }

    private class TokenPalette(editor: Editor, options: PluginOptions) {
        val attributes = Array(BracketColorPalette.COLOR_COUNT) { level ->
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

    private class ReusableHighlighters(entries: List<VisibleTokenEntry>) {
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

    private const val MIN_TOKEN_WINDOW_PADDING = 256
    private const val MAX_TOKEN_WINDOW_PADDING = 4_096
    private const val MAX_REPORTED_VISIBLE_CHARACTERS = 16_384
}
