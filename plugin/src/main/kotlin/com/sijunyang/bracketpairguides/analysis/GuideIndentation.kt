package com.sijunyang.bracketpairguides.analysis

/** Overflow-safe visual-column arithmetic shared by indexed and provisional guides. */
internal object GuideIndentation {
    const val MAXIMUM_COLUMN = Int.MAX_VALUE - 1

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
