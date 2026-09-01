package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.analysisCoverage
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.assertj.core.api.Assertions.assertThat

class EditorGuideFallbackIntegrationTest : BasePlatformTestCase() {
    fun testGuideSettingUsesBoundedProvisionalPositionUntilBackgroundAnalysis() {
        val body =
            List(300) { index ->
                if (index == 260) "value" else "        value"
            }.joinToString("\n")
        val source = "{\n$body\n    }"
        myFixture.configureByText("ProvisionalGuideFallback.txt", source)
        val editor = myFixture.editor
        val pair =
            BracketPair(
                openOffset = source.indexOf('{'),
                openTokenLength = 1,
                closeOffset = source.lastIndexOf('}'),
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 301,
            )
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val initialOptions =
            BracketGuidePreferences(
                colorBracketTokens = false,
                showActiveGuide = false,
                showActivePairBorder = true,
            )
        val input =
            AnalysisInput(
                editor = editor,
                fileType = myFixture.file.fileType,
                coverage = initialOptions.analysisCoverage(),
                disabledLanguageIds = emptySet(),
            )
        val result = input.bracketSnapshot(listOf(pair))
        BracketGuideSettings.getInstance().replace(initialOptions)
        EditorGuideSessions.dispose(editor)
        val session =
            EditorGuideSessions.install(
                editor = editor,
                visibleRange = { TextRange(0, editor.document.textLength) },
                preferences = initialOptions,
            )
        try {
            session.accept(AnalysisOutcome.Complete(result))
            val guideOptions = initialOptions.copy(showActiveGuide = true)
            BracketGuideSettings.getInstance().replace(guideOptions)
            session.updateOptions(guideOptions, refreshColors = false)

            val guide =
                checkNotNull(
                    editor
                        .observedBracketMarkup()
                        .guideMarks
                        .singleOrNull()
                        ?.customRenderer
                        ?.let { it as? BracketGuideDrawing }
                        ?.guide,
                )
            assertThat(guide.guideColumn).isEqualTo(4)
            assertThat(guide.anchorLine).isEqualTo(pair.closeLine)
        } finally {
            EditorGuideSessions.dispose(editor)
        }
    }
}
