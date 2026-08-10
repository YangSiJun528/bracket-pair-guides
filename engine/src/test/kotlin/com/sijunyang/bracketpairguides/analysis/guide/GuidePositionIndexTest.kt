package com.sijunyang.bracketpairguides.analysis.guide

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.sijunyang.bracketpairguides.analysis.intellij.DocumentGuidePositions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidePositionIndexTest {
    @Test
    fun `builds from primitive line indentation`() {
        val indentation = intArrayOf(8, 2, 4)
        var cancellationChecks = 0
        val index = checkNotNull(
            GuidePositionIndex.from(
                baseLine = 4,
                lineCount = indentation.size,
                checkCanceled = { cancellationChecks++ },
                indentationAt = indentation::get,
            ),
        )

        val guide = index.guide(pair(closeLine = 6).copy(openLine = 3))

        assertEquals(2, guide.guideColumn)
        assertEquals(5, guide.anchorLine)
        assertTrue(cancellationChecks > 0)
    }

    @Test
    fun `uses the minimum body and closing indentation`() {
        val index = indexFor("if (ready) {\n        nested()\n  leastIndented()\n    }")
        val pair = BracketPair(11, 1, 56, 1, 0, 0, 3)

        val guide = index.guide(pair)
        assertEquals(2, guide.guideColumn)
        assertEquals(2, guide.anchorLine)
    }

    @Test
    fun `expands tabs using the editor tab size`() {
        val index = indexFor("{\n\tvalue\n    other\n}", tabSize = 4)

        assertEquals(0, index.guide(pair(closeLine = 3)).guideColumn)
        assertEquals(4, index.guide(pair(closeLine = 2)).guideColumn)
    }

    @Test
    fun `ignores blank lines in minimum queries`() {
        val index = indexFor("{\n\n      value\n  }")

        assertEquals(2, index.guide(pair(closeLine = 3)).guideColumn)
    }

    @Test
    fun `blocked query returns the earliest exact minimum across edges and tree`() {
        val lines = MutableList(701) { "        value" }
        lines[0] = "{"
        lines[100] = "  left edge"
        lines[300] = " middle block"
        lines[600] = " right edge"
        lines[700] = "        }"
        val index = indexFor(lines.joinToString("\n"))

        val guide = index.guide(pair(closeLine = 700))

        assertEquals(1, guide.guideColumn)
        assertEquals(300, guide.anchorLine)
    }

    @Test
    fun `blocked queries match linear minima around every block edge`() {
        val indentations = IntArray(1_025) { line -> (line * 17) % 13 }
        val index = indexFor(
            indentations.joinToString("\n") { indentation ->
                " ".repeat(indentation) + "value"
            },
        )
        val edges = listOf(1, 2, 254, 255, 256, 257, 510, 511, 512, 513, 767, 768, 1_024)

        for (firstLine in edges) {
            for (lastLine in edges) {
                if (lastLine < firstLine) continue
                val expectedColumn = (firstLine..lastLine)
                    .minOf(indentations::get)
                val expectedLine = (firstLine..lastLine)
                    .first { line -> indentations[line] == expectedColumn }

                val guide = index.guide(
                    pair(closeLine = lastLine).copy(openLine = firstLine - 1),
                )

                assertEquals(expectedColumn, guide.guideColumn)
                assertEquals(expectedLine, guide.anchorLine)
            }
        }
    }

    @Test
    fun `restricted index retains absolute anchor lines`() {
        val index = indexFor(
            "ignored\n{\n    nested\n  }\nignored",
            indexedLineRange = 2..3,
        )

        val guide = index.guide(pair(closeLine = 3).copy(openLine = 1))

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
    fun `restricted index omits a disjoint range`() {
        val document = DocumentImpl("one\ntwo")

        assertNull(
            DocumentGuidePositions(
                document = document,
                tabSize = 4,
                checkCanceled = {},
            ).index(10..20),
        )
    }

    @Test
    fun `checks cancellation while scanning a very long indentation`() {
        var cancellationChecks = 0
        val text = " ".repeat(20_000)
        val progressState = ProgressIndicatorBase()
        val progress = object : ProgressIndicator by progressState {
            override fun checkCanceled() {
                cancellationChecks++
                progressState.checkCanceled()
            }
        }

        DocumentGuidePositions(
            document = DocumentImpl(text),
            tabSize = 4,
            checkCanceled = progress::checkCanceled,
        ).index(0..0)

        assertTrue(cancellationChecks > 2)
    }

    @Test
    fun `blocked shape enforces the combined array memory boundary`() {
        val exactBoundary = 1_032_192

        assertNotNull(GuideIndexShape.forLineCount(1))
        assertNotNull(GuideIndexShape.forLineCount(1_000_000))

        val boundaryShape = checkNotNull(GuideIndexShape.forLineCount(exactBoundary))
        assertEquals(exactBoundary, boundaryShape.indentationEntryCount)
        assertEquals(4_096, boundaryShape.blockLeafCount)
        assertEquals(8_192, boundaryShape.blockTreeEntryCount)
        assertNull(GuideIndexShape.forLineCount(exactBoundary + 1))
    }

    @Test
    fun `storage planning is overflow safe for the maximum line count`() {
        assertNull(GuideIndexShape.forLineCount(Int.MAX_VALUE))
        assertNull(
            GuidePositionIndex.from(
                baseLine = Int.MAX_VALUE,
                lineCount = 2,
                checkCanceled = {},
                indentationAt = { 0 },
            ),
        )
    }

    @Test
    fun `indentation column saturates before the no-indent sentinel`() {
        val index = indexFor("{\n\t\tvalue", tabSize = Int.MAX_VALUE)

        val guide = index.guide(pair(closeLine = 1))

        assertEquals(Int.MAX_VALUE - 1, guide.guideColumn)
        assertEquals(1, guide.anchorLine)
    }

    @Test
    fun `pair outside the indexed envelope has no guide`() {
        val index = indexFor("  first\n    last")
        val malformed = pair(closeLine = Int.MAX_VALUE).copy(openLine = Int.MAX_VALUE)

        assertNull(index.guideForOrNull(malformed))
    }

    private fun indexFor(
        text: String,
        tabSize: Int = 4,
        indexedLineRange: IntRange? = null,
    ): GuidePositionIndex {
        val document = DocumentImpl(text)
        return checkNotNull(
            DocumentGuidePositions(
                document = document,
                tabSize = tabSize,
                checkCanceled = {},
            ).index(indexedLineRange ?: 0 until document.lineCount),
        )
    }

    private fun GuidePositionIndex.guide(pair: BracketPair) =
        checkNotNull(guideForOrNull(pair))

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
