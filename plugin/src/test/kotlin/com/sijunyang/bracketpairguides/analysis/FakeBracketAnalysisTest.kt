package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FakeBracketAnalysisTest : BasePlatformTestCase() {
    fun testActivePairTieBreakMatchesTheProductionCloseEndRule() {
        myFixture.configureByText("Fake.java", "class Fake { value }   ")
        val wider = BracketPair(
            openOffset = 0,
            openTokenLength = 1,
            closeOffset = 10,
            closeTokenLength = 3,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
        val narrower = wider.copy(closeTokenLength = 1, depth = 1)
        val input = AnalysisInput(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            coverage = AnalysisCoverage(
                tokens = false,
                activePair = true,
                guidePosition = false,
            ),
            disabledLanguageIds = emptySet(),
        )
        val result = FakeBracketAnalysis(
            pairs = { _, _ -> listOf(wider, narrower) },
        ).analyze(input, EmptyProgressIndicator()).requireSnapshot()

        assertSame(narrower, result.activePairAt(5))
    }
}
