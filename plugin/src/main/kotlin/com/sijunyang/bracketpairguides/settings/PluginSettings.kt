package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.Property

/** Immutable settings consumed by analysis, rendering, and the settings page. */
internal data class PluginOptions(
    @JvmField @field:Property val enabled: Boolean = true,
    @JvmField @field:Property val disabledLanguageIds: Set<String> = emptySet(),
    @JvmField @field:Property val colorBracketTokens: Boolean = true,
    @JvmField @field:Property val showActiveGuide: Boolean = true,
    @JvmField @field:Property val showVerticalGuide: Boolean = true,
    @JvmField @field:Property val showHorizontalGuides: Boolean = true,
    @JvmField @field:Property val guideLineWidth: Int =
        PluginSettings.DEFAULT_GUIDE_LINE_WIDTH,
    @JvmField @field:Property val guideOpacityPercent: Int =
        PluginSettings.DEFAULT_GUIDE_OPACITY_PERCENT,
    @JvmField @field:Property val showActivePairBorder: Boolean = false,
    @JvmField @field:Property val showActivePairBackground: Boolean = false,
    @JvmField @field:Property val pairBackgroundOpacityPercent: Int =
        PluginSettings.DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT,
    @JvmField @field:Property val useIndependentComponentColors: Boolean = false,
    @JvmField @field:Property val levelBaseColors: List<Int> = automaticColors(),
    @JvmField @field:Property val guideLineColors: List<Int> = automaticColors(),
    @JvmField @field:Property val pairBorderColors: List<Int> = automaticColors(),
    @JvmField @field:Property val pairBackgroundColors: List<Int> = automaticColors(),
) {
    fun isLanguageEnabled(languageId: String): Boolean =
        languageId !in disabledLanguageIds

    val showsActivePair: Boolean
        get() = showActivePairBorder ||
            (showActivePairBackground && pairBackgroundOpacityPercent > 0)

    val showsGuide: Boolean
        get() = showActiveGuide && (showVerticalGuide || showHorizontalGuides)

    private companion object {
        fun automaticColors(): List<Int> = List(BracketColorPalette.COLOR_COUNT) {
            BracketColorPalette.AUTOMATIC_COLOR
        }
    }
}

@State(
    name = "BracketPairGuides",
    storages = [Storage("bracket-pair-guides.xml")],
)
internal class PluginSettings : SerializablePersistentStateComponent<PluginOptions>(
    PluginOptions(),
) {
    val options: PluginOptions
        get() = state

    override fun loadState(state: PluginOptions) {
        super.loadState(state.normalized())
    }

    fun replace(options: PluginOptions) {
        val normalized = options.normalized()
        if (state != normalized) {
            updateState { normalized }
        }
    }

    private fun PluginOptions.normalized(): PluginOptions = copy(
        disabledLanguageIds = disabledLanguageIds.asSequence()
            .map { languageId -> languageId.trim() }
            .filter { languageId -> languageId.isNotEmpty() }
            .distinct()
            .sorted()
            .toSet(),
        guideLineWidth = guideLineWidth.coerceIn(
            MIN_GUIDE_LINE_WIDTH,
            MAX_GUIDE_LINE_WIDTH,
        ),
        guideOpacityPercent = guideOpacityPercent.coerceIn(
            MIN_GUIDE_OPACITY_PERCENT,
            MAX_GUIDE_OPACITY_PERCENT,
        ),
        pairBackgroundOpacityPercent = pairBackgroundOpacityPercent.coerceIn(
            MIN_PAIR_BACKGROUND_OPACITY_PERCENT,
            MAX_PAIR_BACKGROUND_OPACITY_PERCENT,
        ),
        levelBaseColors = BracketColorPalette.normalizeColors(levelBaseColors),
        guideLineColors = BracketColorPalette.normalizeColors(guideLineColors),
        pairBorderColors = BracketColorPalette.normalizeColors(pairBorderColors),
        pairBackgroundColors = BracketColorPalette.normalizeColors(pairBackgroundColors),
    )

    companion object {
        const val MIN_GUIDE_LINE_WIDTH = 1
        const val MAX_GUIDE_LINE_WIDTH = 4
        const val DEFAULT_GUIDE_LINE_WIDTH = 1
        const val MIN_GUIDE_OPACITY_PERCENT = 10
        const val MAX_GUIDE_OPACITY_PERCENT = 100
        const val DEFAULT_GUIDE_OPACITY_PERCENT = 100
        const val MIN_PAIR_BACKGROUND_OPACITY_PERCENT = 0
        const val MAX_PAIR_BACKGROUND_OPACITY_PERCENT = 100
        const val DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT = 22

        fun getInstance(): PluginSettings {
            return ApplicationManager.getApplication().getService(PluginSettings::class.java)
        }
    }
}
