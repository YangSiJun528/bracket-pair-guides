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
            PluginOptions.MIN_GUIDE_LINE_WIDTH,
            PluginOptions.MAX_GUIDE_LINE_WIDTH,
        ),
        guideOpacityPercent = guideOpacityPercent.coerceIn(
            PluginOptions.MIN_GUIDE_OPACITY_PERCENT,
            PluginOptions.MAX_GUIDE_OPACITY_PERCENT,
        ),
        pairBackgroundOpacityPercent = pairBackgroundOpacityPercent.coerceIn(
            PluginOptions.MIN_PAIR_BACKGROUND_OPACITY_PERCENT,
            PluginOptions.MAX_PAIR_BACKGROUND_OPACITY_PERCENT,
        ),
        levelBaseColors = StoredBracketColors.normalizeColors(levelBaseColors),
        guideLineColors = StoredBracketColors.normalizeColors(guideLineColors),
        pairBorderColors = StoredBracketColors.normalizeColors(pairBorderColors),
        pairBackgroundColors = StoredBracketColors.normalizeColors(pairBackgroundColors),
    )

    companion object {
        fun getInstance(): PluginSettings {
            return ApplicationManager.getApplication().getService(PluginSettings::class.java)
        }
    }
}
