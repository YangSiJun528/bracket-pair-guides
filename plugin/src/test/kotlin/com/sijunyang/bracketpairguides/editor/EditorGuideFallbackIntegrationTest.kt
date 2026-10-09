package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.highlighting.GuideRepairExecution
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.editor.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.analysisCoverage
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.assertj.core.api.Assertions.assertThat

class EditorGuideFallbackIntegrationTest : BasePlatformTestCase() {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.Default)
    private lateinit var repairExecution: GuideRepairExecution

    override fun setUp() {
        super.setUp()
        repairExecution = GuideRepairExecution(scope)
    }

    override fun tearDown() {
        try {
            if (::repairExecution.isInitialized) Disposer.dispose(repairExecution)
            lifetime.cancel()
            PlatformTestUtil.waitWithEventsDispatching(
                "fallback guide repair workers stop",
                { lifetime.children.none() },
                30,
            )
        } finally {
            super.tearDown()
        }
    }

    fun testGuideSettingHidesImmediatelyThenBackgroundRepairRestoresBoundedProvisionalPosition() {
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
                activity = EditorActivity.ACTIVE,
                capabilities = EditorCapabilities.MAIN,
                editor = editor,
                visibleRange = { TextRange(0, editor.document.textLength) },
                preferences = initialOptions,
                requestRepair = { request, isCurrent, publish ->
                    repairExecution.request(editor, request, isCurrent, publish)
                },
            )
        try {
            session.accept(AnalysisOutcome.Complete(result))
            val guideOptions = initialOptions.copy(showActiveGuide = true)
            BracketGuideSettings.getInstance().replace(guideOptions)
            session.updateOptions(guideOptions, refreshColors = false)

            assertThat(editor.observedBracketMarkup().guideMarks).isEmpty()
            PlatformTestUtil.waitWithEventsDispatching(
                "actual background repair publishes the provisional guide",
                { currentGuide() != null },
                30,
            )
            val guide = checkNotNull(currentGuide())
            assertThat(guide.guideColumn).isEqualTo(4)
            assertThat(guide.anchorLine).isEqualTo(pair.closeLine)
        } finally {
            EditorGuideSessions.dispose(editor)
        }
    }

    private fun currentGuide(): BracketGuide? = myFixture.editor.observedBracketMarkup()
        .guideMarks.singleOrNull()?.customRenderer?.let { it as? BracketGuideDrawing }?.guide
}
