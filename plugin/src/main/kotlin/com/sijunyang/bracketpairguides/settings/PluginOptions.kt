package com.sijunyang.bracketpairguides.settings

import com.intellij.util.xmlb.annotations.Property
import org.jetbrains.annotations.ApiStatus

/** Immutable persisted options consumed by the plugin runtime and settings page. */
@ApiStatus.Internal
public data class PluginOptions(
    @JvmField @field:Property public val enabled: Boolean = true,
    @JvmField @field:Property public val disabledLanguageIds: Set<String> = emptySet(),
    @JvmField @field:Property public val colorBracketTokens: Boolean = true,
    @JvmField @field:Property public val showActiveGuide: Boolean = true,
    @JvmField @field:Property public val showVerticalGuide: Boolean = true,
    @JvmField @field:Property public val showHorizontalGuides: Boolean = true,
    @JvmField @field:Property public val guideLineWidth: Int =
        DEFAULT_GUIDE_LINE_WIDTH,
    @JvmField @field:Property public val guideOpacityPercent: Int =
        DEFAULT_GUIDE_OPACITY_PERCENT,
    @JvmField @field:Property public val showActivePairBorder: Boolean = false,
    @JvmField @field:Property public val showActivePairBackground: Boolean = false,
    @JvmField @field:Property public val pairBackgroundOpacityPercent: Int =
        DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT,
    @JvmField @field:Property public val useIndependentComponentColors: Boolean = false,
    @JvmField @field:Property public val levelBaseColors: List<Int> =
        StoredBracketColors.automaticColors(),
    @JvmField @field:Property public val guideLineColors: List<Int> =
        StoredBracketColors.automaticColors(),
    @JvmField @field:Property public val pairBorderColors: List<Int> =
        StoredBracketColors.automaticColors(),
    @JvmField @field:Property public val pairBackgroundColors: List<Int> =
        StoredBracketColors.automaticColors(),
) {
    public fun isLanguageEnabled(languageId: String): Boolean =
        languageId !in disabledLanguageIds

    public val showsActivePair: Boolean
        get() = showActivePairBorder ||
            (showActivePairBackground && pairBackgroundOpacityPercent > 0)

    public val showsGuide: Boolean
        get() = showActiveGuide && (showVerticalGuide || showHorizontalGuides)

    public companion object {
        public const val MIN_GUIDE_LINE_WIDTH: Int = 1
        public const val MAX_GUIDE_LINE_WIDTH: Int = 4
        private const val DEFAULT_GUIDE_LINE_WIDTH: Int = 1
        public const val MIN_GUIDE_OPACITY_PERCENT: Int = 10
        public const val MAX_GUIDE_OPACITY_PERCENT: Int = 100
        private const val DEFAULT_GUIDE_OPACITY_PERCENT: Int = 100
        public const val MIN_PAIR_BACKGROUND_OPACITY_PERCENT: Int = 0
        public const val MAX_PAIR_BACKGROUND_OPACITY_PERCENT: Int = 100
        private const val DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT: Int = 22
    }
}
