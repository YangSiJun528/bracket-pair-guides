package com.sijunyang.bracketpairguides.preferences

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.Test
import java.awt.Color

class StoredColorFormatTest {
    @Test
    fun `defines the exact built-in palette`() {
        assertThat(StoredColorFormat.defaultColors()).containsExactly(
            0xFFD700,
            0xDA70D6,
            0x179FFF,
            0x00CC7A,
            0xFF6B6B,
            0xCC8833,
        )
        assertThat(StoredColorFormat.defaultColors())
            .isNotSameAs(StoredColorFormat.defaultColors())
    }

    @Test
    fun `validates exact concrete persisted colors without migration`() {
        val colors =
            listOf(
                0x010203,
                0x102030,
                0x203040,
                0xA0B0C0,
                0x000000,
                0xFFFFFF,
            )

        assertThat(StoredColorFormat.validatedColors(colors)).isEqualTo(colors)
        assertThat(StoredColorFormat.validatedColors(colors)).isNotSameAs(colors)
        assertThatIllegalArgumentException()
            .isThrownBy { StoredColorFormat.validatedColors(colors.dropLast(1)) }
        assertThatIllegalArgumentException()
            .isThrownBy { StoredColorFormat.validatedColors(colors + 0x123456) }
        assertThatIllegalArgumentException()
            .isThrownBy { StoredColorFormat.validatedColors(colors.updated(1, -1)) }
        assertThatIllegalArgumentException()
            .isThrownBy { StoredColorFormat.validatedColors(colors.updated(1, 0x01000000)) }
    }

    @Test
    fun `stores opaque RGB without alpha`() {
        val stored = StoredColorFormat.colorToStoredValue(Color(0xAA, 0xBB, 0xCC, 0x11))
        val restored = StoredColorFormat.storedColor(stored)

        assertThat(stored).isEqualTo(0xAABBCC)
        assertThat(restored).isEqualTo(Color(0xAABBCC))
        assertThat(restored.alpha).isEqualTo(0xFF)
        assertThatIllegalArgumentException()
            .isThrownBy { StoredColorFormat.storedColor(-1) }
        assertThatIllegalArgumentException()
            .isThrownBy { StoredColorFormat.storedColor(0x01000000) }
    }

    private fun List<Int>.updated(index: Int, value: Int): List<Int> = mapIndexed { currentIndex, currentValue ->
        if (currentIndex == index) value else currentValue
    }
}
