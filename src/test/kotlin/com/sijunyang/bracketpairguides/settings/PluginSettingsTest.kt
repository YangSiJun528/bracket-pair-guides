package com.sijunyang.bracketpairguides.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSettingsTest {
    @Test
    fun `defaults to token colors and active guides without pair emphasis`() {
        val state = PluginSettings.State()

        assertTrue(state.enabled)
        assertTrue(state.colorBracketTokens)
        assertTrue(state.showActiveGuide)
        assertTrue(state.showVerticalGuide)
        assertTrue(state.showHorizontalGuides)
        assertFalse(state.showActivePairBorder)
        assertFalse(state.showActivePairBackground)
        assertFalse(state.useIndependentComponentColors)
        assertTrue(state.levelBaseColors.all { it == BracketColorPalette.AUTOMATIC_COLOR })
        assertTrue(state.guideLineColors.all { it == BracketColorPalette.AUTOMATIC_COLOR })
        assertEquals(PairBorderStyle.BOX.name, state.pairBorderStyle)
        assertEquals(1, state.guideLineWidth)
        assertEquals(100, state.guideOpacityPercent)
        assertEquals(22, state.pairBackgroundOpacityPercent)
    }

    @Test
    fun `normalizes persisted numeric guide options`() {
        val settings = PluginSettings()
        val state = PluginSettings.State(
            guideLineWidth = Int.MAX_VALUE,
            guideOpacityPercent = Int.MIN_VALUE,
            pairBackgroundOpacityPercent = Int.MAX_VALUE,
            levelBaseColors = mutableListOf(0x123456, -9, 0xFFFFFF + 1),
        )

        settings.loadState(state)

        assertEquals(PluginSettings.MAX_GUIDE_LINE_WIDTH, settings.state.guideLineWidth)
        assertEquals(
            PluginSettings.MIN_GUIDE_OPACITY_PERCENT,
            settings.state.guideOpacityPercent,
        )
        assertEquals(
            PluginSettings.MAX_PAIR_BACKGROUND_OPACITY_PERCENT,
            settings.state.pairBackgroundOpacityPercent,
        )
        assertEquals(BracketColorPalette.COLOR_COUNT, settings.state.levelBaseColors.size)
        assertEquals(0x123456, settings.state.levelBaseColors[0])
        assertEquals(BracketColorPalette.AUTOMATIC_COLOR, settings.state.levelBaseColors[1])
        assertEquals(BracketColorPalette.AUTOMATIC_COLOR, settings.state.levelBaseColors[2])
    }

}
