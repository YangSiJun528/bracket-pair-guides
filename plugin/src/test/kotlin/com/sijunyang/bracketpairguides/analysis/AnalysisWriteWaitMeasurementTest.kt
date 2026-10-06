package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import com.sijunyang.bracketpairguides.analysis.intellij.AnalysisCaptureObserver
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.reference.SynchronousAnalysisReference
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Measures actual read bodies and real EDT write acquisition without staging a blocked reader. */
class AnalysisWriteWaitMeasurementTest : BasePlatformTestCase() {
    private val runId = UUID.randomUUID().toString()

    fun testWriteWaitMeasurements() {
        if (!java.lang.Boolean.getBoolean("issue93.measure")) return
        val application = ApplicationManager.getApplication()
        check(application.isDispatchThread) { "The write contention probe requires the fixture EDT" }
        val implementation = when (val selected = System.getProperty("issue93.measure.implementation", "legacy")) {
            "legacy", "existing-BracketAnalysis" -> Implementation.LEGACY
            "incremental" -> Implementation.INCREMENTAL
            else -> error("Unknown measurement implementation: $selected")
        }
        val output = Path.of(
            System.getProperty("issue93.measure.writeWait.output", "build/reports/issue93-write-wait.jsonl"),
        )
        output.parent?.let { Files.createDirectories(it) }
        val warmups = Integer.getInteger("issue93.measure.warmups", 3).coerceAtLeast(0)
        val repeats = Integer.getInteger("issue93.measure.writeWait.repeats", 5).coerceAtLeast(1)
        // One calibration outside all measured intervals; no wall-clock/MXBean calls per read.
        val calibrationNanoBefore = System.nanoTime()
        val calibrationUtc = Instant.now()
        val calibrationNanoAfter = System.nanoTime()
        emit(
            output,
            "kind" to "environment",
            "schema" to 3,
            "synchronousReference" to
                "test-only extracted control flow with shared current production classifier/pairing/calculation/index dependencies",
            "harnessRevision" to "actual-read-body-unstaged-write-event-pump-v3-clock-calibration",
            "clockCalibrationNanoBefore" to calibrationNanoBefore,
            "clockCalibrationUtc" to calibrationUtc,
            "clockCalibrationEpochSecond" to calibrationUtc.epochSecond,
            "clockCalibrationNanoOfSecond" to calibrationUtc.nano,
            "clockCalibrationNanoAfter" to calibrationNanoAfter,
            "clockCalibrationBracketNs" to calibrationNanoAfter - calibrationNanoBefore,
            "clockCalibrationScope" to
                "one start-of-run Instant.now bracketed by System.nanoTime; later wall-clock drift is not bounded",
            "implementation" to implementation.name.lowercase(),
            "revision" to System.getProperty("issue93.measure.revision", "unspecified"),
            "ideBuild" to ApplicationInfo.getInstance().build.asString(),
            "javaVersion" to System.getProperty("java.version"),
            "os" to System.getProperty("os.name"),
            "arch" to System.getProperty("os.arch"),
            "warmups" to warmups,
            "repeats" to repeats,
            "executionContext" to "Dispatchers.Default for both implementations",
            "write" to "actual EDT no-op WriteAction; document is unchanged",
            "probe" to "first body queues write; no reader waits for EDT, no staging gate",
            "performancePump" to
                "single IDE event with ThreadContext reset; completion check each cycle;100us idle park request only when queue empty; actual sample cadence recorded",
            "previousSchemaPump" to
                "schema2 used PlatformTestUtil.waitWithEventsDispatching which drains events then sleeps10ms before rechecking; queued writes could miss the analysis interval",
            "readScope" to "actual body enter/exit under read access; excludes lock acquisition/release overhead",
            "bodyStatusScope" to "body completion only; readAction may retry after a completed body",
            "observerOverhead" to "body timestamps include observer records and first write enqueue",
        )
        for ((name, filename, source) in corpora()) {
            myFixture.configureByText(filename, source)
            val input = AnalysisInput(
                editor = myFixture.editor,
                fileType = myFixture.file.fileType,
                coverage = AnalysisCoverage(tokens = true, activePair = true, guidePosition = true),
                disabledLanguageIds = emptySet(),
            )
            val analyzer = BracketAnalysis()
            val reference = SynchronousAnalysisReference()
            emit(
                output,
                "kind" to "corpus",
                "corpus" to name,
                "characters" to source.length,
                "sha256Utf8" to sha256(source),
            )
            val warmup = AppExecutorUtil.getAppExecutorService().submit {
                runBlocking(Dispatchers.Default) {
                    repeat(warmups) {
                        when (implementation) {
                            Implementation.LEGACY -> ReadAction.compute<AnalysisOutcome, RuntimeException> {
                                reference.analyze(input, EmptyProgressIndicator())
                            }

                            Implementation.INCREMENTAL -> analyzer.analyzeInBackground(input)
                        }
                    }
                }
            }
            awaitPerformanceEvents("analysis write-wait warmup", 120) { warmup.isDone }
            warmup.get()
            repeat(repeats) { iteration ->
                measureWriteWait(output, name, iteration, analyzer, reference, input, implementation)
            }
        }
    }

    private fun measureWriteWait(
        output: Path,
        corpus: String,
        iteration: Int,
        analyzer: BracketAnalysis,
        reference: SynchronousAnalysisReference,
        input: AnalysisInput,
        implementation: Implementation,
    ) {
        val application = ApplicationManager.getApplication()
        val modality = ModalityState.current()
        val writerFinished = AtomicBoolean(false)
        val active = AtomicBoolean(true)
        val writeQueued = AtomicLong(0L)
        val writeRequested = AtomicLong(0L)
        val writeAcquired = AtomicLong(0L)
        val writeEnded = AtomicLong(0L)
        val writerFailure = AtomicReference<Throwable>()
        val analysisStarted = AtomicBoolean(false)
        val analysisFinished = AtomicBoolean(false)
        val analysisStart = AtomicLong(0L)
        val analysisEnd = AtomicLong(0L)
        val analysisStartThread = AtomicReference<String>()
        val analysisEndThread = AtomicReference<String>()
        val analysisJob = AtomicReference<Job>()
        val legacyIndicator = EmptyProgressIndicator()
        val outcome = AtomicReference<String>()
        val observer = CaptureProbe {
            writeQueued.set(System.nanoTime())
            application.invokeLater(
                {
                    try {
                        if (active.get()) {
                            writeRequested.set(System.nanoTime())
                            WriteAction.run<RuntimeException> {
                                writeAcquired.set(System.nanoTime())
                            }
                            writeEnded.set(System.nanoTime())
                        }
                    } catch (failure: Throwable) {
                        writerFailure.set(failure)
                    } finally {
                        writerFinished.set(true)
                    }
                },
                modality,
            )
        }
        val started = System.nanoTime()
        val worker = AppExecutorUtil.getAppExecutorService().submit {
            runBlocking(Dispatchers.Default) {
                withContext(observer) {
                    analysisJob.set(currentCoroutineContext().job)
                    analysisStarted.set(true)
                    analysisStartThread.set(Thread.currentThread().name)
                    analysisStart.set(System.nanoTime())
                    try {
                        val result = when (implementation) {
                            Implementation.LEGACY -> {
                                val requestId = observer.requested(LEGACY_ANALYSIS)
                                ReadAction.compute<AnalysisOutcome, RuntimeException> {
                                    val attemptId = observer.entered(requestId, LEGACY_ANALYSIS)
                                    var completed = false
                                    var failure: Throwable? = null
                                    try {
                                        reference.analyze(input, legacyIndicator).also { completed = true }
                                    } catch (problem: Throwable) {
                                        failure = problem
                                        throw problem
                                    } finally {
                                        observer.exited(attemptId, completed, failure)
                                    }
                                }
                            }

                            Implementation.INCREMENTAL -> analyzer.analyzeInBackground(input)
                        }
                        outcome.set(result?.javaClass?.simpleName ?: "invalidated")
                    } finally {
                        analysisEnd.set(System.nanoTime())
                        analysisEndThread.set(Thread.currentThread().name)
                        analysisFinished.set(true)
                    }
                }
            }
        }
        try {
            val pump = awaitPerformanceEvents("background analysis and EDT no-op write complete", 30) {
                worker.isDone && writerFinished.get()
            }
            worker.get()
            writerFailure.get()?.let { throw AssertionError("EDT write probe failed", it) }
            val requested = writeRequested.get()
            val acquired = writeAcquired.get()
            val ended = writeEnded.get()
            check(requested != 0L && requested <= acquired && acquired <= ended)
            val attempts = observer.attempts()
            check(attempts.isNotEmpty() && attempts.all { it.ended >= it.entered })
            // Actual write acquisition must be outside every recorded read-body interval.
            check(attempts.none { it.entered <= acquired && acquired < it.ended })
            val durations = attempts.map { it.ended - it.entered }
            emit(
                output,
                "kind" to "write-wait-sample",
                "sampleStartNano" to started,
                "corpus" to corpus,
                "implementation" to implementation.name.lowercase(),
                "iteration" to iteration,
                "analysisWallNs" to analysisEnd.get() - analysisStart.get(),
                "wallNs" to maxOf(analysisEnd.get(), ended) - started,
                "writeQueueDelayNs" to requested - writeQueued.get(),
                "writeWaitNs" to acquired - requested,
                "probeGateNs" to 0L,
                "pumpElapsedNs" to pump.finishedNs - pump.startedNs,
                "pumpConditionChecks" to pump.checks,
                "pumpDispatchedEvents" to pump.events,
                "pumpIdleParks" to pump.parks,
                "pumpActualParkedNs" to pump.parkedNs,
                "pumpMaximumConditionCheckGapNs" to pump.maximumCheckGapNs,
                "completionToPumpObservationNs" to pump.finishedNs - maxOf(analysisEnd.get(), ended),
                "readBodyCount" to attempts.size,
                "readBodyTotalNs" to durations.sum(),
                "readBodyMaximumNs" to durations.maxOrNull(),
                "failedReadBodyCount" to attempts.count { !it.completed },
                "writeWaitReadBodyOverlapNs" to attempts.sumOf {
                    maxOf(0L, minOf(it.ended, acquired) - maxOf(it.entered, requested))
                },
                "writeRequestedDuringAnalysis" to (requested in analysisStart.get()..analysisEnd.get()),
                "writeQueuedFromStartNs" to writeQueued.get() - started,
                "writeRequestFromStartNs" to requested - started,
                "writeAcquireFromStartNs" to acquired - started,
                "writeEndFromStartNs" to ended - started,
                "analysisStartFromStartNs" to analysisStart.get() - started,
                "analysisEndFromStartNs" to analysisEnd.get() - started,
                "analysisStartThread" to analysisStartThread.get(),
                "analysisEndThread" to analysisEndThread.get(),
                "outcome" to outcome.get(),
            )
            for (request in observer.requests()) {
                emit(
                    output,
                    "kind" to "read-request",
                    "corpus" to corpus,
                    "iteration" to iteration,
                    "requestId" to request.id,
                    "phase" to phaseName(request.phase),
                    "requestFromStartNs" to request.requested - started,
                    "thread" to request.thread,
                )
            }
            for (attempt in attempts) {
                val request = observer.request(attempt.requestId)
                emit(
                    output,
                    "kind" to "read-body",
                    "corpus" to corpus,
                    "iteration" to iteration,
                    "attemptId" to attempt.id,
                    "requestId" to attempt.requestId,
                    "phase" to phaseName(attempt.phase),
                    "enterFromStartNs" to attempt.entered - started,
                    "exitFromStartNs" to attempt.ended - started,
                    "requestToEnterNs" to attempt.entered - request.requested,
                    "bodyNs" to attempt.ended - attempt.entered,
                    "completed" to attempt.completed,
                    "failureClass" to attempt.failureClass,
                    "thread" to attempt.thread,
                )
            }
        } finally {
            active.set(false)
            legacyIndicator.cancel()
            analysisJob.get()?.cancel()
            worker.cancel(true)
            awaitPerformanceEvents("analysis write-wait probe cleanup") {
                (!analysisStarted.get() || analysisFinished.get()) &&
                    (!observer.hasEntered() || writerFinished.get())
            }
        }
    }

    private class CaptureProbe(private val queueWrite: () -> Unit) : AnalysisCaptureObserver() {
        private val nextRequest = AtomicLong(0L)
        private val nextAttempt = AtomicLong(0L)
        private val firstBody = AtomicBoolean(true)
        private val requests = ConcurrentHashMap<Long, ReadRequest>()
        private val attempts = ConcurrentHashMap<Long, ReadAttempt>()

        override fun requested(phase: Int): Long {
            val requested = System.nanoTime()
            val id = nextRequest.incrementAndGet()
            requests[id] = ReadRequest(id, phase, requested, Thread.currentThread().name)
            return id
        }

        override fun entered(requestId: Long, phase: Int): Long {
            val entered = System.nanoTime()
            check(ApplicationManager.getApplication().isReadAccessAllowed)
            val id = nextAttempt.incrementAndGet()
            attempts[id] = ReadAttempt(id, requestId, phase, entered, Thread.currentThread().name)
            if (firstBody.compareAndSet(true, false)) queueWrite()
            return id
        }

        override fun exited(attemptId: Long, completed: Boolean, failure: Throwable?) {
            val ended = System.nanoTime()
            val attempt = checkNotNull(attempts[attemptId])
            attempt.completed = completed
            attempt.failureClass = failure?.javaClass?.simpleName
            attempt.ended = ended
        }

        fun hasEntered(): Boolean = !firstBody.get()

        fun request(id: Long): ReadRequest = checkNotNull(requests[id])

        fun requests(): List<ReadRequest> = requests.values.sortedBy { it.id }

        fun attempts(): List<ReadAttempt> = attempts.values.sortedBy { it.id }
    }

    private data class ReadRequest(val id: Long, val phase: Int, val requested: Long, val thread: String)

    private class ReadAttempt(
        val id: Long,
        val requestId: Long,
        val phase: Int,
        val entered: Long,
        val thread: String,
    ) {
        @Volatile var ended: Long = 0L

        @Volatile var completed: Boolean = false

        @Volatile var failureClass: String? = null
    }

    private enum class Implementation {
        LEGACY,
        INCREMENTAL,
    }

    private fun phaseName(phase: Int): String =
        if (phase == LEGACY_ANALYSIS) "legacy-analysis" else AnalysisCaptureObserver.phaseName(phase)

    private fun corpora(): List<Triple<String, String, String>> = listOf(
        Triple("ordinary", "Ordinary.java", "class Ordinary {\n" + "  void run() { call(); }\n".repeat(2_000) + "}\n"),
        Triple("nested", "Nested.java", "{".repeat(20_000) + "x" + "}".repeat(20_000)),
        Triple("close-only", "Closers.java", ")]}\n".repeat(40_000)),
        Triple("large-whitespace", "Whitespace.java", "{\n" + " \t".repeat(250_000) + "x\n}\n"),
        Triple("xml", "Nested.xml", "<root>\n" + "  <item><value>x</value></item>\n".repeat(5_000) + "</root>\n"),
    )

    private fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun emit(output: Path, vararg fields: Pair<String, Any?>) {
        val json = (
            listOf<Pair<String, Any?>>(
                "runId" to runId,
            ) + fields
            ).joinToString(prefix = "{", postfix = "}\n") { (key, value) ->
            "${quoted(key)}:${jsonValue(value)}"
        }
        Files.writeString(output, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> quoted(value.toString())
    }

    private fun quoted(value: String): String = "\"" + value.flatMap { character ->
        when (character) {
            '\\' -> "\\\\".toList()
            '"' -> "\\\"".toList()
            '\n' -> "\\n".toList()
            '\r' -> "\\r".toList()
            '\t' -> "\\t".toList()
            else -> if (character < ' ') "\\u%04x".format(character.code).toList() else listOf(character)
        }
    }.joinToString("") + "\""

    private companion object {
        const val LEGACY_ANALYSIS = -1
    }
}
