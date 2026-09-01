package com.sijunyang.bracketpairguides.analysis.active

import com.intellij.openapi.progress.ProcessCanceledException
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.pairing.toPairTable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureTimeMillis

class ActiveBracketPairIndexTest {
    @Test
    fun `uses strict bracket boundaries`() {
        val pair = pair(open = 2, close = 4)
        val index = indexFor(listOf(pair))

        assertThat(index.activePairIndex(1)).isEqualTo(ActiveBracketPairIndex.NO_PAIR)
        assertThat(index.activePairIndex(2)).isEqualTo(ActiveBracketPairIndex.NO_PAIR)
        assertThat(index.activePairIndex(3)).isZero()
        assertThat(index.activePairIndex(4)).isZero()
        assertThat(index.activePairIndex(5)).isEqualTo(ActiveBracketPairIndex.NO_PAIR)
    }

    @Test
    fun `selects the innermost pair and falls back to its parent`() {
        val outer = pair(open = 0, close = 10, depth = 0)
        val inner = pair(open = 3, close = 7, depth = 1)
        val index = indexFor(listOf(outer, inner))

        assertThat(index.activePairIndex(2)).isZero()
        assertThat(index.activePairIndex(4)).isEqualTo(1)
        assertThat(index.activePairIndex(7)).isEqualTo(1)
        assertThat(index.activePairIndex(8)).isZero()
        assertThat(index.activePairIndex(11)).isEqualTo(ActiveBracketPairIndex.NO_PAIR)
    }

    @Test
    fun `later opener wins even for crossing language ranges`() {
        val earlierShorter = pair(open = 0, close = 10, depth = 20)
        val laterLonger = pair(open = 5, close = 100, depth = 0)
        val index = indexFor(listOf(earlierShorter, laterLonger))

        assertThat(index.activePairIndex(7)).isEqualTo(1)
    }

    @Test
    fun `malformed pair cannot hide a valid containing pair`() {
        val valid = pair(open = 0, close = 100)
        val malformed =
            BracketPair(
                openOffset = 10,
                openTokenLength = 1,
                closeOffset = -1,
                closeTokenLength = 100,
                depth = 1,
                openLine = 0,
                closeLine = 0,
            )

        val index = indexFor(listOf(valid, malformed))

        assertThat(index.activePairIndex(20)).isZero()
    }

    @Test
    fun `empty pair is active between its tokens`() {
        val pair = pair(open = 0, close = 1)
        val index = indexFor(listOf(pair))

        assertThat(index.activePairIndex(0)).isEqualTo(ActiveBracketPairIndex.NO_PAIR)
        assertThat(index.activePairIndex(1)).isZero()
        assertThat(index.activePairIndex(2)).isEqualTo(ActiveBracketPairIndex.NO_PAIR)
    }

    @Test
    fun `deep index construction and repeated caret lookup stay bounded`() {
        val pairCount = 50_000
        val pairs =
            List(pairCount) { depth ->
                pair(
                    open = depth,
                    close = pairCount * 2 - depth,
                    depth = depth,
                )
            }

        lateinit var index: ActiveBracketPairIndex
        val elapsed =
            measureTimeMillis {
                index = indexFor(pairs)
                repeat(10_000) { query ->
                    assertThat(index.activePairIndex(pairCount + query.mod(2)))
                        .isEqualTo(pairCount - 1)
                }
            }

        assertThat(elapsed)
            .describedAs("50k-pair index and lookups")
            .isLessThan(15_000)
    }

    @Test
    fun `index construction honors cancellation`() {
        var checks = 0
        assertThatThrownBy {
            indexFor(
                pairs =
                List(2_000) { index ->
                    pair(index, 5_000 - index, index)
                },
                checkCanceled = {
                    checks++
                    if (checks == 3) throw ProcessCanceledException()
                },
            )
        }.isInstanceOf(ProcessCanceledException::class.java)
        assertThat(checks).isEqualTo(3)
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
            val index = indexFor(pairs)

            var offset = 0
            while (offset <= 204) {
                assertThat(index.activePairIndex(offset))
                    .describedAs("sample=%s offset=%s", sample, offset)
                    .isEqualTo(bruteForcePairIndex(pairs, offset))
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

    private fun indexFor(pairs: Iterable<BracketPair>, checkCanceled: () -> Unit = {}): ActiveBracketPairIndex =
        ActiveBracketPairIndex.build(
            pairs = pairs.toPairTable(),
            checkCanceled = checkCanceled,
        )

    private fun isPreferred(candidateIndex: Int, currentIndex: Int, pairs: List<BracketPair>): Boolean {
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

    private fun pair(open: Int, close: Int, depth: Int = 0): BracketPair = BracketPair(
        openOffset = open,
        openTokenLength = 1,
        closeOffset = close,
        closeTokenLength = 1,
        depth = depth,
        openLine = 0,
        closeLine = 0,
    )
}
