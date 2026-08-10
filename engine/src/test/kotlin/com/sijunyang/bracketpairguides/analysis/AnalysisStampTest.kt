package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

class AnalysisStampTest : BasePlatformTestCase() {
    fun testGuidePositionsRequireActivePairAnalysis() {
        try {
            AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = true,
            )
            fail("Expected guide positions without active-pair analysis to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected product invariant.
        }
    }

    fun testTabSizeDoesNotInvalidateTokenOnlyAnalysis() {
        myFixture.configureByText("TokenStamp.java", "class TokenStamp { }")
        val completed = stamp(
            tabSize = 4,
            coverage = AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = false,
            ),
        )
        val required = stamp(
            tabSize = 8,
            coverage = completed.coverage,
        )

        assertTrue(completed.covers(required))
    }

    fun testTabSizeStillInvalidatesGuidePositionAnalysis() {
        myFixture.configureByText("GuideStamp.java", "class GuideStamp { }")
        val completed = stamp(
            tabSize = 4,
            coverage = AnalysisCoverage(
                tokens = false,
                activePair = true,
                guidePosition = true,
            ),
        )
        val required = stamp(
            tabSize = 8,
            coverage = completed.coverage,
        )

        assertFalse(completed.covers(required))
    }

    fun testDifferentHighlighterInstanceInvalidatesTheStamp() {
        myFixture.configureByText("HighlighterStamp.java", "class HighlighterStamp { }")
        val coverage = AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )

        val completed = stamp(tabSize = 4, coverage = coverage)
        (myFixture.editor as EditorEx).setHighlighter(
            EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(project, PlainTextFileType.INSTANCE),
        )
        val required = stamp(tabSize = 4, coverage = coverage)

        assertFalse(completed.covers(required))
    }

    private fun stamp(
        tabSize: Int,
        coverage: AnalysisCoverage,
    ): AnalysisStamp {
        myFixture.editor.settings.setTabSize(tabSize)
        return AnalysisInput(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            coverage = coverage,
            disabledLanguageIds = setOf("test.matcher"),
        ).stamp
    }
}
