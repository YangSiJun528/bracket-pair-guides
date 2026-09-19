package com.sijunyang.bracketpairguides.visual

import com.intellij.driver.client.Driver
import com.intellij.ide.starter.driver.engine.BackgroundRun
import com.intellij.ide.starter.models.IDEStartResult
import java.util.concurrent.atomic.AtomicReference

/** Keep the test failure when Starter's finally block fails while shutting down the IDE. */
internal fun BackgroundRun.useDriverAndCloseIdePreservingFailure(block: Driver.() -> Unit): IDEStartResult {
    val testFailure = AtomicReference<Throwable>()
    try {
        return useDriverAndCloseIde {
            try {
                block()
            } catch (failure: Throwable) {
                testFailure.set(failure)
                throw failure
            }
        }
    } catch (failure: Throwable) {
        val original = testFailure.get() ?: throw failure
        // Keep Driver's diagnostic wrapper when it already exposes the original cause.
        if (generateSequence(failure) { it.cause }.any { it === original }) throw failure
        original.addSuppressed(failure)
        throw original
    }
}
