package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color

/**
 * Resolves every visual component from one level color unless the user enables
 * independent component colors. Keeping this logic outside both recognition
 * and painting makes the palette rules deterministic and directly testable.
 */
internal object BracketColorPalette {
    const val COLOR_COUNT = 6
    const val AUTOMATIC_COLOR = -1

    val LEVEL_KEYS: Array<TextAttributesKey> = Array(COLOR_COUNT) { index ->
        TextAttributesKey.createTextAttributesKey(
            "BRACKET_PAIR_GUIDES_BRACKET_DEPTH_${index + 1}",
            DefaultLanguageHighlighterColors.BRACES,
        )
    }

    fun levelIndex(depth: Int): Int = depth.mod(COLOR_COUNT)

    fun baseColor(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): Color {
        val index = levelIndex(depth)
        return storedColor(settings.levelBaseColors.getOrNull(index))
            ?: scheme.getAttributes(LEVEL_KEYS[index]).foregroundColor
            ?: scheme.defaultForeground
    }

    fun guideLineColor(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): Color = componentColor(
        scheme = scheme,
        settings = settings,
        depth = depth,
        overrides = settings.guideLineColors,
    )

    fun pairBorderColor(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): Color = componentColor(
        scheme = scheme,
        settings = settings,
        depth = depth,
        overrides = settings.pairBorderColors,
    )

    fun pairBackgroundSourceColor(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): Color = componentColor(
        scheme = scheme,
        settings = settings,
        depth = depth,
        overrides = settings.pairBackgroundColors,
    )

    fun pairBackgroundColor(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): Color {
        return blend(
            background = scheme.defaultBackground,
            foreground = pairBackgroundSourceColor(scheme, settings, depth),
            foregroundPercent = settings.pairBackgroundOpacityPercent,
        )
    }

    fun bracketTextAttributes(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): TextAttributes = TextAttributes().also {
        it.foregroundColor = baseColor(scheme, settings, depth)
    }

    fun activePairTextAttributes(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): TextAttributes = TextAttributes().also { attributes ->
        if (hasVisiblePairBackground(settings)) {
            attributes.backgroundColor = pairBackgroundColor(scheme, settings, depth)
        }
        if (settings.showActivePairBorder) {
            attributes.effectColor = pairBorderColor(scheme, settings, depth)
            attributes.effectType = EffectType.BOXED
        }
    }

    fun hasVisiblePairBackground(settings: PluginOptions): Boolean =
        settings.showActivePairBackground &&
            settings.pairBackgroundOpacityPercent.coerceIn(0, 100) > 0

    fun colorToStoredValue(color: Color): Int = color.rgb and 0x00FF_FFFF

    fun storedColor(value: Int?): Color? {
        if (value == null || value !in 0..0x00FF_FFFF) return null
        return Color(value)
    }

    fun normalizeColors(colors: List<Int>): List<Int> {
        return List(COLOR_COUNT) { index ->
            colors.getOrNull(index)?.takeIf { it in 0..0x00FF_FFFF }
                ?: AUTOMATIC_COLOR
        }
    }

    private fun componentColor(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
        overrides: List<Int>,
    ): Color {
        val index = levelIndex(depth)
        if (settings.useIndependentComponentColors) {
            storedColor(overrides.getOrNull(index))?.let { return it }
        }
        return baseColor(scheme, settings, depth)
    }

    private fun blend(
        background: Color,
        foreground: Color,
        foregroundPercent: Int,
    ): Color {
        val foregroundWeight = foregroundPercent.coerceIn(0, 100)
        val backgroundWeight = 100 - foregroundWeight
        return Color(
            (background.red * backgroundWeight + foreground.red * foregroundWeight) / 100,
            (background.green * backgroundWeight + foreground.green * foregroundWeight) / 100,
            (background.blue * backgroundWeight + foreground.blue * foregroundWeight) / 100,
        )
    }
}
