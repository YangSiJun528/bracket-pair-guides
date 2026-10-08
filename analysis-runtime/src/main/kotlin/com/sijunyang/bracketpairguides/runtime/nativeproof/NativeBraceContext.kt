/*
 * SPDX-License-Identifier: Apache-2.0
 * Native brace-context decision order adapted from JetBrains IntelliJ Community
 * BraceMatchingUtil.java (IntelliJ 241.19416.15, distributed in IDEA Community 2024.1.7).
 * Source: https://github.com/JetBrains/intellij-community/blob/idea/241.19416.15/platform/lang-impl/src/com/intellij/codeInsight/highlighting/BraceMatchingUtil.java
 * Copyright JetBrains s.r.o. and contributors. See licenses/intellij-native-brace-context-Apache-2.0.txt.
 * Offset/block-cursor selection and matching traversal are adapted; matcher callbacks and
 * lazy-language preparation use an owned adapter. Keep parity fixtures against the platform helper.
 */
package com.sijunyang.bracketpairguides.runtime.nativeproof

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

/** Native candidate order and navigation semantics; all host callbacks occur under caller-owned read access. */
internal object NativeBraceContext {
    data class Context(val currentBraceOffset: Int, val navigationOffset: Int)

    fun compute(
        editor: Editor,
        file: PsiFile,
        caretOffset: Int,
        blockCursor: Boolean,
        checkCanceled: () -> Unit,
    ): Context? {
        checkCanceled()
        if (!file.isValid) return null
        val source = NativeLazyHighlighter.prepare(editor, file, caretOffset, checkCanceled)
        checkCanceled()
        val session = begin(source, file, editor.document.charsSequence, caretOffset, blockCursor, checkCanceled)
        while (true) {
            if (session.advance(
                    editor.document.charsSequence,
                    NativeBraceMatching.WorkBudget.unlimited(),
                    checkCanceled,
                )
            ) {
                break
            }
        }
        return session.result()
    }

    fun begin(
        source: EditorHighlighter,
        file: PsiFile,
        chars: CharSequence,
        caretOffset: Int,
        blockCursor: Boolean,
        checkCanceled: () -> Unit,
    ): Session {
        val current = NativeCursor.create(source, caretOffset, checkCanceled)
        val currentType = current.takeUnless { it.atEnd() }?.let { BraceMatchingUtil.getFileType(file, it.start) }
        val currentLeft = currentType != null && BraceMatchingUtil.isLBraceToken(current, chars, currentType)
        val currentRight =
            !currentLeft && currentType != null && BraceMatchingUtil.isRBraceToken(current, chars, currentType)
        val insideCurrent = (currentLeft || currentRight) && current.start < caretOffset
        val previous = if (caretOffset > 0 &&
            !insideCurrent
        ) {
            NativeCursor.create(source, caretOffset - 1, checkCanceled)
        } else {
            null
        }
        val previousType = previous?.takeUnless { it.atEnd() }?.let { BraceMatchingUtil.getFileType(file, it.start) }
        val previousLeft =
            previous != null && previousType != null && BraceMatchingUtil.isLBraceToken(previous, chars, previousType)
        val previousRight =
            !previousLeft && previous != null && previousType != null &&
                BraceMatchingUtil.isRBraceToken(previous, chars, previousType)
        val currentStart = if (current.atEnd()) -1 else current.start
        val previousStart = previous?.takeUnless { it.atEnd() }?.start ?: -1
        val candidates = ArrayList<Candidate>(4)
        fun candidate(
            iterator: NativeCursor.Tracked?,
            type: FileType?,
            start: Int,
            forward: Boolean,
            navigationEnd: Boolean,
        ) {
            candidates +=
                Candidate(checkNotNull(iterator).bookmark(), checkNotNull(type), start, forward, navigationEnd)
        }
        if (blockCursor) {
            if (currentLeft) candidate(current, currentType, currentStart, forward = true, navigationEnd = false)
            if (currentRight) candidate(current, currentType, currentStart, forward = false, navigationEnd = false)
            if (previousRight) candidate(previous, previousType, previousStart, forward = false, navigationEnd = false)
            if (previousLeft) candidate(previous, previousType, previousStart, forward = true, navigationEnd = false)
        } else {
            if (previousRight) candidate(previous, previousType, previousStart, forward = false, navigationEnd = false)
            if (currentLeft) candidate(current, currentType, currentStart, forward = true, navigationEnd = true)
            if (previousLeft) candidate(previous, previousType, previousStart, forward = true, navigationEnd = true)
            if (currentRight) candidate(current, currentType, currentStart, forward = false, navigationEnd = false)
        }
        return Session.create(source, candidates)
    }

    internal class Session private constructor(
        private val source: EditorHighlighter,
        private val candidates: List<Candidate>,
    ) {
        private var candidateIndex = 0
        private var scanner: NativeBraceMatching.Session? = null
        private var cursor: NativeCursor? = null
        private var completed = false
        private var context: Context? = null

        /** True means the native context is final, including a final absence. No iterator/text is retained. */
        fun advance(chars: CharSequence, budget: NativeBraceMatching.WorkBudget, checkCanceled: () -> Unit): Boolean {
            if (completed) return true
            while (candidateIndex < candidates.size) {
                if (budget.exhausted()) return false
                val candidate = candidates[candidateIndex]
                val iterator = (cursor ?: candidate.cursor).restore(source, checkCanceled)
                val active =
                    scanner
                        ?: NativeBraceMatching.matching(
                            chars,
                            candidate.fileType,
                            iterator,
                            candidate.forward,
                            checkCanceled,
                        )
                            .also { scanner = it }
                val step = active.advance(chars, iterator, budget, checkCanceled)
                cursor = iterator.bookmark()
                checkCanceled()
                if (step == NativeBraceMatching.Step.MORE) return false
                if (step == NativeBraceMatching.Step.MATCHED) {
                    context = Context(candidate.start, if (candidate.navigationEnd) iterator.end else iterator.start)
                    completed = true
                    return true
                }
                scanner = null
                cursor = null
                candidateIndex++
            }
            completed = true
            return true
        }

        fun result(): Context? {
            check(completed) { "Context traversal has not completed" }
            return context
        }

        companion object {
            internal fun create(source: EditorHighlighter, candidates: List<Candidate>): Session =
                Session(source, candidates)
        }
    }

    internal data class Candidate(
        val cursor: NativeCursor,
        val fileType: FileType,
        val start: Int,
        val forward: Boolean,
        val navigationEnd: Boolean,
    )
}

/**
 * Platform scans have no mandatory cancellation probe of their own on 241. Checking traversal
 * covers platform structural scans and temporary iterator traversal inside matcher callbacks.
 * Direct matching uses an owned scanner with additional checks inside stack membership/recovery.
 * Lazy PSI/lexer and matcher extension callbacks retain their own cancellation behavior.
 */
internal object CancellableNativeHighlighter {
    fun cancellableIterator(delegate: HighlighterIterator, checkCanceled: () -> Unit): HighlighterIterator =
        object : HighlighterIterator {
            private var traversed = 0

            // Implement the stable iterator contract and inherit the platform-provided optional defaults.
            override fun getTextAttributes(): TextAttributes? = delegate.textAttributes
            override fun getStart(): Int = delegate.start
            override fun getEnd(): Int = delegate.end
            override fun getTokenType(): IElementType? = delegate.tokenType
            override fun atEnd(): Boolean = delegate.atEnd()
            override fun getDocument(): Document? = delegate.document

            override fun advance() {
                if (traversed++ and 0xFF == 0) checkCanceled()
                delegate.advance()
            }

            override fun retreat() {
                if (traversed++ and 0xFF == 0) checkCanceled()
                delegate.retreat()
            }
        }
}
