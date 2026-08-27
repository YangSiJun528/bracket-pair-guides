package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences

/** Editor-owned session registry and lifecycle boundary. */
internal object EditorGuideSessions {
    private val KEY = Key.create<EditorGuideSession>("bracket.pair.guides.editor.session")

    fun install(
        editor: Editor,
        visibleRange: (Editor) -> TextRange,
        stickySourceRanges: (Editor) -> List<TextRange> = { emptyList() },
        preferences: BracketGuidePreferences,
        matcherAvailabilityChanged: (Editor) -> Unit = {},
    ): EditorGuideSession {
        assertEdt()
        val existing = editor.getUserData(KEY)
        if (existing != null) {
            existing.updateMatcherAvailabilityListener(matcherAvailabilityChanged)
            return existing
        }
        return EditorGuideSession(
            editor,
            visibleRange,
            stickySourceRanges,
            preferences,
            matcherAvailabilityChanged,
        ).also {
            editor.putUserData(KEY, it)
        }
    }

    /** The only session query allowed from a background highlighting pass. */
    fun canSkipAnalysis(
        editor: Editor,
        required: AnalysisStamp,
    ): Boolean = editor.getUserData(KEY)?.canSkipAnalysis(required) == true

    fun get(editor: Editor): EditorGuideSession? = editor.getUserData(KEY)

    fun dispose(editor: Editor) {
        val application = ApplicationManager.getApplication()
        if (!application.isDisposed) assertEdt()
        val session = editor.getUserData(KEY)
        editor.putUserData(KEY, null)
        if (application.isDisposed && !application.isDispatchThread) {
            session?.forgetAcceptedAnalysis()
            return
        }
        session?.dispose()
    }

    private fun assertEdt() {
        ApplicationManager.getApplication().assertIsDispatchThread()
    }
}
