package com.sijunyang.bracketpairguides.analysis.intellij

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.awaitPerformanceEvents
import com.sijunyang.bracketpairguides.analysis.pairing.BracketRecognitionLimits
import com.sijunyang.bracketpairguides.analysis.pairing.BracketGroupId
import com.sijunyang.bracketpairguides.analysis.pairing.CapturedBracketTokens
import com.sijunyang.bracketpairguides.analysis.pairing.TokenKind
import com.sijunyang.bracketpairguides.analysis.pairing.core.CancellationProbe
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Opt-in lifetime evidence, never a CI collection/timing/total-heap assertion. */
class CaptureReleaseMeasurementTest : BasePlatformTestCase() {
    fun testConsumedCaptureMeasurements() = verifyMeasurements()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun verifyMeasurements() {
        if (!java.lang.Boolean.getBoolean("issue93.measure.captureRelease")) return
        val output = Path.of(System.getProperty("issue93.measure.captureRelease.output", "build/reports/issue93-capture-release.jsonl"))
        output.parent?.let { Files.createDirectories(it) }
        val runId = UUID.randomUUID().toString()
        val timeoutMs = java.lang.Long.getLong("issue93.measure.captureRelease.gcTimeoutMs", 5000L).coerceAtLeast(1)
        val units = System.getProperty("issue93.measure.captureRelease.units", "2000,20000,100000").split(',').map { it.toInt().also { count -> require(count > 0) } }
        emit(output, "runId" to runId, "kind" to "environment", "ideBuild" to ApplicationInfo.getInstance().build.asString(),
            "scope" to "real bounded token capture under readAction; primitive-ID pairing outside read; adapter/session/editor/document intentionally retained during observed GC",
            "arrayScope" to "allowlisted direct nonstatic batch array fields; nonempty arrays weakly tracked; int capacity bytes and reference slots reported separately; headers/padding excluded",
            "contextScope" to "weak String identities from contexts array; sharing/interner ownership not inferred; classifier scratch may retain final context; raw survivors reported",
            "gcTimeoutMs" to timeoutMs)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        try {
            for (language in listOf("java", "xml")) for (count in units) {
                val source = if (language == "java") ")] }\n".replace(" ", "").repeat(count) else buildString { repeat(count) { append("</tag").append(it).append(">\n") } }
                myFixture.configureByText("Closers.$language", source)
                val input = AnalysisInput(myFixture.editor, myFixture.file.fileType, AnalysisCoverage(true, true, true), emptySet())
                val worker = scope.async { captureAll(input) }
                awaitPerformanceEvents("close-only capture $language/$count", 120) { worker.isCompleted }
                val retained = worker.getCompleted()
                val evidence = retained.evidence
                val release = scope.async { observeRelease(retained, timeoutMs) }
                awaitPerformanceEvents("consumed batch release $language/$count", (timeoutMs / 1000 + 10).toInt()) { release.isCompleted }
                val observation = release.getCompleted()
                emit(output, "runId" to runId, "kind" to "sample", "language" to language, "units" to count,
                    "characters" to source.length, "sha256Utf8" to sha256(source), "chunks" to evidence.chunks,
                    "visitedTokens" to evidence.visited, "capturedOccurrences" to evidence.tokens, "completedPairs" to 0,
                    "maximumBatchTokens" to evidence.maximumTokens, "maximumBatchIntCapacityBytes" to evidence.maximumInts,
                    "maximumBatchReferenceSlots" to evidence.maximumReferences, "trackedReferences" to evidence.refs.size,
                    "trackedBatchCount" to evidence.refs.count { it.kind == "batch" }, "trackedArrayCount" to evidence.refs.count { it.kind == "array" },
                    "trackedContextReferences" to evidence.refs.count { it.kind == "context" },
                    "lastClassifiedContextReference" to evidence.lastContextId,
                    "nonClearedBatches" to observation.survivors.count { it.startsWith("batch:") },
                    "nonClearedArrays" to observation.survivors.count { it.startsWith("array:") },
                    "nonClearedContexts" to observation.survivors.count { it.startsWith("context:") },
                    "gcCollectionCountDelta" to observation.gc,
                    "gcCompletionObserved" to (observation.gc > 0), "polls" to observation.polls, "wallNs" to observation.wallNs,
                    "nonClearedRaw" to observation.survivors, "allObservedReleased" to observation.survivors.isEmpty(), "timedOut" to observation.timedOut)
                Reference.reachabilityFence(retained)
            }
        } finally {
            scope.cancel()
            awaitPerformanceEvents("capture release measurement shutdown", 120) { job.isCompleted }
        }
    }

    private suspend fun captureAll(input: AnalysisInput): Retained {
        val context = currentCoroutineContext()
        val checkCanceled = { context.ensureActive(); ProgressManager.checkCanceled() }
        val adapter = readAction { BracketTokenCapture(input) }
        val draft = PairTable.draft()
        val session = PairingMachine<TokenKind, BracketGroupId>().newSession(draft, CancellationProbe(checkCanceled), BracketRecognitionLimits.MAXIMUM_PENDING_OPENS)
        val evidence = Evidence()
        var offset = 0
        do { offset = consumeChunk(adapter, session, offset, evidence, checkCanceled) } while (offset >= 0)
        check(draft.freeze().isEmpty) { "Corpus must produce zero pairs" }
        return Retained(adapter, session, evidence)
    }

    /** Returning only the next offset ends all strong batch/value-view locals before GC observation. */
    private suspend fun consumeChunk(adapter: BracketTokenCapture, session: PairingMachine<TokenKind, BracketGroupId>.Session,
        offset: Int, evidence: Evidence, checkCanceled: () -> Unit): Int {
        val batch = readAction { adapter.capture(offset, checkCanceled = checkCanceled) }
        check(!ApplicationManager.getApplication().isReadAccessAllowed)
        check(batch.sourceAvailable)
        inspect(batch, evidence)
        for (index in 0 until batch.size) {
            checkCanceled()
            var step = batch.begin(index, session)
            while (step == PairingMachine.Step.NEEDS_RULE) {
                val request = session.ruleRequest()
                step = session.resume(readAction { adapter.matches(request) })
            }
            check(step == PairingMachine.Step.ACCEPTED)
        }
        return if (batch.end) -1 else batch.nextOffset
    }

    private fun inspect(batch: CapturedBracketTokens, evidence: Evidence) {
        val id = evidence.chunks++
        evidence.visited += batch.visitedTokens
        evidence.tokens += batch.size
        evidence.maximumTokens = maxOf(evidence.maximumTokens, batch.size)
        evidence.refs += Tracked("batch:$id", "batch", WeakReference(batch))
        if (batch.size > 0) evidence.lastContextId = null
        var ints = 0L
        var slots = 0L
        val fields = CapturedBracketTokens::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type.isArray }
        check(fields.map { it.name }.toSet() == setOf("kinds", "groups", "contexts", "geometry")) { "Review capture payload allowlist before measuring changed fields" }
        for (field in fields) {
            field.isAccessible = true
            val array = field.get(batch) ?: continue
            val length = java.lang.reflect.Array.getLength(array)
            if (array is IntArray) ints += length * 4L else slots += length
            if (length == 0) continue
            evidence.refs += Tracked("array:$id:${field.name}:$length", "array", WeakReference(array))
            if (field.name == "contexts") (array as Array<*>).forEachIndexed { index, value ->
                if (value != null) {
                    check(value is String)
                    val contextId = "context:$id:$index:length=${value.length}"
                    evidence.refs += Tracked(contextId, "context", WeakReference(value))
                    if (index == batch.size - 1) evidence.lastContextId = contextId
                }
            }
        }
        evidence.maximumInts = maxOf(evidence.maximumInts, ints)
        evidence.maximumReferences = maxOf(evidence.maximumReferences, slots)
    }

    private fun observeRelease(retained: Retained, timeoutMs: Long): Observation {
        check(!ApplicationManager.getApplication().isReadAccessAllowed)
        val started = System.nanoTime()
        val before = gcCount()
        var polls = 0
        while (true) {
            System.gc()
            Thread.sleep(25)
            polls++
            val survivors = retained.evidence.refs.filter { it.reference.get() != null }.map { it.id }
            val gc = gcCount() - before
            val timedOut = System.nanoTime() - started >= timeoutMs * 1_000_000
            Reference.reachabilityFence(retained.adapter)
            Reference.reachabilityFence(retained.session)
            if ((survivors.isEmpty() && gc > 0) || timedOut) return Observation(survivors, gc, polls, System.nanoTime() - started, timedOut)
        }
    }

    private class Retained(val adapter: BracketTokenCapture, val session: PairingMachine<TokenKind, BracketGroupId>.Session, val evidence: Evidence)
    private class Evidence {
        val refs = ArrayList<Tracked>()
        var lastContextId: String? = null
        var chunks = 0; var visited = 0L; var tokens = 0L; var maximumTokens = 0
        var maximumInts = 0L; var maximumReferences = 0L
    }
    private data class Tracked(val id: String, val kind: String, val reference: WeakReference<out Any>)
    private data class Observation(val survivors: List<String>, val gc: Long, val polls: Int, val wallNs: Long, val timedOut: Boolean)
    private fun gcCount() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0L, it.collectionCount) }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun emit(output: Path, vararg fields: Pair<String, Any?>) {
        Files.writeString(output, fields.joinToString(prefix = "{", postfix = "}\n") { (key, value) -> "${json(key)}:${json(value)}" }, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
        else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
