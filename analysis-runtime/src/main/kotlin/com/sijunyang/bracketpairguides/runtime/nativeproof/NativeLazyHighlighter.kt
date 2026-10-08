/*
 * SPDX-License-Identifier: Apache-2.0
 * Lazy brace-source preparation adapted from JetBrains IntelliJ Community
 * BraceHighlightingHandler.java (241.19416.15), lines79–112, and
 * BraceHighlightingHandler.kt (263.4732.28), lines79–106; decision order is unchanged.
 * Sources: https://github.com/JetBrains/intellij-community/blob/idea/241.19416.15/platform/lang-impl/src/com/intellij/codeInsight/highlighting/BraceHighlightingHandler.java
 * https://github.com/JetBrains/intellij-community/blob/idea/263.4732.28/platform/lang-impl/src/com/intellij/codeInsight/highlighting/BraceHighlightingHandler.kt
 * Copyright JetBrains s.r.o. and contributors. See licenses/intellij-native-brace-context-Apache-2.0.txt.
 */
package com.sijunyang.bracketpairguides.runtime.nativeproof

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.HighlighterIteratorWrapper
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.Conditions
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.tree.ILazyParseableElementType
import com.intellij.psi.util.PsiUtilCore

/** Owns the platform's lazy-source semantics without depending on its now-internal Handler helper. */
internal object NativeLazyHighlighter {
    fun prepare(editor: Editor, file: PsiFile, caretOffset: Int, checkCanceled: () -> Unit): EditorHighlighter {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        checkCanceled()
        if (!PsiDocumentManager.getInstance(file.project).isCommitted(editor.document)) return editor.highlighter
        // The native helper uses the live caret. Our caller supplies its already captured caret,
        // so lazy source selection and subsequent context selection use the same input.
        val elementAt = file.findElementAt(caretOffset)
        for (element in SyntaxTraverser.psiApi().parents(elementAt).takeWhile(Conditions.notEqualTo(file))) {
            checkCanceled()
            if (PsiUtilCore.getElementType(element) !is ILazyParseableElementType) continue
            val language = ILazyParseableElementType.LANGUAGE_KEY.get(element.node) ?: continue
            val range = element.textRange
            val offset = range.startOffset
            val syntax = SyntaxHighlighterFactory.getSyntaxHighlighter(language, file.project, file.virtualFile)!!
            checkCanceled()
            val highlighter = object : LexerEditorHighlighter(syntax, editor.colorsScheme) {
                override fun createIterator(startOffset: Int): HighlighterIterator =
                    object : HighlighterIteratorWrapper(super.createIterator(maxOf(startOffset - offset, 0))) {
                        override fun getStart(): Int = super.getStart() + offset
                        override fun getEnd(): Int = super.getEnd() + offset
                    }
            }
            checkCanceled()
            val text = editor.document.getText(range)
            checkCanceled()
            highlighter.setText(text)
            checkCanceled()
            return highlighter
        }
        checkCanceled()
        return editor.highlighter
    }
}
