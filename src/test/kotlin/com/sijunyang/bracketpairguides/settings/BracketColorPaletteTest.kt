package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.editor.markup.EffectType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color

class BracketColorPaletteTest : BasePlatformTestCase() {
    fun testBaseColorDrivesEveryComponentByDefaultAndDiffersByLevel() {
        myFixture.configureByText("Sample.java", "class Sample {}")
        val scheme = myFixture.editor.colorsScheme
        val state = PluginSettings.State()
        val baseColors = (0 until BracketColorPalette.COLOR_COUNT).map { depth ->
            BracketColorPalette.baseColor(scheme, state, depth)
        }

        assertEquals(BracketColorPalette.COLOR_COUNT, baseColors.toSet().size)
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
        val state = PluginSettings.State(useIndependentComponentColors = true)
        state.levelBaseColors[2] = 0x102030
        state.guideLineColors[2] = 0x203040
        state.pairBorderColors[2] = 0x304050
        state.pairBackgroundColors[2] = 0x405060

        assertEquals(Color(0x102030), BracketColorPalette.baseColor(scheme, state, 2))
        assertEquals(Color(0x203040), BracketColorPalette.guideLineColor(scheme, state, 2))
        assertEquals(Color(0x304050), BracketColorPalette.pairBorderColor(scheme, state, 2))
        assertEquals(
            Color(0x405060),
            BracketColorPalette.pairBackgroundSourceColor(scheme, state, 2),
        )
    }

    fun testPairAttributesUseOnlyNamedBorderAndBackgroundComponents() {
        myFixture.configureByText("Sample.java", "class Sample {}")
        val scheme = myFixture.editor.colorsScheme
        val state = PluginSettings.State(
            showActivePairBorder = true,
            showActivePairBackground = true,
            pairBorderStyle = PairBorderStyle.ROUNDED_BOX.name,
        )

        val attributes = BracketColorPalette.activePairTextAttributes(scheme, state, 1)

        assertNull(attributes.foregroundColor)
        assertNotNull(attributes.backgroundColor)
        assertEquals(
            BracketColorPalette.pairBorderColor(scheme, state, 1),
            attributes.effectColor,
        )
        assertEquals(EffectType.ROUNDED_BOX, attributes.effectType)
        assertEquals(0, attributes.fontType)
    }

    fun testZeroPercentBackgroundDoesNotCoverOtherEditorHighlights() {
        myFixture.configureByText("Sample.java", "class Sample {}")
        val scheme = myFixture.editor.colorsScheme
        val state = PluginSettings.State(
            showActivePairBorder = true,
            showActivePairBackground = true,
            pairBackgroundOpacityPercent = 0,
            pairBorderStyle = PairBorderStyle.ROUNDED_BOX.name,
        )

        val attributes = BracketColorPalette.activePairTextAttributes(scheme, state, 1)

        assertNull(attributes.backgroundColor)
        assertEquals(
            BracketColorPalette.pairBorderColor(scheme, state, 1),
            attributes.effectColor,
        )
        assertEquals(EffectType.ROUNDED_BOX, attributes.effectType)

        state.showActivePairBorder = false
        val emptyAttributes = BracketColorPalette.activePairTextAttributes(
            scheme,
            state,
            1,
        )
        assertNull(emptyAttributes.backgroundColor)
        assertNull(emptyAttributes.effectColor)
    }
}
