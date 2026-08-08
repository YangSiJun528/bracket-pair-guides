package com.sijunyang.bracketpairguides.analysis

import org.jetbrains.annotations.ApiStatus

/** Overflow-safe visual-column arithmetic shared by indexed and provisional guides. */
@ApiStatus.Internal
public object GuideIndentation {
    private const val MAXIMUM_COLUMN: Int = Int.MAX_VALUE - 1

    public fun afterSpace(column: Int): Int =
        (column + 1).coerceAtMost(MAXIMUM_COLUMN)

    public fun afterTab(column: Int, tabSize: Int): Int {
        val effectiveTabSize = tabSize.coerceAtLeast(1)
        val advance = effectiveTabSize - column % effectiveTabSize
        return (column.toLong() + advance)
            .coerceAtMost(MAXIMUM_COLUMN.toLong())
            .toInt()
    }
}
