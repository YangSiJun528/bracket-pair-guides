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
        assertTrue(state.disabledLanguageIds.isEmpty())
        assertTrue(state.colorBracketTokens)
        assertTrue(state.showActiveGuide)
        assertTrue(state.showVerticalGuide)
        assertTrue(state.showHorizontalGuides)
        assertFalse(state.showActivePairBorder)
        assertFalse(state.showActivePairBackground)
        assertFalse(state.useIndependentComponentColors)
        assertTrue(state.levelBaseColors.all { it == BracketColorPalette.AUTOMATIC_COLOR })
        assertTrue(state.guideLineColors.all { it == BracketColorPalette.AUTOMATIC_COLOR })
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

    @Test
    fun `normalizes and preserves disabled matcher family IDs`() {
        val settings = PluginSettings()
        settings.loadState(
            PluginSettings.State(
                disabledLanguageIds = mutableListOf(
                    " Rust ",
                    "",
                    "JavaScript",
                    "Rust",
                ),
            ),
        )

        assertEquals(
            listOf("JavaScript", "Rust"),
            settings.state.disabledLanguageIds,
        )
        assertFalse(settings.options.isLanguageEnabled("Rust"))
        assertTrue(settings.options.isLanguageEnabled("JAVA"))
    }

    @Test
    fun `round trips every persisted option`() {
        val expected = PluginOptions(
            enabled = false,
            disabledLanguageIds = setOf("Rust", "JavaScript"),
            colorBracketTokens = false,
            showActiveGuide = false,
            showVerticalGuide = false,
            showHorizontalGuides = false,
            guideLineWidth = 3,
            guideOpacityPercent = 70,
            showActivePairBorder = true,
            showActivePairBackground = true,
            pairBackgroundOpacityPercent = 45,
            useIndependentComponentColors = true,
            levelBaseColors = List(BracketColorPalette.COLOR_COUNT) { 0x101010 + it },
            guideLineColors = List(BracketColorPalette.COLOR_COUNT) { 0x202020 + it },
            pairBorderColors = List(BracketColorPalette.COLOR_COUNT) { 0x303030 + it },
            pairBackgroundColors = List(BracketColorPalette.COLOR_COUNT) { 0x404040 + it },
        )
        val source = PluginSettings().apply { replace(expected) }
        val restored = PluginSettings()

        restored.loadState(source.state)

        assertEquals(expected, restored.options)
    }

    @Test
    fun `load and get state do not expose mutable persistence collections`() {
        val input = PluginSettings.State(
            disabledLanguageIds = mutableListOf("Rust"),
            levelBaseColors = mutableListOf(0x123456),
        )
        val settings = PluginSettings()
        settings.loadState(input)

        input.disabledLanguageIds.clear()
        input.levelBaseColors[0] = 0x654321
        val exported = settings.state
        exported.disabledLanguageIds.clear()
        exported.levelBaseColors[0] = 0x654321

        assertEquals(setOf("Rust"), settings.options.disabledLanguageIds)
        assertEquals(0x123456, settings.options.levelBaseColors[0])
    }

}
