package com.sijunyang.bracketpairguides.preferences

import java.awt.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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

        assertEquals(StoredColorFormat.COLOR_COUNT, normalized.size)
        assertEquals(
            listOf(
                0x010203,
                StoredColorFormat.AUTOMATIC_COLOR,
                StoredColorFormat.AUTOMATIC_COLOR,
                0xA0B0C0,
                0x000000,
                0xFFFFFF,
            ),
            normalized,
        )
        assertEquals(
            StoredColorFormat.automaticColors(),
            StoredColorFormat.normalizeColors(emptyList()),
        )
        assertNotSame(
            StoredColorFormat.automaticColors(),
            StoredColorFormat.automaticColors(),
        )
    }

    @Test
    fun `stores RGB without alpha and rejects invalid values`() {
        val stored = StoredColorFormat.colorToStoredValue(Color(0xAA, 0xBB, 0xCC, 0x11))

        assertEquals(0xAABBCC, stored)
        assertEquals(Color(0xAABBCC), StoredColorFormat.storedColor(stored))
        assertNull(StoredColorFormat.storedColor(null))
        assertNull(StoredColorFormat.storedColor(StoredColorFormat.AUTOMATIC_COLOR))
        assertNull(StoredColorFormat.storedColor(0x01000000))
    }
}
