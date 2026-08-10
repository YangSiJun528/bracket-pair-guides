package com.sijunyang.bracketpairguides.analysis.guide

/** The bounded primitive-array shape of the guide-position segment tree. */
internal class GuideTreeShape private constructor(
    val leafCount: Int,
    val entryCount: Int,
) {
    companion object {
        /**
         * Rounds leaves to a power of two without crossing the 16 MiB payload
         * boundary. The next line-count boundary would double the retained
         * LongArray to 32 MiB, so oversized documents omit this index.
         */
        fun forLineCount(lineCount: Int): GuideTreeShape? {
            if (lineCount < 0) return null

            var leafCount = 1L
            val requiredLeaves = lineCount.coerceAtLeast(1).toLong()
            while (leafCount < requiredLeaves) {
                leafCount = leafCount shl 1
            }

            val entryCount = leafCount * TREE_ENTRIES_PER_LEAF
            val payloadBytes = entryCount * Long.SIZE_BYTES
            if (leafCount > Int.MAX_VALUE ||
                entryCount > Int.MAX_VALUE ||
                payloadBytes > MAXIMUM_TREE_PAYLOAD_BYTES
            ) {
                return null
            }
            return GuideTreeShape(
                leafCount = leafCount.toInt(),
                entryCount = entryCount.toInt(),
            )
        }

        private const val TREE_ENTRIES_PER_LEAF = 2L
        private const val MAXIMUM_TREE_PAYLOAD_BYTES = 16L * 1024 * 1024
    }
}
