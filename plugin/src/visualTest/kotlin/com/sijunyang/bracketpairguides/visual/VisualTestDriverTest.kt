package com.sijunyang.bracketpairguides.visual

import com.intellij.driver.client.Driver
import com.intellij.ide.starter.driver.engine.BackgroundRun
import com.intellij.ide.starter.models.IDEStartResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.time.Duration

class VisualTestDriverTest {
    @Test
    fun preservesTestFailureWhenIdeShutdownAlsoFails() {
        val timeout = IllegalStateException("Settings category timed out")
        val shutdown = IllegalStateException("IDE exited with code 143")

        val reported = assertThrows(IllegalStateException::class.java) {
            FailingRun(shutdown).useDriverAndCloseIdePreservingFailure { throw timeout }
        }

        assertSame(timeout, reported)
        assertSame(shutdown, reported.suppressed.single())
    }

    @Test
    fun preservesShutdownFailureWhenTestSucceeded() {
        val shutdown = IllegalStateException("IDE exited with code 143")

        val reported = assertThrows(IllegalStateException::class.java) {
            FailingRun(shutdown).useDriverAndCloseIdePreservingFailure { }
        }

        assertSame(shutdown, reported)
    }

    @Test
    fun keepsDriverDiagnosticsWhenTheyContainTheTestFailure() {
        val timeout = IllegalStateException("Settings category timed out")

        val reported = assertThrows(IllegalStateException::class.java) {
            FailingRun(wrapFailure = true).useDriverAndCloseIdePreservingFailure { throw timeout }
        }

        assertSame(timeout, reported.cause)
        assertTrue(timeout.suppressed.isEmpty(), "Do not create a circular exception chain")
    }

    @Test
    fun propagatesOriginalFailureWithoutSuppressingItself() {
        val timeout = IllegalStateException("Settings category timed out")

        val reported = assertThrows(IllegalStateException::class.java) {
            FailingRun().useDriverAndCloseIdePreservingFailure { throw timeout }
        }

        assertSame(timeout, reported)
        assertTrue(reported.suppressed.isEmpty())
    }

    private class FailingRun(
        private val shutdownFailure: Throwable? = null,
        private val wrapFailure: Boolean = false,
    ) : BackgroundRun(CompletableDeferred(), unusedDriver()) {
        override fun <R> useDriverAndCloseIde(closeIdeTimeout: Duration, block: Driver.() -> R): IDEStartResult {
            try {
                driver.block()
            } catch (failure: Throwable) {
                if (wrapFailure) throw IllegalStateException("Driver UI diagnostics", failure)
                throw failure
            } finally {
                shutdownFailure?.let { throw it }
            }
            error("This fixture must fail in the test or during shutdown")
        }
    }

    private companion object {
        fun unusedDriver(): Driver = Proxy.newProxyInstance(
            Driver::class.java.classLoader,
            arrayOf(Driver::class.java),
        ) { _, method, _ -> error("Unexpected Driver call: ${method.name}") } as Driver
    }
}
