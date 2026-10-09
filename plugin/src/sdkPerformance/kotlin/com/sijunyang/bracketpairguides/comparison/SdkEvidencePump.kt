package com.sijunyang.bracketpairguides.comparison

import com.intellij.concurrency.resetThreadContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** SDK event dispatch for opt-in evidence, without the ordinary fixture helper's fixed 10 ms sleep. */
internal class SdkEvidencePump {
    private val checks = AtomicLong()
    private val events = AtomicLong()
    private val idle = AtomicLong()
    private val idleNanos = AtomicLong()
    private val maximumCheckGap = AtomicLong()

    fun await(timeoutSeconds: Long, completed: () -> Boolean) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000
        var previous = System.nanoTime()
        do {
            val now = System.nanoTime()
            maximumCheckGap.accumulateAndGet(now - previous) { left, right -> maxOf(left, right) }
            previous = now
            checks.incrementAndGet()
            if (completed()) return
            check(now < deadline && !Thread.currentThread().isInterrupted) {
                "SDK evidence watchdog expired/interrupted"
            }
            // SDK 241 PlatformTestUtil.dispatchAllEventsInIdeEventQueue uses this reset+dispatch ordering.
            // Its stable bulk-drain/wait helpers change our one-event cadence (wait also sleeps 10 ms).
            // Keep the experimental call isolated to this pinned-minimum-SDK measurement harness.
            @Suppress("UnstableApiUsage")
            val dispatched = resetThreadContext().use { PlatformTestUtil.dispatchNextEventIfAny() }
            if (dispatched != null) {
                events.incrementAndGet()
            } else {
                val before = System.nanoTime()
                LockSupport.parkNanos(100_000)
                idleNanos.addAndGet(System.nanoTime() - before)
                idle.incrementAndGet()
            }
        } while (true)
    }

    fun snapshot(): Map<String, Long> = mapOf(
        "checks" to checks.get(),
        "events" to events.get(),
        "idleParks" to idle.get(),
        "idleParkNanos" to idleNanos.get(),
        "cumulativeMaximumCheckGapNs" to maximumCheckGap.get(),
    )
}
