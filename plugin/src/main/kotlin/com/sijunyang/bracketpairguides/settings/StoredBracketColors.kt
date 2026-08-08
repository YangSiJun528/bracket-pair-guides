package com.sijunyang.bracketpairguides.settings

import java.awt.Color

/** Stable conversion rules for colors persisted in [PluginOptions]. */
internal object StoredBracketColors {
    const val COLOR_COUNT = 6
    const val AUTOMATIC_COLOR = -1

    fun automaticColors(): List<Int> = List(COLOR_COUNT) { AUTOMATIC_COLOR }

    fun colorToStoredValue(color: Color): Int = color.rgb and 0x00FF_FFFF

    fun storedColor(value: Int?): Color? {
        if (value == null || value !in 0..0x00FF_FFFF) return null
        return Color(value)
    }

    fun normalizeColors(colors: List<Int>): List<Int> = List(COLOR_COUNT) { index ->
        colors.getOrNull(index)?.takeIf { it in 0..0x00FF_FFFF }
            ?: AUTOMATIC_COLOR
    }
}
