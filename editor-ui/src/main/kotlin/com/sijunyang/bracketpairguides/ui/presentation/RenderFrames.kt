package com.sijunyang.bracketpairguides.ui.presentation

import com.intellij.openapi.editor.markup.RangeHighlighter
import java.util.concurrent.atomic.AtomicReference

/** Presentation authority and ownership for synchronous, reentrant SDK effects. */
internal class RenderFrames {
    private val current: AtomicReference<Frame?> = AtomicReference(Frame(this))
    val isClosed: Boolean get() = current.get() == null
    fun begin(): Frame {
        val next = Frame(this)
        while (true) {
            val previous = current.get() ?: return next
            if (current.compareAndSet(previous, next)) return next
        }
    }
    fun close(): Boolean = current.getAndSet(null) != null

    class Frame internal constructor(private val frames: RenderFrames) {
        private var created: MutableList<Mark>? = null
        val isCurrent: Boolean get() = frames.current.get() === this
        fun check() {
            if (!isCurrent) throw ObsoleteRendering.INSTANCE
        }
        fun created(highlighter: RangeHighlighter): Mark {
            val mark = Mark(highlighter, this)
            val owned = created ?: ArrayList<Mark>().also { created = it }
            owned += mark
            return mark
        }
        fun adopt(mark: Mark): Mark {
            check()
            check(mark.isReusable)
            mark.owner = this
            return mark
        }
        fun commit() {
            check()
            created = null
        }
        fun rollback() {
            val owned = created ?: return
            created = null
            var failure: Throwable? = null
            for (mark in owned) {
                try {
                    if (mark.owner === this) mark.dispose()
                } catch (cleanup: Throwable) {
                    if (failure ==
                        null
                    ) {
                        failure = cleanup
                    } else {
                        failure.addSuppressed(cleanup)
                    }
                }
            }
            failure?.let { throw it }
        }
    }
    class Mark internal constructor(val highlighter: RangeHighlighter, internal var owner: Frame) {
        private var retiring = false
        val isReusable: Boolean get() = !retiring && highlighter.isValid
        fun dispose() {
            if (retiring) return
            // beforeRemoved is reentrant while the SDK resource can still report valid.
            retiring = true
            try {
                if (highlighter.isValid) highlighter.dispose()
            } catch (
                failure: Throwable,
            ) {
                retiring = false
                throw failure
            }
        }
    }
}

internal class ObsoleteRendering private constructor() : RuntimeException(null, null, false, false) {
    companion object {
        val INSTANCE = ObsoleteRendering()
    }
}
