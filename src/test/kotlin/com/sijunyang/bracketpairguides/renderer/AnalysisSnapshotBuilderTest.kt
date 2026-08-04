package com.sijunyang.bracketpairguides.renderer

import com.intellij.openapi.progress.ProcessCanceledException
import com.sijunyang.bracketpairguides.analyzer.BracketPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalysisSnapshotBuilderTest {
    @Test
    fun `detects whether guide position work is needed`() {
        assertFalse(
            AnalysisSnapshotBuilder.containsMultilinePair(
                listOf(pair(openLine = 3, closeLine = 3)),
            ),
        )
        assertTrue(
            AnalysisSnapshotBuilder.containsMultilinePair(
                listOf(
                    pair(openLine = 3, closeLine = 3),
                    pair(openLine = 3, closeLine = 4),
                ),
            ),
        )
    }

    @Test
    fun `multiline probe honors cancellation on a large single-line result`() {
        val pairs = List(2_000) { pair(openLine = it, closeLine = it) }
        var cancellationChecks = 0

        try {
            AnalysisSnapshotBuilder.containsMultilinePair(pairs) {
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
