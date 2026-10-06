package com.sijunyang.bracketpairguides.presentation

import com.intellij.openapi.editor.Editor
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair

/** Geometry-only reuse; missing indentation is repaired independently in the background. */
internal object GuidePositionFallback {
    fun guideAfterChange(
        editor: Editor,
        pair: BracketPair,
        previousPair: BracketPair,
        previous: BracketGuide?,
        currentAnchorLine: Int?,
        change: DocumentChange,
    ): BracketGuide? = sameLine(editor, pair) ?: previous
        ?.takeIf { change.isOutside(previousPair) }
        ?.withPair(editor, pair, currentAnchorLine)

    fun guideFor(editor: Editor, pair: BracketPair, previous: BracketGuide?, currentAnchorLine: Int?): BracketGuide? =
        sameLine(editor, pair) ?: previous
            ?.takeIf { it.pair.hasSameRange(pair) }
            ?.withPair(editor, pair, currentAnchorLine)

    private fun sameLine(editor: Editor, pair: BracketPair): BracketGuide? = if (pair.openLine == pair.closeLine) {
        BracketGuide(pair, 0, pair.openLine.coerceIn(0, editor.document.lineCount - 1))
    } else {
        null
    }

    private fun BracketPair.hasSameRange(other: BracketPair): Boolean =
        openOffset == other.openOffset && openTokenLength == other.openTokenLength &&
            closeOffset == other.closeOffset && closeTokenLength == other.closeTokenLength &&
            openLine == other.openLine && closeLine == other.closeLine

    private fun BracketGuide.withPair(editor: Editor, pair: BracketPair, currentAnchorLine: Int?): BracketGuide {
        val first = (if (pair.openLine < pair.closeLine) pair.openLine + 1 else pair.closeLine)
            .coerceIn(0, editor.document.lineCount - 1)
        val last = pair.closeLine.coerceIn(first, editor.document.lineCount - 1)
        return copy(pair = pair, anchorLine = (currentAnchorLine ?: anchorLine).coerceIn(first, last))
    }
}
