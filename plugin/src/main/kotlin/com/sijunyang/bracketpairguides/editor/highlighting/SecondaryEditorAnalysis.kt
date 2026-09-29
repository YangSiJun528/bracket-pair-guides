package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.TextRange
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.editor.EditorActivitySource
import com.sijunyang.bracketpairguides.editor.EditorEffectGuard
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.editor.events.EditorGuideEvents
import com.sijunyang.bracketpairguides.editor.events.IdentityEventBatch
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.jetbrains.concurrency.CancellablePromise
import java.util.IdentityHashMap

/** Supplies the normal analysis/apply pipeline to editors without daemon passes. */
@Service(Service.Level.APP)
internal class SecondaryEditorAnalysis internal constructor(
    private val activity: (Editor) -> EditorActivity,
    private val visibleRange: (Editor) -> TextRange,
) : Disposable,
    EditorFactoryListener {
    constructor() : this(EditorActivitySource::capture, Editor::calculateVisibleRange)

    private val managedEditors = java.util.Collections.newSetFromMap(IdentityHashMap<Editor, Boolean>())
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val running = IdentityHashMap<Editor, CancellablePromise<*>>()
    private val pending = IdentityEventBatch<Editor>(
        schedule = { action -> alarm.addRequest(action, REFRESH_DELAY_MILLIS, ModalityState.any()) },
        consume = ::analyze,
    )

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
        managedEditors.remove(event.editor)
        pending.remove(event.editor)
        running.remove(event.editor)?.cancel()
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
        if (!EditorEffectGuard.allowsEffects() || editor.isDisposed) return
        pending.request(editor)
    }

    private fun analyze(editor: Editor) {
        if (!EditorEffectGuard.allowsEffects() || editor.isDisposed ||
            EditorGuideSessions.get(editor)?.isVisible != true
        ) {
            return
        }
        val project = editor.project ?: ProjectManager.getInstance().defaultProject
        if (project.isDisposed) return
        val pass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = EditorSurfaceClassifier.fileType(editor),
            sourceFile = EditorSurfaceClassifier.sourceFile(editor),
            analyze = service<BracketAnalysis>()::analyze,
            activity = activity,
            visibleRange = visibleRange,
            stickySourceRanges = { emptyList() },
        )
        running.remove(editor)?.cancel()
        running[editor] = ReadAction.nonBlocking<BracketGuideHighlightingPass> {
            pass.doCollectInformation(
                ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator(),
            )
            pass
        }.expireWith(this)
            .expireWhen { editor.isDisposed || project.isDisposed }
            .coalesceBy(this, editor)
            .finishOnUiThread(ModalityState.stateForComponent(editor.contentComponent)) { result ->
                running.remove(editor)
                if (EditorEffectGuard.allowsEffects() && !editor.isDisposed) {
                    result.doApplyInformationToEditor()
                }
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun dispose() {
        val application = ApplicationManager.getApplication()
        if (!application.isDisposed || application.isDispatchThread) {
            managedEditors.forEach { EditorGuideSessions.get(it)?.setAnalysisRefreshRequester {} }
        }
        managedEditors.clear()
        pending.clear()
        running.values.forEach { it.cancel() }
        running.clear()
    }

    companion object {
        private const val REFRESH_DELAY_MILLIS = 75

        fun ensureInitialized() {
            if (EditorEffectGuard.allowsEffects()) service<SecondaryEditorAnalysis>()
        }
    }
}
