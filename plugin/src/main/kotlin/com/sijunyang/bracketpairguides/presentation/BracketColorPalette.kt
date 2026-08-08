package com.sijunyang.bracketpairguides.presentation

import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.StoredBracketColors
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import org.jetbrains.annotations.TestOnly

/**
 * Resolves every visual component from one level color unless the user enables
 * independent component colors. Keeping this logic outside both recognition
 * and painting makes the palette rules deterministic and directly testable.
 */
internal object BracketColorPalette {
    private val levelKeys: Array<TextAttributesKey> =
        Array(StoredBracketColors.COLOR_COUNT) { index ->
            TextAttributesKey.createTextAttributesKey(
                "BRACKET_PAIR_GUIDES_BRACKET_DEPTH_${index + 1}",
                DefaultLanguageHighlighterColors.BRACES,
            )
        }

    public fun levelIndex(depth: Int): Int = depth.mod(StoredBracketColors.COLOR_COUNT)

    public fun levelKey(levelIndex: Int): TextAttributesKey = levelKeys[levelIndex]

    @TestOnly
    public fun isLevelKeyForTest(key: TextAttributesKey?): Boolean = key in levelKeys

    public fun baseColor(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): Color {
        val index = levelIndex(depth)
        return StoredBracketColors.storedColor(
            settings.levelBaseColors.getOrNull(index),
        )
            ?: scheme.getAttributes(levelKeys[index]).foregroundColor
            ?: scheme.defaultForeground
    }

    public fun guideLineColor(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): Color = componentColor(
        scheme = scheme,
        settings = settings,
        depth = depth,
        overrides = settings.guideLineColors,
    )

    public fun pairBorderColor(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): Color = componentColor(
        scheme = scheme,
        settings = settings,
        depth = depth,
        overrides = settings.pairBorderColors,
    )

    public fun pairBackgroundSourceColor(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): Color = componentColor(
        scheme = scheme,
        settings = settings,
        depth = depth,
        overrides = settings.pairBackgroundColors,
    )

    public fun pairBackgroundColor(
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

    public fun bracketTextAttributes(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
    ): TextAttributes = TextAttributes().also {
        it.foregroundColor = baseColor(scheme, settings, depth)
    }

    public fun activePairTextAttributes(
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

    public fun hasVisiblePairBackground(settings: PluginOptions): Boolean =
        settings.showActivePairBackground &&
            settings.pairBackgroundOpacityPercent.coerceIn(0, 100) > 0

    private fun componentColor(
        scheme: EditorColorsScheme,
        settings: PluginOptions,
        depth: Int,
        overrides: List<Int>,
    ): Color {
        val index = levelIndex(depth)
        if (settings.useIndependentComponentColors) {
            StoredBracketColors.storedColor(overrides.getOrNull(index))?.let { return it }
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
