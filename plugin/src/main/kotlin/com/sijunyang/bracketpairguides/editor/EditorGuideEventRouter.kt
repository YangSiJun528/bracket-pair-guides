package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.settings.PluginSettings
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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.util.Alarm
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly

/** Routes platform editor events to the state owned by each editor session. */
@Service(Service.Level.APP)
@ApiStatus.Internal
public class EditorGuideEventRouter public constructor() :
    CaretListener,
    DocumentListener,
    EditorFactoryListener,
    EditorColorsListener,
    VisibleAreaListener,
    Disposable {
    private val visibleRefreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val visibleRefreshBatcher = IdentityEventBatcher<Editor>(
        schedule = { refresh ->
            visibleRefreshAlarm.addRequest(refresh, VISIBLE_REFRESH_DELAY_MILLIS)
        },
        consume = { editor ->
            if (!editor.isDisposed) EditorGuideSession.get(editor)?.visibleAreaChanged()
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

    public override fun caretPositionChanged(event: CaretEvent): Unit = onEdt(event.editor) {
        if (event.caret?.let { it !== event.editor.caretModel.primaryCaret } == true) {
            return@onEdt
        }
        primaryCaretChanged(event.editor)
    }

    public override fun caretAdded(event: CaretEvent): Unit = caretPositionChanged(event)

    // The removed caret is no longer primary when this callback runs, so the
    // post-removal primary selection must be refreshed without the event filter.
    public override fun caretRemoved(event: CaretEvent): Unit = onEdt(event.editor) {
        primaryCaretChanged(event.editor)
    }

    public override fun documentChanged(event: DocumentEvent): Unit {
        val change = DocumentChange.from(event)
        val editors = EditorFactory.getInstance().getEditors(event.document).toList()
        onEdt {
            val sessionEditors = editors.filter { editor ->
                !editor.isDisposed && EditorGuideSession.get(editor) != null
            }
            routeDocumentChange(
                editors = sessionEditors,
                change = change,
                immediateEditor = preferredImmediateEditor(sessionEditors),
            )
        }
    }

    public override fun visibleAreaChanged(event: VisibleAreaEvent): Unit = onEdt(event.editor) {
        if (EditorGuideSession.get(event.editor) != null) {
            visibleRefreshBatcher.request(event.editor)
        }
    }

    public override fun editorReleased(event: EditorFactoryEvent): Unit {
        visibleRefreshBatcher.remove(event.editor)
        EditorGuideSession.dispose(event.editor)
    }

    public override fun globalSchemeChange(scheme: EditorColorsScheme?): Unit {
        for (editor in EditorFactory.getInstance().allEditors) {
            onEdt(editor) {
                EditorGuideSession.get(editor)?.updateOptions(
                    PluginSettings.getInstance().options,
                    resolveImmediately = false,
                    refreshColors = true,
                )
            }
        }
    }

    public override fun dispose(): Unit {
        visibleRefreshBatcher.clear()
        for (editor in EditorFactory.getInstance().allEditors) {
            EditorGuideSession.dispose(editor)
        }
    }

    private fun primaryCaretChanged(editor: Editor) {
        val session = EditorGuideSession.get(editor) ?: return
        session.caretMoved()
        if (session.hasCappedTokenDecorations) {
            visibleRefreshBatcher.request(editor)
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

    public companion object {
        private const val VISIBLE_REFRESH_DELAY_MILLIS = 16

        private fun routeDocumentChange(
            editors: List<Editor>,
            change: DocumentChange,
            immediateEditor: Editor?,
        ) {
            if (immediateEditor != null && !immediateEditor.isDisposed) {
                EditorGuideSession.get(immediateEditor)?.documentChanged(
                    change,
                    resolveImmediately = true,
                )
            }
            for (editor in editors) {
                if (editor === immediateEditor || editor.isDisposed) continue
                EditorGuideSession.get(editor)?.documentChanged(
                    change,
                    resolveImmediately = false,
                )
            }
        }

        @TestOnly
        public fun routeDocumentChangeForTest(
            editors: List<Editor>,
            change: DocumentChange,
            immediateEditor: Editor?,
        ): Unit {
            routeDocumentChange(editors, change, immediateEditor)
        }

        public fun preferredImmediateEditor(editors: List<Editor>): Editor? =
            editors.firstOrNull { editor -> editor.contentComponent.hasFocus() }
                ?: editors.firstOrNull { editor ->
                    val project = editor.project
                    project != null &&
                        !project.isDisposed &&
                        FileEditorManager.getInstance(project).selectedTextEditor === editor
                }
                ?: editors.firstOrNull { editor -> editor.contentComponent.isShowing }
                ?: editors.firstOrNull()

        public fun ensureInitialized(): Unit {
            ApplicationManager.getApplication().getService(EditorGuideEventRouter::class.java)
        }
    }
}
