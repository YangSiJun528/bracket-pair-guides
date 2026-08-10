package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.editor.EditorFactory
import com.sijunyang.bracketpairguides.settings.BracketGuidePreferences

/** A committed preference transition and its effects on live editor sessions. */
internal data class GuideSettingsChange(
    val previous: BracketGuidePreferences,
    val current: BracketGuidePreferences,
) {
    val isEmpty: Boolean
        get() = previous == current

    val requiresAnalysisRefresh: Boolean
        get() = previous.analysisCoverage() != current.analysisCoverage() ||
            previous.disabledLanguageIds != current.disabledLanguageIds

    fun apply() {
        if (isEmpty) return

        val sessionEditors = EditorFactory.getInstance().allEditors.filter { editor ->
            !editor.isDisposed && EditorGuideSessions.get(editor) != null
        }
        val immediateEditor = EditorGuideEvents.foregroundAmong(sessionEditors)
        for (editor in sessionEditors) {
            EditorGuideSessions.get(editor)?.updateOptions(
                current,
                resolveImmediately = editor === immediateEditor,
                refreshColors = false,
            )
        }
        if (requiresAnalysisRefresh) DaemonRefresh.request()
    }
}
