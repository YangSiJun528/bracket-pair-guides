package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair

/** Bounded provisional position used while an exact guide snapshot is pending. */
internal object GuidePositionFallback {
    fun guideFor(
        editor: Editor,
        pair: BracketPair,
        previous: BracketGuide?,
        currentAnchorLine: Int?,
    ): BracketGuide {
        val document = editor.document
        if (pair.openLine == pair.closeLine) {
            return BracketGuide(
                pair = pair,
                guideColumn = 0,
                anchorLine = pair.openLine.coerceIn(0, document.lineCount - 1),
            )
        }

        val firstLine = lineAfterOpenOrClose(pair.openLine, pair.closeLine)
            .coerceIn(0, document.lineCount - 1)
        val lastLine = pair.closeLine.coerceIn(firstLine, document.lineCount - 1)
        if (previous != null && previous.hasSameRange(pair)) {
            return previous.withPair(pair, currentAnchorLine, firstLine, lastLine)
        }

        val tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
        return scan(
            editor = editor,
            pair = pair,
            firstLine = firstLine,
            lastLine = lastLine,
            currentAnchorLine = currentAnchorLine,
            tabSize = tabSize,
        )
    }

    private fun BracketGuide.hasSameRange(pair: BracketPair): Boolean =
        this.pair.openOffset == pair.openOffset &&
            this.pair.openTokenLength == pair.openTokenLength &&
            this.pair.closeOffset == pair.closeOffset &&
            this.pair.closeTokenLength == pair.closeTokenLength &&
            this.pair.openLine == pair.openLine &&
            this.pair.closeLine == pair.closeLine

    private fun BracketGuide.withPair(
        pair: BracketPair,
        currentAnchorLine: Int?,
        firstLine: Int,
        lastLine: Int,
    ): BracketGuide = BracketGuide(
        pair = pair,
        guideColumn = guideColumn,
        anchorLine = currentAnchorLine?.coerceIn(firstLine, lastLine)
            ?: anchorLine.coerceIn(firstLine, lastLine),
    )

    private fun scan(
        editor: Editor,
        pair: BracketPair,
        firstLine: Int,
        lastLine: Int,
        currentAnchorLine: Int?,
        tabSize: Int,
    ): BracketGuide {
        val document = editor.document
        val text = document.immutableCharSequence
        val budget = ScanBudget()
        var minimum = NO_INDENT
        var anchorLine = currentAnchorLine?.coerceIn(firstLine, lastLine) ?: lastLine

        fun inspect(line: Int): Boolean {
            val indentation = indentationColumn(
                document = document,
                text = text,
                line = line,
                tabSize = tabSize,
                budget = budget,
            )
            if (indentation != UNRESOLVED &&
                indentation != NO_INDENT &&
                (indentation < minimum || indentation == minimum && line < anchorLine)
            ) {
                minimum = indentation
                anchorLine = line
            }
            return budget.exhausted
        }

        if (inspect(lastLine)) return result(pair, minimum, anchorLine)
        val previousAnchor = currentAnchorLine?.coerceIn(firstLine, lastLine)
        if (previousAnchor != null &&
            previousAnchor != lastLine &&
            inspect(previousAnchor)
        ) {
            return result(pair, minimum, anchorLine)
        }
        if (minimum == 0 && anchorLine == firstLine) {
            return result(pair, minimum, anchorLine)
        }

        var line = firstLine
        while (line < lastLine && !budget.exhausted) {
            if (line != previousAnchor && inspect(line)) {
                break
            }
            // Zero is the absolute minimum, but the exact index breaks ties by
            // the earliest line. Stop only after every earlier candidate has
            // either been inspected above or visited by this forward scan.
            if (minimum == 0 && anchorLine <= line) break
            line++
        }
        val resolvedAnchorLine = if (minimum == NO_INDENT &&
            !budget.exhausted
        ) {
            firstLine
        } else {
            anchorLine
        }
        return result(pair, minimum, resolvedAnchorLine)
    }

    private fun result(
        pair: BracketPair,
        minimum: Int,
        anchorLine: Int,
    ): BracketGuide = BracketGuide(
        pair = pair,
        guideColumn = minimum.takeUnless { it == NO_INDENT } ?: 0,
        anchorLine = anchorLine,
    )

    private fun indentationColumn(
        document: Document,
        text: CharSequence,
        line: Int,
        tabSize: Int,
        budget: ScanBudget,
    ): Int {
        if (!budget.startLine()) return UNRESOLVED
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        var column = 0
        var offset = start
        while (offset < end) {
            if (!budget.consumeCharacter()) return UNRESOLVED
            when (text[offset]) {
                ' ' -> column = incrementColumn(column)
                '\t' -> column = nextTabStop(column, tabSize)
                else -> return column
            }
            offset++
        }
        return NO_INDENT
    }

    private fun lineAfterOpenOrClose(openLine: Int, closeLine: Int): Int =
        if (openLine < closeLine) openLine + 1 else closeLine

    private fun incrementColumn(column: Int): Int =
        (column.toLong() + 1).coerceAtMost(MAXIMUM_COLUMN.toLong()).toInt()

    private fun nextTabStop(column: Int, tabSize: Int): Int {
        val effectiveTabSize = tabSize.coerceAtLeast(1)
        val remainder = column % effectiveTabSize
        val step = if (remainder == 0) effectiveTabSize else effectiveTabSize - remainder
        return (column.toLong() + step).coerceAtMost(MAXIMUM_COLUMN.toLong()).toInt()
    }

    private class ScanBudget {
        private var remainingLines = MAX_SYNCHRONOUS_LINES
        private var remainingCharacters = MAX_SYNCHRONOUS_CHARACTERS

        val exhausted: Boolean
            get() = remainingLines == 0 || remainingCharacters == 0

        fun startLine(): Boolean {
            if (remainingLines == 0) return false
            remainingLines--
            return true
        }

        fun consumeCharacter(): Boolean {
            if (remainingCharacters == 0) return false
            remainingCharacters--
            return true
        }
    }

    private const val MAX_SYNCHRONOUS_LINES = 256
    private const val MAX_SYNCHRONOUS_CHARACTERS = 32_768
    private const val MAXIMUM_COLUMN = Int.MAX_VALUE - 1
    private const val NO_INDENT = Int.MAX_VALUE
    private const val UNRESOLVED = -1
}
