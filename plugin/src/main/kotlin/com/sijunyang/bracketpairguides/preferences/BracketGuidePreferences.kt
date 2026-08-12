package com.sijunyang.bracketpairguides.preferences

import com.intellij.util.xmlb.annotations.Property

/** Immutable persisted options consumed by the plugin runtime and settings page. */
internal data class BracketGuidePreferences(
    @JvmField @field:Property val enabled: Boolean = true,
    @JvmField @field:Property val disabledLanguageIds: Set<String> = emptySet(),
    @JvmField @field:Property val colorBracketTokens: Boolean = true,
    @JvmField @field:Property val showActiveGuide: Boolean = true,
    @JvmField @field:Property val showVerticalGuide: Boolean = true,
    @JvmField @field:Property val showHorizontalGuides: Boolean = true,
    @JvmField @field:Property val guideLineWidth: Int =
        DEFAULT_GUIDE_LINE_WIDTH,
    @JvmField @field:Property val guideOpacityPercent: Int =
        DEFAULT_GUIDE_OPACITY_PERCENT,
    @JvmField @field:Property val showActivePairBorder: Boolean = false,
    @JvmField @field:Property val showActivePairBackground: Boolean = false,
    @JvmField @field:Property val pairBackgroundOpacityPercent: Int =
        DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT,
    @JvmField @field:Property val useIndependentComponentColors: Boolean = false,
    @JvmField @field:Property val levelBaseColors: List<Int> =
        StoredColorFormat.defaultColors(),
    @JvmField @field:Property val guideLineColors: List<Int> =
        StoredColorFormat.defaultColors(),
    @JvmField @field:Property val pairBorderColors: List<Int> =
        StoredColorFormat.defaultColors(),
    @JvmField @field:Property val pairBackgroundColors: List<Int> =
        StoredColorFormat.defaultColors(),
) {
    fun isLanguageEnabled(languageId: String): Boolean =
        languageId !in disabledLanguageIds

    val showsActivePair: Boolean
        get() = showActivePairBorder ||
            (showActivePairBackground && pairBackgroundOpacityPercent > 0)

    val showsGuide: Boolean
        get() = showActiveGuide && (showVerticalGuide || showHorizontalGuides)

    companion object {
        const val MIN_GUIDE_LINE_WIDTH: Int = 1
        const val MAX_GUIDE_LINE_WIDTH: Int = 4
        private const val DEFAULT_GUIDE_LINE_WIDTH: Int = 1
        const val MIN_GUIDE_OPACITY_PERCENT: Int = 10
        const val MAX_GUIDE_OPACITY_PERCENT: Int = 100
        private const val DEFAULT_GUIDE_OPACITY_PERCENT: Int = 100
        const val MIN_PAIR_BACKGROUND_OPACITY_PERCENT: Int = 0
        const val MAX_PAIR_BACKGROUND_OPACITY_PERCENT: Int = 100
        private const val DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT: Int = 22
    }
}
