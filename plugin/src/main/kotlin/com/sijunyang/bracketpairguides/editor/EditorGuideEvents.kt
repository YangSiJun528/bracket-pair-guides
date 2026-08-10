package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.util.Alarm

/** Routes platform editor events to the state owned by each editor session. */
@Service(Service.Level.APP)
internal class EditorGuideEvents :
    CaretListener,
    DocumentListener,
    EditorFactoryListener,
    EditorColorsListener,
    VisibleAreaListener,
    Disposable {
    private val visibleRefreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val visibleRefreshBatch = IdentityEventBatch<Editor>(
        schedule = { refresh ->
            visibleRefreshAlarm.addRequest(refresh, VISIBLE_REFRESH_DELAY_MILLIS)
        },
        consume = { editor ->
            if (!editor.isDisposed) EditorGuideSessions.get(editor)?.visibleAreaChanged()
        },
    )

    init {
        val editorFactory = EditorFactory.getInstance()
        editorFactory.eventMulticaster.addCaretListener(this, this)
        editorFactory.eventMulticaster.addDocumentListener(this, this)
        editorFactory.eventMulticaster.addVisibleAreaListener(this, this)
        editorFactory.addEditorFactoryListener(this, this)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(EditorColorsManager.TOPIC, this)
    }

    override fun caretPositionChanged(event: CaretEvent): Unit = onEdt(event.editor) {
        if (event.caret?.let { it !== event.editor.caretModel.primaryCaret } == true) {
            return@onEdt
        }
        primaryCaretChanged(event.editor)
    }

    override fun caretAdded(event: CaretEvent): Unit = caretPositionChanged(event)

    // The removed caret is no longer primary when this callback runs, so the
    // post-removal primary selection must be refreshed without the event filter.
    override fun caretRemoved(event: CaretEvent): Unit = onEdt(event.editor) {
        primaryCaretChanged(event.editor)
    }

    override fun documentChanged(event: DocumentEvent): Unit {
        val editors = EditorFactory.getInstance().getEditors(event.document).toList()
        onEdt {
            for (editor in editors) {
                if (!editor.isDisposed) {
                    EditorGuideSessions.get(editor)?.documentChanged()
                }
            }
        }
    }

    override fun visibleAreaChanged(event: VisibleAreaEvent): Unit = onEdt(event.editor) {
        if (EditorGuideSessions.get(event.editor) != null) {
            visibleRefreshBatch.request(event.editor)
        }
    }

    override fun editorReleased(event: EditorFactoryEvent): Unit {
        visibleRefreshBatch.remove(event.editor)
        EditorGuideSessions.dispose(event.editor)
    }

    override fun globalSchemeChange(scheme: EditorColorsScheme?): Unit {
        for (editor in EditorFactory.getInstance().allEditors) {
            onEdt(editor) {
                EditorGuideSessions.get(editor)?.updateOptions(
                    BracketGuideSettings.getInstance().options,
                    refreshColors = true,
                )
            }
        }
    }

    override fun dispose(): Unit {
        visibleRefreshBatch.clear()
        for (editor in EditorFactory.getInstance().allEditors) {
            EditorGuideSessions.dispose(editor)
        }
    }

    private fun primaryCaretChanged(editor: Editor) {
        val session = EditorGuideSessions.get(editor) ?: return
        session.caretMoved()
        if (session.hasCappedTokenDecorations) {
            visibleRefreshBatch.request(editor)
        }
    }

    private fun onEdt(editor: Editor, action: () -> Unit) {
        onEdt {
            if (!editor.isDisposed) action()
        }
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            action()
        } else {
            application.invokeLater { action() }
        }
    }

    companion object {
        private const val VISIBLE_REFRESH_DELAY_MILLIS = 16

        fun ensureInitialized(): Unit {
            ApplicationManager.getApplication().getService(EditorGuideEvents::class.java)
        }
    }
}
