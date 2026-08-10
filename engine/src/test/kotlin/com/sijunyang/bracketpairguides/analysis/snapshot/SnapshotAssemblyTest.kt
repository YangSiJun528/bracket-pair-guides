package com.sijunyang.bracketpairguides.analysis.snapshot

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.AnalysisOutcome

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

        assertTrue(outcome is AnalysisOutcome.Complete)
        assertFalse(recognitionCalled)
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
                BracketRecognition.Unavailable(AnalysisLimit.PAIR_CAPACITY)
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

        assertTrue(outcome is AnalysisOutcome.Unavailable)
        assertEquals(
            AnalysisLimit.PAIR_CAPACITY,
            (outcome as AnalysisOutcome.Unavailable).limit,
        )
        assertFalse(canonicalizationCalled)
    }

    private fun assembly(
        coverage: AnalysisCoverage,
        recognize: () -> BracketRecognition,
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
