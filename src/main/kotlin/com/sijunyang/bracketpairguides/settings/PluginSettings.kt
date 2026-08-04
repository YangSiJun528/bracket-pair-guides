package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Immutable settings consumed by analysis, rendering, and the settings preview. */
internal data class PluginOptions(
    val enabled: Boolean = true,
    val disabledLanguageIds: Set<String> = emptySet(),
    val colorBracketTokens: Boolean = true,
    val showActiveGuide: Boolean = true,
    val showVerticalGuide: Boolean = true,
    val showHorizontalGuides: Boolean = true,
    val guideLineWidth: Int = PluginSettings.DEFAULT_GUIDE_LINE_WIDTH,
    val guideOpacityPercent: Int = PluginSettings.DEFAULT_GUIDE_OPACITY_PERCENT,
    val showActivePairBorder: Boolean = false,
    val showActivePairBackground: Boolean = false,
    val pairBackgroundOpacityPercent: Int =
        PluginSettings.DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT,
    val useIndependentComponentColors: Boolean = false,
    val levelBaseColors: List<Int> = automaticColors(),
    val guideLineColors: List<Int> = automaticColors(),
    val pairBorderColors: List<Int> = automaticColors(),
    val pairBackgroundColors: List<Int> = automaticColors(),
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
internal class PluginSettings : PersistentStateComponent<PluginSettings.State> {
    /** Mutable bean used only at the XML serialization boundary. */
    data class State(
        var enabled: Boolean = true,
        var disabledLanguageIds: MutableList<String> = mutableListOf(),
        var colorBracketTokens: Boolean = true,
        var showActiveGuide: Boolean = true,
        var showVerticalGuide: Boolean = true,
        var showHorizontalGuides: Boolean = true,
        var guideLineWidth: Int = DEFAULT_GUIDE_LINE_WIDTH,
        var guideOpacityPercent: Int = DEFAULT_GUIDE_OPACITY_PERCENT,
        var showActivePairBorder: Boolean = false,
        var showActivePairBackground: Boolean = false,
        var pairBackgroundOpacityPercent: Int = DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT,
        var useIndependentComponentColors: Boolean = false,
        var levelBaseColors: MutableList<Int> = automaticColors(),
        var guideLineColors: MutableList<Int> = automaticColors(),
        var pairBorderColors: MutableList<Int> = automaticColors(),
        var pairBackgroundColors: MutableList<Int> = automaticColors(),
    )

    @Volatile
    private var currentOptions = PluginOptions()

    val options: PluginOptions
        get() = currentOptions

    override fun getState(): State = currentOptions.toState()

    override fun loadState(state: State) {
        currentOptions = state.toOptions()
    }

    fun replace(options: PluginOptions) {
        currentOptions = options.normalized()
    }

    private fun State.toOptions(): PluginOptions = PluginOptions(
        enabled = enabled,
        disabledLanguageIds = disabledLanguageIds.toSet(),
        colorBracketTokens = colorBracketTokens,
        showActiveGuide = showActiveGuide,
        showVerticalGuide = showVerticalGuide,
        showHorizontalGuides = showHorizontalGuides,
        guideLineWidth = guideLineWidth,
        guideOpacityPercent = guideOpacityPercent,
        showActivePairBorder = showActivePairBorder,
        showActivePairBackground = showActivePairBackground,
        pairBackgroundOpacityPercent = pairBackgroundOpacityPercent,
        useIndependentComponentColors = useIndependentComponentColors,
        levelBaseColors = levelBaseColors,
        guideLineColors = guideLineColors,
        pairBorderColors = pairBorderColors,
        pairBackgroundColors = pairBackgroundColors,
    ).normalized()

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

    private fun PluginOptions.toState(): State = State(
        enabled = enabled,
        disabledLanguageIds = disabledLanguageIds.sorted().toMutableList(),
        colorBracketTokens = colorBracketTokens,
        showActiveGuide = showActiveGuide,
        showVerticalGuide = showVerticalGuide,
        showHorizontalGuides = showHorizontalGuides,
        guideLineWidth = guideLineWidth,
        guideOpacityPercent = guideOpacityPercent,
        showActivePairBorder = showActivePairBorder,
        showActivePairBackground = showActivePairBackground,
        pairBackgroundOpacityPercent = pairBackgroundOpacityPercent,
        useIndependentComponentColors = useIndependentComponentColors,
        levelBaseColors = levelBaseColors.toMutableList(),
        guideLineColors = guideLineColors.toMutableList(),
        pairBorderColors = pairBorderColors.toMutableList(),
        pairBackgroundColors = pairBackgroundColors.toMutableList(),
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

        private fun automaticColors(): MutableList<Int> = MutableList(
            BracketColorPalette.COLOR_COUNT,
        ) { BracketColorPalette.AUTOMATIC_COLOR }

        fun getInstance(): PluginSettings {
            return ApplicationManager.getApplication().getService(PluginSettings::class.java)
        }
    }
}
