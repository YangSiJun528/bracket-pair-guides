package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator

data class BracketGuide(
    val pair: BracketPair,
    val guideColumn: Int,
)

/**
 * Precomputes indentation once per document and answers minimum-indentation
 * queries in O(log lineCount). A naive per-pair body scan becomes quadratic for
 * deeply nested or long scopes.
 */
internal class GuidePositionIndex private constructor(
    private val lineCount: Int,
    private val treeSize: Int,
    private val minimumTree: IntArray,
) {
    fun guideFor(pair: BracketPair): BracketGuide {
        val firstCandidateLine = (pair.openLine + 1).coerceAtMost(pair.closeLine)
        val guideColumn = minimumIndent(firstCandidateLine, pair.closeLine)
            .takeUnless { it == NO_INDENT }
            ?: 0
        return BracketGuide(pair, guideColumn)
    }

    internal fun minimumIndent(firstLine: Int, lastLine: Int): Int {
        if (lineCount == 0 || firstLine > lastLine) return NO_INDENT

        var left = firstLine.coerceIn(0, lineCount - 1) + treeSize
        var right = lastLine.coerceIn(0, lineCount - 1) + treeSize
        var minimum = NO_INDENT

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
            val tree = IntArray(treeSize * 2) { NO_INDENT }
            val effectiveTabSize = tabSize.coerceAtLeast(1)

            for (line in 0 until lineCount) {
                if (line and CANCELLATION_LINE_MASK == 0) checkCanceled()
                tree[treeSize + line] = indentationColumn(
                    text = text,
                    start = lineStarts[line],
                    end = lineEnds[line],
                    tabSize = effectiveTabSize,
                    checkCanceled = checkCanceled,
                )
            }
            for (node in treeSize - 1 downTo 1) {
                if (node and CANCELLATION_TREE_MASK == 0) checkCanceled()
                tree[node] = minOf(tree[node * 2], tree[node * 2 + 1])
            }
            checkCanceled()

            return GuidePositionIndex(lineCount, treeSize, tree)
        }

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
    }
}
