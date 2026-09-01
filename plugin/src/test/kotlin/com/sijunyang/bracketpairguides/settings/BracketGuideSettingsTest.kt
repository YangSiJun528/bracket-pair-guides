package com.sijunyang.bracketpairguides.settings

import com.intellij.util.xmlb.XmlSerializer
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.StoredColorFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.Test

class BracketGuideSettingsTest {
    @Test
    fun `defaults to token colors and active guides without pair emphasis`() {
        val state = BracketGuidePreferences()

        assertThat(state.enabled).isTrue()
        assertThat(state.disabledLanguageIds).isEmpty()
        assertThat(state.disableNativeMatchedBraceHighlighting).isTrue()
        assertThat(state.colorBracketTokens).isTrue()
        assertThat(state.showActiveGuide).isTrue()
        assertThat(state.showVerticalGuide).isTrue()
        assertThat(state.showHorizontalGuides).isTrue()
        assertThat(state.showActivePairBorder).isFalse()
        assertThat(state.showActivePairBackground).isFalse()
        assertThat(state.useIndependentComponentColors).isFalse()
        assertThat(state.levelBaseColors).isEqualTo(StoredColorFormat.defaultColors())
        assertThat(state.guideLineColors).isEqualTo(StoredColorFormat.defaultColors())
        assertThat(state.pairBorderColors).isEqualTo(StoredColorFormat.defaultColors())
        assertThat(state.pairBackgroundColors).isEqualTo(StoredColorFormat.defaultColors())
        assertThat(state.guideLineWidth).isEqualTo(1)
        assertThat(state.guideOpacityPercent).isEqualTo(100)
        assertThat(state.pairBackgroundOpacityPercent).isEqualTo(22)
    }

    @Test
    fun `normalizes persisted numeric guide options`() {
        val settings = BracketGuideSettings()
        val state =
            BracketGuidePreferences(
                guideLineWidth = Int.MAX_VALUE,
                guideOpacityPercent = Int.MIN_VALUE,
                pairBackgroundOpacityPercent = Int.MAX_VALUE,
            )

        settings.loadState(state)

        assertThat(settings.state.guideLineWidth).isEqualTo(BracketGuidePreferences.MAX_GUIDE_LINE_WIDTH)
        assertThat(settings.state.guideOpacityPercent).isEqualTo(BracketGuidePreferences.MIN_GUIDE_OPACITY_PERCENT)
        assertThat(
            settings.state.pairBackgroundOpacityPercent,
        ).isEqualTo(BracketGuidePreferences.MAX_PAIR_BACKGROUND_OPACITY_PERCENT)
    }

    @Test
    fun `rejects malformed color state instead of migrating it`() {
        val settings = BracketGuideSettings()

        assertThatIllegalArgumentException().isThrownBy {
            settings.loadState(
                BracketGuidePreferences(
                    levelBaseColors = listOf(0x123456, -1, 0xFFFFFF + 1),
                ),
            )
        }
    }

    @Test
    fun `normalizes and preserves disabled matcher family IDs`() {
        val settings = BracketGuideSettings()
        settings.loadState(
            BracketGuidePreferences(
                disabledLanguageIds =
                setOf(
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
        val expected =
            BracketGuidePreferences(
                enabled = false,
                disabledLanguageIds = setOf("Rust", "JavaScript"),
                disableNativeMatchedBraceHighlighting = false,
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
    fun `load isolates state from mutable caller collections`() {
        val disabledLanguageIds = mutableSetOf("Rust")
        val levelBaseColors =
            StoredColorFormat.defaultColors().toMutableList().apply {
                this[0] = 0x123456
            }
        val input =
            BracketGuidePreferences(
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
