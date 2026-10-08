package com.sijunyang.bracketpairguides.ui.editor.highlighting

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences

/** SDK XML persistence and a fresh advisory's public eligibility across a restart boundary. */
class NativeGuideAdvisoryIdeContractTest : BasePlatformTestCase() {
    fun testSuppressionSurvivesXmlReloadUntilConflictCapabilityEnds() {
        val settings = CodeInsightSettings.getInstance()
        val original = settings.HIGHLIGHT_BRACES
        val first = NativeGuideAdvisory()
        val restarted = NativeGuideAdvisory()
        val restartedAgain = NativeGuideAdvisory()
        try {
            settings.HIGHLIGHT_BRACES = true
            val options = BracketGuidePreferences()
            assertTrue(first.interest(options).enabled)
            // Public persistent-state loading simulates a previously accepted suppression action.
            first.loadState(NativeGuideAdvisory.Suppression(true))
            restarted.loadState(roundTrip(first.state))
            assertTrue(restarted.state.suppressedForCurrentConflict)
            assertFalse(restarted.interest(options).enabled)
            restartedAgain.loadState(roundTrip(restarted.state))
            assertTrue(restartedAgain.state.suppressedForCurrentConflict)
            assertFalse(restartedAgain.interest(options).enabled)

            // An actual capability transition, rather than process restart, ends this episode.
            settings.HIGHLIGHT_BRACES = false
            restartedAgain.changesApplied()
            assertFalse(restartedAgain.state.suppressedForCurrentConflict)
            settings.HIGHLIGHT_BRACES = true
            restartedAgain.changesApplied()
            assertTrue(restartedAgain.interest(options).enabled)
            assertFalse(roundTrip(restartedAgain.state).suppressedForCurrentConflict)
        } finally {
            settings.HIGHLIGHT_BRACES = original
            first.dispose()
            restarted.dispose()
            restartedAgain.dispose()
        }
    }

    private fun roundTrip(state: NativeGuideAdvisory.Suppression): NativeGuideAdvisory.Suppression {
        val xml = JDOMUtil.write(XmlSerializer.serialize(state))
        return XmlSerializer.deserialize(JDOMUtil.load(xml), NativeGuideAdvisory.Suppression::class.java)
    }
}
