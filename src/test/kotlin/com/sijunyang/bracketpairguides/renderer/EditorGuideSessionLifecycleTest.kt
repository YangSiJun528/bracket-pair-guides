package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

class EditorGuideSessionLifecycleTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        PluginSettings.getInstance().loadState(PluginSettings.State())
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
        var staleResolverCalls = 0
        val pass = GuideLineHighlightingPass(
            project = project,
            editor = editor,
            pairProvider = BracketPairProvider { listOf(pair) },
            activePairResolver = ActiveBracketPairResolver { _, _ ->
                staleResolverCalls++
                ActiveBracketPairResolution.Complete(pair)
            },
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

        assertEquals(0, staleResolverCalls)
        assertNull(session.activeGuide)
        assertEquals(0, session.tokenDecorations.entries.size)

        editor.caretModel.moveToOffset(source.indexOf("content") + 1)
        assertEquals(0, staleResolverCalls)
    }

    fun testDocumentChangesSkipImmediateResolutionWithoutActivePresentation() {
        myFixture.configureByText("TokenOnly.txt", "{ value }")
        val editor = myFixture.editor
        EditorGuideSession.dispose(editor)
        var resolverCalls = 0
        val session = EditorGuideSession.install(
            editor = editor,
            resolver = ActiveBracketPairResolver { _, _ ->
                resolverCalls++
                ActiveBracketPairResolution.Complete(null)
            },
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

            assertEquals(0, resolverCalls)
            assertNull(session.activeGuide)
            assertEquals(0, session.activePairHighlights.size)
        } finally {
            EditorGuideSession.dispose(editor)
        }
    }
}
