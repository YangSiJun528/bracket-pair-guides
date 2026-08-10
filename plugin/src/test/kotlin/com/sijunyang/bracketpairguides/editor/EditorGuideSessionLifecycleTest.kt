package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.FakeBracketAnalysis
import com.sijunyang.bracketpairguides.analysis.FakeBracketSnapshot
import com.sijunyang.bracketpairguides.editor.highlighting.BracketGuideHighlightingPass
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuidePreferences
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class EditorGuideSessionLifecycleTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BracketGuideSettings.getInstance().loadState(BracketGuidePreferences())
    }

    fun testHighlighterReplacementClearsPresentationFromPreviousSemantics() {
        val source = "class Sample { content }"
        myFixture.configureByText("Sample.java", source)
        val editor = myFixture.editor
        val pair = BracketPair(
            openOffset = source.indexOf('{'),
            openTokenLength = 1,
            closeOffset = source.indexOf('}'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
        editor.caretModel.moveToOffset(source.indexOf("content"))
        val fakeAnalysis = FakeBracketAnalysis(
            pairs = { _, _ -> listOf(pair) },
        )
        val pass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = fakeAnalysis::analyze,
            visibleRange = { current ->
                TextRange(0, current.document.textLength)
            },
        )
        ReadAction.compute<Unit, RuntimeException> {
            pass.doCollectInformation(EmptyProgressIndicator())
        }
        pass.doApplyInformationToEditor()
        val session = checkNotNull(EditorGuideSessions.get(editor))
        assertEquals(1, editor.observedBracketMarkup().guideMarks.size)
        assertEquals(2, editor.observedBracketMarkup().tokenMarks.size)

        (editor as EditorEx).setHighlighter(
            EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(project, PlainTextFileType.INSTANCE),
        )
        session.visibleAreaChanged()

        assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())
        editor.caretModel.moveToOffset(source.indexOf("content") + 1)
        assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())
    }

    fun testDocumentChangeWithoutSnapshotDoesNotCreateActivePresentation() {
        myFixture.configureByText("NoSnapshot.txt", "{ value }")
        val editor = myFixture.editor
        BracketGuideSettings.getInstance().replace(
            BracketGuidePreferences(showActivePairBorder = true),
        )
        EditorGuideEvents.ensureInitialized()
        EditorGuideSessions.dispose(editor)
        EditorGuideSessions.install(
            editor = editor,
            visibleRange = { TextRange(0, editor.document.textLength) },
        )

        try {
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(2, "x")
            }
            editor.caretModel.moveToOffset(4)

            assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())
        } finally {
            EditorGuideSessions.dispose(editor)
        }
    }

    fun testSplitDocumentKeepsAdjustedPresentationInBothEditors() {
        val document = EditorFactory.getInstance().createDocument("{ value }")
        val firstEditor = EditorFactory.getInstance().createEditor(document, project)
        val secondEditor = EditorFactory.getInstance().createEditor(document, project)
        val options = BracketGuidePreferences(showActivePairBorder = true)
        BracketGuideSettings.getInstance().replace(options)
        EditorGuideEvents.ensureInitialized()
        val pair = BracketPair(0, 1, 8, 1, 0, 0, 0)
        try {
            val sessions = listOf(firstEditor, secondEditor).map { editor ->
                editor.caretModel.moveToOffset(3)
                val session = EditorGuideSessions.install(
                    editor = editor,
                    visibleRange = { TextRange(0, document.textLength) },
                )
                session.accept(
                    AnalysisOutcome.Complete(
                        FakeBracketSnapshot(
                            stamp = AnalysisInput(
                                editor = editor,
                                fileType = PlainTextFileType.INSTANCE,
                                coverage = options.analysisCoverage(),
                                disabledLanguageIds = emptySet(),
                            ).stamp,
                            activePair = { pair },
                        ),
                    ),
                )
                session
            }
            assertEquals(
                listOf(2, 2),
                listOf(firstEditor, secondEditor).map {
                    it.observedBracketMarkup().activePairMarks.size
                },
            )

            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(0, "x")
            }

            assertEquals(2, sessions.size)
            for (editor in listOf(firstEditor, secondEditor)) {
                assertEquals(
                    listOf(1, 9),
                    editor.observedBracketMarkup().activePairMarks
                        .map { it.startOffset }
                        .sorted(),
                )
            }
        } finally {
            EditorGuideSessions.dispose(firstEditor)
            EditorGuideSessions.dispose(secondEditor)
            EditorFactory.getInstance().releaseEditor(firstEditor)
            EditorFactory.getInstance().releaseEditor(secondEditor)
        }
    }

    fun testLanguageChangeClearsPresentationUntilANewSnapshotArrives() {
        val source = "{ value }"
        myFixture.configureByText("LanguageChange.txt", source)
        val editor = myFixture.editor
        val options = BracketGuidePreferences(showActivePairBorder = true)
        BracketGuideSettings.getInstance().replace(options)
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val pair = BracketPair(0, 1, source.lastIndex, 1, 0, 0, 0)
        val fakeAnalysis = FakeBracketAnalysis(pairs = { _, _ -> listOf(pair) })
        val pass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = fakeAnalysis::analyze,
            visibleRange = { current ->
                TextRange(0, current.document.textLength)
            },
        )
        ReadAction.compute<Unit, RuntimeException> {
            pass.doCollectInformation(EmptyProgressIndicator())
        }
        pass.doApplyInformationToEditor()
        val session = checkNotNull(EditorGuideSessions.get(editor))
        assertTrue(editor.observedBracketMarkup().allMarks.isNotEmpty())

        session.updateOptions(
            options.copy(disabledLanguageIds = setOf("changed.language")),
            refreshColors = false,
        )
        editor.caretModel.moveToOffset(source.indexOf("value") + 1)

        assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())
    }
}
