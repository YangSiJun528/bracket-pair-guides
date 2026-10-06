package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.reference.SynchronousAnalysisReference
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.BracketIndexes
import com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exact immutable array payload and observed release; no heap-size or timing assertions. */
class AnalysisPayloadMeasurementTest : BasePlatformTestCase() {
    private val runId = UUID.randomUUID().toString()

    fun testPayloadMeasurements() {
        if (!java.lang.Boolean.getBoolean("issue93.measure.payload")) return
        val output = Path.of(
            System.getProperty("issue93.measure.payload.output", "build/reports/issue93-payload.jsonl"),
        )
        output.parent?.let { Files.createDirectories(it) }
        val variants = System.getProperty("issue93.measure.payload.implementations", "legacy,incremental").split(',')
        require(variants.all { it == "legacy" || it == "incremental" })
        val timeoutMs = java.lang.Long.getLong("issue93.measure.payload.gcTimeoutMs", 5_000L).coerceAtLeast(1L)
        emit(
            output, "kind" to "environment", "schema" to 1,
            "revision" to System.getProperty("issue93.measure.revision", "unspecified"),
            "ideBuild" to ApplicationInfo.getInstance().build.asString(),
            "javaVersion" to System.getProperty("java.version"),
            "jvmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "),
            "scope" to
                "identity-deduplicated BracketIndexes graph; exact primitive array capacity bytes; object/array headers, padding and primitive fields excluded",
            "legacyReference" to "test-only control flow with shared current production dependencies",
            "releaseScope" to
                "completed analysis helper; explicit outcome holder cleared; separate measurement editor disposed; fixture document remains alive",
            "captureRelease" to "unobserved: capture batches are not exposed by the production result",
            "gcTimeoutMs" to timeoutMs,
        )
        val worker = Executors.newSingleThreadExecutor()
        try {
            for ((name, filename, source) in corpora()) {
                val selected = System.getProperty("issue93.measure.corpus")
                if (selected != null && selected != name) continue
                myFixture.configureByText(filename, source)
                for (variant in variants) {
                    val factory = EditorFactory.getInstance()
                    val editor = factory.createEditor(myFixture.editor.document, project) as EditorEx
                    val holder = AtomicReference<AnalysisOutcome.Complete?>()
                    try {
                        editor.setHighlighter(
                            EditorHighlighterFactory.getInstance().createEditorHighlighter(
                                project,
                                myFixture.file.fileType,
                            ),
                        )
                        val input =
                            AnalysisInput(
                                editor,
                                myFixture.file.fileType,
                                AnalysisCoverage(true, true, true),
                                emptySet(),
                            )
                        // The future returns evidence containing only counts and weak references, never the outcome.
                        val analysis = worker.submit<Evidence> { analyzeIntoHolder(input, variant, holder) }
                        awaitPerformanceEvents("payload analysis $name/$variant", 120) { analysis.isDone }
                        val evidence = analysis.get(1, TimeUnit.SECONDS)
                        emit(
                            output, "kind" to "payload", "corpus" to name, "implementation" to variant,
                            "characters" to source.length, "sha256Utf8" to sha256(source),
                            "primitiveArrayPayloadBytes" to evidence.payloadBytes,
                            "primitiveArrayCount" to evidence.arrayCount, "ownObjectCount" to evidence.objectCount,
                            "classCounts" to evidence.classCounts, "arrayCounts" to evidence.arrayCounts,
                            "staticReachableArrayCount" to evidence.staticArrays,
                        )
                        factory.releaseEditor(editor)
                        holder.set(null)
                        val release = worker.submit<Release> { observeRelease(evidence, timeoutMs) }
                        awaitPerformanceEvents("payload release $name/$variant", (timeoutMs / 1_000L + 10L).toInt()) {
                            release.isDone
                        }
                        val observation = release.get(1, TimeUnit.SECONDS)
                        emit(
                            output, "kind" to "release", "corpus" to name, "implementation" to variant,
                            "outcomeCleared" to observation.outcomeCleared,
                            "indexesCleared" to observation.indexesCleared,
                            "nonStaticArraysCleared" to observation.arraysCleared,
                            "nonStaticArraysTracked" to evidence.arrays.size,
                            "gcCollectionCountDelta" to observation.gcDelta,
                            "gcCompletionObserved" to (observation.gcDelta > 0L),
                            "polls" to observation.polls, "wallNs" to observation.wallNs,
                            "allTrackedReleased" to observation.allCleared, "timedOut" to observation.timedOut,
                            "heapUsedBytesDiagnostic" to ManagementFactory.getMemoryMXBean().heapMemoryUsage.used,
                        )
                    } finally {
                        holder.set(null)
                        if (!editor.isDisposed) factory.releaseEditor(editor)
                    }
                }
            }
        } finally {
            worker.shutdownNow()
        }
    }

    private fun analyzeIntoHolder(
        input: AnalysisInput,
        variant: String,
        holder: AtomicReference<AnalysisOutcome.Complete?>,
    ): Evidence {
        val outcome = runBlocking(Dispatchers.Default) {
            if (variant == "incremental") {
                BracketAnalysis().analyzeInBackground(input)
            } else {
                ReadAction.compute<AnalysisOutcome, RuntimeException> {
                    SynchronousAnalysisReference().analyze(input, EmptyProgressIndicator())
                }
            }
        }
        check(outcome is AnalysisOutcome.Complete) {
            "Payload measurement requires complete outcome, got ${outcome?.javaClass?.name}"
        }
        holder.set(outcome)
        val field = outcome.snapshot.javaClass.getDeclaredField("indexes").apply { isAccessible = true }
        val indexes = field.get(outcome.snapshot) as BracketIndexes
        return inspect(indexes, outcome)
    }

    private fun inspect(indexes: BracketIndexes, outcome: AnalysisOutcome.Complete): Evidence {
        val objects = IdentityHashMap<Any, Boolean>()
        val classCounts = sortedMapOf<String, Long>()
        val arrayCounts = sortedMapOf<String, Long>()
        val arrays = ArrayList<Any>()
        var payload = 0L
        fun visit(value: Any) {
            if (objects.put(value, true) != null) return
            val type = value.javaClass
            if (type.isArray) {
                val width = when (type.componentType) {
                    java.lang.Byte.TYPE, java.lang.Boolean.TYPE -> 1
                    java.lang.Short.TYPE, java.lang.Character.TYPE -> 2
                    java.lang.Integer.TYPE, java.lang.Float.TYPE -> 4
                    java.lang.Long.TYPE, java.lang.Double.TYPE -> 8
                    else -> error("Unexpected reference array at immutable payload boundary: ${type.name}")
                }
                payload += java.lang.reflect.Array.getLength(value).toLong() * width
                arrays.add(value)
                arrayCounts[type.name] = (arrayCounts[type.name] ?: 0L) + 1L
            } else {
                check(type.name in OWN_TYPES) {
                    "Unexpected reference outside own immutable payload boundary: ${type.name}"
                }
                classCounts[type.name] = (classCounts[type.name] ?: 0L) + 1L
                type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.type.isPrimitive }.forEach {
                    it.isAccessible = true
                    it.get(value)?.let(::visit)
                }
            }
        }
        visit(indexes)
        // Exclude singleton-owned arrays from release expectations, while still counting their payload.
        val staticGraph = IdentityHashMap<Any, Boolean>()
        fun visitStatic(value: Any) {
            if (staticGraph.put(value, true) != null || value.javaClass.isArray) return
            if (value.javaClass.name !in OWN_TYPES) return
            value.javaClass.declaredFields.filterNot {
                Modifier.isStatic(it.modifiers) || it.type.isPrimitive
            }.forEach {
                it.isAccessible = true
                it.get(value)?.let(::visitStatic)
            }
        }
        OWN_TYPES.forEach { name ->
            Class.forName(name).declaredFields.filter {
                Modifier.isStatic(it.modifiers) && !it.type.isPrimitive
            }.forEach {
                it.isAccessible = true
                it.get(null)?.let(::visitStatic)
            }
        }
        val nonStatic = arrays.filterNot { staticGraph.containsKey(it) }.map { WeakReference(it) }
        return Evidence(
            payload, arrays.size, classCounts.values.sum(), classCounts, arrayCounts,
            arrays.size - nonStatic.size,
            WeakReference(outcome), WeakReference(indexes), nonStatic,
        )
    }

    private fun observeRelease(evidence: Evidence, timeoutMs: Long): Release {
        val start = System.nanoTime()
        val deadline = start + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val gcBefore = gcCount()
        var polls = 0
        while (true) {
            System.gc()
            polls++
            Thread.sleep(25L)
            val outcome = evidence.outcome.get() == null
            val indexes = evidence.indexes.get() == null
            val arrays = evidence.arrays.count { it.get() == null }
            val delta = gcCount() - gcBefore
            val allCleared = outcome && indexes && arrays == evidence.arrays.size
            val timedOut = System.nanoTime() >= deadline
            if ((allCleared && delta > 0L) || timedOut) {
                return Release(outcome, indexes, arrays, delta, polls, System.nanoTime() - start, allCleared, timedOut)
            }
        }
    }

    private class Evidence(
        val payloadBytes: Long,
        val arrayCount: Int,
        val objectCount: Long,
        val classCounts: Map<String, Long>,
        val arrayCounts: Map<String, Long>,
        val staticArrays: Int,
        val outcome: WeakReference<AnalysisOutcome.Complete>,
        val indexes: WeakReference<BracketIndexes>,
        val arrays: List<WeakReference<Any>>,
    )

    private class Release(
        val outcomeCleared: Boolean,
        val indexesCleared: Boolean,
        val arraysCleared: Int,
        val gcDelta: Long,
        val polls: Int,
        val wallNs: Long,
        val allCleared: Boolean,
        val timedOut: Boolean,
    )

    private fun gcCount(): Long = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0L, it.collectionCount) }
    private fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun emit(output: Path, vararg fields: Pair<String, Any?>) {
        val json = (listOf("runId" to runId) + fields).joinToString(prefix = "{", postfix = "}\n") { (key, value) ->
            "${quote(key)}:${json(value)}"
        }
        Files.writeString(output, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun json(value: Any?): String = when (value) {
        null -> "null"

        is Number, is Boolean -> value.toString()

        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") {
            "${quote(it.key.toString())}:${json(it.value)}"
        }

        else -> quote(value.toString())
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    private fun corpora(): List<Triple<String, String, String>> = listOf(
        Triple("ordinary", "Ordinary.java", "class Ordinary {\n" + "  void run() { call(); }\n".repeat(2_000) + "}\n"),
        Triple("nested", "Nested.java", "{".repeat(20_000) + "x" + "}".repeat(20_000)),
        Triple("close-only", "Closers.java", ")]}\n".repeat(40_000)),
        Triple("large-whitespace", "Whitespace.java", "{\n" + " \t".repeat(250_000) + "x\n}\n"),
        Triple("xml", "Nested.xml", "<root>\n" + "  <item><value>x</value></item>\n".repeat(5_000) + "</root>\n"),
    )

    companion object {
        private val OWN_TYPES = setOf(
            "com.sijunyang.bracketpairguides.analysis.snapshot.BracketIndexes",
            "com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable",
            "com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex",
            "com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex",
            "com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex",
        )
    }
}
