package com.sijunyang.bracketpairguides.runtime.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.UIUtil
import com.sijunyang.bracketpairguides.core.api.BracketCalculator
import com.sijunyang.bracketpairguides.core.input.CalculationControl
import com.sijunyang.bracketpairguides.core.input.RepairRequest
import com.sijunyang.bracketpairguides.core.input.RetryCapture
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.BracketPair
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import com.sijunyang.bracketpairguides.runtime.capture.AnalysisCaptureObserver
import com.sijunyang.bracketpairguides.runtime.capture.AnalysisReadEpoch
import com.sijunyang.bracketpairguides.runtime.capture.EditorSource
import com.sijunyang.bracketpairguides.runtime.capture.SourceChanged
import com.sijunyang.bracketpairguides.runtime.capture.SourceIdentity
import com.sijunyang.bracketpairguides.ui.work.GuideChange
import com.sijunyang.bracketpairguides.ui.work.GuideDemand
import com.sijunyang.bracketpairguides.ui.work.NativeInterest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Actual scalar SDK boundary; no test scheduling callback is installed in production. */
class ScalarPrefixCaptureIdeContractTest : BasePlatformTestCase() {
    private val canceled = AtomicBoolean()
    private val requests = ConcurrentLinkedQueue<Int>()
    private val exits = ConcurrentLinkedQueue<Pair<Boolean, Throwable?>>()
    private val observer = object : AnalysisCaptureObserver() {
        private val ids = AtomicLong()
        override fun requested(phase: Int): Long {
            val app = ApplicationManager.getApplication()
            check(!app.isDispatchThread && !app.isReadAccessAllowed)
            requests.add(phase)
            return ids.incrementAndGet()
        }
        override fun entered(requestId: Long, phase: Int): Long {
            val app = ApplicationManager.getApplication()
            check(!app.isDispatchThread && app.isReadAccessAllowed)
            return requestId
        }
        override fun exited(attemptId: Long, completed: Boolean, failure: Throwable?) {
            exits.add(completed to failure)
        }
    }
    private val control = object : CalculationControl {
        override fun checkCanceled() {
            if (canceled.get()) throw CancellationException("scalar capture canceled")
        }
        override suspend fun yieldWork() = Unit
    }
    private val demand = GuideDemand(
        0,
        AnalysisCoverage(true, true, true),
        emptySet(),
        true,
        0,
        null,
        GuideChange.CONFIGURATION,
        NativeInterest(0, false),
    )
    private fun <T> worker(action: suspend () -> T): T {
        val future = AppExecutorUtil.getAppExecutorService().submit<T> {
            runBlocking(Dispatchers.Default + observer) { action() }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        try {
            while (!future.isDone && System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                LockSupport.parkNanos(1_000_000)
            }
            check(future.isDone) { "scalar worker did not settle" }
            return future.get()
        } finally {
            if (!future.isDone) future.cancel(true)
        }
    }
    private fun source(epoch: AnalysisReadEpoch, calculation: DocumentCalculation): EditorSource =
        EditorSource(myFixture.editor, SourceIdentity.capture(myFixture.editor, demand), epoch, control, calculation)

    fun testScalarAndContinuationUseActualBoundedBackgroundReads() {
        val indent = " ".repeat(5000)
        myFixture.configureByText("Scalar.java", "{\n${indent}value\n}")
        val epoch = AnalysisReadEpoch()
        val calculation = DocumentCalculation(myFixture.editor.document)
        try {
            val input = source(epoch, calculation)
            worker {
                input.beginAttempt()
                val first = input.initialPrefix(1)
                assertEquals(" ".repeat(128), first.text)
                assertEquals(130, first.afterOffset)
                assertEquals(5007, first.lineEndOffset)
                val continuation = input.continuePrefix(1, first.afterOffset)
                assertEquals(" ".repeat(4096), continuation.text)
                assertEquals(4226, continuation.afterOffset)
                assertEquals(first.lineEndOffset, continuation.lineEndOffset)
                val tail = input.continuePrefix(1, continuation.afterOffset)
                assertEquals(" ".repeat(776) + "value", tail.text)
                assertEquals(5007, tail.afterOffset)
                assertEquals(5007, tail.lineEndOffset)
                input.validateCurrent()
            }
            assertEquals(
                listOf(
                    AnalysisCaptureObserver.INITIAL_STATE,
                    AnalysisCaptureObserver.GUIDE_PREFIX,
                    AnalysisCaptureObserver.GUIDE_CONTINUATION,
                    AnalysisCaptureObserver.GUIDE_CONTINUATION,
                    AnalysisCaptureObserver.FINAL_VALIDATION,
                ),
                requests.toList(),
            )
            assertEquals(5, exits.size)
            assertTrue(exits.all { it.first && it.second == null })
        } finally {
            Disposer.dispose(epoch)
        }
    }
    fun testScalarRejectsCancellationChangedSourceAndUnrelatedWriteEpoch() {
        myFixture.configureByText("Scalar.java", "{\n    value\n}")
        val epoch = AnalysisReadEpoch()
        val calculation = DocumentCalculation(myFixture.editor.document)
        try {
            val input = source(epoch, calculation)
            worker { input.beginAttempt() }
            canceled.set(true)
            assertTrue(worker { runCatching { input.initialPrefix(1) }.exceptionOrNull() } is CancellationException)
            canceled.set(false)
            worker { input.beginAttempt() }
            val unrelated = EditorFactory.getInstance().createDocument("other")
            WriteCommandAction.runWriteCommandAction(project) { unrelated.insertString(0, "new") }
            assertTrue(worker { runCatching { input.initialPrefix(1) }.exceptionOrNull() } is RetryCapture)
            worker { input.beginAttempt() }
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "new") }
            assertTrue(worker { runCatching { input.initialPrefix(1) }.exceptionOrNull() } is SourceChanged)
            assertEquals(3, requests.count { it == AnalysisCaptureObserver.GUIDE_PREFIX })
            val failed = exits.filter { !it.first }
            assertEquals(3, failed.size)
            assertTrue(failed[0].second is CancellationException)
            assertTrue(failed[1].second is RetryCapture)
            assertTrue(failed[2].second is SourceChanged)
        } finally {
            canceled.set(false)
            Disposer.dispose(epoch)
        }
    }
    fun testRepairThenFullAnalysisSurvivesAttemptRestartAfterUnrelatedWrite() {
        myFixture.configureByText("Scalar.java", "class C {\n    void f() { }\n}")
        val epoch = AnalysisReadEpoch()
        val calculation = DocumentCalculation(myFixture.editor.document)
        try {
            val input = source(epoch, calculation)
            val calculator = BracketCalculator()
            val pair = BracketPair(8, 1, myFixture.editor.document.textLength - 1, 1, 0, 0, 2)
            worker {
                val guide = checkNotNull(calculator.repair(input, RepairRequest(pair, true), control))
                assertEquals(pair, guide.pair)
                assertEquals(0, guide.guideColumn)
                val result = calculator.analyze(input, demand.coverage, control) as AnalysisResult.Available
                assertEquals(pair, result.view.activePairAt(10))
            }
            val unrelated = EditorFactory.getInstance().createDocument("other")
            WriteCommandAction.runWriteCommandAction(project) { unrelated.insertString(0, "new") }
            assertTrue(worker { runCatching { input.tokensAt(0) }.exceptionOrNull() } is RetryCapture)
            worker {
                val result = calculator.analyze(input, demand.coverage, control) as AnalysisResult.Available
                assertEquals(pair, result.view.activePairAt(10))
                val guide = checkNotNull(calculator.repair(input, RepairRequest(pair, true), control))
                assertEquals(pair, guide.pair)
                assertEquals(0, guide.guideColumn)
            }
        } finally {
            Disposer.dispose(epoch)
        }
    }
}
