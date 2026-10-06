package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.tree.IElementType

/** Absolute token bookmark; the iterator exists only inside one host read action. */
internal class NativeCursor private constructor(
    private val edge: Edge,
    private val start: Int,
    private val end: Int,
    private val type: IElementType?,
) {
    private enum class Edge { TOKEN, BEFORE_FIRST, AFTER_LAST, EMPTY }

    fun restore(highlighter: EditorHighlighter, checkCanceled: () -> Unit): Tracked {
        checkCanceled()
        val delegate = highlighter.createIterator(start)
        if (edge == Edge.EMPTY) {
            if (!delegate.atEnd()) throw NativeProofInvalidated()
            return Tracked(CancellableNativeHighlighter.cancellableIterator(delegate, checkCanceled))
        }
        if (delegate.atEnd() || delegate.start != start || delegate.end != end || delegate.tokenType !== type) {
            throw NativeProofInvalidated()
        }
        val tracked = Tracked(CancellableNativeHighlighter.cancellableIterator(delegate, checkCanceled))
        when (edge) {
            Edge.BEFORE_FIRST -> tracked.retreat()
            Edge.AFTER_LAST -> tracked.advance()
            else -> Unit
        }
        if (edge != Edge.TOKEN && !tracked.atEnd()) throw NativeProofInvalidated()
        return tracked
    }

    /** Tracks callback-local movement too, so suspended state uses the actual post-callback cursor. */
    internal class Tracked(private val delegate: HighlighterIterator) : HighlighterIterator {
        private var boundary = Edge.EMPTY
        private var hasNearest = false
        private var nearestStart = 0
        private var nearestEnd = 0
        private var nearestType: IElementType? = null

        init {
            rememberToken()
        }

        private fun rememberToken() {
            if (delegate.atEnd()) return
            // Read the complete bookmark before replacing it, matching the old value-object order.
            val start = delegate.start
            val end = delegate.end
            val type = delegate.tokenType
            nearestStart = start
            nearestEnd = end
            nearestType = type
            hasNearest = true
        }

        // Implement the stable iterator contract and inherit the platform-provided optional defaults.
        override fun getTextAttributes(): TextAttributes? = delegate.textAttributes
        override fun getStart(): Int = delegate.start
        override fun getEnd(): Int = delegate.end
        override fun getTokenType(): IElementType? = delegate.tokenType
        override fun atEnd(): Boolean = delegate.atEnd()
        override fun getDocument(): Document? = delegate.document

        override fun advance() {
            rememberToken()
            delegate.advance()
            boundary = Edge.AFTER_LAST
        }

        override fun retreat() {
            rememberToken()
            delegate.retreat()
            boundary = Edge.BEFORE_FIRST
        }

        fun bookmark(): NativeCursor = if (!delegate.atEnd()) {
            token(delegate)
        } else {
            if (hasNearest) {
                NativeCursor(boundary, nearestStart, nearestEnd, nearestType)
            } else {
                NativeCursor(Edge.EMPTY, 0, 0, null)
            }
        }
    }

    companion object {
        fun create(highlighter: EditorHighlighter, offset: Int, checkCanceled: () -> Unit): Tracked {
            checkCanceled()
            return Tracked(
                CancellableNativeHighlighter.cancellableIterator(highlighter.createIterator(offset), checkCanceled),
            )
        }

        private fun token(iterator: HighlighterIterator): NativeCursor =
            NativeCursor(Edge.TOKEN, iterator.start, iterator.end, iterator.tokenType)
    }
}

/** A canceled/retried or changed source invalidates the entire native proof, never a prefix. */
internal class NativeProofInvalidated : RuntimeException(null, null, false, false)
