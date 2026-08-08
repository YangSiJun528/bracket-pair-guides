package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.progress.ProcessCanceledException
import com.sijunyang.bracketpairguides.analysis.BracketPair
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AnalysisSnapshotBuilderTest {
    @Test
    fun `finds the minimum multiline guide query envelope`() {
        assertEquals(
            null,
            AnalysisSnapshotBuilder.multilineGuideRange(
                listOf(pair(openLine = 3, closeLine = 3)),
                documentLength = 100,
                documentLineCount = 20,
            ),
        )
        assertEquals(
            4..12,
            AnalysisSnapshotBuilder.multilineGuideRange(
                listOf(
                    pair(openLine = 3, closeLine = 4),
                    pair(openLine = 9, closeLine = 12),
                ),
                documentLength = 100,
                documentLineCount = 20,
            ),
        )
        assertEquals(
            4..4,
            AnalysisSnapshotBuilder.multilineGuideRange(
                listOf(
                    pair(openLine = 3, closeLine = 4),
                    pair(openLine = 0, closeLine = 1).copy(
                        openOffset = 0,
                        closeOffset = 10,
                        openLine = Int.MIN_VALUE,
                        closeLine = Int.MAX_VALUE,
                    ),
                ),
                documentLength = 100,
                documentLineCount = 20,
            ),
        )
    }

    @Test
    fun `multiline probe honors cancellation on a large single-line result`() {
        val pairs = List(2_000) { pair(openLine = it, closeLine = it) }
        var cancellationChecks = 0

        try {
            AnalysisSnapshotBuilder.multilineGuideRange(
                pairs,
                documentLength = 3_000,
                documentLineCount = 2_001,
            ) {
                cancellationChecks++
                if (cancellationChecks == 3) throw ProcessCanceledException()
            }
            fail("Expected multiline probing to be canceled")
        } catch (_: ProcessCanceledException) {
            assertEquals(3, cancellationChecks)
        }
    }

    private fun pair(openLine: Int, closeLine: Int): BracketPair = BracketPair(
        openOffset = openLine,
        openTokenLength = 1,
        closeOffset = closeLine + 1,
        closeTokenLength = 1,
        depth = 0,
        openLine = openLine,
        closeLine = closeLine,
    )
}
