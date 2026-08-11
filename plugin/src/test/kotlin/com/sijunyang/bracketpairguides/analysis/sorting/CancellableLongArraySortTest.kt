package com.sijunyang.bracketpairguides.analysis.sorting

import com.intellij.openapi.progress.ProcessCanceledException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class CancellableLongArraySortTest {
    @Test
    fun `sorts multiple chunks with signed values and duplicates`() {
        val values = LongArray(LARGE_INPUT_SIZE) { index ->
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

        assertThat(values).containsExactly(*expected)
        assertThat(cancellationChecks).isGreaterThan(2)
    }

    @Test
    fun `can cancel after chunk sorting while merge is in progress`() {
        val values = LongArray(LARGE_INPUT_SIZE) { -it.toLong() }
        var cancellationChecks = 0

        assertThatThrownBy {
            values.sortCancellable {
                cancellationChecks++
                if (cancellationChecks == FIRST_MERGE_CHECK) throw ProcessCanceledException()
            }
        }.isInstanceOf(ProcessCanceledException::class.java)
        assertThat(cancellationChecks).isEqualTo(FIRST_MERGE_CHECK)
    }

    companion object {
        private const val LARGE_INPUT_SIZE = 50_000
        // Initial check + four chunk completions precede merge progress checks.
        private const val FIRST_MERGE_CHECK = 8
    }
}
