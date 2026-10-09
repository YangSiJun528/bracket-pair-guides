package com.sijunyang.bracketpairguides.analysis.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.reference.SynchronousAnalysisReference
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.CalculatedAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class IncrementalAnalysisTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var epoch: AnalysisReadEpoch

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        epoch = AnalysisReadEpoch()
        Disposer.register(testRootDisposable, epoch)
    }

    override fun tearDown() {
        try {
            scope.cancel()
            PlatformTestUtil.waitWithEventsDispatching(
                "incremental analysis workers stop",
                { scope.coroutineContext[Job]!!.children.none() },
                WATCHDOG_SECONDS,
            )
        } finally {
            super.tearDown()
        }
    }

    fun testJavaAndXmlChunkPipelinePreservesObservablePairsTokensAndGuides() {
        val java = buildString {
            append("class Capture {\n  void method() {\n")
            repeat(80) { index ->
                append("\tif (ready) {\n\t  call($index, new int[] { 1, 2 });\n\n\t}\n")
            }
            append("  }\n}\n")
        }
        val xml = buildString {
            append("<root>\n")
            repeat(80) { index ->
                append("\t<node id=\"$index\">\n\t  <leaf />\n\n\t</node>\n")
            }
            append("</root>\n")
        }
        for ((name, source) in listOf("Capture.java" to java, "Capture.xml" to xml)) {
            myFixture.configureByText(name, source)
            val input = input()
            val expected = synchronousSnapshot(input)
            val calculated = await(start(input))
            assertThat(calculated).isInstanceOf(CalculatedAnalysis.Available::class.java)
            val available = calculated as CalculatedAnalysis.Available
            assertThat(available.canonicalPairs.isEmpty).isFalse()
            assertThat(available.limit).isNull()
            assertThat(available.coverage).isEqualTo(input.coverage)
            val actual = BracketSnapshot(input.stamp, available.matcherAvailability, available.indexes)
            assertSnapshotParity(expected, actual, source.length)
        }
    }

    fun testEdtWriteCompletesWhileOffReadComputeIsPausedThenAnalysisRetriesCleanly() {
        configureJava()
        val input = input()
        val expected = synchronousSnapshot(input)
        val gate = OffReadGate()
        val worker = start(input, gate)
        try {
            awaitGate(gate)
            assertThat(worker.isCompleted).isFalse()
            val beforeWrite = epoch.current
            var writeCompleted = false
            ApplicationManager.getApplication().runWriteAction {
                writeCompleted = true
            }
            // This EDT write completed while the background callback still occupies the gate.
            assertThat(writeCompleted).isTrue()
            assertThat(gate.release.count).isEqualTo(1L)
            assertThat(worker.isCompleted).isFalse()
            assertThat(epoch.current).isGreaterThan(beforeWrite)
        } finally {
            gate.release.countDown()
        }
        val result = await(worker)
        assertThat(result).isInstanceOf(CalculatedAnalysis.Available::class.java)
        val available = result as CalculatedAnalysis.Available
        assertSnapshotParity(
            expected,
            BracketSnapshot(input.stamp, available.matcherAvailability, available.indexes),
            input.editor.document.textLength,
        )
    }

    fun testWriteCancelsAnInProgressTokenCaptureAndRetryPreservesAllResults() {
        myFixture.configureByText(
            "CaptureRetry.java",
            "class Capture {\n" + "  void method() { call(1, new int[] { 2 }); }\n".repeat(100) + "}\n",
        )
        val editor = myFixture.editor as EditorEx
        val delegate = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, myFixture.file.fileType)
        val gate = InReadCaptureGate()
        editor.setHighlighter(object : EditorHighlighter by delegate {
            override fun createIterator(startOffset: Int): HighlighterIterator {
                val iterator = delegate.createIterator(startOffset)
                return object : HighlighterIterator by iterator {
                    private var advances = 0
                    override fun advance() {
                        iterator.advance()
                        if (++advances == 128) gate.pauseOnce()
                    }
                }
            }
        })
        val request = input()
        val expected = synchronousSnapshot(request)
        gate.armed.set(true)
        val worker = start(request)
        PlatformTestUtil.waitWithEventsDispatching(
            "partly classified capture reaches its in-read gate",
            { gate.entered.count == 0L },
            WATCHDOG_SECONDS,
        )
        assertThat(worker.isCompleted).isFalse()
        val oldEpoch = epoch.current
        // The gate has no release signal: only platform read-action cancellation
        // can let this EDT write acquire its lock before the watchdog fails.
        runNoOpWrite()
        assertThat(epoch.current).isGreaterThan(oldEpoch)
        assertThat(gate.canceled.get()).isTrue()
        val result = await(worker)
        assertThat(result).isInstanceOf(CalculatedAnalysis.Available::class.java)
        val available = result as CalculatedAnalysis.Available
        assertSnapshotParity(
            expected,
            BracketSnapshot(request.stamp, available.matcherAvailability, available.indexes),
            request.editor.document.textLength,
        )
    }

    fun testDocumentEditDuringOffReadComputeRejectsTheOldRequest() {
        configureJava()
        val request = input()
        val gate = OffReadGate()
        val worker = start(request, gate)
        try {
            awaitGate(gate)
            WriteCommandAction.runWriteCommandAction(project) {
                request.editor.document.insertString(0, "// edited after capture\n")
            }
        } finally {
            gate.release.countDown()
        }
        assertThat(await(worker)).isNull()
    }

    fun testTabSizeChangedBetweenChunksRejectsTheCapturedLayoutAtCompletion() {
        configureJava()
        val request = input()
        val gate = OffReadGate()
        val worker = start(request, gate)
        try {
            awaitGate(gate)
            request.editor.settings.setTabSize(request.stamp.tabSize + 3)
            // The source is unchanged, but the final result must still reject its old layout.
            assertThat(request.editor.document.modificationStamp).isEqualTo(request.stamp.documentStamp)
            assertThat(request.stamp.matchesCapturedSource(request.editor, request.fileType)).isTrue()
        } finally {
            gate.release.countDown()
        }
        assertThat(await(worker)).isNull()
    }

    fun testHighlighterReplacementDuringOffReadComputeRejectsAnUneditedRequest() {
        configureJava()
        val request = input()
        val originalStamp = request.editor.document.modificationStamp
        val gate = OffReadGate()
        val worker = start(request, gate)
        try {
            awaitGate(gate)
            val editor = request.editor as EditorEx
            editor.setHighlighter(
                EditorHighlighterFactory.getInstance().createEditorHighlighter(project, PlainTextFileType.INSTANCE),
            )
            assertThat(editor.document.modificationStamp).isEqualTo(originalStamp)
        } finally {
            gate.release.countDown()
        }
        assertThat(await(worker)).isNull()
    }

    fun testCancellationStopsTheWorkerWithoutReleasingItsOffReadGate() {
        configureJava()
        val gate = OffReadGate()
        val worker = start(input(), gate)
        try {
            awaitGate(gate)
            worker.cancel()
            awaitDone(worker)
            assertThat(worker.isCancelled).isTrue()
            assertThat(gate.release.count).isEqualTo(1L)
        } finally {
            gate.release.countDown()
        }
    }

    fun testDisposedEditorCannotReturnAPublicationPayload() {
        configureJava()
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(myFixture.editor.document, project) as EditorEx
        editor.setHighlighter(
            EditorHighlighterFactory.getInstance().createEditorHighlighter(project, myFixture.file.fileType),
        )
        val gate = OffReadGate()
        val worker = start(input(editor), gate)
        try {
            awaitGate(gate)
            factory.releaseEditor(editor)
        } finally {
            gate.release.countDown()
            if (!editor.isDisposed) factory.releaseEditor(editor)
        }
        assertThat(await(worker)).isNull()
    }

    fun testRequestThatIsAlreadyStaleAtStartReturnsNoPayload() {
        configureJava()
        val stale = input()
        WriteCommandAction.runWriteCommandAction(project) {
            stale.editor.document.insertString(0, "// already stale\n")
        }
        assertThat(await(start(stale))).isNull()
    }

    private fun runNoOpWrite() {
        ApplicationManager.getApplication().runWriteAction { }
    }

    private fun configureJava() {
        myFixture.configureByText(
            "IncrementalCapture.java",
            "class Capture {\n  void method() {\n    call(1);\n  }\n}\n",
        )
    }

    private fun input(editor: Editor = myFixture.editor): AnalysisInput =
        ReadAction.compute<AnalysisInput, RuntimeException> {
            AnalysisInput(editor, myFixture.file.fileType, AnalysisCoverage(true, true, true), emptySet())
        }

    private fun synchronousSnapshot(input: AnalysisInput): BracketSnapshot {
        val outcome = ReadAction.compute<AnalysisOutcome, RuntimeException> {
            SynchronousAnalysisReference().analyze(input, EmptyProgressIndicator())
        }
        assertThat(outcome).isInstanceOf(AnalysisOutcome.Complete::class.java)
        return (outcome as AnalysisOutcome.Complete).snapshot
    }

    private fun start(input: AnalysisInput, gate: OffReadGate? = null): Deferred<CalculatedAnalysis?> = scope.async {
        val context = currentCoroutineContext()
        val application = ApplicationManager.getApplication()
        IncrementalAnalysis(input, epoch) {
            context.ensureActive()
            if (application.isReadAccessAllowed) {
                gate?.sawRead?.set(true)
                ProgressManager.checkCanceled()
            } else {
                gate?.pauseOnce(context)
            }
        }.calculate()
    }

    private fun awaitGate(gate: OffReadGate) {
        assertThat(ApplicationManager.getApplication().isDispatchThread).isTrue()
        PlatformTestUtil.waitWithEventsDispatching(
            "off-read computation reaches its gate after capture",
            { gate.entered.count == 0L },
            WATCHDOG_SECONDS,
        )
        assertThat(gate.sawRead.get()).isTrue()
    }

    private fun awaitDone(worker: Deferred<*>) {
        PlatformTestUtil.waitWithEventsDispatching(
            "incremental analysis finishes",
            { worker.isCompleted },
            WATCHDOG_SECONDS,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun await(worker: Deferred<CalculatedAnalysis?>): CalculatedAnalysis? {
        awaitDone(worker)
        return worker.getCompleted()
    }

    private fun assertSnapshotParity(expected: BracketSnapshot, actual: BracketSnapshot, length: Int) {
        assertThat(actual.matcherAvailability).isEqualTo(expected.matcherAvailability)
        var observedGuides = 0
        for (offset in 0..length) {
            val expectedPair = expected.activePairAt(offset)
            val actualPair = actual.activePairAt(offset)
            assertThat(actualPair).describedAs("active pair at offset %s", offset).isEqualTo(expectedPair)
            if (expectedPair != null) {
                val expectedGuide = expected.guideFor(expectedPair)
                assertThat(actual.guideFor(expectedPair)).isEqualTo(expectedGuide)
                if (expectedGuide != null) observedGuides++
            }
        }
        assertThat(observedGuides).isGreaterThan(0)
        val expectedTokens = expected.visibleTokens(TextRange(0, length), 0, Int.MAX_VALUE)
        val actualTokens = actual.visibleTokens(TextRange(0, length), 0, Int.MAX_VALUE)
        assertThat(actualTokens.size).isEqualTo(expectedTokens.size)
        for (index in 0 until expectedTokens.size) {
            assertThat(actualTokens.offsetAt(index)).isEqualTo(expectedTokens.offsetAt(index))
            assertThat(actualTokens.lengthAt(index)).isEqualTo(expectedTokens.lengthAt(index))
            assertThat(actualTokens.depthAt(index)).isEqualTo(expectedTokens.depthAt(index))
        }
    }

    private class InReadCaptureGate {
        val armed = AtomicBoolean()
        val entered = CountDownLatch(1)
        val canceled = AtomicBoolean()
        private val claimed = AtomicBoolean()

        fun pauseOnce() {
            if (!armed.get() || !claimed.compareAndSet(false, true)) return
            val application = ApplicationManager.getApplication()
            check(application.isReadAccessAllowed && !application.isDispatchThread)
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WATCHDOG_SECONDS.toLong())
            try {
                while (true) {
                    ProgressManager.checkCanceled()
                    check(System.nanoTime() < deadline) { "Token capture was not canceled for the pending EDT write" }
                    Thread.sleep(1)
                }
            } catch (failure: Throwable) {
                if (failure is ProcessCanceledException || failure is CancellationException) canceled.set(true)
                throw failure
            }
        }
    }

    private class OffReadGate {
        val sawRead = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val claimed = AtomicBoolean()

        fun pauseOnce(context: CoroutineContext) {
            if (!sawRead.get() || !claimed.compareAndSet(false, true)) return
            check(!ApplicationManager.getApplication().isReadAccessAllowed)
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WATCHDOG_SECONDS.toLong())
            while (release.count != 0L) {
                context.ensureActive()
                check(System.nanoTime() < deadline) { "Off-read test gate exceeded its watchdog" }
                release.await(10, TimeUnit.MILLISECONDS)
            }
            context.ensureActive()
        }
    }

    private companion object {
        const val WATCHDOG_SECONDS = 20
    }
}
