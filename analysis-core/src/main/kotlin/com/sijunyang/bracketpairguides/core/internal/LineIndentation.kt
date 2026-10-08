package com.sijunyang.bracketpairguides.core.internal

/** Scans immutable chunks of one line without retaining its text. */
internal class LineIndentation(tabSize: Int, private val checkCanceled: () -> Unit = {}) {
    private val tabSize = tabSize.coerceAtLeast(1)
    private var currentColumn = 0

    var isComplete: Boolean = false
        private set

    val column: Int
        get() {
            check(isComplete) { "Indentation is only available after the line is complete" }
            return currentColumn
        }

    /** Returns true once content or the actual end of this line establishes its indentation. */
    fun append(chunk: String, endOfLine: Boolean): Boolean {
        check(!isComplete) { "A completed line cannot accept another indentation chunk" }
        checkCanceled()
        for (offset in chunk.indices) {
            if (offset != 0 && offset and CANCELLATION_CHARACTER_MASK == 0) checkCanceled()
            currentColumn = when (chunk[offset]) {
                ' ' -> VisualColumn.afterSpace(currentColumn)

                '\t' -> VisualColumn.afterTab(currentColumn, tabSize)

                else -> {
                    isComplete = true
                    return true
                }
            }
        }
        if (endOfLine) {
            currentColumn = VisualColumn.BLANK_LINE_COLUMN
            isComplete = true
        }
        return isComplete
    }

    private companion object {
        const val CANCELLATION_CHARACTER_MASK = 0xFFF
    }
}
