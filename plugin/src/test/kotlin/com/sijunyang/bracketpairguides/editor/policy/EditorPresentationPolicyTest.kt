package com.sijunyang.bracketpairguides.editor.policy

import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

internal class EditorPresentationPolicyTest {
    private val options = BracketGuidePreferences(showActivePairBorder = true)

    @Test
    fun `focus and visibility affect presentation without changing analysis coverage`() {
        for (activity in listOf(EditorActivity.ACTIVE, EditorActivity(true, false), EditorActivity.INACTIVE)) {
            val plan = EditorPresentationPolicy.resolve(EditorCapabilities.MAIN, options, activity)
            assertThat(plan.analysis).isEqualTo(AnalysisCoverage(true, true, true))
            assertThat(plan.presentation.colorTokens).isEqualTo(activity.visible)
            assertThat(plan.presentation.highlightActivePair).isEqualTo(activity.visible && activity.active)
            assertThat(plan.presentation.horizontalGuides).isEqualTo(activity.visible && activity.active)
            assertThat(plan.presentation.verticalGuide).isEqualTo(activity.visible && activity.active)
        }
    }

    @Test
    fun `secondary surfaces request colors without pair graphs or guide geometry`() {
        val plan = EditorPresentationPolicy.resolve(EditorCapabilities.COLORS_ONLY, options, EditorActivity.ACTIVE)
        assertThat(plan.analysis).isEqualTo(AnalysisCoverage(true, false, false))
        assertThat(plan.presentation).isEqualTo(PresentationPolicy(true, false, false, false))
    }

    @Test
    fun `unsupported editors never request analysis or presentation`() {
        val plan = EditorPresentationPolicy.resolve(EditorCapabilities.NONE, options, EditorActivity.ACTIVE)
        assertThat(plan.analysis).isEqualTo(AnalysisCoverage(false, false, false))
        assertThat(plan.presentation).isEqualTo(PresentationPolicy(false, false, false, false))
    }

    @Test
    fun `horizontal and vertical preferences remain independent of token colors`() {
        for (horizontal in listOf(false, true)) {
            for (vertical in listOf(false, true)) {
                val preferences = options.copy(showHorizontalGuides = horizontal, showVerticalGuide = vertical)
                val plan = EditorPresentationPolicy.resolve(EditorCapabilities.MAIN, preferences, EditorActivity.ACTIVE)
                assertThat(plan.presentation.colorTokens).isTrue()
                assertThat(plan.presentation.highlightActivePair).isTrue()
                assertThat(plan.presentation.horizontalGuides).isEqualTo(horizontal)
                assertThat(plan.presentation.verticalGuide).isEqualTo(vertical)
                assertThat(plan.analysis.guidePosition).isEqualTo(horizontal || vertical)
            }
        }
    }

    @Test
    fun `one line editors retain horizontal guides and active emphasis`() {
        val plan = EditorPresentationPolicy.resolve(EditorCapabilities.ONE_LINE, options, EditorActivity.ACTIVE)
        assertThat(plan.presentation).isEqualTo(PresentationPolicy(true, true, true, false))
    }

    @Test
    fun `disabling colors or the plugin is never overridden by surface policy`() {
        val noColors = EditorPresentationPolicy.resolve(
            EditorCapabilities.COLORS_ONLY,
            options.copy(colorBracketTokens = false),
            EditorActivity.ACTIVE,
        )
        assertThat(noColors.analysis.pairs).isFalse()
        assertThat(noColors.presentation.colorTokens).isFalse()
        val disabled = EditorPresentationPolicy.resolve(
            EditorCapabilities.MAIN,
            options.copy(enabled = false),
            EditorActivity.ACTIVE,
        )
        assertThat(disabled.analysis.pairs).isFalse()
        assertThat(disabled.presentation).isEqualTo(PresentationPolicy(false, false, false, false))
    }
}
