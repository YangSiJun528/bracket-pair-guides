package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.awaitPerformanceEvents
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.editor.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Opt-in real executor measurement; only headless visibility/viewport facts are supplied by the fixture. */
internal class EditorExecutionMeasurementTest : BasePlatformTestCase() {
    fun testEditorExecutionMeasurements() = verifyMeasurements()

    private fun verifyMeasurements() {
        if (!java.lang.Boolean.getBoolean("issue93.measure.execution")) return
        val output = Path.of(
            System.getProperty("issue93.measure.execution.output", "build/reports/issue93-execution.jsonl"),
        )
        output.parent?.let { Files.createDirectories(it) }
        val runId = UUID.randomUUID().toString()
        val warmups = Integer.getInteger("issue93.measure.execution.warmups", 100).coerceAtLeast(0)
        val repeats = Integer.getInteger("issue93.measure.execution.repeats", 30).coerceAtLeast(1)
        val settings = BracketGuideSettings.getInstance()
        val previous = settings.options
        settings.loadState(BracketGuidePreferences(showActivePairBorder = true))
        emit(
            output, "runId" to runId, "kind" to "environment",
            "ideBuild" to ApplicationInfo.getInstance().build.asString(),
            "javaVersion" to System.getProperty("java.version"),
            "warmups" to warmups, "repeats" to repeats,
            "scope" to
                "actual EditorAnalysisExecution and analyzeInBackground; headless ACTIVE visibility and full-document viewport supplied",
            "acceptanceMetric" to
                "request-to-observed canSkipAnalysis plus valid markup; includes event-pump observation cadence",
            "secondaryDebounce" to "unchanged production non-main delay, currently75ms",
            "cacheMiss" to "dispose accepted session and append a newline before every request",
        )
        val source = "class ExecutionMeasure {\n  void run() {\n" +
            "    call(new int[] { 1, 2 });\n".repeat(256) + "  }\n}\n"
        try {
            for (secondary in listOf(false, true)) {
                myFixture.configureByText("ExecutionMeasure.java", source)
                // BasePlatformTestCase does not promise the fixture editor's surface kind.
                // Both modes use real registered viewers with an explicit production classifier input.
                val editor = EditorFactory.getInstance().createViewer(
                    myFixture.editor.document,
                    project,
                    if (secondary) EditorKind.PREVIEW else EditorKind.MAIN_EDITOR,
                ).also {
                    (it as EditorEx).setHighlighter(
                        EditorHighlighterFactory.getInstance().createEditorHighlighter(
                            project,
                            myFixture.file.fileType,
                        ),
                    )
                }
                try {
                    measureEditor(editor, secondary, output, runId, source, warmups, repeats)
                } finally {
                    EditorGuideSessions.dispose(editor)
                    if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
                }
            }
        } finally {
            settings.loadState(previous)
        }
    }

    private fun measureEditor(
        editor: Editor,
        secondary: Boolean,
        output: Path,
        runId: String,
        source: String,
        warmups: Int,
        repeats: Int,
    ) {
        val app = ApplicationManager.getApplication()
        val expectedKind = if (secondary) EditorKind.PREVIEW else EditorKind.MAIN_EDITOR
        val expectedCapabilities = if (secondary) EditorCapabilities.COLORS_ONLY else EditorCapabilities.MAIN
        val expectedCoverage = if (secondary) {
            AnalysisCoverage(
                true,
                false,
                false,
            )
        } else {
            AnalysisCoverage(true, true, true)
        }
        val actualCapabilities = EditorSurfaceClassifier.capabilities(editor)
        check(editor.editorKind == expectedKind && actualCapabilities == expectedCapabilities) {
            "Surface mismatch: expected kind=$expectedKind capabilities=$expectedCapabilities; " +
                "actual kind=${editor.editorKind} capabilities=$actualCapabilities viewer=${editor.isViewer} " +
                "oneLine=${editor.isOneLineMode}"
        }
        val ownedJob = SupervisorJob()
        val scope = CoroutineScope(ownedJob + Dispatchers.Default)
        val analysis = BracketAnalysis()
        val repair = GuideRepairExecution(scope)
        val armed = AtomicReference<Sample?>()
        val execution = EditorAnalysisExecution(
            scope,
            { input ->
                val sample = checkNotNull(armed.get())
                sample.calls.incrementAndGet()
                sample.input.set(input)
                check(!app.isDispatchThread && !app.isReadAccessAllowed)
                sample.analysisStart.set(System.nanoTime())
                try {
                    check(input.coverage == expectedCoverage) {
                        "Analysis coverage mismatch: kind=${editor.editorKind} capabilities=$actualCapabilities; " +
                            "expected=$expectedCoverage actual=${input.coverage}"
                    }
                    analysis.analyzeInBackground(input).also { result ->
                        check(result is AnalysisOutcome.Complete) { "Measurement requires a complete actual analysis" }
                        sample.outcome.set(result)
                    }
                } catch (failure: Throwable) {
                    sample.failure.set(failure)
                    throw failure
                } finally {
                    sample.analysisFinish.set(System.nanoTime())
                }
            },
            activity = { EditorActivity.ACTIVE },
            visibleRange = { TextRange(0, it.document.textLength) },
            stickySourceRanges = { emptyList() },
            repairExecution = { repair },
        )
        // This frozen Java source has no comments or strings containing bracket characters.
        // Precompute expected ranges outside the request-to-publication measurement interval.
        val expectedTokenOffsets = source.indices.filter { source[it] in "()[]{}" }
        check(expectedTokenOffsets.size == EXPECTED_TOKEN_MARKS) { "Frozen Java corpus endpoint count changed" }
        val mode = if (secondary) "secondary-preview" else "main"
        emit(
            output,
            "runId" to runId,
            "kind" to "corpus",
            "mode" to mode,
            "editorKind" to editor.editorKind.name,
            "fixtureEditorKind" to myFixture.editor.editorKind.name,
            "capabilities" to actualCapabilities,
            "expectedCoverage" to expectedCoverage,
            "initialCharacters" to source.length,
            "sha256Utf8" to sha256(source),
            "expectedTokenMarks" to EXPECTED_TOKEN_MARKS,
        )
        try {
            repeat(warmups + repeats) { index ->
                // No session remains for the edit listener to schedule another request.
                EditorGuideSessions.dispose(editor)
                WriteCommandAction.runWriteCommandAction(project) {
                    editor.document.insertString(editor.document.textLength, "\n")
                }
                editor.caretModel.moveToOffset(source.indexOf("call") + 1)
                val sample = Sample()
                armed.set(sample)
                val requested = System.nanoTime()
                execution.request(editor)
                val requestReturned = System.nanoTime()
                var observed = 0L
                val pump = awaitPerformanceEvents("editor execution accepted", 120) {
                    sample.failure.get()?.let { throw it }
                    val input = sample.input.get()
                    val accepted = input != null && sample.outcome.get() != null &&
                        EditorGuideSessions.canSkipAnalysis(editor, input.stamp) &&
                        editor.observedBracketMarkup().tokenMarks.isNotEmpty()
                    if (accepted && observed == 0L) observed = System.nanoTime()
                    accepted
                }
                check(sample.calls.get() == 1) { "Every sample must execute exactly one uncached calculation" }
                check(sample.analysisFinish.get() >= sample.analysisStart.get() && sample.analysisStart.get() != 0L)
                val marks = editor.observedBracketMarkup()
                val publicationFacts = "mode=$mode kind=${editor.editorKind} capabilities=$actualCapabilities " +
                    "coverage=${sample.input.get()?.coverage} tokens=${marks.tokenMarks.size} " +
                    "guides=${marks.guideMarks.size} activePair=${marks.activePairMarks.size}"
                check(marks.tokenMarks.size == EXPECTED_TOKEN_MARKS) {
                    "Every Java bracket endpoint must be colored: expected $EXPECTED_TOKEN_MARKS; $publicationFacts"
                }
                check(marks.tokenMarks.map { it.startOffset }.sorted() == expectedTokenOffsets) {
                    "Published token ranges must match every frozen Java bracket endpoint; $publicationFacts"
                }
                check(marks.tokenMarks.all { it.isValid && it.endOffset == it.startOffset + 1 }) {
                    "Published tokens must be valid single-character ranges; $publicationFacts"
                }
                check(sample.input.get()?.coverage == expectedCoverage) {
                    "Published analysis must retain the expected surface coverage=$expectedCoverage; $publicationFacts"
                }
                if (secondary) {
                    check(marks.guideMarks.isEmpty() && marks.activePairMarks.isEmpty()) {
                        "PREVIEW must publish token decorations only; $publicationFacts"
                    }
                } else {
                    check(marks.guideMarks.isNotEmpty() && marks.activePairMarks.isNotEmpty()) {
                        "MAIN_EDITOR must publish its guide and active pair; $publicationFacts"
                    }
                }
                if (index >= warmups) {
                    emit(
                        output, "runId" to runId, "kind" to "sample", "mode" to mode,
                        "iteration" to index - warmups, "characters" to editor.document.textLength,
                        "editorKind" to editor.editorKind.name, "coverage" to sample.input.get()?.coverage,
                        "requestEdtNs" to requestReturned - requested,
                        "requestToAnalysisStartNs" to sample.analysisStart.get() - requested,
                        "analysisNs" to sample.analysisFinish.get() - sample.analysisStart.get(),
                        "requestToObservedAcceptanceNs" to observed - requested,
                        "analysisFinishToObservedAcceptanceNs" to observed - sample.analysisFinish.get(),
                        "tokenMarks" to marks.tokenMarks.size, "guideMarks" to marks.guideMarks.size,
                        "activePairMarks" to marks.activePairMarks.size, "analysisCalls" to sample.calls.get(),
                        "pumpChecks" to pump.checks, "pumpEvents" to pump.events, "pumpParks" to pump.parks,
                        "pumpParkedNs" to pump.parkedNs, "pumpMaximumCheckGapNs" to pump.maximumCheckGapNs,
                    )
                }
            }
        } finally {
            Disposer.dispose(execution)
            Disposer.dispose(repair)
            ownedJob.cancel()
            awaitPerformanceEvents("editor measurement shutdown", 120) { ownedJob.isCompleted }
            EditorGuideSessions.dispose(editor)
        }
    }

    companion object {
        // Class/method braces plus run() parentheses; each call adds (), [], and initializer braces.
        private const val EXPECTED_TOKEN_MARKS = 6 + 256 * 6
    }

    private class Sample {
        val calls = AtomicInteger()
        val input = AtomicReference<AnalysisInput?>()
        val outcome = AtomicReference<AnalysisOutcome?>()
        val failure = AtomicReference<Throwable?>()
        val analysisStart = AtomicLong()
        val analysisFinish = AtomicLong()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun emit(output: Path, vararg fields: Pair<String, Any?>) {
        val line = fields.joinToString(prefix = "{", postfix = "}\n") { (key, value) ->
            "${quoted(key)}:" +
                when (value) {
                    null -> "null"
                    is Number, is Boolean -> value.toString()
                    else -> quoted(value.toString())
                }
        }
        Files.writeString(output, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r") + "\""
}
