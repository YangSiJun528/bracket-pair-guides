package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.pairing.BracketRecognitionRefusal
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBracketRecognition
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.pairing.toPairTable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.util.concurrent.CancellationException

class SnapshotCalculationTest {
    @Test
    fun emptyCoverageIgnoresRecognitionAndProvidesNoIndexes() {
        val coverage = AnalysisCoverage(tokens = false, activePair = false, guidePosition = false)
        val prepared = prepare(
            coverage,
            DocumentBracketRecognition.Unavailable(BracketRecognitionRefusal.PAIR_CAPACITY),
        )
        val result = prepared.finish(null) as CalculatedAnalysis.Available

        assertThat(prepared.guideLines).isNull()
        assertThat(result.coverage).isEqualTo(coverage)
        assertThat(result.matcherAvailability).isEqualTo(BraceMatcherAvailability.UNDETERMINED)
        assertThat(result.canonicalPairs.isEmpty).isTrue()
        assertThat(result.indexes.tokens.firstIndexAtOrAfter(Int.MAX_VALUE)).isZero()
        assertThat(result.indexes.activePairs.activePairIndex(1)).isNegative()
    }

    @Test
    fun recognitionRefusalsPreserveTheirLimitWithoutRequestingGuides() {
        for ((refusal, limit) in listOf(
            BracketRecognitionRefusal.PAIR_CAPACITY to AnalysisLimit.PAIR_CAPACITY,
            BracketRecognitionRefusal.PENDING_OPEN_CAPACITY to AnalysisLimit.PENDING_OPEN_CAPACITY,
        )) {
            val prepared = prepare(
                ALL_COVERAGE,
                DocumentBracketRecognition.Unavailable(refusal),
            )
            val result = prepared.finish(null) as CalculatedAnalysis.Unavailable

            assertThat(prepared.guideLines).isNull()
            assertThat(result.limit).isEqualTo(limit)
        }
    }

    @Test
    fun emptyRecognitionPreservesMatcherAvailabilityAndRequestedCoverage() {
        val prepared = prepare(
            ALL_COVERAGE,
            DocumentBracketRecognition.Complete(PairTable.empty(), BraceMatcherAvailability.UNAVAILABLE),
        )
        val result = prepared.finish(null) as CalculatedAnalysis.Available

        assertThat(prepared.guideLines).isNull()
        assertThat(result.matcherAvailability).isEqualTo(BraceMatcherAvailability.UNAVAILABLE)
        assertThat(result.coverage).isEqualTo(ALL_COVERAGE)
        assertThat(result.limit).isNull()
    }

    @Test
    fun layoutsRetainOnlyRequestedFacetsAndKeepCanonicalPairs() {
        val pairs = listOf(pair()).toPairTable()
        val recognition = DocumentBracketRecognition.Complete(pairs, BraceMatcherAvailability.AVAILABLE)
        for (coverage in listOf(
            AnalysisCoverage(tokens = true, activePair = false, guidePosition = false),
            AnalysisCoverage(tokens = false, activePair = true, guidePosition = false),
            AnalysisCoverage(tokens = true, activePair = true, guidePosition = false),
            ALL_COVERAGE,
        )) {
            val result = prepare(coverage, recognition).finish(null) as CalculatedAnalysis.Available

            assertThat(result.layout).isEqualTo(IndexLayout.forCoverage(coverage))
            assertThat(result.canonicalPairs).isSameAs(pairs)
            assertThat(result.indexes.pairs.isEmpty).isEqualTo(!coverage.activePair)
            assertThat(result.indexes.tokens.firstIndexAtOrAfter(Int.MAX_VALUE))
                .isEqualTo(if (coverage.tokens) 2 else 0)
            assertThat(result.indexes.activePairs.activePairIndex(1))
                .isEqualTo(if (coverage.activePair) 0 else -1)
            assertThat(result.indexes.guidePositions).isNull()
            assertThat(result.limit).isNull()
        }
    }

    @Test
    fun preflightRequestsExactGuideEnvelopeAndFinishAttachesItsIndex() {
        val bracket = pair(openLine = 1, closeLine = 5)
        val prepared = prepare(
            ALL_COVERAGE,
            DocumentBracketRecognition.Complete(listOf(bracket).toPairTable(), BraceMatcherAvailability.AVAILABLE),
            lineCount = 8,
        )
        assertThat(prepared.guideLines).isEqualTo(2..5)
        val index = checkNotNull(GuidePositionIndex.from(2, 4, {}) { 3 })
        val result = prepared.finish(index) as CalculatedAnalysis.Available

        assertThat(result.indexes.guidePositions).isSameAs(index)
        assertThat(result.indexes.guidePositions?.guideForOrNull(bracket)?.guideColumn).isEqualTo(3)
        assertThat(result.coverage).isEqualTo(ALL_COVERAGE)
        assertThat(result.limit).isNull()
    }

    @Test
    fun guideCapacityKeepsExactLowerFacetsWithoutAllocatingAGuideIndex() {
        val pairs = listOf(pair(closeLine = 1_048_576)).toPairTable()
        val prepared = prepare(
            ALL_COVERAGE,
            DocumentBracketRecognition.Complete(pairs, BraceMatcherAvailability.AVAILABLE),
            lineCount = 1_048_577,
        )
        val result = prepared.finish(null) as CalculatedAnalysis.Available

        assertThat(prepared.guideLines).isNull()
        assertThat(result.coverage).isEqualTo(ALL_COVERAGE.withoutGuidePosition())
        assertThat(result.layout.guidePosition).isFalse()
        assertThat(result.limit).isEqualTo(AnalysisLimit.GUIDE_CAPACITY)
        assertThat(result.canonicalPairs).isSameAs(pairs)
        assertThat(result.indexes.pairs).isSameAs(pairs)
        assertThat(result.indexes.activePairs.activePairIndex(1)).isZero()
        assertThat(result.indexes.tokens.firstIndexAtOrAfter(Int.MAX_VALUE)).isEqualTo(2)
        assertThat(result.indexes.guidePositions).isNull()
    }

    @Test
    fun finishRejectsMissingAndUnrequestedGuideIndexes() {
        val prepared = prepare(
            ALL_COVERAGE,
            DocumentBracketRecognition.Complete(
                listOf(pair(closeLine = 2)).toPairTable(),
                BraceMatcherAvailability.AVAILABLE,
            ),
            lineCount = 3,
        )
        assertThatThrownBy { prepared.finish(null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("A preflighted guide index must be allocatable")

        val noGuides = prepare(
            ALL_COVERAGE.withoutGuidePosition(),
            DocumentBracketRecognition.Complete(PairTable.empty(), BraceMatcherAvailability.AVAILABLE),
        )
        val index = checkNotNull(GuidePositionIndex.from(0, 1, {}) { 0 })
        assertThatThrownBy { noGuides.finish(index) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("A guide index must have a requested line range")
    }

    @Test
    fun cancellationStopsPreparationAndFinishing() {
        val recognition = DocumentBracketRecognition.Complete(
            listOf(pair()).toPairTable(),
            BraceMatcherAvailability.AVAILABLE,
        )
        assertThatThrownBy {
            SnapshotCalculation.prepare(ALL_COVERAGE, recognition, 16, 1) {
                throw CancellationException("canceled preparation")
            }
        }.isInstanceOf(CancellationException::class.java)

        var canceled = false
        val prepared = SnapshotCalculation.prepare(ALL_COVERAGE, recognition, 16, 1) {
            if (canceled) throw CancellationException("canceled finishing")
        }
        canceled = true
        assertThatThrownBy { prepared.finish(null) }
            .isInstanceOf(CancellationException::class.java)
            .hasMessage("canceled finishing")
    }

    private fun prepare(
        coverage: AnalysisCoverage,
        recognition: DocumentBracketRecognition,
        lineCount: Int = 1,
    ): PreparedSnapshot = SnapshotCalculation.prepare(coverage, recognition, 16, lineCount, {})

    private fun pair(openLine: Int = 0, closeLine: Int = 0): BracketPair = BracketPair(
        openOffset = 0,
        openTokenLength = 1,
        closeOffset = 10,
        closeTokenLength = 1,
        depth = 0,
        openLine = openLine,
        closeLine = closeLine,
    )

    private companion object {
        val ALL_COVERAGE = AnalysisCoverage(tokens = true, activePair = true, guidePosition = true)
    }
}
