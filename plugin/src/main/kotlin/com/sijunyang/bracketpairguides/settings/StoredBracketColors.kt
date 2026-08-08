package com.sijunyang.bracketpairguides.settings

import java.awt.Color

/** Stable conversion rules for colors persisted in [PluginOptions]. */
internal object StoredBracketColors {
    public const val COLOR_COUNT: Int = 6
    public const val AUTOMATIC_COLOR: Int = -1

    public fun automaticColors(): List<Int> = List(COLOR_COUNT) { AUTOMATIC_COLOR }

    public fun colorToStoredValue(color: Color): Int = color.rgb and 0x00FF_FFFF

    public fun storedColor(value: Int?): Color? {
        if (value == null || value !in 0..0x00FF_FFFF) return null
        return Color(value)
    }

    public fun normalizeColors(colors: List<Int>): List<Int> = List(COLOR_COUNT) { index ->
        colors.getOrNull(index)?.takeIf { it in 0..0x00FF_FFFF }
            ?: AUTOMATIC_COLOR
    }
}
