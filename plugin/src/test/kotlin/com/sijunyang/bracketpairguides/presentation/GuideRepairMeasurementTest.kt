package com.sijunyang.bracketpairguides.presentation

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.awaitPerformanceEvents
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.editor.highlighting.GuideRepairExecution
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sun.management.ThreadMXBean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Opt-in paired evidence: frozen synchronous policy and actual independent repair execution. */
class GuideRepairMeasurementTest : BasePlatformTestCase() {
    private val runId = UUID.randomUUID().toString()

    fun testGuideRepairMeasurements() {
        if (!java.lang.Boolean.getBoolean("issue93.measure.guideRepair")) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        val output = Path.of(
            System.getProperty("issue93.measure.guideRepair.output", "build/reports/issue93-guide-repair.jsonl"),
        )
        output.parent?.let { Files.createDirectories(it) }
        val warmups = Integer.getInteger("issue93.measure.guideRepair.warmups", 5).coerceAtLeast(0)
        val repeats = Integer.getInteger("issue93.measure.guideRepair.repeats", 25).coerceAtLeast(1)
        val allocation = allocationBean()
        emit(
            output, "kind" to "environment", "schema" to 3,
            "implementations" to listOf("frozen_whole_presentation_reference", "independent_geometry_repair"),
            "harnessRevision" to "matched-presentation-event-aware-pump-v3",
            "previousSchemaScope" to "schema2 froze guideAfterChange only; UI callback scopes were not paired",
            "frozenReferenceCommit" to "516d4ed",
            "frozenPresentationOriginalSourceSha256" to
                "fffb030fc15629e7973184c2cbfc94adecc2f312562793df4f02e03310c7f847",
            "frozenFallbackOriginalSourceSha256" to "2d33cb291ef1dc291fa9c6e8a0b9547fb45ab170af54e3561eb38b820d524477",
            "frozenReference" to
                "whole ActiveGuidePresentation and complete GuidePositionFallback copied from516d4ed; shared TrackedBracketPair/ActivePairMarkup verified unchanged at staging",
            "revision" to System.getProperty("issue93.measure.revision", "unspecified"),
            "ideBuild" to ApplicationInfo.getInstance().build.asString(),
            "javaVersion" to System.getProperty("java.version"), "javaVm" to System.getProperty("java.vm.name"),
            "os" to System.getProperty("os.name"), "arch" to System.getProperty("os.arch"),
            "processors" to Runtime.getRuntime().availableProcessors(),
            "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
            "jvmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments,
            "warmups" to warmups, "repeats" to repeats, "tabSize" to TAB_SIZE,
            "lineCap" to LINE_CAP, "characterCap" to CHARACTER_CAP,
            "fixtureVersion" to 1,
            "baselineTimingScope" to
                "frozen ActiveGuidePresentation.refreshAfterDocumentChange including tracked-pair and markup updates, on EDT after completed real write; excludes edit/setup/geometry/hash/reporting",
            "uiCallbackTimingScope" to
                "ActiveGuidePresentation.refreshAfterDocumentChange plus immutable repair request construction and GuideRepairExecution.request; excludes real edit and restored pre-edit presentation",
            "repairTimingScope" to
                "actual Default calculateGuide, short cancellable read captures and unlocked indentation; callback-start to worker start/finish and EDT acceptance recorded separately",
            "acceptTimingScope" to
                "actual EDT ActiveGuidePresentation.publishRepair only; excludes dispatch/guard overhead from its phase but includes them in acceptance latency",
            "wholeRepairCost" to
                "UI callback latency plus direct worker capture/compute duration plus EDT acceptance duration; also report elapsed end-to-end completion latency separately",
            "allocationScope" to
                "EDT callback and acceptance current-thread deltas; worker direct coroutine execution segments tracked across thread hops and nested same-thread contexts",
            "allocationBookkeeping" to
                "includes trace bookkeeping performed inside measured segments; excludes suspension gaps, independent jobs, trace creation, event pumping and reporting; allocation sums omit launcher/dispatcher work outside phase boundaries",
            "allocationCounterAvailable" to (allocation != null),
            "excludedHostWork" to
                "EditorGuideSession orchestration, native event routing and full-analysis scheduling; harness directly exercises the production presentation and repair module interfaces",
            "concurrentPlatformWork" to
                "not globally disabled; event-aware EDT pump dispatches eventual repair publication",
            "performancePump" to
                "check completion before each single IDE event; reset ThreadContext as PlatformTestUtil does; idle park requested100us only when no event exists; watchdog10s",
            "pumpTimingLimit" to
                "park request is not guaranteed actual scheduling cadence; per-sample actual maximum condition-check gap, event count and parked time recorded; dispatch latency remains included",
            "previousPumpBytecode" to
                "IDE241 PlatformTestUtil.waitWithEventsDispatching drains queue then unconditionally sleeps10ms before checking completion again",
            "edit" to
                "alternating one-character equal-length space/tab replacement inside previous pair, completed before either timing interval",
        )
        for (corpus in corpora()) {
            emit(
                output, "kind" to "corpus", "corpus" to corpus.name, "fixtureVersion" to 1,
                "characters" to corpus.source.length, "documentLines" to corpus.source.count { it == '\n' } + 1,
                "sha256Utf8" to sha256(corpus.source),
                "tabbedSha256Utf8" to sha256(
                    corpus.source.replaceRange(corpus.editOffset, corpus.editOffset + 1, "\t"),
                ),
                "editOffset" to corpus.editOffset, "oldLength" to 1, "newLength" to 1,
                "pairCandidateLines" to corpus.candidateLines, "requiredPrefixCharacters" to corpus.prefixCharacters,
                "expectedBoundedResult" to if (corpus.refuses) "null" else "exact",
                "branch" to
                    if (corpus.candidateLines ==
                        0
                    ) {
                        "same-line geometry reuse/no repair job"
                    } else {
                        "post-edit forward exact repair"
                    },
            )
            measureReference(output, corpus, warmups, repeats, allocation)
            measureRepair(output, corpus, warmups, repeats, allocation)
        }
    }

    private fun measureReference(output: Path, corpus: Corpus, warmups: Int, repeats: Int, allocation: ThreadMXBean?) {
        myFixture.configureByText("${corpus.name}-reference.txt", corpus.source)
        val editor = myFixture.editor
        editor.settings.setTabSize(TAB_SIZE)
        val presentation = FrozenActiveGuidePresentation(editor)
        val preferences = BracketGuidePreferences()
        var tabbed = false
        try {
            repeat(warmups + repeats) { sample ->
                val previousPair = pairGeometry(editor.document)
                val previous = BracketGuide(
                    previousPair,
                    corpus.guideColumn,
                    if (tabbed) corpus.tabbedAnchorLine else corpus.spaceAnchorLine,
                )
                editor.caretModel.moveToOffset(corpus.editOffset + 1)
                presentation.replace(previousPair, previous, allowGuideFallback = false, preferences = preferences)
                val change = DocumentChange(corpus.editOffset, 1, 1)
                check(!change.isOutside(previousPair))
                replace(editor.document, corpus.editOffset, tabbed)
                tabbed = !tabbed
                val before = allocated(allocation)
                val started = System.nanoTime()
                presentation.refreshAfterDocumentChange(change, editor.caretModel.primaryCaret.offset, preferences)
                val wallNs = System.nanoTime() - started
                val bytes = allocationDelta(before, allocated(allocation))
                val result = displayedGuide(editor)
                emitSample(
                    output, corpus, sample, warmups, tabbed, result,
                    "implementation" to "frozen_whole_presentation_reference", "wallNs" to wallNs,
                    "uiCallbackWallNs" to wallNs, "presentationRefreshWallNs" to wallNs,
                    "requestAndScheduleWallNs" to 0L, "uiCallbackAllocatedBytes" to bytes,
                    "summedPhaseWallNs" to wallNs, "summedPhaseAllocatedBytes" to bytes,
                    "endToEndCompletionNs" to wallNs, "repairJobStarted" to false,
                    "resultAvailableOnCallbackReturn" to true, "writeCompletedBeforeTiming" to true,
                    "guideSessionInstalled" to (EditorGuideSessions.get(editor) != null),
                )
            }
        } finally {
            presentation.clear(preserveGuide = false)
        }
    }

    private fun measureRepair(output: Path, corpus: Corpus, warmups: Int, repeats: Int, allocation: ThreadMXBean?) {
        myFixture.configureByText("${corpus.name}-repair.txt", corpus.source)
        val editor = myFixture.editor
        editor.settings.setTabSize(TAB_SIZE)
        val lifetime = SupervisorJob()
        val scope = CoroutineScope(lifetime + Dispatchers.Default)
        val current = AtomicReference<RepairSample>()
        val presentation = ActiveGuidePresentation(editor)
        val execution = GuideRepairExecution(scope) { candidate, request ->
            val sample = checkNotNull(current.get())
            sample.workerStartedNs = System.nanoTime()
            try {
                val result = withContext(sample.trace) { GuideRepairExecution.calculateGuide(candidate, request) }
                sample.workerFinishedNs = System.nanoTime()
                sample.trace.awaitClosed()
                sample.workerReadyNs = System.nanoTime()
                sample.result = result
                result
            } finally {
                if (sample.workerFinishedNs == 0L) sample.workerFinishedNs = System.nanoTime()
                sample.workerFinished = true
            }
        }
        val preferences = BracketGuidePreferences()
        var tabbed = false
        try {
            repeat(warmups + repeats) { iteration ->
                val previousPair = pairGeometry(editor.document)
                val previous =
                    BracketGuide(
                        previousPair,
                        corpus.guideColumn,
                        if (tabbed) corpus.tabbedAnchorLine else corpus.spaceAnchorLine,
                    )
                editor.caretModel.moveToOffset(corpus.editOffset + 1)
                presentation.replace(previousPair, previous, allowGuideFallback = false, preferences = preferences)
                val change = DocumentChange(corpus.editOffset, 1, 1)
                check(!change.isOutside(previousPair))
                replace(editor.document, corpus.editOffset, tabbed)
                tabbed = !tabbed
                val pair = pairGeometry(editor.document)
                val sample = RepairSample(ExecutionThreads(allocation))
                current.set(sample)
                val before = allocated(allocation)
                val callbackStarted = System.nanoTime()
                presentation.refreshAfterDocumentChange(change, editor.caretModel.primaryCaret.offset, preferences)
                val presentationFinished = System.nanoTime()
                if (presentation.needsGuideRepair) {
                    val input =
                        AnalysisInput(
                            editor,
                            EditorSurfaceClassifier.fileType(editor),
                            GuideRepairRequest.COVERAGE,
                            emptySet(),
                        )
                    val request =
                        GuideRepairRequest(pair, input.stamp, input.fileType, input.disabledLanguageIds, exact = true)
                    execution.request(editor, request, isCurrent = {
                        presentation.currentPair == pair && presentation.adjustedPair == pair
                    }, publish = { guide ->
                        val acceptBefore = allocated(allocation)
                        val acceptStarted = System.nanoTime()
                        val accepted = presentation.publishRepair(guide, preferences)
                        val acceptFinished = System.nanoTime()
                        val acceptBytes = allocationDelta(acceptBefore, allocated(allocation))
                        sample.acceptStartedNs = acceptStarted
                        sample.acceptFinishedNs = acceptFinished
                        sample.acceptAllocatedBytes = acceptBytes
                        sample.accepted = accepted
                    })
                }
                val callbackFinished = System.nanoTime()
                val callbackBytes = allocationDelta(before, allocated(allocation))
                val immediateGuide = displayedGuide(editor)
                val hideInvariant = if (corpus.candidateLines == 0) immediateGuide != null else immediateGuide == null
                check(hideInvariant) { "Affected multiline guide must be hidden when the UI callback returns" }
                val pump = awaitPerformanceEvents("independent repair finished") {
                    lifetime.children.all { root -> root.children.none { !it.isCompleted } }
                }
                val result = if (corpus.candidateLines == 0) immediateGuide else sample.result
                val workerNs = if (sample.workerStartedNs !=
                    0L
                ) {
                    sample.workerFinishedNs - sample.workerStartedNs
                } else {
                    0L
                }
                val acceptNs = if (sample.accepted) sample.acceptFinishedNs - sample.acceptStartedNs else 0L
                val workerBytes = if (sample.workerStartedNs != 0L) sample.trace.allocatedBytes() else 0L
                val acceptBytes = if (sample.accepted) sample.acceptAllocatedBytes else 0L
                val completion = maxOf(callbackFinished, sample.acceptFinishedNs, sample.workerFinishedNs)
                val phaseBytes = if (callbackBytes != null && workerBytes != null &&
                    acceptBytes != null
                ) {
                    callbackBytes + workerBytes + acceptBytes
                } else {
                    null
                }
                emitSample(
                    output, corpus, iteration, warmups, tabbed, result,
                    "implementation" to "independent_geometry_repair", "writeCompletedBeforeTiming" to true,
                    "uiCallbackWallNs" to callbackFinished - callbackStarted,
                    "presentationRefreshWallNs" to presentationFinished - callbackStarted,
                    "requestAndScheduleWallNs" to callbackFinished - presentationFinished,
                    "uiCallbackAllocatedBytes" to callbackBytes,
                    "immediateHideInvariant" to hideInvariant,
                    "resultAvailableOnCallbackReturn" to (corpus.candidateLines == 0),
                    "repairJobStarted" to (sample.workerStartedNs != 0L),
                    "repairWorkerFinished" to sample.workerFinished,
                    "workerCaptureComputeWallNs" to workerNs, "workerDirectCoroutineAllocatedBytes" to workerBytes,
                    "workerExecutionThreads" to sample.trace.names(),
                    "workerExecutionSegments" to sample.trace.segmentCount(),
                    "workerAllocationSegmentsClosed" to sample.trace.isClosed(),
                    "workerStartFromCallbackStartNs" to
                        sample.workerStartedNs.takeIf { it != 0L }?.let { it - callbackStarted },
                    "workerFinishFromCallbackStartNs" to
                        sample.workerFinishedNs.takeIf { it != 0L }?.let { it - callbackStarted },
                    "publicationAccepted" to sample.accepted, "edtAcceptanceWallNs" to acceptNs,
                    "edtAcceptanceAllocatedBytes" to acceptBytes,
                    "acceptanceFromCallbackStartNs" to
                        sample.acceptFinishedNs.takeIf { it != 0L }?.let { it - callbackStarted },
                    "endToEndCompletionNs" to completion - callbackStarted,
                    "traceSettleAfterComputeNs" to
                        sample.workerReadyNs.takeIf { it != 0L }?.let { it - sample.workerFinishedNs },
                    "workerReadyToEdtAcceptanceStartNs" to
                        sample.acceptStartedNs.takeIf { it != 0L }?.let { it - sample.workerReadyNs },
                    "pumpElapsedNs" to pump.finishedNs - pump.startedNs,
                    "pumpConditionChecks" to pump.checks,
                    "pumpDispatchedEvents" to pump.events,
                    "pumpIdleParks" to pump.parks,
                    "pumpActualParkedNs" to pump.parkedNs,
                    "pumpMaximumConditionCheckGapNs" to pump.maximumCheckGapNs,
                    "completionToPumpObservationNs" to pump.finishedNs - completion,
                    "summedPhaseWallNs" to callbackFinished - callbackStarted + workerNs + acceptNs,
                    "summedPhaseAllocatedBytes" to phaseBytes,
                    "eventualVisibleGuideColumn" to displayedGuide(editor)?.guideColumn,
                    "eventualVisibleAnchorLine" to displayedGuide(editor)?.anchorLine,
                    "guideSessionInstalled" to (EditorGuideSessions.get(editor) != null),
                )
            }
        } finally {
            lifetime.cancel()
            Disposer.dispose(execution)
            awaitPerformanceEvents("repair measurement workers stop") { lifetime.children.none() }
            presentation.clear(preserveGuide = false)
        }
    }

    private fun replace(document: Document, offset: Int, tabbed: Boolean) {
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(offset, offset + 1, if (tabbed) " " else "\t")
        }
    }

    private fun displayedGuide(editor: Editor): BracketGuide? = editor.markupModel.allHighlighters
        .mapNotNull { (it.customRenderer as? BracketGuideDrawing)?.guide }.singleOrNull()

    private fun allocated(allocation: ThreadMXBean?): Long =
        allocation?.getThreadAllocatedBytes(Thread.currentThread().id) ?: -1L
    private fun allocationDelta(before: Long, after: Long): Long? = if (before >= 0 &&
        after >= before
    ) {
        after - before
    } else {
        null
    }

    private fun emitSample(
        output: Path,
        corpus: Corpus,
        sample: Int,
        warmups: Int,
        tabbed: Boolean,
        result: BracketGuide?,
        vararg phases: Pair<String, Any?>,
    ) {
        val expectedAnchor = if (tabbed) corpus.tabbedAnchorLine else corpus.spaceAnchorLine
        emit(
            output, "kind" to "sample", "corpus" to corpus.name,
            "phase" to if (sample < warmups) "warmup" else "measured",
            "iteration" to if (sample < warmups) sample else sample - warmups,
            "documentVariant" to if (tabbed) "tab" else "space",
            "sha256Utf8" to
                sha256(
                    if (tabbed) {
                        corpus.source.replaceRange(
                            corpus.editOffset,
                            corpus.editOffset + 1,
                            "\t",
                        )
                    } else {
                        corpus.source
                    },
                ),
            "result" to if (result ==
                null
            ) {
                "null"
            } else {
                "exact"
            },
            "guideColumn" to result?.guideColumn, "anchorLine" to result?.anchorLine,
            "expectedGuideColumn" to corpus.guideColumn.takeUnless { corpus.refuses },
            "expectedAnchorLine" to expectedAnchor.takeUnless { corpus.refuses },
            "matchesExpectedGeometry" to
                if (corpus.refuses) {
                    result == null
                } else {
                    result != null && result.guideColumn == corpus.guideColumn &&
                        result.anchorLine == expectedAnchor
                },
            *phases,
        )
    }

    private class RepairSample(val trace: ExecutionThreads) {
        @Volatile var workerStartedNs = 0L

        @Volatile var workerFinishedNs = 0L

        @Volatile var workerReadyNs = 0L

        @Volatile var workerFinished = false

        @Volatile var result: BracketGuide? = null
        var acceptStartedNs = 0L
        var acceptFinishedNs = 0L
        var acceptAllocatedBytes: Long? = null
        var accepted = false
    }

    /** Measures inherited execution intervals, including migration, without counting suspension gaps. */
    private class ExecutionThreads(private val allocation: ThreadMXBean? = null) :
        AbstractCoroutineContextElement(Key),
        ThreadContextElement<ExecutionThreads.Segment> {
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

    private fun pairGeometry(document: Document): BracketPair {
        val closeOffset = document.textLength - 1
        return BracketPair(0, 1, closeOffset, 1, 0, 0, document.getLineNumber(closeOffset))
    }

    private fun corpora(): List<Corpus> {
        fun body(name: String, bodyLines: Int, indent: Int, refuses: Boolean = false): Corpus {
            val source = "{\n" + (" ".repeat(indent) + "value\n").repeat(bodyLines) + " ".repeat(indent) + "}"
            return Corpus(name, source, 2, bodyLines + 1, (bodyLines + 1) * (indent + 1), refuses, indent, 1, 2)
        }
        fun longIndent(name: String, indent: Int, refuses: Boolean): Corpus = Corpus(
            name,
            "{\n" + " ".repeat(indent) + "value\n        }",
            2,
            2,
            indent + 1 + 9,
            refuses,
            guideColumn = 8,
            spaceAnchorLine = 2,
            tabbedAnchorLine = 2,
        )
        return listOf(
            body("ordinary-small-body", bodyLines = 7, indent = 4),
            body("exact-256-line-budget", bodyLines = LINE_CAP - 1, indent = 8),
            body("257-line-refusal", bodyLines = LINE_CAP, indent = 8, refuses = true),
            longIndent("exact-32768-character-budget", indent = CHARACTER_CAP - 10, refuses = false),
            longIndent("32769-character-refusal", indent = CHARACTER_CAP - 9, refuses = true),
            Corpus("same-line-special-case", "{ value }", 1, 0, 0, false, 0, 0, 0),
        )
    }

    private fun allocationBean(): ThreadMXBean? =
        (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf { bean ->
            bean.isThreadAllocatedMemorySupported && runCatching {
                if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
                bean.isThreadAllocatedMemoryEnabled
            }.getOrDefault(false)
        }

    private fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun emit(output: Path, vararg fields: Pair<String, Any?>) {
        val json = (listOf<Pair<String, Any?>>("runId" to runId) + fields)
            .joinToString(prefix = "{", postfix = "}\n") { (key, value) -> "${quoted(key)}:${jsonValue(value)}" }
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

    private data class Corpus(
        val name: String,
        val source: String,
        val editOffset: Int,
        val candidateLines: Int,
        val prefixCharacters: Int,
        val refuses: Boolean,
        val guideColumn: Int,
        val spaceAnchorLine: Int,
        val tabbedAnchorLine: Int,
    )

    private companion object {
        const val TAB_SIZE = 4
        const val LINE_CAP = 256
        const val CHARACTER_CAP = 32_768
    }
}
