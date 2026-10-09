package com.sijunyang.bracketpairguides.ui.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.sijunyang.bracketpairguides.ui.policy.EditorCapabilities

/** Reads editor metadata only; safe for a highlighting pass constructed off EDT. */
object EditorSurfaceClassifier {
    fun capabilities(editor: Editor): EditorCapabilities = when {
        editor !is EditorEx || editor.isDisposed -> EditorCapabilities.NONE
        EditorFactory.getInstance().allEditors.none { it === editor } -> EditorCapabilities.NONE
        editor.editorKind != EditorKind.MAIN_EDITOR -> EditorCapabilities.COLORS_ONLY
        editor.isOneLineMode -> EditorCapabilities.ONE_LINE
        else -> EditorCapabilities.MAIN
    }
}
