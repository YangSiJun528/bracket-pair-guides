package com.sijunyang.bracketpairguides.renderer

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

/** Routes platform editor events to the state owned by each editor session. */
@Service(Service.Level.APP)
internal class EditorGuideEventRouter :
    CaretListener,
    DocumentListener,
    EditorFactoryListener,
    EditorColorsListener,
    VisibleAreaListener,
    Disposable {
    init {
        val editorFactory = EditorFactory.getInstance()
        editorFactory.eventMulticaster.addCaretListener(this, this)
        editorFactory.eventMulticaster.addDocumentListener(this, this)
        editorFactory.eventMulticaster.addVisibleAreaListener(this, this)
        editorFactory.addEditorFactoryListener(this, this)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(EditorColorsManager.TOPIC, this)
    }

    override fun caretPositionChanged(event: CaretEvent) = onEdt(event.editor) {
        EditorGuideSession.get(event.editor)?.caretMoved()
    }

    override fun caretAdded(event: CaretEvent) = caretPositionChanged(event)

    override fun caretRemoved(event: CaretEvent) = caretPositionChanged(event)

    override fun documentChanged(event: DocumentEvent) {
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

    override fun visibleAreaChanged(event: VisibleAreaEvent) = onEdt(event.editor) {
        EditorGuideSession.get(event.editor)?.visibleAreaChanged()
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        EditorGuideSession.dispose(event.editor)
    }

    override fun globalSchemeChange(scheme: EditorColorsScheme?) {
        for (editor in EditorFactory.getInstance().allEditors) {
            onEdt(editor) {
                EditorGuideSession.get(editor)?.updateOptions(PluginSettings.getInstance().options)
            }
        }
    }

    override fun dispose() {
        for (editor in EditorFactory.getInstance().allEditors) {
            EditorGuideSession.dispose(editor)
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

    private fun preferredImmediateEditor(editors: List<Editor>): Editor? =
        editors.firstOrNull { editor -> editor.contentComponent.hasFocus() }
            ?: editors.firstOrNull { editor ->
                val project = editor.project
                project != null &&
                    !project.isDisposed &&
                    FileEditorManager.getInstance(project).selectedTextEditor === editor
            }
            ?: editors.firstOrNull { editor -> editor.contentComponent.isShowing }
            ?: editors.firstOrNull()

    companion object {
        internal fun routeDocumentChange(
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

        fun ensureInitialized() {
            ApplicationManager.getApplication().getService(EditorGuideEventRouter::class.java)
        }
    }
}
