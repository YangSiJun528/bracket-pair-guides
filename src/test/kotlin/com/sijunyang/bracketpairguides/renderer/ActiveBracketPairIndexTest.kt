package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class ActiveBracketPairIndexTest {
    @Test
    fun `uses strict bracket boundaries`() {
        val pair = pair(open = 2, close = 4)
        val index = ActiveBracketPairIndex.build(listOf(pair))

        assertEquals(ActiveBracketPairIndex.NO_PAIR, index.activePairIndex(1))
        assertEquals(ActiveBracketPairIndex.NO_PAIR, index.activePairIndex(2))
        assertEquals(0, index.activePairIndex(3))
        assertEquals(0, index.activePairIndex(4))
        assertEquals(ActiveBracketPairIndex.NO_PAIR, index.activePairIndex(5))
    }

    @Test
    fun `selects the innermost pair and falls back to its parent`() {
        val outer = pair(open = 0, close = 10, depth = 0)
        val inner = pair(open = 3, close = 7, depth = 1)
        val index = ActiveBracketPairIndex.build(listOf(outer, inner))

        assertEquals(0, index.activePairIndex(2))
        assertEquals(1, index.activePairIndex(4))
        assertEquals(1, index.activePairIndex(7))
        assertEquals(0, index.activePairIndex(8))
        assertEquals(ActiveBracketPairIndex.NO_PAIR, index.activePairIndex(11))
    }

    @Test
    fun `later opener wins even for crossing language ranges`() {
        val earlierShorter = pair(open = 0, close = 10, depth = 20)
        val laterLonger = pair(open = 5, close = 100, depth = 0)
        val index = ActiveBracketPairIndex.build(listOf(earlierShorter, laterLonger))

        assertEquals(1, index.activePairIndex(7))
    }

    @Test
    fun `empty pair is active between its tokens`() {
        val pair = pair(open = 0, close = 1)
        val index = ActiveBracketPairIndex.build(listOf(pair))

        assertEquals(ActiveBracketPairIndex.NO_PAIR, index.activePairIndex(0))
        assertEquals(0, index.activePairIndex(1))
        assertEquals(ActiveBracketPairIndex.NO_PAIR, index.activePairIndex(2))
    }

    @Test
    fun `deep index construction and repeated caret lookup stay bounded`() {
        val pairCount = 50_000
        val pairs = List(pairCount) { depth ->
            pair(
                open = depth,
                close = pairCount * 2 - depth,
                depth = depth,
            )
        }

        lateinit var index: ActiveBracketPairIndex
        val elapsed = measureTimeMillis {
            index = ActiveBracketPairIndex.build(pairs)
            repeat(10_000) { query ->
                assertEquals(
                    pairCount - 1,
                    index.activePairIndex(pairCount + query.mod(2)),
                )
            }
        }

        assertTrue("50k-pair index and lookups took ${elapsed}ms", elapsed < 15_000)
    }

    @Test
    fun `index construction honors cancellation`() {
        var checks = 0
        try {
            ActiveBracketPairIndex.build(
                pairs = List(2_000) { index ->
                    pair(index, 5_000 - index, index)
                },
                checkCanceled = {
                    checks++
                    if (checks == 3) throw ProcessCanceledException()
                },
            )
        } catch (_: ProcessCanceledException) {
            assertEquals(3, checks)
            return
        }
        throw AssertionError("Expected index construction to be canceled")
    }

    private fun pair(open: Int, close: Int, depth: Int = 0): BracketPair {
        return BracketPair(
            openOffset = open,
            openTokenLength = 1,
            closeOffset = close,
            closeTokenLength = 1,
            depth = depth,
            openLine = 0,
            closeLine = 0,
        )
    }
}
