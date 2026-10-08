package com.sijunyang.bracketpairguides.core.internal

import com.sijunyang.bracketpairguides.model.BracketGuide
import com.sijunyang.bracketpairguides.model.BracketPair

/** Owns the old provisional scan order and its consumed-character admission budget. */
internal class GuideRepairCalculation(
    private val pair: BracketPair,
    private val lines: IntRange,
    tabSize: Int,
    private val exact: Boolean,
    currentAnchorLine: Int?,
    private val checkCanceled: () -> Unit,
) {
    private val previousAnchor = currentAnchorLine?.coerceIn(lines.first, lines.last)
    // Primitive cursors preserve closer/old-anchor/forward order without a coroutine sequence
    // or boxed line numbers. The final Int.MAX_VALUE line cannot overflow the cursor.
    private var initialCandidate = if (exact) 2 else 0
    private var nextForwardLine = lines.first
    private var forwardExhausted = false
    private var remainingLines = MAXIMUM_LINES
    private var remainingCharacters = MAXIMUM_CHARACTERS
    private var minimum = VisualColumn.BLANK_LINE_COLUMN
    private var anchor = if (exact) lines.first else previousAnchor ?: lines.last
    private var scanningLine = -1
    private var scanningForward = false
    private val indentation = LineIndentation(tabSize, checkCanceled)
    private var complete = false
    private var refused = false

    /** A negative result ends the scan; all admitted source lines are nonnegative. */
    fun nextLine(): Int {
        check(scanningLine < 0) { "Finish the current line before requesting another" }
        checkCanceled()
        if (complete) return -1
        val line = nextCandidate()
        if (line < 0) {
            complete = true
            if (minimum == VisualColumn.BLANK_LINE_COLUMN) anchor = lines.first
            return -1
        }
        if (remainingLines == 0 || !exact && remainingCharacters == 0) {
            complete = true
            refused = exact
            return -1
        }
        if (scanningForward && minimum == 0 && anchor < line) {
            complete = true
            return -1
        }
        remainingLines--
        scanningLine = line
        indentation.reset()
        return line
    }

    private fun nextCandidate(): Int {
        if (initialCandidate == 0) {
            initialCandidate = 1
            scanningForward = false
            return lines.last
        }
        if (initialCandidate == 1) {
            initialCandidate = 2
            if (previousAnchor != null && previousAnchor != lines.last) {
                scanningForward = false
                return previousAnchor
            }
        }
        scanningForward = true
        while (!forwardExhausted) {
            val line = nextForwardLine
            if (line == lines.last) forwardExhausted = true else nextForwardLine++
            if (exact || line != lines.last && line != previousAnchor) return line
        }
        return -1
    }

    /** Returns true once this line is resolved, or the shared character budget refuses it. */
    fun append(chunk: String, endOfLine: Boolean): Boolean {
        check(scanningLine >= 0) { "Request a line before appending indentation" }
        val line = scanningLine
        val scanner = indentation
        checkCanceled()
        val content = chunk.indexOfFirst { it != ' ' && it != '\t' }
        val consumed = if (content < 0) chunk.length else content + 1
        if (consumed > remainingCharacters) {
            complete = true
            refused = exact
            scanningLine = -1
            return true
        }
        remainingCharacters -= consumed
        if (!scanner.append(chunk, endOfLine)) return false
        val column = scanner.column
        if (column != VisualColumn.BLANK_LINE_COLUMN &&
            (column < minimum || column == minimum && line < anchor)
        ) {
            minimum = column
            anchor = line
        }
        scanningLine = -1
        // In forward order every earlier tie has been visited. The approximate strategy
        // starts with the closer/old anchor, and must visit earlier lines before stopping.
        if (minimum == 0 && (exact || anchor == lines.first || scanningForward && anchor <= line)) {
            complete = true
        }
        if (!exact && (remainingLines == 0 || remainingCharacters == 0)) complete = true
        return true
    }

    fun result(): BracketGuide? {
        check(complete && scanningLine < 0) { "Repair must finish before publication" }
        checkCanceled()
        return if (refused) null else BracketGuide(
            pair,
            minimum.takeUnless { it == VisualColumn.BLANK_LINE_COLUMN } ?: 0,
            anchor,
        )
    }

    companion object {
        const val MAXIMUM_LINES = 256
        const val MAXIMUM_CHARACTERS = 32_768
    }
}
