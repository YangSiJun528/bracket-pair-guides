package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VirtualFile
import com.sijunyang.bracketpairguides.editor.policy.EditorCapabilities

/** Reads editor metadata only; safe for a highlighting pass constructed off EDT. */
object EditorSurfaceClassifier {
    fun capabilities(editor: Editor): EditorCapabilities = when {
        editor !is EditorEx || editor.isDisposed -> EditorCapabilities.NONE
        EditorFactory.getInstance().allEditors.none { it === editor } -> EditorCapabilities.NONE
        editor.editorKind != EditorKind.MAIN_EDITOR -> EditorCapabilities.COLORS_ONLY
        editor.isOneLineMode -> EditorCapabilities.ONE_LINE
        else -> EditorCapabilities.MAIN
    }

    fun sourceFile(editor: Editor): VirtualFile? = FileDocumentManager.getInstance().getFile(editor.document)
        ?: (editor as? EditorEx)?.virtualFile

    fun fileType(editor: Editor): FileType = sourceFile(editor)?.fileType ?: PlainTextFileType.INSTANCE
}
