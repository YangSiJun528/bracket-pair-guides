package com.sijunyang.bracketpairguides.analysis.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PairCollectionTest {
    @Test
    fun `the first pair beyond capacity aborts without exposing the accepted prefix`() {
        val pairs = PairCollection(PairCapacity(2))
        pairs.acceptPair(0)
        pairs.acceptPair(2)

        val signal = assertThrows(PairCapacityReached::class.java) {
            pairs.acceptPair(4)
        }

        assertSame(PairCapacityReached, signal)
        assertNull(pairs.authoritativePairs())
    }

    @Test
    fun `a table at the exact capacity remains authoritative`() {
        val pairs = PairCollection(PairCapacity(2))
        pairs.acceptPair(0)
        pairs.acceptPair(2)

        assertEquals(2, pairs.authoritativePairs()?.size())
    }

    private fun PairCollection.acceptPair(openOffset: Int) {
        accept(
            openOffset = openOffset,
            openTokenLength = 1,
            closeOffset = openOffset + 1,
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
    }
}
