package com.sijunyang.bracketpairguides.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.StoredColorFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class BracketGuideSettingsTest {
    @Test
    fun `defaults to token colors and active guides without pair emphasis`() {
        val state = BracketGuidePreferences()

        assertThat(state.enabled).isTrue()
        assertThat(state.disabledLanguageIds).isEmpty()
        assertThat(state.colorBracketTokens).isTrue()
        assertThat(state.showActiveGuide).isTrue()
        assertThat(state.showVerticalGuide).isTrue()
        assertThat(state.showHorizontalGuides).isTrue()
        assertThat(state.showActivePairBorder).isFalse()
        assertThat(state.showActivePairBackground).isFalse()
        assertThat(state.useIndependentComponentColors).isFalse()
        assertThat(state.levelBaseColors).allMatch { it == StoredColorFormat.AUTOMATIC_COLOR }
        assertThat(state.guideLineColors).allMatch { it == StoredColorFormat.AUTOMATIC_COLOR }
        assertThat(state.pairBorderColors).allMatch { it == StoredColorFormat.AUTOMATIC_COLOR }
        assertThat(state.pairBackgroundColors).allMatch { it == StoredColorFormat.AUTOMATIC_COLOR }
        assertThat(state.guideLineWidth).isEqualTo(1)
        assertThat(state.guideOpacityPercent).isEqualTo(100)
        assertThat(state.pairBackgroundOpacityPercent).isEqualTo(22)
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

        assertThat(settings.state.guideLineWidth).isEqualTo(BracketGuidePreferences.MAX_GUIDE_LINE_WIDTH)
        assertThat(settings.state.guideOpacityPercent).isEqualTo(BracketGuidePreferences.MIN_GUIDE_OPACITY_PERCENT)
        assertThat(
            settings.state.pairBackgroundOpacityPercent,
        ).isEqualTo(BracketGuidePreferences.MAX_PAIR_BACKGROUND_OPACITY_PERCENT)
        assertThat(settings.state.levelBaseColors).hasSize(StoredColorFormat.COLOR_COUNT)
        assertThat(settings.state.levelBaseColors[0]).isEqualTo(0x123456)
        assertThat(settings.state.levelBaseColors[1]).isEqualTo(StoredColorFormat.AUTOMATIC_COLOR)
        assertThat(settings.state.levelBaseColors[2]).isEqualTo(StoredColorFormat.AUTOMATIC_COLOR)
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

        assertThat(settings.state.disabledLanguageIds).isEqualTo(setOf("JavaScript", "Rust"))
        assertThat(settings.options.isLanguageEnabled("Rust")).isFalse()
        assertThat(settings.options.isLanguageEnabled("JAVA")).isTrue()
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

        assertThat(restored.options).isEqualTo(expected)
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

        assertThat(settings.options.disabledLanguageIds).isEqualTo(setOf("JavaScript", "Rust"))
        assertThat(settings.options.guideLineWidth).isEqualTo(3)
        assertThat(settings.options.levelBaseColors[0]).isEqualTo(0x123456)
        assertThat(settings.options.levelBaseColors).hasSize(StoredColorFormat.COLOR_COUNT)
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

        assertThat(settings.options.disabledLanguageIds).isEqualTo(setOf("Rust"))
        assertThat(settings.options.levelBaseColors[0]).isEqualTo(0x123456)
        assertThat(settings.options.disabledLanguageIds).isNotSameAs(input.disabledLanguageIds)
        assertThat(settings.options.levelBaseColors).isNotSameAs(input.levelBaseColors)
    }

    @Test
    fun `replace participates in platform modification tracking`() {
        val settings = BracketGuideSettings()
        val before = settings.stateModificationCount

        settings.replace(BracketGuidePreferences(enabled = false))

        assertThat(settings.stateModificationCount).isGreaterThan(before)
        assertThat(settings.options.enabled).isFalse()
    }

    @Test
    fun `replace skips platform updates when normalized state is unchanged`() {
        val settings = BracketGuideSettings()
        val before = settings.stateModificationCount

        settings.replace(BracketGuidePreferences())

        assertThat(settings.stateModificationCount).isEqualTo(before)
    }
}
