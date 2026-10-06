package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import java.lang.reflect.Proxy

class AnalysisStampTest : BasePlatformTestCase() {
    fun testGuidePositionsRequireActivePairAnalysis() {
        assertThatIllegalArgumentException().isThrownBy(::invalidGuideCoverage)
    }

    fun testTabSizeDoesNotInvalidateTokenOnlyAnalysis() {
        myFixture.configureByText("TokenStamp.java", "class TokenStamp { }")
        val completed =
            stamp(
                tabSize = 4,
                coverage =
                AnalysisCoverage(
                    tokens = true,
                    activePair = false,
                    guidePosition = false,
                ),
            )
        val required =
            stamp(
                tabSize = 8,
                coverage = completed.coverage,
            )

        assertThat(completed.covers(required)).isTrue()
    }

    fun testTabSizeStillInvalidatesGuidePositionAnalysis() {
        myFixture.configureByText("GuideStamp.java", "class GuideStamp { }")
        val completed =
            stamp(
                tabSize = 4,
                coverage =
                AnalysisCoverage(
                    tokens = false,
                    activePair = true,
                    guidePosition = true,
                ),
            )
        val required =
            stamp(
                tabSize = 8,
                coverage = completed.coverage,
            )

        assertThat(completed.covers(required)).isFalse()
    }

    fun testDifferentHighlighterInstanceInvalidatesTheStamp() {
        myFixture.configureByText("HighlighterStamp.java", "class HighlighterStamp { }")
        val coverage =
            AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = false,
            )

        val completed = stamp(tabSize = 4, coverage = coverage)
        (myFixture.editor as EditorEx).setHighlighter(
            EditorHighlighterFactory
                .getInstance()
                .createEditorHighlighter(project, PlainTextFileType.INSTANCE),
        )
        val required = stamp(tabSize = 4, coverage = coverage)

        assertThat(completed.covers(required)).isFalse()
    }

    fun testChecksDocumentCoverageAndLanguageSelection() {
        myFixture.configureByText("Revision.java", "class Revision { }")
        val coverage =
            AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = false,
            )
        val stamp = stamp(coverage)
        val fileType = myFixture.file.fileType

        assertThat(
            stamp.matchesCurrent(myFixture.editor, fileType, coverage, emptySet()),
        ).isTrue()
        assertThat(
            stamp.matchesCurrent(
                myFixture.editor,
                PlainTextFileType.INSTANCE,
                coverage,
                emptySet(),
            ),
        ).isFalse()
        assertThat(
            stamp.matchesCurrent(
                myFixture.editor,
                fileType,
                coverage.copy(activePair = true),
                emptySet(),
            ),
        ).isFalse()
        assertThat(
            stamp.matchesCurrent(
                myFixture.editor,
                fileType,
                coverage,
                setOf("JAVA"),
            ),
        ).isFalse()

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }

        assertThat(
            stamp.matchesCurrent(myFixture.editor, fileType, coverage, emptySet()),
        ).isFalse()
    }

    fun testIgnoresTabSizeOnlyWhenGuidePositionsAreNotRequired() {
        myFixture.configureByText("Tabs.java", "class Tabs { }")
        val editor = myFixture.editor
        val originalTabSize = editor.settings.getTabSize(project)
        val tokenCoverage =
            AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = false,
            )
        val tokenStamp = stamp(tokenCoverage)
        val fileType = myFixture.file.fileType

        try {
            editor.settings.setTabSize(originalTabSize + 1)
            assertThat(
                tokenStamp.matchesCurrent(
                    editor,
                    fileType,
                    tokenCoverage,
                    emptySet(),
                ),
            ).isTrue()

            val guideCoverage =
                AnalysisCoverage(
                    tokens = false,
                    activePair = true,
                    guidePosition = true,
                )
            val guideStamp = stamp(guideCoverage)
            editor.settings.setTabSize(originalTabSize + 2)
            assertThat(
                guideStamp.matchesCurrent(
                    editor,
                    fileType,
                    guideCoverage,
                    emptySet(),
                ),
            ).isFalse()
        } finally {
            editor.settings.setTabSize(originalTabSize)
        }
    }

    fun testRejectsAReplacementHighlighter() {
        myFixture.configureByText("Highlighter.java", "class Highlighter { }")
        val editor = myFixture.editor
        val coverage =
            AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = false,
            )
        val stamp = stamp(coverage)
        val fileType = myFixture.file.fileType

        (editor as EditorEx).setHighlighter(
            EditorHighlighterFactory
                .getInstance()
                .createEditorHighlighter(project, PlainTextFileType.INSTANCE),
        )

        assertThat(stamp.matchesCurrent(editor, fileType, coverage, emptySet())).isFalse()
    }

    fun testStaleCapturedSourceDoesNotAccessHighlighter() {
        myFixture.configureByText("StaleSource.java", "class StaleSource { }")
        val coverage = AnalysisCoverage(tokens = true, activePair = false, guidePosition = false)
        val captured = stamp(coverage)
        val fileType = myFixture.file.fileType
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }
        var highlighterAccesses = 0
        val editor = editorWithHighlighterAccess(myFixture.editor) {
            highlighterAccesses++
            error("Stale document must be rejected before reading its highlighter")
        }

        assertThat(captured.matchesCapturedSource(editor, fileType)).isFalse()
        assertThat(highlighterAccesses).isZero()
    }

    fun testStaleCurrentStampDoesNotAccessHighlighter() {
        myFixture.configureByText("StaleCurrent.java", "class StaleCurrent { }")
        val coverage = AnalysisCoverage(tokens = false, activePair = true, guidePosition = true)
        val captured = stamp(coverage)
        val fileType = myFixture.file.fileType
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }
        var highlighterAccesses = 0
        val editor = editorWithHighlighterAccess(myFixture.editor) {
            highlighterAccesses++
            error("Stale document must be rejected before reading its highlighter")
        }

        assertThat(captured.matchesCurrent(editor, fileType, coverage, emptySet())).isFalse()
        assertThat(highlighterAccesses).isZero()
    }

    fun testCurrentDocumentStillChecksHighlighterIdentity() {
        myFixture.configureByText("CurrentIdentity.java", "class CurrentIdentity { }")
        val coverage = AnalysisCoverage(tokens = true, activePair = false, guidePosition = false)
        val captured = stamp(coverage)
        val fileType = myFixture.file.fileType
        var currentHighlighter = myFixture.editor.highlighter
        var highlighterAccesses = 0
        val editor = editorWithHighlighterAccess(myFixture.editor) {
            highlighterAccesses++
            currentHighlighter
        }

        assertThat(captured.matchesCapturedSource(editor, fileType)).isTrue()
        assertThat(captured.matchesCurrent(editor, fileType, coverage, emptySet())).isTrue()
        assertThat(highlighterAccesses).isEqualTo(2)

        currentHighlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, PlainTextFileType.INSTANCE)

        assertThat(captured.matchesCapturedSource(editor, fileType)).isFalse()
        assertThat(captured.matchesCurrent(editor, fileType, coverage, emptySet())).isFalse()
        assertThat(highlighterAccesses).isEqualTo(4)
    }

    private fun editorWithHighlighterAccess(delegate: Editor, highlighter: () -> EditorHighlighter): Editor =
        Proxy.newProxyInstance(Editor::class.java.classLoader, arrayOf(Editor::class.java)) { _, method, arguments ->
            if (method.name ==
                "getHighlighter"
            ) {
                highlighter()
            } else {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            }
        } as Editor

    private fun stamp(tabSize: Int, coverage: AnalysisCoverage): AnalysisStamp {
        myFixture.editor.settings.setTabSize(tabSize)
        return stamp(coverage, setOf("test.matcher"))
    }

    private fun stamp(coverage: AnalysisCoverage, disabledLanguageIds: Set<String> = emptySet()): AnalysisStamp =
        AnalysisInput(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            coverage = coverage,
            disabledLanguageIds = disabledLanguageIds,
        ).stamp

    private fun invalidGuideCoverage() {
        AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = true,
        )
    }
}
