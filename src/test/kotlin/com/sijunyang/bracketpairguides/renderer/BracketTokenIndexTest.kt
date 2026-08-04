package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            val index = BracketTokenIndex.build(pairs)

            assertEquals("sample=$sample", expected.size, index.size)
            expected.forEachIndexed { tokenIndex, token ->
                val message = "sample=$sample token=$tokenIndex"
                assertEquals("$message offset", token.offset, index.offsetAt(tokenIndex))
                assertEquals("$message length", token.length, index.lengthAt(tokenIndex))
                assertEquals("$message depth", token.depth, index.depthAt(tokenIndex))
            }

            repeat(40) { viewport ->
                val startRelative = random.nextInt(0, RELATIVE_OFFSET_LIMIT)
                val endRelative = random.nextInt(startRelative + 1, RELATIVE_OFFSET_LIMIT + 1)
                val startOffset = baseOffset + startRelative
                val endOffset = baseOffset + endRelative
                val expectedCount = expected.count { token ->
                    token.offset < endOffset &&
                        token.offset.toLong() + token.length > startOffset
                }

                assertEquals(
                    "sample=$sample viewport=$viewport range=[$startOffset,$endOffset)",
                    expectedCount,
                    index.countIn(startOffset, endOffset),
                )

                val firstAtOrAfter = index.firstIndexAtOrAfter(startOffset)
                val expectedFirstAtOrAfter = expected.indexOfFirst { it.offset >= startOffset }
                    .takeIf { it >= 0 }
                    ?: expected.size
                assertEquals(
                    "sample=$sample viewport=$viewport lower bound",
                    expectedFirstAtOrAfter,
                    firstAtOrAfter,
                )

                val firstCandidate = index.firstIndexInRange(startOffset)
                assertTrue(firstCandidate in 0..expected.size)
                for (tokenIndex in 0 until firstCandidate) {
                    val token = expected[tokenIndex]
                    assertTrue(
                        "sample=$sample viewport=$viewport skipped overlapping token=$tokenIndex",
                        token.offset.toLong() + token.length <= startOffset,
                    )
                }
            }
        }
    }

    private fun pair(open: Int, close: Int, depth: Int): BracketPair {
        return BracketPair(open, 1, close, 1, depth, 0, 0)
    }

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

    private companion object {
        const val RELATIVE_OFFSET_LIMIT = 1_000
        const val OPEN_TOKEN = 0
        const val CLOSE_TOKEN = 1
    }
}
