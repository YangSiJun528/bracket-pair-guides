package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.renderer.AnalysisSnapshot
import com.sijunyang.bracketpairguides.renderer.EditorGuideSession
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange

/** Applies draft options to a detached editor session without touching persisted settings. */
internal class PreviewDecorationController(
    editor: Editor,
) {
    private val session = EditorGuideSession.detached(
        editor = editor,
        options = PluginOptions(),
        visibleRangeProvider = { current -> TextRange(0, current.document.textLength) },
    )

    fun updateOptions(options: PluginOptions) {
        session.updateOptions(options)
    }

    fun updateRecognition(snapshot: AnalysisSnapshot) {
        session.accept(snapshot)
    }

    fun caretMoved() {
        session.caretMoved()
    }

    fun clearRecognition() {
        session.clear()
    }

    fun dispose() {
        session.dispose()
    }
}
