package com.sijunyang.bracketpairguides.analysis.snapshot

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.pairing.BracketRecognitionRefusal
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBracketRecognition
import com.sijunyang.bracketpairguides.analysis.pairing.toPairTable
import org.assertj.core.api.Assertions.assertThat

class AnalysisPublicationTest : BasePlatformTestCase() {
    fun testEmptyCoverageCanonicalizesItsStampedEmptyPayload() {
        myFixture.configureByText("NoCoverage.java", "class NoCoverage { }")
        val input = input(AnalysisCoverage(false, false, false))
        var canonicalizationCalled = false
        // The production pure calculation must ignore even an unavailable recognition for empty coverage.
        val calculated =
            calculate(input, DocumentBracketRecognition.Unavailable(BracketRecognitionRefusal.PAIR_CAPACITY))
        val outcome = stampedOutcome(input, calculated) { effectiveInput, layout, pairs, indexes ->
            canonicalizationCalled = true
            assertThat(effectiveInput).isSameAs(input)
            assertThat(layout).isEqualTo(IndexLayout.forCoverage(input.coverage))
            assertThat(pairs.isEmpty).isTrue()
            indexes
        } as AnalysisOutcome.Complete
        assertThat(outcome.stamp).isSameAs(input.stamp)
        assertThat(canonicalizationCalled).isTrue()
    }

    fun testRecognitionRefusalPublishesAttemptedStampWithoutCanonicalization() {
        myFixture.configureByText("Refused.java", "class Refused { }")
        val input = input(AnalysisCoverage(true, false, false))
        val calculated =
            calculate(input, DocumentBracketRecognition.Unavailable(BracketRecognitionRefusal.PAIR_CAPACITY))
        val outcome = stampedOutcome(input, calculated) { _, _, _, _ ->
            error("Refused recognition has no indexes")
        } as AnalysisOutcome.Unavailable
        assertThat(outcome.stamp).isSameAs(input.stamp)
        assertThat(outcome.limit).isEqualTo(AnalysisLimit.PAIR_CAPACITY)
    }

    fun testCompletePayloadCanonicalizesAfterGuidePositionsWithCapturedIdentity() {
        myFixture.configureByText("Complete.java", "{\n  value\n}")
        val input = input(ALL_COVERAGE)
        val pairs = listOf(pair(closeLine = 2)).toPairTable()
        val calls = mutableListOf<String>()
        calls += "calculate"
        val calculated = calculate(
            input,
            DocumentBracketRecognition.Complete(pairs, BraceMatcherAvailability.AVAILABLE),
            documentLineCount = 3,
            guidePositions = { lines ->
                calls += "guides"
                assertThat(lines).isEqualTo(1..2)
                GuidePositionIndex.from(lines.first, 2, {}) { 2 }
            },
        )
        val outcome = stampedOutcome(input, calculated) { effectiveInput, layout, canonicalPairs, indexes ->
            calls += "canonical"
            assertThat(effectiveInput).isSameAs(input)
            assertThat(layout).isEqualTo(IndexLayout.forCoverage(input.coverage))
            assertThat(canonicalPairs).isSameAs(pairs)
            assertThat(indexes.guidePositions).isNotNull()
            indexes
        } as AnalysisOutcome.Complete
        assertThat(calls).containsExactly("calculate", "guides", "canonical")
        assertThat(outcome.stamp).isSameAs(input.stamp)
        assertThat(outcome.snapshot.matcherAvailability).isEqualTo(BraceMatcherAvailability.AVAILABLE)
    }

    fun testLimitedPayloadCanonicalizesEffectiveCoverageAndPreservesAttemptedStamp() {
        myFixture.configureByText("Limited.java", "{ value }")
        val input = input(ALL_COVERAGE)
        val pairs = listOf(pair(closeLine = 1_048_576)).toPairTable()
        var canonicalizationCalled = false
        val calculated = calculate(
            input,
            DocumentBracketRecognition.Complete(pairs, BraceMatcherAvailability.AVAILABLE),
            documentLength = 16,
            documentLineCount = 1_048_577,
        )
        val outcome = stampedOutcome(input, calculated) { effectiveInput, layout, canonicalPairs, indexes ->
            canonicalizationCalled = true
            assertThat(effectiveInput.coverage).isEqualTo(input.coverage.withoutGuidePosition())
            assertThat(input.stamp.covers(effectiveInput.stamp)).isTrue()
            assertThat(layout).isEqualTo(IndexLayout.forCoverage(effectiveInput.coverage))
            assertThat(canonicalPairs).isSameAs(pairs)
            assertThat(indexes.guidePositions).isNull()
            indexes
        } as AnalysisOutcome.Limited
        assertThat(canonicalizationCalled).isTrue()
        assertThat(outcome.stamp).isSameAs(input.stamp)
        assertThat(outcome.snapshot.stamp.coverage).isEqualTo(input.coverage.withoutGuidePosition())
        assertThat(outcome.limit).isEqualTo(AnalysisLimit.GUIDE_CAPACITY)
    }

    private fun calculate(
        input: AnalysisInput,
        recognition: DocumentBracketRecognition,
        documentLength: Int = myFixture.editor.document.textLength,
        documentLineCount: Int = myFixture.editor.document.lineCount,
        guidePositions: (IntRange) -> GuidePositionIndex? = { error("No guide positions should be requested") },
    ): CalculatedAnalysis {
        val prepared = SnapshotCalculation.prepare(input.coverage, recognition, documentLength, documentLineCount, {})
        return prepared.finish(prepared.guideLines?.let(guidePositions))
    }

    private fun input(coverage: AnalysisCoverage): AnalysisInput =
        AnalysisInput(myFixture.editor, myFixture.file.fileType, coverage, emptySet())

    private fun pair(closeLine: Int): BracketPair = BracketPair(0, 1, 8, 1, 0, 0, closeLine)

    private companion object {
        val ALL_COVERAGE = AnalysisCoverage(true, true, true)
    }
}
