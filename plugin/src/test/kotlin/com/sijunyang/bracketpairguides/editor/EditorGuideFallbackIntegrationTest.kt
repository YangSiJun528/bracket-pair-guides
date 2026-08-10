package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.FakeBracketSnapshot
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuidePreferences
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.junit.Assert.assertEquals

class EditorGuideFallbackIntegrationTest : BasePlatformTestCase() {
    fun testGuideSettingUsesBoundedProvisionalPositionUntilBackgroundAnalysis() {
        val body = List(300) { index ->
            if (index == 260) "value" else "        value"
        }.joinToString("\n")
        val source = "{\n$body\n    }"
        myFixture.configureByText("ProvisionalGuideFallback.txt", source)
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
        val initialOptions = BracketGuidePreferences(
            colorBracketTokens = false,
            showActiveGuide = false,
            showActivePairBorder = true,
        )
        val stamp = AnalysisInput(
            editor = editor,
            fileType = myFixture.file.fileType,
            coverage = initialOptions.analysisCoverage(),
            disabledLanguageIds = emptySet(),
        ).stamp
        val result = FakeBracketSnapshot(
            stamp = stamp,
            activePair = { pair },
            guide = { null },
        )
        BracketGuideSettings.getInstance().replace(initialOptions)
        EditorGuideSessions.dispose(editor)
        val session = EditorGuideSessions.install(
            editor = editor,
            visibleRange = { TextRange(0, editor.document.textLength) },
        )
        try {
            session.accept(AnalysisOutcome.Complete(result))
            val guideOptions = initialOptions.copy(showActiveGuide = true)
            BracketGuideSettings.getInstance().replace(guideOptions)
            session.updateOptions(guideOptions, refreshColors = false)

            val guide = checkNotNull(
                editor.observedBracketMarkup().guideMarks.singleOrNull()
                    ?.customRenderer
                    ?.let { it as? BracketGuideDrawing }
                    ?.guide,
            )
            assertEquals(4, guide.guideColumn)
            assertEquals(pair.closeLine, guide.anchorLine)
        } finally {
            EditorGuideSessions.dispose(editor)
        }
    }
}
