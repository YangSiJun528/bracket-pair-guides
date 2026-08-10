package com.sijunyang.bracketpairguides.analysis.guide

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.pairing.toPairTable
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GuideLineEnvelopeTest {
    @Test
    fun `finds the minimum multiline guide query envelope`() {
        assertEquals(
            null,
            GuideLineEnvelope.from(
                listOf(pair(openLine = 3, closeLine = 3)).toPairTable(),
                documentLength = 100,
                documentLineCount = 20,
                checkCanceled = {},
            ),
        )
        assertEquals(
            4..12,
            GuideLineEnvelope.from(
                listOf(
                    pair(openLine = 3, closeLine = 4),
                    pair(openLine = 9, closeLine = 12),
                ).toPairTable(),
                documentLength = 100,
                documentLineCount = 20,
                checkCanceled = {},
            )?.lines,
        )
        assertEquals(
            4..4,
            GuideLineEnvelope.from(
                listOf(
                    pair(openLine = 3, closeLine = 4),
                    pair(openLine = 0, closeLine = 1).copy(
                        openOffset = 0,
                        closeOffset = 10,
                        openLine = Int.MIN_VALUE,
                        closeLine = Int.MAX_VALUE,
                    ),
                ).toPairTable(),
                documentLength = 100,
                documentLineCount = 20,
                checkCanceled = {},
            )?.lines,
        )
    }

    @Test
    fun `multiline probe honors cancellation on a large single-line result`() {
        val pairs = List(2_000) { pair(openLine = it, closeLine = it) }
        var cancellationChecks = 0

        try {
            GuideLineEnvelope.from(
                pairs.toPairTable(),
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
