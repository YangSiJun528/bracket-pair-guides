package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.guide.GuideIndexShape
import com.sijunyang.bracketpairguides.analysis.guide.GuideLineEnvelope
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.pairing.BracketRecognitionRefusal
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBracketRecognition
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex

/** Platform-free capacity, layout, and memory-order policy for one analysis. */
internal object SnapshotCalculation {
    fun prepare(
        coverage: AnalysisCoverage,
        recognition: DocumentBracketRecognition,
        documentLength: Int,
        documentLineCount: Int,
        checkCanceled: () -> Unit,
    ): PreparedSnapshot {
        checkCanceled()
        if (!coverage.pairs) {
            return available(
                coverage,
                PairTable.empty(),
                BraceMatcherAvailability.UNDETERMINED,
                guideLines = null,
                limit = null,
                checkCanceled,
            )
        }
        val pairs = when (recognition) {
            is DocumentBracketRecognition.Complete -> recognition.pairs

            is DocumentBracketRecognition.Unavailable -> {
                return PreparedSnapshot(guideLines = null) {
                    checkCanceled()
                    CalculatedAnalysis.Unavailable(recognition.refusal.analysisLimit())
                }
            }
        }
        if (pairs.isEmpty) {
            return available(
                coverage,
                pairs,
                recognition.matcherAvailability,
                guideLines = null,
                limit = null,
                checkCanceled,
            )
        }

        val guideLines = if (coverage.guidePosition) {
            GuideLineEnvelope.from(
                pairs,
                documentLength,
                documentLineCount,
                checkCanceled,
            )?.lines
        } else {
            null
        }
        val omittedGuide = guideLines?.let { lines ->
            val lineCount = (lines.last.toLong() - lines.first + 1L).toInt()
            GuideIndexShape.forLineCount(lineCount) == null
        } == true
        return available(
            coverage = if (omittedGuide) coverage.withoutGuidePosition() else coverage,
            pairs = pairs,
            matcherAvailability = recognition.matcherAvailability,
            guideLines = guideLines.takeUnless { omittedGuide },
            limit = AnalysisLimit.GUIDE_CAPACITY.takeIf { omittedGuide },
            checkCanceled = checkCanceled,
        )
    }

    private fun available(
        coverage: AnalysisCoverage,
        pairs: PairTable,
        matcherAvailability: BraceMatcherAvailability,
        guideLines: IntRange?,
        limit: AnalysisLimit?,
        checkCanceled: () -> Unit,
    ): PreparedSnapshot {
        val layout = IndexLayout.forCoverage(coverage)
        val activePairs = ActiveBracketPairIndex.build(
            if (layout.activePair) pairs else PairTable.empty(),
            checkCanceled,
        )
        // Release active-index temporary workspace before retaining token and guide payloads.
        val tokens = when (layout.tokenStorage) {
            TokenStorage.NONE -> BracketTokenIndex.build(PairTable.empty(), checkCanceled)
            TokenStorage.ATTACHED -> BracketTokenIndex.build(pairs, checkCanceled)
            TokenStorage.DETACHED -> BracketTokenIndex.buildDetached(pairs, checkCanceled)
        }
        return PreparedSnapshot(guideLines) { guidePositions ->
            checkCanceled()
            CalculatedAnalysis.Available(
                coverage = coverage,
                layout = layout,
                canonicalPairs = pairs,
                indexes = BracketIndexes(
                    pairs = pairs.takeIf { layout.activePair } ?: PairTable.empty(),
                    tokens = tokens,
                    activePairs = activePairs,
                    guidePositions = guidePositions,
                ),
                matcherAvailability = matcherAvailability,
                limit = limit,
            )
        }
    }
}

/** Indexes are prepared before the caller allocates the preflighted guide index. */
internal class PreparedSnapshot internal constructor(
    val guideLines: IntRange?,
    private val finishCalculation: (GuidePositionIndex?) -> CalculatedAnalysis,
) {
    fun finish(guidePositions: GuidePositionIndex?): CalculatedAnalysis {
        if (guideLines != null) {
            checkNotNull(guidePositions) { "A preflighted guide index must be allocatable" }
        } else {
            require(guidePositions == null) { "A guide index must have a requested line range" }
        }
        return finishCalculation(guidePositions)
    }
}

/** Immutable calculation payload; publication identity is supplied by the host. */
internal sealed interface CalculatedAnalysis {
    class Available(
        val coverage: AnalysisCoverage,
        val layout: IndexLayout,
        val canonicalPairs: PairTable,
        val indexes: BracketIndexes,
        val matcherAvailability: BraceMatcherAvailability,
        val limit: AnalysisLimit?,
    ) : CalculatedAnalysis {
        init {
            require(limit == null || limit == AnalysisLimit.GUIDE_CAPACITY) {
                "Only guide capacity can preserve a lower-facet calculation"
            }
            require(limit == null || !coverage.guidePosition) {
                "A guide-capacity calculation must omit guide coverage"
            }
        }
    }

    class Unavailable(val limit: AnalysisLimit) : CalculatedAnalysis {
        init {
            require(limit != AnalysisLimit.GUIDE_CAPACITY) {
                "Guide capacity must preserve exact lower facets"
            }
        }
    }
}

private fun BracketRecognitionRefusal.analysisLimit(): AnalysisLimit = when (this) {
    BracketRecognitionRefusal.PAIR_CAPACITY -> AnalysisLimit.PAIR_CAPACITY
    BracketRecognitionRefusal.PENDING_OPEN_CAPACITY -> AnalysisLimit.PENDING_OPEN_CAPACITY
}
