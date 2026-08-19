package com.sijunyang.bracketpairguides.analysis.snapshot

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.pairing.BracketRecognitionRefusal
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBracketRecognition
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import org.assertj.core.api.Assertions.assertThat

class SnapshotAssemblyTest : BasePlatformTestCase() {
    fun testEmptyCoverageSkipsRecognition() {
        myFixture.configureByText("NoCoverage.java", "class NoCoverage { }")
        var recognitionCalled = false

        val outcome = assembly(
            coverage = AnalysisCoverage(
                tokens = false,
                activePair = false,
                guidePosition = false,
            ),
            recognize = {
                recognitionCalled = true
                error("Recognition is not part of empty coverage")
            },
        ).outcome()

        assertThat(outcome).isInstanceOf(AnalysisOutcome.Complete::class.java)
        assertThat(recognitionCalled).isFalse()
    }

    fun testRecognitionRefusalPublishesNoSnapshot() {
        myFixture.configureByText("Refused.java", "class Refused { }")
        var canonicalizationCalled = false
        val input = input(
            AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = false,
            ),
        )

        val outcome = SnapshotAssembly(
            input = input,
            recognize = {
                DocumentBracketRecognition.Unavailable(
                    BracketRecognitionRefusal.PAIR_CAPACITY,
                )
            },
            checkCanceled = {},
            documentLength = myFixture.editor.document.textLength,
            documentLineCount = myFixture.editor.document.lineCount,
            guidePositions = { error("Guide positions were not requested") },
            canonicalIndexes = { _, _, _, indexes ->
                canonicalizationCalled = true
                indexes
            },
        ).outcome()

        assertThat(outcome)
            .isInstanceOfSatisfying(AnalysisOutcome.Unavailable::class.java) { unavailable ->
                assertThat(unavailable.limit).isEqualTo(AnalysisLimit.PAIR_CAPACITY)
            }
        assertThat(canonicalizationCalled).isFalse()
    }

    fun testEmptyRecognitionPreservesUnavailableMatcherState() {
        myFixture.configureByText("Unsupported.txt", "value")

        val outcome = assembly(
            coverage = AnalysisCoverage(
                tokens = true,
                activePair = true,
                guidePosition = true,
            ),
            recognize = {
                DocumentBracketRecognition.Complete(
                    PairTable.empty(),
                    BraceMatcherAvailability.UNAVAILABLE,
                )
            },
        ).outcome() as AnalysisOutcome.Complete

        assertThat(outcome.snapshot.matcherAvailability)
            .isEqualTo(BraceMatcherAvailability.UNAVAILABLE)
    }

    private fun assembly(
        coverage: AnalysisCoverage,
        recognize: () -> DocumentBracketRecognition,
    ): SnapshotAssembly {
        val input = input(coverage)
        return SnapshotAssembly(
            input = input,
            recognize = recognize,
            checkCanceled = {},
            documentLength = myFixture.editor.document.textLength,
            documentLineCount = myFixture.editor.document.lineCount,
            guidePositions = { error("Guide positions were not requested") },
            canonicalIndexes = { _, _, _, indexes -> indexes },
        )
    }

    private fun input(coverage: AnalysisCoverage): AnalysisInput = AnalysisInput(
        editor = myFixture.editor,
        fileType = myFixture.file.fileType,
        coverage = coverage,
        disabledLanguageIds = emptySet(),
    )
}
