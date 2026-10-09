package com.sijunyang.bracketpairguides.core

import com.sijunyang.bracketpairguides.core.api.BracketCalculator
import com.sijunyang.bracketpairguides.core.input.CalculationControl
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock

/** Public use cases with narrowly scoped stack/resource evidence for otherwise unobservable phases. */
class LateCancellationContractTest {
    private val tokens = AnalysisCoverage(true, false, false)

    @Test fun cancellationDuringMergeCannotPublishAPartiallySortedTokenView() = runBlocking<Unit> {
        cancelInPhase("mergeSortedRuns", RecordedInput("()".repeat(16384)), tokens)
    }

    @Test fun cancellationDuringMergeCopyCannotPublishAPartiallyCopiedTokenView() = runBlocking<Unit> {
        // 32768 token entries require one merge pass and a copy back to the immutable index storage.
        cancelInPhase("copyWithCancellation", RecordedInput("()".repeat(16384)), tokens)
    }

    @Test fun cancellationWhileSealingGuidesCannotPublishAFrozenPartialTree() = runBlocking<Unit> {
        val input = RecordedInput("{\n" + "  x\n".repeat(1024) + "}")
        cancelInPhase("seal", input, AnalysisCoverage(true, true, true), "GuidePositionIndex\$Builder")
        assertThat(input.prefixRequests.sumOf { it.second }).isEqualTo(2050)
    }

    @Test fun cacheWaitRemainsCancellableBeforeTheOwnerReleasesItsLock() = runBlocking<Unit> {
        val calculator = BracketCalculator()
        // Reflection observes/holds the one resource whose blocking behavior is under test.
        // It does not invoke private computation or create an alternate calculation path.
        val cache = calculator.javaClass.getDeclaredField("cache").let {
            it.isAccessible = true
            it.get(calculator)
        }
        val lock = cache.javaClass.getDeclaredField("lock").let {
            it.isAccessible = true
            it.get(cache) as ReentrantLock
        }
        val waited = CompletableDeferred<Unit>()
        val cancellation = CancellationException("cancel cache waiter")
        val control = object : CalculationControl {
            override fun checkCanceled() {
                if (Thread.currentThread().stackTrace.any {
                        it.className.endsWith("CalculationCache") &&
                            it.methodName == "locked"
                    }
                ) {
                    waited.complete(Unit)
                    throw cancellation
                }
            }
            override suspend fun yieldWork() = yield()
        }
        lock.lock()
        try {
            val observed = async(Dispatchers.Default) {
                try {
                    calculator.analyze(RecordedInput("()"), tokens, control)
                    null
                } catch (
                    failure: CancellationException,
                ) {
                    failure
                }
            }
            withTimeout(5000) { waited.await() }
            assertThat(withTimeout(5000) { observed.await() }).isSameAs(cancellation)
            assertThat(lock.isHeldByCurrentThread).isTrue()
        } finally {
            lock.unlock()
        }
        assertThat(calculator.analyze(RecordedInput("()"), tokens, coroutineControl()))
            .isInstanceOf(AnalysisResult.Available::class.java)
    }

    @Test fun canonicalCacheStoresOnlyWeakPayloadReferences() = runBlocking<Unit> {
        val calculator = BracketCalculator()
        releasedStorage(calculator)
        // Deterministic retention evidence: every cache entry owns only weak references to payloads.
        val cache = calculator.javaClass.getDeclaredField("cache").let {
            it.isAccessible = true
            it.get(calculator)
        }
        val entries = cache.javaClass.getDeclaredField("entries").let {
            it.isAccessible = true
            it.get(cache) as List<*>
        }
        assertThat(entries).hasSize(1)
        for (entry in entries) {
            for (name in listOf("pairs", "indexes")) {
                val reference = entry!!.javaClass.getDeclaredField(name).let {
                    it.isAccessible = true
                    it.get(entry)
                }
                assertThat(reference).isInstanceOf(WeakReference::class.java)
            }
        }
        // This verifies the retention structure, not a GC deadline or actual collection.
        // No assertion depends on whether the JVM chooses to run GC during this test.
    }

    private suspend fun releasedStorage(calculator: BracketCalculator): WeakReference<Any> {
        val result = calculator.analyze(
            RecordedInput("()".repeat(1024)),
            tokens,
            coroutineControl(),
        ) as AnalysisResult.Available
        val storage = result.view.javaClass.getDeclaredField("indexes").let {
            it.isAccessible = true
            it.get(result.view)
        }
        return WeakReference(storage)
    }

    private suspend fun cancelInPhase(
        method: String,
        input: RecordedInput,
        coverage: AnalysisCoverage,
        owner: String = "CancellableLongArraySortKt",
    ) {
        val cancellation = CancellationException("cancel during $owner.$method")
        var observedPhase = false
        val control = object : CalculationControl {
            override fun checkCanceled() {
                if (Thread.currentThread().stackTrace.any { it.className.endsWith(owner) && it.methodName == method }) {
                    observedPhase = true
                    throw cancellation
                }
            }
            override suspend fun yieldWork() = yield()
        }
        val calculator = BracketCalculator()
        var failure: Throwable? = null
        try {
            calculator.analyze(input, coverage, control)
        } catch (error: Throwable) {
            failure = error
        }
        assertThat(observedPhase).describedAs("the public calculation reached the requested resource phase").isTrue()
        assertThat(failure).isSameAs(cancellation)
        assertThat(
            calculator.analyze(input, coverage, coroutineControl()),
        ).isInstanceOf(AnalysisResult.Available::class.java)
    }
}
