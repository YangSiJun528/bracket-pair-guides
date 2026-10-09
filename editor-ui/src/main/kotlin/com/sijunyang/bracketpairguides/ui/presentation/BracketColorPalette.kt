package com.sijunyang.bracketpairguides.ui.presentation

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.ui.preferences.StoredColorFormat
import java.awt.Color

/**
 * Resolves every visual component from one level color unless the user enables
 * independent component colors. Keeping this logic outside both recognition
 * and painting makes the palette rules deterministic and directly testable.
 */
internal object BracketColorPalette {
    private val levelKeys: Array<TextAttributesKey> =
        Array(StoredColorFormat.COLOR_COUNT) { index ->
            TextAttributesKey.createTextAttributesKey(
                "BRACKET_PAIR_GUIDES_BRACKET_DEPTH_${index + 1}",
            )
        }

    fun levelIndex(depth: Int): Int = depth.mod(StoredColorFormat.COLOR_COUNT)

    fun levelKey(levelIndex: Int): TextAttributesKey = levelKeys[levelIndex]

    fun baseColor(settings: BracketGuidePreferences, depth: Int): Color {
        val index = levelIndex(depth)
        return StoredColorFormat.storedColor(settings.levelBaseColors[index])
    }

    fun guideLineColor(settings: BracketGuidePreferences, depth: Int): Color = componentColor(
        settings = settings,
        depth = depth,
        overrides = settings.guideLineColors,
    )

    fun guideLineRgb(settings: BracketGuidePreferences, depth: Int): Int =
        componentRgb(settings, depth, settings.guideLineColors)

    fun pairBorderColor(settings: BracketGuidePreferences, depth: Int): Color = componentColor(
        settings = settings,
        depth = depth,
        overrides = settings.pairBorderColors,
    )

    fun pairBackgroundSourceColor(settings: BracketGuidePreferences, depth: Int): Color = componentColor(
        settings = settings,
        depth = depth,
        overrides = settings.pairBackgroundColors,
    )

    fun pairBackgroundColor(scheme: EditorColorsScheme, settings: BracketGuidePreferences, depth: Int): Color = blend(
        background = scheme.defaultBackground,
        foreground = pairBackgroundSourceColor(settings, depth),
        foregroundPercent = settings.pairBackgroundOpacityPercent,
    )

    fun bracketTextAttributes(settings: BracketGuidePreferences, depth: Int): TextAttributes = TextAttributes().also {
        it.foregroundColor = baseColor(settings, depth)
    }

    fun activePairTextAttributes(
        scheme: EditorColorsScheme,
        settings: BracketGuidePreferences,
        depth: Int,
    ): TextAttributes = TextAttributes().also { attributes ->
        if (hasVisiblePairBackground(settings)) {
            attributes.backgroundColor = pairBackgroundColor(scheme, settings, depth)
        }
        if (settings.showActivePairBorder) {
            attributes.effectColor = pairBorderColor(settings, depth)
            attributes.effectType = EffectType.BOXED
        }
    }

    fun hasVisiblePairBackground(settings: BracketGuidePreferences): Boolean = settings.showActivePairBackground &&
        settings.pairBackgroundOpacityPercent.coerceIn(0, 100) > 0

    private fun componentColor(settings: BracketGuidePreferences, depth: Int, overrides: List<Int>): Color =
        StoredColorFormat.storedColor(componentRgb(settings, depth, overrides))

    private fun componentRgb(settings: BracketGuidePreferences, depth: Int, overrides: List<Int>): Int {
        val index = levelIndex(depth)
        val storedValue =
            if (settings.useIndependentComponentColors) {
                overrides[index]
            } else {
                settings.levelBaseColors[index]
            }
        require(storedValue in 0..0x00FF_FFFF) { "Stored colors must be 24-bit RGB values" }
        return storedValue
    }

    private fun blend(background: Color, foreground: Color, foregroundPercent: Int): Color {
        val foregroundWeight = foregroundPercent.coerceIn(0, 100)
        val backgroundWeight = 100 - foregroundWeight
        return Color(
            (background.red * backgroundWeight + foreground.red * foregroundWeight) / 100,
            (background.green * backgroundWeight + foreground.green * foregroundWeight) / 100,
            (background.blue * backgroundWeight + foreground.blue * foregroundWeight) / 100,
        )
    }
}
