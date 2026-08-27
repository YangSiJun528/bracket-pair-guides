package com.sijunyang.bracketpairguides.presentation

import com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.TokenWindow
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
    private var stickySourceRanges: List<TextRange> = emptyList()
    private var entries: List<VisibleTokenEntry> = emptyList()
    private var stickyEntries: List<VisibleTokenEntry> = emptyList()
    private var stableFocusStartOffset = 0
    private var stableFocusEndOffset = 0
    private var isViewportCapped = false

    var isCapped = false
        private set

    fun replace(
        analysis: BracketSnapshot,
        reportedVisibleRange: TextRange,
        reportedStickySourceRanges: List<TextRange>,
        options: BracketGuidePreferences,
    ) {
        val visibleRange = normalizedVisibleRange(reportedVisibleRange)
        val window = desiredWindow(visibleRange)
        val stickyRanges = normalizedStickySourceRanges(reportedStickySourceRanges)
        val reusable = PreviousTokenMarks(entries)
        val selection = if (options.enabled && options.colorBracketTokens) {
            createEntries(
                analysis,
                window,
                stickyRanges,
                decorationFocusOffset(visibleRange),
                reusable,
                options,
            )
        } else {
            EntrySelection.EMPTY
        }
        reusable.disposeRemaining()
        windowStartOffset = window.startOffset
        windowEndOffset = window.endOffset
        stickySourceRanges = stickyRanges
        entries = selection.entries
        stickyEntries = selection.entries.filter(VisibleTokenEntry::stickyOnly)
        stableFocusStartOffset = selection.stableFocusStartOffset ?: window.startOffset
        stableFocusEndOffset = selection.stableFocusEndOffset ?: window.endOffset
        isViewportCapped = selection.isViewportCapped
        isCapped = selection.isCapped
    }

    fun replaceIfOutsideWindow(
        analysis: BracketSnapshot,
        reportedVisibleRange: TextRange,
        reportedStickySourceRanges: List<TextRange>,
        options: BracketGuidePreferences,
    ): Boolean {
        val visibleRange = normalizedVisibleRange(reportedVisibleRange)
        val stickyRanges = normalizedStickySourceRanges(reportedStickySourceRanges)
        val focusOffset = decorationFocusOffset(visibleRange)
        if (canReuseFor(visibleRange, stickyRanges, focusOffset)) return false
        replace(analysis, visibleRange, stickyRanges, options)
        return true
    }

    fun updateAttributes(options: BracketGuidePreferences) {
        if (!options.enabled || !options.colorBracketTokens) {
            disposeEntries(entries)
            entries = emptyList()
            stickyEntries = emptyList()
            stableFocusStartOffset = windowStartOffset
            stableFocusEndOffset = windowEndOffset
            isViewportCapped = false
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

    /** Removes source lines that are no longer authoritative after a document edit. */
    fun documentChanged() {
        if (stickyEntries.isEmpty()) return
        disposeEntries(stickyEntries)
        stickyEntries = emptyList()
        stickySourceRanges = emptyList()
        isViewportCapped = false
        isCapped = false
    }

    fun dispose() {
        disposeEntries(entries)
        windowStartOffset = 0
        windowEndOffset = 0
        stickySourceRanges = emptyList()
        entries = emptyList()
        stickyEntries = emptyList()
        stableFocusStartOffset = 0
        stableFocusEndOffset = 0
        isViewportCapped = false
        isCapped = false
    }

    /** A capped token slice must follow scrolling even inside its padded window. */
    private fun canReuseFor(
        range: TextRange,
        stickyRanges: List<TextRange>,
        focusOffset: Int,
    ): Boolean {
        return windowStartOffset <= range.startOffset &&
            windowEndOffset >= range.endOffset &&
            stickySourceRanges == stickyRanges &&
            (!isViewportCapped || focusOffset in stableFocusStartOffset..stableFocusEndOffset)
    }

    private fun createEntries(
        analysis: BracketSnapshot,
        window: TextRange,
        stickyRanges: List<TextRange>,
        focusOffset: Int,
        reusable: PreviousTokenMarks,
        options: BracketGuidePreferences,
    ): EntrySelection {
        val selection = selectTokens(
            analysis = analysis,
            window = window,
            stickyRanges = stickyRanges,
            focusOffset = focusOffset,
        )
        val palette = TokenPalette(options)
        val entries = ArrayList<VisibleTokenEntry>(selection.tokens.size)
        for (token in selection.tokens) {
            entries += applyToken(
                reusable,
                token.startOffset,
                token.endOffset,
                token.levelIndex,
                token.stickyOnly,
                palette,
            )
        }
        return EntrySelection(
            entries = entries,
            stableFocusStartOffset = selection.stableFocusStartOffset,
            stableFocusEndOffset = selection.stableFocusEndOffset,
            isViewportCapped = selection.isViewportCapped,
            isCapped = selection.isCapped,
        )
    }

    private fun selectTokens(
        analysis: BracketSnapshot,
        window: TextRange,
        stickyRanges: List<TextRange>,
        focusOffset: Int,
    ): TokenSelection {
        val selected = ArrayList<SelectedToken>(MAX_VISIBLE_TOKEN_DECORATIONS)
        val selectedRanges = HashMap<TokenRange, Int>(MAX_VISIBLE_TOKEN_DECORATIONS)
        val cappedStickyRanges = ArrayList<TextRange>(stickyRanges.size)
        var processedStickyRanges = 0
        for ((rangeIndex, range) in stickyRanges.withIndex()) {
            val remaining = MAX_VISIBLE_TOKEN_DECORATIONS - selected.size
            if (remaining == 0) {
                break
            }
            val rangesRemaining = stickyRanges.size - rangeIndex
            val reservedLimit = maxOf(1, remaining / rangesRemaining)
            val tokens = analysis.visibleTokens(
                range = range,
                focusOffset = range.startOffset + range.length / 2,
                limit = reservedLimit,
            )
            appendTokens(
                tokens = tokens,
                range = range,
                stickyOnly = true,
                selected = selected,
                selectedRanges = selectedRanges,
            )
            if (tokens.isCapped) cappedStickyRanges += range
            processedStickyRanges++
        }

        var stickySelectionCapped = processedStickyRanges < stickyRanges.size
        for (range in cappedStickyRanges) {
            val remaining = MAX_VISIBLE_TOKEN_DECORATIONS - selected.size
            if (remaining == 0) {
                stickySelectionCapped = true
                continue
            }
            val selectedInRange = selected.count { token ->
                token.startOffset >= range.startOffset &&
                    token.startOffset < range.endOffset
            }
            val tokens = analysis.visibleTokens(
                range = range,
                focusOffset = range.startOffset + range.length / 2,
                limit = minOf(
                    MAX_VISIBLE_TOKEN_DECORATIONS,
                    selectedInRange + remaining,
                ),
            )
            appendTokens(
                tokens = tokens,
                range = range,
                stickyOnly = true,
                selected = selected,
                selectedRanges = selectedRanges,
            )
            stickySelectionCapped = stickySelectionCapped || tokens.isCapped
        }

        val remaining = MAX_VISIBLE_TOKEN_DECORATIONS - selected.size
        val possibleViewportDuplicates = selected.count { token ->
            token.startOffset >= window.startOffset &&
                token.startOffset < window.endOffset
        }
        val viewportTokens = if (remaining > 0) {
            analysis.visibleTokens(
                range = window,
                focusOffset = focusOffset,
                limit = remaining + possibleViewportDuplicates,
            )
        } else {
            null
        }
        viewportTokens?.let { tokens ->
            appendTokens(
                tokens = tokens,
                range = window,
                stickyOnly = false,
                selected = selected,
                selectedRanges = selectedRanges,
            )
        }
        selected.sortWith(
            compareBy<SelectedToken>(SelectedToken::startOffset)
                .thenBy(SelectedToken::endOffset),
        )
        val viewportCapped = viewportTokens?.isCapped == true
        val viewportTokensOmitted = viewportTokens == null &&
            analysis.visibleTokens(
                range = window,
                focusOffset = focusOffset,
                limit = 1,
            ).size > 0
        return TokenSelection(
            tokens = selected,
            stableFocusStartOffset = viewportTokens?.stableFocusStartOffset,
            stableFocusEndOffset = viewportTokens?.stableFocusEndOffset,
            isViewportCapped = viewportCapped,
            isCapped = stickySelectionCapped ||
                viewportCapped ||
                viewportTokensOmitted,
        )
    }

    private fun appendTokens(
        tokens: TokenWindow,
        range: TextRange,
        stickyOnly: Boolean,
        selected: MutableList<SelectedToken>,
        selectedRanges: MutableMap<TokenRange, Int>,
    ) {
        var index = 0
        while (index < tokens.size && selected.size < MAX_VISIBLE_TOKEN_DECORATIONS) {
            val startOffset = tokens.offsetAt(index)
            val endOffset = startOffset.toLong() + tokens.lengthAt(index)
            if (startOffset < range.endOffset &&
                endOffset > range.startOffset &&
                endOffset <= editor.document.textLength
            ) {
                val tokenRange = TokenRange(startOffset, endOffset.toInt())
                val previousIndex = selectedRanges[tokenRange]
                if (previousIndex == null) {
                    selectedRanges[tokenRange] = selected.size
                    selected += SelectedToken(
                        startOffset = startOffset,
                        endOffset = endOffset.toInt(),
                        levelIndex = BracketColorPalette.levelIndex(tokens.depthAt(index)),
                        stickyOnly = stickyOnly,
                    )
                } else if (!stickyOnly) {
                    selected[previousIndex].stickyOnly = false
                }
            }
            index++
        }
    }

    private fun applyToken(
        reusable: PreviousTokenMarks,
        startOffset: Int,
        endOffset: Int,
        levelIndex: Int,
        stickyOnly: Boolean,
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
        return VisibleTokenEntry(
            highlighter,
            colorKey,
            levelIndex,
            attributes,
            stickyOnly,
        )
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

    private fun normalizedStickySourceRanges(reported: List<TextRange>): List<TextRange> {
        if (reported.isEmpty()) return emptyList()
        val documentLength = editor.document.textLength
        val sorted = reported.mapNotNull { range ->
            val startOffset = range.startOffset.coerceIn(0, documentLength)
            val endOffset = range.endOffset.coerceIn(startOffset, documentLength)
            TextRange(startOffset, endOffset).takeUnless(TextRange::isEmpty)
        }.sortedWith(
            compareBy<TextRange>(TextRange::getStartOffset)
                .thenBy(TextRange::getEndOffset),
        )
        if (sorted.isEmpty()) return emptyList()

        val merged = ArrayList<TextRange>(sorted.size)
        for (range in sorted) {
            val previous = merged.lastOrNull()
            if (previous != null && range.startOffset <= previous.endOffset) {
                merged[merged.lastIndex] = TextRange(
                    previous.startOffset,
                    maxOf(previous.endOffset, range.endOffset),
                )
            } else {
                merged += range
            }
        }
        return merged
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
        val stickyOnly: Boolean,
    )

    private class TokenPalette(options: BracketGuidePreferences) {
        val attributes = Array(StoredColorFormat.COLOR_COUNT) { level ->
            BracketColorPalette.bracketTextAttributes(options, level)
        }
    }

    private data class SelectedToken(
        val startOffset: Int,
        val endOffset: Int,
        val levelIndex: Int,
        var stickyOnly: Boolean,
    )

    private data class TokenRange(
        val startOffset: Int,
        val endOffset: Int,
    )

    private data class TokenSelection(
        val tokens: List<SelectedToken>,
        val stableFocusStartOffset: Int? = null,
        val stableFocusEndOffset: Int? = null,
        val isViewportCapped: Boolean = false,
        val isCapped: Boolean = false,
    )

    private data class EntrySelection(
        val entries: List<VisibleTokenEntry>,
        val stableFocusStartOffset: Int? = null,
        val stableFocusEndOffset: Int? = null,
        val isViewportCapped: Boolean = false,
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
                val highlighter = previous[index++].highlighter
                if (highlighter.isValid) highlighter.dispose()
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
