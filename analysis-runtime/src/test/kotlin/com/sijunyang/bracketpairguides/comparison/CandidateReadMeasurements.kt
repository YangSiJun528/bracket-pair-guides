package com.sijunyang.bracketpairguides.comparison

import com.intellij.openapi.application.ApplicationManager
import com.sijunyang.bracketpairguides.runtime.capture.AnalysisCaptureObserver
import com.sijunyang.bracketpairguides.runtime.nativeproof.NativeCaptureObserver
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Test-only observation of the candidate's real read-action bodies. */
class CandidateReadMeasurements {
    data class ReadRequest(
        val requestId: Long,
        val phase: String,
        val requestedNanos: Long,
        val threadId: Long,
        val edt: Boolean,
        val readAllowed: Boolean,
    )

    data class ReadSample(
        val requestId: Long,
        val attemptId: Long,
        val phase: String,
        val requestedNanos: Long,
        val enteredNanos: Long,
        val exitedNanos: Long,
        val threadId: Long,
        val edt: Boolean,
        val readAllowed: Boolean,
        val allocatedBytes: Long?,
        val completed: Boolean,
        val failureClass: String?,
    )

    private val nativePhaseValue = AtomicReference<String?>()
    val nativePhase: String? get() = nativePhaseValue.get()

    @Volatile private var nativeProgressListener: ((String, Int) -> Unit)? = null
    fun onNativeProgress(listener: ((String, Int) -> Unit)?) {
        nativeProgressListener = listener
    }

    private val sequence = AtomicLong()
    private val requests = ConcurrentHashMap<Long, ReadRequest>()
    private val bodies = ConcurrentHashMap<Long, Body>()
    private val allocation = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf {
        it.isThreadAllocatedMemorySupported
    }?.also { if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true }

    private fun requested(phase: String): Long {
        val now = System.nanoTime()
        val thread = Thread.currentThread()
        val application = ApplicationManager.getApplication()
        val id = sequence.incrementAndGet()
        requests[id] = ReadRequest(
            id,
            phase,
            now,
            thread.id,
            application.isDispatchThread,
            application.isReadAccessAllowed,
        )
        return id
    }

    private fun entered(requestId: Long): Long {
        val now = System.nanoTime()
        val thread = Thread.currentThread()
        val allocated = allocation?.getThreadAllocatedBytes(thread.id) ?: -1L
        val application = ApplicationManager.getApplication()
        val id = sequence.incrementAndGet()
        bodies[id] = Body(
            checkNotNull(requests[requestId]),
            id,
            now,
            thread.id,
            application.isDispatchThread,
            application.isReadAccessAllowed,
            allocated,
        )
        return id
    }

    private fun exited(attemptId: Long, completed: Boolean, failure: Throwable?) {
        val now = System.nanoTime()
        val body = checkNotNull(bodies[attemptId])
        val allocated = allocation?.getThreadAllocatedBytes(Thread.currentThread().id) ?: -1L
        body.completed = completed
        body.failureClass = failure?.javaClass?.name
        body.allocatedBytes = if (Thread.currentThread().id == body.threadId &&
            body.allocatedAtEntry >= 0 &&
            allocated >= body.allocatedAtEntry
        ) {
            allocated - body.allocatedAtEntry
        } else {
            null
        }
        body.exitedNanos = now
    }

    val context: CoroutineContext = object : AnalysisCaptureObserver() {
        override fun requested(phase: Int) =
            this@CandidateReadMeasurements.requested(AnalysisCaptureObserver.phaseName(phase))
        override fun entered(requestId: Long, phase: Int) = this@CandidateReadMeasurements.entered(requestId)
        override fun exited(attemptId: Long, completed: Boolean, failure: Throwable?) =
            this@CandidateReadMeasurements.exited(attemptId, completed, failure)
    } + object : NativeCaptureObserver() {
        override fun requested(phase: Int) = this@CandidateReadMeasurements.requested(nativePhases[phase])
        override fun entered(requestId: Long, phase: Int): Long {
            val attempt = this@CandidateReadMeasurements.entered(requestId)
            nativePhaseValue.set(nativePhases[phase])
            return attempt
        }
        override fun exited(attemptId: Long, completed: Boolean, failure: Throwable?) {
            try {
                this@CandidateReadMeasurements.exited(attemptId, completed, failure)
            } finally {
                nativePhaseValue.set(null)
            }
        }
        override fun progressed(phase: Int, ownedOperations: Int) {
            nativeProgressListener?.invoke(nativePhases[phase], ownedOperations)
        }
    }

    private val nativePhases = listOf(
        "native-admission", "native-preparation",
        "native-direct-classification", "native-direct-traversal", "native-scope-classification",
        "native-structural-traversal", "native-scope-match-classification",
        "native-scope-match-traversal", "native-final-validation",
    )

    /** Reset only between completed operations; it is deliberately not a production control. */
    fun reset() {
        check(bodies.values.none { it.exitedNanos == 0L })
        requests.clear()
        bodies.clear()
    }

    fun requests(): List<ReadRequest> = requests.values.sortedBy { it.requestId }
    fun snapshot(): List<ReadSample> = bodies.values.sortedBy { it.attemptId }.map {
        ReadSample(
            it.request.requestId, it.attemptId, it.request.phase, it.request.requestedNanos,
            it.enteredNanos, it.exitedNanos, it.threadId, it.edt, it.readAllowed,
            it.allocatedBytes, it.completed, it.failureClass,
        )
    }

    private class Body(
        val request: ReadRequest,
        val attemptId: Long,
        val enteredNanos: Long,
        val threadId: Long,
        val edt: Boolean,
        val readAllowed: Boolean,
        val allocatedAtEntry: Long,
    ) {
        @Volatile var exitedNanos: Long = 0

        @Volatile var allocatedBytes: Long? = null

        @Volatile var completed: Boolean = false

        @Volatile var failureClass: String? = null
    }
}
