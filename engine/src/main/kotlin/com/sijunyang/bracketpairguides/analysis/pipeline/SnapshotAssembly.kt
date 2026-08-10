package com.sijunyang.bracketpairguides.analysis.pipeline

import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketSnapshot
import com.sijunyang.bracketpairguides.analysis.IndexedBracketSnapshot
import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.guide.GuideLineEnvelope
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBrackets
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex

/** The state and memory-order policy for assembling one [BracketSnapshot]. */
internal class SnapshotAssembly(
    private val input: AnalysisInput,
    private val documentBrackets: DocumentBrackets,
    private val progress: ProgressIndicator,
) {
    fun snapshot(): BracketSnapshot {
        val stamp = input.stamp
        if (!stamp.coverage.pairs) return emptySnapshot()

        val layout = IndexLayout.forCoverage(stamp.coverage)
        val pairs = documentBrackets.pairs(progress)
        if (pairs.isEmpty) return emptySnapshot()

        val activeIndex = if (layout.activePair) {
            ActiveBracketPairIndex.build(pairs, progress::checkCanceled)
        } else {
            ActiveBracketPairIndex.build(PairTable.empty())
        }

        // Active runs first so its larger temporary workspace is released before
        // the stable token-index payload is retained.
        val tokenIndex = when (layout.tokenStorage) {
            TokenStorage.NONE -> BracketTokenIndex.build(PairTable.empty())
            TokenStorage.ATTACHED -> BracketTokenIndex.build(
                pairs,
                progress::checkCanceled,
            )
            TokenStorage.DETACHED -> BracketTokenIndex.buildDetached(
                pairs,
                progress::checkCanceled,
            )
        }

        val positionIndex = if (layout.guidePosition) {
            guidePositionIndex(pairs)
        } else {
            null
        }
        return IndexedBracketSnapshot(
            stamp = stamp,
            pairs = pairs.takeIf { layout.activePair } ?: PairTable.empty(),
            tokenIndex = tokenIndex,
            activeIndex = activeIndex,
            positionIndex = positionIndex,
        )
    }

    private fun guidePositionIndex(pairs: PairTable): GuidePositionIndex? {
        val document = input.editor.document
        val envelope = GuideLineEnvelope.from(
            pairs = pairs,
            documentLength = document.textLength,
            documentLineCount = document.lineCount,
            checkCanceled = progress::checkCanceled,
        ) ?: return null
        // Oversized spans intentionally use the bounded on-demand fallback.
        return GuidePositionIndex.from(
            document = document,
            tabSize = input.stamp.tabSize,
            progress = progress,
            indexedLineRange = envelope.lines,
        )
    }

    private fun emptySnapshot(): BracketSnapshot = IndexedBracketSnapshot(
        stamp = input.stamp,
        pairs = PairTable.empty(),
        tokenIndex = BracketTokenIndex.build(PairTable.empty()),
        activeIndex = ActiveBracketPairIndex.build(PairTable.empty()),
        positionIndex = null,
    )
}
