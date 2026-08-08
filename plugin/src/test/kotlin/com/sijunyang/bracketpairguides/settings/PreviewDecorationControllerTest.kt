package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.renderer.AnalysisCapabilities
import com.sijunyang.bracketpairguides.renderer.AnalysisSnapshotBuilder
import com.sijunyang.bracketpairguides.renderer.AnalysisStamp
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PreviewDecorationControllerTest : BasePlatformTestCase() {
    fun testRecognitionRefreshesHighlighterIdentityBeforeLaterUiUpdates() {
        val source = "{\n  value\n}"
        myFixture.configureByText("ChangedPreview.txt", source)
        val editor = myFixture.editor as EditorEx
        val controller = PreviewDecorationController(editor) {
            TextRange(0, source.length)
        }
        try {
            editor.highlighter = EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(null, PlainTextFileType.INSTANCE)
            val snapshot = AnalysisSnapshotBuilder.build(
                editor = editor,
                pairProvider = BracketPairProvider {
                    listOf(
                        BracketPair(
                            openOffset = 0,
                            openTokenLength = 1,
                            closeOffset = source.lastIndex,
                            closeTokenLength = 1,
                            depth = 0,
                            openLine = 0,
                            closeLine = 2,
                        ),
                    )
                },
                stamp = AnalysisStamp.current(editor, AnalysisCapabilities.PREVIEW),
                progress = EmptyProgressIndicator(),
            )

            controller.updateRecognition(snapshot)
            assertTrue(tokenHighlighters().isNotEmpty())

            controller.updateOptions(PluginOptions(guideLineWidth = 2))
            controller.visibleAreaChanged()

            assertTrue(tokenHighlighters().isNotEmpty())
        } finally {
            controller.dispose()
        }
    }

    fun testMovesABoundedTokenWindowWithThePreviewViewport() {
        val pairCount = 10_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("LongPreview.txt", source)
        val editor = myFixture.editor
        val pairs = List(pairCount) { index ->
            val openOffset = index * 2
            BracketPair(openOffset, 1, openOffset + 1, 1, 0, 0, 0)
        }
        var visibleRange = TextRange(0, 512)
        val controller = PreviewDecorationController(editor) { visibleRange }
        try {
            val snapshot = AnalysisSnapshotBuilder.build(
                editor = editor,
                pairProvider = BracketPairProvider { pairs },
                stamp = AnalysisStamp.current(editor, AnalysisCapabilities.PREVIEW),
                progress = EmptyProgressIndicator(),
            )
            controller.updateRecognition(snapshot)

            val firstWindow = tokenHighlighters()
            assertTrue(firstWindow.isNotEmpty())
            assertTrue(firstWindow.all { it.startOffset < source.length / 2 })
            assertTrue(firstWindow.size < 2_000)

            visibleRange = TextRange(source.length - 512, source.length)
            controller.visibleAreaChanged()

            val lastWindow = tokenHighlighters()
            assertTrue(lastWindow.isNotEmpty())
            assertTrue(lastWindow.all { it.startOffset > source.length / 2 })
            assertTrue(lastWindow.size < 2_000)
        } finally {
            controller.dispose()
        }
    }

    private fun tokenHighlighters(): List<RangeHighlighter> =
        myFixture.editor.markupModel.allHighlighters.filter { highlighter ->
            highlighter.textAttributesKey in BracketColorPalette.LEVEL_KEYS
        }
}
