package com.sijunyang.bracketpairguides.analysis.guide

import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair

/**
 * Precomputes indentation once for the multiline-pair query envelope and
 * answers exact minimum-indentation queries by scanning at most two partial
 * 256-line blocks plus an O(log blockCount) minimum-tree query. A naive per-pair
 * body scan becomes quadratic for deeply nested or long scopes.
 */
internal class GuidePositionIndex private constructor(
    private val baseLine: Int,
    private val lineCount: Int,
    private val indentationByLine: IntArray,
    private val blockTreeBase: Int,
    private val blockMinimumTree: LongArray,
) {
    fun guideForOrNull(pair: BracketPair): BracketGuide? {
        if (lineCount == 0 || pair.openLine >= pair.closeLine) return null

        val firstCandidateLine = lineAfterOpenOrClose(pair.openLine, pair.closeLine)
        val lastIndexedLine = baseLine + lineCount - 1
        if (firstCandidateLine < baseLine ||
            pair.closeLine > lastIndexedLine
        ) {
            return null
        }
        return guideForRange(
            pair,
            firstLine = firstCandidateLine,
            lastLine = pair.closeLine,
        )
    }

    private fun guideForRange(pair: BracketPair, firstLine: Int, lastLine: Int): BracketGuide {
        val minimum = minimumEntry(firstLine, lastLine)
        val column = entryColumn(minimum)
        return if (column == VisualColumn.BLANK_LINE_COLUMN) {
            BracketGuide(pair, guideColumn = 0, anchorLine = firstLine)
        } else {
            BracketGuide(pair, column, entryLine(minimum))
        }
    }

    private fun minimumEntry(firstLine: Int, lastLine: Int): Long {
        if (lineCount == 0 || firstLine > lastLine) return NO_INDENT_ENTRY

        val lastIndexedLine = baseLine + lineCount - 1
        val boundedFirstLine = maxOf(firstLine, baseLine)
        val boundedLastLine = minOf(lastLine, lastIndexedLine)
        if (boundedFirstLine > boundedLastLine) return NO_INDENT_ENTRY

        val firstRelativeLine = boundedFirstLine - baseLine
        val afterLastRelativeLine = boundedLastLine - baseLine + 1
        val firstFullBlock = firstBlockAtOrAfter(firstRelativeLine)
        val afterLastFullBlock = afterLastRelativeLine / LINES_PER_BLOCK
        val firstFullBlockLine = firstFullBlock * LINES_PER_BLOCK
        val afterLastFullBlockLine = afterLastFullBlock * LINES_PER_BLOCK
        var minimum = NO_INDENT_ENTRY

        val afterLeftEdge = minOf(afterLastRelativeLine, firstFullBlockLine)
        minimum =
            minOf(
                minimum,
                minimumLineEntry(firstRelativeLine, afterLeftEdge),
            )
        if (firstFullBlock < afterLastFullBlock) {
            minimum =
                minOf(
                    minimum,
                    minimumBlockEntry(firstFullBlock, afterLastFullBlock),
                )
        }
        val firstRightEdge = maxOf(afterLeftEdge, afterLastFullBlockLine)
        minimum =
            minOf(
                minimum,
                minimumLineEntry(firstRightEdge, afterLastRelativeLine),
            )
        return minimum
    }

    private fun minimumLineEntry(firstLine: Int, afterLastLine: Int): Long {
        var minimum = NO_INDENT_ENTRY
        for (line in firstLine until afterLastLine) {
            minimum =
                minOf(
                    minimum,
                    entry(indentationByLine[line], baseLine + line),
                )
        }
        return minimum
    }

    private fun minimumBlockEntry(firstBlock: Int, afterLastBlock: Int): Long {
        var left = firstBlock + blockTreeBase
        var right = afterLastBlock - 1 + blockTreeBase
        var minimum = NO_INDENT_ENTRY
        while (left <= right) {
            if (left and 1 == 1) minimum = minOf(minimum, blockMinimumTree[left++])
            if (right and 1 == 0) minimum = minOf(minimum, blockMinimumTree[right--])
            left /= 2
            right /= 2
        }
        return minimum
    }

    private fun firstBlockAtOrAfter(relativeLine: Int): Int = relativeLine / LINES_PER_BLOCK +
        if (relativeLine % LINES_PER_BLOCK == 0) 0 else 1

    /** Owns the final arrays until sealing transfers their immutable view to the index. */
    internal class Builder internal constructor(
        private val baseLine: Int,
        private val lineCount: Int,
        private val storage: GuideIndexShape,
        private val checkCanceled: () -> Unit,
    ) {
        private val indentationByLine = IntArray(storage.indentationEntryCount)
        private val blockMinimumTree = LongArray(storage.blockTreeEntryCount) { NO_INDENT_ENTRY }
        private var appendedLines = 0
        private var sealed = false

        fun append(indentation: Int) {
            check(!sealed) { "A sealed guide index cannot accept indentation" }
            check(appendedLines < lineCount) { "All requested guide lines already have indentation" }
            val line = appendedLines
            if (line and CANCELLATION_LINE_MASK == 0) checkCanceled()
            indentationByLine[line] = indentation
            val blockLeaf = storage.blockLeafCount + line / LINES_PER_BLOCK
            blockMinimumTree[blockLeaf] = minOf(
                blockMinimumTree[blockLeaf],
                entry(indentation, baseLine + line),
            )
            appendedLines++
        }

        fun seal(): GuidePositionIndex {
            check(!sealed) { "A guide index can only be sealed once" }
            check(appendedLines == lineCount) { "Every requested guide line needs indentation before sealing" }
            // A canceled seal cannot leave a mutable alias to a partially finalized tree.
            sealed = true
            checkCanceled()
            for (node in storage.blockLeafCount - 1 downTo 1) {
                if (node and CANCELLATION_TREE_MASK == 0) checkCanceled()
                blockMinimumTree[node] = minOf(
                    blockMinimumTree[node * 2],
                    blockMinimumTree[node * 2 + 1],
                )
            }
            checkCanceled()
            return GuidePositionIndex(
                baseLine = baseLine,
                lineCount = lineCount,
                indentationByLine = indentationByLine,
                blockTreeBase = storage.blockLeafCount,
                blockMinimumTree = blockMinimumTree,
            )
        }
    }

    companion object {
        internal fun builder(baseLine: Int, lineCount: Int, checkCanceled: () -> Unit): Builder? {
            checkCanceled()
            if (baseLine < 0 ||
                lineCount <= 0 ||
                baseLine.toLong() + lineCount - 1L > Int.MAX_VALUE
            ) {
                return null
            }
            val storage = GuideIndexShape.forLineCount(lineCount) ?: return null
            return Builder(baseLine, lineCount, storage, checkCanceled)
        }

        internal fun from(
            baseLine: Int,
            lineCount: Int,
            checkCanceled: () -> Unit,
            indentationAt: (Int) -> Int,
        ): GuidePositionIndex? {
            val builder = builder(baseLine, lineCount, checkCanceled) ?: return null
            for (line in 0 until lineCount) {
                if (line and CANCELLATION_LINE_MASK == 0) checkCanceled()
                builder.append(indentationAt(line))
            }
            return builder.seal()
        }

        private fun entry(column: Int, line: Int): Long =
            (column.toLong() shl Int.SIZE_BITS) or (line.toLong() and UINT_MASK)

        private fun entryColumn(entry: Long): Int = (entry ushr Int.SIZE_BITS).toInt()

        private fun entryLine(entry: Long): Int = entry.toInt()

        private fun lineAfterOpenOrClose(openLine: Int, closeLine: Int): Int =
            if (openLine < closeLine) openLine + 1 else closeLine

        private const val LINES_PER_BLOCK = GuideIndexShape.LINES_PER_BLOCK
        private const val CANCELLATION_LINE_MASK = 0xFF
        private const val CANCELLATION_TREE_MASK = 0xFFF
        private const val NO_INDENT_ENTRY = Long.MAX_VALUE
        private const val UINT_MASK = 0xFFFF_FFFFL
    }
}
