package com.sijunyang.bracketpairguides.analysis.pipeline

import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.BracketIndexes
import com.sijunyang.bracketpairguides.analysis.BracketSnapshot
import com.sijunyang.bracketpairguides.analysis.IndexedBracketSnapshot
import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.guide.GuideLineEnvelope
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.guide.GuideIndexShape
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBrackets
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBracketState
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex

/** The state and memory-order policy for assembling one [BracketSnapshot]. */
internal class SnapshotAssembly(
    private val input: AnalysisInput,
    private val documentBrackets: DocumentBrackets,
    private val progress: ProgressIndicator,
    private val canonicalIndexes: (
        IndexLayout,
        PairTable,
        BracketIndexes,
    ) -> BracketIndexes,
) {
    fun snapshot(): AnalysisOutcome {
        val stamp = input.stamp
        val layout = IndexLayout.forCoverage(stamp.coverage)
        if (!stamp.coverage.pairs) return complete(emptySnapshot(layout))

        val pairs = when (val state = documentBrackets.pairs(progress)) {
            is DocumentBracketState.Complete -> state.pairs
            is DocumentBracketState.Unavailable -> return unavailable(state.limit)
        }
        if (pairs.isEmpty) return complete(emptySnapshot(layout))

        val guideEnvelope = if (layout.guidePosition) {
            guideEnvelope(pairs)
        } else {
            null
        }
        val guideShape = guideEnvelope?.let { envelope ->
            GuideIndexShape.forLineCount(envelope.lineCount())
                ?: return unavailable(AnalysisLimit.WORKING_MEMORY)
        }
        AnalysisBudget.limitAt(
            pairCount = pairs.size(),
            layout = layout,
            guidePayloadBytes = guideShape?.payloadBytes ?: 0L,
        )?.let { limit -> return unavailable(limit) }

        val activeIndex = if (layout.activePair) {
            ActiveBracketPairIndex.build(pairs, progress::checkCanceled)
        } else {
            ActiveBracketPairIndex.build(PairTable.empty(), progress::checkCanceled)
        }

        // Active runs first so its larger temporary workspace is released before
        // the stable token-index payload is retained.
        val tokenIndex = when (layout.tokenStorage) {
            TokenStorage.NONE -> BracketTokenIndex.build(
                PairTable.empty(),
                progress::checkCanceled,
            )
            TokenStorage.ATTACHED -> BracketTokenIndex.build(
                pairs,
                progress::checkCanceled,
            )
            TokenStorage.DETACHED -> BracketTokenIndex.buildDetached(
                pairs,
                progress::checkCanceled,
            )
        }

        val positionIndex = guideEnvelope?.let { envelope ->
            checkNotNull(
                GuidePositionIndex.from(
                    document = input.editor.document,
                    tabSize = input.stamp.tabSize,
                    progress = progress,
                    indexedLineRange = envelope.lines,
                ),
            ) { "A preflighted guide index must be allocatable" }
        }
        val indexes = BracketIndexes(
            pairs = pairs.takeIf { layout.activePair } ?: PairTable.empty(),
            tokens = tokenIndex,
            activePairs = activeIndex,
            guidePositions = positionIndex,
        )
        return complete(
            IndexedBracketSnapshot(
                stamp = stamp,
                indexes = canonicalIndexes(layout, pairs, indexes),
            ),
        )
    }

    private fun guideEnvelope(pairs: PairTable): GuideLineEnvelope? {
        val document = input.editor.document
        return GuideLineEnvelope.from(
            pairs = pairs,
            documentLength = document.textLength,
            documentLineCount = document.lineCount,
            checkCanceled = progress::checkCanceled,
        )
    }

    private fun GuideLineEnvelope.lineCount(): Int =
        (lines.last.toLong() - lines.first + 1L).toInt()

    private fun complete(snapshot: BracketSnapshot): AnalysisOutcome =
        AnalysisOutcome.Complete(snapshot)

    private fun unavailable(limit: AnalysisLimit): AnalysisOutcome =
        AnalysisOutcome.Unavailable(input.stamp, limit)

    private fun emptySnapshot(layout: IndexLayout): BracketSnapshot {
        val pairs = PairTable.empty()
        val indexes = BracketIndexes(
            pairs = pairs,
            tokens = BracketTokenIndex.build(pairs, progress::checkCanceled),
            activePairs = ActiveBracketPairIndex.build(pairs, progress::checkCanceled),
            guidePositions = null,
        )
        return IndexedBracketSnapshot(
            stamp = input.stamp,
            indexes = canonicalIndexes(layout, pairs, indexes),
        )
    }
}
