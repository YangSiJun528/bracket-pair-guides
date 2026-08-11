package com.sijunyang.bracketpairguides.preferences

import java.awt.Color
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class StoredColorFormatTest {
    @Test
    fun `normalizes invalid missing and extra persisted colors`() {
        val normalized = StoredColorFormat.normalizeColors(
            listOf(
                0x010203,
                -2,
                0x01000000,
                0xA0B0C0,
                0x000000,
                0xFFFFFF,
                0x123456,
            ),
        )

        assertThat(normalized).containsExactly(
            0x010203,
            StoredColorFormat.AUTOMATIC_COLOR,
            StoredColorFormat.AUTOMATIC_COLOR,
            0xA0B0C0,
            0x000000,
            0xFFFFFF,
        )
        assertThat(StoredColorFormat.normalizeColors(emptyList())).isEqualTo(StoredColorFormat.automaticColors())
        assertThat(StoredColorFormat.automaticColors()).isNotSameAs(StoredColorFormat.automaticColors())
    }

    @Test
    fun `stores RGB without alpha and rejects invalid values`() {
        val stored = StoredColorFormat.colorToStoredValue(Color(0xAA, 0xBB, 0xCC, 0x11))

        assertThat(stored).isEqualTo(0xAABBCC)
        assertThat(StoredColorFormat.storedColor(stored)).isEqualTo(Color(0xAABBCC))
        assertThat(StoredColorFormat.storedColor(null)).isNull()
        assertThat(StoredColorFormat.storedColor(StoredColorFormat.AUTOMATIC_COLOR)).isNull()
        assertThat(StoredColorFormat.storedColor(0x01000000)).isNull()
    }
}
