package com.sijunyang.bracketpairguides.preferences

import java.awt.Color

/** Stable conversion rules for colors persisted in [BracketGuidePreferences]. */
internal object StoredColorFormat {
    const val COLOR_COUNT: Int = 6

    private val DEFAULT_COLORS = listOf(
        0xFFD700,
        0xDA70D6,
        0x179FFF,
        0x00CC7A,
        0xFF6B6B,
        0xCC8833,
    )

    fun defaultColors(): List<Int> = DEFAULT_COLORS.toList()

    fun defaultColor(level: Int): Int = DEFAULT_COLORS[level]

    fun colorToStoredValue(color: Color): Int = color.rgb and 0x00FF_FFFF

    fun storedColor(value: Int): Color {
        require(value in 0..0x00FF_FFFF) { "Stored colors must be 24-bit RGB values" }
        return Color(value)
    }

    fun validatedColors(colors: List<Int>): List<Int> {
        require(colors.size == COLOR_COUNT) { "Exactly $COLOR_COUNT colors are required" }
        require(colors.all { color -> color in 0..0x00FF_FFFF }) {
            "Stored colors must be 24-bit RGB values"
        }
        return colors.toList()
    }
}
