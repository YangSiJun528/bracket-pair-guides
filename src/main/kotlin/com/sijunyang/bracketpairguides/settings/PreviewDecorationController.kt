package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.renderer.AnalysisSnapshot
import com.sijunyang.bracketpairguides.renderer.ActiveBracketPairResolver
import com.sijunyang.bracketpairguides.renderer.DocumentChange
import com.sijunyang.bracketpairguides.renderer.EditorGuideSession
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.util.TextRange

/** Applies draft options to a detached editor session without touching persisted settings. */
internal class PreviewDecorationController(
    private val editor: Editor,
    private val visibleRangeProvider: (Editor) -> TextRange = Editor::calculateVisibleRange,
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
        updateOptions(options, refreshColors = false)
    }

    fun updateOptions(options: PluginOptions, refreshColors: Boolean) {
        session.updateOptions(
            options,
            resolveImmediately = !refreshColors,
            refreshColors = refreshColors,
        )
    }

    fun updateRecognition(snapshot: AnalysisSnapshot) {
        tryUpdateRecognition(snapshot)
    }

    fun tryUpdateRecognition(snapshot: AnalysisSnapshot): Boolean {
        val dependenciesAreCurrent = session.updateDependenciesIfCurrent(
            ActiveBracketPairResolver.NONE,
            visibleRangeProvider,
            snapshot.stamp,
        )
        if (!dependenciesAreCurrent) return false
        session.accept(snapshot)
        return true
    }

    fun requiresRecognitionRefresh(): Boolean = session.requiresAnalysisRefresh()

    fun caretMoved() {
        session.caretMoved()
    }

    fun documentChanged(change: DocumentChange) {
        session.documentChanged(change)
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
