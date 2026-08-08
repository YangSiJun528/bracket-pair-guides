package com.sijunyang.bracketpairguides.analysis.index

import com.sijunyang.bracketpairguides.analysis.api.BracketPair
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
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
    fun `malformed pair cannot hide a valid containing pair`() {
        val valid = pair(open = 0, close = 100)
        val malformed = BracketPair(
            openOffset = 10,
            openTokenLength = 1,
            closeOffset = -1,
            closeTokenLength = 100,
            depth = 1,
            openLine = 0,
            closeLine = 0,
        )

        val index = ActiveBracketPairIndex.build(listOf(valid, malformed))

        assertEquals(0, index.activePairIndex(20))
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

    @Test
    fun `primitive sweep matches a brute force lookup for crossing ranges`() {
        val random = Random(0xBACC37)
        var sample = 0
        while (sample < 100) {
            val pairs = ArrayList<BracketPair>(100)
            var pairIndex = 0
            while (pairIndex < 100) {
                val open = random.nextInt(0, 180)
                val close = random.nextInt(open + 1, 201)
                pairs += BracketPair(open, 1, close, random.nextInt(1, 4), pairIndex, 0, 0)
                pairIndex++
            }
            val index = ActiveBracketPairIndex.build(pairs)

            var offset = 0
            while (offset <= 204) {
                assertEquals(
                    "sample=$sample offset=$offset",
                    bruteForcePairIndex(pairs, offset),
                    index.activePairIndex(offset),
                )
                offset++
            }
            sample++
        }
    }

    private fun bruteForcePairIndex(pairs: List<BracketPair>, offset: Int): Int {
        var winner = ActiveBracketPairIndex.NO_PAIR
        var pairIndex = 0
        while (pairIndex < pairs.size) {
            val pair = pairs[pairIndex]
            val start = pair.openOffset + 1
            val end = pair.closeOffset + pair.closeTokenLength
            if (offset >= start && offset < end && isPreferred(pairIndex, winner, pairs)) {
                winner = pairIndex
            }
            pairIndex++
        }
        return winner
    }

    private fun isPreferred(
        candidateIndex: Int,
        currentIndex: Int,
        pairs: List<BracketPair>,
    ): Boolean {
        if (currentIndex == ActiveBracketPairIndex.NO_PAIR) return true
        val candidate = pairs[candidateIndex]
        val current = pairs[currentIndex]
        if (candidate.openOffset != current.openOffset) {
            return candidate.openOffset > current.openOffset
        }
        val candidateEnd = candidate.closeOffset + candidate.closeTokenLength
        val currentEnd = current.closeOffset + current.closeTokenLength
        return if (candidateEnd != currentEnd) {
            candidateEnd < currentEnd
        } else {
            candidateIndex < currentIndex
        }
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
