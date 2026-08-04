package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.renderer.AnalysisSnapshot
import com.sijunyang.bracketpairguides.renderer.EditorGuideSession
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.util.TextRange

/** Applies draft options to a detached editor session without touching persisted settings. */
internal class PreviewDecorationController(
    private val editor: Editor,
    visibleRangeProvider: (Editor) -> TextRange = Editor::calculateVisibleRange,
) {
    private val session = EditorGuideSession.detached(
        editor = editor,
        options = PluginOptions(),
        visibleRangeProvider = visibleRangeProvider,
    )
    private val visibleAreaListener = VisibleAreaListener { visibleAreaChanged() }

    init {
        editor.scrollingModel.addVisibleAreaListener(visibleAreaListener)
    }

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

    internal fun visibleAreaChanged() {
        session.visibleAreaChanged()
    }

    fun dispose() {
        editor.scrollingModel.removeVisibleAreaListener(visibleAreaListener)
        session.dispose()
    }
}
