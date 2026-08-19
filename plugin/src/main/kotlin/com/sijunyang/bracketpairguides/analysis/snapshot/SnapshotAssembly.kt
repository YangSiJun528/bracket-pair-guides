package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.guide.GuideIndexShape
import com.sijunyang.bracketpairguides.analysis.guide.GuideLineEnvelope
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.pairing.BracketRecognitionRefusal
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBracketRecognition
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex

/** The state and memory-order policy for assembling one [BracketSnapshot]. */
internal class SnapshotAssembly(
    private val input: AnalysisInput,
    private val recognize: () -> DocumentBracketRecognition,
    private val checkCanceled: () -> Unit,
    private val documentLength: Int,
    private val documentLineCount: Int,
    private val guidePositions: (IntRange) -> GuidePositionIndex?,
    private val canonicalIndexes: (
        AnalysisInput,
        IndexLayout,
        PairTable,
        BracketIndexes,
    ) -> BracketIndexes,
) {
    fun outcome(): AnalysisOutcome {
        val stamp = input.stamp
        var snapshotInput = input
        var layout = IndexLayout.forCoverage(stamp.coverage)
        if (!stamp.coverage.pairs) {
            return complete(
                emptySnapshot(layout, BraceMatcherAvailability.UNDETERMINED),
            )
        }

        val recognition = recognize()
        val pairs = when (recognition) {
            is DocumentBracketRecognition.Complete -> recognition.pairs
            is DocumentBracketRecognition.Unavailable -> {
                return unavailable(recognition.refusal.analysisLimit())
            }
        }
        val matcherAvailability = recognition.matcherAvailability
        if (pairs.isEmpty) {
            return complete(emptySnapshot(layout, matcherAvailability))
        }

        var guideEnvelope = if (layout.guidePosition) {
            guideEnvelope(pairs)
        } else {
            null
        }
        val omittedGuide = guideEnvelope?.let { envelope ->
            GuideIndexShape.forLineCount(envelope.lineCount()) == null
        } == true
        if (omittedGuide) {
            snapshotInput = input.withCoverage(input.coverage.withoutGuidePosition())
            layout = IndexLayout.forCoverage(snapshotInput.coverage)
            guideEnvelope = null
        }

        val activeIndex = if (layout.activePair) {
            ActiveBracketPairIndex.build(pairs, checkCanceled)
        } else {
            ActiveBracketPairIndex.build(PairTable.empty(), checkCanceled)
        }

        // Active runs first so its larger temporary workspace is released before
        // the stable token-index payload is retained.
        val tokenIndex = when (layout.tokenStorage) {
            TokenStorage.NONE -> BracketTokenIndex.build(
                PairTable.empty(),
                checkCanceled,
            )
            TokenStorage.ATTACHED -> BracketTokenIndex.build(
                pairs,
                checkCanceled,
            )
            TokenStorage.DETACHED -> BracketTokenIndex.buildDetached(
                pairs,
                checkCanceled,
            )
        }

        // Qodana cannot infer that the preflight and allocation callbacks share
        // the same capacity contract. Keep this assertion so a broken callback
        // cannot publish a snapshot that claims guide coverage without an index.
        @Suppress("RedundantRequireNotNullCall")
        val positionIndex = guideEnvelope?.let { envelope ->
            checkNotNull(
                guidePositions(envelope.lines),
            ) { "A preflighted guide index must be allocatable" }
        }
        val indexes = BracketIndexes(
            pairs = pairs.takeIf { layout.activePair } ?: PairTable.empty(),
            tokens = tokenIndex,
            activePairs = activeIndex,
            guidePositions = positionIndex,
        )
        val snapshot = BracketSnapshot(
            stamp = snapshotInput.stamp,
            matcherAvailability = matcherAvailability,
            indexes = canonicalIndexes(snapshotInput, layout, pairs, indexes),
        )
        return if (omittedGuide) {
            AnalysisOutcome.Limited(
                stamp = stamp,
                snapshot = snapshot,
                limit = AnalysisLimit.GUIDE_CAPACITY,
            )
        } else {
            complete(snapshot)
        }
    }

    private fun guideEnvelope(pairs: PairTable): GuideLineEnvelope? =
        GuideLineEnvelope.from(
            pairs = pairs,
            documentLength = documentLength,
            documentLineCount = documentLineCount,
            checkCanceled = checkCanceled,
        )

    private fun GuideLineEnvelope.lineCount(): Int =
        (lines.last.toLong() - lines.first + 1L).toInt()

    private fun complete(snapshot: BracketSnapshot): AnalysisOutcome =
        AnalysisOutcome.Complete(snapshot)

    private fun unavailable(limit: AnalysisLimit): AnalysisOutcome =
        AnalysisOutcome.Unavailable(input.stamp, limit)

    private fun emptySnapshot(
        layout: IndexLayout,
        matcherAvailability: BraceMatcherAvailability,
    ): BracketSnapshot {
        val pairs = PairTable.empty()
        val indexes = BracketIndexes(
            pairs = pairs,
            tokens = BracketTokenIndex.build(pairs, checkCanceled),
            activePairs = ActiveBracketPairIndex.build(pairs, checkCanceled),
            guidePositions = null,
        )
        return BracketSnapshot(
            stamp = input.stamp,
            matcherAvailability = matcherAvailability,
            indexes = canonicalIndexes(input, layout, pairs, indexes),
        )
    }
}

private fun BracketRecognitionRefusal.analysisLimit(): AnalysisLimit = when (this) {
    BracketRecognitionRefusal.PAIR_CAPACITY -> AnalysisLimit.PAIR_CAPACITY
    BracketRecognitionRefusal.PENDING_OPEN_CAPACITY -> AnalysisLimit.PENDING_OPEN_CAPACITY
}
