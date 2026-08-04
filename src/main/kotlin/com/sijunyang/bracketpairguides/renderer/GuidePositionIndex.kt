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
        val firstCandidateLine = lineAfterOpenOrClose(pair.openLine, pair.closeLine)
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
        ): GuidePositionIndex? {
            progress.checkCanceled()
            val lineCount = document.lineCount
            val storage = storageFor(lineCount) ?: return null
            val starts = IntArray(lineCount)
            val ends = IntArray(lineCount)
            for (line in 0 until lineCount) {
                if (line and CANCELLATION_LINE_MASK == 0) progress.checkCanceled()
                starts[line] = document.getLineStartOffset(line)
                ends[line] = document.getLineEndOffset(line)
            }
            return build(
                text = document.immutableCharSequence,
                lineStarts = starts,
                lineEnds = ends,
                tabSize = tabSize,
                checkCanceled = progress::checkCanceled,
                storage = storage,
            )
        }

        internal fun from(
            text: CharSequence,
            lineStarts: IntArray,
            lineEnds: IntArray,
            tabSize: Int,
            checkCanceled: () -> Unit = {},
        ): GuidePositionIndex = build(
            text = text,
            lineStarts = lineStarts,
            lineEnds = lineEnds,
            tabSize = tabSize,
            checkCanceled = checkCanceled,
            storage = checkNotNull(storageFor(lineStarts.size)) {
                "Guide position index exceeds its $MAXIMUM_TREE_PAYLOAD_BYTES-byte tree budget"
            },
        )

        private fun build(
            text: CharSequence,
            lineStarts: IntArray,
            lineEnds: IntArray,
            tabSize: Int,
            checkCanceled: () -> Unit,
            storage: TreeStorage,
        ): GuidePositionIndex {
            require(lineStarts.size == lineEnds.size)
            val lineCount = lineStarts.size
            val treeSize = storage.leafCount
            val tree = LongArray(storage.entryCount) { NO_INDENT_ENTRY }
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

        /**
         * The segment tree rounds its leaf count to a power of two. Allowing the
         * next boundary after 1,048,576 lines would double only this LongArray
         * from 16 MiB to 32 MiB. Oversized documents instead omit the index and
         * use the existing bounded active-guide scan; its result can be
         * provisional, but it does not allocate in proportion to line count.
         */
        internal fun supportsLineCount(lineCount: Int): Boolean = storageFor(lineCount) != null

        /** LongArray payload only; excludes the small JVM array header. */
        internal fun treePayloadBytes(lineCount: Int): Long? = storagePlan(lineCount)?.payloadBytes

        private fun storageFor(lineCount: Int): TreeStorage? = storagePlan(lineCount)
            ?.takeIf { it.payloadBytes <= MAXIMUM_TREE_PAYLOAD_BYTES }

        private fun storagePlan(lineCount: Int): TreeStorage? {
            if (lineCount < 0) return null

            var leafCount = 1L
            val requiredLeaves = lineCount.coerceAtLeast(1).toLong()
            while (leafCount < requiredLeaves) {
                leafCount = leafCount shl 1
            }

            val entryCount = leafCount * TREE_ENTRIES_PER_LEAF
            if (leafCount > Int.MAX_VALUE || entryCount > Int.MAX_VALUE) return null
            return TreeStorage(
                leafCount = leafCount.toInt(),
                entryCount = entryCount.toInt(),
                payloadBytes = entryCount * Long.SIZE_BYTES,
            )
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
                    ' ' -> column = GuideIndentation.afterSpace(column)
                    '\t' -> column = GuideIndentation.afterTab(column, tabSize)
                    else -> return column
                }
            }
            return NO_INDENT
        }

        internal fun lineAfterOpenOrClose(openLine: Int, closeLine: Int): Int =
            if (openLine < closeLine) openLine + 1 else closeLine

        private const val CANCELLATION_LINE_MASK = 0xFF
        private const val CANCELLATION_TREE_MASK = 0xFFF
        private const val CANCELLATION_CHARACTER_MASK = 0xFFF
        private const val NO_INDENT_ENTRY = Long.MAX_VALUE
        private const val UINT_MASK = 0xFFFF_FFFFL
        private const val TREE_ENTRIES_PER_LEAF = 2L
        internal const val MAXIMUM_TREE_PAYLOAD_BYTES = 16L * 1024 * 1024

        private data class TreeStorage(
            val leafCount: Int,
            val entryCount: Int,
            val payloadBytes: Long,
        )
    }
}
