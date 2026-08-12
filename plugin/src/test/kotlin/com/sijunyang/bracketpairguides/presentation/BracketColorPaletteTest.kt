package com.sijunyang.bracketpairguides.presentation

import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.StoredColorFormat
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import org.assertj.core.api.Assertions.assertThat

class BracketColorPaletteTest : BasePlatformTestCase() {
    fun testExplicitDefaultPaletteDrivesEveryComponentByDefault() {
        myFixture.configureByText("Sample.java", "class Sample {}")
        val scheme = myFixture.editor.colorsScheme
        val state = BracketGuidePreferences()
        val baseColors = (0 until StoredColorFormat.COLOR_COUNT).map { depth ->
            BracketColorPalette.baseColor(state, depth)
        }

        assertThat(baseColors).containsExactlyElementsOf(
            StoredColorFormat.defaultColors().map { rgb -> Color(rgb) },
        )
        baseColors.forEachIndexed { depth, baseColor ->
            assertThat(BracketColorPalette.guideLineColor(state, depth)).isEqualTo(baseColor)
            assertThat(BracketColorPalette.pairBorderColor(state, depth)).isEqualTo(baseColor)
            assertThat(BracketColorPalette.pairBackgroundSourceColor(state, depth)).isEqualTo(baseColor)
            val renderedBackground = BracketColorPalette.pairBackgroundColor(
                scheme,
                state,
                depth,
            )
            assertThat(renderedBackground).isNotEqualTo(baseColor)
            assertThat(renderedBackground).isNotEqualTo(scheme.defaultBackground)
        }
    }

    fun testAdvancedColorsOverrideEachComponentIndependently() {
        myFixture.configureByText("Sample.java", "class Sample {}")
        val state = BracketGuidePreferences(
            useIndependentComponentColors = true,
            levelBaseColors = BracketGuidePreferences().levelBaseColors.updated(2, 0x102030),
            guideLineColors = BracketGuidePreferences().guideLineColors.updated(2, 0x203040),
            pairBorderColors = BracketGuidePreferences().pairBorderColors.updated(2, 0x304050),
            pairBackgroundColors = BracketGuidePreferences().pairBackgroundColors.updated(2, 0x405060),
        )

        assertThat(BracketColorPalette.baseColor(state, 2)).isEqualTo(Color(0x102030))
        assertThat(BracketColorPalette.guideLineColor(state, 2)).isEqualTo(Color(0x203040))
        assertThat(BracketColorPalette.pairBorderColor(state, 2)).isEqualTo(Color(0x304050))
        assertThat(BracketColorPalette.pairBackgroundSourceColor(state, 2)).isEqualTo(Color(0x405060))
    }

    fun testDisabledComponentOverridesApplyBaseWithoutDiscardingSavedColors() {
        val state = BracketGuidePreferences(
            useIndependentComponentColors = false,
            levelBaseColors = BracketGuidePreferences().levelBaseColors.updated(1, 0x102030),
            guideLineColors = BracketGuidePreferences().guideLineColors.updated(1, 0x203040),
            pairBorderColors = BracketGuidePreferences().pairBorderColors.updated(1, 0x304050),
            pairBackgroundColors = BracketGuidePreferences().pairBackgroundColors.updated(1, 0x405060),
        )

        assertThat(BracketColorPalette.guideLineColor(state, 1)).isEqualTo(Color(0x102030))
        assertThat(BracketColorPalette.pairBorderColor(state, 1)).isEqualTo(Color(0x102030))
        assertThat(BracketColorPalette.pairBackgroundSourceColor(state, 1))
            .isEqualTo(Color(0x102030))
        assertThat(state.guideLineColors[1]).isEqualTo(0x203040)
        assertThat(state.pairBorderColors[1]).isEqualTo(0x304050)
        assertThat(state.pairBackgroundColors[1]).isEqualTo(0x405060)
    }

    fun testPairAttributesUseBoxBorderAndNamedBackgroundComponent() {
        myFixture.configureByText("Sample.java", "class Sample {}")
        val scheme = myFixture.editor.colorsScheme
        val state = BracketGuidePreferences(
            showActivePairBorder = true,
            showActivePairBackground = true,
        )

        val attributes = BracketColorPalette.activePairTextAttributes(scheme, state, 1)

        assertThat(attributes.foregroundColor).isNull()
        assertThat(attributes.backgroundColor).isNotNull()
        assertThat(attributes.effectColor).isEqualTo(BracketColorPalette.pairBorderColor(state, 1))
        assertThat(attributes.effectType).isEqualTo(EffectType.BOXED)
        assertThat(attributes.fontType).isEqualTo(0)
    }

    fun testZeroPercentBackgroundDoesNotCoverOtherEditorHighlights() {
        myFixture.configureByText("Sample.java", "class Sample {}")
        val scheme = myFixture.editor.colorsScheme
        val state = BracketGuidePreferences(
            showActivePairBorder = true,
            showActivePairBackground = true,
            pairBackgroundOpacityPercent = 0,
        )

        val attributes = BracketColorPalette.activePairTextAttributes(scheme, state, 1)

        assertThat(attributes.backgroundColor).isNull()
        assertThat(attributes.effectColor).isEqualTo(BracketColorPalette.pairBorderColor(state, 1))
        assertThat(attributes.effectType).isEqualTo(EffectType.BOXED)

        val backgroundOnly = state.copy(showActivePairBorder = false)
        val emptyAttributes = BracketColorPalette.activePairTextAttributes(
            scheme,
            backgroundOnly,
            1,
        )
        assertThat(emptyAttributes.backgroundColor).isNull()
        assertThat(emptyAttributes.effectColor).isNull()
    }

    private fun List<Int>.updated(index: Int, value: Int): List<Int> =
        toMutableList().also { it[index] = value }
}
