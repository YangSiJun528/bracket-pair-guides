package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator

internal data class BracketGuide(
    val pair: BracketPair,
    val guideColumn: Int,
    val anchorLine: Int = pair.openLine,
)

/**
 * Precomputes indentation once per document and answers minimum-indentation
 * queries in O(log lineCount). A naive per-pair body scan becomes quadratic for
 * deeply nested or long scopes.
 */
internal class GuidePositionIndex private constructor(
    private val lineCount: Int,
    private val treeSize: Int,
    private val minimumTree: LongArray,
) {
    fun guideFor(pair: BracketPair): BracketGuide {
        val firstCandidateLine = (pair.openLine + 1).coerceAtMost(pair.closeLine)
        val minimum = minimumEntry(firstCandidateLine, pair.closeLine)
        val column = entryColumn(minimum)
        return if (column == NO_INDENT) {
            BracketGuide(pair, guideColumn = 0, anchorLine = firstCandidateLine)
        } else {
            BracketGuide(pair, column, entryLine(minimum))
        }
    }

    private fun minimumEntry(firstLine: Int, lastLine: Int): Long {
        if (lineCount == 0 || firstLine > lastLine) return NO_INDENT_ENTRY

        var left = firstLine.coerceIn(0, lineCount - 1) + treeSize
        var right = lastLine.coerceIn(0, lineCount - 1) + treeSize
        var minimum = NO_INDENT_ENTRY

        while (left <= right) {
            if (left and 1 == 1) minimum = minOf(minimum, minimumTree[left++])
            if (right and 1 == 0) minimum = minOf(minimum, minimumTree[right--])
            left /= 2
            right /= 2
        }

        return minimum
    }

    companion object {
        internal const val NO_INDENT: Int = Int.MAX_VALUE

        fun from(
            document: Document,
            tabSize: Int,
            progress: ProgressIndicator,
        ): GuidePositionIndex {
            val lineCount = document.lineCount
            val starts = IntArray(lineCount)
            val ends = IntArray(lineCount)
            for (line in 0 until lineCount) {
                if (line and CANCELLATION_LINE_MASK == 0) progress.checkCanceled()
                starts[line] = document.getLineStartOffset(line)
                ends[line] = document.getLineEndOffset(line)
            }
            return from(
                text = document.immutableCharSequence,
                lineStarts = starts,
                lineEnds = ends,
                tabSize = tabSize,
                checkCanceled = progress::checkCanceled,
            )
        }

        internal fun from(
            text: CharSequence,
            lineStarts: IntArray,
            lineEnds: IntArray,
            tabSize: Int,
            checkCanceled: () -> Unit = {},
        ): GuidePositionIndex {
            require(lineStarts.size == lineEnds.size)
            val lineCount = lineStarts.size
            var treeSize = 1
            while (treeSize < lineCount) treeSize *= 2
            val tree = LongArray(treeSize * 2) { NO_INDENT_ENTRY }
            val effectiveTabSize = tabSize.coerceAtLeast(1)

            for (line in 0 until lineCount) {
                if (line and CANCELLATION_LINE_MASK == 0) checkCanceled()
                tree[treeSize + line] = entry(
                    indentationColumn(
                        text = text,
                        start = lineStarts[line],
                        end = lineEnds[line],
                        tabSize = effectiveTabSize,
                        checkCanceled = checkCanceled,
                    ),
                    line,
                )
            }
            for (node in treeSize - 1 downTo 1) {
                if (node and CANCELLATION_TREE_MASK == 0) checkCanceled()
                tree[node] = minOf(tree[node * 2], tree[node * 2 + 1])
            }
            checkCanceled()

            return GuidePositionIndex(lineCount, treeSize, tree)
        }

        private fun entry(column: Int, line: Int): Long =
            (column.toLong() shl Int.SIZE_BITS) or (line.toLong() and UINT_MASK)

        private fun entryColumn(entry: Long): Int = (entry ushr Int.SIZE_BITS).toInt()

        private fun entryLine(entry: Long): Int = entry.toInt()

        private fun indentationColumn(
            text: CharSequence,
            start: Int,
            end: Int,
            tabSize: Int,
            checkCanceled: () -> Unit,
        ): Int {
            var column = 0
            for (offset in start until end) {
                if ((offset - start) and CANCELLATION_CHARACTER_MASK == 0) {
                    checkCanceled()
                }
                when (text[offset]) {
                    ' ' -> column++
                    '\t' -> column += tabSize - column % tabSize
                    else -> return column
                }
            }
            return NO_INDENT
        }

        private const val CANCELLATION_LINE_MASK = 0xFF
        private const val CANCELLATION_TREE_MASK = 0xFFF
        private const val CANCELLATION_CHARACTER_MASK = 0xFFF
        private const val NO_INDENT_ENTRY = Long.MAX_VALUE
        private const val UINT_MASK = 0xFFFF_FFFFL
    }
}
