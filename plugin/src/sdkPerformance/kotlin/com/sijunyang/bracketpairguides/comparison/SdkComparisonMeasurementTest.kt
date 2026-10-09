package com.sijunyang.bracketpairguides.comparison

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.management.ThreadMXBean
import java.awt.Dimension
import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opt-in real SDK raw measurements. No missing opt-in is reported as a passing measurement. */
class SdkComparisonMeasurementTest : BasePlatformTestCase() {
    fun testSdkComparison() {
        check(System.getProperty("issue97.perf.workload") != null) { "Explicit performance workload required" }
        val hostName = checkNotNull(System.getProperty("issue97.perf.host"))
        val host = Class.forName(hostName).getDeclaredConstructor().newInstance() as ComparisonHost
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failure = AtomicReference<Throwable?>()
        val complete = java.util.concurrent.atomic.AtomicBoolean()
        val pump = SdkEvidencePump()
        val task = scope.launch {
            try { Fixture(host, pump).runSelected() } catch (problem: Throwable) { failure.set(problem) }
            finally {
                try { withContext(NonCancellable + Dispatchers.EDT) { host.close() } }
                catch (cleanup: Throwable) {
                    failure.get()?.addSuppressed(cleanup) ?: failure.compareAndSet(null, cleanup)
                }
                finally { complete.set(true) }
            }
        }
        try {
            pump.await(600) { complete.get() }
            failure.get()?.let { throw AssertionError("SDK comparison failed", it) }
        } finally {
            task.cancel(); scope.cancel()
            PlatformTestUtil.waitWithEventsDispatching("SDK comparison cleanup", { task.isCompleted }, 30)
        }
    }

    private inner class Fixture(override val host: ComparisonHost, private val pump: SdkEvidencePump) : ComparisonFixture {
        override val warmups: Int = Integer.getInteger("issue97.perf.warmup", 100)
        override val repeats: Int = Integer.getInteger("issue97.perf.repeat", 30)
        override val cancellationTrials: Int = Integer.getInteger("issue97.perf.cancelTrials", 30)
        private val output = Path.of(checkNotNull(System.getProperty("issue97.perf.output")))
        private val runId = UUID.randomUUID().toString()
        private val sequence = AtomicLong()
        private val revision = checkNotNull(System.getProperty("issue97.perf.sourceRevision")).also {
            require(it.matches(Regex("[0-9a-f]{40}"))) { "Full measured production revision required" }
        }
        private val gson = Gson().newBuilder().serializeNulls().create()
        override val allocation = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf {
            it.isThreadAllocatedMemorySupported
        }?.also { if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true }
        private val selected = System.getProperty("issue97.perf.workload")
        init {
            require(warmups >= 0 && repeats > 0 && cancellationTrials > 0)
            require(output.isAbsolute) { "Use an absolute fresh evidence path" }
            Files.createDirectories(output.parent)
            Files.createFile(output) // Refuse stale append/mixed-run evidence.
        }
        suspend fun runSelected() {
            withContext(Dispatchers.EDT) { host.assertNoUnownedAttachments() }
            val descriptor = verifyMeasurementDescriptor(host)
            val idea = ApplicationInfo.getInstance()
            check(idea.build.baselineVersion == 241 && idea.fullVersion == "2024.1.7") {
                "Expected the frozen 2024.1.7 SDK fixture, got ${idea.build.asString()} / ${idea.fullVersion}"
            }
            System.getProperty("issue97.perf.expectedIdeBuild")?.let {
                check(idea.build.asString() == it) { "Selected SDK build differs from expected $it" }
            }
            emit("kind" to "environment", "schema" to 2, "implementation" to host.implementation,
                "descriptorIsolation" to descriptor,
                "workload" to selected, "ide" to idea.build.asString(), "ideVersion" to idea.fullVersion,
                "java" to System.getProperty("java.runtime.version"), "javaHome" to System.getProperty("java.home"),
                "javaVendor" to System.getProperty("java.vendor"),
                "adapterClassLoader" to host.javaClass.classLoader.toString(),
                "adapterCodeSource" to host.javaClass.protectionDomain.codeSource?.location?.toString(),
                "sdkCodeSource" to ApplicationInfo::class.java.protectionDomain.codeSource?.location?.toString(),
                "vm" to System.getProperty("java.vm.name"), "os" to System.getProperty("os.name"),
                "arch" to System.getProperty("os.arch"), "jvmArgs" to ManagementFactory.getRuntimeMXBean().inputArguments,
                "sourceRevision" to revision,
                "harnessClassSha256" to classSha(SdkComparisonMeasurementTest::class.java),
                "adapterClassSha256" to classSha(host.javaClass),
                "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
                "processors" to Runtime.getRuntime().availableProcessors(),
                "warmups" to warmups, "repeats" to repeats, "cancelTrials" to cancellationTrials,
                "resourceReplicatesPerCorpus" to 1,
                "resourceScope" to "reachable owned primitive array payload and bounded GC observation; not full retained heap",
                "eventPump" to "actual SDK one-event context-reset dispatch; 100us requested idle park; cumulative actual cadence retained, no staging readers",
                "scope" to "actual SDK direct source+calculator excludes factory/document lease lifecycle; execution actual request-to-observed markup; headless ACTIVE, no real focus/paint")
            when (selected) {
                "analysis" -> analysis()
                "write-wait" -> writer()
                "execution" -> execution()
                "repair" -> runRepairWorkload()
                "edit-restoration" -> runEditRestorationWorkload()
                "indentation" -> runIndentationWorkload()
                "native" -> runNativeWorkload()
                "payload" -> payload()
                "capture-release" -> captureRelease()
                else -> error("Unknown SDK workload: $selected")
            }
            withContext(Dispatchers.EDT) { host.assertNoUnownedAttachments() }
            emit("kind" to "completed", "workload" to selected,
                "coverage" to if (selected == "edit-restoration") "observation-complete-origin-unknown-and-refusal-censoring" else "complete-for-declared-scope",
                "pump" to pump.snapshot())
        }
        override suspend fun editor(name: String, text: String, mode: String): Editor = withContext(Dispatchers.EDT) {
            host.assertNoUnownedAttachments()
            val file = myFixture.configureByText(name, text)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
            EditorFactory.getInstance().createEditor(document, project, checkNotNull(file.virtualFile), false,
                if (mode == "tokens") EditorKind.PREVIEW else EditorKind.MAIN_EDITOR).also {
                emit("kind" to "corpus", "name" to name, "length" to text.length,
                    "sha256" to sha(text), "editorKind" to it.editorKind.name)
            }
        }
        override suspend fun release(editor: Editor) = withContext(NonCancellable + Dispatchers.EDT) {
            try {
                host.assertNoUnownedAttachments()
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
            check(editor.isDisposed && EditorFactory.getInstance().allEditors.none { it === editor }) {
                "Measured editor remains registered after release"
            }
            host.assertNoUnownedAttachments()
            emit("kind" to "editor-release", "editorIdentity" to System.identityHashCode(editor),
                "disposed" to editor.isDisposed, "registered" to false, "unownedAttachments" to false)
        }
        override suspend fun edit(editor: Editor, offset: Int, length: Int, replacement: String) =
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project) {
                    editor.document.replaceString(offset, offset + length, replacement)
                    PsiDocumentManager.getInstance(project).commitDocument(editor.document)
                }
            }
        override suspend fun <T> measure(workload: String, corpus: String, iteration: Int,
            metadata: Map<String, Any?>, reads: ReadRecorder?, operation: suspend () -> T): T {
            val observedReads = reads ?: host.newReads()
            val segments = SdkAllocationSegments(allocation)
            val cadenceBefore = pump.snapshot()
            val start = System.nanoTime()
            var success = false
            var failureClass: String? = null
            try {
                return withContext(observedReads.context + segments) { operation() }.also { success = true }
            } catch (failure: Throwable) {
                failureClass = failure.javaClass.name
                throw failure
            } finally {
                val end = System.nanoTime()
                withContext(NonCancellable) { segments.awaitClosed() }
                if (iteration >= 0) emit("kind" to "sample", "workload" to workload, "corpus" to corpus,
                    "iteration" to iteration, "wallNs" to end - start, "allocation" to segments.snapshot(),
                    "allocationSupported" to (allocation != null), "success" to success, "failureClass" to failureClass,
                    "pumpBefore" to cadenceBefore, "pumpAfter" to pump.snapshot(),
                    "requests" to observedReads.requests(), "reads" to observedReads.snapshot(), "metadata" to metadata)
            }
        }
        override fun emit(vararg fields: Pair<String, Any?>) {
            Files.writeString(output, gson.toJson(linkedMapOf("runId" to runId, "sequence" to sequence.incrementAndGet(), *fields)) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        private suspend fun analysis() {
            for ((name, text) in corpora()) {
                val editor = editor(name, text)
                try {
                    measureAnalysisIterations(editor, name, text)
                    repeat(cancellationTrials) { trial ->
                        val reads = host.newReads()
                        val owner = SupervisorJob()
                        val cancellationTrace = SdkAllocationSegments(allocation)
                        val scope = CoroutineScope(owner + Dispatchers.Default + reads.context + cancellationTrace)
                        try {
                            val job = scope.async { host.analyze(editor, "all") }
                            val deadline = System.nanoTime() + 5_000_000_000L
                            while (reads.requests().isEmpty() && !job.isCompleted && System.nanoTime() < deadline) delay(1.milliseconds)
                            val firstRequestObserved = reads.requests().isNotEmpty()
                            val requested = System.nanoTime()
                            val completed = job.isCompleted
                            job.cancel()
                            job.join()
                            val joined = System.nanoTime()
                            cancellationTrace.awaitClosed()
                            val bodies = reads.snapshot()
                            check(bodies.all { it.exitedNanos != 0L }) { "Cancelled calculation still owns an observed read body" }
                            val lastBodyExit = bodies.maxOfOrNull { it.exitedNanos } ?: requested
                            emit("kind" to "cancellation", "corpus" to name, "trial" to trial,
                                "completedBeforeRequest" to completed, "firstReadRequestObserved" to firstRequestObserved,
                                "cancelRequestedNanos" to requested, "joinedNanos" to joined,
                                "cancelToJoinNs" to joined - requested,
                                "cancelToObservedReadUnwindNs" to maxOf(joined, lastBodyExit) - requested,
                                "allocation" to cancellationTrace.snapshot(),
                                "scope" to "fixed trials; completion-before-request retained, never retried; join includes scheduling/unwind, earliest production cancellation check unobserved",
                                "requests" to reads.requests(), "reads" to bodies)
                        } finally {
                            owner.cancel()
                            withContext(NonCancellable) { owner.join() }
                        }
                    }
                } finally { release(editor) }
            }
        }

        /** Keeps the hot previous result through each next calculation, then releases the local owner before cancellation trials. */
        private suspend fun measureAnalysisIterations(editor: Editor, name: String, text: String) {
            var retained: AnalysisHandle? = null
            repeat(warmups + repeats) { n ->
                val previous = retained
                val result = measure("analysis", name, n - warmups) { host.analyze(editor, "all") }
                Reference.reachabilityFence(previous)
                retained = result
                if (n >= warmups) emit("kind" to "result", "workload" to "analysis", "corpus" to name,
                    "iteration" to n - warmups, "shape" to result.shape(),
                    "pair" to result.sample(text.indexOf('{').coerceAtLeast(0)))
            }
            Reference.reachabilityFence(retained)
        }

        private suspend fun payload() {
            for ((name, text) in corpora()) {
                val editor = editor(name, text)
                val evidence: ResourceEvidence
                try {
                    repeat(warmups) { host.analyze(editor, "all") }
                    evidence = inspectResult(editor, name)
                } finally { release(editor) }
                val release = evidence.awaitRelease()
                emit("kind" to "payload-release", "corpus" to name, "resourceReplicate" to 0,
                    "resourceReplicates" to 1, "resourceWarmups" to warmups,
                    "resultAndEditorReleased" to true, "observation" to release)
                check(release["released"] == true) { "Owned result storage release was not observed for $name" }
            }
        }

        private suspend fun inspectResult(editor: Editor, name: String): ResourceEvidence {
            val result = measure("payload", name, 0) { host.analyze(editor, "all") }
            val evidence = ResourceEvidence().also { it.result(result.payload) }
            emit("kind" to "payload", "corpus" to name, "resourceReplicate" to 0,
                "shape" to result.shape(), "payload" to evidence.summary())
            return evidence // No strong result or owned array escapes this helper.
        }
        private suspend fun writer() {
            for ((name, text) in corpora()) {
                val editor = editor(name, text)
                try {
                    repeat(warmups) { host.analyze(editor, "all") }
                    repeat(repeats) { n ->
                        val reads = host.newReads()
                        val owner = SupervisorJob()
                        val scope = CoroutineScope(owner + Dispatchers.Default + reads.context)
                        val analysisStarted = AtomicLong()
                        val analysisFinished = AtomicLong()
                        try {
                            val job = scope.async {
                                analysisStarted.set(System.nanoTime())
                                try { host.analyze(editor, "all") }
                                finally { analysisFinished.set(System.nanoTime()) }
                            }
                            val deadline = System.nanoTime() + 5_000_000_000L
                            while (reads.snapshot().isEmpty() && !job.isCompleted && System.nanoTime() < deadline) delay(1.milliseconds)
                            val bodyObserved = reads.snapshot().isNotEmpty()
                            val queued = System.nanoTime()
                            val incompleteAtQueue = analysisFinished.get() == 0L
                            var requested = 0L
                            var acquired = 0L
                            var incompleteAtRequest = false
                            withContext(Dispatchers.EDT) {
                                incompleteAtRequest = analysisFinished.get() == 0L
                                requested = System.nanoTime()
                                ApplicationManager.getApplication().runWriteAction { acquired = System.nanoTime() }
                            }
                            val result = job.await()
                            val bodies = reads.snapshot()
                            emit("kind" to "write-wait", "corpus" to name, "iteration" to n,
                                "firstBodyObserved" to bodyObserved, "analysisIncompleteAtQueue" to incompleteAtQueue,
                                "analysisIncompleteAtActualWriteRequest" to incompleteAtRequest,
                                "queuedNanos" to queued, "requestedNanos" to requested,
                                "acquiredNanos" to acquired, "queueDelayNs" to requested - queued,
                                "waitNs" to acquired - requested,
                                "writerRequestedInsideBody" to bodies.any { requested >= it.enteredNanos && requested <= it.exitedNanos },
                                "analysisStartedNanos" to analysisStarted.get(), "analysisFinishedNanos" to analysisFinished.get(),
                                "scope" to "unstaged actual EDT no-op WriteAction request-to-acquisition; dispatch delay separate; misses retained without retries",
                                "shape" to result.shape(), "requests" to reads.requests(), "reads" to bodies)
                        } finally {
                            owner.cancel()
                            withContext(NonCancellable) { owner.join() }
                        }
                    }
                } finally { release(editor) }
            }
        }
        private suspend fun execution() {
            val text = "class ExecutionMeasure {\n  void run() {\n" +
                "    call(new int[] { 1, 2 });\n".repeat(256) + "  }\n}\n"
            val expectedTokenOffsets = text.indices.filter { text[it] in "()[]{}" }
            check(expectedTokenOffsets.size == 1542)
            for (mode in listOf("all", "tokens")) {
                val editor = viewer("ExecutionMeasure.java", text, mode)
                try {
                    repeat(warmups + repeats) { n ->
                        var handle: ExecutionHandle? = null
                        var constructionWallNs = -1L
                        var constructionEdtBytes: Long? = null
                        var requestWallNs = -1L
                        var requestEdtBytes: Long? = null
                        var closeWallNs = -1L
                        var closeEdtBytes: Long? = null
                        val trace = SdkAllocationSegments(allocation)
                        val underlying = host.newReads()
                        val reads = object : ReadRecorder by underlying {
                            override val context = underlying.context + trace
                        }
                        try {
                            // Previous owned UI is closed before editing, preventing listener-triggered extra work.
                            edit(editor, editor.document.textLength, 0, "\n")
                            val requested = withContext(Dispatchers.EDT) {
                                editor.caretModel.moveToOffset(text.indexOf("call") + 1)
                                val constructionBefore = allocated()
                                val constructionStart = System.nanoTime()
                                val created: ExecutionHandle
                                try {
                                    created = host.execution(editor, mode, reads) { }
                                    handle = created
                                }
                                finally {
                                    constructionWallNs = System.nanoTime() - constructionStart
                                    constructionEdtBytes = delta(constructionBefore, allocated())
                                }
                                val requestBefore = allocated()
                                val requestStart = System.nanoTime()
                                try { created.request() }
                                finally {
                                    requestWallNs = System.nanoTime() - requestStart
                                    requestEdtBytes = delta(requestBefore, allocated())
                                }
                                requestStart
                            }
                            // Language 1.9 cannot smart-cast the nullable cleanup owner captured by changing closures.
                            @Suppress("RedundantRequireNotNullCall")
                            val active = checkNotNull(handle)
                            val deadline = System.nanoTime() + 30_000_000_000L
                            var count = 0
                            while (count == 0 && System.nanoTime() < deadline) {
                                count = withContext(Dispatchers.EDT) { active.markupCount() }
                                if (count == 0) delay(1.milliseconds)
                            }
                            check(count > 0) { "No actual accepted markup" }
                            val observed = System.nanoTime()
                            val refresh = withContext(Dispatchers.EDT) {
                                val before = active.markup()
                                checkTokenRanges(before, expectedTokenOffsets, mode)
                                val bytes = allocated()
                                val start = System.nanoTime()
                                active.refresh()
                                val wall = System.nanoTime() - start
                                val afterBytes = allocated()
                                val after = active.markup()
                                checkTokenRanges(after, expectedTokenOffsets, mode)
                                mapOf("wallNs" to wall, "edtAllocatedBytes" to delta(bytes, afterBytes),
                                    "reusedMarkupIdentities" to before.count { old -> after.any { it === old } },
                                    "beforeCount" to before.size, "afterCount" to after.size,
                                    "scope" to "synchronous unchanged-presentation UI callbacks; baseline may return early, no forced internal render")
                            }
                            // Do not await controller trace closure until the current request has unwound.
                            val workerDeadline = System.nanoTime() + 5_000_000_000L
                            while (active.workerActive() && System.nanoTime() < workerDeadline) delay(1.milliseconds)
                            check(!active.workerActive()) { "Published request did not unwind" }
                            trace.awaitClosed()
                            if (n >= warmups) emit("kind" to "execution", "mode" to mode, "iteration" to n - warmups,
                                "requestToObservedMarkupNs" to observed - requested, "markupCount" to count,
                                "sourceSha256" to sha(text), "expectedTokenMarks" to expectedTokenOffsets.size,
                                "synchronousRequestEntry" to mapOf(
                                    "wallNs" to requestWallNs, "edtAllocatedBytes" to requestEdtBytes,
                                    "scope" to "actual synchronous handle.request on EDT; excludes handle construction and subsequent async work",
                                    "nonAdditiveWithAsyncAllocation" to true),
                                "unchangedPresentationCallback" to refresh, "allocation" to trace.snapshot(),
                                "requests" to reads.requests(), "reads" to reads.snapshot())
                        } finally {
                            withContext(NonCancellable + Dispatchers.EDT) {
                                handle?.let { owned ->
                                    val before = allocated()
                                    val start = System.nanoTime()
                                    try { owned.close() }
                                    finally {
                                        closeWallNs = System.nanoTime() - start
                                        closeEdtBytes = delta(before, allocated())
                                    }
                                }
                            }
                            withContext(NonCancellable) {
                                val deadline = System.nanoTime() + 5_000_000_000L
                                while (handle?.workerActive() == true && System.nanoTime() < deadline) delay(1.milliseconds)
                                check(handle?.workerActive() != true) { "Execution work did not unwind" }
                                trace.awaitClosed()
                            }
                            if (n >= warmups) emit("kind" to "execution-lifecycle", "mode" to mode,
                                "iteration" to n - warmups,
                                "construction" to mapOf("wallNs" to constructionWallNs,
                                    "edtAllocatedBytes" to constructionEdtBytes,
                                    "scope" to "test bridge/factory/session creation and listeners; outside request latency"),
                                "request" to mapOf("wallNs" to requestWallNs, "edtAllocatedBytes" to requestEdtBytes,
                                    "scope" to "actual synchronous handle.request on EDT"),
                                "close" to mapOf("wallNs" to closeWallNs, "edtAllocatedBytes" to closeEdtBytes,
                                    "scope" to "test bridge/factory/session synchronous disposal; async cancellation unwind excluded"),
                                "nonAdditiveWithAsyncAllocation" to true,
                                "allocationScopeRule" to "separate per-thread counters and inherited coroutine segments; never sum overlapping scopes or infer whole-session allocation")
                        }
                    }
                } finally { release(editor) }
            }
        }

        private suspend fun viewer(name: String, text: String, mode: String): Editor = withContext(Dispatchers.EDT) {
            host.assertNoUnownedAttachments()
            val file = myFixture.configureByText(name, text)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            EditorFactory.getInstance().createViewer(myFixture.editor.document, project,
                if (mode == "tokens") EditorKind.PREVIEW else EditorKind.MAIN_EDITOR).also {
                (it as EditorEx).highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file.fileType)
                // Actual SDK geometry supplies the same full-document viewport used by the old execution probe.
                val height = (it.lineHeight.toLong() * (it.document.lineCount + 2)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val extent = Dimension(1200, height)
                it.component.size = extent
                it.component.doLayout()
                it.scrollPane.size = extent
                it.scrollPane.doLayout()
                it.scrollPane.viewport.extentSize = extent
                it.scrollPane.viewport.viewSize = extent
                it.contentComponent.preferredSize = extent
                it.contentComponent.size = extent
                it.scrollPane.viewport.doLayout()
                val visible = it.calculateVisibleRange()
                check(visible.startOffset == 0 && visible.endOffset >= text.lastIndexOf('}') + 1) {
                    "Actual SDK viewport is not the full frozen execution corpus: $visible"
                }
                emit("kind" to "corpus", "name" to name, "length" to text.length, "sha256" to sha(text),
                    "editorKind" to it.editorKind.name, "viewer" to it.isViewer,
                    "viewportWidth" to it.scrollingModel.visibleArea.width,
                    "viewportHeight" to it.scrollingModel.visibleArea.height,
                    "reportedVisibleStart" to visible.startOffset, "reportedVisibleEnd" to visible.endOffset)
            }
        }

        private fun checkTokenRanges(marks: List<Any>, expected: List<Int>, mode: String) {
            val tokens = marks.filterIsInstance<com.intellij.openapi.editor.markup.RangeHighlighter>()
                .filter { it.isValid && it.textAttributesKey?.externalName?.startsWith("BRACKET_PAIR_GUIDES_BRACKET_DEPTH_") == true }
            val endpoints = tokens.filter { it.endOffset == it.startOffset + 1 }.map { it.startOffset }.sorted()
            check(endpoints == expected) { "Actual colored endpoint ranges differ: expected ${expected.size}, actual ${endpoints.size}" }
            val other = marks.filterIsInstance<com.intellij.openapi.editor.markup.RangeHighlighter>().filter { it !in tokens }
            if (mode == "tokens") check(other.isEmpty()) { "PREVIEW must publish tokens only" }
            else {
                check(other.any { it.customRenderer != null }) { "MAIN_EDITOR guide markup is missing" }
                check(other.any { it.layer == com.intellij.openapi.editor.markup.HighlighterLayer.ELEMENT_UNDER_CARET }) {
                    "MAIN_EDITOR active pair markup is missing"
                }
            }
        }
        private fun allocated(): Long = allocation?.getThreadAllocatedBytes(Thread.currentThread().id) ?: -1
        private fun delta(before: Long, after: Long): Long? = if (before in 0L..after) after - before else null
        private suspend fun captureRelease() {
            for (xml in listOf(false, true)) for (units in listOf(2000, 20000, 100000)) {
                val text = if (xml) buildString { repeat(units) { append("</tag").append(it).append(">\n") } }
                    else ")]}\n".repeat(units)
                val name = if (xml) "Closers.xml" else "Closers.java"
                val editor = editor(name, text)
                var handle: CaptureHandle? = null
                try {
                    handle = host.capture(editor, "all")
                    val evidence = ResourceEvidence()
                    var offset = 0
                    var chunks = 0
                    var occurrences = 0L
                    var maximumBatch = 0
                    var exhausted = false
                    val started = System.nanoTime()
                    while (!exhausted) {
                        val fact = inspectChunk(handle, offset, evidence)
                        check(fact.nextOffset in offset..text.length)
                        check(fact.exhausted || fact.nextOffset > offset) { "Capture made no progress" }
                        offset = fact.nextOffset
                        exhausted = fact.exhausted
                        chunks++
                        occurrences += fact.size
                        maximumBatch = maxOf(maximumBatch, fact.size)
                    }
                    check(offset == text.length)
                    val captureAndInspectionNs = System.nanoTime() - started
                    val release = evidence.awaitRelease()
                    emit("kind" to "capture-release", "corpus" to "$name-$units", "resourceReplicate" to 0,
                        "resourceReplicates" to 1, "resourceWarmups" to 0,
                        "characters" to text.length, "sha256" to sha(text), "chunks" to chunks,
                        "capturedOccurrences" to occurrences, "maximumBatchTokens" to maximumBatch,
                        "exhausted" to true, "finalOffset" to offset,
                        "captureAndDiagnosticInspectionNs" to captureAndInspectionNs,
                        "scope" to "all real bounded SDK raw captures consumed; no pairing; diagnostic traversal is included only in diagnostic wall, not a throughput metric",
                        "retainedAdapterType" to handle.retainedInput.javaClass.name,
                        "editorAndAdapterRetainedDuringGc" to true,
                        "payload" to evidence.summary(), "observation" to release,
                        "contextScope" to "raw context String survivors; classifier scratch may validly retain final context; no all-context-release claim")
                    Reference.reachabilityFence(handle.retainedInput)
                    Reference.reachabilityFence(editor)
                    check(release["nonClearedBatches"] == 0 && release["nonClearedArrays"] == 0) {
                        "Consumed batch/array release was not observed for $name-$units"
                    }
                } finally { try { handle?.close() } finally { release(editor) } }
            }
        }
        private suspend fun inspectChunk(handle: CaptureHandle, offset: Int, evidence: ResourceEvidence): ChunkFacts {
            val chunk = handle.next(offset)
            evidence.capture(chunk.payload)
            return ChunkFacts(chunk.nextOffset, chunk.exhausted, chunk.size)
        }

    }
}
private data class ChunkFacts(val nextOffset: Int, val exhausted: Boolean, val size: Int)
private fun corpora() = listOf(
    "Ordinary.java" to "class Ordinary {\n" + "  void run() { call(); }\n".repeat(2_000) + "}\n",
    "Nested.java" to "{".repeat(20_000) + "x" + "}".repeat(20_000),
    "Closers.java" to ")]}\n".repeat(40_000),
    "Whitespace.java" to "{\n" + " \t".repeat(250_000) + "x\n}\n",
    "Nested.xml" to "<root>\n" + "  <item><value>x</value></item>\n".repeat(5_000) + "</root>\n",
)
private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 255) }
private fun classSha(type: Class<*>): String {
    val name = "/" + type.name.replace('.', '/') + ".class"
    val bytes = checkNotNull(type.getResourceAsStream(name)).use { it.readBytes() }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
