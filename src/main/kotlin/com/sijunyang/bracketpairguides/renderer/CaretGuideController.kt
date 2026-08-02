package com.sijunyang.bracketpairguides.renderer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
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

/**
 * Keeps the active guide and pair-symbol presentation synchronized with caret
 * movement without rerunning lexer analysis. The application service owns the
 * global listener for plugin unload and application-shutdown cleanup.
 */
@Service(Service.Level.APP)
internal class CaretGuideController :
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

    override fun caretPositionChanged(event: CaretEvent) {
        GuideLineHighlightingPass.updateActivePresentation(event.editor)
    }

    override fun caretAdded(event: CaretEvent) {
        GuideLineHighlightingPass.updateActivePresentation(event.editor)
    }

    override fun caretRemoved(event: CaretEvent) {
        GuideLineHighlightingPass.updateActivePresentation(event.editor)
    }

    override fun documentChanged(event: DocumentEvent) {
        for (editor in EditorFactory.getInstance().getEditors(event.document)) {
            GuideLineHighlightingPass.updateAfterDocumentChange(editor)
        }
    }

    override fun visibleAreaChanged(event: VisibleAreaEvent) {
        GuideLineHighlightingPass.updateVisiblePresentation(event.editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        GuideLineHighlightingPass.clearEditorState(event.editor)
    }

    override fun globalSchemeChange(scheme: EditorColorsScheme?) {
        for (editor in EditorFactory.getInstance().allEditors) {
            GuideLineHighlightingPass.refreshSettings(editor)
        }
    }

    override fun dispose() {
        for (editor in EditorFactory.getInstance().allEditors) {
            GuideLineHighlightingPass.clearEditorState(editor)
        }
    }

    companion object {
        fun ensureInitialized() {
            ApplicationManager.getApplication().getService(CaretGuideController::class.java)
        }
    }
}
