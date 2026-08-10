package com.sijunyang.bracketpairguides.presentation

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair

/** A bracket pair whose offsets and guide anchor follow document edits. */
internal class TrackedBracketPair(private val editor: Editor) {
    var current: BracketPair? = null
        private set

    private var range: RangeMarker? = null
    private var anchor: RangeMarker? = null

    val adjusted: BracketPair?
        get() {
            val original = current ?: return null
            val currentRange = range?.takeIf(RangeMarker::isValid) ?: return null
            val closeOffset = currentRange.endOffset - original.closeTokenLength
            if (currentRange.startOffset + original.openTokenLength > closeOffset) return null
            return original.copy(
                openOffset = currentRange.startOffset,
                closeOffset = closeOffset,
                openLine = editor.document.getLineNumber(currentRange.startOffset),
                closeLine = editor.document.getLineNumber(closeOffset),
            )
        }

    val anchorLine: Int?
        get() {
            val currentAnchor = anchor?.takeIf(RangeMarker::isValid) ?: return null
            return editor.document.getLineNumber(
                currentAnchor.startOffset.coerceIn(0, editor.document.textLength),
            )
        }

    fun track(pair: BracketPair, guide: BracketGuide?) {
        clear()
        current = pair
        range = editor.document.createRangeMarker(
            pair.openOffset,
            pair.closeOffset + pair.closeTokenLength,
        ).apply {
            isGreedyToLeft = false
            isGreedyToRight = false
        }
        moveAnchorTo(guide)
    }

    /** Refreshes semantic metadata while the existing range marker remains authoritative. */
    fun refresh(pair: BracketPair, guide: BracketGuide?) {
        current = pair
        moveAnchorTo(guide)
    }

    fun clear() {
        range?.dispose()
        range = null
        anchor?.dispose()
        anchor = null
        current = null
    }

    private fun moveAnchorTo(guide: BracketGuide?) {
        val line = guide?.anchorLine?.coerceIn(0, editor.document.lineCount - 1)
        if (line == null) {
            anchor?.dispose()
            anchor = null
        } else if (anchorLine != line) {
            anchor?.dispose()
            val offset = editor.document.getLineStartOffset(line)
            anchor = editor.document.createRangeMarker(offset, offset).apply {
                isGreedyToLeft = false
                isGreedyToRight = false
            }
        }
    }
}
