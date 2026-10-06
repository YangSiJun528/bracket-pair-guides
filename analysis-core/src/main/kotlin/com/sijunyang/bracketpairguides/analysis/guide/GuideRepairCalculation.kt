package com.sijunyang.bracketpairguides.analysis.guide

import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair

/** Owns the old provisional scan order and its consumed-character admission budget. */
class GuideRepairCalculation(
    private val pair: BracketPair,
    private val lines: IntRange,
    private val tabSize: Int,
    private val exact: Boolean,
    currentAnchorLine: Int?,
    private val checkCanceled: () -> Unit,
) {
    private val previousAnchor = currentAnchorLine?.coerceIn(lines.first, lines.last)
    private val candidates = sequence {
        if (!exact) {
            yield(lines.last)
            if (previousAnchor != null && previousAnchor != lines.last) yield(previousAnchor)
        }
        for (line in lines) {
            if (exact || line != lines.last && line != previousAnchor) yield(line)
        }
    }.iterator()
    private var initialCandidatesRemaining = if (exact) 0 else 1 + if (previousAnchor != null && previousAnchor != lines.last) 1 else 0
    private var remainingLines = MAXIMUM_LINES
    private var remainingCharacters = MAXIMUM_CHARACTERS
    private var minimum = VisualColumn.BLANK_LINE_COLUMN
    private var anchor = if (exact) lines.first else previousAnchor ?: lines.last
    private var scanningLine: Int? = null
    private var scanningForward = false
    private var indentation: LineIndentation? = null
    private var complete = false
    private var refused = false

    fun nextLine(): Int? {
        check(scanningLine == null) { "Finish the current line before requesting another" }
        checkCanceled()
        if (complete) return null
        if (!candidates.hasNext()) {
            complete = true
            if (minimum == VisualColumn.BLANK_LINE_COLUMN) anchor = lines.first
            return null
        }
        if (remainingLines == 0 || !exact && remainingCharacters == 0) {
            complete = true
            refused = exact
            return null
        }
        val line = candidates.next()
        val forward = initialCandidatesRemaining == 0
        if (initialCandidatesRemaining > 0) initialCandidatesRemaining--
        if (forward && minimum == 0 && anchor < line) {
            complete = true
            return null
        }
        scanningForward = forward
        remainingLines--
        return line.also {
            scanningLine = it
            indentation = LineIndentation(tabSize, checkCanceled)
        }
    }

    /** Returns true once this line is resolved, or the shared character budget refuses it. */
    fun append(chunk: String, endOfLine: Boolean): Boolean {
        val line = checkNotNull(scanningLine)
        val scanner = checkNotNull(indentation)
        checkCanceled()
        val content = chunk.indexOfFirst { it != ' ' && it != '\t' }
        val consumed = if (content < 0) chunk.length else content + 1
        if (consumed > remainingCharacters) {
            complete = true
            refused = exact
            scanningLine = null
            indentation = null
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
        scanningLine = null
        indentation = null
        // In forward order every earlier tie has been visited. The approximate strategy
        // starts with the closer/old anchor, and must visit earlier lines before stopping.
        if (minimum == 0 && (exact || anchor == lines.first || scanningForward && anchor <= line)) {
            complete = true
        }
        if (!exact && (remainingLines == 0 || remainingCharacters == 0)) complete = true
        return true
    }

    fun result(): BracketGuide? {
        check(complete && scanningLine == null) { "Repair must finish before publication" }
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
