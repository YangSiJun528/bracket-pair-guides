package com.sijunyang.bracketpairguides.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AnalysisInputTest : BasePlatformTestCase() {
    fun testDefensivelyCopiesDisabledLanguageIds() {
        myFixture.configureByText("Copy.java", "class Copy { }")
        val disabled = mutableSetOf("JAVA")
        val input = AnalysisInput(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            coverage = AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = false,
            ),
            disabledLanguageIds = disabled,
        )

        disabled.clear()
        disabled += "KOTLIN"

        assertEquals(setOf("JAVA"), input.disabledLanguageIds)
    }
}
