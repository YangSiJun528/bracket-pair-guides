package com.sijunyang.bracketpairguides.analysis.guide

import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable

/** The document-line envelope needed by all valid multiline bracket guides. */
internal data class GuideLineEnvelope private constructor(val lines: IntRange) {
    companion object {
        internal fun from(
            pairs: PairTable,
            documentLength: Int,
            documentLineCount: Int,
            checkCanceled: () -> Unit,
        ): GuideLineEnvelope? {
            if (documentLineCount <= 0) return null
            val lastDocumentLine = documentLineCount - 1
            var firstGuideLine = Int.MAX_VALUE
            var lastGuideLine = -1
            for (pairIndex in 0 until pairs.size()) {
                if (pairIndex and CANCELLATION_MASK == 0) checkCanceled()
                val openLine = pairs.openLineAt(pairIndex)
                val closeLine = pairs.closeLineAt(pairIndex)
                if (pairs.hasWellFormedTokenRangeAt(pairIndex, documentLength) &&
                    openLine >= 0 &&
                    openLine < closeLine &&
                    closeLine <= lastDocumentLine
                ) {
                    firstGuideLine = minOf(firstGuideLine, openLine + 1)
                    lastGuideLine = maxOf(lastGuideLine, closeLine)
                }
            }
            checkCanceled()
            return if (lastGuideLine < 0) {
                null
            } else {
                GuideLineEnvelope(firstGuideLine..lastGuideLine)
            }
        }

        private const val CANCELLATION_MASK = 0xFF
    }
}
