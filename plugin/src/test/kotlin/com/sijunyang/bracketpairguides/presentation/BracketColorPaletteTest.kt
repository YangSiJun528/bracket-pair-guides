package com.sijunyang.bracketpairguides.presentation

import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.StoredBracketColors
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color

class BracketColorPaletteTest : BasePlatformTestCase() {
    fun testBaseColorDrivesEveryComponentByDefaultAndDiffersByLevel() {
        myFixture.configureByText("Sample.java", "class Sample {}")
        val scheme = myFixture.editor.colorsScheme
        val state = PluginOptions()
        val baseColors = (0 until StoredBracketColors.COLOR_COUNT).map { depth ->
            BracketColorPalette.baseColor(scheme, state, depth)
        }

        assertEquals(StoredBracketColors.COLOR_COUNT, baseColors.toSet().size)
        baseColors.forEachIndexed { depth, baseColor ->
            assertEquals(baseColor, BracketColorPalette.guideLineColor(scheme, state, depth))
            assertEquals(baseColor, BracketColorPalette.pairBorderColor(scheme, state, depth))
            assertEquals(
                baseColor,
                BracketColorPalette.pairBackgroundSourceColor(scheme, state, depth),
            )
            val renderedBackground = BracketColorPalette.pairBackgroundColor(
                scheme,
                state,
                depth,
            )
            assertTrue(renderedBackground != baseColor)
            assertTrue(renderedBackground != scheme.defaultBackground)
        }
    }

    fun testAdvancedColorsOverrideEachComponentIndependently() {
        myFixture.configureByText("Sample.java", "class Sample {}")
        val scheme = myFixture.editor.colorsScheme
        val state = PluginOptions(
            useIndependentComponentColors = true,
            levelBaseColors = PluginOptions().levelBaseColors.updated(2, 0x102030),
            guideLineColors = PluginOptions().guideLineColors.updated(2, 0x203040),
            pairBorderColors = PluginOptions().pairBorderColors.updated(2, 0x304050),
            pairBackgroundColors = PluginOptions().pairBackgroundColors.updated(2, 0x405060),
        )

        assertEquals(Color(0x102030), BracketColorPalette.baseColor(scheme, state, 2))
        assertEquals(Color(0x203040), BracketColorPalette.guideLineColor(scheme, state, 2))
        assertEquals(Color(0x304050), BracketColorPalette.pairBorderColor(scheme, state, 2))
        assertEquals(
            Color(0x405060),
            BracketColorPalette.pairBackgroundSourceColor(scheme, state, 2),
        )
    }

    fun testPairAttributesUseBoxBorderAndNamedBackgroundComponent() {
        myFixture.configureByText("Sample.java", "class Sample {}")
        val scheme = myFixture.editor.colorsScheme
        val state = PluginOptions(
            showActivePairBorder = true,
            showActivePairBackground = true,
        )

        val attributes = BracketColorPalette.activePairTextAttributes(scheme, state, 1)

        assertNull(attributes.foregroundColor)
        assertNotNull(attributes.backgroundColor)
        assertEquals(
            BracketColorPalette.pairBorderColor(scheme, state, 1),
            attributes.effectColor,
        )
        assertEquals(EffectType.BOXED, attributes.effectType)
        assertEquals(0, attributes.fontType)
    }

    fun testZeroPercentBackgroundDoesNotCoverOtherEditorHighlights() {
        myFixture.configureByText("Sample.java", "class Sample {}")
        val scheme = myFixture.editor.colorsScheme
        val state = PluginOptions(
            showActivePairBorder = true,
            showActivePairBackground = true,
            pairBackgroundOpacityPercent = 0,
        )

        val attributes = BracketColorPalette.activePairTextAttributes(scheme, state, 1)

        assertNull(attributes.backgroundColor)
        assertEquals(
            BracketColorPalette.pairBorderColor(scheme, state, 1),
            attributes.effectColor,
        )
        assertEquals(EffectType.BOXED, attributes.effectType)

        val backgroundOnly = state.copy(showActivePairBorder = false)
        val emptyAttributes = BracketColorPalette.activePairTextAttributes(
            scheme,
            backgroundOnly,
            1,
        )
        assertNull(emptyAttributes.backgroundColor)
        assertNull(emptyAttributes.effectColor)
    }

    private fun List<Int>.updated(index: Int, value: Int): List<Int> =
        toMutableList().also { it[index] = value }
}
