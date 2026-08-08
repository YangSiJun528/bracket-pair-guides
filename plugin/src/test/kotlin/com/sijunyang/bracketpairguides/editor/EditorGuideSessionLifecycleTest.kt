package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.analysis.api.ActivePairResult
import com.sijunyang.bracketpairguides.analysis.api.BracketPair
import com.sijunyang.bracketpairguides.analysis.api.FakeBracketEngine
import com.sijunyang.bracketpairguides.editor.highlighting.GuideLineHighlightingPass
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.PluginSettings
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame

class EditorGuideSessionLifecycleTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        PluginSettings.getInstance().loadState(PluginOptions())
    }

    fun testHighlighterReplacementRejectsTheResolverFromThePreviousSemantics() {
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
        val engine = FakeBracketEngine(
            pairProvider = { _, _ -> listOf(pair) },
            activePairProvider = { ActivePairResult.Complete(pair) },
        )
        val pass = GuideLineHighlightingPass(
            project = project,
            editor = editor,
            engine = engine,
        )
        ReadAction.compute<Unit, RuntimeException> {
            pass.doCollectInformation(EmptyProgressIndicator())
        }
        pass.doApplyInformationToEditor()
        val session = checkNotNull(EditorGuideSession.get(editor))
        assertNotNull(session.activeGuide)
        assertEquals(2, session.tokenDecorations.entries.size)

        (editor as EditorEx).setHighlighter(
            EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(project, PlainTextFileType.INSTANCE),
        )
        session.visibleAreaChanged()

        assertEquals(0, engine.activePairCallCount)
        assertNull(session.activeGuide)
        assertEquals(0, session.tokenDecorations.entries.size)

        editor.caretModel.moveToOffset(source.indexOf("content") + 1)
        assertEquals(0, engine.activePairCallCount)
    }

    fun testDocumentChangesSkipImmediateResolutionWithoutActivePresentation() {
        myFixture.configureByText("TokenOnly.txt", "{ value }")
        val editor = myFixture.editor
        EditorGuideSession.dispose(editor)
        val engine = FakeBracketEngine()
        val session = EditorGuideSession.install(
            editor = editor,
            engine = engine,
            visibleRangeProvider = { currentEditor ->
                TextRange(0, currentEditor.document.textLength)
            },
        )
        try {
            session.updateOptions(PluginOptions(enabled = false))
            session.documentChanged(DocumentChange(offset = 2, mayAffectGuidePosition = true))

            session.updateOptions(
                PluginOptions(
                    colorBracketTokens = true,
                    showActiveGuide = false,
                    showActivePairBorder = false,
                    showActivePairBackground = false,
                ),
            )
            session.documentChanged(DocumentChange(offset = 3, mayAffectGuidePosition = true))

            assertEquals(0, engine.activePairCallCount)
            assertNull(session.activeGuide)
            assertEquals(0, session.activePairHighlights.size)
        } finally {
            EditorGuideSession.dispose(editor)
        }
    }

    fun testSplitDocumentRunsOnlyOneImmediateResolver() {
        val document = EditorFactory.getInstance().createDocument("{ value }")
        val firstEditor = EditorFactory.getInstance().createEditor(document, project)
        val secondEditor = EditorFactory.getInstance().createEditor(document, project)
        val firstEngine = FakeBracketEngine()
        val secondEngine = FakeBracketEngine()
        try {
            EditorGuideSession.install(
                editor = firstEditor,
                engine = firstEngine,
                visibleRangeProvider = { TextRange(0, document.textLength) },
            )
            EditorGuideSession.install(
                editor = secondEditor,
                engine = secondEngine,
                visibleRangeProvider = { TextRange(0, document.textLength) },
            )

            EditorGuideEventRouter.routeDocumentChangeForTest(
                editors = listOf(firstEditor, secondEditor),
                change = DocumentChange(offset = 2, mayAffectGuidePosition = true),
                immediateEditor = secondEditor,
            )

            assertEquals(0, firstEngine.activePairCallCount)
            assertEquals(1, secondEngine.activePairCallCount)
        } finally {
            EditorGuideSession.dispose(firstEditor)
            EditorGuideSession.dispose(secondEditor)
            EditorFactory.getInstance().releaseEditor(firstEditor)
            EditorFactory.getInstance().releaseEditor(secondEditor)
        }
    }

    fun testSecondaryCaretMovementDoesNotRepeatPrimaryResolution() {
        myFixture.configureByText("MultipleCarets.txt", "{ first second }")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(3)
        val engine = FakeBracketEngine()
        EditorGuideEventRouter.ensureInitialized()
        EditorGuideSession.dispose(editor)
        EditorGuideSession.install(
            editor = editor,
            engine = engine,
            visibleRangeProvider = { TextRange(0, editor.document.textLength) },
        )
        try {
            val addedCaret = checkNotNull(
                editor.caretModel.addCaret(editor.offsetToVisualPosition(9)),
            )
            assertSame(addedCaret, editor.caretModel.primaryCaret)
            val secondaryCaret = editor.caretModel.allCarets.single {
                it !== editor.caretModel.primaryCaret
            }
            val callsAfterPrimarySelection = engine.activePairCallCount

            secondaryCaret.moveToOffset(6)

            assertEquals(callsAfterPrimarySelection, engine.activePairCallCount)
            editor.caretModel.primaryCaret.moveToOffset(10)
            assertEquals(callsAfterPrimarySelection + 1, engine.activePairCallCount)
        } finally {
            EditorGuideSession.dispose(editor)
        }
    }

    fun testBackgroundOptionRefreshSkipsImmediateResolution() {
        myFixture.configureByText("OptionRefresh.txt", "{ value }")
        val editor = myFixture.editor
        val engine = FakeBracketEngine()
        EditorGuideSession.dispose(editor)
        val session = EditorGuideSession.install(
            editor = editor,
            engine = engine,
            visibleRangeProvider = { TextRange(0, editor.document.textLength) },
        )
        try {
            session.updateOptions(
                PluginOptions(disabledLanguageIds = setOf("first")),
                resolveImmediately = false,
            )
            assertEquals(0, engine.activePairCallCount)

            session.updateOptions(
                PluginOptions(disabledLanguageIds = setOf("second")),
                resolveImmediately = true,
            )
            assertEquals(1, engine.activePairCallCount)
        } finally {
            EditorGuideSession.dispose(editor)
        }
    }

    fun testThemeRefreshDoesNotRunImmediateResolution() {
        myFixture.configureByText("ThemeRefresh.txt", "{ value }")
        val editor = myFixture.editor
        val engine = FakeBracketEngine()
        EditorGuideSession.dispose(editor)
        EditorGuideSession.install(
            editor = editor,
            engine = engine,
            visibleRangeProvider = { TextRange(0, editor.document.textLength) },
        )
        try {
            EditorGuideEventRouter.ensureInitialized()
            ApplicationManager.getApplication()
                .getService(EditorGuideEventRouter::class.java)
                .globalSchemeChange(null)

            assertEquals(0, engine.activePairCallCount)
        } finally {
            EditorGuideSession.dispose(editor)
        }
    }
}
