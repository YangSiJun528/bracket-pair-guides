package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.command.WriteCommandAction
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

    fun testChecksDocumentCoverageAndLanguageSelection() {
        myFixture.configureByText("Revision.java", "class Revision { }")
        val coverage = AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
        val stamp = stamp(coverage)
        val fileType = myFixture.file.fileType

        assertTrue(
            stamp.matchesCurrent(myFixture.editor, fileType, coverage, emptySet()),
        )
        assertFalse(
            stamp.matchesCurrent(
                myFixture.editor,
                PlainTextFileType.INSTANCE,
                coverage,
                emptySet(),
            ),
        )
        assertFalse(
            stamp.matchesCurrent(
                myFixture.editor,
                fileType,
                coverage.copy(activePair = true),
                emptySet(),
            ),
        )
        assertFalse(
            stamp.matchesCurrent(
                myFixture.editor,
                fileType,
                coverage,
                setOf("JAVA"),
            ),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }

        assertFalse(
            stamp.matchesCurrent(myFixture.editor, fileType, coverage, emptySet()),
        )
    }

    fun testIgnoresTabSizeOnlyWhenGuidePositionsAreNotRequired() {
        myFixture.configureByText("Tabs.java", "class Tabs { }")
        val editor = myFixture.editor
        val originalTabSize = editor.settings.getTabSize(project)
        val tokenCoverage = AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
        val tokenStamp = stamp(tokenCoverage)
        val fileType = myFixture.file.fileType

        try {
            editor.settings.setTabSize(originalTabSize + 1)
            assertTrue(
                tokenStamp.matchesCurrent(
                    editor,
                    fileType,
                    tokenCoverage,
                    emptySet(),
                ),
            )

            val guideCoverage = AnalysisCoverage(
                tokens = false,
                activePair = true,
                guidePosition = true,
            )
            val guideStamp = stamp(guideCoverage)
            editor.settings.setTabSize(originalTabSize + 2)
            assertFalse(
                guideStamp.matchesCurrent(
                    editor,
                    fileType,
                    guideCoverage,
                    emptySet(),
                ),
            )
        } finally {
            editor.settings.setTabSize(originalTabSize)
        }
    }

    fun testRejectsAReplacementHighlighter() {
        myFixture.configureByText("Highlighter.java", "class Highlighter { }")
        val editor = myFixture.editor
        val coverage = AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
        val stamp = stamp(coverage)
        val fileType = myFixture.file.fileType

        (editor as EditorEx).setHighlighter(
            EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(project, PlainTextFileType.INSTANCE),
        )

        assertFalse(stamp.matchesCurrent(editor, fileType, coverage, emptySet()))
    }

    private fun stamp(
        tabSize: Int,
        coverage: AnalysisCoverage,
    ): AnalysisStamp {
        myFixture.editor.settings.setTabSize(tabSize)
        return stamp(coverage, setOf("test.matcher"))
    }

    private fun stamp(
        coverage: AnalysisCoverage,
        disabledLanguageIds: Set<String> = emptySet(),
    ): AnalysisStamp = AnalysisInput(
        editor = myFixture.editor,
        fileType = myFixture.file.fileType,
        coverage = coverage,
        disabledLanguageIds = disabledLanguageIds,
    ).stamp
}
