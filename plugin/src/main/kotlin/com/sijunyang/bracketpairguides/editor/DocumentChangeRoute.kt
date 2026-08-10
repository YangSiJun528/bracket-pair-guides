package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.editor.Editor

/** One immediate document-change delivery plus deferred deliveries to sibling editors. */
internal object DocumentChangeRoute {
    fun deliver(
        editors: List<Editor>,
        change: DocumentChange,
        foregroundEditor: Editor?,
    ) {
        if (foregroundEditor != null && !foregroundEditor.isDisposed) {
            EditorGuideSessions.get(foregroundEditor)?.documentChanged(
                change,
                resolveImmediately = true,
            )
        }
        for (editor in editors) {
            if (editor === foregroundEditor || editor.isDisposed) continue
            EditorGuideSessions.get(editor)?.documentChanged(
                change,
                resolveImmediately = false,
            )
        }
    }
}
