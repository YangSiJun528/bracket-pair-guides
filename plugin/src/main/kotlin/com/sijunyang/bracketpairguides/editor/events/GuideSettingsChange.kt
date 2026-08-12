package com.sijunyang.bracketpairguides.editor.events

import com.intellij.openapi.editor.EditorFactory
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.hasDifferentAnalysisFrom

/** A committed preference transition and its effects on live editor sessions. */
internal data class GuideSettingsChange(
    val previous: BracketGuidePreferences,
    val current: BracketGuidePreferences,
) {
    val isEmpty: Boolean
        get() = previous == current

    val requiresAnalysisRefresh: Boolean
        get() = current.hasDifferentAnalysisFrom(previous)

    fun apply(
        applyNativeMatchedBraceSetting: (BracketGuidePreferences) -> Unit =
            NativeMatchedBraceHighlighting.getInstance()::apply,
    ) {
        if (isEmpty) return

        applyNativeMatchedBraceSetting(current)

        val sessionEditors = EditorFactory.getInstance().allEditors.filter { editor ->
            !editor.isDisposed && EditorGuideSessions.get(editor) != null
        }
        for (editor in sessionEditors) {
            EditorGuideSessions.get(editor)?.updateOptions(
                current,
                refreshColors = false,
            )
        }
        if (requiresAnalysisRefresh) DaemonRefresh.request()
    }
}
