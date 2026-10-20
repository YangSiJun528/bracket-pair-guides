package com.sijunyang.bracketpairguides.core

import com.sijunyang.bracketpairguides.core.api.BracketCalculator
import com.sijunyang.bracketpairguides.core.input.CalculationControl
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.concurrent.CancellationException

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
