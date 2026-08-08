package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

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
        levelBaseColors = StoredBracketColors.normalizeColors(levelBaseColors),
        guideLineColors = StoredBracketColors.normalizeColors(guideLineColors),
        pairBorderColors = StoredBracketColors.normalizeColors(pairBorderColors),
        pairBackgroundColors = StoredBracketColors.normalizeColors(pairBackgroundColors),
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
