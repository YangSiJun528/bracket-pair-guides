package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.StoredColorFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BracketGuideSettingsTest {
    @Test
    fun `defaults to token colors and active guides without pair emphasis`() {
        val state = BracketGuidePreferences()

        assertTrue(state.enabled)
        assertTrue(state.disabledLanguageIds.isEmpty())
        assertTrue(state.colorBracketTokens)
        assertTrue(state.showActiveGuide)
        assertTrue(state.showVerticalGuide)
        assertTrue(state.showHorizontalGuides)
        assertFalse(state.showActivePairBorder)
        assertFalse(state.showActivePairBackground)
        assertFalse(state.useIndependentComponentColors)
        assertTrue(state.levelBaseColors.all { it == StoredColorFormat.AUTOMATIC_COLOR })
        assertTrue(state.guideLineColors.all { it == StoredColorFormat.AUTOMATIC_COLOR })
        assertTrue(state.pairBorderColors.all { it == StoredColorFormat.AUTOMATIC_COLOR })
        assertTrue(state.pairBackgroundColors.all { it == StoredColorFormat.AUTOMATIC_COLOR })
        assertEquals(1, state.guideLineWidth)
        assertEquals(100, state.guideOpacityPercent)
        assertEquals(22, state.pairBackgroundOpacityPercent)
    }

    @Test
    fun `normalizes persisted numeric guide options`() {
        val settings = BracketGuideSettings()
        val state = BracketGuidePreferences(
            guideLineWidth = Int.MAX_VALUE,
            guideOpacityPercent = Int.MIN_VALUE,
            pairBackgroundOpacityPercent = Int.MAX_VALUE,
            levelBaseColors = listOf(0x123456, -9, 0xFFFFFF + 1),
        )

        settings.loadState(state)

        assertEquals(BracketGuidePreferences.MAX_GUIDE_LINE_WIDTH, settings.state.guideLineWidth)
        assertEquals(
            BracketGuidePreferences.MIN_GUIDE_OPACITY_PERCENT,
            settings.state.guideOpacityPercent,
        )
        assertEquals(
            BracketGuidePreferences.MAX_PAIR_BACKGROUND_OPACITY_PERCENT,
            settings.state.pairBackgroundOpacityPercent,
        )
        assertEquals(StoredColorFormat.COLOR_COUNT, settings.state.levelBaseColors.size)
        assertEquals(0x123456, settings.state.levelBaseColors[0])
        assertEquals(StoredColorFormat.AUTOMATIC_COLOR, settings.state.levelBaseColors[1])
        assertEquals(StoredColorFormat.AUTOMATIC_COLOR, settings.state.levelBaseColors[2])
    }

    @Test
    fun `normalizes and preserves disabled matcher family IDs`() {
        val settings = BracketGuideSettings()
        settings.loadState(
            BracketGuidePreferences(
                disabledLanguageIds = setOf(
                    " Rust ",
                    "",
                    "JavaScript",
                    "Rust",
                ),
            ),
        )

        assertEquals(
            setOf("JavaScript", "Rust"),
            settings.state.disabledLanguageIds,
        )
        assertFalse(settings.options.isLanguageEnabled("Rust"))
        assertTrue(settings.options.isLanguageEnabled("JAVA"))
    }

    @Test
    fun `round trips every persisted option`() {
        val expected = BracketGuidePreferences(
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
            levelBaseColors = List(StoredColorFormat.COLOR_COUNT) { 0x101010 + it },
            guideLineColors = List(StoredColorFormat.COLOR_COUNT) { 0x202020 + it },
            pairBorderColors = List(StoredColorFormat.COLOR_COUNT) { 0x303030 + it },
            pairBackgroundColors = List(StoredColorFormat.COLOR_COUNT) { 0x404040 + it },
        )
        val source = BracketGuideSettings().apply { replace(expected) }
        val restored = BracketGuideSettings()

        val serialized = XmlSerializer.serialize(source.state)
        restored.loadState(XmlSerializer.deserialize(serialized, BracketGuidePreferences::class.java))

        assertEquals(expected, restored.options)
    }

    @Test
    fun `loads settings written by the previous mutable list state`() {
        val legacyXml = JDOMUtil.load(
            """
            <state>
              <option name="disabledLanguageIds">
                <list>
                  <option value=" Rust " />
                  <option value="JavaScript" />
                  <option value="Rust" />
                </list>
              </option>
              <option name="guideLineWidth" value="3" />
              <option name="levelBaseColors">
                <list>
                  <option value="1193046" />
                </list>
              </option>
            </state>
            """.trimIndent(),
        )
        val legacyState = XmlSerializer.deserialize(legacyXml, BracketGuidePreferences::class.java)
        val settings = BracketGuideSettings()
        settings.loadState(legacyState)

        assertEquals(setOf("JavaScript", "Rust"), settings.options.disabledLanguageIds)
        assertEquals(3, settings.options.guideLineWidth)
        assertEquals(0x123456, settings.options.levelBaseColors[0])
        assertEquals(StoredColorFormat.COLOR_COUNT, settings.options.levelBaseColors.size)
    }

    @Test
    fun `load isolates state from mutable caller collections`() {
        val disabledLanguageIds = mutableSetOf("Rust")
        val levelBaseColors = mutableListOf(0x123456)
        val input = BracketGuidePreferences(
            disabledLanguageIds = disabledLanguageIds,
            levelBaseColors = levelBaseColors,
        )
        val settings = BracketGuideSettings()
        settings.loadState(input)

        disabledLanguageIds.clear()
        levelBaseColors[0] = 0x654321

        assertEquals(setOf("Rust"), settings.options.disabledLanguageIds)
        assertEquals(0x123456, settings.options.levelBaseColors[0])
        assertNotSame(input.disabledLanguageIds, settings.options.disabledLanguageIds)
        assertNotSame(input.levelBaseColors, settings.options.levelBaseColors)
    }

    @Test
    fun `replace participates in platform modification tracking`() {
        val settings = BracketGuideSettings()
        val before = settings.stateModificationCount

        settings.replace(BracketGuidePreferences(enabled = false))

        assertTrue(settings.stateModificationCount > before)
        assertFalse(settings.options.enabled)
    }

    @Test
    fun `replace skips platform updates when normalized state is unchanged`() {
        val settings = BracketGuideSettings()
        val before = settings.stateModificationCount

        settings.replace(BracketGuidePreferences())

        assertEquals(before, settings.stateModificationCount)
    }
}
