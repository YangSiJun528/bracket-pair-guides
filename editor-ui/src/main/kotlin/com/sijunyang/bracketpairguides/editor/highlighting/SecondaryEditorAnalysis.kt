package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.editor.EditorActivitySource
import com.sijunyang.bracketpairguides.editor.EditorEffectGuard
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.editor.events.EditorGuideEvents
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import java.util.IdentityHashMap

/** Supplies the normal analysis/apply pipeline to editors without daemon passes. */
@Service(Service.Level.APP)
internal class SecondaryEditorAnalysis internal constructor(
    private val activity: (Editor) -> EditorActivity,
    private val visibleRange: (Editor) -> TextRange,
    private val requestAnalysis: (Editor) -> Unit = EditorAnalysisRequests.Companion::request,
    private val cancelAnalysis: (Editor) -> Unit = { editor ->
        EditorAnalysisRequests.cancel(editor)
    },
) : Disposable,
    EditorFactoryListener {
    @Suppress("unused")
    constructor() : this(EditorActivitySource::capture, Editor::calculateVisibleRange)

    private val managedEditors = java.util.Collections.newSetFromMap(IdentityHashMap<Editor, Boolean>())
    init {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val factory = EditorFactory.getInstance()
        factory.addEditorFactoryListener(this, this)
        factory.allEditors.forEach(::attach)
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        attach(event.editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        if (managedEditors.remove(event.editor)) cancelAnalysis(event.editor)
    }

    private fun attach(editor: Editor) {
        if (!EditorEffectGuard.allowsEffects() || editor.isDisposed ||
            editor.editorKind == EditorKind.MAIN_EDITOR
        ) {
            return
        }
        if (!EditorSurfaceClassifier.capabilities(editor).colorTokens) return
        managedEditors.add(editor)
        val session = EditorGuideSessions.install(
            editor = editor,
            visibleRange = visibleRange,
            preferences = BracketGuideSettings.getInstance().options,
            activity = activity(editor),
        )
        session.setAnalysisRefreshRequester { request(editor) }
        EditorGuideEvents.ensureInitialized(editor)
        session.requestAnalysis()
    }

    private fun request(editor: Editor) {
        if (!EditorEffectGuard.allowsEffects() || editor.isDisposed ||
            EditorGuideSessions.get(editor)?.isVisible != true
        ) {
            return
        }
        requestAnalysis(editor)
    }

    override fun dispose() {
        val application = ApplicationManager.getApplication()
        if (!application.isDisposed || application.isDispatchThread) {
            managedEditors.forEach { EditorGuideSessions.get(it)?.setAnalysisRefreshRequester {} }
        }
        managedEditors.forEach(cancelAnalysis)
        managedEditors.clear()
    }

    companion object {
        fun ensureInitialized() {
            if (EditorEffectGuard.allowsEffects()) service<SecondaryEditorAnalysis>()
        }
    }
}
