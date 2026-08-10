package com.sijunyang.bracketpairguides.analysis.guide

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidePositionIndexTest {
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
            GuidePositionIndex.from(
                document = document,
                tabSize = 4,
                progress = EmptyProgressIndicator(),
                indexedLineRange = 10..20,
            ),
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

        GuidePositionIndex.from(
            document = DocumentImpl(text),
            tabSize = 4,
            progress = progress,
            indexedLineRange = 0..0,
        )

        assertTrue(cancellationChecks > 2)
    }

    @Test
    fun `tree shape enforces the power of two memory boundary`() {
        val boundary = 1_048_576

        assertNotNull(GuideTreeShape.forLineCount(1))
        assertNotNull(GuideTreeShape.forLineCount(1_000_000))
        assertNotNull(GuideTreeShape.forLineCount(boundary))
        assertNull(GuideTreeShape.forLineCount(boundary + 1))
    }

    @Test
    fun `storage planning is overflow safe for the maximum line count`() {
        assertNull(GuideTreeShape.forLineCount(Int.MAX_VALUE))
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
            GuidePositionIndex.from(
                document = document,
                tabSize = tabSize,
                progress = EmptyProgressIndicator(),
                indexedLineRange = indexedLineRange ?: 0 until document.lineCount,
            ),
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
