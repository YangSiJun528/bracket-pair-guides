package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.AnalysisSnapshotBuilder
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.BracketPairProvider
import com.sijunyang.bracketpairguides.presentation.ActivePairDecoration
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

class GuidePositionFallbackTest : BasePlatformTestCase() {
    fun testSnapshotWithOutOfRangePositionIndexUsesTheBoundedActiveGuideResolver() {
        val body = List(300) { index ->
            if (index == 260) "value" else "        value"
        }.joinToString("\n")
        val source = "{\n$body\n    }"
        myFixture.configureByText("OversizedGuideFallback.txt", source)
        val editor = myFixture.editor
        val pair = BracketPair(
            openOffset = source.indexOf('{'),
            openTokenLength = 1,
            closeOffset = source.lastIndexOf('}'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 301,
        )
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val unrelatedPair = BracketPair(
            openOffset = editor.document.getLineStartOffset(260),
            openTokenLength = 1,
            closeOffset = editor.document.getLineStartOffset(261),
            closeTokenLength = 1,
            depth = 0,
            openLine = 260,
            closeLine = 261,
        )
        val options = PluginOptions(colorBracketTokens = false)
        val stamp = AnalysisStamp.current(editor, options.analysisCapabilities())
        val unrelatedIndex = inReadAction {
            checkNotNull(
                AnalysisSnapshotBuilder.build(
                    editor = editor,
                    pairProvider = BracketPairProvider { listOf(unrelatedPair) },
                    stamp = stamp,
                    progress = EmptyProgressIndicator(),
                ).positionIndex,
            )
        }
        assertEquals(null, unrelatedIndex.guideForOrNull(pair))
        val snapshot = inReadAction {
            AnalysisSnapshotBuilder.build(
                editor = editor,
                pairProvider = BracketPairProvider { listOf(pair) },
                stamp = stamp,
                progress = EmptyProgressIndicator(),
            )
        }.copy(positionIndex = unrelatedIndex)
        val session = EditorGuideSession.detached(
            editor = editor,
            options = options,
            visibleRangeProvider = { TextRange(0, editor.document.textLength) },
        )
        try {
            session.accept(snapshot)

            val guide = checkNotNull(
                ActivePairDecoration.guideOf(session.activeGuide),
            )
            assertEquals(4, guide.guideColumn)
            assertEquals(pair.closeLine, guide.anchorLine)
        } finally {
            session.dispose()
        }
    }

    private fun <T> inReadAction(action: () -> T): T =
        ReadAction.compute<T, RuntimeException>(action)
}
