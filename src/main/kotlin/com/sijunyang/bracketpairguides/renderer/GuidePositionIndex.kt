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

    /**
     * Updates only the indentation lines affected by one document event. When
     * line count changes, unchanged indentation values are copied and the range
     * tree is rebuilt without rescanning the rest of the document text.
     */
    internal fun afterDocumentChange(
        document: Document,
        change: DocumentChange,
        tabSize: Int,
    ): GuidePositionIndex {
        val newLineCount = document.lineCount
        val expectedLineCount = lineCount - change.oldLineBreakCount +
            change.newLineBreakCount
        if (expectedLineCount != newLineCount) {
            return from(document, tabSize)
        }

        val startLine = document.getLineNumber(
            change.offset.coerceIn(0, document.textLength),
        )
        val oldAffectedLineCount = change.oldLineBreakCount + 1
        val newAffectedLineCount = change.newLineBreakCount + 1
        if (newLineCount == lineCount) {
            updateLinesInPlace(
                document = document,
                firstLine = startLine,
                lineCount = newAffectedLineCount,
                tabSize = tabSize,
            )
            return this
        }

        val indentations = IntArray(newLineCount) { NO_INDENT }
        val prefixCount = startLine.coerceAtMost(minOf(lineCount, newLineCount))
        copyIndentations(
            indentations,
            sourceStart = 0,
            targetStart = 0,
            count = prefixCount,
        )

        val firstOldSuffix = (startLine + oldAffectedLineCount).coerceAtMost(lineCount)
        val firstNewSuffix = (startLine + newAffectedLineCount).coerceAtMost(newLineCount)
        val suffixCount = minOf(
            lineCount - firstOldSuffix,
            newLineCount - firstNewSuffix,
        )
        copyIndentations(
            indentations,
            sourceStart = firstOldSuffix,
            targetStart = firstNewSuffix,
            count = suffixCount,
        )

        val text = document.immutableCharSequence
        val lastChangedLine = (startLine + newAffectedLineCount - 1)
            .coerceAtMost(newLineCount - 1)
        var line = startLine
        while (line <= lastChangedLine) {
            indentations[line] = indentationColumn(
                text = text,
                start = document.getLineStartOffset(line),
                end = document.getLineEndOffset(line),
                tabSize = tabSize.coerceAtLeast(1),
            )
            line++
        }
        return fromIndentations(indentations)
    }

    private fun updateLinesInPlace(
        document: Document,
        firstLine: Int,
        lineCount: Int,
        tabSize: Int,
    ) {
        if (this.lineCount == 0) return
        val text = document.immutableCharSequence
        val lastLine = (firstLine + lineCount - 1).coerceAtMost(this.lineCount - 1)
        var line = firstLine.coerceAtLeast(0)
        while (line <= lastLine) {
            var node = treeSize + line
            minimumTree[node] = indentationColumn(
                text = text,
                start = document.getLineStartOffset(line),
                end = document.getLineEndOffset(line),
                tabSize = tabSize.coerceAtLeast(1),
            )
            node /= 2
            while (node > 0) {
                minimumTree[node] = minOf(
                    minimumTree[node * 2],
                    minimumTree[node * 2 + 1],
                )
                node /= 2
            }
            line++
        }
    }

    private fun copyIndentations(
        target: IntArray,
        sourceStart: Int,
        targetStart: Int,
        count: Int,
    ) {
        if (count <= 0) return
        System.arraycopy(
            minimumTree,
            treeSize + sourceStart,
            target,
            targetStart,
            count,
        )
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

        internal fun from(document: Document, tabSize: Int): GuidePositionIndex {
            val lineCount = document.lineCount
            val indentations = IntArray(lineCount)
            val text = document.immutableCharSequence
            var line = 0
            while (line < lineCount) {
                indentations[line] = indentationColumn(
                    text = text,
                    start = document.getLineStartOffset(line),
                    end = document.getLineEndOffset(line),
                    tabSize = tabSize.coerceAtLeast(1),
                )
                line++
            }
            return fromIndentations(indentations)
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

        private fun fromIndentations(indentations: IntArray): GuidePositionIndex {
            val lineCount = indentations.size
            var treeSize = 1
            while (treeSize < lineCount) treeSize *= 2
            val tree = IntArray(treeSize * 2) { NO_INDENT }
            indentations.copyInto(tree, destinationOffset = treeSize)
            var node = treeSize - 1
            while (node > 0) {
                tree[node] = minOf(tree[node * 2], tree[node * 2 + 1])
                node--
            }
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

        private fun indentationColumn(
            text: CharSequence,
            start: Int,
            end: Int,
            tabSize: Int,
        ): Int {
            var column = 0
            var offset = start
            while (offset < end) {
                when (text[offset]) {
                    ' ' -> column++
                    '\t' -> column += tabSize - column % tabSize
                    else -> return column
                }
                offset++
            }
            return NO_INDENT
        }

        private const val CANCELLATION_LINE_MASK = 0xFF
        private const val CANCELLATION_TREE_MASK = 0xFFF
        private const val CANCELLATION_CHARACTER_MASK = 0xFFF
    }
}
