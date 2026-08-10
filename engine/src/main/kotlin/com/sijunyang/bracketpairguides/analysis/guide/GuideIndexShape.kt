package com.sijunyang.bracketpairguides.analysis.guide

/** The bounded primitive-array shape of the blocked guide-position index. */
internal class GuideIndexShape private constructor(
    val indentationEntryCount: Int,
    val blockLeafCount: Int,
    val blockTreeEntryCount: Int,
) {
    companion object {
        /**
         * Plans one indentation integer per line and a power-of-two minimum
         * tree whose leaves summarize 256-line blocks. The combined retained
         * primitive-array payload never crosses 4 MiB.
         */
        fun forLineCount(lineCount: Int): GuideIndexShape? {
            if (lineCount < 0) return null

            val indentationBytes = lineCount.toLong() * Int.SIZE_BYTES
            if (indentationBytes > MAXIMUM_INDEX_PAYLOAD_BYTES) return null

            val blockCount = (lineCount.toLong() + LINES_PER_BLOCK - 1L) / LINES_PER_BLOCK
            var blockLeafCount = 1L
            val requiredBlockLeaves = blockCount.coerceAtLeast(1L)
            while (blockLeafCount < requiredBlockLeaves) {
                blockLeafCount = blockLeafCount shl 1
            }

            val blockTreeEntryCount = blockLeafCount * TREE_ENTRIES_PER_LEAF
            val blockTreeBytes = blockTreeEntryCount * Long.SIZE_BYTES
            val payloadBytes = indentationBytes + blockTreeBytes
            if (blockLeafCount > Int.MAX_VALUE ||
                blockTreeEntryCount > Int.MAX_VALUE ||
                payloadBytes > MAXIMUM_INDEX_PAYLOAD_BYTES
            ) {
                return null
            }
            return GuideIndexShape(
                indentationEntryCount = lineCount,
                blockLeafCount = blockLeafCount.toInt(),
                blockTreeEntryCount = blockTreeEntryCount.toInt(),
            )
        }

        internal const val LINES_PER_BLOCK: Int = 256
        private const val TREE_ENTRIES_PER_LEAF = 2L
        private const val MAXIMUM_INDEX_PAYLOAD_BYTES: Long = 4L * 1024 * 1024
    }
}
