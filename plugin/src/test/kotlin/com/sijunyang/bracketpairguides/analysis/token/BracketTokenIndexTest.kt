package com.sijunyang.bracketpairguides.analysis.token

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.pairing.toPairTable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import kotlin.random.Random

class BracketTokenIndexTest {
    @Test
    fun `sorts both endpoints and selects only an overlapping viewport`() {
        val pairs = listOf(
            pair(open = 0, close = 100, depth = 0),
            pair(open = 10, close = 20, depth = 1),
            pair(open = 30, close = 40, depth = 1),
        )
        val index = BracketTokenIndex.build(pairs.toPairTable(), NO_CANCELLATION)

        assertThat(index.tokenCount).isEqualTo(6)
        assertThat(index.values(BracketTokenIndex::offsetAt))
            .containsExactly(0, 10, 20, 30, 40, 100)
        val first = index.firstIndexInRange(9)
        assertThat(index.offsetAt(first)).isEqualTo(10)
        assertThat(index.depthAt(first)).isEqualTo(1)
        assertThat(index.lengthAt(first)).isEqualTo(1)
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
        val index = BracketTokenIndex.build(
            listOf(pair).toPairTable(),
            NO_CANCELLATION,
        )

        assertThat(index.firstIndexInRange(8)).isZero()
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

        val index = BracketTokenIndex.build(
            listOf(valid, invalid).toPairTable(),
            NO_CANCELLATION,
        )

        assertThat(index.tokenCount).isEqualTo(2)
        assertThat(index.firstIndexInRange(9)).isEqualTo(index.firstIndexAtOrAfter(11))
    }

    @Test
    fun `detached index preserves every token metadata field`() {
        val pairs = listOf(
            BracketPair(0, 2, 4, 3, Int.MIN_VALUE, 0, 0),
            BracketPair(4, 1, 9, 2, Int.MAX_VALUE, 0, 0),
            BracketPair(6, 0, 8, 1, 17, 0, 0),
            BracketPair(4, 2, 9, 1, -1, 0, 0),
        )

        val pairTable = pairs.toPairTable()
        val index = BracketTokenIndex.buildDetached(pairTable, NO_CANCELLATION)

        assertThat(index.tokenCount).isEqualTo(6)
        assertThat(index.values(BracketTokenIndex::offsetAt)).containsExactly(0, 4, 4, 4, 9, 9)
        assertThat(index.values(BracketTokenIndex::lengthAt)).containsExactly(2, 3, 1, 2, 2, 1)
        assertThat(
            index.values(BracketTokenIndex::depthAt),
        ).containsExactly(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, -1, Int.MAX_VALUE, -1)
    }

    @Test
    fun `content equality compares the complete observable token sequence`() {
        val first = BracketTokenIndex.buildDetached(
            listOf(
                pair(open = 0, close = 10, depth = 0),
                pair(open = 2, close = 8, depth = 0),
            ).toPairTable(),
            NO_CANCELLATION,
        )
        val sameTokensWithDifferentPairs = BracketTokenIndex.buildDetached(
            listOf(
                pair(open = 0, close = 8, depth = 0),
                pair(open = 2, close = 10, depth = 0),
            ).toPairTable(),
            NO_CANCELLATION,
        )
        val differentDepth = BracketTokenIndex.buildDetached(
            listOf(
                pair(open = 0, close = 10, depth = 1),
                pair(open = 2, close = 8, depth = 0),
            ).toPairTable(),
            NO_CANCELLATION,
        )

        assertThat(first.hasSameContent(sameTokensWithDifferentPairs, NO_CANCELLATION)).isTrue()
        assertThat(first.hasSameContent(differentDepth, NO_CANCELLATION)).isFalse()
    }

    @Test
    fun `content equality checks cancellation during a large exact comparison`() {
        val pairs = List(300) { index ->
            pair(open = index * 2, close = index * 2 + 1, depth = index)
        }.toPairTable()
        val first = BracketTokenIndex.buildDetached(pairs, NO_CANCELLATION)
        val second = BracketTokenIndex.buildDetached(pairs, NO_CANCELLATION)
        var checks = 0

        assertThatThrownBy {
            first.hasSameContent(second) {
                if (++checks == 3) throw TestCancellation()
            }
        }.isInstanceOf(TestCancellation::class.java)
    }

    @Test
    fun `compact index matches an independent token model across random ranges`() {
        val random = Random(0x70C3_11DE)

        repeat(40) { sample ->
            val baseOffset = if (sample and 1 == 0) {
                0
            } else {
                Int.MAX_VALUE - RELATIVE_OFFSET_LIMIT
            }
            val pairs = MutableList(80) { pairIndex ->
                val openRelative = random.nextInt(0, 800)
                val openLength = random.nextInt(1, 9)
                val closeRelative = random.nextInt(openRelative + openLength, 950)
                val closeLength = random.nextInt(
                    from = 1,
                    until = minOf(9, RELATIVE_OFFSET_LIMIT - closeRelative + 1),
                )
                BracketPair(
                    openOffset = baseOffset + openRelative,
                    openTokenLength = openLength,
                    closeOffset = baseOffset + closeRelative,
                    closeTokenLength = closeLength,
                    depth = pairIndex,
                    openLine = 0,
                    closeLine = 0,
                )
            }
            if (baseOffset != 0) {
                pairs += BracketPair(
                    openOffset = Int.MAX_VALUE - 4,
                    openTokenLength = 1,
                    closeOffset = Int.MAX_VALUE - 3,
                    closeTokenLength = 3,
                    depth = pairs.size,
                    openLine = 0,
                    closeLine = 0,
                )
            }
            pairs += malformedPairs(baseOffset, pairs.size)

            val expected = pairs.flatMapIndexed { pairIndex, pair ->
                if (!isWellFormed(pair)) {
                    emptyList()
                } else {
                    listOf(
                        ExpectedToken(
                            offset = pair.openOffset,
                            length = pair.openTokenLength,
                            depth = pair.depth,
                            pairIndex = pairIndex,
                            tokenKind = OPEN_TOKEN,
                        ),
                        ExpectedToken(
                            offset = pair.closeOffset,
                            length = pair.closeTokenLength,
                            depth = pair.depth,
                            pairIndex = pairIndex,
                            tokenKind = CLOSE_TOKEN,
                        ),
                    )
                }
            }.sortedWith(
                compareBy(
                    ExpectedToken::offset,
                    ExpectedToken::pairIndex,
                    ExpectedToken::tokenKind,
                ),
            )
            val index = BracketTokenIndex.build(
                pairs.toPairTable(),
                NO_CANCELLATION,
            )

            assertThat(index.tokenCount)
                .describedAs("sample=%s", sample)
                .isEqualTo(expected.size)
            expected.forEachIndexed { tokenIndex, token ->
                val message = "sample=$sample token=$tokenIndex"
                assertThat(index.offsetAt(tokenIndex)).describedAs("%s offset", message)
                    .isEqualTo(token.offset)
                assertThat(index.lengthAt(tokenIndex)).describedAs("%s length", message)
                    .isEqualTo(token.length)
                assertThat(index.depthAt(tokenIndex)).describedAs("%s depth", message)
                    .isEqualTo(token.depth)
            }

            repeat(40) { viewport ->
                val startRelative = random.nextInt(0, RELATIVE_OFFSET_LIMIT)
                val startOffset = baseOffset + startRelative
                val firstAtOrAfter = index.firstIndexAtOrAfter(startOffset)
                val expectedFirstAtOrAfter = expected.indexOfFirst { it.offset >= startOffset }
                    .takeIf { it >= 0 }
                    ?: expected.size
                assertThat(firstAtOrAfter)
                    .describedAs("sample=%s viewport=%s lower bound", sample, viewport)
                    .isEqualTo(expectedFirstAtOrAfter)

                val firstCandidate = index.firstIndexInRange(startOffset)
                assertThat(firstCandidate).isBetween(0, expected.size)
                for (tokenIndex in 0 until firstCandidate) {
                    val token = expected[tokenIndex]
                    assertThat(
                        token.offset.toLong() + token.length <= startOffset,
                    ).describedAs(
                        "sample=%s viewport=%s skipped overlapping token=%s",
                        sample,
                        viewport,
                        tokenIndex,
                    ).isTrue()
                }
            }
        }
    }

    private fun pair(open: Int, close: Int, depth: Int): BracketPair {
        return BracketPair(open, 1, close, 1, depth, 0, 0)
    }

    private fun BracketTokenIndex.values(read: BracketTokenIndex.(Int) -> Int): List<Int> =
        List(tokenCount) { index -> read(index) }

    private val BracketTokenIndex.tokenCount: Int
        get() = firstIndexAtOrAfter(Int.MAX_VALUE)

    private fun malformedPairs(baseOffset: Int, firstDepth: Int): List<BracketPair> = listOf(
        BracketPair(-1, 1, baseOffset + 20, 1, firstDepth, 0, 0),
        BracketPair(baseOffset + 10, 0, baseOffset + 20, 1, firstDepth + 1, 0, 0),
        BracketPair(baseOffset + 10, 20, baseOffset + 15, 1, firstDepth + 2, 0, 0),
        BracketPair(baseOffset + 10, 1, -1, 1, firstDepth + 3, 0, 0),
        BracketPair(
            baseOffset + 10,
            1,
            Int.MAX_VALUE - 1,
            3,
            firstDepth + 4,
            0,
            0,
        ),
    )

    private fun isWellFormed(pair: BracketPair): Boolean {
        if (pair.openOffset < 0 || pair.closeOffset < 0 ||
            pair.openTokenLength <= 0 || pair.closeTokenLength <= 0
        ) {
            return false
        }
        return pair.openOffset.toLong() + pair.openTokenLength <= pair.closeOffset &&
            pair.closeOffset.toLong() + pair.closeTokenLength <= Int.MAX_VALUE
    }

    private data class ExpectedToken(
        val offset: Int,
        val length: Int,
        val depth: Int,
        val pairIndex: Int,
        val tokenKind: Int,
    )

    private class TestCancellation : RuntimeException()

    private companion object {
        const val RELATIVE_OFFSET_LIMIT = 1_000
        const val OPEN_TOKEN = 0
        const val CLOSE_TOKEN = 1
        val NO_CANCELLATION: () -> Unit = {}
    }
}
