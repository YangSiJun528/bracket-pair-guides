package com.sijunyang.bracketpairguides.analyzer

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CancellableBracketPairSortTest {
    @Test
    fun `sorts multiple runs by opening then closing offset`() {
        val pairs = MutableList(CANCELLABLE_PAIR_SORT_CHUNK_SIZE * 2 + 257) { index ->
            pair(
                open = index % 257,
                close = (index / 257) % 127,
                depth = index,
            )
        }
        val expected = pairs.sortedWith(
            compareBy(BracketPair::openOffset, BracketPair::closeOffset),
        )
        var cancellationChecks = 0

        pairs.sortBracketPairsCancellable { cancellationChecks++ }

        assertEquals(expected, pairs)
        assertEquals(
            "equal keys must retain their input order",
            expected.map(BracketPair::depth),
            pairs.map(BracketPair::depth),
        )
        assertTrue(cancellationChecks > 8)
    }

    @Test
    fun `can cancel after chunk sorting while merge is in progress`() {
        val pairs = MutableList(CANCELLABLE_PAIR_SORT_CHUNK_SIZE * 2) { index ->
            pair(open = PAIR_COUNT - index, close = index, depth = index)
        }
        var cancellationChecks = 0

        try {
            pairs.sortBracketPairsCancellable {
                cancellationChecks++
                if (cancellationChecks == FIRST_MERGE_CANCELLATION_CHECK) {
                    throw ProcessCanceledException()
                }
            }
            fail("Expected bracket-pair sorting to be canceled")
        } catch (_: ProcessCanceledException) {
            assertEquals(FIRST_MERGE_CANCELLATION_CHECK, cancellationChecks)
        }
    }

    private fun pair(open: Int, close: Int, depth: Int): BracketPair = BracketPair(
        openOffset = open,
        openTokenLength = 1,
        closeOffset = close,
        closeTokenLength = 1,
        depth = depth,
        openLine = 0,
        closeLine = 0,
    )

    private companion object {
        const val PAIR_COUNT = CANCELLABLE_PAIR_SORT_CHUNK_SIZE * 2

        // Initial check + one after each of the two bounded chunk sorts + the
        // first 4,096-element merge checkpoint.
        const val FIRST_MERGE_CANCELLATION_CHECK = 4
    }
}
