package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        assertEquals(0, index.minimumIndent(1, 3))
        assertEquals(4, index.minimumIndent(1, 2))
    }

    @Test
    fun `ignores blank lines in minimum queries`() {
        val index = indexFor("{\n\n      value\n  }")

        assertEquals(2, index.minimumIndent(1, 3))
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

    private fun indexFor(text: String, tabSize: Int = 4): GuidePositionIndex {
        val starts = mutableListOf(0)
        val ends = mutableListOf<Int>()
        text.forEachIndexed { index, char ->
            if (char == '\n') {
                ends += index
                starts += index + 1
            }
        }
        ends += text.length

        return GuidePositionIndex.from(
            text = text,
            lineStarts = starts.toIntArray(),
            lineEnds = ends.toIntArray(),
            tabSize = tabSize,
        )
    }
}
