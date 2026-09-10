package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class BracketGuidePreferenceNormalizationTest {
    @Test
    fun `already persisted immutable snapshot is reused without normalization`() {
        val inaccessibleLanguages =
            object : AbstractSet<String>() {
                override val size: Int
                    get() = error("The persisted language set must not be inspected")

                override fun iterator(): Iterator<String> = error("The persisted language set must not be iterated")
            }
        val inaccessibleColors =
            object : AbstractList<Int>() {
                override val size: Int
                    get() = error("Persisted colors must not be inspected")

                override fun get(index: Int): Int = error("Persisted colors must not be read")
            }
        val current =
            BracketGuidePreferences(
                disabledLanguageIds = inaccessibleLanguages,
                levelBaseColors = inaccessibleColors,
                guideLineColors = inaccessibleColors,
                pairBorderColors = inaccessibleColors,
                pairBackgroundColors = inaccessibleColors,
            )

        val normalized = current.normalizedForStorage(current)

        assertThat(normalized).isSameAs(current)
    }
}
