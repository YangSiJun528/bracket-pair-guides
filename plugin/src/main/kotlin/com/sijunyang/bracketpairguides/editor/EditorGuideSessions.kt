package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings

/** Editor-owned session registry and lifecycle boundary. */
internal object EditorGuideSessions {
    private val KEY = Key.create<EditorGuideSession>("bracket.pair.guides.editor.session")

    fun install(
        editor: Editor,
        visibleRange: (Editor) -> TextRange,
    ): EditorGuideSession {
        assertEdt()
        val existing = editor.getUserData(KEY)
        if (existing != null) return existing
        return EditorGuideSession(
            editor,
            visibleRange,
            BracketGuideSettings.getInstance().options,
        ).also {
            editor.putUserData(KEY, it)
        }
    }

    /** The only session query allowed from a background highlighting pass. */
    fun hasAcceptedAnalysis(
        editor: Editor,
        required: AnalysisStamp,
        includeIdeSizeRefusal: Boolean = true,
    ): Boolean = editor.getUserData(KEY)?.hasAcceptedAnalysis(
        required,
        includeIdeSizeRefusal,
    ) == true

    fun get(editor: Editor): EditorGuideSession? = editor.getUserData(KEY)

    fun dispose(editor: Editor): Unit {
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
