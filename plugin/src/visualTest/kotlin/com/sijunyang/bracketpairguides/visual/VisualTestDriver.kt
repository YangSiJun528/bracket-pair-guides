package com.sijunyang.bracketpairguides.visual

import com.intellij.driver.client.Driver
import com.intellij.ide.starter.driver.engine.BackgroundRun
import com.intellij.ide.starter.models.IDEStartResult

/** Keep the test failure when Starter's finally block fails while shutting down the IDE. */
internal fun BackgroundRun.useDriverAndCloseIdePreservingFailure(block: Driver.() -> Unit): IDEStartResult {
    var testFailure: Throwable? = null
    try {
        return useDriverAndCloseIde {
            try {
                block()
            } catch (failure: Throwable) {
                testFailure = failure
                throw failure
            }
        }
    } catch (failure: Throwable) {
        val original = testFailure ?: throw failure
        // Keep Driver's diagnostic wrapper when it already exposes the original cause.
        if (generateSequence(failure) { it.cause }.any { it === original }) throw failure
        original.addSuppressed(failure)
        throw original
    }
}
