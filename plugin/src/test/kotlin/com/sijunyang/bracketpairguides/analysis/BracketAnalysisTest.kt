package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BracketAnalysisTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun tearDown() {
        try {
            scope.cancel()
            PlatformTestUtil.waitWithEventsDispatching(
                "bracket analysis workers stop",
                { scope.coroutineContext[Job]!!.children.none() },
                WATCHDOG_SECONDS,
            )
        } finally {
            super.tearDown()
        }
    }

    fun testAnalyzePreservesStampAndAnswersOnlyPublicQueries() {
        val source =
            """
            class Sample {
                void run() {
                    call();
                }
            }
            """.trimIndent()
        myFixture.configureByText("Sample.java", source)
        val request =
            request(
                AnalysisCoverage(
                    tokens = true,
                    activePair = true,
                    guidePosition = true,
                ),
            )

        val outcome = analyze(request)
        val result = complete(outcome)

        assertThat(outcome.stamp).isSameAs(request.stamp)
        assertThat(result.stamp).isSameAs(request.stamp)
        val tokens =
            result.visibleTokens(
                range = TextRange(0, source.length),
                focusOffset = source.indexOf("call"),
                limit = 100,
            )
        assertThat(tokens.isCapped).isFalse()
        assertThat(tokens.size).isPositive()
        assertThat((0 until tokens.size).map(tokens::offsetAt)).isSorted()

        val activePair = checkNotNull(result.activePairAt(source.indexOf("call") + 2))
        assertThat(activePair.openOffset).isEqualTo(source.indexOf('{', source.indexOf("run")))
        assertThat(activePair.closeOffset).isEqualTo(source.indexOf('}', source.indexOf("call")))
        val guide = checkNotNull(result.guideFor(activePair))
        assertThat(guide.guideColumn).isEqualTo(4)
        assertThat(guide.anchorLine).isEqualTo(activePair.closeLine)
    }

    fun testCoverageBuildsOnlyRequestedArtifacts() {
        val source = "class Planned { void run() { call(); } }"
        myFixture.configureByText("Planned.java", source)
        val caretOffset = source.indexOf("call") + 2
        val range = TextRange(0, source.length)

        val tokenOnly = complete(analyze(request(AnalysisCoverage(true, false, false))))
        assertThat(tokenOnly.visibleTokens(range, caretOffset, 100).size).isPositive()
        assertThat(tokenOnly.activePairAt(caretOffset)).isNull()

        val activeOnly = complete(analyze(request(AnalysisCoverage(false, true, false))))
        assertThat(activeOnly.visibleTokens(range, caretOffset, 100).size).isZero()
        assertThat(activeOnly.activePairAt(caretOffset)).isNotNull()

        val inactive = complete(analyze(request(AnalysisCoverage(false, false, false))))
        assertThat(inactive.visibleTokens(range, caretOffset, 100).size).isZero()
        assertThat(inactive.activePairAt(caretOffset)).isNull()
    }

    fun testUnavailableOutcomeRetainsTheAttemptStampWithoutAPartialSnapshot() {
        myFixture.configureByText("Unavailable.java", "class Unavailable { }")
        val request =
            request(
                AnalysisCoverage(
                    tokens = true,
                    activePair = true,
                    guidePosition = false,
                ),
            )

        val outcome: AnalysisOutcome =
            AnalysisOutcome.Unavailable(
                request.stamp,
                AnalysisLimit.PAIR_CAPACITY,
            )

        assertThat(outcome.stamp).isSameAs(request.stamp)
        assertThat(outcome)
            .isInstanceOfSatisfying(AnalysisOutcome.Unavailable::class.java) { unavailable ->
                assertThat(unavailable.limit).isEqualTo(AnalysisLimit.PAIR_CAPACITY)
            }
    }

    fun testProductPairCapacityAcceptsTheBoundaryAndRejectsTheNextPair() {
        val exactSource =
            buildString(200_020) {
                append("class Dense {")
                repeat(99_999) { append("{}") }
                append('}')
            }
        myFixture.configureByText("ExactPairCapacity.java", exactSource)

        val exact = analyzeCurrentTokens()

        assertThat(exact).isInstanceOf(AnalysisOutcome.Complete::class.java)

        val overflowSource =
            buildString(200_022) {
                append("class Dense {")
                repeat(100_000) { append("{}") }
                append('}')
            }
        myFixture.configureByText("ExceededPairCapacity.java", overflowSource)

        val overflow = analyzeCurrentTokens()

        assertThat(overflow)
            .isInstanceOfSatisfying(AnalysisOutcome.Unavailable::class.java) { unavailable ->
                assertThat(unavailable.limit).isEqualTo(AnalysisLimit.PAIR_CAPACITY)
            }
    }

    fun testProductPendingOpenCapacityAcceptsTheBoundaryAndRejectsTheNextOpen() {
        myFixture.configureByText(
            "ExactPendingCapacity.java",
            "class Pending " + "{".repeat(50_000),
        )

        val exact = analyzeCurrentTokens()

        assertThat(exact).isInstanceOf(AnalysisOutcome.Complete::class.java)

        myFixture.configureByText(
            "ExceededPendingCapacity.java",
            "class Pending " + "{".repeat(50_001),
        )

        val overflow = analyzeCurrentTokens()

        assertThat(overflow)
            .isInstanceOfSatisfying(AnalysisOutcome.Unavailable::class.java) { unavailable ->
                assertThat(unavailable.limit).isEqualTo(AnalysisLimit.PENDING_OPEN_CAPACITY)
            }
    }

    fun testGuideCapacityKeepsExactPairAndTokenIndexesWithoutPublishingAGuide() {
        val guideLineCount = 1_032_193
        val source =
            buildString(guideLineCount + 20) {
                append("class Huge {\n")
                repeat(guideLineCount - 1) { append('\n') }
                append('}')
            }
        myFixture.configureByText("Huge.java", source)
        val input =
            request(
                AnalysisCoverage(
                    tokens = true,
                    activePair = true,
                    guidePosition = true,
                ),
            )
        val outcome = analyze(input)

        assertThat(outcome).isInstanceOf(AnalysisOutcome.Limited::class.java)
        val limited = outcome as AnalysisOutcome.Limited
        assertThat(limited.stamp).isSameAs(input.stamp)
        assertThat(limited.limit).isEqualTo(AnalysisLimit.GUIDE_CAPACITY)
        assertThat(
            limited.snapshot.stamp.coverage,
        ).isEqualTo(input.coverage.copy(guidePosition = false))
        assertThat(input.stamp.covers(limited.snapshot.stamp)).isTrue()
        assertThat(
            request(input.coverage.copy(guidePosition = false))
                .stamp
                .covers(limited.snapshot.stamp),
        ).isTrue()
        val pair = checkNotNull(limited.snapshot.activePairAt(source.indexOf('\n') + 1))
        assertThat(limited.snapshot.guideFor(pair)).isNull()
        assertThat(
            limited.snapshot
                .visibleTokens(
                    TextRange(0, source.length),
                    pair.openOffset,
                    10,
                ).size,
        ).isEqualTo(2)
    }

    fun testHighlighterReplacementDuringCaptureRejectsTheAttemptStamp() {
        myFixture.configureByText("Replaced.java", "class Replaced { void run() { call(); } }")
        val gate = installCaptureGate()
        val input = request(AnalysisCoverage(true, true, true))
        val worker = start(input)
        try {
            awaitGate(gate)
            val originalDocumentStamp = input.editor.document.modificationStamp
            ApplicationManager.getApplication().runWriteAction {
                (input.editor as EditorEx).setHighlighter(
                    EditorHighlighterFactory.getInstance().createEditorHighlighter(project, PlainTextFileType.INSTANCE),
                )
            }
            assertThat(input.editor.document.modificationStamp).isEqualTo(originalDocumentStamp)
            assertThat(request(input.coverage).stamp.covers(input.stamp)).isFalse()
            assertThat(await(worker)).isNull()
        } finally {
            gate.release.countDown()
            worker.cancel()
        }
    }

    fun testAnalyzePropagatesCoroutineCancellationDuringCapture() {
        myFixture.configureByText("Canceled.java", "class Canceled { void run() { call(); } }")
        val gate = installCaptureGate()
        val worker = start(request(AnalysisCoverage(true, true, true)))
        try {
            awaitGate(gate)
            worker.cancel()
            awaitDone(worker)
            assertThat(worker.isCancelled).isTrue()
            assertThatThrownBy { await(worker) }.isInstanceOf(CancellationException::class.java)
            assertThat(gate.release.count).isEqualTo(1L)
        } finally {
            gate.release.countDown()
            worker.cancel()
        }
    }

    private fun request(coverage: AnalysisCoverage): AnalysisInput = AnalysisInput(
        editor = myFixture.editor,
        fileType = myFixture.file.fileType,
        coverage = coverage,
        disabledLanguageIds = emptySet(),
    )

    private fun analyzeCurrentTokens(): AnalysisOutcome = analyze(request(AnalysisCoverage(true, false, false)))

    private fun analyze(input: AnalysisInput): AnalysisOutcome = checkNotNull(await(start(input))) {
        "A current request must produce an outcome"
    }

    private fun start(input: AnalysisInput): Deferred<AnalysisOutcome?> = scope.async {
        BracketAnalysis().analyzeInBackground(input)
    }

    private fun awaitDone(worker: Deferred<*>) {
        check(ApplicationManager.getApplication().isDispatchThread)
        PlatformTestUtil.waitWithEventsDispatching("bracket analysis completes", {
            worker.isCompleted
        }, WATCHDOG_SECONDS)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun await(worker: Deferred<AnalysisOutcome?>): AnalysisOutcome? {
        awaitDone(worker)
        return worker.getCompleted()
    }

    private fun awaitGate(gate: CaptureGate) {
        PlatformTestUtil.waitWithEventsDispatching("actual token capture reaches its gate", {
            gate.entered.count == 0L
        }, WATCHDOG_SECONDS)
    }

    private fun installCaptureGate(): CaptureGate {
        val editor = myFixture.editor as EditorEx
        val delegate = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, myFixture.file.fileType)
        val gate = CaptureGate()
        editor.setHighlighter(object : EditorHighlighter by delegate {
            override fun createIterator(startOffset: Int): HighlighterIterator {
                gate.pauseOnce()
                return delegate.createIterator(startOffset)
            }
        })
        return gate
    }

    private class CaptureGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val claimed = AtomicBoolean()

        fun pauseOnce() {
            val application = ApplicationManager.getApplication()
            // Fixture painting can also request an iterator; only gate background capture.
            if (application.isDispatchThread || !application.isReadAccessAllowed) return
            if (!claimed.compareAndSet(false, true)) return
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WATCHDOG_SECONDS.toLong())
            while (release.count != 0L) {
                ProgressManager.checkCanceled()
                check(System.nanoTime() < deadline) { "Capture gate exceeded its watchdog" }
                release.await(1, TimeUnit.MILLISECONDS)
            }
            ProgressManager.checkCanceled()
        }
    }

    private fun complete(outcome: AnalysisOutcome): BracketSnapshot {
        assertThat(outcome)
            .describedAs("complete analysis")
            .isInstanceOf(AnalysisOutcome.Complete::class.java)
        return (outcome as AnalysisOutcome.Complete).snapshot
    }

    private companion object {
        const val WATCHDOG_SECONDS = 30
    }
}
