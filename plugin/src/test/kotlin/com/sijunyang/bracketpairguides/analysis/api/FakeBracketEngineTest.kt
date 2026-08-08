package com.sijunyang.bracketpairguides.analysis.api

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FakeBracketEngineTest : BasePlatformTestCase() {
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
        val request = AnalyzeRequest(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            capabilities = AnalysisCapabilities(
                tokens = false,
                activePair = true,
                guidePosition = false,
            ),
        )
        val result = FakeBracketEngine(
            pairProvider = { _, _ -> listOf(wider, narrower) },
        ).analyze(request, EmptyProgressIndicator())

        assertSame(narrower, result.activePairAt(5))
    }
}
