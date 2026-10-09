package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.reference.SynchronousAnalysisReference
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Opt-in raw measurements, without timing assertions or changes to normal test execution. */
class AnalysisBaselineMeasurementTest : BasePlatformTestCase() {
    private val runId = UUID.randomUUID().toString()

    fun testBaselineMeasurements() {
        if (!java.lang.Boolean.getBoolean("issue93.measure")) return
        val output = Path.of(System.getProperty("issue93.measure.output", "build/reports/issue93-baseline.jsonl"))
        output.parent?.let { Files.createDirectories(it) }
        val implementation = when (val selected = System.getProperty("issue93.measure.implementation", "legacy")) {
            "legacy", "existing-BracketAnalysis" -> Implementation.LEGACY
            "incremental" -> Implementation.INCREMENTAL
            else -> error("Unknown measurement implementation: $selected")
        }
        val warmups = Integer.getInteger("issue93.measure.warmups", 3).coerceAtLeast(0)
        val repeats = Integer.getInteger("issue93.measure.repeats", 7).coerceAtLeast(1)
        val cancellationTrials = Integer.getInteger("issue93.measure.cancelTrials", 30).coerceAtLeast(1)
        emit(
            output,
            "kind" to "environment",
            "schema" to 3,
            "synchronousReference" to "test-only extracted control flow with shared current production classifier/pairing/calculation/index dependencies",
            "harnessRevision" to "default-context-segment-allocation-repeated-cancellation-v3",
            "implementation" to implementation.name.lowercase(),
            "fixtureThread" to Thread.currentThread().name,
            "fixtureWaiting" to "EDT events pumped while worker completes, for both implementations",
            "revision" to System.getProperty("issue93.measure.revision", "unspecified"),
            "javaVersion" to System.getProperty("java.version"),
            "javaVm" to System.getProperty("java.vm.name"),
            "os" to System.getProperty("os.name"),
            "arch" to System.getProperty("os.arch"),
            "ideBuild" to ApplicationInfo.getInstance().build.asString(),
            "processors" to Runtime.getRuntime().availableProcessors(),
            "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
            "jvmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "),
            "warmups" to warmups,
            "repeats" to repeats,
            "cancellationTrials" to cancellationTrials,
            "cancellationTrialScope" to "fixed attempts per corpus after throughput/memory samples; completion-before-request retained as raw non-cancellation, without retries",
            "corpusSelection" to System.getProperty("issue93.measure.corpus", "all"),
            "writeWaitNs" to null,
            "memoryScope" to "whole JVM; GC and 1ms heap sampling are approximate",
            "readLockScope" to if (implementation == Implementation.LEGACY) {
                "read action body; excludes lock acquisition and release overhead"
            } else {
                "unmeasured: incremental has multiple suspend read actions; whole coroutine wall is not read duration"
            },
            "allocationScope" to "sum of inherited coroutine execution segments across threads; waiting excluded",
            "allocationExclusions" to "EDT, sampler, independent platform tasks, dispatcher work outside context activation",
            "allocationOverhead" to "includes trace frame/bookkeeping allocations; nested same-thread segments counted once",
            "executionContext" to "one top-level runBlocking(Dispatchers.Default) per corpus for both implementations",
            "executionThreadScope" to "inherited coroutine segments; independent platform helper tasks unobserved",
            "cancellationCheckScope" to "legacy indicator checks observed; incremental Job check observation unmeasured",
        )
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "issue93-analysis-measurement") }
        try {
            for ((name, filename, source) in corpora()) {
                val selected = System.getProperty("issue93.measure.corpus")
                if (selected != null && selected != name) continue
                myFixture.configureByText(filename, source)
                val input = AnalysisInput(
                    editor = myFixture.editor,
                    fileType = myFixture.file.fileType,
                    coverage = AnalysisCoverage(tokens = true, activePair = true, guidePosition = true),
                    disabledLanguageIds = emptySet(),
                )
                emit(
                    output,
                    "kind" to "corpus",
                    "corpus" to name,
                    "filename" to filename,
                    "characters" to source.length,
                    "sha256Utf8" to sha256(source),
                )
                val measurement = worker.submit {
                    runBlocking(Dispatchers.Default) {
                        measureCorpus(output, name, input, implementation, warmups, repeats, cancellationTrials)
                    }
                }
                PlatformTestUtil.waitWithEventsDispatching("analysis measurement for $name", { measurement.isDone }, 120)
                measurement.get(1, TimeUnit.SECONDS)
            }
        } finally {
            worker.shutdownNow()
        }
    }

    private suspend fun measureCorpus(
        output: Path,
        name: String,
        input: AnalysisInput,
        implementation: Implementation,
        warmups: Int,
        repeats: Int,
        cancellationTrials: Int,
    ) {
        val analyzer = BracketAnalysis()
        val reference = SynchronousAnalysisReference()
        val warmupThreads = ExecutionThreads()
        repeat(warmups) {
            withContext(warmupThreads) { execute(analyzer, reference, input, implementation, ReadTiming()) }
        }
        warmupThreads.awaitClosed()
        emit(output, "kind" to "warmup-threads", "corpus" to name, "executionThreads" to warmupThreads.names())
        val beforeGc = afterGcHeap()
        var retained: AnalysisOutcome? = null
        repeat(repeats) { iteration ->
            val allocation = allocationBean()
            val threadId = Thread.currentThread().id
            val gcBefore = gcCount()
            val heapBefore = heapBytes()
            val sampler = HeapSampler(heapBefore)
            val timing = ReadTiming()
            val executionThreads = ExecutionThreads(allocation)
            var completed = false
            val start = System.nanoTime()
            try {
                withContext(executionThreads) {
                    retained = execute(analyzer, reference, input, implementation, timing)
                    completed = true
                }
            } finally {
                val end = System.nanoTime()
                executionThreads.awaitClosed()
                sampler.close()
                emit(
                    output,
                    "kind" to "sample",
                    "corpus" to name,
                    "implementation" to implementation.name.lowercase(),
                    "iteration" to iteration,
                    "wallNs" to end - start,
                    "readAcquireNs" to timing.start?.let { it - start },
                    "readLockNs" to timing.end?.let { it - checkNotNull(timing.start) },
                    "threadAllocatedBytes" to null,
                    "directCoroutineAllocatedBytes" to executionThreads.allocatedBytes(),
                    "allocationSegmentCount" to executionThreads.segmentCount(),
                    "allocationTraceComplete" to executionThreads.isClosed(),
                    "measurementStartThreadId" to threadId,
                    "measurementReturnThread" to Thread.currentThread().name,
                    "measurementReturnThreadId" to Thread.currentThread().id,
                    "executionThreads" to executionThreads.names(),
                    "heapBeforeBytes" to heapBefore,
                    "heapAfterBytes" to heapBytes(),
                    "sampledPeakHeapBytes" to sampler.peak.get(),
                    "gcCountDelta" to gcCount() - gcBefore,
                    "sampleCompleted" to completed,
                    "outcome" to if (completed) retained?.javaClass?.simpleName ?: "invalidated" else "failed",
                    "matcherAvailability" to matcherAvailability(retained),
                )
            }
        }
        val heldAfterGc = afterGcHeap()
        emit(
            output,
            "kind" to "retention",
            "corpus" to name,
            "afterWarmupGcBytes" to beforeGc,
            "resultHeldAfterGcBytes" to heldAfterGc,
            "heldDeltaBytes" to heldAfterGc - beforeGc,
            "resultClass" to retained?.javaClass?.simpleName,
        )
        retained = null
        emit(output, "kind" to "released", "corpus" to name, "afterReleaseGcBytes" to afterGcHeap())
        repeat(cancellationTrials) { trial ->
            measureCancellation(output, name, analyzer, reference, input, implementation, trial, cancellationTrials)
        }
    }

    private suspend fun execute(
        analyzer: BracketAnalysis,
        reference: SynchronousAnalysisReference,
        input: AnalysisInput,
        implementation: Implementation,
        timing: ReadTiming,
    ): AnalysisOutcome? {
        return when (implementation) {
            Implementation.LEGACY -> ReadAction.compute<AnalysisOutcome, RuntimeException> {
                timing.start = System.nanoTime()
                try {
                    reference.analyze(input, EmptyProgressIndicator())
                } finally {
                    timing.end = System.nanoTime()
                }
            }
            Implementation.INCREMENTAL -> analyzer.analyzeInBackground(input)
        }
    }

    private suspend fun measureCancellation(
        output: Path,
        name: String,
        analyzer: BracketAnalysis,
        reference: SynchronousAnalysisReference,
        input: AnalysisInput,
        implementation: Implementation,
        trial: Int,
        trials: Int,
    ) {
        val requestTime = AtomicLong(0L)
        val observedTime = AtomicLong(0L)
        val requestThread = AtomicReference<String>()
        val executionThreads = ExecutionThreads()
        val timer = Executors.newSingleThreadScheduledExecutor()
        val delegate = EmptyProgressIndicator()
        val indicator = object : ProgressIndicator by delegate {
            override fun checkCanceled() {
                executionThreads.record()
                if (isCanceled && observedTime.get() == 0L) observedTime.compareAndSet(0L, System.nanoTime())
                delegate.checkCanceled()
            }
        }
        var scheduled: ScheduledFuture<*>? = null
        var canceled = false
        var thrownType: String? = null
        val returnedAt: Long
        executionThreads.record()
        val start = System.nanoTime()
        try {
            when (implementation) {
                Implementation.LEGACY -> withContext(executionThreads) {
                    scheduled = timer.schedule(
                        {
                            requestThread.set(Thread.currentThread().name)
                            requestTime.set(System.nanoTime())
                            indicator.cancel()
                        },
                        1,
                        TimeUnit.MILLISECONDS,
                    )
                    ReadAction.compute<AnalysisOutcome, RuntimeException> { reference.analyze(input, indicator) }
                }
                Implementation.INCREMENTAL -> withContext(executionThreads) {
                    supervisorScope {
                        val analysisJob = currentCoroutineContext().job
                        scheduled = timer.schedule(
                            {
                                requestThread.set(Thread.currentThread().name)
                                requestTime.set(System.nanoTime())
                                analysisJob.cancel(CancellationException("issue93 measurement cancellation"))
                            },
                            1,
                            TimeUnit.MILLISECONDS,
                        )
                        analyzer.analyzeInBackground(input)
                    }
                }
            }
        } catch (failure: CancellationException) {
            canceled = true
            thrownType = failure.javaClass.simpleName
        } catch (failure: ProcessCanceledException) {
            canceled = true
            thrownType = failure.javaClass.simpleName
        } finally {
            returnedAt = System.nanoTime()
            scheduled?.cancel(false)
            timer.shutdownNow()
        }
        executionThreads.awaitClosed()
        val requested = requestTime.get()
        val observed = observedTime.get()
        emit(
            output,
            "kind" to "cancellation",
            "corpus" to name,
            "trial" to trial,
            "trials" to trials,
            "implementation" to implementation.name.lowercase(),
            "cancellationTarget" to if (implementation == Implementation.LEGACY) "indicator" else "coroutine Job",
            "requestedDelayNs" to 1_000_000L,
            "wallNs" to returnedAt - start,
            "canceled" to canceled,
            "thrownType" to thrownType,
            "requestThread" to requestThread.get(),
            "returnThread" to Thread.currentThread().name,
            "executionThreads" to executionThreads.names(),
            "requestFromStartNs" to requested.takeIf { it != 0L }?.let { it - start },
            "requestAfterReturn" to (requested != 0L && requested > returnedAt),
            "checkLatencyNs" to observed.takeIf { it != 0L && requested != 0L }?.let { it - requested },
            "unwindLatencyNs" to requested.takeIf { canceled && it != 0L && it <= returnedAt }?.let { returnedAt - it },
        )
    }

    private enum class Implementation {
        LEGACY,
        INCREMENTAL,
    }

    private class ReadTiming {
        var start: Long? = null
        var end: Long? = null
    }

    /** Measures inherited execution intervals, including migration, without counting suspension gaps. */
    private class ExecutionThreads(private val allocation: ThreadMXBean? = null) :
        ThreadContextElement<ExecutionThreads.Segment>,
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ExecutionThreads>

        class Segment(val allocatedAtEntry: Long, var depth: Int = 1)

        private val threads = ConcurrentHashMap<Long, String>()
        private val current = ThreadLocal<Segment>()
        private val bytes = AtomicLong(0L)
        private val segments = AtomicLong(0L)
        private val openSegments = AtomicInteger(0)
        private val invalidCounter = AtomicBoolean(false)

        fun record() {
            val thread = Thread.currentThread()
            threads.putIfAbsent(thread.id, thread.name)
        }

        fun names(): List<String> = threads.entries.sortedBy { it.key }.map { "${it.value}#${it.key}" }

        fun allocatedBytes(): Long? = bytes.get().takeIf {
            allocation != null && !invalidCounter.get() && isClosed()
        }

        fun segmentCount(): Long = segments.get()

        fun isClosed(): Boolean = openSegments.get() == 0

        suspend fun awaitClosed() {
            // A restore on another thread can finish just after the awaiting owner resumes.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (!isClosed() && System.nanoTime() < deadline) yield()
        }

        override fun updateThreadContext(context: CoroutineContext): Segment {
            val nested = current.get()
            if (nested != null) {
                nested.depth++
                return nested
            }
            val before = allocation?.getThreadAllocatedBytes(Thread.currentThread().id) ?: -1L
            val segment = Segment(before)
            current.set(segment)
            openSegments.incrementAndGet()
            segments.incrementAndGet()
            record()
            return segment
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Segment) {
            oldState.depth--
            if (oldState.depth != 0) return
            val after = allocation?.getThreadAllocatedBytes(Thread.currentThread().id) ?: -1L
            if (allocation != null) {
                if (oldState.allocatedAtEntry < 0L || after < oldState.allocatedAtEntry) {
                    invalidCounter.set(true)
                } else {
                    bytes.addAndGet(after - oldState.allocatedAtEntry)
                }
            }
            current.remove()
            openSegments.decrementAndGet()
        }
    }

    private fun corpora(): List<Triple<String, String, String>> = listOf(
        Triple("ordinary", "Ordinary.java", "class Ordinary {\n" + "  void run() { call(); }\n".repeat(2_000) + "}\n"),
        Triple("nested", "Nested.java", "{".repeat(20_000) + "x" + "}".repeat(20_000)),
        Triple("close-only", "Closers.java", ")]}\n".repeat(40_000)),
        Triple("large-whitespace", "Whitespace.java", "{\n" + " \t".repeat(250_000) + "x\n}\n"),
        Triple("xml", "Nested.xml", "<root>\n" + "  <item><value>x</value></item>\n".repeat(5_000) + "</root>\n"),
    )

    private fun matcherAvailability(outcome: AnalysisOutcome?): String? = when (outcome) {
        is AnalysisOutcome.Complete -> outcome.snapshot.matcherAvailability.name
        is AnalysisOutcome.Limited -> outcome.snapshot.matcherAvailability.name
        else -> null
    }

    private fun allocationBean(): ThreadMXBean? =
        (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf { bean ->
            bean.isThreadAllocatedMemorySupported &&
                runCatching {
                    if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
                    bean.isThreadAllocatedMemoryEnabled
                }.getOrDefault(false)
        }

    private fun heapBytes(): Long = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

    private fun afterGcHeap(): Long {
        System.gc()
        System.runFinalization()
        System.gc()
        return heapBytes()
    }

    private fun gcCount(): Long = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0L, it.collectionCount) }

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
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
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

    private class HeapSampler(initialHeap: Long) : AutoCloseable {
        val peak = AtomicLong(initialHeap)
        private val active = AtomicBoolean(true)
        private val thread = Thread(
            {
                while (active.get()) {
                    val used = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
                    peak.accumulateAndGet(used, ::maxOf)
                    LockSupport.parkNanos(1_000_000L)
                }
            },
            "issue93-heap-sampler",
        ).apply {
            isDaemon = true
            start()
        }

        override fun close() {
            active.set(false)
            LockSupport.unpark(thread)
            thread.join(1_000L)
        }
    }
}
