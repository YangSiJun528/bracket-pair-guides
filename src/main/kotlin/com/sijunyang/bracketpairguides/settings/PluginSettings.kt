package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "BracketPairGuides",
    storages = [Storage("bracket-pair-guides.xml")],
)
internal class PluginSettings : PersistentStateComponent<PluginSettings.State> {
    data class State(
        var enabled: Boolean = true,
        var colorBracketTokens: Boolean = true,
        var showActiveGuide: Boolean = true,
        var showVerticalGuide: Boolean = true,
        var showHorizontalGuides: Boolean = true,
        var guideLineWidth: Int = DEFAULT_GUIDE_LINE_WIDTH,
        var guideOpacityPercent: Int = DEFAULT_GUIDE_OPACITY_PERCENT,
        var showActivePairBorder: Boolean = true,
        var showActivePairBackground: Boolean = true,
        var pairBackgroundOpacityPercent: Int = DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT,
        var pairBorderStyle: String = PairBorderStyle.BOX.name,
        var useIndependentComponentColors: Boolean = false,
        var levelBaseColors: MutableList<Int> = automaticColors(),
        var guideLineColors: MutableList<Int> = automaticColors(),
        var pairBorderColors: MutableList<Int> = automaticColors(),
        var pairBackgroundColors: MutableList<Int> = automaticColors(),
    )

    private var currentState = State()

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        state.guideLineWidth = state.guideLineWidth.coerceIn(
            MIN_GUIDE_LINE_WIDTH,
            MAX_GUIDE_LINE_WIDTH,
        )
        state.guideOpacityPercent = state.guideOpacityPercent.coerceIn(
            MIN_GUIDE_OPACITY_PERCENT,
            MAX_GUIDE_OPACITY_PERCENT,
        )
        state.pairBackgroundOpacityPercent = state.pairBackgroundOpacityPercent.coerceIn(
            MIN_PAIR_BACKGROUND_OPACITY_PERCENT,
            MAX_PAIR_BACKGROUND_OPACITY_PERCENT,
        )
        state.pairBorderStyle = PairBorderStyle.fromPersistentValue(
            state.pairBorderStyle,
        ).name
        state.levelBaseColors = BracketColorPalette.normalizeColors(state.levelBaseColors)
        state.guideLineColors = BracketColorPalette.normalizeColors(state.guideLineColors)
        state.pairBorderColors = BracketColorPalette.normalizeColors(state.pairBorderColors)
        state.pairBackgroundColors = BracketColorPalette.normalizeColors(
            state.pairBackgroundColors,
        )
        currentState = state
    }

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
