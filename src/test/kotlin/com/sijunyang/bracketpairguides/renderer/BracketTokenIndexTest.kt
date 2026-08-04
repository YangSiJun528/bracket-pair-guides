package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import org.junit.Assert.assertEquals
import org.junit.Test

class BracketTokenIndexTest {
    @Test
    fun `sorts both endpoints and selects only an overlapping viewport`() {
        val pairs = listOf(
            pair(open = 0, close = 100, depth = 0),
            pair(open = 10, close = 20, depth = 1),
            pair(open = 30, close = 40, depth = 1),
        )
        val index = BracketTokenIndex.build(pairs)

        assertEquals(6, index.size)
        assertEquals(2, index.countIn(9, 21))
        val first = index.firstIndexInRange(9)
        assertEquals(10, index.offsetAt(first))
        assertEquals(1, index.depthAt(first))
        assertEquals(1, index.lengthAt(first))
    }

    @Test
    fun `includes a token that begins before the viewport and overlaps it`() {
        val pair = BracketPair(
            openOffset = 2,
            openTokenLength = 8,
            closeOffset = 30,
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
        val index = BracketTokenIndex.build(listOf(pair))

        assertEquals(1, index.countIn(8, 12))
        assertEquals(0, index.firstIndexInRange(8))
    }

    @Test
    fun `ignores both endpoints of a structurally invalid pair`() {
        val valid = pair(open = 0, close = 100, depth = 0)
        val invalid = BracketPair(
            openOffset = 10,
            openTokenLength = 1,
            closeOffset = -1,
            closeTokenLength = 100,
            depth = 1,
            openLine = 0,
            closeLine = 0,
        )

        val index = BracketTokenIndex.build(listOf(valid, invalid))

        assertEquals(2, index.size)
        assertEquals(0, index.countIn(9, 11))
    }

    private fun pair(open: Int, close: Int, depth: Int): BracketPair {
        return BracketPair(open, 1, close, 1, depth, 0, 0)
    }
}
