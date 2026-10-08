package com.sijunyang.bracketpairguides.ui.measurement

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.sijunyang.bracketpairguides.ui.editor.EditorGuides

/** Measurement integrity only; observes the real registry without installing composition. */
object UiSdkGuideIsolation {
    @JvmStatic
    fun assertNoAttachments() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val attached = EditorFactory.getInstance().allEditors.filter { EditorGuides.get(it) != null }
        check(attached.isEmpty()) {
            "Unexpected editor guide attachments outside owned measurement lifetime: " +
                attached.joinToString { editor ->
                    "kind=${editor.editorKind},editorIdentity=${System.identityHashCode(editor)}," +
                        "documentIdentity=${System.identityHashCode(editor.document)}," +
                        "length=${editor.document.textLength},disposed=${editor.isDisposed}"
                }
        }
    }
}
