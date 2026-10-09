package com.sijunyang.bracketpairguides.analysis.guide

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.util.concurrent.CancellationException

class LineIndentationTest {
    @Test
    fun chunkBoundariesPreserveSpacesAndTabStops() {
        val text = " \t \tvalue and ignored body"
        for (sizes in listOf(listOf(1), listOf(2, 1, 3, 5))) {
            assertThat(scan(text, sizes, tabSize = 4)).isEqualTo(8)
        }
        assertThat(scan("\t\tvalue", listOf(1), tabSize = 0)).isEqualTo(2)
        assertThat(scan("value", listOf(1), tabSize = 4)).isZero()
    }

    @Test
    fun whitespaceOnlyPrefixesAreIncompleteUntilTheActualLineEnd() {
        val scanner = LineIndentation(4)
        assertThat(scanner.append(" \t", endOfLine = false)).isFalse()
        assertThat(scanner.isComplete).isFalse()
        assertThatThrownBy { scanner.column }.isInstanceOf(IllegalStateException::class.java)
        assertThat(scanner.append("", endOfLine = true)).isTrue()
        assertThat(scanner.column).isEqualTo(VisualColumn.BLANK_LINE_COLUMN)

        assertThat(scan("", listOf(1), 4)).isEqualTo(VisualColumn.BLANK_LINE_COLUMN)
        assertThat(scan(" \t  \t", listOf(1), 4)).isEqualTo(VisualColumn.BLANK_LINE_COLUMN)
    }

    @Test
    fun longWhitespaceCanSpanPrefixesAndContinuationChunks() {
        val indentation = " ".repeat(100_000)
        assertThat(scan(indentation + "value", listOf(128, 4096, 7), 4)).isEqualTo(100_000)
        assertThat(scan(indentation, listOf(128, 4096, 7), 4)).isEqualTo(VisualColumn.BLANK_LINE_COLUMN)
    }

    @Test
    fun completionSkipsTheRemainingBodyAndRejectsFurtherChunks() {
        var probes = 0
        val scanner = LineIndentation(4) { probes++ }
        assertThat(scanner.append("  value" + " ".repeat(20_000), endOfLine = false)).isTrue()
        assertThat(scanner.column).isEqualTo(2)
        assertThat(probes).isEqualTo(1)
        assertThatThrownBy { scanner.append("", endOfLine = true) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun visualColumnsSaturateBelowTheBlankLineSentinel() {
        assertThat(scan("\t\t value", listOf(1), Int.MAX_VALUE)).isEqualTo(Int.MAX_VALUE - 1)
        assertThat(scan("\t\t ", listOf(1), Int.MAX_VALUE)).isEqualTo(VisualColumn.BLANK_LINE_COLUMN)
    }

    @Test
    fun longChunksObserveCancellationBeforeCompleting() {
        var probes = 0
        val scanner = LineIndentation(4) {
            if (++probes == 3) throw CancellationException("canceled indentation")
        }
        assertThatThrownBy { scanner.append(" ".repeat(20_000), endOfLine = true) }
            .isInstanceOf(CancellationException::class.java)
            .hasMessage("canceled indentation")
        assertThat(scanner.isComplete).isFalse()
        assertThatThrownBy { scanner.column }.isInstanceOf(IllegalStateException::class.java)
    }

    private fun scan(text: String, chunkSizes: List<Int>, tabSize: Int): Int {
        val scanner = LineIndentation(tabSize)
        if (text.isEmpty()) scanner.append("", endOfLine = true)
        var offset = 0
        var chunk = 0
        while (!scanner.isComplete) {
            val end = minOf(text.length, offset + chunkSizes[chunk++ % chunkSizes.size])
            scanner.append(text.substring(offset, end), endOfLine = end == text.length)
            offset = end
        }
        return scanner.column
    }
}
