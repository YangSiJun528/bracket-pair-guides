package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GuidePositionIndexTest {
    @Test
    fun `uses the minimum body and closing indentation`() {
        val index = indexFor("if (ready) {\n        nested()\n  leastIndented()\n    }")
        val pair = BracketPair(11, 1, 56, 1, 0, 0, 3)

        val guide = index.guideFor(pair)
        assertEquals(2, guide.guideColumn)
        assertEquals(2, guide.anchorLine)
    }

    @Test
    fun `expands tabs using the editor tab size`() {
        val index = indexFor("{\n\tvalue\n    other\n}", tabSize = 4)

        assertEquals(0, index.guideFor(pair(closeLine = 3)).guideColumn)
        assertEquals(4, index.guideFor(pair(closeLine = 2)).guideColumn)
    }

    @Test
    fun `ignores blank lines in minimum queries`() {
        val index = indexFor("{\n\n      value\n  }")

        assertEquals(2, index.guideFor(pair(closeLine = 3)).guideColumn)
    }

    @Test
    fun `restricted index retains absolute anchor lines`() {
        val index = indexFor(
            "ignored\n{\n    nested\n  }\nignored",
            indexedLineRange = 2..3,
        )

        val guide = index.guideFor(pair(closeLine = 3).copy(openLine = 1))

        assertEquals(2, guide.guideColumn)
        assertEquals(3, guide.anchorLine)

        assertEquals(
            null,
            index.guideForOrNull(pair(closeLine = 2).copy(openLine = 0)),
        )
        assertEquals(
            null,
            index.guideForOrNull(pair(closeLine = 11).copy(openLine = 10)),
        )
    }

    @Test
    fun `restricted index does not read text outside its range`() {
        val text = "outside\nignored\n    nested\n  }\noutside"
        val guardedText = object : CharSequence {
            override val length: Int = text.length

            override fun get(index: Int): Char {
                check(index in 16 until 30) { "Read outside indexed lines: $index" }
                return text[index]
            }

            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                text.subSequence(startIndex, endIndex)
        }

        val index = GuidePositionIndex.from(
            text = guardedText,
            lineStarts = intArrayOf(0, 8, 16, 27, 31),
            lineEnds = intArrayOf(7, 15, 26, 30, 38),
            tabSize = 4,
            indexedLineRange = 2..3,
        )

        assertEquals(2, index.guideFor(pair(closeLine = 3).copy(openLine = 1)).guideColumn)
    }

    @Test
    fun `restricted index rejects a disjoint range`() {
        try {
            GuidePositionIndex.from(
                text = "one\ntwo",
                lineStarts = intArrayOf(0, 4),
                lineEnds = intArrayOf(3, 7),
                tabSize = 4,
                indexedLineRange = 10..20,
            )
            fail("Expected a disjoint indexed range to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `checks cancellation while scanning a very long indentation`() {
        var cancellationChecks = 0
        val text = " ".repeat(20_000)

        GuidePositionIndex.from(
            text = text,
            lineStarts = intArrayOf(0),
            lineEnds = intArrayOf(text.length),
            tabSize = 4,
            checkCanceled = { cancellationChecks++ },
        )

        assertTrue(cancellationChecks > 2)
    }

    @Test
    fun `tree payload calculation exposes the power of two boundary`() {
        val boundary = 1_048_576

        assertEquals(16L, GuidePositionIndex.treePayloadBytes(1))
        assertEquals(32L, GuidePositionIndex.treePayloadBytes(2))
        assertEquals(
            GuidePositionIndex.MAXIMUM_TREE_PAYLOAD_BYTES,
            GuidePositionIndex.treePayloadBytes(1_000_000),
        )
        assertEquals(
            GuidePositionIndex.MAXIMUM_TREE_PAYLOAD_BYTES,
            GuidePositionIndex.treePayloadBytes(boundary),
        )
        assertEquals(
            GuidePositionIndex.MAXIMUM_TREE_PAYLOAD_BYTES * 2,
            GuidePositionIndex.treePayloadBytes(boundary + 1),
        )
        assertTrue(GuidePositionIndex.supportsLineCount(boundary))
        assertFalse(GuidePositionIndex.supportsLineCount(boundary + 1))
    }

    @Test
    fun `storage planning is overflow safe for the maximum line count`() {
        assertEquals(null, GuidePositionIndex.treePayloadBytes(Int.MAX_VALUE))
        assertFalse(GuidePositionIndex.supportsLineCount(Int.MAX_VALUE))
    }

    @Test
    fun `indentation column saturates before the no-indent sentinel`() {
        val index = indexFor("{\n\t\tvalue", tabSize = Int.MAX_VALUE)

        val guide = index.guideFor(pair(closeLine = 1))

        assertEquals(GuideIndentation.MAXIMUM_COLUMN, guide.guideColumn)
        assertEquals(1, guide.anchorLine)
    }

    @Test
    fun `line selection does not overflow after the maximum open line`() {
        val index = indexFor("  first\n    last")
        val malformed = pair(closeLine = Int.MAX_VALUE).copy(openLine = Int.MAX_VALUE)

        val guide = index.guideFor(malformed)

        assertEquals(4, guide.guideColumn)
        assertEquals(1, guide.anchorLine)
    }

    private fun indexFor(
        text: String,
        tabSize: Int = 4,
        indexedLineRange: IntRange? = null,
    ): GuidePositionIndex {
        val starts = mutableListOf(0)
        val ends = mutableListOf<Int>()
        text.forEachIndexed { index, char ->
            if (char == '\n') {
                ends += index
                starts += index + 1
            }
        }
        ends += text.length

        val lineStarts = starts.toIntArray()
        val lineEnds = ends.toIntArray()
        return if (indexedLineRange == null) {
            GuidePositionIndex.from(text, lineStarts, lineEnds, tabSize)
        } else {
            GuidePositionIndex.from(
                text = text,
                lineStarts = lineStarts,
                lineEnds = lineEnds,
                tabSize = tabSize,
                indexedLineRange = indexedLineRange,
            )
        }
    }

    private fun pair(closeLine: Int): BracketPair = BracketPair(
        openOffset = 0,
        openTokenLength = 1,
        closeOffset = 1,
        closeTokenLength = 1,
        depth = 0,
        openLine = 0,
        closeLine = closeLine,
    )
}
