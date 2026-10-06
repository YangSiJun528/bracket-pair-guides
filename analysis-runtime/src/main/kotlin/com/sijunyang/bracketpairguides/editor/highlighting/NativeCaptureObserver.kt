package com.sijunyang.bracketpairguides.editor.highlighting

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Optional per-coroutine observation; never block or mutate the inspected host from these callbacks. */
internal abstract class NativeCaptureObserver : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeCaptureObserver> {
        const val ADMISSION = 0
        const val PREPARATION = 1
        const val DIRECT_CLASSIFICATION = 2
        const val DIRECT_TRAVERSAL = 3
        const val SCOPE_CLASSIFICATION = 4
        const val STRUCTURAL_TRAVERSAL = 5
        const val SCOPE_MATCH_CLASSIFICATION = 6
        const val SCOPE_MATCH_TRAVERSAL = 7
        const val FINAL_VALIDATION = 8
    }

    abstract fun requested(phase: Int): Long
    abstract fun entered(requestId: Long, phase: Int): Long
    abstract fun exited(attemptId: Long, completed: Boolean, failure: Throwable?)
    open fun progressed(phase: Int, ownedOperations: Int) { }

    /** Invoked only after releasing the read action; fixture suspension never holds a reader. */
    open suspend fun released(phase: Int) { }
}
