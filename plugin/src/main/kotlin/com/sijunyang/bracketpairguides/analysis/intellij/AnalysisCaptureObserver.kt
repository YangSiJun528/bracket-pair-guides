package com.sijunyang.bracketpairguides.analysis.intellij

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Optional measurement observer. Callbacks must neither block nor mutate analyzed platform state. */
internal abstract class AnalysisCaptureObserver : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AnalysisCaptureObserver> {
        const val INITIAL_STATE = 0
        const val TOKENS = 1
        const val COMPATIBILITY = 2
        const val GUIDE_PREFIX = 3
        const val GUIDE_CONTINUATION = 4
        const val FINAL_VALIDATION = 5
        const val RETRY_VALIDATION = 6

        fun phaseName(phase: Int): String = when (phase) {
            INITIAL_STATE -> "initial-state"
            TOKENS -> "tokens"
            COMPATIBILITY -> "compatibility"
            GUIDE_PREFIX -> "guide-prefix"
            GUIDE_CONTINUATION -> "guide-continuation"
            FINAL_VALIDATION -> "final-validation"
            RETRY_VALIDATION -> "retry-validation"
            else -> error("Unknown capture phase: $phase")
        }
    }

    /** One logical read request can enter its read body more than once after platform cancellation. */
    abstract fun requested(phase: Int): Long

    /** Called inside actual read access, before the captured action and consistency checks. */
    abstract fun entered(requestId: Long, phase: Int): Long

    /** A failed body is recorded before its exception propagates, including canceled/retried bodies. */
    abstract fun exited(attemptId: Long, completed: Boolean, failure: Throwable?)
}
