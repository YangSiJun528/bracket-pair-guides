package com.sijunyang.bracketpairguides.settings

import java.awt.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class StoredBracketColorsTest {
    @Test
    fun `normalizes invalid missing and extra persisted colors`() {
        val normalized = StoredBracketColors.normalizeColors(
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

        assertEquals(StoredBracketColors.COLOR_COUNT, normalized.size)
        assertEquals(
            listOf(
                0x010203,
                StoredBracketColors.AUTOMATIC_COLOR,
                StoredBracketColors.AUTOMATIC_COLOR,
                0xA0B0C0,
                0x000000,
                0xFFFFFF,
            ),
            normalized,
        )
        assertEquals(
            StoredBracketColors.automaticColors(),
            StoredBracketColors.normalizeColors(emptyList()),
        )
        assertNotSame(
            StoredBracketColors.automaticColors(),
            StoredBracketColors.automaticColors(),
        )
    }

    @Test
    fun `stores RGB without alpha and rejects invalid values`() {
        val stored = StoredBracketColors.colorToStoredValue(Color(0xAA, 0xBB, 0xCC, 0x11))

        assertEquals(0xAABBCC, stored)
        assertEquals(Color(0xAABBCC), StoredBracketColors.storedColor(stored))
        assertNull(StoredBracketColors.storedColor(null))
        assertNull(StoredBracketColors.storedColor(StoredBracketColors.AUTOMATIC_COLOR))
        assertNull(StoredBracketColors.storedColor(0x01000000))
    }
}
