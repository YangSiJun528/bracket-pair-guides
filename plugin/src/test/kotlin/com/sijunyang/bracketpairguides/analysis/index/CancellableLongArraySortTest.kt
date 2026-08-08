package com.sijunyang.bracketpairguides.analysis.index

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableLongArraySortTest {
    @Test
    fun `sorts multiple chunks with signed values and duplicates`() {
        val values = LongArray(CANCELLABLE_LONG_SORT_CHUNK_SIZE * 3 + 73) { index ->
            when (index.mod(11)) {
                0 -> Long.MIN_VALUE
                1 -> Long.MAX_VALUE
                2, 3 -> -7L
                4, 5 -> 7L
                else -> ((index.toLong() * 7_919L).mod(100_003L)) - 50_001L
            }
        }.also { it.reverse() }
        val expected = values.sortedArray()
        var cancellationChecks = 0

        values.sortCancellable { cancellationChecks++ }

        assertArrayEquals(expected, values)
        assertTrue(cancellationChecks > 2)
    }

    @Test
    fun `can cancel after chunk sorting while merge is in progress`() {
        val values = LongArray(CANCELLABLE_LONG_SORT_CHUNK_SIZE * 2) { -it.toLong() }
        var cancellationChecks = 0

        try {
            values.sortCancellable {
                cancellationChecks++
                if (cancellationChecks == FIRST_MERGE_CHECK) throw ProcessCanceledException()
            }
        } catch (_: ProcessCanceledException) {
            assertEquals(FIRST_MERGE_CHECK, cancellationChecks)
            return
        }
        throw AssertionError("Expected sorting to be canceled during the merge")
    }

    companion object {
        // Initial check + one check after each of the two chunk sorts precede the merge.
        private const val FIRST_MERGE_CHECK = 4
    }
}
