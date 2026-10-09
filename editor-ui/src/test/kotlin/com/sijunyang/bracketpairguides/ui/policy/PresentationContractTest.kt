package com.sijunyang.bracketpairguides.ui.policy

import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class PresentationContractTest {
    @Test fun `focus changes display without discarding analysis coverage`() {
        val preferences = BracketGuidePreferences(showActivePairBorder = true)
        val focused = EditorPresentationPolicy.resolve(EditorCapabilities.MAIN, preferences, EditorActivity.ACTIVE)
        val unfocused = EditorPresentationPolicy.resolve(
            EditorCapabilities.MAIN,
            preferences,
            EditorActivity(true, false),
        )
        assertThat(unfocused.analysis).isEqualTo(focused.analysis)
        assertThat(unfocused.presentation.colorTokens).isTrue()
        assertThat(unfocused.presentation.highlightActivePair).isFalse()
        assertThat(unfocused.presentation.verticalGuide).isFalse()
        assertThat(preferences.showActivePairBorder).isTrue()
    }

    @Test fun `unsupported surface requests no computation or display`() {
        val plan = EditorPresentationPolicy.resolve(
            EditorCapabilities.NONE,
            BracketGuidePreferences(),
            EditorActivity.ACTIVE,
        )
        assertThat(plan.analysis.pairs).isFalse()
        assertThat(plan.analysis.guidePosition).isFalse()
        assertThat(plan.presentation).isEqualTo(PresentationPolicy(false, false, false, false))
    }
}
