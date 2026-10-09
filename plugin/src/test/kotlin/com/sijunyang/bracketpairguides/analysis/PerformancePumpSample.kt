package com.sijunyang.bracketpairguides.analysis

import com.intellij.concurrency.resetThreadContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Opt-in PERF harness wait: normal tests and production keep their existing scheduling. */
internal fun awaitPerformanceEvents(
    description: String,
    timeoutSeconds: Int = 10,
    complete: () -> Boolean,
): PerformancePumpSample {
    ApplicationManager.getApplication().assertIsDispatchThread()
    val sample = PerformancePumpSample(System.nanoTime())
    val deadline = sample.startedNs + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
    var previousCheck = sample.startedNs
    while (true) {
        val checked = System.nanoTime()
        sample.maximumCheckGapNs = maxOf(sample.maximumCheckGapNs, checked - previousCheck)
        previousCheck = checked
        sample.checks++
        if (complete()) {
            sample.finishedNs = System.nanoTime()
            return sample
        }
        check(checked < deadline) { "$description exceeded the performance pump watchdog" }
        check(!Thread.currentThread().isInterrupted) { "$description EDT was interrupted" }
        // Preserve PlatformTestUtil's per-event context reset without its unconditional10ms sleep.
        val event = resetThreadContext().use { PlatformTestUtil.dispatchNextEventIfAny() }
        if (event != null) {
            sample.events++
        } else {
            sample.parks++
            val parkStarted = System.nanoTime()
            LockSupport.parkNanos(100_000L)
            sample.parkedNs += System.nanoTime() - parkStarted
        }
    }
}

/** Actual cadence is recorded;100us requested idle park is not an OS scheduling guarantee. */
internal class PerformancePumpSample(val startedNs: Long) {
    var finishedNs = 0L
    var checks = 0L
    var events = 0L
    var parks = 0L
    var parkedNs = 0L
    var maximumCheckGapNs = 0L
}
