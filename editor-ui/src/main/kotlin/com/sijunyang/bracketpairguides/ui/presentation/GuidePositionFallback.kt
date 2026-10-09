package com.sijunyang.bracketpairguides.ui.presentation

import com.intellij.openapi.editor.Editor
import com.sijunyang.bracketpairguides.model.BracketGuide
import com.sijunyang.bracketpairguides.model.BracketPair

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
        ?.takeIf { change.isOutside(previousPair) || preservesMinimum(editor, change, it.guideColumn) }
        ?.withPair(editor, pair, currentAnchorLine)

    fun guideFor(editor: Editor, pair: BracketPair, previous: BracketGuide?, currentAnchorLine: Int?): BracketGuide? =
        sameLine(editor, pair) ?: previous
            ?.takeIf { it.pair.hasSameRange(pair) }
            ?.withPair(editor, pair, currentAnchorLine)

    /** The prefix before offset is unchanged; edits strictly right of the minimum cannot lower it. */
    private fun preservesMinimum(editor: Editor, change: DocumentChange, guideColumn: Int): Boolean {
        if (!change.horizontalWhitespaceOnly) return false
        val document = editor.document
        if (change.offset > document.textLength) return false
        val line = document.getLineNumber(change.offset)
        // Admit only a bounded physical prefix before requesting the SDK tab-expanded mapping.
        // This is a conservative optimization boundary, not a document indentation scan.
        if (change.offset - document.getLineStartOffset(line) > MAX_MAPPING_PREFIX_CHARACTERS) return false
        return editor.offsetToLogicalPosition(change.offset).column > guideColumn
    }

    private const val MAX_MAPPING_PREFIX_CHARACTERS = 4096

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
