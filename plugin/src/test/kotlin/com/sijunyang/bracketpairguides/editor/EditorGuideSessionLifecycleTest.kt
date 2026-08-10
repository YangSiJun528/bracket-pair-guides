package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.analysis.ActivePairKnowledge
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.FakeBracketAnalysis
import com.sijunyang.bracketpairguides.editor.highlighting.BracketGuideHighlightingPass
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuidePreferences
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class EditorGuideSessionLifecycleTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BracketGuideSettings.getInstance().loadState(BracketGuidePreferences())
    }

    fun testHighlighterReplacementRejectsTheSearchFromThePreviousSemantics() {
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
            activePair = { ActivePairKnowledge.Known(pair) },
        )
        val pass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            analyze = fakeAnalysis::analyze,
            resolveActivePair = fakeAnalysis::resolveActivePair,
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

        assertEquals(0, fakeAnalysis.activePairCallCount)
        assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())

        editor.caretModel.moveToOffset(source.indexOf("content") + 1)
        assertEquals(0, fakeAnalysis.activePairCallCount)
    }

    fun testDocumentChangesSkipImmediateResolutionWithoutActivePresentation() {
        myFixture.configureByText("TokenOnly.txt", "{ value }")
        val editor = myFixture.editor
        EditorGuideSessions.dispose(editor)
        val fakeAnalysis = FakeBracketAnalysis()
        val session = EditorGuideSessions.install(
            editor = editor,
            resolveActivePair = fakeAnalysis::resolveActivePair,
            visibleRange = { currentEditor ->
                TextRange(0, currentEditor.document.textLength)
            },
        )
        try {
            session.updateOptions(
                BracketGuidePreferences(enabled = false),
                resolveImmediately = true,
                refreshColors = false,
            )
            session.documentChanged(
                DocumentChange(offset = 2, mayAffectGuidePosition = true),
                resolveImmediately = true,
            )

            session.updateOptions(
                BracketGuidePreferences(
                    colorBracketTokens = true,
                    showActiveGuide = false,
                    showActivePairBorder = false,
                    showActivePairBackground = false,
                ),
                resolveImmediately = true,
                refreshColors = false,
            )
            session.documentChanged(
                DocumentChange(offset = 3, mayAffectGuidePosition = true),
                resolveImmediately = true,
            )

            assertEquals(0, fakeAnalysis.activePairCallCount)
            assertTrue(editor.observedBracketMarkup().guideMarks.isEmpty())
            assertTrue(editor.observedBracketMarkup().activePairMarks.isEmpty())
        } finally {
            EditorGuideSessions.dispose(editor)
        }
    }

    fun testSplitDocumentRunsOnlyOneImmediateSearch() {
        val document = EditorFactory.getInstance().createDocument("{ value }")
        val firstEditor = EditorFactory.getInstance().createEditor(document, project)
        val secondEditor = EditorFactory.getInstance().createEditor(document, project)
        val firstAnalysis = FakeBracketAnalysis()
        val secondAnalysis = FakeBracketAnalysis()
        try {
            EditorGuideSessions.install(
                editor = firstEditor,
                resolveActivePair = firstAnalysis::resolveActivePair,
                visibleRange = { TextRange(0, document.textLength) },
            )
            EditorGuideSessions.install(
                editor = secondEditor,
                resolveActivePair = secondAnalysis::resolveActivePair,
                visibleRange = { TextRange(0, document.textLength) },
            )

            DocumentChangeRoute.deliver(
                editors = listOf(firstEditor, secondEditor),
                change = DocumentChange(offset = 2, mayAffectGuidePosition = true),
                foregroundEditor = secondEditor,
            )

            assertEquals(0, firstAnalysis.activePairCallCount)
            assertEquals(1, secondAnalysis.activePairCallCount)
        } finally {
            EditorGuideSessions.dispose(firstEditor)
            EditorGuideSessions.dispose(secondEditor)
            EditorFactory.getInstance().releaseEditor(firstEditor)
            EditorFactory.getInstance().releaseEditor(secondEditor)
        }
    }

    fun testSecondaryCaretMovementDoesNotRepeatPrimaryResolution() {
        myFixture.configureByText("MultipleCarets.txt", "{ first second }")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(3)
        val fakeAnalysis = FakeBracketAnalysis()
        EditorGuideEvents.ensureInitialized()
        EditorGuideSessions.dispose(editor)
        EditorGuideSessions.install(
            editor = editor,
            resolveActivePair = fakeAnalysis::resolveActivePair,
            visibleRange = { TextRange(0, editor.document.textLength) },
        )
        try {
            val addedCaret = checkNotNull(
                editor.caretModel.addCaret(editor.offsetToVisualPosition(9)),
            )
            assertSame(addedCaret, editor.caretModel.primaryCaret)
            val secondaryCaret = editor.caretModel.allCarets.single {
                it !== editor.caretModel.primaryCaret
            }
            val callsAfterPrimarySelection = fakeAnalysis.activePairCallCount

            secondaryCaret.moveToOffset(6)

            assertEquals(callsAfterPrimarySelection, fakeAnalysis.activePairCallCount)
            editor.caretModel.primaryCaret.moveToOffset(10)
            assertEquals(callsAfterPrimarySelection + 1, fakeAnalysis.activePairCallCount)
        } finally {
            EditorGuideSessions.dispose(editor)
        }
    }

    fun testBackgroundOptionRefreshSkipsImmediateResolution() {
        myFixture.configureByText("OptionRefresh.txt", "{ value }")
        val editor = myFixture.editor
        val fakeAnalysis = FakeBracketAnalysis()
        EditorGuideSessions.dispose(editor)
        val session = EditorGuideSessions.install(
            editor = editor,
            resolveActivePair = fakeAnalysis::resolveActivePair,
            visibleRange = { TextRange(0, editor.document.textLength) },
        )
        try {
            session.updateOptions(
                BracketGuidePreferences(disabledLanguageIds = setOf("first")),
                resolveImmediately = false,
                refreshColors = false,
            )
            assertEquals(0, fakeAnalysis.activePairCallCount)

            session.updateOptions(
                BracketGuidePreferences(disabledLanguageIds = setOf("second")),
                resolveImmediately = true,
                refreshColors = false,
            )
            assertEquals(1, fakeAnalysis.activePairCallCount)
        } finally {
            EditorGuideSessions.dispose(editor)
        }
    }

    fun testThemeRefreshDoesNotRunImmediateResolution() {
        myFixture.configureByText("ThemeRefresh.txt", "{ value }")
        val editor = myFixture.editor
        val fakeAnalysis = FakeBracketAnalysis()
        EditorGuideSessions.dispose(editor)
        EditorGuideSessions.install(
            editor = editor,
            resolveActivePair = fakeAnalysis::resolveActivePair,
            visibleRange = { TextRange(0, editor.document.textLength) },
        )
        try {
            EditorGuideEvents.ensureInitialized()
            ApplicationManager.getApplication()
                .getService(EditorGuideEvents::class.java)
                .globalSchemeChange(null)

            assertEquals(0, fakeAnalysis.activePairCallCount)
        } finally {
            EditorGuideSessions.dispose(editor)
        }
    }
}
