package com.sijunyang.bracketpairguides.comparison

import com.sun.management.ThreadMXBean
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Per-thread execution segments; nested activation of this trace is counted once. */
internal class SdkAllocationSegments(private val allocation: ThreadMXBean?) :
    AbstractCoroutineContextElement(Key),
    ThreadContextElement<SdkAllocationSegments.Frame> {
    companion object Key : CoroutineContext.Key<SdkAllocationSegments>
    private val local = ThreadLocal<Frame>()
    private val total = AtomicLong()
    private val open = AtomicInteger()
    private val count = AtomicLong()
    private val invalid = AtomicInteger()

    internal class Frame(val thread: Long, val before: Long, var depth: Int = 1)

    override fun updateThreadContext(context: CoroutineContext): Frame {
        local.get()?.let {
            it.depth++
            return it
        }
        val thread = Thread.currentThread().id
        val before = allocation?.getThreadAllocatedBytes(thread) ?: -1
        val frame = Frame(thread, before)
        local.set(frame)
        open.incrementAndGet()
        count.incrementAndGet()
        return frame
    }
    override fun restoreThreadContext(context: CoroutineContext, oldState: Frame) {
        check(oldState.thread == Thread.currentThread().id)
        if (--oldState.depth != 0) return
        val after = allocation?.getThreadAllocatedBytes(oldState.thread) ?: -1
        if (oldState.before >= 0 && after >= oldState.before) {
            total.addAndGet(after - oldState.before)
        } else if (allocation != null) {
            invalid.incrementAndGet()
        }
        local.remove()
        open.decrementAndGet()
    }
    suspend fun awaitClosed() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (open.get() != 0 && System.nanoTime() < deadline) delay(1)
        check(open.get() == 0) { "Allocation trace still has active execution segments" }
    }
    fun snapshot(): Map<String, Any?> = mapOf(
        "coroutineAllocatedBytes" to if (allocation != null && invalid.get() == 0) total.get() else null,
        "allocationSegmentCount" to count.get(),
        "allocationTraceComplete" to (open.get() == 0),
        "allocationInvalidSegments" to invalid.get(),
        "allocationScope" to
            "inherited coroutine execution segments including direct source capture on EDT; nested same-thread segments counted once",
        "allocationExclusions" to
            "waiting, independent IDE tasks, dispatcher work outside context activation, unknown/unsupported counters",
        "allocationOverhead" to "includes trace and read-observer bookkeeping; not exclusively production allocation",
    )
}
