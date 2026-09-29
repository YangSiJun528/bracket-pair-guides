package com.sijunyang.bracketpairguides.editor.policy

import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.analysisCoverage

/** Stable surface support; independent of editability and transient focus. */
internal data class EditorCapabilities(
    val colorTokens: Boolean,
    val activePair: Boolean,
    val horizontalGuides: Boolean,
    val verticalGuide: Boolean,
) {
    companion object {
        val NONE = EditorCapabilities(false, false, false, false)
        val COLORS_ONLY = EditorCapabilities(true, false, false, false)
        val MAIN = EditorCapabilities(true, true, true, true)
        val ONE_LINE = MAIN.copy(verticalGuide = false)
    }
}

internal data class EditorActivity(val visible: Boolean, val active: Boolean) {
    companion object {
        val INACTIVE = EditorActivity(false, false)
        val ACTIVE = EditorActivity(true, true)
    }
}

internal data class PresentationPolicy(
    val colorTokens: Boolean,
    val highlightActivePair: Boolean,
    val horizontalGuides: Boolean,
    val verticalGuide: Boolean,
) {
    /** A transient rendering view, never written back to persisted preferences. */
    fun applyTo(options: BracketGuidePreferences): BracketGuidePreferences = options.copy(
        colorBracketTokens = colorTokens,
        showActiveGuide = horizontalGuides || verticalGuide,
        showHorizontalGuides = horizontalGuides,
        showVerticalGuide = verticalGuide,
        showActivePairBorder = highlightActivePair && options.showActivePairBorder,
        showActivePairBackground = highlightActivePair && options.showActivePairBackground,
    )
}

internal data class EditorPlan(val analysis: AnalysisCoverage, val presentation: PresentationPolicy)

/** Pure policy: platform state is captured by the host before reaching this boundary. */
internal object EditorPresentationPolicy {
    fun resolve(
        capabilities: EditorCapabilities,
        options: BracketGuidePreferences,
        activity: EditorActivity,
    ): EditorPlan {
        val supported = PresentationPolicy(
            colorTokens = options.enabled && options.colorBracketTokens && capabilities.colorTokens,
            highlightActivePair = options.enabled && options.showsActivePair && capabilities.activePair,
            horizontalGuides = options.enabled && options.showActiveGuide &&
                options.showHorizontalGuides && capabilities.horizontalGuides,
            verticalGuide = options.enabled && options.showActiveGuide &&
                options.showVerticalGuide && capabilities.verticalGuide,
        )
        val active = activity.visible && activity.active
        return EditorPlan(
            analysis = supported.applyTo(options).analysisCoverage(),
            presentation = supported.copy(
                colorTokens = supported.colorTokens && activity.visible,
                highlightActivePair = supported.highlightActivePair && active,
                horizontalGuides = supported.horizontalGuides && active,
                verticalGuide = supported.verticalGuide && active,
            ),
        )
    }
}
