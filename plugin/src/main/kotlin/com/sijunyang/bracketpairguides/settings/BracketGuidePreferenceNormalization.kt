package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.StoredColorFormat

/** Platform-independent normalization used at the persisted settings boundary. */
internal fun BracketGuidePreferences.normalizedForStorage(
    current: BracketGuidePreferences? = null,
): BracketGuidePreferences {
    if (this === current) return this
    return copy(
        disabledLanguageIds =
        disabledLanguageIds
            .asSequence()
            .map { languageId -> languageId.trim() }
            .filter { languageId -> languageId.isNotEmpty() }
            .distinct()
            .sorted()
            .toSet(),
        guideLineWidth =
        guideLineWidth.coerceIn(
            BracketGuidePreferences.MIN_GUIDE_LINE_WIDTH,
            BracketGuidePreferences.MAX_GUIDE_LINE_WIDTH,
        ),
        guideOpacityPercent =
        guideOpacityPercent.coerceIn(
            BracketGuidePreferences.MIN_GUIDE_OPACITY_PERCENT,
            BracketGuidePreferences.MAX_GUIDE_OPACITY_PERCENT,
        ),
        pairBackgroundOpacityPercent =
        pairBackgroundOpacityPercent.coerceIn(
            BracketGuidePreferences.MIN_PAIR_BACKGROUND_OPACITY_PERCENT,
            BracketGuidePreferences.MAX_PAIR_BACKGROUND_OPACITY_PERCENT,
        ),
        levelBaseColors = StoredColorFormat.validatedColors(levelBaseColors),
        guideLineColors = StoredColorFormat.validatedColors(guideLineColors),
        pairBorderColors = StoredColorFormat.validatedColors(pairBorderColors),
        pairBackgroundColors = StoredColorFormat.validatedColors(pairBackgroundColors),
    )
}
