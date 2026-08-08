package com.sijunyang.bracketpairguides.renderer

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.index.BracketGuide
import com.sijunyang.bracketpairguides.analysis.index.GuideIndentation
import com.sijunyang.bracketpairguides.analysis.index.GuidePositionIndex

/** Bounded indentation lookup used only while the authoritative snapshot is stale. */
internal object ActiveGuidePositionResolver {
    fun resolve(
        editor: Editor,
        pair: BracketPair,
        previous: BracketGuide?,
        currentAnchorLine: Int?,
        change: DocumentChange?,
    ): BracketGuide {
        val document = editor.document
        if (pair.openLine == pair.closeLine) {
            return BracketGuide(
                pair = pair,
                guideColumn = 0,
                anchorLine = pair.openLine.coerceIn(0, document.lineCount - 1),
            )
        }

        val firstLine = GuidePositionIndex.lineAfterOpenOrClose(pair.openLine, pair.closeLine)
            .coerceIn(0, document.lineCount - 1)
        val lastLine = pair.closeLine.coerceIn(firstLine, document.lineCount - 1)
        if (previous != null &&
            change?.mayAffectGuidePosition != true &&
            previous.canReuseFor(pair, change)
        ) {
            return previous.withPair(pair, currentAnchorLine, firstLine, lastLine)
        }

        val tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
        val changedLine = change?.offset
            ?.coerceIn(0, document.textLength)
            ?.let(document::getLineNumber)
            ?.takeIf { it in firstLine..lastLine }
        return scan(
            editor = editor,
            pair = pair,
            firstLine = firstLine,
            lastLine = lastLine,
            changedLine = changedLine,
            currentAnchorLine = currentAnchorLine,
            tabSize = tabSize,
        )
    }

    /**
     * Exact ranges are unchanged. A uniform endpoint translation is also safe
     * when the edit occurred before the pair and did not change its guide lines.
     * Any one-sided boundary change can represent a rematch and must be scanned.
     */
    private fun BracketGuide.canReuseFor(
        pair: BracketPair,
        change: DocumentChange?,
    ): Boolean {
        val previousPair = this.pair
        if (previousPair.hasSameRange(pair)) return true
        if (change == null ||
            previousPair.openTokenLength != pair.openTokenLength ||
            previousPair.closeTokenLength != pair.closeTokenLength ||
            previousPair.openLine != pair.openLine ||
            previousPair.closeLine != pair.closeLine
        ) {
            return false
        }

        val openShift = pair.openOffset.toLong() - previousPair.openOffset
        val closeShift = pair.closeOffset.toLong() - previousPair.closeOffset
        return openShift == closeShift &&
            change.offset <= minOf(previousPair.openOffset, pair.openOffset)
    }

    private fun BracketPair.hasSameRange(other: BracketPair): Boolean =
        openOffset == other.openOffset &&
            openTokenLength == other.openTokenLength &&
            closeOffset == other.closeOffset &&
            closeTokenLength == other.closeTokenLength &&
            openLine == other.openLine &&
            closeLine == other.closeLine

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
        changedLine: Int?,
        currentAnchorLine: Int?,
        tabSize: Int,
    ): BracketGuide {
        val document = editor.document
        val text = document.immutableCharSequence
        val budget = ScanBudget()
        var minimum = GuidePositionIndex.NO_INDENT
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
                indentation != GuidePositionIndex.NO_INDENT &&
                (indentation < minimum || indentation == minimum && line < anchorLine)
            ) {
                minimum = indentation
                anchorLine = line
            }
            return budget.exhausted
        }

        if (inspect(lastLine)) return result(pair, minimum, anchorLine)
        if (changedLine != null && changedLine != lastLine && inspect(changedLine)) {
            return result(pair, minimum, anchorLine)
        }
        val previousAnchor = currentAnchorLine?.coerceIn(firstLine, lastLine)
        if (previousAnchor != null &&
            previousAnchor != lastLine &&
            previousAnchor != changedLine &&
            inspect(previousAnchor)
        ) {
            return result(pair, minimum, anchorLine)
        }
        if (minimum == 0 && anchorLine == firstLine) {
            return result(pair, minimum, anchorLine)
        }

        var line = firstLine
        while (line < lastLine && !budget.exhausted) {
            if (line != changedLine && line != previousAnchor && inspect(line)) {
                break
            }
            // Zero is the absolute minimum, but the exact index breaks ties by
            // the earliest line. Stop only after every earlier candidate has
            // either been inspected above or visited by this forward scan.
            if (minimum == 0 && anchorLine <= line) break
            line++
        }
        val resolvedAnchorLine = if (minimum == GuidePositionIndex.NO_INDENT &&
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
        guideColumn = minimum.takeUnless { it == GuidePositionIndex.NO_INDENT } ?: 0,
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
                ' ' -> column = GuideIndentation.afterSpace(column)
                '\t' -> column = GuideIndentation.afterTab(column, tabSize)
                else -> return column
            }
            offset++
        }
        return GuidePositionIndex.NO_INDENT
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
    private const val UNRESOLVED = -1
}
