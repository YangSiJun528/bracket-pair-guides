package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.api.AnalysisCapabilities
import com.sijunyang.bracketpairguides.analysis.api.AnalyzeRequest
import com.sijunyang.bracketpairguides.analysis.api.BracketPair
import com.sijunyang.bracketpairguides.analysis.api.FakeAnalysisResult
import com.sijunyang.bracketpairguides.presentation.ActivePairDecoration
import com.sijunyang.bracketpairguides.settings.PluginOptions
import org.junit.Assert.assertEquals

class GuidePositionFallbackTest : BasePlatformTestCase() {
    fun testResultWithoutIndexedGuideUsesTheBoundedActiveGuideResolver() {
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
        val options = PluginOptions(colorBracketTokens = false)
        val revision = AnalyzeRequest(
            editor = editor,
            fileType = myFixture.file.fileType,
            capabilities = options.analysisCapabilities(),
        ).revision
        val result = FakeAnalysisResult(
            revision = revision,
            activePairProvider = { pair },
            guideProvider = { null },
        )
        val session = EditorGuideSession.detached(
            editor = editor,
            options = options,
            visibleRangeProvider = { TextRange(0, editor.document.textLength) },
        )
        try {
            session.accept(result)

            val guide = checkNotNull(
                ActivePairDecoration.guideOf(session.activeGuide),
            )
            assertEquals(4, guide.guideColumn)
            assertEquals(pair.closeLine, guide.anchorLine)
        } finally {
            session.dispose()
        }
    }
}
