package com.sijunyang.bracketpairguides.analysis.pairing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.Test

class PairCollectionTest {
    @Test
    fun `the first pair beyond capacity aborts without exposing the accepted prefix`() {
        val pairs = PairCollection(PairCapacity(2))
        pairs.acceptPair(0)
        pairs.acceptPair(2)

        val signal = catchThrowable {
            pairs.acceptPair(4)
        }

        assertThat(signal).isInstanceOf(PairCapacityReached::class.java)
        assertThat(pairs.authoritativePairs()).isNull()
    }

    @Test
    fun `a table at the exact capacity remains authoritative`() {
        val pairs = PairCollection(PairCapacity(2))
        pairs.acceptPair(0)
        pairs.acceptPair(2)

        assertThat(pairs.authoritativePairs()?.size()).isEqualTo(2)
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
