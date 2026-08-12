package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.StoredColorFormat

@State(
    name = "BracketPairGuides",
    storages = [Storage("bracket-pair-guides.xml")],
)
internal class BracketGuideSettings : SerializablePersistentStateComponent<BracketGuidePreferences>(
    BracketGuidePreferences(),
) {
    val options: BracketGuidePreferences
        get() = state

    override fun loadState(state: BracketGuidePreferences) {
        super.loadState(state.normalized())
    }

    fun replace(options: BracketGuidePreferences) {
        val normalized = options.normalized()
        if (state != normalized) {
            updateState { normalized }
        }
    }

    private fun BracketGuidePreferences.normalized(): BracketGuidePreferences = copy(
        disabledLanguageIds = disabledLanguageIds.asSequence()
            .map { languageId -> languageId.trim() }
            .filter { languageId -> languageId.isNotEmpty() }
            .distinct()
            .sorted()
            .toSet(),
        guideLineWidth = guideLineWidth.coerceIn(
            BracketGuidePreferences.MIN_GUIDE_LINE_WIDTH,
            BracketGuidePreferences.MAX_GUIDE_LINE_WIDTH,
        ),
        guideOpacityPercent = guideOpacityPercent.coerceIn(
            BracketGuidePreferences.MIN_GUIDE_OPACITY_PERCENT,
            BracketGuidePreferences.MAX_GUIDE_OPACITY_PERCENT,
        ),
        pairBackgroundOpacityPercent = pairBackgroundOpacityPercent.coerceIn(
            BracketGuidePreferences.MIN_PAIR_BACKGROUND_OPACITY_PERCENT,
            BracketGuidePreferences.MAX_PAIR_BACKGROUND_OPACITY_PERCENT,
        ),
        levelBaseColors = StoredColorFormat.validatedColors(levelBaseColors),
        guideLineColors = StoredColorFormat.validatedColors(guideLineColors),
        pairBorderColors = StoredColorFormat.validatedColors(pairBorderColors),
        pairBackgroundColors = StoredColorFormat.validatedColors(pairBackgroundColors),
    )

    companion object {
        fun getInstance(): BracketGuideSettings {
            return ApplicationManager.getApplication().getService(BracketGuideSettings::class.java)
        }
    }
}
