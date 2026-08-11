package com.sijunyang.bracketpairguides.analysis.guide

/** Overflow-safe visual-column arithmetic shared by indexed and provisional guides. */
internal object VisualColumn {
    const val BLANK_LINE_COLUMN: Int = Int.MAX_VALUE
    private const val MAXIMUM_COLUMN: Int = Int.MAX_VALUE - 1

    fun afterSpace(column: Int): Int =
        (column + 1).coerceAtMost(MAXIMUM_COLUMN)

    fun afterTab(column: Int, tabSize: Int): Int {
        val effectiveTabSize = tabSize.coerceAtLeast(1)
        val advance = effectiveTabSize - column % effectiveTabSize
        return (column.toLong() + advance)
            .coerceAtMost(MAXIMUM_COLUMN.toLong())
            .toInt()
    }
}
