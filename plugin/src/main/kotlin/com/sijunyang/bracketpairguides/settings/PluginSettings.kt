package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import org.jetbrains.annotations.ApiStatus

@State(
    name = "BracketPairGuides",
    storages = [Storage("bracket-pair-guides.xml")],
)
@ApiStatus.Internal
public class PluginSettings : SerializablePersistentStateComponent<PluginOptions>(
    PluginOptions(),
) {
    public val options: PluginOptions
        get() = state

    public override fun loadState(state: PluginOptions) {
        super.loadState(state.normalized())
    }

    public fun replace(options: PluginOptions) {
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

    public companion object {
        public fun getInstance(): PluginSettings {
            return ApplicationManager.getApplication().getService(PluginSettings::class.java)
        }
    }
}
